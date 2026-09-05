package com.unique.core.vam

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.unique.core.common.apk.ApkManifest
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Lets a virtual app share a file with a real one.
 *
 * A guest that shares a photo hands out `content://com.example.app.fileprovider/…`, and
 * nothing outside UNIQUE can do anything with that: `ActivityManagerService` resolves an
 * authority against installed packages, and the guest is not installed. The receiving app
 * gets a URI it cannot open, which is the failure mode of every "share" button in every
 * virtualized app that has not solved this.
 *
 * The URI is therefore rewritten, on its way out, onto an authority UNIQUE really does
 * own:
 *
 * ```
 * content://com.example.app.fileprovider/images/a.jpg
 *   →  content://com.unique.shared/0/com.example.app.fileprovider/images/a.jpg
 * ```
 *
 * and `UniqueSharedProvider` forwards every call back to the guest's own provider through
 * the cross-process path §6.4.0 already built. The receiving app sees an ordinary provider
 * belonging to an installed app, and the grant it is given is an ordinary grant — UNIQUE
 * does not invent a permission model, it borrows the platform's.
 *
 * ## What is deliberately not done
 *
 * The file is not copied. A copy would double the storage, go stale, and silently turn a
 * revoked grant into a permanent one; forwarding keeps the guest's own provider in charge
 * of what it hands out, including refusing.
 *
 * And only URIs the guest's *own manifest* declares are rewritten. A guest passing on a
 * `content://` URI it received from somewhere else — a photo picker, another app — is
 * passing on a grant it was given, and rewriting that would break it.
 */
object VirtualUriGrants {

    /** The authority UNIQUE lends to guests. `<applicationId>.shared`. */
    fun sharedAuthority(hostPackage: String): String = "$hostPackage.shared"

    /**
     * Rewrites every guest content URI an outgoing intent carries.
     *
     * Data, clip items and the `EXTRA_STREAM` extras, because a share can use any of the
     * three and apps genuinely use all three. Returns the same intent when nothing
     * matched, so the common case costs one authority comparison.
     */
    fun rewriteOutgoing(hostPackage: String, intent: Intent, ready: AppBootstrap.Result.Ready): Intent {
        val authorities = ownAuthorities(ready.manifest)
        if (authorities.isEmpty()) return intent
        val vuid = ready.params.vuid

        var changed = 0
        fun map(uri: Uri?): Uri? {
            val rewritten = rewrite(hostPackage, vuid, uri, authorities) ?: return uri
            changed++
            return rewritten
        }

        val data = map(intent.data)
        val stream = map(streamExtra(intent))
        val clip = intent.clipData?.let { original ->
            var touched = false
            val items = (0 until original.itemCount).map { index ->
                val item = original.getItemAt(index)
                val mapped = map(item.uri)
                if (mapped !== item.uri) {
                    touched = true
                    ClipData.Item(item.text, item.htmlText, item.intent, mapped)
                } else {
                    item
                }
            }
            if (!touched) original else ClipData(original.description, items.first()).also { built ->
                items.drop(1).forEach(built::addItem)
            }
        }
        if (changed == 0) return intent

        val out = Intent(intent)
        if (data !== intent.data) out.data = data
        if (clip !== intent.clipData) out.clipData = clip
        if (stream !== streamExtra(intent) && stream != null) {
            out.putExtra(Intent.EXTRA_STREAM, stream)
        }
        // Without a grant flag the receiving app has a readable authority and no permission
        // for it. A guest that forgot the flag was already broken; one that set it must not
        // be broken *by* the rewrite.
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 &&
            intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0
        ) {
            out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Diagnostics.info(
            DiagChannel.STORAGE, "URI_GRANT_REWRITTEN",
            mapOf(
                "package" to ready.params.packageName,
                "vuid" to vuid.toString(),
                "uris" to changed.toString(),
                "action" to (intent.action ?: "-"),
                "example" to (out.data ?: streamExtra(out))?.toString().orEmpty().take(200),
            ),
        )
        return out
    }

    /**
     * `content://<host>.shared/<vuid>/<guest authority>/<path…>`, or null when [uri] is not
     * one of this guest's own.
     */
    fun rewrite(hostPackage: String, vuid: Int, uri: Uri?, authorities: Set<String>): Uri? {
        if (uri == null) return null
        if (uri.scheme != "content") return null
        val authority = uri.authority ?: return null
        if (authority !in authorities) return null
        return uri.buildUpon()
            .authority(sharedAuthority(hostPackage))
            .path("/$vuid/$authority${uri.encodedPath.orEmpty()}")
            .build()
    }

    /** Undoes [rewrite]: the instance, the guest authority, and the guest's own URI. */
    fun resolve(uri: Uri): Target? {
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val vuid = segments[0].toIntOrNull() ?: return null
        val authority = segments[1]
        val rest = segments.drop(2).joinToString("/") { Uri.encode(it) }
        val guest = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .encodedPath(if (rest.isEmpty()) "" else "/$rest")
            .encodedQuery(uri.encodedQuery)
            .build()
        return Target(vuid, authority, guest)
    }

    data class Target(val vuid: Int, val authority: String, val guestUri: Uri)

    /** The provider authorities this guest's own manifest declares. */
    fun ownAuthorities(manifest: ApkManifest): Set<String> =
        manifest.components
            .asSequence()
            .filter { it.kind == ComponentKind.PROVIDER && it.enabled }
            .flatMap { it.authorities.asSequence() }
            .toSet()

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
}
