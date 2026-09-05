package com.unique.core.vam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Runs a guest's receiver in a process that was started for exactly that purpose.
 *
 * The stub service this is called from has one job: exist, so the process exists. Once it
 * does, the ordinary graft runs and the guest's receiver is instantiated from the guest's
 * own class loader with the guest's own `Context` — the same object a live delivery would
 * get. Nothing about the receiver's own code can tell the two apart, which is the point.
 */
object VirtualColdBroadcast {

    fun deliver(context: Context, params: VirtualLaunchParams, stubIntent: Intent?) {
        val receiverClass = params.targetComponent
        if (receiverClass == null) {
            Diagnostics.error(
                DiagChannel.PROCESS, "COLD_BROADCAST_NO_RECEIVER",
                mapOf("package" to params.packageName, "vuid" to params.vuid.toString()),
            )
            return
        }
        val payload = extractBroadcast(stubIntent)
        if (payload == null) {
            Diagnostics.error(
                DiagChannel.PROCESS, "COLD_BROADCAST_NO_PAYLOAD",
                mapOf("package" to params.packageName, "receiver" to receiverClass),
            )
            return
        }

        // The Service's own context, not getApplicationContext(). Before the graft the two
        // are the same object; after it, `getApplicationContext()` is the *guest's*
        // Application, and AppBootstrap reads `filesDir` off what it is handed to find
        // UNIQUE's own storage root. The distinction only bites on a re-entry, which is
        // exactly the case that is hardest to notice.
        val ready = when (val result = AppBootstrap.bootstrap(context, params)) {
            is AppBootstrap.Result.Ready -> result
            is AppBootstrap.Result.Failed -> {
                Diagnostics.error(
                    DiagChannel.PROCESS, "COLD_BROADCAST_BOOTSTRAP_FAILED",
                    mapOf(
                        "package" to params.packageName,
                        "code" to result.code,
                        "message" to result.message,
                    ),
                )
                return
            }
        }

        // Extras written by the sender are unparcelled lazily, and by default against the
        // system class loader - which cannot see a Parcelable the guest declared. Naming
        // the guest's loader here is what a live delivery gets for free.
        runCatching {
            payload.setExtrasClassLoader(ready.application.classLoader)
        }

        val delivered = VirtualReceiverRegistry.deliverColdStart(ready, receiverClass, payload)
        Diagnostics.info(
            DiagChannel.PROCESS, "COLD_BROADCAST_DELIVERED",
            mapOf(
                "package" to params.packageName,
                "vuid" to params.vuid.toString(),
                "receiver" to receiverClass,
                "action" to (payload.action ?: "-"),
                "delivered" to delivered.toString(),
            ),
        )
        if (delivered) acknowledge(context, params, receiverClass, payload.action)
    }

    /**
     * Tells UNIQUE's main process the receiver ran, so it stops re-trying.
     *
     * Sent only when the receiver actually ran. A wake that got as far as grafting and
     * then failed is exactly the case worth another attempt, and acknowledging it would
     * throw that away — the router would record a delivery that never happened.
     *
     * Best-effort in the other direction too: a failure here costs one duplicate delivery
     * ninety seconds later, which a receiver must already tolerate because the platform
     * itself does not promise exactly-once.
     */
    private fun acknowledge(
        context: Context,
        params: VirtualLaunchParams,
        receiverClass: String,
        action: String?,
    ) {
        val host = AppBootstrap.hostPackageName ?: return
        runCatching {
            context.contentResolver
                .acquireUnstableContentProviderClient(VirtualProviderRouter.routerUri(host))
                ?.use { client ->
                    client.call(
                        VirtualBroadcastRouter.ROUTER_METHOD_COLD_DELIVERED, null,
                        Bundle().apply {
                            putInt(VirtualBroadcastRouter.KEY_VUID, params.vuid)
                            putString(VirtualBroadcastRouter.KEY_RECEIVER, receiverClass)
                            putString(VirtualBroadcastRouter.KEY_ACTION, action ?: "")
                        },
                    )
                }
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "COLD_BROADCAST_ACK_FAILED",
                mapOf("receiver" to receiverClass, "error" to it.toString()),
            )
        }
    }

    /**
     * The guest's own broadcast, unwrapped from the stub start.
     *
     * Nested as a parcelled extra rather than flattened onto the stub intent: the guest's
     * receiver reads `intent.getAction()` and its own extras, and merging the two would
     * hand it UNIQUE's routing keys as if the sender had set them.
     */
    private fun extractBroadcast(stubIntent: Intent?): Intent? {
        val intent = stubIntent ?: return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(VirtualLaunchParams.KEY_BROADCAST) as? Intent
    }
}
