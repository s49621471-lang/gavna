package com.unique.core.vam

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.ServiceTarget
import com.unique.core.hook.SystemServiceHook

/**
 * Makes the platform believe the virtual package is installed.
 *
 * This is not an optional nicety - it is what lets UNIQUE run an app that is *not*
 * installed on the device, which is the entire point of the product. `LoadedApk` itself
 * refuses to build a class loader otherwise: `initializeJavaContextClassLoader()` asks
 * PackageManagerService for the package and throws
 * `IllegalStateException: ... is package not installed?` when it comes back null.
 *
 * Every shim is conditional: it answers for the virtual package and hands every other
 * package straight to the real PackageManagerService. Answering for everything would
 * break the host code that shares the process.
 */
object VirtualPackageManagerHook {

    @Volatile private var installedFor: String? = null

    val boundPackage: String? get() = installedFor

    /**
     * Installs the shims for [packageName].
     *
     * Called during the graft, before the application is created, because the very first
     * consumer is `LoadedApk` itself.
     */
    @Synchronized
    fun install(
        packageName: String,
        manifest: ApkManifest,
        applicationInfo: ApplicationInfo,
        components: GuestComponents,
        hostContext: Context? = null,
        apkPath: String? = null,
    ): Boolean {
        if (installedFor == packageName) return true

        if (hostContext != null && apkPath != null) {
            startSignatureLoad(hostContext, apkPath, packageName)
        }

        val target = SystemServiceHook.TARGETS.first { it.serviceName == "package" }
        val report = SystemServiceHook.install(
            target,
            shims(packageName, manifest, applicationInfo, components),
        )
        if (!report.installed) {
            Diagnostics.error(
                DiagChannel.LAUNCH, "VPM_HOOK_FAILED",
                mapOf("package" to packageName, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        installedFor = packageName
        Diagnostics.info(
            DiagChannel.LAUNCH, "VPM_HOOK_INSTALLED",
            mapOf("package" to packageName, "bound" to (report.bind?.bound?.joinToString(",") ?: "")),
        )
        return true
    }

    private fun shims(
        packageName: String,
        manifest: ApkManifest,
        applicationInfo: ApplicationInfo,
        components: GuestComponents,
    ): List<MethodShim> = listOf(

        // The one LoadedApk depends on. Returning null here is what makes the platform
        // conclude the package is not installed and refuse to build a class loader.
        shim("getPackageInfo") {
            replaceWith { call ->
                if (call.firstArgOf<String>() == packageName) {
                    buildPackageInfo(packageName, manifest, applicationInfo, components, flagsOf(call))
                } else call.proceed()
            }
        },

        shim("getApplicationInfo") {
            replaceWith { call ->
                if (call.firstArgOf<String>() == packageName) applicationInfo else call.proceed()
            }
        },

        shim("getActivityInfo") {
            replaceWith { call ->
                val component = call.firstArgOf<ComponentName>()
                if (component != null && component.packageName == packageName) {
                    components.activity(component.className) ?: call.proceed()
                } else call.proceed()
            }
        },

        // The three siblings of `getActivityInfo`. Without them a guest asking the
        // platform about its own service — which every library that starts one by
        // `ComponentName` does — gets `NameNotFoundException` for a component that is
        // right there in its manifest.
        shim("getServiceInfo") {
            replaceWith { call ->
                val component = call.firstArgOf<ComponentName>()
                if (component != null && component.packageName == packageName) {
                    components.service(component.className) ?: call.proceed()
                } else call.proceed()
            }
        },

        shim("getReceiverInfo") {
            replaceWith { call ->
                val component = call.firstArgOf<ComponentName>()
                if (component != null && component.packageName == packageName) {
                    components.receiver(component.className) ?: call.proceed()
                } else call.proceed()
            }
        },

        shim("getProviderInfo") {
            replaceWith { call ->
                val component = call.firstArgOf<ComponentName>()
                if (component != null && component.packageName == packageName) {
                    components.provider(component.className) ?: call.proceed()
                } else call.proceed()
            }
        },

        // Intent resolution about the guest's own components. Scoped to intents that
        // name the guest - see GuestIntentResolution for why the scope is that narrow.
        shim("resolveIntent") {
            matchMethods { it.name == "resolveIntent" || it.name == "resolveService" }
            replaceWith { call ->
                val intent = call.firstArgOf<Intent>()
                if (!GuestIntentResolution.isScopedToGuest(intent, packageName)) call.proceed()
                else {
                    val matches = if (call.method.name == "resolveService") {
                        GuestIntentResolution.services(manifest, components, intent!!, packageName)
                    } else {
                        GuestIntentResolution.activities(manifest, components, intent!!, packageName)
                    }
                    matches.firstOrNull() ?: call.proceed()
                }
            }
        },

        shim("queryIntent") {
            matchMethods { method ->
                method.name == "queryIntentActivities" || method.name == "queryIntentServices" ||
                    method.name == "queryIntentReceivers"
            }
            replaceWith { call ->
                val intent = call.firstArgOf<Intent>()
                if (!GuestIntentResolution.isScopedToGuest(intent, packageName)) call.proceed()
                else {
                    val matches = when (call.method.name) {
                        "queryIntentServices" ->
                            GuestIntentResolution.services(manifest, components, intent!!, packageName)
                        "queryIntentReceivers" ->
                            GuestIntentResolution.receivers(manifest, components, intent!!, packageName)
                        else ->
                            GuestIntentResolution.activities(manifest, components, intent!!, packageName)
                    }
                    // An empty answer is handed back to the platform rather than returned:
                    // a guest may legitimately scope an intent to itself for a component
                    // it does not have, and "no match" from here would hide a real one.
                    if (matches.isEmpty()) call.proceed()
                    else GuestIntentResolution.asReturnValue(call.method.returnType, matches)
                        ?: call.proceed()
                }
            }
        },

        // `ContentResolver` asks this before every provider acquisition, and a guest's own
        // authority is one the platform has never heard of.
        shim("resolveContentProvider") {
            replaceWith { call ->
                val authority = call.firstArgOf<String>()
                val entry = authority?.let { name ->
                    manifest.providers.firstOrNull { name in it.authorities }
                }
                if (entry == null) call.proceed()
                else components.provider(entry.className) ?: call.proceed()
            }
        },

        /*
         * What the guest sees when it enumerates the device's applications.
         *
         * Two wrongs, and the first is the one that breaks ordinary code: the guest's own
         * package is *absent*, because the platform has never installed it. An app that
         * looks itself up in `getInstalledPackages()` — to read its own install time, to
         * check whether a companion app is present, to build an "apps on this device"
         * list — concludes it is not installed.
         *
         * The second is that UNIQUE is present. Its package, its stub pool and its
         * processes are exactly what a virtualization check looks for, and a guest has no
         * business seeing the app it is running inside; §"neither should be able to tell".
         *
         * Nothing else is touched. Every other installed app stays, because that is what
         * the device really has and inventing one would be worse than either problem.
         */
        shim("installedPackages") {
            // Not `getPackagesHoldingPermissions`, which asks a *question* about each
            // entry: adding the guest to that answer would claim it holds a permission
            // nobody checked. The guest's own permission state is answered where it
            // belongs, by `permissionCheck` below.
            matchMethods { method ->
                method.name == "getInstalledPackages" || method.name == "getInstalledApplications"
            }
            // `replaceWith` rather than `rewriteResult`, because completing the list needs
            // the *flags* the caller passed, and only the call carries those.
            replaceWith { call ->
                val result = call.proceed()
                val list = ParceledLists.unwrap(result) ?: return@replaceWith result
                val host = hostPackageName
                val kept = ArrayList<Any?>(list.size + 1)
                var changed = false
                var sawGuest = false
                for (element in list) {
                    val name = packageNameOf(element)
                    if (name == host) { changed = true; continue }
                    if (name == packageName) sawGuest = true
                    kept += element
                }
                if (!sawGuest) {
                    val own: Any? =
                        if (call.method.name == "getInstalledApplications") applicationInfo
                        else buildPackageInfo(
                            packageName, manifest, applicationInfo, components, flagsOf(call),
                        )
                    if (own != null) { kept += own; changed = true }
                }
                if (!changed) result
                else ParceledLists.wrap(call.method.returnType, kept) ?: result
            }
        },

        // Identity by uid. Inside a virtual process the only meaningful answer for our own
        // uid is the virtual package: that is what "the app believes it is itself" means
        // for every framework path that resolves a caller by uid rather than by context.
        shim("getPackagesForUid") {
            replaceWith { call ->
                val uid = call.args.filterIsInstance<Int>().firstOrNull()
                if (uid == Process.myUid()) arrayOf(packageName) else call.proceed()
            }
        },

        shim("getNameForUid") {
            replaceWith { call ->
                val uid = call.args.filterIsInstance<Int>().firstOrNull()
                if (uid == Process.myUid()) packageName else call.proceed()
            }
        },

        // An app turning one of its own components on or off.
        //
        // `PackageManagerService` refuses outright — `Attempt to change component state`,
        // checked against the calling uid — because the component belongs to a package it
        // has never installed. There is nothing for it to store and no way for it to
        // agree, so the state lives in `GuestComponentState` and these four answer from
        // there. Anything naming another package still goes to the platform.
        shim("componentEnabledSetting") {
            matchMethods { method ->
                method.name == "setComponentEnabledSetting" ||
                    method.name == "getComponentEnabledSetting"
            }
            replaceWith { call ->
                val component = call.firstArgOf<ComponentName>()
                if (!GuestComponentState.owns(component)) call.proceed()
                else if (call.method.name == "getComponentEnabledSetting") {
                    GuestComponentState.settingFor(component!!.className)
                } else {
                    // `(componentName, newState, flags, userId, callingPackage)`: the new
                    // state is the first int, ahead of flags and the user id.
                    val newState = call.args.filterIsInstance<Int>().firstOrNull()
                    if (newState == null) {
                        Diagnostics.warn(
                            DiagChannel.PROCESS, "COMPONENT_STATE_SHAPE_UNKNOWN",
                            mapOf("method" to call.method.name),
                        )
                    } else {
                        GuestComponentState.set(component!!.className, newState)
                    }
                    null
                }
            }
        },

        shim("applicationEnabledSetting") {
            matchMethods { method ->
                method.name == "setApplicationEnabledSetting" ||
                    method.name == "getApplicationEnabledSetting"
            }
            replaceWith { call ->
                if (!GuestComponentState.owns(call.firstArgOf<String>())) call.proceed()
                else if (call.method.name == "getApplicationEnabledSetting") {
                    GuestComponentState.applicationSetting()
                } else {
                    call.args.filterIsInstance<Int>().firstOrNull()
                        ?.let { GuestComponentState.setApplication(it) }
                    null
                }
            }
        },

        // Who installed this app.
        //
        // `IPackageManager.getInstallerPackageName` throws for a package it does not know:
        //
        //   IllegalArgumentException: Unknown package: com.unique.probe
        //
        // and that is an *unchecked* exception, unlike the `NameNotFoundException` the
        // rest of this interface answers a missing package with. Analytics and update
        // SDKs call it on almost every launch and few of them catch it, so an app asking a
        // question with an obvious answer crashed on the answer being an error.
        //
        // Answered as null, which is what a sideloaded app gets on a real device — and
        // what UNIQUE actually is, since it imports an APK rather than installing one.
        // Naming `com.android.vending` would read as "installed from Play" and send apps
        // down licensing, update and billing paths that cannot work here.
        shim("getInstallerPackageName") {
            replaceWith { call ->
                if (call.args.filterIsInstance<String>().firstOrNull() == packageName) null
                else call.proceed()
            }
        },

        // Permissions are answered from the *instance's* state, narrowed by what the host
        // actually holds - see VirtualPermissions. The package name is still rewritten,
        // because a query UNIQUE does not recognise falls through to the platform and must
        // arrive there naming a package the platform knows.
        shim("permissionCheck") {
            matchMethods { method -> VirtualPermissions.isPermissionCheck(method) }
            rewriteAll<String>(matching = { it == packageName }) { hostPackageName }
            replaceWith { call ->
                val permission = VirtualPermissions.permissionArgOf(call.args)
                if (permission == null) call.proceed()
                else VirtualPermissions.check(permission)
                    .also { VirtualPermissions.reportAnswer(permission, it) }
            }
        },
    )

    /** Set once by the graft so permission queries can be redirected to the host. */
    @Volatile
    var hostPackageName: String = "android"

    /** The package name an enumerated entry carries, whichever info class it is. */
    private fun packageNameOf(element: Any?): String? = when (element) {
        null -> null
        is PackageInfo -> element.packageName
        is ApplicationInfo -> element.packageName
        else -> null
    }

    /**
     * The `flags` argument, found by shape rather than by index.
     *
     * The width changed and the position does not generalise: `getPackageInfo` is
     * `(String, long flags, int userId)` on API 33+ and `(String, int flags, int userId)`
     * before it, while `getInstalledPackages` is `(long flags, int userId)` and
     * `getPackagesHoldingPermissions` puts a `String[]` first.
     *
     * The rule that covers all of them: flags became `long` in API 33 and `userId` stayed
     * `int`, so a `long` parameter *is* the flags. Where there is none, the flags are the
     * first of the two `int`s — the last one is always `userId`.
     */
    private fun flagsOf(call: com.unique.core.common.shim.ShimCall): Long {
        val types = call.method.parameterTypes
        val longIndex = types.indexOfFirst { it == Long::class.javaPrimitiveType }
        if (longIndex >= 0) return (call.args.getOrNull(longIndex) as? Long) ?: 0L
        val ints = types.withIndex().filter { it.value == Int::class.javaPrimitiveType }
        if (ints.size < 2) return 0L
        return ((call.args.getOrNull(ints.first().index) as? Int) ?: 0).toLong()
    }

    /**
     * Builds the `PackageInfo`, filling exactly the arrays the caller asked for.
     *
     * The flags were ignored, which is not a small omission: `getPackageInfo(pkg,
     * GET_ACTIVITIES)` came back with `activities == null`, and the caller — a launcher
     * shortcut helper, a deep-link router, an SDK checking that its own activity is
     * declared — either NPEs or concludes the app is misconfigured. `GET_META_DATA` is
     * the same story one level down.
     */
    private fun buildPackageInfo(
        packageName: String,
        manifest: ApkManifest,
        applicationInfo: ApplicationInfo,
        components: GuestComponents,
        flags: Long,
    ): PackageInfo = PackageInfo().apply {
        fun has(flag: Int) = (flags and flag.toLong()) != 0L
        if (has(PackageManager.GET_ACTIVITIES)) activities = components.activities()
        if (has(PackageManager.GET_SERVICES)) services = components.services()
        if (has(PackageManager.GET_RECEIVERS)) receivers = components.receivers()
        if (has(PackageManager.GET_PROVIDERS)) providers = components.providers()
        if (has(PackageManager.GET_PERMISSIONS)) {
            permissions = manifest.declaredPermissions.map { declared ->
                android.content.pm.PermissionInfo().apply {
                    name = declared.name
                    this.packageName = packageName
                    protectionLevel = declared.protectionLevel
                }
            }.toTypedArray()
        }
        this.packageName = packageName
        this.applicationInfo = applicationInfo
        versionName = manifest.versionName
        @Suppress("DEPRECATION")
        versionCode = manifest.versionCode.toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode = manifest.versionCode
        }
        sharedUserId = manifest.sharedUserId
        firstInstallTime = System.currentTimeMillis()
        lastUpdateTime = firstInstallTime
        requestedPermissions = manifest.usesPermissions.toTypedArray()
        // REQUESTED_PERMISSION_REQUIRED is not in the SDK surface; its value (1) has been
        // stable since API 1 and the flags array must be the same length as the names.
        requestedPermissionsFlags = IntArray(manifest.usesPermissions.size) { 1 }

        // Signatures, copied from what the platform's own parser makes of the APK.
        //
        // Apps check their own signature far more often than one would guess: integrity
        // checks, licence checks, and every Google API whose key is bound to a signing
        // certificate. A null here is not a missing nicety - it is an app that decides it
        // has been tampered with and refuses to start, which looks like UNIQUE breaking it.
        // Only when asked. Reading the property waits for the background parse, and the
        // `getPackageInfo` that `LoadedApk` makes during the graft asks for neither.
        @Suppress("DEPRECATION")
        val wantsSignatures = has(PackageManager.GET_SIGNATURES) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                has(PackageManager.GET_SIGNING_CERTIFICATES))
        if (wantsSignatures) archiveSignatures?.let { archive ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingInfo = archive.signingInfo
            }
            // Both forms, always. From API 28 the archive parser fills `signingInfo` and
            // leaves the deprecated `signatures` array null - but the *real*
            // PackageManager still fills `signatures` for a caller that asked with
            // GET_SIGNATURES, and plenty of apps and libraries still do. Populating only
            // what the parser handed back would leave those reading null, which is
            // exactly the "the app thinks it was tampered with" failure this exists to
            // prevent.
            @Suppress("DEPRECATION")
            val fromArchive = archive.signatures
            @Suppress("DEPRECATION")
            signatures = when {
                fromArchive != null && fromArchive.isNotEmpty() -> fromArchive
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    archive.signingInfo?.let { info ->
                        if (info.hasMultipleSigners()) info.apkContentsSigners
                        else info.signingCertificateHistory
                    }
                else -> null
            }
        }
    }

