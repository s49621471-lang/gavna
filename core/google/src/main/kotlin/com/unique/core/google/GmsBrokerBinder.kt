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
            ?: return delegate.transact(code, data, reply, flags)
        return try {
            delegate.transact(code, rewritten, reply, flags)
        } finally {
            rewritten.recycle()
        }
    }

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
                val targets = located.fields.filter { field ->
                    data.setDataPosition(field.dataStart)
                    runCatching { data.readString() }.getOrNull() == guestPackage &&
                        data.dataPosition() == field.end
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
        /** Interface descriptors whose calls carry a calling package Play services checks. */
        private val BROKER_DESCRIPTORS = setOf(
            "com.google.android.gms.common.internal.IGmsServiceBroker",
        )

        /** The packages that answer a Play services bind. */
        private val GOOGLE_PACKAGES = setOf("com.google.android.gms", "com.google.android.gsf")

        /**
         * Wraps [binder] when it is Play services' service broker, otherwise returns it.
         *
         * Two checks in order, and the order is about cost. `servicePackage` is free and
         * rules out every bind that is not to Google — which is nearly all of them —
         * before the descriptor is asked for, because reading a descriptor from a remote
         * binder is a synchronous transaction and this runs on the app's main thread.
         *
         * The descriptor is what actually decides, though: an app reaches the broker
         * through several different intents and the descriptor is the one thing every
         * route has in common.
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
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull() ?: return binder
            if (descriptor !in BROKER_DESCRIPTORS) return binder
            Diagnostics.info(
                DiagChannel.LAUNCH, "GMS_BROKER_WRAPPED",
                mapOf("descriptor" to descriptor, "package" to guestPackage),
            )
            return GmsBrokerBinder(binder, guestPackage, hostPackage)
        }
    }
}
