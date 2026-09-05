package com.unique.core.hook

import android.os.Build
import android.os.IBinder
import android.os.IInterface
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.ShimBindResult
import com.unique.core.common.shim.ShimRegistry
import com.unique.core.diagnostics.Diagnostics
import java.lang.reflect.Proxy

/**
 * Names of the system services UNIQUE proxies, with the interface each one exposes.
 *
 * The interface name is resolved at runtime rather than compiled against, so a release
 * that renames or restructures an interface degrades to "this service is not proxied,
 * and Diagnostics says so" instead of a `NoClassDefFoundError` at process start.
 */
data class ServiceTarget(
    val serviceName: String,
    val stubClassName: String,
    /** Framework singletons that cache the *unwrapped* interface and must be re-pointed. */
    val cachedSingletons: List<SingletonRef> = emptyList(),
) {
    data class SingletonRef(val className: String, val fieldName: String)
}

/**
 * Installs shims onto system-service Binder interfaces.
 *
 * Two things must happen for an interception to be complete, and missing either one is
 * the classic way these engines end up with "the hook works sometimes":
 *
 *  1. `ServiceManager`'s cache must return a Binder whose `queryLocalInterface` yields
 *     the shimmed interface, so future `getService` callers are covered.
 *  2. Every framework singleton that already cached the *raw* interface must be
 *     re-pointed, because `ActivityManager.getService()` and friends never consult
 *     `ServiceManager` again after the first call.
 */
object SystemServiceHook {