    /**
     * The signing information for the APK on disk, parsed once per process.
     *
     * Asked of the *real* `PackageManager` rather than parsed by UNIQUE. Signature
     * verification is exactly the wrong place to have a second implementation: the
     * platform's answer is by definition the one an app would have got if it were
     * installed, including which of v1/v2/v3 it honours and how it handles rotation.
     *
     * Loaded on a thread of its own, and waited for only by a caller that actually asked
     * for signatures. `getPackageArchiveInfo` verifies the APK's signing block, which
     * means digesting **every byte of the APK** — 2.5 seconds for a 1.6 GB game, on the
     * main thread, in the middle of a launch:
     *
     * ```
     * MIUIScout App: Enter APP_SCOUT_WARNING State (duration=2501ms … w=159)
     *   at NativeCrypto.EVP_DigestUpdateDirect
     *   at ApkSignatureVerifier.verify
     *   at VirtualPackageManagerHook.loadArchiveSignatures
     *   at AppBootstrap.graft
     * ```
     *
     * The OEM's hang watchdog saw it before the user did. Nothing in the graft needs the
     * signature — `LoadedApk` asks for `GET_ACTIVITIES`-less `PackageInfo` — so the work
     * belongs off the launch path and in front of the first caller who wants the answer.
     */
    private val signatureLoad = java.util.concurrent.atomic.AtomicReference<
        java.util.concurrent.Future<PackageInfo?>>()

