package com.unique.core.vam

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Process
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentEntry
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.apk.ManifestReader
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.HiddenApi
import com.unique.core.hook.Reflect
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
            if (result is Result.Ready) bootstrapped = result
            result
        } catch (t: Throwable) {
            Result.Failed("BOOTSTRAP_FAILED", t.toString(), t)
        }
    }

    private fun graft(hostContext: Context, params: VirtualLaunchParams): Result {
        val started = System.nanoTime()
        val model = VirtualPathModel(hostContext.filesDir.absolutePath)

        val baseApk = File(model.baseApk(params.packageName, params.versionCode))
        if (!baseApk.isFile) {
            return Result.Failed("APK_MISSING", "${baseApk.path} does not exist.")
        }

        // The manifest is read here rather than passed in, so a virtual process needs no
        // round trip to :server before it can start. It costs a few milliseconds.
        val manifest = runCatching { ManifestReader.fromApk(baseApk) }.getOrElse {
            return Result.Failed("MANIFEST_UNREADABLE", it.toString(), it)
        }

        val appInfo = buildApplicationInfo(model, manifest, params)

        val activityThreadClass = Reflect.findClass("android.app.ActivityThread")
            ?: return Result.Failed("NO_ACTIVITY_THREAD", "android.app.ActivityThread not found")
        val activityThread = Reflect.findMethod(activityThreadClass, "currentActivityThread")
            ?.invoke(null)
            ?: return Result.Failed("NO_ACTIVITY_THREAD", "currentActivityThread() returned null")

        val loadedApk = makeLoadedApk(activityThreadClass, activityThread, appInfo)
            ?: return Result.Failed("NO_LOADED_APK", "getPackageInfoNoCheck did not return a LoadedApk")

        installLoadedApk(activityThreadClass, activityThread, params.packageName, loadedApk)
        rebindBoundApplication(activityThread, appInfo, loadedApk, params.processName)

        // Must precede makeApplication: LoadedApk.initializeJavaContextClassLoader() asks
        // the real PackageManagerService for this package and throws when it is not
        // installed - which, for an app UNIQUE imported rather than installed, it is not.
        VirtualPackageManagerHook.hostPackageName = hostContext.packageName
        val pmHooked = VirtualPackageManagerHook.install(
            packageName = params.packageName,
            manifest = manifest,
            applicationInfo = appInfo,
            activityInfoOf = { className ->
                resolveActivity(manifest, className)?.let { entry ->
                    buildActivityInfo(manifest, appInfo, params, entry)
                }
            },
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

        val (application, applicationError) = makeApplication(activityThreadClass, activityThread, loadedApk)
        if (application == null) {
            return Result.Failed(
                "NO_APPLICATION",
                "could not create ${manifest.applicationClassName ?: "android.app.Application"} " +
                    "from ${appInfo.sourceDir}: $applicationError",
            )
        }

        Reflect.set(activityThreadClass, "mInitialApplication", activityThread, application)

        VirtualServiceRouter.bindSlot(params.slot)

        val ready = Result.Ready(params, manifest, application, appInfo)

        // Providers first: the platform creates a process's providers before any other
        // component and apps rely on that ordering.
        runCatching { VirtualProviderRegistry.install(ready) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_INSTALL_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        // Registered after the application exists, because the guest's own Context is
        // what its receivers must run with.
        runCatching { VirtualReceiverRegistry.install(ready) }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "RECEIVER_INSTALL_FAILED",
                mapOf("package" to params.packageName, "error" to it.toString()),
            )
        }

        Diagnostics.vuid = params.vuid
        Diagnostics.packageName = params.packageName
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
            nativeLibraryDir = model.nativeLibraryDir(params.packageName, params.versionCode)

            this.dataDir = dataDir
            deviceProtectedDataDir = dataDir
            // credentialProtectedDataDir is not in the SDK surface. It is the field
            // LoadedApk actually derives mDataDirFile from on modern releases, so leaving
            // it unset makes getFilesDir() fall back to the host's directory - the exact
            // failure this graft exists to prevent.
            Reflect.set(ApplicationInfo::class.java, "credentialProtectedDataDir", this, dataDir)

            theme = manifest.themeResId
            icon = manifest.iconResId

            // FLAG_HAS_CODE makes the platform build a class loader; FLAG_INSTALLED stops
            // framework paths that treat an uninstalled package as absent.
            flags = ApplicationInfo.FLAG_HAS_CODE or FLAG_INSTALLED
            if (manifest.hasCode.not()) flags = flags and ApplicationInfo.FLAG_HAS_CODE.inv()

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
        val instrumentation = Reflect.get(activityThreadClass, "mInstrumentation", activityThread)
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
            enabled = entry.enabled
            permission = entry.permission
            // The stub's window has already been created by the system, so soft-input and
            // resize behaviour is inherited from it; per-activity fidelity for those is
            // phase 3 work and is recorded as such rather than silently approximated.
            softInputMode = 0
        }
    }

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
        return ServiceInfo().apply {
            name = entry.className
            packageName = ready.params.packageName
            processName = ready.params.processName
            applicationInfo = ready.applicationInfo
            exported = entry.exported
            enabled = entry.enabled
            permission = entry.permission
        }
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
