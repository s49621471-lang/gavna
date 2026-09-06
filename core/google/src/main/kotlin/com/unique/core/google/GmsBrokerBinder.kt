package com.unique.core.google

import android.os.IBinder
import android.os.Parcel
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.diagnostics.Diagnostics

/**
 * The Play services binder, with the guest's package swapped for UNIQUE's on the way out.
 *
 * ## What this makes work
 *
 * Play services refuses any request whose calling package does not belong to the calling
 * uid. Inside UNIQUE the uid is UNIQUE's and the name is the guest's, so the refusal is
 * unconditional — and it is the *only* thing standing between a guest and every Google
 * API. With the name corrected, the bind succeeds and the client gets a working service:
 * Maps, the advertising ID, Firebase, Dynamite modules, analytics, FCM.
 *
 * ## What it does not make work, and this is worth being exact about
 *
 * Play services now believes the caller is `com.unique`, because that is the truth about
 * the uid. Anything that is *about who the app is* is therefore answered for UNIQUE:
 *
 * - **Google sign-in that asks for an ID token or a server auth code** is validated
 *   against the OAuth client registered for the app's package and signing certificate.
 *   `com.unique` is not that app, so Play services answers `DEVELOPER_ERROR` (10). No
 *   rewriting fixes this: the check is a signature check made by another process against
 *   a record in Google's own project, and only the app's real APK, installed normally,
 *   satisfies it.
 * - **Play Integrity, SafetyNet and licence checks** attest UNIQUE, not the guest.
 *
 * The honest summary is that this turns "no Google at all" into "Google, as UNIQUE".
 * That is most of what apps use Play services for and none of what identity is for.
 * `GoogleCompatRouter` says so for `SIGN_IN`, and the device-log analyzer's `google`
 * check names `DEVELOPER_ERROR` when an app hits it, so the failure has one explanation
 * rather than looking like a bug in the app.
 *
 * ## Failure is always "leave it alone"
 *
 * Any error in locating or rebuilding the request abandons the rewrite and forwards
 * exactly the parcel the app wrote. The worst case is therefore the refusal that would
 * have happened anyway, never a malformed request reaching Play services.
 */
