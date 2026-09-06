package com.unique.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unique.app.engine.UniqueEngine
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.StubRouter
import com.unique.core.vam.VirtualLaunchParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives a broadcast a guest scheduled through a `PendingIntent`, and hands it back.
 *
 * A guest's manifest receiver is not a component the platform knows: UNIQUE registers it
 * *dynamically*, inside the guest's own process, and a dynamic receiver is matched by
 * filter rather than by name. An explicit broadcast — which is what every
 * `PendingIntent.getBroadcast` builds — therefore has nothing in the guest to point at,
 * and pointing it at the guest's real class means it resolves to nothing when it fires.
 *
 * That was the state this replaces. In one Android 15 run, 26 broadcasts were built and
 * none of them could ever have arrived:
 *
 * ```
 * W LAUNCH PENDING_INTENT_RECEIVER_UNSUPPORTED
 *     receiver=com.google.android.gms.measurement.AppMeasurementReceiver   (x26)
 * ```
 *
 * This receiver is what they point at now. It lives in UNIQUE's **main** process and is
 * declared in UNIQUE's own manifest, which is the whole point: a `PendingIntent` fires
 * when there may be no `:vappN` process at all, and the component it names has to exist
 * anyway. Waking the guest is then the ordinary cold-broadcast path, which already knows
 * how to start the right slot and retry until the guest's receiver acknowledges.
 *
 * A malformed intent is dropped with a diagnostic rather than crashing UNIQUE's main
 * process. Anything can send to a receiver whose name it knows; this one is not exported,
 * but the rule stands regardless — nothing that arrives here is trusted to be well
 * formed.
 */
class BroadcastStub : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val vuid = intent.getIntExtra(StubRouter.EXTRA_VUID, -1)
        val receiverClass = intent.getStringExtra(StubRouter.EXTRA_RECEIVER)
        @Suppress("DEPRECATION")
        val payload = intent.getParcelableExtra(VirtualLaunchParams.KEY_BROADCAST) as? Intent

        if (vuid < 0 || receiverClass.isNullOrEmpty() || payload == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PENDING_BROADCAST_MALFORMED",
                mapOf(
                    "vuid" to vuid.toString(),
                    "receiver" to (receiverClass ?: "-"),
                    "payload" to (payload?.action ?: "-"),
                ),
            )
            return
        }

        // goAsync, because waking a guest reads the instance record and may fork a
        // process. Without it the broadcast is finished the moment onReceive returns and
        // the platform is free to kill this process mid-start, which looks exactly like a
        // delivery that was never attempted.
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                UniqueEngine.deliverPendingBroadcast(context, vuid, receiverClass, payload)
            } catch (t: Throwable) {
                Diagnostics.error(
                    DiagChannel.PROCESS, "PENDING_BROADCAST_FAILED",
                    mapOf(
                        "vuid" to vuid.toString(),
                        "receiver" to receiverClass,
                        "error" to t.toString(),
                    ),
                )
            } finally {
                result.finish()
            }
        }
    }
}
