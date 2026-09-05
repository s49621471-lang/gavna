package com.unique.core.vam

import android.content.Context
import android.content.pm.ApplicationInfo
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect

/**
 * Gives the guest its own network security policy instead of UNIQUE's.
 *
 * `ActivityThread.handleBindApplication` installs a process-wide policy from the app's
 * `android:networkSecurityConfig` — cleartext rules, certificate pinning, which CAs are
 * trusted — long before a `:vappN` becomes anybody. So a guest inherited UNIQUE's, which is
 * wrong in a way that is mostly invisible and occasionally not:
 *
 * ```
 * FATAL EXCEPTION: nfz Dispatcher
 * java.lang.NullPointerException: Attempt to invoke interface method
 *     'DomainEncryptionMode NetworkSecurityPolicy.getDomainEncryptionMode(String)'
 *     on a null object reference
 *   at com.android.org.conscrypt.metrics.TlsEncryptedClientHelloHandshake$Builder.calculateSkipReason
 *   at com.android.org.conscrypt.ConscryptEngineSocket.close
 * ```
 *
 * — Conscrypt asking the policy about a domain while closing a TLS socket, on a device
 * where the guest's config was never installed, and taking the app's network dispatcher
 * down with it.
 *
 * The invisible half matters more. An app that pins certificates, or that permits cleartext
 * to one host, is describing a security decision; running it under the host's policy either
 * relaxes it or breaks it, and neither is something a virtualization layer may do silently.
 *
 * `NetworkSecurityConfigProvider.install` is hidden framework API and is reached
 * reflectively. A failure is reported rather than thrown: an app whose policy could not be
 * installed still runs, under the host's, and that is worth knowing rather than crashing
 * over.
 */
internal object VirtualNetworkSecurity {

    fun install(context: Context, appInfo: ApplicationInfo, configResId: Int) {
        val provider = Reflect.findClass("android.security.net.config.NetworkSecurityConfigProvider")
        if (provider == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "NETWORK_SECURITY_PROVIDER_MISSING",
                mapOf("package" to appInfo.packageName),
            )
            return
        }
        val method = Reflect.findMethod(provider, "install", Context::class.java)
        if (method == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "NETWORK_SECURITY_INSTALL_MISSING",
                mapOf("package" to appInfo.packageName),
            )
            return
        }
        runCatching { method.invoke(null, context) }
            .recoverCatching { installByHand(context) }
            .fold(
            onSuccess = {
                Diagnostics.info(
                    DiagChannel.PROCESS, "NETWORK_SECURITY_INSTALLED",
                    mapOf(
                        "package" to appInfo.packageName,
                        // Zero means the app declares no config and gets the platform
                        // default for its targetSdk — still the guest's answer, not the
                        // host's, because it is derived from the guest's ApplicationInfo.
                        "configRes" to configResId.toString(),
                        "targetSdk" to appInfo.targetSdkVersion.toString(),
                    ),
                )
            },
            onFailure = {
                // A warning, not an error: the guest still runs, under the host's policy.
                // The *cause* is unwrapped because a bare InvocationTargetException says
                // nothing, and this is exactly the sort of thing that differs per OEM.
                val cause = (it as? java.lang.reflect.InvocationTargetException)
                    ?.targetException ?: it
                Diagnostics.warn(
                    DiagChannel.PROCESS, "NETWORK_SECURITY_INSTALL_FAILED",
                    mapOf(
                        "package" to appInfo.packageName,
                        "error" to cause.toString(),
                        "detail" to "the guest keeps UNIQUE's network security policy",
                    ),
                )
            },
        )
    }

    /**
     * The same thing `install` does, assembled here when calling it does not work.
     *
     * `NetworkSecurityConfigProvider.install` builds an `ApplicationConfig` from a
     * `ManifestConfigSource` over the context and publishes it in two places. Some OEM
     * builds have moved or renamed the entry point; the two classes it works on have been
     * stable since Android 7, so doing the assembly here is the more portable half.
     */
    private fun installByHand(context: Context) {
        val appConfigClass = Reflect.findClass("android.security.net.config.ApplicationConfig")
            ?: error("ApplicationConfig not found")
        val sourceClass = Reflect.findClass("android.security.net.config.ManifestConfigSource")
            ?: error("ManifestConfigSource not found")
        val source = sourceClass.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
        val configSourceInterface = appConfigClass.declaredConstructors
            .firstOrNull { it.parameterTypes.size == 1 }
            ?: error("ApplicationConfig has no single-argument constructor")
        val config = configSourceInterface.apply { isAccessible = true }.newInstance(source)
        appConfigClass.getDeclaredMethod("setDefaultInstance", appConfigClass)
            .apply { isAccessible = true }
            .invoke(null, config)
    }
}
