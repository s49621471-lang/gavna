package com.unique.core.vam

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.unique.core.common.apk.Abi
import android.webkit.WebView
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.google.GoogleStackVisibility
import com.unique.core.common.nativelib.GuestNativeExclusions
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.common.profile.DeviceProfileCodec
import com.unique.core.vprofile.DeviceProfileProvider
import com.unique.core.diagnostics.CrashGuard
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.HiddenApi
import com.unique.core.hook.Reflect
import com.unique.core.nativebridge.UniqueNative
import com.unique.core.vstorage.VirtualStorage
import java.io.File
import java.lang.ref.WeakReference

/**
 * Grafts a virtual package onto the running `ActivityThread`.
 *
 * This is the piece that makes an uninstalled package behave like an installed one. The
 * platform builds every app-facing value - package name, data directory, resources, class
 * loader - from a `LoadedApk`, which it derives from an `ApplicationInfo`. So UNIQUE
 * constructs an `ApplicationInfo` describing the virtual package, asks `ActivityThread`
 * for a `LoadedApk` built from it, and installs that `LoadedApk` where the framework
 * looks for it.
 *
 * Nothing here is patched by argument index or by a version-specific signature: methods
 * are resolved by name and fields by *type*, for the same reason `MethodShim` exists.
 *
 * Bootstrap is idempotent and happens at most once per process: a `:vappN` slot serves
 * exactly one (instance, manifest process) pair for its lifetime.
 */
object AppBootstrap {

    sealed interface Result {
        data class Ready(
            val params: VirtualLaunchParams,
            val manifest: ApkManifest,
            val application: Application,
            val applicationInfo: ApplicationInfo,
        ) : Result

        data class Failed(val code: String, val message: String, val cause: Throwable? = null) : Result
    }

    @Volatile private var bootstrapped: Result.Ready? = null

    @Volatile private var hostPackage: String? = null

    /** UNIQUE's own package name once this process has been grafted; null before. */
    val hostPackageName: String? get() = hostPackage

    @Volatile private var host: Context? = null

    /**
     * UNIQUE's own `Context` in this process, kept for the things the guest's cannot do.
     *
     * After the graft, `application` is the guest's and its `PackageManager` answers as the
     * guest — which is the point, and exactly wrong for asking the *device* a question,
     * such as whether anything installed here can serve an intent.
     */
    val hostContext: Context? get() = host

    val current: Result.Ready? get() = bootstrapped

    /** True once this process is serving a virtual package. */
    val isBootstrapped: Boolean get() = bootstrapped != null

    @Synchronized
    fun bootstrap(hostContext: Context, params: VirtualLaunchParams): Result {
        bootstrapped?.let { existing ->
            if (existing.params.vuid == params.vuid &&
                existing.params.packageName == params.packageName
            ) return existing
            // A slot must never serve two instances: their data directories differ, and a
            // second graft would leave the first instance's objects pointing at the wrong
            // one. The launch is refused instead.
            surrenderSlot(existing, params)
            return Result.Failed(
                "SLOT_ALREADY_BOUND",
                "Slot ${params.slot} already serves ${existing.params.packageName} " +
                    "(u${existing.params.vuid}); refusing to rebind to ${params.packageName}.",
            )
        }

        if (HiddenApi.ensure() != HiddenApi.State.GRANTED) {
            return Result.Failed(
                "HIDDEN_API_DENIED",
                "Platform access was refused on this device: ${HiddenApi.failureDetail}",
            )
        }

        return try {
            val result = graft(hostContext, params)
            if (result is Result.Ready) {
                bootstrapped = result
                announceReady(hostContext, params)
            }
            result
        } catch (t: Throwable) {
            // The stack as well as the line. `Diagnostics` writes one line per event,
            // which is right for events and useless for this one: the tag is the same
            // "Unique" the device capture is filtered on, so the frames travel with it.
            android.util.Log.e("Unique", "BOOTSTRAP_FAILED ${params.packageName}", t)
            Result.Failed("BOOTSTRAP_FAILED", describeFailure(t), t)
        }
    }

    /**
     * What actually went wrong, rather than what was holding it.
     *
     * The guest's `Application.onCreate` is called reflectively, so anything it throws
     * arrives wrapped. `Throwable.toString()` on the wrapper says only:
     *
     * ```
     * BOOTSTRAP_FAILED package=com.beemdevelopment.aegis code=BOOTSTRAP_FAILED
     *   message=java.lang.reflect.InvocationTargetException
     * ```
     *
     * — which names UNIQUE's calling convention and nothing about the app. A real device
     * log is often all there is, and that line ends the investigation instead of starting
     * it. The chain is unwrapped to its root, the wrappers are named in passing, and the
     * first frames of the root are carried so the line says *where*.
     */
    internal fun describeFailure(t: Throwable): String {
        val chain = ArrayList<Throwable>(4)
        var current: Throwable? = t
        while (current != null && chain.size < 8 && chain.none { it === current }) {
            chain += current
            current = current.cause
        }
        val root = chain.lastOrNull() ?: return t.toString()
        val where = root.stackTrace.take(4)
            .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        return buildString {
            append(root.toString())
            if (chain.size > 1) {
                append(" (through ")
                append(chain.dropLast(1).joinToString("/") { it.javaClass.simpleName })
                append(')')
            }
            if (where.isNotEmpty()) append(" at ").append(where)
        }
    }

    /**
     * Ends this process after a slot it holds has been given to somebody else.
     *
     * Refusing the rebind protects the running instance, but on its own it poisons the
     * slot: the pool has already committed it to [wanted], so every later launch that
     * lands here is refused for the same reason and the app never starts. That is what a
     * physical-device run looked like — three different apps in a row, each refused
     * because slot 0 still held Gemini's graft, with no way back short of force-stopping
     * UNIQUE.
     *
     * The disagreement is between UNIQUE's main process, which believes this slot is
     * free, and this process, which knows it is not. The main process is the one that
     * allocates, so this one yields: it dies, the platform starts `:vappN` clean for the
     * next attempt, and the guest that was already running here would have been stopped by
     * the reallocation anyway.
     *
     * [ProcessPool][com.unique.core.vprocess.ProcessPool] now ends a slot's process when
     * it releases it, so reaching here at all means the liveness check missed — the
     * process list lagging behind reality, which it does. This is the second line, not the
     * first, and it costs the user one tap rather than every launch from here on.
     *
     * Off the caller's thread and after a beat, so the refusal is recorded and the Binder
     * reply is sent before the process stops existing.
     */
    private fun surrenderSlot(existing: Result.Ready, wanted: VirtualLaunchParams) {
        Diagnostics.warn(
            DiagChannel.PROCESS, "SLOT_SURRENDERED",
            mapOf(
                "slot" to wanted.slot.toString(),
                "serving" to "${existing.params.packageName}/u${existing.params.vuid}",
                "wanted" to "${wanted.packageName}/u${wanted.vuid}",
                "detail" to "this process is ending so the slot can be re-grafted",
            ),
        )
        Thread({
            runCatching { Thread.sleep(SURRENDER_DELAY_MILLIS) }
            Process.killProcess(Process.myPid())
        }, "unique-slot-surrender").start()
    }

    private const val SURRENDER_DELAY_MILLIS = 250L

    /**
     * Tells UNIQUE's main process that this slot now serves this instance.
     *
     * Announced here, from the one place every graft passes through, rather than from the
     * provider path that first needed it. A process grafted for an *Activity* is just as
     * able to serve a provider, and when only the provider path announced, a caller
     * acquiring from an already-running guest waited out its whole budget before
     * proceeding anyway:
     *
     * ```
     * PROVIDER_READY_NOT_SEEN slot=0 vuid=0 waitedMillis=45005
     * PROVIDER_BIND_READY … slot=0            (2.5 seconds later)
     * ```
     *
     * Forty-five seconds of waiting for a signal nothing was going to send, on a process
     * that had been ready the whole time.
     *
     * On a thread of its own because this runs on the guest's main thread at the end of
     * `Application.onCreate`, and a Binder call into a busy `:core` would hold the guest
     * there. Nothing waits on the announcement: a caller that misses it falls back to the
     * slower path it had before, which is the correct behaviour when the message is late
     * and the wrong behaviour to block a guest's startup for.
     */
    fun announceReady(hostContext: Context, params: VirtualLaunchParams) =
        announce(hostContext, params, VirtualProviderRouter.ROUTER_METHOD_SLOT_READY)

    /**
     * Says a graft has *begun*, which is a different and equally necessary fact.
     *
     * A caller waiting on a slot re-issues the warm-up when nothing has been heard, on the
     * assumption that the process died. While a graft is running that assumption is wrong
     * and the re-issue is harmful: it queues another `onStartCommand` behind a main thread
     * that is busy for tens of seconds, and ActivityManager kills the process for `bg anr`
     * — sixteen seconds before it would have been ready.
     */
    fun announceStarting(hostContext: Context, params: VirtualLaunchParams) =
        announce(hostContext, params, VirtualProviderRouter.ROUTER_METHOD_SLOT_STARTING)

