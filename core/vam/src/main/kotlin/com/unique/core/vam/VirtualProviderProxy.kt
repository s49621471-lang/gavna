package com.unique.core.vam

import android.content.AttributionSource
import com.unique.core.hook.Reflect
import java.lang.reflect.Proxy

/**
 * The object that makes a guest's provider calls acceptable to the platform.
 *
 * Every call through an `IContentProvider` carries an `AttributionSource` naming the
 * caller, and the provider on the other side checks it against the calling uid:
 *
 * ```
 * SecurityException: Package com.example.app does not belong to 10300
 * ```
 *
 * A guest's `Context` names the guest, because that is the entire point of the graft, and
 * the platform has never heard of it. So every source that goes out is replaced with the
 * host's, which is valid for the uid the call actually comes from.
 *
 * The host's source is substituted whole rather than edited: an `AttributionSource` carries
 * a token the system registered for it, and a tokened source with the package changed is
 * something the platform has every right to reject.
 *
 * Extracted from the `getContentProvider` shim because that is no longer the only place
 * that needs it. A provider acquired *before* the graft — and the framework acquires the
 * settings provider before any guest code exists — never passes through that shim, and has
 * to be wrapped where it sits.
 */
internal object VirtualProviderProxy {

    /** Whether [provider] is already one of ours, so wrapping twice is avoided. */
    fun isWrapped(provider: Any?): Boolean =
        provider != null && Proxy.isProxyClass(provider.javaClass)

    /**
     * Returns [provider] with every outbound `AttributionSource` replaced by [hostSource].
     *
     * Settings reads are answered locally where the instance has its own answer for them:
     * `ANDROID_ID` is per-instance, and two clones that report the same one are one
     * installation to anything that fingerprints the device.
     */
    fun wrap(provider: Any, hostSource: AttributionSource): Any? {
        if (isWrapped(provider)) return provider
        val iface = Reflect.findClass("android.content.IContentProvider") ?: return null
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val rewritten = args?.map { arg ->
                if (arg is AttributionSource) hostSource else arg
            }?.toTypedArray()
            val answered = if (method.name == "call" && rewritten != null) {
                VirtualSettings.answerSettingsCall(rewritten)
            } else {
                null
            }
            if (answered != null) return@newProxyInstance answered
            try {
                method.invoke(provider, *(rewritten ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