    /** Blocks until the background parse finishes; null when it was never started. */
    private val archiveSignatures: PackageInfo?
        get() = runCatching { signatureLoad.get()?.get() }.getOrNull()

    private fun startSignatureLoad(hostContext: Context, apkPath: String, packageName: String) {
        val task = java.util.concurrent.FutureTask {
            val info = loadArchiveSignatures(hostContext, apkPath)
            Diagnostics.info(
                DiagChannel.LAUNCH, "SIGNATURES_LOADED",
                mapOf(
                    "package" to packageName,
                    "signers" to signerCount(info).toString(),
                    "hasSigningInfo" to (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info?.signingInfo != null
                        ).toString(),
                ),
            )
            info
        }
        signatureLoad.set(task)
        // A plain thread rather than a pool: it runs once per process and it must not be
        // a daemon of anything the guest can shut down.
        Thread(task, "unique-signatures").apply { isDaemon = true }.start()
    }

    /**
     * How many signers the archive actually yielded, whichever form carried them.
     *
     * Counting only the deprecated array reported zero for every APK on API 28+, which
     * made a working signature load look like a failed one.
     */
    private fun signerCount(info: PackageInfo?): Int {
        if (info == null) return 0
        @Suppress("DEPRECATION")
        val legacy = info.signatures?.size ?: 0
        if (legacy > 0) return legacy
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0
        val signing = info.signingInfo ?: return 0
        return if (signing.hasMultipleSigners()) signing.apkContentsSigners.size
        else signing.signingCertificateHistory.size
    }

    private fun loadArchiveSignatures(hostContext: Context, apkPath: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return runCatching {
            hostContext.packageManager.getPackageArchiveInfo(apkPath, flags)
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "SIGNATURE_PARSE_FAILED",
                mapOf("apk" to apkPath, "error" to it.toString()),
            )
            null
        }
    }
}