    /**
     * The services UNIQUE proxies and where the framework caches each one.
     *
     * Singleton field names are those used from Android 12 through 16. A field that is
     * absent on a given device is skipped with a diagnostic; it is not fatal, because a
     * release that removes a cache has also removed the staleness problem it caused.
     */
    val TARGETS: List<ServiceTarget> = listOf(
        ServiceTarget(
            "activity", "android.app.IActivityManager\$Stub",
            listOf(ServiceTarget.SingletonRef("android.app.ActivityManager", "IActivityManagerSingleton")),
        ),
        ServiceTarget(
            "activity_task", "android.app.IActivityTaskManager\$Stub",
            listOf(ServiceTarget.SingletonRef("android.app.ActivityTaskManager", "IActivityTaskManagerSingleton")),
        ),
        ServiceTarget(
            "package", "android.content.pm.IPackageManager\$Stub",
            listOf(ServiceTarget.SingletonRef("android.app.ActivityThread", "sPackageManager")),
        ),
        // `NotificationManager.sService` is a **static** field, filled by the first
        // `getService()` anywhere in the process and never consulted again. Patching only
        // the `ServiceManager` cache left whatever the guest asked before the hook — and
        // `areNotificationsEnabled()` in an `Application.onCreate` is exactly that ask —
        // holding the raw interface for the life of the process:
        //
        //   SecurityException: Caller not system or systemui or same package: uid 10302
        //       does not have android.permission.STATUS_BAR_SERVICE
        //     at NotificationManager.areNotificationsEnabled          (killed ChatGPT)
        //
        // Running `onCreate` last removes the common case; re-pointing the field removes
        // the class of it, including UNIQUE's own code and the framework touching it first.
        ServiceTarget(
            "notification", "android.app.INotificationManager\$Stub",
            listOf(ServiceTarget.SingletonRef("android.app.NotificationManager", "sService")),
        ),
        ServiceTarget("appops", "com.android.internal.app.IAppOpsService\$Stub"),
        ServiceTarget("alarm", "android.app.IAlarmManager\$Stub"),
        ServiceTarget("jobscheduler", "android.app.job.IJobScheduler\$Stub"),
        // `WindowManagerGlobal.sWindowManagerService` is the same shape of static cache,
        // and 60 of the 63 surveyed apps reach `WindowManager`.
        ServiceTarget(
            "window", "android.view.IWindowManager\$Stub",
            listOf(ServiceTarget.SingletonRef(
                "android.view.WindowManagerGlobal", "sWindowManagerService",
            )),
        ),
        ServiceTarget("clipboard", "android.content.IClipboard\$Stub"),
        ServiceTarget("account", "android.accounts.IAccountManager\$Stub"),
        // `ActivityThread.sPermissionManager`, cached the moment anything asks whether it
        // should show a permission rationale.
        ServiceTarget(
            "permissionmgr", "android.permission.IPermissionManager\$Stub",
            listOf(ServiceTarget.SingletonRef("android.app.ActivityThread", "sPermissionManager")),
        ),
        ServiceTarget("media_session", "android.media.session.ISessionManager\$Stub"),

        // Services that take the caller's own package name and check it against the
        // calling uid. A guest reaching one of these unproxied gets a SecurityException
        // naming its own package, which reads as the app being broken; see
        // VirtualIdentityHooks.CALLER_PACKAGE_SERVICES for the evidence and the rule.
        ServiceTarget("connectivity", "android.net.IConnectivityManager\$Stub"),
        ServiceTarget("restrictions", "android.content.IRestrictionsManager\$Stub"),
        ServiceTarget("locale", "android.app.ILocaleManager\$Stub"),
        ServiceTarget("power", "android.os.IPowerManager\$Stub"),
        ServiceTarget("wifi", "android.net.wifi.IWifiManager\$Stub"),
        ServiceTarget("location", "android.location.ILocationManager\$Stub"),
        ServiceTarget("audio", "android.media.IAudioService\$Stub"),
        ServiceTarget("vibrator_manager", "android.os.IVibratorManagerService\$Stub"),
        ServiceTarget("usagestats", "android.app.usage.IUsageStatsManager\$Stub"),
        ServiceTarget("netstats", "android.net.INetworkStatsService\$Stub"),
        ServiceTarget("content", "android.content.IContentService\$Stub"),
        ServiceTarget("shortcut", "android.content.pm.IShortcutService\$Stub"),

        // Added after a survey of 63 real apps (tools/apk-survey) and a second device run.
        // `mount` is not speculative: it killed a guest outright.
        //
        //   SecurityException: callingPackage does not match UID
        //     at IStorageManager$Stub$Proxy.getVolumeList
        //     at Environment.isExternalStorageManager        -> clear.una died on its first frame
        ServiceTarget("mount", "android.os.storage.IStorageManager\$Stub"),
        ServiceTarget("phone", "com.android.internal.telephony.ITelephony\$Stub"),
        // No `download` entry, and that is a correction rather than an omission.
        //
        // `Context.DOWNLOAD_SERVICE` is the string "download", but there is no binder
        // service behind it: `SystemServiceRegistry` builds `DownloadManager` from a
        // `ContentResolver`, and everything it does goes to `content://downloads`. So the
        // target resolved to nothing, ten times per device run:
        //
        //   IDENTITY_HOOK_FAILED service=download reason=service not available
        //
        // A guest's downloads travel the provider path, which already carries UNIQUE's
        // attribution source. Rule 8: a hook that binds to nothing is removed, not left
        // looking installed.
        ServiceTarget("device_policy", "android.app.admin.IDevicePolicyManager\$Stub"),
        ServiceTarget("media.camera", "android.hardware.ICameraService\$Stub"),
        ServiceTarget("telecom", "com.android.internal.telecom.ITelecomService\$Stub"),
        ServiceTarget("media_router", "android.media.IMediaRouterService\$Stub"),

        // Found by running a real app rather than by surveying one. Fossify Gallery calls
        // `AppWidgetManager.getAppWidgetIds` from its `MainActivity.onCreate` — a home-screen
        // widget is ordinary in a gallery, a music player or a weather app — and
        // `AppWidgetServiceImpl.SecurityPolicy.enforceCallFromPackage` checks the package
        // it is handed against the calling uid:
        //
        //   SecurityException: Package org.fossify.gallery does not belong to 10108
        //     at IAppWidgetService$Stub$Proxy.getAppWidgetIds
        //     at org.fossify.gallery.activities.MainActivity.onCreate     <- died here
        ServiceTarget("appwidget", "com.android.internal.appwidget.IAppWidgetService\$Stub"),
    )

    data class InstallReport(
        val serviceName: String,
        val installed: Boolean,
        val reason: String? = null,
        val bind: ShimBindResult? = null,
        val singletonsPatched: Int = 0,
    )

