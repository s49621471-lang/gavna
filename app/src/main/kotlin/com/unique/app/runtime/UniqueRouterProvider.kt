package com.unique.app.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.VirtualBroadcastRouter
import com.unique.core.vam.VirtualDiagnostics
import com.unique.core.vam.VirtualProviderRouter

/**
 * UNIQUE's own process, reachable from every other process it owns.
 *
 * Only the main process holds the instance table and the `:vappN` process pool, so only it
 * can say which slot will serve a guest authority — and only it can lease one. A
 * `ContentProvider` declared in the host manifest is the cheapest way to expose that:
 * the platform starts and publishes it, the call is synchronous, and no AIDL had to be
 * invented for a single question with a single answer.
 *
 * Deliberately not exported. Everything that talks to it is a process of UNIQUE's own.
 *
 * It carries no data: `query`, `insert`, `update` and `delete` are not a table this
 * provider has, and answering them with plausible-looking emptiness would be worse than
 * saying so. [call] is the interface.
 */
class UniqueRouterProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Diagnostics.info(DiagChannel.PROCESS, "PROVIDER_ROUTER_CREATED", emptyMap())
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        VirtualProviderRouter.ROUTER_METHOD_RESOLVE -> VirtualProviderRouter.resolve(extras)
        VirtualDiagnostics.METHOD_PUBLISH -> VirtualDiagnostics.absorb(extras)
        VirtualProviderRouter.ROUTER_METHOD_SLOT_READY -> VirtualProviderRouter.slotReady(extras)
        VirtualProviderRouter.ROUTER_METHOD_SLOT_STARTING ->
            VirtualProviderRouter.slotStarting(extras)
        VirtualProviderRouter.ROUTER_METHOD_SLOT_STATUS -> VirtualProviderRouter.slotStatus(extras)
        VirtualBroadcastRouter.ROUTER_METHOD_COLD_DELIVERED ->
            VirtualBroadcastRouter.coldDelivered(extras)
        else -> {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_ROUTER_UNKNOWN_METHOD",
                mapOf("method" to method),
            )
            null
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException("UNIQUE's router is call()-only")

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("UNIQUE's router is call()-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("UNIQUE's router is call()-only")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("UNIQUE's router is call()-only")
}
