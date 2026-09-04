package com.unique.core.vam

import android.content.ComponentName
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
    ): Boolean {
        if (installedFor == packageName) return true

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

        // Permissions can only ever be narrowed to what the host actually holds, so the
        // query is redirected to the host package rather than answered locally. A local
        // "granted" would be a lie the platform would then refuse to honour.
        shim("checkPermission") {
            rewriteAll<String>(matching = { it == packageName }) { hostPackageName }
        },
        shim("checkUidPermission") {
            rewriteAll<String>(matching = { it == packageName }) { hostPackageName }
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
    }
}