    /**
     * Proxies one service.
     *
     * Returns a report rather than throwing: a service that cannot be proxied on a
     * particular OEM build should disable the features that need it and be visible in
     * Diagnostics, not prevent UNIQUE from starting at all.
     */
    fun install(target: ServiceTarget, shims: List<MethodShim>): InstallReport {
        if (!HiddenApi.isGranted) {
            return InstallReport(target.serviceName, false, "hidden API access denied")
        }

        val serviceManager = Reflect.findClass("android.os.ServiceManager")
            ?: return InstallReport(target.serviceName, false, "android.os.ServiceManager not found")

        val original = runCatching {
            Reflect.findMethod(serviceManager, "getService", String::class.java)
                ?.invoke(null, target.serviceName) as? IBinder
        }.getOrNull() ?: return InstallReport(target.serviceName, false, "service not available")

        val stub = Reflect.findClass(target.stubClassName)
            ?: return InstallReport(target.serviceName, false, "${target.stubClassName} not found")

        val ifaceClass = stub.enclosingClass
            ?: return InstallReport(target.serviceName, false, "stub has no enclosing interface")

        val realInterface = runCatching {
            Reflect.findMethod(stub, "asInterface", IBinder::class.java)?.invoke(null, original)
        }.getOrNull() as? IInterface
            ?: return InstallReport(target.serviceName, false, "asInterface returned null")

        val registry = ShimRegistry(Build.VERSION.SDK_INT)
        shims.forEach { registry.register(it) }
        val (shimmed, bindResult) = registry.wrap<IInterface>(realInterface, ifaceClass)

        if (bindResult.unbound.isNotEmpty()) {
            Diagnostics.warn(
                DiagChannel.HOOK, "HOOK_BIND_FAILED",
                mapOf(
                    "service" to target.serviceName,
                    "unbound" to bindResult.unbound.joinToString(","),
                    "sdk" to Build.VERSION.SDK_INT.toString(),
                ),
            )
        }
        if (bindResult.bound.isEmpty()) {
            // Nothing matched at all, which is a different problem from "one shim missed":
            // either the release renamed everything, or UNIQUE is looking at the wrong
            // interface. Listing what the interface actually offers turns that from a
            // mystery inside the guest into a fact in the log.
            Diagnostics.warn(
                DiagChannel.HOOK, "HOOK_MATCHED_NOTHING",
                mapOf(
                    "service" to target.serviceName,
                    "interface" to ifaceClass.name,
                    "methods" to ifaceClass.methods.joinToString(",") { it.name }
                        .let { it.substring(0, minOf(it.length, 900)) },
                ),
            )
        }

        // 1. ServiceManager cache: hand out a Binder that yields the shimmed interface.
        val binderProxy = Proxy.newProxyInstance(
            IBinder::class.java.classLoader, arrayOf(IBinder::class.java),
        ) { _, method, args ->
            when (method.name) {
                "queryLocalInterface" -> shimmed
                else -> method.invoke(original, *(args ?: emptyArray()))
            }
        } as IBinder

        val cachePatched = patchServiceManagerCache(serviceManager, target.serviceName, binderProxy)

        // 2. Singletons that already hold the raw interface.
        var singletons = 0
        for (ref in target.cachedSingletons) {
            if (patchSingleton(ref, shimmed)) singletons++
            else Diagnostics.warn(
                DiagChannel.HOOK, "SINGLETON_PATCH_SKIPPED",
                mapOf("service" to target.serviceName, "field" to "${ref.className}.${ref.fieldName}"),
            )
        }

        Diagnostics.info(
            DiagChannel.HOOK, "SERVICE_HOOKED",
            mapOf(
                "service" to target.serviceName,
                "cache" to cachePatched.toString(),
                "singletons" to singletons.toString(),
                "bound" to bindResult.bound.joinToString(","),
                // Concrete method names, because a shim can bind to a method the platform
                // has stopped calling and still report itself bound.
                "matched" to bindResult.describeMatches().take(400),
            ),
        )
        return InstallReport(target.serviceName, true, null, bindResult, singletons)
    }

    /**
     * `ServiceManager.sCache` is consulted before any Binder round trip, so replacing the
     * entry there covers every later `getService` for this service.
     */
    private fun patchServiceManagerCache(
        serviceManager: Class<*>, name: String, proxy: IBinder,
    ): Boolean = runCatching {
        @Suppress("UNCHECKED_CAST")
        val cache = Reflect.get(serviceManager, "sCache") as? MutableMap<String, IBinder>
            ?: return false
        cache[name] = proxy
        true
    }.getOrDefault(false)

    /**
     * `android.util.Singleton` holds the resolved instance in `mInstance`; a plain static
     * field holds it directly. Both shapes appear across the services UNIQUE proxies.
     */
    private fun patchSingleton(ref: ServiceTarget.SingletonRef, shimmed: Any): Boolean = runCatching {
        val owner = Reflect.findClass(ref.className) ?: return false
        val field = Reflect.findField(owner, ref.fieldName) ?: return false
        val holder = field.get(null)

        val singletonClass = Reflect.findClass("android.util.Singleton")
        if (holder != null && singletonClass != null && singletonClass.isInstance(holder)) {
            val instance = Reflect.findField(singletonClass, "mInstance") ?: return false
            instance.set(holder, shimmed)
            return true
        }
        // A plain static field holding the interface directly - written whether or not it
        // is already populated. An empty one is the *interesting* case: filling it now is
        // what stops the framework resolving the service itself later, on a code path
        // (`NotificationManager.getService`) that never consults `ServiceManager` twice.
        if (!field.type.isInstance(shimmed)) return false
        field.set(null, shimmed)
        true
    }.getOrDefault(false)
}
