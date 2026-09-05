package com.unique.app.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vam.VirtualProviderBridge
import com.unique.core.vam.VirtualUriGrants

/**
 * The authority UNIQUE lends to guests so they can share files with real apps.
 *
 * A guest hands out `content://com.example.app.fileprovider/…`, which nothing outside
 * UNIQUE can open: `ActivityManagerService` resolves authorities against installed
 * packages, and the guest is not installed. Outgoing intents are therefore rewritten onto
 * this authority (see [VirtualUriGrants]) and every call is forwarded back to the guest's
 * own provider.
 *
 * Forwarded, not copied. The guest's provider stays in charge of what it hands out — it
 * can still refuse, and it can still stop — where a copy would double the storage, go
 * stale, and quietly turn a revoked grant into a permanent one.
 *
 * `exported="false"` with `grantUriPermissions="true"`: nothing may open this by knowing
 * its name, only by being *given* a URI, and the giving is the platform's own temporary
 * grant rather than a permission model UNIQUE invented.
 */
class UniqueSharedProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Diagnostics.info(DiagChannel.STORAGE, "SHARED_PROVIDER_CREATED", emptyMap())
        return true
    }

    /**
     * Opens a client on the guest provider a URI names.
     *
     * A client per call rather than a cached one. The instance's process can die between
     * two reads — that is normal for a virtual app — and a cached client would hand the
     * caller a dead binder instead of restarting the slot.
     */
    private inline fun <T> forward(uri: Uri, operation: String, body: (android.content.ContentProviderClient, Uri) -> T): T? {
        val target = VirtualUriGrants.resolve(uri) ?: run {
            Diagnostics.warn(
                DiagChannel.STORAGE, "SHARED_URI_UNPARSEABLE",
                mapOf("uri" to uri.toString().take(200), "operation" to operation),
            )
            return null
        }
        val context = this.context ?: return null
        val client = VirtualProviderBridge.open(context, target.vuid, target.authority) ?: run {
            Diagnostics.warn(
                DiagChannel.STORAGE, "SHARED_URI_UNREACHABLE",
                mapOf(
                    "authority" to target.authority,
                    "vuid" to target.vuid.toString(),
                    "operation" to operation,
                ),
            )
            return null
        }
        return client.use { body(it, target.guestUri) }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = forward(uri, "query") { client, guest ->
        client.query(guest, projection, selection, selectionArgs, sortOrder)
    }

    override fun getType(uri: Uri): String? =
        forward(uri, "getType") { client, guest -> client.getType(guest) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        forward(uri, "openFile") { client, guest -> client.openFile(guest, mode) }

    override fun openTypedAssetFile(
        uri: Uri, mimeTypeFilter: String, opts: Bundle?,
    ): AssetFileDescriptor? = forward(uri, "openTypedAssetFile") { client, guest ->
        client.openTypedAssetFileDescriptor(guest, mimeTypeFilter, opts)
    }

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
        forward(uri, "getStreamTypes") { client, guest ->
            client.getStreamTypes(guest, mimeTypeFilter)
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        forward(uri, "insert") { client, guest -> client.insert(guest, values) }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = forward(uri, "update") { client, guest ->
        client.update(guest, values, selection, selectionArgs)
    } ?: 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        forward(uri, "delete") { client, guest ->
            client.delete(guest, selection, selectionArgs)
        } ?: 0
}
