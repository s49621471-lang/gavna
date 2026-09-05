package com.unique.core.vam

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.diagnostics.Diagnostics
import java.lang.ref.WeakReference

/**
 * Gives a guest activity the window behaviour its own manifest asked for.
 *
 * Two attributes cannot be delivered by substituting the `ActivityInfo`, because the
 * platform has already used its own copy — the *stub's* — before any UNIQUE code runs in
 * the launch:
 *
 *  - **`android:screenOrientation`.** `WindowManager` takes the orientation from the
 *    `ActivityRecord` it built in `system_server` from the stub's manifest entry, where it
 *    is `unspecified`. So a landscape game opened in a portrait phone and stayed there,
 *    which is what "игры запускаются вертикально" was. `setRequestedOrientation` is the
 *    same call an app makes at runtime and it reaches the same record, so this is not a
 *    workaround: it is the API for saying exactly this.
 *
 *  - **`android:configChanges`.** `ActivityRecord.shouldRelaunchLocked` reads the stub's,
 *    and the stub declares every change it can (it has to: the stub pool cannot know what
 *    a guest will want). The result is that a guest which did *not* declare, say,
 *    `orientation` is never relaunched on rotation — it just keeps the layout it loaded in
 *    the other orientation. The platform's own answer to that situation is
 *    `Activity.recreate()`, so a change the guest did not declare produces one.
 *
 * Both are applied from `Application.ActivityLifecycleCallbacks`, which is the guest's own
 * `Application` — the callbacks run for its activities and nothing else in the process.
 */
internal object VirtualActivityLifecycle {

    @Volatile private var installed = false

    /** Every live guest activity, with the manifest entry it was launched from. */
    private val live = ArrayList<Tracked>()

    private class Tracked(activity: Activity, val entry: ComponentEntry) {
        val ref = WeakReference(activity)
        /** The configuration this activity last saw, for diffing. */
        var config: Configuration = Configuration(activity.resources.configuration)
    }

    @Synchronized
    fun install(ready: AppBootstrap.Result.Ready) {
        if (installed) return
        val application = ready.application
        application.registerActivityLifecycleCallbacks(callbacks(ready))
        application.registerComponentCallbacks(configWatcher(ready))
        installed = true
        Diagnostics.info(
            DiagChannel.LAUNCH, "ACTIVITY_LIFECYCLE_INSTALLED",
            mapOf("package" to ready.params.packageName),
        )
    }

    @Synchronized
    fun reset() {
        installed = false
        live.clear()
    }

    private fun callbacks(ready: AppBootstrap.Result.Ready) =
        object : Application.ActivityLifecycleCallbacks {

            /**
             * Runs before the guest's own `onCreate`, which is the only useful moment:
             * a game reads the display size while it is creating its surface, and an
             * orientation applied afterwards makes it read the wrong one and letterbox
             * itself for the rest of the session.
             */
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                val entry = entryFor(ready, activity) ?: return
                synchronized(this@VirtualActivityLifecycle) {
                    live.removeAll { it.ref.get() == null || it.ref.get() === activity }
                    live += Tracked(activity, entry)
                }
                applyOrientation(activity, entry)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(this@VirtualActivityLifecycle) {
                    live.removeAll { it.ref.get() == null || it.ref.get() === activity }
                }
            }
        }

    private fun applyOrientation(activity: Activity, entry: ComponentEntry) {
        val orientation = entry.screenOrientation
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
        // The stub is `unspecified`, so anything the guest declared is a change. Asking
        // for it again after a recreate costs one Binder call and keeps the answer right
        // when the platform has reset the record.
        val applied = runCatching { activity.requestedOrientation = orientation }.isSuccess
        Diagnostics.event(
            DiagChannel.LAUNCH,
            if (applied) DiagLevel.INFO else DiagLevel.WARN,
            "ACTIVITY_ORIENTATION_APPLIED",
            mapOf(
                "activity" to entry.className,
                "orientation" to orientation.toString(),
                "applied" to applied.toString(),
            ),
        )
    }

    /**
     * Recreates the activities whose guest manifest does not declare the change.
     *
     * Only the bits the guest did *not* claim are considered, and only the ones that
     * actually invalidate resources — a change nobody reloads for would turn every
     * keyboard slide into a restart.
     */
    private fun configWatcher(ready: AppBootstrap.Result.Ready) = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val snapshot = synchronized(this@VirtualActivityLifecycle) { live.toList() }
            for (tracked in snapshot) {
                val activity = tracked.ref.get() ?: continue
                val changed = tracked.config.diff(newConfig)
                tracked.config = Configuration(newConfig)
                val undeclared = changed and tracked.entry.configChanges.inv() and RECREATE_ON
                if (undeclared == 0) continue
                Diagnostics.info(
                    DiagChannel.LAUNCH, "ACTIVITY_RECREATED_FOR_CONFIG",
                    mapOf(
                        "package" to ready.params.packageName,
                        "activity" to tracked.entry.className,
                        "changed" to "0x${Integer.toHexString(changed)}",
                        "declared" to "0x${Integer.toHexString(tracked.entry.configChanges)}",
                        "undeclared" to "0x${Integer.toHexString(undeclared)}",
                    ),
                )
                runCatching { activity.recreate() }
            }
        }

        override fun onLowMemory() = Unit
    }

    /**
     * The changes an app that did not declare them expects to be restarted for.
     *
     * Deliberately narrow. `CONFIG_FONT_SCALE` is excluded because the platform reports it
     * on changes an app never asked about, and a restart loop is far worse than a stale
     * font metric.
     */
    private val RECREATE_ON = ActivityInfo.CONFIG_ORIENTATION or
        ActivityInfo.CONFIG_SCREEN_SIZE or
        ActivityInfo.CONFIG_SCREEN_LAYOUT or
        ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
        ActivityInfo.CONFIG_DENSITY or
        ActivityInfo.CONFIG_LOCALE or
        ActivityInfo.CONFIG_UI_MODE or
        ActivityInfo.CONFIG_KEYBOARD_HIDDEN

    /**
     * The manifest entry an activity was launched from.
     *
     * The intent's component is tried first because it is the only thing that survives an
     * `<activity-alias>`: the platform instantiates the *target* class, so the class name
     * alone cannot tell which alias — and which alias's orientation — was asked for.
     */
    private fun entryFor(ready: AppBootstrap.Result.Ready, activity: Activity): ComponentEntry? {
        val components = ready.manifest.components
        val fromIntent = activity.intent?.component?.className
        if (fromIntent != null) {
            components.firstOrNull {
                (it.kind == ComponentKind.ACTIVITY || it.kind == ComponentKind.ACTIVITY_ALIAS) &&
                    it.className == fromIntent
            }?.let { return it }
        }
        val className = activity.javaClass.name
        return components.firstOrNull {
            it.kind == ComponentKind.ACTIVITY && it.className == className
        }
    }
}