    private fun announce(hostContext: Context, params: VirtualLaunchParams, method: String) {
        // `hostPackage` is set part-way through the graft, and the *starting* announcement
        // is made before the graft begins — so on a cold process this read was null and
        // this method returned having done nothing. Which is precisely the case the
        // announcement exists for: the caller in `:core` re-warms a slot it believes is
        // dead, that start queues behind a main thread busy grafting for tens of seconds,
        // and ActivityManager kills the process it was trying to help:
        //
        //   PROVIDER_READY_NOT_SEEN slot=0 vuid=0 waitedMillis=45147 rewarms=2
        //   ANR in com.unique:vapp0  Reason: executing service …ServiceStub_p0_s6
        //   Killing 5384:com.unique:vapp0/u0a108 (adj 0): bg anr
        //
        // Before the graft the context still *is* UNIQUE's, so its package name is the
        // right answer and the only one available. Afterwards `hostPackage` is set and is
        // used, because by then the context reports the guest.
        val host = hostPackage ?: hostContext.packageName
        Thread({
            runCatching {
                hostContext.contentResolver
                    .acquireUnstableContentProviderClient(VirtualProviderRouter.routerUri(host))
                    ?.use { client ->
                        client.call(
                            method, null,
                            Bundle().apply {
                                putInt(VirtualProviderRouter.KEY_SLOT, params.slot)
                                putInt(VirtualProviderRouter.KEY_VUID, params.vuid)
                                putInt(VirtualProviderRouter.KEY_PID, Process.myPid())
                            },
                        )
                    }
            }.onFailure {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "SLOT_ANNOUNCE_FAILED",
                    mapOf(
                        "slot" to params.slot.toString(),
                        "method" to method,
                        "error" to it.toString(),
                    ),
                )
            }
        }, "unique-slot-announce").start()
    }

    private fun graft(hostContext: Context, params: VirtualLaunchParams): Result {
        val started = System.nanoTime()
        val model = VirtualPathModel(hostContext.filesDir.absolutePath)

        // The version in the launch intent can be stale, and legitimately so.
        //
        // Killing a virtual process leaves its *task* behind, and the system relaunches
        // the activity from the intent it stored - which still names the version that was
        // current when the task was created. After an update that version's directory is
        // gone, and refusing the launch would mean a user who updates an app can never
        // reopen it from Recents. The version actually on disk is the right answer.
        val requested = File(model.baseApk(params.packageName, params.versionCode))
        val baseApk = if (requested.isFile) requested else substituteVersion(model, params)
        if (baseApk == null || !baseApk.isFile) {
            return Result.Failed("APK_MISSING", "${requested.path} does not exist.")
        }
        val effectiveVersion = baseApk.parentFile?.name?.toLongOrNull() ?: params.versionCode
        val effective = if (effectiveVersion == params.versionCode) params
        else params.copy(versionCode = effectiveVersion)

        // The manifest is read here rather than passed in, so a virtual process needs no
        // round trip to :server before it can start. It costs a few milliseconds.
        val manifest = runCatching { ManifestReader.fromApk(baseApk) }.getOrElse {
            return Result.Failed("MANIFEST_UNREADABLE", it.toString(), it)
        }

        val appInfo = buildApplicationInfo(model, manifest, effective)

        val activityThreadClass = Reflect.findClass("android.app.ActivityThread")
            ?: return Result.Failed("NO_ACTIVITY_THREAD", "android.app.ActivityThread not found")
        val activityThread = Reflect.findMethod(activityThreadClass, "currentActivityThread")
            ?.invoke(null)
            ?: return Result.Failed("NO_ACTIVITY_THREAD", "currentActivityThread() returned null")

        val loadedApk = makeLoadedApk(activityThreadClass, activityThread, appInfo)
            ?: return Result.Failed("NO_LOADED_APK", "getPackageInfoNoCheck did not return a LoadedApk")

        installLoadedApk(activityThreadClass, activityThread, params.packageName, loadedApk)
        rebindBoundApplication(activityThread, appInfo, loadedApk, params.processName)

        // The guest's resources exist from here on, which is what turns a meta-data
        // *reference* into the value the platform would have put in the bundle. Done
        // before anything can read `ApplicationInfo.metaData` - a provider's onCreate is
        // the earliest such reader, and Play services is the loudest.
        guestResources = runCatching {
            Reflect.findMethod(loadedApk.javaClass, "getResources")?.invoke(loadedApk)
                as? android.content.res.Resources
        }.getOrNull()
        appInfo.metaData = GuestMetaData.bundle(manifest.applicationMetaDataEntries, guestResources)

        // The instance's own identity, read from runtime/ before any guest code runs.
        // Without it every instance of every app reports the same ANDROID_ID and the same
        // Build fields, so anything that fingerprints the device sees two clones as one
        // installation - and separate identity is the point of a second instance.
        bindDeviceProfile(hostContext, effective)

        // Bound before the permission shims are installed and before the guest's
        // Application exists: an app that checks a permission in Application.onCreate -
        // and plenty do - must already get its instance's answer, not the host's.
        VirtualPermissions.bind(
            vuid = effective.vuid,
            packageName = effective.packageName,
            declared = manifest.usesPermissions,
            defined = manifest.declaredPermissions,
            context = hostContext,
        )

        // Must precede makeApplication: LoadedApk.initializeJavaContextClassLoader() asks
        // the real PackageManagerService for this package and throws when it is not
        // installed - which, for an app UNIQUE imported rather than installed, it is not.
        VirtualPackageManagerHook.hostPackageName = hostContext.packageName
        // Kept because after the graft nothing else in this process can answer "what is
        // UNIQUE called": `context.packageName` reports the guest, which is the point.
        // Anything addressing UNIQUE's own components - the router provider, the stubs -
        // needs the real name.
        hostPackage = hostContext.packageName
        host = hostContext.applicationContext ?: hostContext
        val pmHooked = VirtualPackageManagerHook.install(
            packageName = params.packageName,
            manifest = manifest,
            applicationInfo = appInfo,
            components = guestComponents(manifest, appInfo, params),
            hostContext = hostContext,
            apkPath = baseApk.absolutePath,
        )
        if (!pmHooked) {
            return Result.Failed(
                "VPM_HOOK_FAILED",
                "could not virtualize PackageManager; the platform would refuse to load " +
                    "${params.packageName} because it is not installed on this device",
            )
        }

        // Also a prerequisite: every framework call the guest makes carries its package
        // name outward, and system_server checks that against UNIQUE's real uid. Without
        // this, the very first call fails - PhoneWindow's constructor reads a setting,
        // which acquires a content provider, which is rejected.
        if (!VirtualActivityManagerHook.install(params.packageName, hostContext)) {
            return Result.Failed(
                "VAM_HOOK_FAILED",
                "could not virtualize ActivityManager; every framework call from " +
                    "${params.packageName} would be rejected as a package/uid mismatch",
            )
        }

        // Activity starts made by the guest itself go to IActivityTaskManager, not
        // IActivityManager - Instrumentation.execStartActivity has called
        // ActivityTaskManager.getService() since Android 10. Hooking only the latter
        // would bind cleanly and never fire, which is how the bind path failed for a
        // week. Not fatal on its own: the first activity is launched by UNIQUE and works
        // regardless, so a failure here degrades to "the guest cannot open a second
        // screen" with a diagnostic, rather than refusing the launch outright.
        VirtualActivityTaskManagerHook.install(params.packageName, hostContext)
        VirtualPermissions.installManagerHook(params.packageName, hostContext.packageName)
        VirtualAppOpsHook.install(params.packageName, hostContext.packageName)

        // Before the guest's own code runs, and after the hooks it depends on.
        //
        // This process spent its first second as UNIQUE's own, and everything it acquired
        // then is still cached — the settings provider above all, which the framework reads
        // during `handleBindApplication` before a line of guest code exists. Those caches
        // sit in front of the wrapper that rewrites the caller's identity, so a guest
        // reading a setting gets `Package … does not belong to <uid>`. `Application.onCreate`
        // is the first place a guest reads one, so the eviction has to precede it.
        VirtualProviderCaches.evict(
            hostContext,
            VirtualActivityManagerHook.hostSourceFor(hostContext, hostContext.packageName),
        )

        // Created, but its `onCreate` deliberately *not* run yet: `makeApplication` is
        // passed a null Instrumentation, and `callApplicationOnCreate` happens at the very
        // end of this method instead. See [runApplicationOnCreate] for why.
        val (application, applicationError) = makeApplication(activityThreadClass, activityThread, loadedApk)
        if (application == null) {
            return Result.Failed(
                "NO_APPLICATION",
                "could not create ${manifest.applicationClassName ?: "android.app.Application"} " +
                    "from ${appInfo.sourceDir}: $applicationError",
            )
        }

        Reflect.set(activityThreadClass, "mInitialApplication", activityThread, application)

        // Meta-data, resolved for real this time.
        //
        // The first attempt is made as soon as the `LoadedApk` exists, because a provider's
        // `onCreate` is the earliest thing that can read `ApplicationInfo.metaData` — but
        // `LoadedApk.getResources()` that early came back null on an Android 14 device, and
        // the bundle then carried the resource *id* where the value should be:
        //
        //   metaDataNumber=2130771968      (0x7f010000, the reference itself)
        //
        // Google Play services reads exactly such an int, so an id is as wrong as nothing.
        // The Application's own `Resources` are the guest's by construction, and this runs
        // before its `onCreate` and before its providers.
        guestResources = runCatching { (application as Application).resources }.getOrNull()
            ?: guestResources
        appInfo.metaData = GuestMetaData.bundle(manifest.applicationMetaDataEntries, guestResources)

        // Whether this guest is told the device has Google Play services.
        //
        // Visible unless *this instance* has already died of the one refusal that kills
        // an old Play services client — which the crash handler below records, because a
        // crash is the last moment anything in the process can write a file. Before
        // `Application.onCreate` and the providers, because that is where a Play services
        // SDK initialises. See GoogleStackVisibility, including the rule this replaced
        // and why the version number it read cannot say what it looked like it said.
        val visibilityFile = File(
            VirtualPathModel(
                (hostContext.applicationContext ?: hostContext).filesDir.absolutePath,
            ).googleVisibilityFile(params.vuid, params.packageName),
        )
        runCatching {
            VirtualPackageManagerHook.bindGoogleVisibility(
                GoogleStackVisibility.decide(readVisibilityOverride(visibilityFile)),
            )
            installGoogleCrashObserver(visibilityFile, params)
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "GOOGLE_VISIBILITY_UNDECIDED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // The guest's own network security policy, replacing the one the platform installed
        // for UNIQUE before this process was anybody. Cleartext rules and pinning are the
        // guest's decisions; running its traffic under the host's policy is wrong even when
        // nothing crashes, and on Android 15 something does.
        runCatching {
            VirtualNetworkSecurity.install(
                (application as android.app.Application).applicationContext ?: hostContext,
                appInfo,
                manifest.networkSecurityConfigResId,
            )
        }

        VirtualServiceRouter.bindSlot(params.slot)

        val ready = Result.Ready(effective, manifest, application, appInfo)

        // Native IO redirection, installed once the guest's Application exists.
        //
        // The timing is the whole design. Libraries are hooked by walking what is loaded
        // *now*, so this must run after the guest's static initialisers and
        // Application.onCreate - where apps overwhelmingly call System.loadLibrary - and
        // it is idempotent so a later re-run costs nothing. A library the guest loads
        // after this point is not covered until the next install; that limit is recorded
        // rather than papered over.
        installIoRedirection(hostContext, effective, appInfo)

        // Must happen before any guest code can touch a WebView, and cannot be undone
        // afterwards. See setWebViewDataDirectorySuffix.
        setWebViewDataDirectorySuffix(effective)

        // Before any guest native code can run, for the same reason the Java handler is
        // installed early: the earliest crashes are the ones with no other trace.
        installNativeCrashHandler(model, effective)

        // Where a crash record goes when this process stops existing. Installed before
        // any guest code runs, because the earliest crashes are the ones with no other
        // trace at all.
        runCatching {
            VirtualDiagnostics.installRemoteSink(
                hostContext, hostContext.packageName, ":vapp${effective.slot}",
            )
        }

        // Which of the guest's own components it has turned off, from an earlier session.
        // Bound before the hooks below because `getComponentEnabledSetting` is answered
        // from it, and an app asks that in `Application.onCreate`.
        runCatching {
            GuestComponentState.bind(params.vuid, params.packageName, hostContext)
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "COMPONENT_STATE_BIND_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // The guest's own target SDK decides which platform behaviour changes apply to
        // it, and the process was bound as UNIQUE — so without this a guest built against
        // Android 9 is held to UNIQUE's rules and throws on APIs it has always used.
        runCatching { GuestCompatChanges.applyFor(ready.manifest.targetSdk) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "COMPAT_CHANGES_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // Must precede the `mount` hook below: the volume rewrite it installs reads the
        // instance's external root from here, and an unprepared one installs nothing.
        runCatching { VirtualExternalStorage.prepare(hostContext, effective) }.onFailure {
            Diagnostics.warn(
                DiagChannel.STORAGE, "EXTERNAL_STORAGE_PREPARE_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // Every service whose interception is nothing but the caller's identity. The list
        // and the reason for each entry are in VirtualIdentityHooks; three of them are
        // there because a guest crashed on this device without them.
        runCatching {
            VirtualIdentityHooks.installAll(params.packageName, hostContext.packageName)
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.HOOK, "IDENTITY_HOOK_INSTALL_FAILED",
                mapOf("error" to it.toString()),
            )
        }
        runCatching { VirtualIdentityHooks.reportAlarmCapability(hostContext) }

        // Everything below installs a hook, and every one of them must be in place
        // before the first line of *guest* code runs. That line is a provider's
        // `attachInfo`, not `Application.onCreate`, and the difference cost an app:
        //
        //   14:41:34.450 PROVIDERS_PUBLISHED package=com.a0soft.gphone.acc.free
        //   14:41:34.459 SERVICE_HOOKED      service=notification
        //   14:41:34.471 FATAL EXCEPTION: GoogleApiHandler
        //     java.lang.SecurityException: Package com.a0soft.gphone.acc.free
        //       is not owned by uid 10303
        //       at INotificationManager$Stub$Proxy.getNotificationChannel
        //
        // Nine milliseconds of unhooked `notification` was all it took: the provider's
        // `attachInfo` started a thread, the thread asked for a notification channel
        // under the guest's own name, and `system_server` refused it on a `Handler`
        // where no app can catch it. The rule that follows is absolute and is the
        // reason these four calls now come *before* the providers: **a hook installed
        // after guest code has run is a hook that was not installed.**
        //
        // Nothing here needs a provider. The notification hook needs the guest's
        // `Context` for its icons, the job hook needs the slot, the receiver registry
        // needs the guest's `Context`, and all three exist by now.
        runCatching { VirtualJobSchedulerHook.install(ready, hostContext) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "JOB_HOOK_INSTALL_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // Needs the guest's Context: a notification's icon is a resource in an APK the
        // system has never installed, and only this process can load it.
        runCatching { VirtualNotificationHook.install(ready, hostContext.packageName) }
            .onFailure {
                Diagnostics.warn(
                    DiagChannel.PROCESS, "NOTIFICATION_INSTALL_FAILED",
                    mapOf("package" to params.packageName, "error" to it.toString()),
                )
            }

        // Registered after the application exists, because the guest's own Context is
        // what its receivers must run with. A provider's `attachInfo` may send a
        // broadcast the guest expects to receive, so this precedes the providers too.
        runCatching { VirtualReceiverRegistry.install(ready) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "RECEIVER_INSTALL_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // The window attributes the platform used the stub's copy of. Registered before
        // any guest code because a guest may start its first Activity from a provider's
        // `attachInfo` as readily as from `onCreate`.
        runCatching { VirtualActivityLifecycle.install(ready) }.onFailure {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "ACTIVITY_LIFECYCLE_INSTALL_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // Providers, once every hook is in and not before.
        //
        // They still come before every other *guest* component and before its
        // `Application.onCreate`, which is the ordering apps rely on. What changed is
        // that they no longer come before UNIQUE's own plumbing: `ContentProvider.attachInfo`
        // runs real app code, and `androidx.core.content.FileProvider`'s reads external
        // storage while parsing its `<paths>`. With the `mount` proxy not yet installed
        // that call went out under the guest's name and the provider never published:
        //
        //   PROVIDER_PUBLISH_FAILED provider=androidx.core.content.FileProvider
        //     error=java.lang.SecurityException: callingPackage does not match UID
        //
        // FileProvider is how an app shares a file with anything outside itself, so this
        // was every camera intent, every "share" and every attachment in every app that
        // uses it.
        runCatching { VirtualProviderRegistry.install(ready) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_INSTALL_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        Diagnostics.vuid = params.vuid
        Diagnostics.packageName = params.packageName

        // Last, and deliberately so: everything above is what the guest's own `onCreate`
        // is about to reach. See [runApplicationOnCreate].
        runApplicationOnCreate(activityThreadClass, activityThread, application as Application)

        // Again, now that `onCreate` has run.
        //
        // `System.loadLibrary` overwhelmingly happens in a static initialiser or in
        // `onCreate`, and the redirector hooks what is loaded *at the moment it runs*. It
        // used to run after `onCreate` for exactly that reason; moving `onCreate` to the
        // end of the graft silently took that away, leaving every such library covered
        // only by the load-watch — which catches the next library, not the one already
        // mapped. The call is idempotent, so the second pass costs one walk of the
        // process's `.so` list and closes the window.
        installIoRedirection(hostContext, effective, appInfo)

        Diagnostics.info(
            DiagChannel.LAUNCH, "BOOTSTRAP_OK",
            mapOf(
                "package" to params.packageName,
                "vuid" to params.vuid.toString(),
                "slot" to params.slot.toString(),
                "applicationClass" to (manifest.applicationClassName ?: "android.app.Application"),
                "dataDir" to appInfo.dataDir,
                "sourceDir" to appInfo.sourceDir,
                "nativeLibraryDir" to (appInfo.nativeLibraryDir ?: "-"),
                "targetSdk" to appInfo.targetSdkVersion.toString(),
                "observedPackageName" to application.packageName,
                "observedFilesDir" to application.filesDir.absolutePath,
                "millis" to ((System.nanoTime() - started) / 1_000_000).toString(),
            ),
        )
        return ready
    }

    /**
     * Records a native crash where UNIQUE can read it afterwards.
     *
     * A SIGSEGV inside a guest's own `.so` left the platform's tombstone and UNIQUE's
     * events up to the crash, and nothing written *by* UNIQUE — so a diagnostics export
     * said "the app stopped" and no more. Rule 10 is about a trace the user can hand to
     * someone, and a tombstone on a device they no longer have is not one.
     *
     * Written under the instance's own diagnostics directory, so two instances of one app
     * cannot overwrite each other's last crash.
     */
    private fun installNativeCrashHandler(model: VirtualPathModel, params: VirtualLaunchParams) {
        val dir = File(model.diagnosticsDir(params.vuid, params.packageName))
        val ok = runCatching { dir.mkdirs() }.isSuccess
        if (!ok && !dir.isDirectory) {
            Diagnostics.warn(
                DiagChannel.NATIVE, "NATIVE_CRASH_HANDLER_NO_DIR",
                mapOf("dir" to dir.absolutePath),
            )
            return
        }
        // One file per *process*, not per instance. Two reasons, both learned by the file
        // coming back empty:
        //
        //  - Several processes of one instance (`:alt`, a provider slot) each install a
        //    handler, and a shared path means the second one's O_TRUNC erases the first
        //    one's crash — the record most worth keeping.
        //  - The fd is opened at install time and held open until the process dies, so
        //    anything that *unlinks* the path leaves the handler writing into a nameless
        //    inode. It succeeds, and nothing appears.
        pruneCrashRecords(dir)
        val file = File(dir, nativeCrashFileFor(Process.myPid()))
        val status = runCatching {
            UniqueNative.installCrashHandler(file.absolutePath)
        }.getOrElse {
            Diagnostics.error(
                DiagChannel.NATIVE, "NATIVE_CRASH_HANDLER_FAILED",
                mapOf("error" to it.toString()),
            )
            return
        }
        Diagnostics.info(
            DiagChannel.NATIVE, "NATIVE_CRASH_HANDLER",
            mapOf("status" to status.name, "file" to file.absolutePath),
        )
    }

    /** Where one virtual process's native crash record lands, inside [diagnosticsDir]. */
    fun nativeCrashFileFor(pid: Int): String = "$NATIVE_CRASH_PREFIX$pid.properties"

    /** Matches every native crash record an instance has accumulated. */
    const val NATIVE_CRASH_PREFIX = "native-crash-"

    /**
     * Keeps the newest few records, without ever unlinking one a live process is holding.
     *
     * One record per process means one per launch, so they do need pruning. But an *empty*
     * record is not a spent one — it is a process that has not crashed yet, holding that
     * exact fd open until it does. Deleting it leaves the handler writing into a nameless
     * inode: the write succeeds and nothing appears. That is precisely how this went wrong,
     * with one instance's `:alt` slot quietly erasing the record of the process that was
     * about to crash.
     *
     * Age is used instead of a liveness check because `/proc` is not readable here
     * (`hidepid`), and an empty record an hour old belongs to a process that is certainly
     * gone. Non-empty records are real crashes and only ever pruned by count.
     */
    private fun pruneCrashRecords(dir: File, keep: Int = 8) {
        val records = dir.listFiles { f ->
            f.isFile && f.name.startsWith(NATIVE_CRASH_PREFIX)
        }?.toList().orEmpty()
        val now = System.currentTimeMillis()
        records.filter { it.length() == 0L && now - it.lastModified() > EMPTY_RECORD_TTL_MILLIS }
            .forEach { it.delete() }
        records.filter { it.length() > 0L }
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    /** How long an empty crash record is assumed to still belong to a running process. */
    private const val EMPTY_RECORD_TTL_MILLIS = 60L * 60L * 1000L

    /**
     * Gives this process its own WebView data directory.
     *
     * Since Android P, WebView refuses to run in two processes against one data directory:
     *
     * ```
     * java.lang.RuntimeException: Using WebView from more than one process at once with
     *     the same data directory is not supported.
     * ```
     *
     * The check is per *process*, and every `:vappN` is another process of UNIQUE as far
     * as WebView can tell — only the process whose name equals the package name gets the
     * default (empty) suffix, and none of UNIQUE's virtual processes does. So the first
     * `:vappN` to create a WebView would work and the second would throw, which is a
     * failure that arrives in the guest, names UNIQUE's directory, and points at nothing
     * the app developer can act on.
     *
     * Keyed by slot rather than by instance, because slot and process are one-to-one and
     * the constraint is about processes. Two instances of one app in two slots therefore
     * also get separate suffixes, which is correct for a different reason.
     *
     * It must be called before WebView is used in this process and can only be called
     * once, so it happens here, before any guest code runs at all.
     */
    private fun setWebViewDataDirectorySuffix(params: VirtualLaunchParams) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val suffix = "vapp${params.slot}"
        runCatching { WebView.setDataDirectorySuffix(suffix) }.fold(
            onSuccess = {
                Diagnostics.info(
                    DiagChannel.WEBVIEW, "WEBVIEW_DATA_DIR_SUFFIX",
                    mapOf("suffix" to suffix, "slot" to params.slot.toString()),
                )
            },
            onFailure = {
                // IllegalStateException means WebView was already loaded in this process.
                // It should not be, and if it is, saying so here is far cheaper than the
                // guest's own crash later.
                Diagnostics.warn(
                    DiagChannel.WEBVIEW, "WEBVIEW_DATA_DIR_SUFFIX_REFUSED",
                    mapOf("suffix" to suffix, "error" to it.toString()),
                )
            },
        )
    }

    // ---------------------------------------------------------------------------------
    // ApplicationInfo
    // ---------------------------------------------------------------------------------

    /**
     * Builds the `ApplicationInfo` the whole graft hangs from.
     *
     * `uid` is the host's real uid, not a synthetic one: every file the virtual app
     * touches is owned by the host process, and lying here would make the framework's own
     * permission and storage checks disagree with the filesystem.
     *
     * All three data-directory fields point at the same virtual directory. Android
     * separates credential-protected from device-protected storage; UNIQUE does not,
     * because the host's own storage is already credential-protected and splitting them
     * would create a directory that survives differently from the rest of an instance.
     */
    /**
     * Publishes this instance's redirect table and hooks the guest's own libraries.
     *
     * Scoped to the guest's native library directory and its APK directory, never the
     * whole process: patching every library would redirect UNIQUE's own file operations
     * as well as the guest's, and the native side refuses an empty scope for that reason.
     */
    private fun installIoRedirection(
        hostContext: Context,
        params: VirtualLaunchParams,
        appInfo: ApplicationInfo,
    ) {
        runCatching {
            val storage = VirtualStorage(hostContext)
            val rules = storage.publishRedirection(
                params.vuid, params.packageName, params.versionCode,
            )
            val scope = listOfNotNull(
                appInfo.nativeLibraryDir,
                appInfo.sourceDir?.substringBeforeLast('/'),
            ).filter { it.isNotBlank() }.flatMap(::pathAliases).distinct()
            UniqueNative.setRedirectScope(scope)
            // Set before install, never after: install() walks what is loaded now, and an
            // exclusion that arrives afterwards excludes a library that is already hooked.
            val exclusions = nativeExclusionsFor(hostContext, params)
            UniqueNative.setRedirectExclusions(exclusions)
            val status = UniqueNative.installIoRedirect()
            // And keep it current: a library the guest loads later has its own GOT and is
            // not covered by the scan above.
            val watch = UniqueNative.watchLibraryLoads()
            Diagnostics.info(
                DiagChannel.NATIVE, "IO_REDIRECT_INSTALLED",
                mapOf(
                    "status" to status.name,
                    "watch" to watch.name,
                    "rules" to rules.size.toString(),
                    "slots" to UniqueNative.redirectSlotsPatched().toString(),
                    "scope" to scope.joinToString(",").take(200),
                    // Reported on every launch, not only when it is non-empty: a library
                    // that is deliberately not hooked and one the scan never found produce
                    // the same zero slots, and only one of them is a bug.
                    "excluded" to exclusions.joinToString(",").take(200),
                ),
            )
        }.onFailure {
            Diagnostics.error(
                DiagChannel.NATIVE, "IO_REDIRECT_INSTALL_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }
    }

    /** The one word this instance's visibility file holds, or null when it holds none. */
    private fun readVisibilityOverride(file: File): GoogleStackVisibility.Override? =
        runCatching {
            GoogleStackVisibility.parseOverride(if (file.isFile) file.readText() else null)
        }.getOrNull()

    /**
     * Records, at the moment of the crash, that this instance cannot see Play services.
     *
     * The refusal is fatal precisely because it arrives on the app's own looper, so there
     * is no catching it and no asking afterwards — the process is gone. What *can* be
     * done is leave a mark the next launch reads, which turns "this app is broken forever
     * with Play services visible" into "this app crashed once and then behaved".
     *
     * A crash that is anything else is left alone. Writing the mark for every crash would
     * quietly disable Play services for an app that died of its own bug, and nothing
     * would ever say why.
     */
    private fun installGoogleCrashObserver(file: File, params: VirtualLaunchParams) {
        CrashGuard.observer = observer@{ error ->
            if (!GoogleStackVisibility.isRefusedCallingPackage(error)) return@observer
            val written = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(GoogleStackVisibility.AUTO_HIDE_MARKER)
                true
            }.getOrDefault(false)
            Diagnostics.error(
                DiagChannel.LAUNCH, "GOOGLE_STACK_AUTO_HIDDEN",
                mapOf(
                    "package" to params.packageName,
                    "vuid" to params.vuid.toString(),
                    "recorded" to written.toString(),
                    "detail" to "Play services refused this guest's identity on its own " +
                        "looper; hidden from this instance from the next launch on",
                ),
            )
        }
    }

    /**
     * The libraries the redirector must leave alone for this guest.
     *
     * The built-in list plus whatever this instance's own override file names, one per
     * line, `#` starting a comment. The file is the recovery route for a protector
     * UNIQUE has not met yet: a crash whose only evidence is an unaligned PC inside an
     * anonymous page can be answered by naming one `.so`, without a new build.
     *
     * A failure to read it is a warning and not a launch failure — the built-in list
     * still applies, and an unreadable override must not be the reason an app does not
     * start.
     */
    private fun nativeExclusionsFor(
        hostContext: Context,
        params: VirtualLaunchParams,
    ): List<String> {
        val model = VirtualPathModel(
            (hostContext.applicationContext ?: hostContext).filesDir.absolutePath,
        )
        val file = File(model.nativeExclusionsFile(params.vuid, params.packageName))
        val extra = runCatching {
            if (!file.isFile) emptyList()
            else file.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.NATIVE, "NATIVE_EXCLUSIONS_UNREADABLE",
                mapOf("file" to file.path, "error" to it.toString()),
            )
            emptyList()
        }
        return GuestNativeExclusions.forGuest(extra)
    }

    /**
     * Loads this instance's device profile and applies what has to be applied early.
     *
     * `Build` fields are `static final` and are read at class initialisation, so they are
     * written before the guest's classes load. Settings are answered lazily through the
     * provider wrapper instead, because a guest reads them whenever it likes.
     *
     * A missing or unreadable profile is reported and the host's identity stands. That is
     * the safe direction: an instance with a *half* identity is worse than one with the
     * device's, because its halves disagree with each other.
     */
    private fun bindDeviceProfile(hostContext: Context, params: VirtualLaunchParams) {
        val model = VirtualPathModel(hostContext.filesDir.absolutePath)
        val file = File(model.profileFile(params.vuid))
        val profile = runCatching {
            if (file.isFile) DeviceProfileCodec.decode(file.readText()) else null
        }.getOrNull()
        if (profile == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROFILE_UNAVAILABLE",
                mapOf(
                    "vuid" to params.vuid.toString(),
                    "file" to file.path,
                    "detail" to "the guest will report this device's own identity",
                ),
            )
            return
        }
        VirtualSettings.bind(profile)
        VirtualSettings.applyBuildOverrides(profile)
        DeviceProfileProvider.bind(profile)
    }

    /**
     * The newest version of this package that is actually on disk.
     *
     * Returns null when the package is gone entirely, which is a real failure; a *stale*
     * version is not.
     */
    private fun substituteVersion(model: VirtualPathModel, params: VirtualLaunchParams): File? {
        val root = File(model.apkDir(params.packageName, 0L)).parentFile ?: return null
        val newest = root.listFiles()
            ?.mapNotNull { dir -> dir.name.toLongOrNull()?.let { it to dir } }
            ?.maxByOrNull { it.first }
            ?: return null
        val candidate = File(model.baseApk(params.packageName, newest.first))
        if (!candidate.isFile) return null
        Diagnostics.warn(
            DiagChannel.LAUNCH, "LAUNCH_VERSION_SUBSTITUTED",
            mapOf(
                "package" to params.packageName,
                "requested" to params.versionCode.toString(),
                "using" to newest.first.toString(),
                "detail" to "the launch intent names a version that has been updated away",
            ),
        )
        return candidate
    }

    /**
     * The same directory under every name the platform might report it as.
     *
     * `/data/data/<host>` and `/data/user/0/<host>` are the same directory: the first is
     * a symlink kept for compatibility, and which one appears depends on who resolved the
     * path. `Context.getFilesDir()` hands back the `/data/user/0` form, while the dynamic
     * linker recorded the library it opened as:
     *
     * ```
     * /data/data/com.unique/files/virtual/apk/com.unique.probe/28/lib/x86_64/libprobenative.so
     * ```
     *
     * so a scope built from the first never matched the second and nothing was hooked -
     * with the library plainly loaded and the filter plainly correct-looking. Matching
     * both is the fix; the diagnostic that prints the filter next to the library names it
     * did not match is what made it a two-minute problem instead of a long one.
     */
    private fun pathAliases(path: String): List<String> = when {
        path.startsWith("/data/user/0/") ->
            listOf(path, path.replaceFirst("/data/user/0/", "/data/data/"))
        path.startsWith("/data/data/") ->
            listOf(path, path.replaceFirst("/data/data/", "/data/user/0/"))
        else -> listOf(path)
    }

    /**
     * The extracted-library directory this device can actually execute.
     *
     * Read from disk rather than passed in, like the manifest: no round trip to `:server`
     * on the launch path. The importer extracted one ABI - the best one the device
     * supports - and picking it here by the same rule keeps the two from disagreeing,
     * which would surface as `UnsatisfiedLinkError` from inside the guest with the
     * directory looking perfectly correct in the diagnostics.
     */
    private fun nativeLibraryDirFor(model: VirtualPathModel, params: VirtualLaunchParams): String {
        val root = File(model.nativeLibraryRoot(params.packageName, params.versionCode))
        val present = root.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        val chosen = Abi.preferred(Build.SUPPORTED_ABIS.orEmpty().toList(), present)
        return if (chosen != null) File(root, chosen.dirName).absolutePath
        else model.nativeLibraryDir(params.packageName, params.versionCode)
    }

    private fun buildApplicationInfo(
        model: VirtualPathModel,
        manifest: ApkManifest,
        params: VirtualLaunchParams,
    ): ApplicationInfo {
        val apkDir = model.apkDir(params.packageName, params.versionCode)
        val dataDir = model.dataDir(params.vuid, params.packageName)
        val splits = File(apkDir).listFiles()
            ?.filter { it.name.startsWith("split_") && it.name.endsWith(".apk") }
            ?.sortedBy { it.name }
            ?: emptyList()

        return ApplicationInfo().apply {
            packageName = params.packageName
            processName = params.processName
            className = manifest.applicationClassName
            targetSdkVersion = manifest.targetSdk
            minSdkVersion = manifest.minSdk
            uid = Process.myUid()
            enabled = true

            sourceDir = model.baseApk(params.packageName, params.versionCode)
            publicSourceDir = sourceDir
            if (splits.isNotEmpty()) {
                splitSourceDirs = splits.map { it.absolutePath }.toTypedArray()
                splitPublicSourceDirs = splitSourceDirs
            }
            nativeLibraryDir = nativeLibraryDirFor(model, params)

            this.dataDir = dataDir
            deviceProtectedDataDir = dataDir
            // credentialProtectedDataDir is not in the SDK surface. It is the field
            // LoadedApk actually derives mDataDirFile from on modern releases, so leaving
            // it unset makes getFilesDir() fall back to the host's directory - the exact
            // failure this graft exists to prevent.
            Reflect.set(ApplicationInfo::class.java, "credentialProtectedDataDir", this, dataDir)

            theme = manifest.themeResId
            icon = manifest.iconResId
            // Without this a guest asking its own PackageManager what it is called gets
            // its package name: `ApplicationInfo.loadLabel` resolves `labelRes` against
            // the app's resources and falls back to `packageName` when it is zero. Apps
            // do ask — an about screen, a notification title, a share sheet.
            labelRes = manifest.labelResId
            // Read by NetworkSecurityConfigProvider to find the guest's own config.
            Reflect.set(
                ApplicationInfo::class.java, "networkSecurityConfigRes", this,
                manifest.networkSecurityConfigResId,
            )
            if (manifest.labelResId == 0 && manifest.label != null) {
                // The minority that spell their name out in the manifest. `loadLabel`
                // prefers this over `labelRes` when it is set, which is the right order.
                nonLocalizedLabel = manifest.label
            }

            // FLAG_HAS_CODE makes the platform build a class loader; FLAG_INSTALLED stops
            // framework paths that treat an uninstalled package as absent.
            //
            // The screen-support flags are not decoration. `CompatibilityInfo` reads them
            // and, when they are absent, decides the app predates the screen sizes this
            // device has and puts it in *screen compatibility mode* - a scaled, letterboxed
            // window with the density lied about. PackageParser sets them for anything with
            // a modern target SDK, so leaving them clear was UNIQUE telling the platform
            // every guest was an Android 1.5 app.
            flags = ApplicationInfo.FLAG_HAS_CODE or FLAG_INSTALLED or
                ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS or
                ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS or
                ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS or
                ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS or
                ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES or
                ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS or
                ApplicationInfo.FLAG_ALLOW_BACKUP
            if (manifest.hasCode.not()) flags = flags and ApplicationInfo.FLAG_HAS_CODE.inv()
            // The application-level default every activity inherits. `Activity.attach`
            // reads the *activity's* copy, but plenty of framework and library code reads
            // this one - and a `PhoneWindow` created outside an Activity (a Toast, a
            // Dialog on the application context) takes its renderer from here.
            if (manifest.hardwareAccelerated) {
                flags = flags or ApplicationInfo.FLAG_HARDWARE_ACCELERATED
            }
            if (manifest.largeHeap) flags = flags or ApplicationInfo.FLAG_LARGE_HEAP
            if (manifest.supportsRtl) flags = flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            if (manifest.extractNativeLibs != false) {
                flags = flags or ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS
            }
            // `Environment`'s legacy-view decision and `StorageManager`'s mount mode both
            // read this private flag; an app that asked for legacy storage and is denied it
            // sees an empty external directory it wrote to yesterday.
            if (manifest.requestLegacyExternalStorage) {
                runCatching {
                    val field = ApplicationInfo::class.java.getDeclaredField("privateFlags")
                        .apply { isAccessible = true }
                    val bit = ApplicationInfo::class.java
                        .getDeclaredField("PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE")
                        .apply { isAccessible = true }.getInt(null)
                    field.setInt(this, field.getInt(this) or bit)
                }
            }
            // After the assignment above, not before it: an earlier version set this first
            // and the line below wiped it, which is the quiet kind of wrong — the app runs
            // and its cleartext policy is the host's.
            manifest.usesCleartextTraffic?.let { cleartext ->
                val flag = runCatching {
                    ApplicationInfo::class.java
                        .getDeclaredField("FLAG_USES_CLEARTEXT_TRAFFIC").getInt(null)
                }.getOrNull() ?: return@let
                flags = if (cleartext) flags or flag else flags and flag.inv()
            }

            // Setting this by reflection: the field is hidden, and its absence makes
            // ContextImpl fall back to a null volume, which breaks getDataDir() on some
            // OEM builds.
            runCatching {
                val storageManager = Reflect.findClass("android.os.storage.StorageManager")
                val uuidDefault = storageManager?.let { Reflect.get(it, "UUID_DEFAULT") }
                if (uuidDefault != null) {
                    Reflect.set(ApplicationInfo::class.java, "storageUuid", this, uuidDefault)
                }
            }
            runCatching {
                Reflect.set(ApplicationInfo::class.java, "primaryCpuAbi", this, "arm64-v8a")
                Reflect.set(ApplicationInfo::class.java, "seInfo", this, "default")
            }
        }
    }

    /** `ApplicationInfo.FLAG_INSTALLED`, which is hidden but stable since API 21. */
    private const val FLAG_INSTALLED = 1 shl 23

    // ---------------------------------------------------------------------------------
    // LoadedApk
    // ---------------------------------------------------------------------------------

    /**
     * Asks `ActivityThread` for a `LoadedApk`.
     *
     * `getPackageInfoNoCheck` gained a single-argument overload in Android 14 while the
     * two-argument form remained. Rather than branching on SDK_INT, the overload actually
     * present is selected by parameter count - the same reason `MethodShim` resolves
     * arguments at install time.
     */
    private fun makeLoadedApk(
        activityThreadClass: Class<*>,
        activityThread: Any,
        appInfo: ApplicationInfo,
    ): Any? {
        val candidates = activityThreadClass.declaredMethods
            .filter { it.name == "getPackageInfoNoCheck" }
            .sortedBy { it.parameterCount }
        val compatibilityInfo = Reflect.findClass("android.content.res.CompatibilityInfo")
            ?.let { Reflect.get(it, "DEFAULT_COMPATIBILITY_INFO") }

        for (method in candidates) {
            method.isAccessible = true
            val result = runCatching {
                when (method.parameterCount) {
                    1 -> method.invoke(activityThread, appInfo)
                    2 -> method.invoke(activityThread, appInfo, compatibilityInfo)
                    else -> null
                }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    /**
     * Publishes the `LoadedApk` where the framework looks for it.
     *
     * `ActivityThread.performLaunchActivity` resolves an activity's `LoadedApk` by looking
     * the activity's `applicationInfo.packageName` up in `mPackages`. Installing it here
     * is what makes the launched activity's context report the virtual package - and, by
     * extension, what makes `getPackageName()` and `getFilesDir()` correct inside the app.
     */
    @Suppress("UNCHECKED_CAST")
    private fun installLoadedApk(
        activityThreadClass: Class<*>,
        activityThread: Any,
        packageName: String,
        loadedApk: Any,
    ) {
        for (fieldName in listOf("mPackages", "mResourcePackages")) {
            val map = Reflect.get(activityThreadClass, fieldName, activityThread)
                as? MutableMap<String, WeakReference<*>> ?: continue
            map[packageName] = WeakReference(loadedApk)
        }
    }

    /**
     * Points `mBoundApplication` at the virtual package.
     *
     * Framework internals and a number of widely used libraries read the process's bound
     * application rather than a context - `ActivityThread.currentPackageName()` among
     * them. Leaving it describing UNIQUE would make those callers disagree with every
     * `Context`, which is exactly the inconsistency this project exists to avoid.
     */
    private fun rebindBoundApplication(
        activityThread: Any,
        appInfo: ApplicationInfo,
        loadedApk: Any,
        processName: String,
    ) {
        val bound = Reflect.get(activityThread.javaClass, "mBoundApplication", activityThread)
            ?: return
        val boundClass = bound.javaClass
        Reflect.set(boundClass, "appInfo", bound, appInfo)
        Reflect.set(boundClass, "info", bound, loadedApk)
        Reflect.set(boundClass, "processName", bound, processName)
        renameProcess(processName)
    }

    /**
     * Makes `/proc/self/cmdline` say what the guest's process is called.
     *
     * `ActivityThread.handleBindApplication` does exactly this for an installed app:
     *
     * ```java
     * Process.setArgV0(data.processName);
     * ```
     *
     * and it ran before the graft, with UNIQUE's own name — so every guest's
     * `/proc/self/cmdline` read `com.unique:vapp0`. That file is the single most common
     * thing an app checks when it wants to know whether it is running where it thinks it
     * is: it is one `read()` with no permission, no API and no way for UNIQUE to answer it
     * afterwards. Java-side process name is already the guest's, which made the mismatch
     * *more* obvious rather than less.
     *
     * Setting argv[0] is what the platform itself does, on the same thread, with the same
     * method. It changes nothing the system tracks — `ActivityManagerService` keys
     * processes by pid and holds the stub's name in its own record — and it is not
     * reversible, which is why it happens once, here, before any guest code runs.
     */
    private fun renameProcess(processName: String) {
        val result = runCatching {
            val clazz = Reflect.findClass("android.os.Process") ?: error("no android.os.Process")
            val method = Reflect.findMethod(clazz, "setArgV0", String::class.java)
                ?: error("no Process.setArgV0(String)")
            method.invoke(null, processName)
        }
        if (result.isSuccess) {
            Diagnostics.info(
                DiagChannel.PROCESS, "PROCESS_RENAMED",
                mapOf("argv0" to processName),
            )
        } else {
            // Not fatal: the guest runs, and the only cost is that a process-name check
            // inside it sees UNIQUE. Reported because that cost is invisible otherwise.
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROCESS_RENAME_FAILED",
                mapOf(
                    "argv0" to processName,
                    "error" to (result.exceptionOrNull()?.toString() ?: "?"),
                ),
            )
        }
    }

    /**
     * Creates the virtual `Application`.
     *
     * `LoadedApk.makeApplication` was renamed to `makeApplicationInner` in Android 14,
     * with `makeApplication` kept as a wrapper on some builds and removed on others.
     * Both names are tried, newest first.
     */
    private fun makeApplication(
        activityThreadClass: Class<*>,
        activityThread: Any,
        loadedApk: Any,
    ): Pair<Application?, String> {
        // Null, and that is the point. `makeApplicationInner` uses this argument for
        // exactly one thing — `instrumentation.callApplicationOnCreate(app)` — so passing
        // null builds the Application and stops short of running it. See
        // [runApplicationOnCreate], which does that afterwards, and why it has to.
        val instrumentation: Any? = null
        val attempts = ArrayList<String>()

        val candidates = loadedApk.javaClass.declaredMethods
            .filter { it.name == "makeApplicationInner" || it.name == "makeApplication" }
            .sortedWith(compareBy({ it.name != "makeApplicationInner" }, { it.parameterCount }))

        if (candidates.isEmpty()) {
            return null to "LoadedApk exposes neither makeApplicationInner nor makeApplication"
        }

        for (method in candidates) {
            method.isAccessible = true
            val args: Array<Any?> = when (method.parameterCount) {
                2 -> arrayOf(false, instrumentation)
                // Android 14 added an allowDuplicateInstances flag on the private form.
                3 -> arrayOf(false, instrumentation, false)
                else -> {
                    attempts += "${method.name}/${method.parameterCount}: unsupported arity"
                    continue
                }
            }
            try {
                val app = method.invoke(loadedApk, *args) as? Application
                if (app != null) return app to ""
                attempts += "${method.name}/${method.parameterCount}: returned null"
            } catch (e: Throwable) {
                // The real reason lives at the bottom of the chain: the framework wraps a
                // ClassNotFoundException or a resources failure in a RuntimeException whose
                // own message says almost nothing. Swallowing this is what turned a
                // one-line failure into a debugging session.
                attempts += "${method.name}/${method.parameterCount}: ${rootCause(e)}"
            }
        }
        return null to attempts.joinToString("; ")
    }

    /**
     * Runs the guest's `Application.onCreate`, once everything it can reach is in place.
     *
     * ## Why this is separate from creating the Application
     *
     * It used to happen inside `makeApplication`, which is where the platform's own
     * `LoadedApk` puts it — and that put it in the middle of the graft, with the identity
     * hooks and the guest's providers still to come. A guest's `onCreate` is not a quiet
     * moment: it starts analytics, opens a network stack, asks whether notifications are
     * enabled, initialises WorkManager. Every one of those goes out through a service that
     * was not proxied yet, and the framework caches the interface it got:
     *
     * ```
     * SecurityException: Package com.openai.chatgpt does not belong to 10302
     *   at ConnectivityManager.getNetworkCapabilities            (statsig, from onCreate)
     * SecurityException: Caller not system or systemui or same package: uid 10302 does not
     *   have android.permission.STATUS_BAR_SERVICE
     *   at NotificationManager.areNotificationsEnabled           -> killed the app
     * ```
     *
     * Both services *were* proxied — sixty and eighty lines further down this file.
     * `NotificationManager.sService` is a static field, so the raw interface it captured
     * during `onCreate` outlived the hook that arrived afterwards, and the shim was never
     * reached again.
     *
     * ## Why this ordering is also the platform's
     *
     * `ActivityThread.handleBindApplication` does the same three things in the same order:
     * make the Application, install the content providers, *then*
     * `callApplicationOnCreate`. UNIQUE was passing a non-null `Instrumentation` into
     * `makeApplicationInner`, which collapsed the first and third steps into one and left
     * providers published after the app had already started. `androidx.startup`'s
     * `InitializationProvider` is built on that guarantee, and said so:
     *
     * ```
     * PROVIDER_PUBLISH_FAILED provider=androidx.startup.InitializationProvider
     *   error=IllegalStateException: WorkManager is already initialized.
     * ```
     *
     * So this is not a workaround for a UNIQUE-specific problem. It is the platform's own
     * sequence, restored.
     *
     * Failures are reported and swallowed. A guest whose `onCreate` throws is broken in a
     * way UNIQUE cannot fix, and the platform's behaviour — the process dies with the
     * guest's own stack — is reproduced by letting the exception reach the default handler
     * rather than by turning a live guest into a `BOOTSTRAP_FAILED` with no trace.
     */
    private fun runApplicationOnCreate(
        activityThreadClass: Class<*>,
        activityThread: Any,
        application: Application,
    ) {
        val instrumentation = Reflect.get(activityThreadClass, "mInstrumentation", activityThread)
        if (instrumentation == null) {
            // Nothing to call through; the platform would have skipped onCreate too, so
            // call it directly rather than leaving the guest un-started.
            Diagnostics.warn(
                DiagChannel.PROCESS, "INSTRUMENTATION_MISSING",
                mapOf("detail" to "ActivityThread.mInstrumentation is null; calling onCreate directly"),
            )
            application.onCreate()
            return
        }
        val method = Reflect.findMethod(
            instrumentation.javaClass, "callApplicationOnCreate", Application::class.java,
        )
        if (method == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "INSTRUMENTATION_NO_ON_CREATE",
                mapOf("class" to instrumentation.javaClass.name),
            )
            application.onCreate()
            return
        }
        method.isAccessible = true
        method.invoke(instrumentation, application)
    }

    private fun rootCause(t: Throwable): String {
        var root: Throwable = t
        val chain = StringBuilder(root.toString())
        while (root.cause != null && root.cause !== root) {
            root = root.cause!!
            chain.append(" <- ").append(root.toString())
        }
        return chain.toString().take(600)
    }

    // ---------------------------------------------------------------------------------
    // Activity resolution
    // ---------------------------------------------------------------------------------

    /**
     * Builds the `ActivityInfo` the platform will launch with.
     *
     * The stub's own `ActivityInfo` describes a UNIQUE component; substituting this one
     * is what makes `performLaunchActivity` instantiate the virtual app's class, from the
     * virtual class loader, with the virtual theme.
     */
    fun activityInfoFor(ready: Result.Ready, className: String): ActivityInfo? {
        val entry = resolveActivity(ready.manifest, className) ?: return null
        return buildActivityInfo(ready.manifest, ready.applicationInfo, ready.params, entry)
    }

    private fun buildActivityInfo(
        manifest: ApkManifest,
        appInfo: ApplicationInfo,
        params: VirtualLaunchParams,
        entry: ComponentEntry,
    ): ActivityInfo {
        return ActivityInfo().apply {
            name = entry.className
            packageName = params.packageName
            processName = params.processName
            applicationInfo = appInfo
            targetActivity = entry.targetActivity
            theme = if (entry.theme != 0) entry.theme else manifest.themeResId
            launchMode = entry.launchMode
            taskAffinity = entry.taskAffinity
            configChanges = entry.configChanges
            screenOrientation = entry.screenOrientation
            exported = entry.exported
            enabled = GuestComponentState.isEnabled(entry)
            permission = entry.permission
            labelRes = entry.labelResId
            if (entry.labelResId == 0 && entry.labelText != null) nonLocalizedLabel = entry.labelText
            if (entry.iconResId != 0) icon = entry.iconResId
            metaData = GuestMetaData.bundle(entry.metaDataEntries, guestResources)
            // `Activity.attach` reads this and nothing else to decide whether the window
            // gets a hardware renderer:
            //
            //     mWindow.setWindowManager(…, (info.flags & FLAG_HARDWARE_ACCELERATED) != 0)
            //
            // Leaving `flags` at zero therefore put every virtual app on the software
            // rasteriser. Two symptoms, one cause: everything drew slowly, and anything
            // that renders through a RenderNode died outright with
            // "Software rendering doesn't support drawRenderNode".
            flags = activityFlagsOf(entry)
            softInputMode = entry.window.softInputMode
            uiOptions = entry.window.uiOptions
            documentLaunchMode = entry.window.documentLaunchMode
            if (entry.window.maxRecents >= 0) maxRecents = entry.window.maxRecents
            colorMode = entry.window.colorMode
            if (entry.window.persistableMode >= 0) persistableMode = entry.window.persistableMode
            // Hidden fields; skipped silently on a release that has renamed them, because
            // they change how a window animates and is pinned, never whether it opens.
            if (entry.window.rotationAnimation >= 0) {
                Reflect.set(
                    ActivityInfo::class.java, "rotationAnimation", this,
                    entry.window.rotationAnimation,
                )
            }
            Reflect.set(
                ActivityInfo::class.java, "lockTaskLaunchMode", this, entry.window.lockTaskMode,
            )
            applyResizeMode(this, entry)
        }
    }

    /**
     * `ActivityInfo.flags`, composed from what the manifest declared.
     *
     * The constants are read from the platform class rather than mirrored as numbers, so
     * `core/common` can stay free of `android.*` without this becoming a table of magic
     * bits that has to be checked against a release note.
     */
    private fun activityFlagsOf(entry: ComponentEntry): Int {
        val w = entry.window
        var flags = 0
        if (w.hardwareAccelerated) flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
        if (w.multiprocess) flags = flags or ActivityInfo.FLAG_MULTIPROCESS
        if (w.finishOnTaskLaunch) flags = flags or ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH
        if (w.clearTaskOnLaunch) flags = flags or ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH
        if (w.alwaysRetainTaskState) flags = flags or ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE
        if (w.stateNotNeeded) flags = flags or ActivityInfo.FLAG_STATE_NOT_NEEDED
        if (w.excludeFromRecents) flags = flags or ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
        if (w.allowTaskReparenting) flags = flags or ActivityInfo.FLAG_ALLOW_TASK_REPARENTING
        if (w.noHistory) flags = flags or ActivityInfo.FLAG_NO_HISTORY
        if (w.immersive) flags = flags or ActivityInfo.FLAG_IMMERSIVE
        if (w.autoRemoveFromRecents) flags = flags or ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS
        if (w.relinquishTaskIdentity) flags = flags or ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY
        if (w.resumeWhilePausing) flags = flags or ActivityInfo.FLAG_RESUME_WHILE_PAUSING
        // Hidden constants: read by name so a release that removes one costs the guest a
        // window attribute rather than the launch.
        if (w.showForAllUsers) flags = flags or hiddenActivityFlag("FLAG_SHOW_FOR_ALL_USERS")
        if (w.turnScreenOn) flags = flags or hiddenActivityFlag("FLAG_TURN_SCREEN_ON")
        if (w.showWhenLocked) flags = flags or hiddenActivityFlag("FLAG_SHOW_WHEN_LOCKED")
        return flags
    }

    private val hiddenActivityFlags = HashMap<String, Int>()

    @Synchronized
    private fun hiddenActivityFlag(name: String): Int = hiddenActivityFlags.getOrPut(name) {
        runCatching {
            ActivityInfo::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
        }.getOrDefault(0)
    }

    /**
     * `resizeMode` and the aspect-ratio clamps, which are hidden fields.
     *
     * Set by reflection because they are not in the SDK surface, and skipped silently when
     * a release does not have them: they change how the window is letterboxed, never
     * whether the activity starts.
     */
    private fun applyResizeMode(info: ActivityInfo, entry: ComponentEntry) {
        val resizeable = entry.window.resizeable
        if (resizeable != null) {
            // RESIZE_MODE_RESIZEABLE = 2, RESIZE_MODE_UNRESIZEABLE = 0. Hidden constants
            // whose values have not moved since they were introduced in API 24.
            Reflect.set(ActivityInfo::class.java, "resizeMode", info, if (resizeable) 2 else 0)
        }
        // The aspect-ratio clamps are private fields with public setters that are hidden
        // too; the fields are the stable half. An app that pins itself to 16:9 and is
        // stretched to a 20:9 phone renders with the wrong layout, which reads as UNIQUE
        // breaking it.
        if (entry.window.maxAspectRatio > 0f) {
            Reflect.set(ActivityInfo::class.java, "mMaxAspectRatio", info, entry.window.maxAspectRatio)
        }
        if (entry.window.minAspectRatio > 0f) {
            Reflect.set(ActivityInfo::class.java, "mMinAspectRatio", info, entry.window.minAspectRatio)
        }
    }

    /**
     * The guest's own `Resources`, once its `LoadedApk` exists.
     *
     * Meta-data references resolve against these, so an `ActivityInfo` built before the
     * graft finishes carries the reference id and one built afterwards carries the value.
     * Both are better than the empty bundle that was there before, and the second is what
     * the platform would have produced.
     */
    @Volatile private var guestResources: android.content.res.Resources? = null

    /**
     * Builds the `ServiceInfo` the platform will instantiate a service from.
     *
     * Same idea as [activityInfoFor]: `handleCreateService` resolves the `LoadedApk` from
     * `info.applicationInfo` and instantiates `info.name`, so substituting this makes the
     * guest's own Service class run with the guest's class loader and data directory.
     */
    fun serviceInfoFor(ready: Result.Ready, className: String): ServiceInfo? {
        val entry = ready.manifest.components.firstOrNull {
            it.kind == ComponentKind.SERVICE && it.className == className
        } ?: return null
        return buildServiceInfo(ready.applicationInfo, ready.params, entry)
    }

    private fun buildServiceInfo(
        appInfo: ApplicationInfo,
        params: VirtualLaunchParams,
        entry: ComponentEntry,
    ): ServiceInfo {
        return ServiceInfo().apply {
            name = entry.className
            packageName = params.packageName
            processName = params.processName
            applicationInfo = appInfo
            exported = entry.exported
            enabled = GuestComponentState.isEnabled(entry)
            permission = entry.permission
            labelRes = entry.labelResId
            if (entry.labelResId == 0 && entry.labelText != null) nonLocalizedLabel = entry.labelText
            if (entry.iconResId != 0) icon = entry.iconResId
            // Read by, among others, `android.app.job.JobService`-adjacent libraries and
            // every SDK that configures itself from its own service entry.
            metaData = GuestMetaData.bundle(entry.metaDataEntries, guestResources)
            // `mForegroundServiceType` has only a getter in the SDK surface. The stub's
            // own declaration is what Android 14 actually checks, but a guest library that
            // reads its own ServiceInfo back gets a truthful answer this way.
            if (entry.foregroundServiceType != 0) {
                Reflect.set(
                    ServiceInfo::class.java, "mForegroundServiceType", this,
                    entry.foregroundServiceType,
                )
            }
            if (entry.window.directBootAware) {
                Reflect.set(ServiceInfo::class.java, "directBootAware", this, true)
            }
        }
    }

    /**
     * The component-info factory the virtual `PackageManager` answers from.
     *
     * Built here because this is the one place that holds the manifest, the
     * `ApplicationInfo` and the launch parameters at the same time — the hook is installed
     * before `bootstrapped` is set, so it cannot ask [current] for any of them.
     */
    private fun guestComponents(
        manifest: ApkManifest,
        appInfo: ApplicationInfo,
        params: VirtualLaunchParams,
    ): GuestComponents = object : GuestComponents {

        override fun activity(className: String): ActivityInfo? =
            resolveActivity(manifest, className)?.let { buildActivityInfo(manifest, appInfo, params, it) }

        override fun service(className: String): ServiceInfo? =
            entry(ComponentKind.SERVICE, className)?.let { buildServiceInfo(appInfo, params, it) }

        override fun provider(className: String): ProviderInfo? =
            entry(ComponentKind.PROVIDER, className)?.let { buildProviderInfo(appInfo, params, it) }

        override fun receiver(className: String): ActivityInfo? =
            entry(ComponentKind.RECEIVER, className)
                ?.let { buildActivityInfo(manifest, appInfo, params, it) }

        override fun activities(): Array<ActivityInfo> = manifest.components
            .filter { it.kind == ComponentKind.ACTIVITY || it.kind == ComponentKind.ACTIVITY_ALIAS }
            .map { buildActivityInfo(manifest, appInfo, params, it) }
            .toTypedArray()

        override fun services(): Array<ServiceInfo> = manifest.services
            .map { buildServiceInfo(appInfo, params, it) }
            .toTypedArray()

        override fun providers(): Array<ProviderInfo> = manifest.providers
            .map { buildProviderInfo(appInfo, params, it) }
            .toTypedArray()

        override fun receivers(): Array<ActivityInfo> = manifest.receivers
            .map { buildActivityInfo(manifest, appInfo, params, it) }
            .toTypedArray()

        private fun entry(kind: ComponentKind, className: String): ComponentEntry? =
            manifest.components.firstOrNull { it.kind == kind && it.className == className }
    }

    /**
     * The `ProviderInfo` a guest's provider is published and reported with.
     *
     * `grantUriPermissions` comes from the manifest rather than being hard-coded true, as
     * it was in both places that built one of these: granting a URI on a provider that
     * never opted in is UNIQUE widening the guest's own surface.
     */
    fun buildProviderInfo(
        appInfo: ApplicationInfo,
        params: VirtualLaunchParams,
        entry: ComponentEntry,
    ): ProviderInfo = ProviderInfo().apply {
        name = entry.className
        packageName = params.packageName
        processName = params.processName
        applicationInfo = appInfo
        authority = entry.authorities.joinToString(";")
        exported = entry.exported
        enabled = GuestComponentState.isEnabled(entry)
        grantUriPermissions = entry.grantUriPermissions
        readPermission = entry.readPermission
        writePermission = entry.writePermission
        metaData = GuestMetaData.bundle(entry.metaDataEntries, guestResources)
    }

    /** Null [className] means the package's launcher activity. */
    fun resolveActivity(manifest: ApkManifest, className: String?): ComponentEntry? {
        if (className == null) return manifest.launcherActivity
        return manifest.components.firstOrNull {
            (it.kind == ComponentKind.ACTIVITY || it.kind == ComponentKind.ACTIVITY_ALIAS) &&
                it.className == className
        }
    }
}
