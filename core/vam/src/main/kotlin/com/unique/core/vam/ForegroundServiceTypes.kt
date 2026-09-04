package com.unique.core.vam

import android.content.pm.ServiceInfo
import android.os.Build
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Foreground service type handling — the sharpest edge in Android 14+ virtualization.
 *
 * From Android 14, `startForeground(id, notification, type)` requires the type to be
 * declared on the manifest entry of the service that actually calls it. Inside UNIQUE
 * that service is a *stub* in the host's manifest, not the virtual app's service, and the
 * host must also hold the matching `FOREGROUND_SERVICE_*` permission.
 *
 * So the stubs declare a superset and this class intersects a virtual service's declared
 * types with it. When the intersection is empty the start is refused with a clear
 * diagnostic rather than silently downgraded — a downgraded foreground service produces a
 * `ForegroundServiceDidNotStartInTimeException` crash that a user cannot interpret and a
 * developer cannot reproduce.
 */
object ForegroundServiceTypes {

    /**
     * Types the host manifest declares on its stub services.
     *
     * Deliberately not "everything": each entry costs a `FOREGROUND_SERVICE_*` permission
     * on the host, several of which require a Play policy declaration. The set is the
     * types real apps use for work a virtualized app can legitimately do.
     */
    val HOST_SUPPORTED: Int = buildSet {
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }.fold(0) { acc, t -> acc or t }

    sealed interface Decision {
        /** The intersection is non-empty; start with [type]. */
        data class Allow(val type: Int) : Decision
        /** Nothing in common; the start is refused with a reason the UI can show. */
        data class Refuse(val requested: Int, val reason: String) : Decision
        /** Pre-Android-14: no type is required. */
        data object NotRequired : Decision
    }

    fun decide(virtualDeclaredType: Int): Decision {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Decision.NotRequired

        val intersection = virtualDeclaredType and HOST_SUPPORTED
        if (intersection != 0) return Decision.Allow(intersection)

        if (virtualDeclaredType == 0) {
            // The app declared no type at all. On Android 14+ the platform rejects this
            // for apps targeting 34+; SPECIAL_USE is the honest fallback and is visible.
            return if (HOST_SUPPORTED and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) {
                Decision.Allow(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                Decision.Refuse(0, "The app started a foreground service without declaring a type.")
            }
        }

        val decision = Decision.Refuse(
            virtualDeclaredType,
            "This app needs a foreground service type UNIQUE does not declare " +
                "(requested 0x${Integer.toHexString(virtualDeclaredType)}).",
        )
        Diagnostics.warn(
            DiagChannel.PROCESS, "FGS_TYPE_UNSUPPORTED",
            mapOf(
                "requested" to "0x${Integer.toHexString(virtualDeclaredType)}",
                "hostSupported" to "0x${Integer.toHexString(HOST_SUPPORTED)}",
            ),
        )
        return decision
    }
}