class GmsBrokerBinder(
    private val delegate: IBinder,
    private val guestPackage: String,
    private val hostPackage: String,
) : IBinder by delegate {

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val rewritten = runCatching { rewrite(data) }.getOrNull()
            ?: return runCatching { reportUnrewritten(code, data) }
                .let { delegate.transact(code, data, reply, flags) }
        return try {
            delegate.transact(code, rewritten, reply, flags)
        } finally {
            rewritten.recycle()
        }
    }

    /**
     * Says when a request carries the guest's name in a shape the rewrite cannot reach.
     *
     * Three passes have now been made at `Unknown calling package name`, each fixing a
     * real route and each followed by a log with the same message on a different one. The
     * pattern is not that the fixes were wrong; it is that "the rewrite did not fire" and
     * "the rewrite fired and something else refused" have looked identical in the log, so
     * every round had to start by guessing which had happened.
     *
     * This ends that. When [rewrite] declines, the parcel is scanned for the guest's
     * package as a *bare* string — one written straight into the transaction rather than
     * as a field of a `SafeParcel` object — and the interface and transaction code are
     * named. A log that carries this line says the route; one that does not says the
     * refusal came from somewhere the calling package never travelled, which is a
     * different investigation.
     *
     * Deliberately **not** rewritten here, and that is the point rather than an omission.
     * Replacing a bare string changes the byte length of everything after it, and a
     * parcel gives no way to know whether the string sits inside a length-prefixed
     * container — a `Bundle`, a typed array — whose header would then be wrong. A
     * corrupted request to Play services is a worse failure than a refused one, and it is
     * one that would present as something else entirely. The position is reported so the
     * next fix can be made against a known shape instead of a fourth guess.
     *
     * One line per interface and code, because this runs on every transaction.
     */
    private fun reportUnrewritten(code: Int, data: Parcel) {
        val descriptor = runCatching { delegate.interfaceDescriptor }.getOrNull() ?: "?"
        if (!reported.add("$descriptor#$code")) return
        val offsets = SafeParcelRewrite.findBareString(
            data.dataSize(),
            { at -> data.setDataPosition(at); data.readInt() },
            { at ->
                data.setDataPosition(at)
                runCatching { data.readString() }.getOrNull()
            },
            guestPackage,
        )
        if (offsets.isEmpty()) return
        Diagnostics.warn(
            DiagChannel.LAUNCH, "GMS_PACKAGE_NOT_REWRITTEN",
            mapOf(
                "descriptor" to descriptor,
                "code" to code.toString(),
                "package" to guestPackage,
                "bareAt" to offsets.joinToString("+"),
                "size" to data.dataSize().toString(),
                "detail" to "the name is in this request but not as a SafeParcel field",
            ),
        )
    }

    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A copy of [data] with every top-level field equal to the guest's package name
     * replaced, or null when there is nothing to replace or anything was not understood.
     */
    private fun rewrite(data: Parcel): Parcel? {
        val size = data.dataSize()
        if (size <= 0) return null
        val savedPosition = data.dataPosition()
        try {
            val readInt = { at: Int ->
                data.setDataPosition(at)
                data.readInt()
            }
            for (offset in SafeParcelRewrite.candidateOffsets(size, readInt)) {
                val located = SafeParcelRewrite.locate(offset, size, readInt) ?: continue
                val expected = SafeParcelRewrite.stringFieldSize(guestPackage)
                val targets = located.fields.filter { field ->
                    // Size first: a field that is not the right length cannot hold this
                    // string, and skipping it avoids reading at a position that may
                    // overlap a binder or a descriptor.
                    field.size == expected &&
                        run {
                            data.setDataPosition(field.dataStart)
                            runCatching { data.readString() }.getOrNull() == guestPackage &&
                                data.dataPosition() == field.end
                        }
                }
                if (targets.isEmpty()) continue
                return rebuild(data, located, targets)
            }
            return null
        } finally {
            data.setDataPosition(savedPosition)
        }
    }

    /**
     * Builds the replacement parcel.
     *
     * Everything outside the replaced fields is copied with `appendFrom`, which carries
     * binder references across — the reason the parcel is rebuilt rather than marshalled,
     * since the request holds the client's callback interface and `marshall()` refuses
     * any parcel that does.
     */
    private fun rebuild(
        data: Parcel,
        located: SafeParcelRewrite.Located,
        targets: List<SafeParcelRewrite.Field>,
    ): Parcel {
        val out = Parcel.obtain()
        out.appendFrom(data, 0, located.start)

        out.writeInt(SafeParcelRewrite.objectHeader())
        val objectSizeAt = out.dataPosition()
        out.writeInt(0)
        val bodyStart = out.dataPosition()

        for (field in located.fields) {
            if (field in targets) {
                out.writeInt(SafeParcelRewrite.variableFieldHeader(field.id))
                val fieldSizeAt = out.dataPosition()
                out.writeInt(0)
                val fieldStart = out.dataPosition()
                out.writeString(hostPackage)
                backPatch(out, fieldSizeAt, out.dataPosition() - fieldStart)
            } else {
                out.appendFrom(data, field.headerStart, field.totalLength)
            }
        }

        val bodyEnd = out.dataPosition()
        backPatch(out, objectSizeAt, bodyEnd - bodyStart)

        val tail = data.dataSize() - located.end
        if (tail > 0) out.appendFrom(data, located.end, tail)

        Diagnostics.event(
            DiagChannel.LAUNCH, DiagLevel.DEBUG, "GMS_CALLING_PACKAGE_REWRITTEN",
            mapOf(
                "from" to guestPackage,
                "to" to hostPackage,
                "fields" to targets.size.toString(),
            ),
        )
        return out
    }

    /** Writes [value] at [at] and returns the position to where it was. */
    private fun backPatch(parcel: Parcel, at: Int, value: Int) {
        val resume = parcel.dataPosition()
        parcel.setDataPosition(at)
        parcel.writeInt(value)
        parcel.setDataPosition(resume)
    }

    companion object {
        /** The packages that answer a Play services bind. */
        private val GOOGLE_PACKAGES = setOf("com.google.android.gms", "com.google.android.gsf")

        /**
         * Wraps [binder] when the bind is to Play services, otherwise returns it.
         *
         * ## Why the service package decides and the interface does not
         *
         * The first version of this allowed only `IGmsServiceBroker`, on the reasoning
         * that it is the interface `getRemoteService` goes through. A device log showed
         * what that misses: the broker was wrapped seventeen times and Firebase Analytics
         * was refused anyway, because it does not use the broker at all —
         *
         * ```
         * SERVICE_INTENT_CROSS_APP action=com.google.android.gms.measurement.START
         * GMS_BROKER_WRAPPED descriptor=…IGmsServiceBroker package=com.gordey.standarling
         * E FA: Task exception while flushing queue:
         *     SecurityException: Unknown calling package name 'com.gordey.standarling'.
         * ```
         *
         * — it binds `AppMeasurementService` directly and sends the package inside an
         * `AppMetadata`. There is no reason to expect that to be the last such service,
         * and an allowlist of interfaces has to be extended once per discovery.
         *
         * So the gate is "is this a bind to Play services", which is the actual condition,
         * and the rewrite decides for itself whether there is anything to do: it only
         * changes a field whose value is exactly the guest's package name, and leaves the
         * parcel untouched when there is none. A request that carries no calling package
         * passes through unmodified whatever interface it belongs to.
         */
        fun wrapIfBroker(
            binder: IBinder?,
            servicePackage: String?,
            guestPackage: String,
            hostPackage: String,
        ): IBinder? {
            if (binder == null || guestPackage == hostPackage) return binder
            if (servicePackage !in GOOGLE_PACKAGES) return binder
            if (binder is GmsBrokerBinder) return binder
            Diagnostics.info(
                DiagChannel.LAUNCH, "GMS_BROKER_WRAPPED",
                mapOf(
                    "descriptor" to (
                        runCatching { binder.interfaceDescriptor }.getOrNull() ?: "?"
                        ),
                    "package" to guestPackage,
                    "service" to servicePackage.orEmpty(),
                ),
            )
            return GmsBrokerBinder(binder, guestPackage, hostPackage)
        }
    }
}
