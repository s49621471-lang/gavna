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
        ServiceTarget("notification", "android.app.INotificationManager\$Stub"),
        ServiceTarget("appops", "com.android.internal.app.IAppOpsService\$Stub"),
        ServiceTarget("alarm", "android.app.IAlarmManager\$Stub"),
        ServiceTarget("jobscheduler", "android.app.job.IJobScheduler\$Stub"),
        ServiceTarget("window", "android.view.IWindowManager\$Stub"),
        ServiceTarget("clipboard", "android.content.IClipboard\$Stub"),
        ServiceTarget("account", "android.accounts.IAccountManager\$Stub"),
        ServiceTarget("permissionmgr", "android.permission.IPermissionManager\$Stub"),
        ServiceTarget("media_session", "android.media.session.ISessionManager\$Stub"),
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
        val holder = field.get(null) ?: return false

        val singletonClass = Reflect.findClass("android.util.Singleton")
        if (singletonClass != null && singletonClass.isInstance(holder)) {
            val instance = Reflect.findField(singletonClass, "mInstance") ?: return false
            instance.set(holder, shimmed)
            return true
        }
        // A plain static field holding the interface directly.
        field.set(null, shimmed)
        true
    }.getOrDefault(false)
}
