package com.unique.core.vam

import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import java.lang.reflect.Proxy

/**
 * Holds a guest to the platform rules of the SDK *it* was built against.
 *
 * Android gates most behaviour changes on the app's target SDK, and enforces them through
 * a compat framework rather than through `if (targetSdkVersion >= …)` at each call site.
 * In an ordinary app process `ActivityThread.handleBindApplication` installs the set of
 * changes that are *disabled* for the package being started. A `:vappN` is bound as
 * **UNIQUE**, so the set installed is UNIQUE's — and a guest built against Android 9 is
 * then held to the rules of UNIQUE's target SDK:
 *
 * ```
 * IllegalArgumentException: com.termux: Targeting S+ (version 31 and above) requires that
 *   one of FLAG_IMMUTABLE or FLAG_MUTABLE be specified when creating a PendingIntent
 *     at android.app.PendingIntent.checkPendingIntent
 *     at com.termux.app.TermuxService.buildNotification     <- died on its first screen
 * ```
 *
 * Termux targets Android 9. The two-argument `PendingIntent.getActivity` it has always
 * used is correct for it, and there is nothing its authors could do about this.
 *
 * ## Where this can act, and where it cannot
 *
 * Only for the checks the platform makes **inside the app's own process**, which is where
 * `android.compat.Compatibility` lives. A rule enforced by `system_server` against the
 * *calling uid* — `RECEIVER_EXPORTED`, for one — is not reachable from here and is
 * answered at its own call site instead; see
 * `VirtualActivityManagerHook.applyReceiverExportDefault`.
 *
 * ## Why the ids are read from the platform
 *
 * A change id is a bare `long` on a `private static final` field, and writing the number
 * into UNIQUE would be exactly the kind of transcription this codebase avoids everywhere
 * else. The field is read from the class that declares it, so the number comes from the
 * device. What UNIQUE states is the half that is *not* on the device at runtime — which
 * SDK the change is gated at, since `@EnabledAfter` has source retention.
 *
 * A change whose field cannot be found is skipped and reported. That is the correct
 * outcome for a release that has removed it: the rule it named is gone too.
 */
internal object GuestCompatChanges {

    /**
     * One gated behaviour change, identified by where the platform declares it.
     *
     * [enabledFromSdk] is the first target SDK the change applies to — `@EnabledAfter(R)`
     * means 31, `@EnabledSince(U)` means 34.
     */
    private data class Gate(
        val owner: String,
        val field: String,
        val enabledFromSdk: Int,
        val why: String,
    )

    /**
     * The changes a real application has actually died on.
     *
     * Deliberately not "every gated change": the platform has hundreds, most of them
     * benign, and disabling one that a guest's own code expects to be *enabled* would
     * introduce a fault rather than remove one. Each entry here is here because an app
     * stopped working without it, and says which app.
     */
    private val GATES = listOf(
        Gate(
            owner = "android.app.PendingIntent",
            field = "PENDING_INTENT_EXPLICIT_MUTABILITY_REQUIRED",
            enabledFromSdk = 31,
            why = "Termux (targetSdk 28) builds a PendingIntent with neither FLAG_IMMUTABLE " +
                "nor FLAG_MUTABLE, which is correct for it and throws under UNIQUE's rules",
        ),
    )

    @Volatile private var installed = false

    /** Clears the one-shot guard. Only useful between tests. */
    @Synchronized
    fun reset() {
        installed = false
    }

    /**
     * Disables, for this process, the changes that the guest's own target SDK predates.
     *
     * Installed as a delegate in front of the platform's own rather than by rewriting its
     * disabled-id array: `AppCompatCallbacks`'s field layout and `install` signature have
     * both changed, and `Compatibility.setBehaviorChangeDelegate` is the API that exists
     * for putting something in front.
     */
    @Synchronized
    fun applyFor(targetSdk: Int) {
        if (installed) return
        val disabled = GATES
            .filter { it.enabledFromSdk > targetSdk }
            .mapNotNull { gate -> idOf(gate)?.let { it to gate } }
        if (disabled.isEmpty()) {
            installed = true
            return
        }

        val compat = Reflect.findClass("android.compat.Compatibility") ?: run {
            report("COMPAT_CLASS_MISSING", mapOf("class" to "android.compat.Compatibility"))
            return
        }
        val delegateType = Reflect.findClass(
            "android.compat.Compatibility\$BehaviorChangeDelegate",
        ) ?: run {
            report("COMPAT_DELEGATE_MISSING", emptyMap())
            return
        }
        val previous = Reflect.get(compat, "sCallbacks", null)
        val ids = disabled.map { it.first }.toHashSet()

        val proxy = Proxy.newProxyInstance(
            delegateType.classLoader ?: GuestCompatChanges::class.java.classLoader,
            arrayOf(delegateType),
        ) { _, method, args ->
            val argv = args ?: emptyArray()
            if (method.name == "isChangeEnabled" && argv.size == 1) {
                val id = argv[0] as? Long
                if (id != null && id in ids) return@newProxyInstance false
            }
            when {
                previous != null -> method.invoke(previous, *argv)
                // No previous delegate is not a state a real app process is in, but the
                // platform's own default answers "enabled" for everything, so match it.
                method.returnType == java.lang.Boolean.TYPE -> true
                else -> null
            }
        }

        val set = Reflect.findMethodByName(compat, "setBehaviorChangeDelegate")
        if (set == null) {
            report("COMPAT_DELEGATE_NOT_SETTABLE", emptyMap())
            return
        }
        runCatching { set.invoke(null, proxy) }.onFailure {
            report("COMPAT_DELEGATE_INSTALL_FAILED", mapOf("error" to it.toString()))
            return
        }
        installed = true
        Diagnostics.info(
            DiagChannel.PROCESS, "COMPAT_CHANGES_DISABLED",
            mapOf(
                "targetSdk" to targetSdk.toString(),
                "changes" to disabled.joinToString(",") { (id, gate) -> "${gate.field}=$id" },
            ),
        )
    }

    /** The change id, read from the class that declares it rather than transcribed. */
    private fun idOf(gate: Gate): Long? {
        val owner = Reflect.findClass(gate.owner) ?: return null.also {
            report("COMPAT_OWNER_MISSING", mapOf("class" to gate.owner))
        }
        val value = Reflect.get(owner, gate.field, null)
        if (value !is Long) {
            Diagnostics.event(
                DiagChannel.PROCESS, DiagLevel.DEBUG, "COMPAT_CHANGE_NOT_FOUND",
                mapOf("class" to gate.owner, "field" to gate.field, "why" to gate.why),
            )
            return null
        }
        return value
    }

    private fun report(code: String, fields: Map<String, String>) =
        Diagnostics.warn(DiagChannel.PROCESS, code, fields)
}
