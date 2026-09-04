package com.unique.core.vam

import android.content.ComponentName
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
        activityInfoOf: (String) -> Any?,
        hostContext: Context? = null,
        apkPath: String? = null,
    ): Boolean {
        if (installedFor == packageName) return true

        if (hostContext != null && apkPath != null) {
            archiveSignatures = loadArchiveSignatures(hostContext, apkPath)
            Diagnostics.info(
                DiagChannel.LAUNCH, "SIGNATURES_LOADED",
                mapOf(
                    "package" to packageName,
                    "signers" to signerCount(archiveSignatures).toString(),
                    "hasSigningInfo" to (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            archiveSignatures?.signingInfo != null
                        ).toString(),
                ),
            )
        }

        val target = SystemServiceHook.TARGETS.first { it.serviceName == "package" }
        val report = SystemServiceHook.install(
            target,
            shims(packageName, manifest, applicationInfo, activityInfoOf),
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
        activityInfoOf: (String) -> Any?,
    ): List<MethodShim> = listOf(

        // The one LoadedApk depends on. Returning null here is what makes the platform
        // conclude the package is not installed and refuse to build a class loader.
        shim("getPackageInfo") {
            replaceWith { call ->
                if (call.firstArgOf<String>() == packageName) {
                    buildPackageInfo(packageName, manifest, applicationInfo)
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
                    activityInfoOf(component.className) ?: call.proceed()
                } else call.proceed()
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

    private fun buildPackageInfo(
        packageName: String,
        manifest: ApkManifest,
        applicationInfo: ApplicationInfo,
    ): PackageInfo = PackageInfo().apply {
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
        archiveSignatures?.let { archive ->
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
     */
    @Volatile private var archiveSignatures: PackageInfo? = null

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
