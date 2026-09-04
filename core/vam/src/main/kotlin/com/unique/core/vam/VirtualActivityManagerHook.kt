package com.unique.core.vam

import android.content.AttributionSource
import android.content.Context
import android.content.Intent
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.SystemServiceHook
import com.unique.core.hook.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Presents the host's identity to system services on the guest's behalf.
 *
 * Inside a virtual process every `Context` reports the virtual package, which is the
 * whole point — but that name also travels outward on every framework call as the
 * `callingPackage` argument, and `system_server` checks it against the *real* uid:
 *
 * ```
 * SecurityException: Given calling package com.unique.probe does not match caller's uid 10109
 * ```
 *
 * Nothing works past the first framework call without this. `PhoneWindow`'s constructor
 * alone reads a setting, which acquires a content provider, which fails.
 *
 * ## Which arguments are rewritten, and why not all of them
 *
 * The rule is: **on methods an app calls on its own behalf, any argument that is exactly
 * the virtual package name becomes the host package name.** Those methods are identified
 * by carrying an `IApplicationThread` parameter, which is the framework's own marker for
 * "this call comes from an app process about itself".
 *
 * A blanket rewrite across the whole interface would be actively dangerous:
 * `forceStopPackage(String, int)` takes a package name as *data*, and rewriting it would
 * make a guest's call stop UNIQUE itself. It has no `IApplicationThread`, so the
 * predicate excludes it. A small allowlist covers the identity-bearing methods that lack
 * one, of which `getIntentSender` is the important case.
 */
object VirtualActivityManagerHook {

    private const val APPLICATION_THREAD = "android.app.IApplicationThread"

    /**
     * Methods that carry a calling package but no `IApplicationThread`.
     *
     * Kept deliberately short. Each entry is here because the argument is the caller's own
     * identity, never a package being acted upon.
     */
    private val IDENTITY_METHODS = setOf(
        "getIntentSender",
        "getIntentSenderWithFeature",
        "checkUriPermission",
        "grantUriPermission",
        "revokeUriPermission",
        "setServiceForeground",
    )

    @Volatile private var installedFor: String? = null

    val boundPackage: String? get() = installedFor

    @Synchronized
    fun install(virtualPackage: String, hostContext: Context): Boolean {
        if (installedFor == virtualPackage) return true

        val hostPackage = hostContext.packageName
        val target = SystemServiceHook.TARGETS.first { it.serviceName == "activity" }
        val report = SystemServiceHook.install(
            target,
            shims(virtualPackage, hostPackage, hostContext.attributionSource),
        )
        if (!report.installed) {
            Diagnostics.error(
                DiagChannel.LAUNCH, "VAM_HOOK_FAILED",
                mapOf("package" to virtualPackage, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        installedFor = virtualPackage
        Diagnostics.info(
            DiagChannel.LAUNCH, "VAM_HOOK_INSTALLED",
            mapOf(
                "package" to virtualPackage,
                "host" to hostPackage,
                "methods" to (report.bind?.bound?.size?.toString() ?: "0"),
            ),
        )
        return true
    }

    private fun shims(
        virtualPackage: String,
        hostPackage: String,
        hostSource: AttributionSource,
    ): List<MethodShim> = listOf(

        // Registered first, because the first shim that binds to a method wins and this
        // one needs both halves: the outbound package rewrite *and* a wrapped provider.
        shim("getContentProvider") {
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteResult { holder -> wrapProviderHolder(holder, hostSource) }
        },

        // Service starts and binds have to be routed onto a stub the host declares:
        // system_server will not start a component of a package it has never installed.
        shim("startService") {
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteFirst<Intent> { intent -> routeService(hostPackage, intent) }
        },
        shim("bindService") {
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteFirst<Intent> { intent -> routeService(hostPackage, intent) }
        },
        shim("bindIsolatedService") {
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteFirst<Intent> { intent -> routeService(hostPackage, intent) }
        },
        shim("stopService") {
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteFirst<Intent> { intent -> routeService(hostPackage, intent) }
        },

        shim("callerIdentity") {
            matchMethods { method -> carriesCallerIdentity(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
        },
    )

    /**
     * Rewrites a service intent onto a stub, or returns it unchanged.
     *
     * Unchanged is the right answer for anything that is not a virtual service: UNIQUE's
     * own components share this process, and rewriting their intents would break them.
     *
     * Implicit intents - no component - are left alone and reported. Resolving one needs
     * the guest's intent filters, which is provider/receiver work; silently starting the
     * wrong service would be far worse than a start that visibly does nothing.
     */
    private fun routeService(hostPackage: String, intent: Intent): Intent {
        val ready = AppBootstrap.current ?: return intent
        val component = intent.component
        if (component == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "SERVICE_INTENT_IMPLICIT",
                mapOf("action" to (intent.action ?: "-"), "package" to ready.params.packageName),
            )
            return intent
        }
        if (component.packageName != ready.params.packageName) return intent

        val entry = ready.manifest.components.firstOrNull {
            it.kind == ComponentKind.SERVICE && it.className == component.className
        } ?: run {
            Diagnostics.warn(
                DiagChannel.PROCESS, "SERVICE_NOT_DECLARED",
                mapOf("service" to component.className, "package" to ready.params.packageName),
            )
            return intent
        }

        return VirtualServiceRouter.outbound(hostPackage, intent, ready.params, entry) ?: intent
    }

    /**
     * Wraps the `IContentProvider` handed back by `getContentProvider`.
     *
     * Acquiring the provider is only half the problem. Every call *through* it carries an
     * `AttributionSource`, built from the guest `Context` and therefore naming the virtual
     * package, and the provider side checks it against the caller's uid:
     *
     * ```
     * SecurityException: Package com.unique.probe does not belong to 10109
     * ```
     *
     * The host's own `AttributionSource` is substituted rather than a rewritten copy of
     * the guest's: an `AttributionSource` carries a token the system registered for it,
     * and editing the package on a tokened source would produce something the platform
     * has every right to reject. The host's is already valid for the host's uid.
     */
    private fun wrapProviderHolder(holder: Any?, hostSource: AttributionSource): Any? {
        if (holder == null) return null
        val providerField = runCatching {
            holder.javaClass.getField("provider")
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "PROVIDER_HOLDER_SHAPE_UNKNOWN",
                mapOf("class" to holder.javaClass.name),
            )
            return holder
        }
        val provider = runCatching { providerField.get(holder) }.getOrNull() ?: return holder
        if (Proxy.isProxyClass(provider.javaClass)) return holder // already wrapped

        val iface = Reflect.findClass("android.content.IContentProvider") ?: return holder
        val wrapped = Proxy.newProxyInstance(
            iface.classLoader, arrayOf(iface),
        ) { _, method, args ->
            val rewritten = args?.map { arg ->
                if (arg is AttributionSource) hostSource else arg
            }?.toTypedArray()
            try {
                method.invoke(provider, *(rewritten ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
        runCatching { providerField.set(holder, wrapped) }
        return holder
    }

    internal fun carriesCallerIdentity(method: Method): Boolean {
        if (method.name in IDENTITY_METHODS) return true
        return method.parameterTypes.any { it.name == APPLICATION_THREAD }
    }
}
