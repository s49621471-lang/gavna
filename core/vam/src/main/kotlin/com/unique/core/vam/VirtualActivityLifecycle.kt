package com.unique.core.vam

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.diagnostics.Diagnostics

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
 *  - **`FLAG_HARDWARE_ACCELERATED`.** See [applyHardwareAcceleration]: the substituted
 *    `ActivityInfo` carries the bit, and on an Android 14 device the window still came up
 *    without it, so the window is told directly through the API that exists for saying so.
 *
 * Both are applied from `Application.ActivityLifecycleCallbacks`, which is the guest's own
 * `Application` — the callbacks run for its activities and nothing else in the process.
 *
 * ## What is deliberately *not* done here
 *
 * `android:configChanges` has the same shape of problem — `ActivityRecord.shouldRelaunchLocked`
 * reads the stub's, and the stub declares every change it can, so a guest that did not
 * declare `orientation` is never relaunched on rotation and keeps the layout it loaded in
 * the other one. Calling `Activity.recreate()` for an undeclared change looks like the
 * obvious answer and was tried: in the acceptance suite it resurrected an activity from
 * an earlier test, that activity re-applied its landscape orientation, the display rotated
 * twice around a permission request, and the activity waiting for the result was destroyed
 * before it arrived. A stale layout after a rotation is a cosmetic fault; losing an
 * activity result is not. It stays a known limit until there is a way to do it that does
 * not touch an activity with work in flight.
 */
internal object VirtualActivityLifecycle {

    @Volatile private var installed = false

    @Synchronized
    fun install(ready: AppBootstrap.Result.Ready) {
        if (installed) return
        ready.application.registerActivityLifecycleCallbacks(callbacks(ready))
        installed = true
        Diagnostics.info(
            DiagChannel.LAUNCH, "ACTIVITY_LIFECYCLE_INSTALLED",
            mapOf("package" to ready.params.packageName),
        )
    }

    @Synchronized
    fun reset() {
        installed = false
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
                applyHardwareAcceleration(activity, entry)
                applyOrientation(activity, entry)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    /**
     * Turns the hardware renderer on for the guest's window, explicitly.
     *
     * The `ActivityInfo` UNIQUE substitutes carries `FLAG_HARDWARE_ACCELERATED` now, and
     * that is where `Activity.attach` reads it:
     *
     * ```java
     * mWindow.setWindowManager(…, (info.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0);
     * ```
     *
     * On an Android 14 device that was still not enough. The launch item is rewritten
     * before `ActivityThread` acts on it and the guest's `ActivityInfo` reports
     * `flags=512` when the guest asks its own `PackageManager` — and the window came up
     * without the flag anyway:
     *
     * ```
     * activityInfoFlags=512  activityInfoHardwareAccelerated=true
     * windowFlags=0x80810100 windowHardwareAccelerated=false
     * ```
     *
     * — against `LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED
     * DRAWS_SYSTEM_BAR_BACKGROUNDS` for an ordinary installed app on the same device. So
     * the window is told directly, through the public API that exists for saying exactly
     * this, before the guest's `onCreate` and therefore before `setContentView` builds the
     * decor. `Window.setFlags` also records it as a *forced* flag, so `generateLayout`
     * cannot clear it afterwards.
     *
     * The `ActivityInfo` keeps the bit as well. It is what a guest reading its own
     * manifest sees, and it is what the platform would use if a release moves this
     * decision somewhere else.
     */
    private fun applyHardwareAcceleration(activity: Activity, entry: ComponentEntry) {
        if (!entry.window.hardwareAccelerated) return
        val flag = android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val already = (activity.window.attributes.flags and flag) != 0
        val applied = already || runCatching { activity.window.setFlags(flag, flag) }.isSuccess
        Diagnostics.event(
            DiagChannel.LAUNCH,
            if (applied) DiagLevel.INFO else DiagLevel.WARN,
            "ACTIVITY_HARDWARE_ACCELERATED",
            mapOf(
                "activity" to entry.className,
                "fromActivityInfo" to already.toString(),
                "applied" to applied.toString(),
            ),
        )
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
