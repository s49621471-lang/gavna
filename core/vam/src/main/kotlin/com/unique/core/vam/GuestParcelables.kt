package com.unique.core.vam

import android.content.Intent
import android.os.BaseBundle
import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Makes a guest's own `Parcelable` classes findable in the intents handed to it.
 *
 * ## The crash, and why the obvious fix did not work
 *
 * An `Intent` crosses a process boundary as a parcelled `Bundle`. Google Sign-In dies on
 * the other side of that boundary:
 *
 * ```
 * BadParcelableException: ClassNotFoundException when unmarshalling:
 *     com.google.android.gms.auth.api.signin.internal.SignInConfiguration
 *   at android.os.Parcel.readParcelableCreatorInternal
 *   at android.os.Bundle.getParcelable
 *   at …signin.internal.SignInHubActivity.onCreate
 * ```
 *
 * The class is in the guest's **own** APK — the app bundles `play-services-auth` — so
 * nothing is missing except a loader that can see it. The obvious answer,
 * `intent.setExtrasClassLoader(guestLoader)`, went in one pass ago and the next phone log
 * had the identical crash. It is worth writing down why, because the mistake is a natural
 * one and the code read as correct:
 *
 *  - `BaseBundle.setClassLoader` writes one field, `mClassLoader`, and that field is read
 *    in exactly one place: `unparcel()`.
 *  - `unparcel()` builds the map **lazily**. Each value becomes a `Parcel.LazyValue` that
 *    captures the loader *as it is at that moment* — `readLazyValue(loader)` stores it —
 *    and resolves the class later, when something asks for the value.
 *  - So the loader that matters is the one in place at the moment of the **first read of
 *    any key**, and after that no `setExtrasClassLoader` can change anything.
 *
 * And UNIQUE always reads first. Every intent it hands a guest carries its own routing
 * extras, and `VirtualLaunchParams.from(intent)` is the first line of every rewrite — so
 * the bundle was always unparcelled under UNIQUE's loader before the guest's loader was
 * even known, let alone set.
 *
 * ## The fix
 *
 * [loader] is a single `ClassLoader` that does not resolve anything itself: it forwards to
 * whichever guest this process is serving, *at the moment it is asked*. Setting it costs
 * nothing and can be done before anything is known, which is the point — it goes on before
 * the first read rather than after it. When the guest's own `onCreate` finally asks for
 * `config`, the `LazyValue` calls through to a graft that is by then complete.
 *
 * That it is one shared instance matters twice over: `Parcel` caches `Parcelable.Creator`s
 * per class loader, and ART caches resolved classes per loader, so a new loader per intent
 * would defeat both.
 *
 * This is not a Google-specific fix and the flow that found it is a coincidence. Any app
 * that puts one of its own `Parcelable`s into an `Intent` — a launch argument, an activity
 * result, a broadcast payload — hits exactly this.
 */
object GuestParcelables {

    /**
     * The loader every guest bundle is given, before anything reads one.
     *
     * `ClassLoader(null)` is a loader with the boot loader as its effective parent, and
     * every method that can answer a name is overridden to forward instead. Forwarding
     * rather than sub-classing the guest's loader is what makes it installable early: the
     * guest's loader does not exist yet when this is set.
     */
    val loader: ClassLoader = ForwardingClassLoader(::target)

    /**
     * A loader that resolves nothing itself and asks [target] every time.
     *
     * Taking the target as a function rather than reading it directly is what makes the
     * behaviour testable off a device — and it is also the whole mechanism: the answer is
     * allowed to change, and the interesting case is a name asked for after the graft
     * through a loader installed before it.
     */
    internal class ForwardingClassLoader(
        private val target: () -> ClassLoader,
    ) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            target().loadClass(name)

        override fun findClass(name: String): Class<*> = target().loadClass(name)

        override fun getResource(name: String): java.net.URL? = target().getResource(name)

        override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
            target().getResources(name)

        override fun getResourceAsStream(name: String): java.io.InputStream? =
            target().getResourceAsStream(name)

        override fun toString(): String =
            "UNIQUE-forwarding->" + runCatching { target().toString() }.getOrDefault("?")
    }

    /**
     * Whichever loader can answer for the guest right now.
     *
     * Before the graft there is no guest, and UNIQUE's own loader is both the only answer
     * available and the right one — the only classes anything asks for that early are the
     * framework's, which every loader in the process chains to. A negative result is not
     * cached by ART, so a name that fails before the graft is looked up properly after it.
     */
    private fun target(): ClassLoader =
        AppBootstrap.current?.application?.classLoader
            ?: GuestParcelables::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()

    /**
     * Gives [intent]'s extras the forwarding loader. Call **before** reading any extra.
     *
     * A no-op on an intent with no extras, and deliberately silent about it: most intents
     * carry none, and this runs on the main thread for every transaction a `:vappN`
     * receives.
     */
    fun adopt(intent: Intent?) {
        if (intent == null) return
        runCatching { intent.setExtrasClassLoader(loader) }
            .onFailure {
                Diagnostics.warn(
                    DiagChannel.LAUNCH, "INTENT_CLASSLOADER_UNSET",
                    mapOf("error" to it.toString()),
                )
            }
    }

    /** The same for a bare `Bundle` — a broadcast payload, a saved instance state. */
    fun adopt(bundle: BaseBundle?) {
        if (bundle == null) return
        runCatching {
            when (bundle) {
                is Bundle -> bundle.classLoader = loader
                else -> Unit
            }
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "BUNDLE_CLASSLOADER_UNSET",
                mapOf("error" to it.toString()),
            )
        }
    }
}
