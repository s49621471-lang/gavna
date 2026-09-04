package com.unique.core.vam

import android.content.ContentProviderClient
import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * The caller's half of cross-process content-provider access.
 *
 * Any process — UNIQUE's own, or another guest's — that wants a guest authority it does
 * not itself publish comes through here. Three steps, and each exists because the one
 * before it cannot be skipped:
 *
 *  1. **Resolve.** Ask UNIQUE's router provider which `:vappN` slot will serve
 *     `(vuid, authority)`. Only the main process holds the process pool, so only it can
 *     answer, and only it can lease.
 *  2. **Bind.** `call()` the stub provider of that slot. Acquiring the stub is what starts
 *     the process — a `ContentProvider` in the host manifest is something the platform
 *     will happily bring up — and the call itself makes the process *be* that instance:
 *     it bootstraps the guest and publishes its providers. This is synchronous, which is
 *     the point. Starting a service and polling for readiness would be a race with a
 *     timeout in place of an answer.
 *  3. **Acquire.** Take a client on the stub authority. From then on the caller passes
 *     ordinary `content://<guest authority>/...` URIs: the stub accepts them because
 *     step 2 widened its authority set, and routes each call to the guest's own provider.
 *
 * What the caller ends up holding is a real Binder to a real `ContentProvider` running in
 * the guest's process, so cursors, `openFile` and `call` all work by the same mechanism
 * they would for an installed app. Nothing is re-marshalled by UNIQUE.
 */
object VirtualProviderBridge {

    const val METHOD_BIND = "unique.bindInstance"
    const val KEY_AUTHORITIES = "unique.authorities"
    const val KEY_ERROR = "unique.error"

    /** What [resolve] found: which slot serves an authority, and for which instance. */
    data class Route(
        val slot: Int,
        val vuid: Int,
        val packageName: String,
        val versionCode: Long,
        val providerClass: String,
        val processName: String,
        val authority: String,
    ) {
        fun launchParams(): VirtualLaunchParams = VirtualLaunchParams(
            vuid = vuid,
            packageName = packageName,
            versionCode = versionCode,
            targetComponent = providerClass,
            kind = VirtualComponentKind.PROVIDER,
            processName = processName,
            slot = slot,
        )
    }

    fun resolve(context: Context, vuid: Int, authority: String): Route? {
        val hostPackage = hostPackageOf(context)
        val reply = runCatching {
            context.contentResolver.call(
                VirtualProviderRouter.routerUri(hostPackage),
                VirtualProviderRouter.ROUTER_METHOD_RESOLVE,
                null,
                Bundle().apply {
                    putInt(VirtualProviderRouter.KEY_VUID, vuid)
                    putString(VirtualProviderRouter.KEY_AUTHORITY, authority)
                },
            )
        }.getOrElse {
            Diagnostics.error(
                DiagChannel.PROCESS, "PROVIDER_RESOLVE_FAILED",
                mapOf("authority" to authority, "error" to it.toString()),
            )
            return null
        } ?: return null

        val slot = reply.getInt(VirtualProviderRouter.KEY_SLOT, -1)
        if (slot < 0) return null
        return Route(
            slot = slot,
            vuid = reply.getInt(VirtualProviderRouter.KEY_VUID, vuid),
            packageName = reply.getString(VirtualProviderRouter.KEY_PACKAGE).orEmpty(),
            versionCode = reply.getLong(VirtualProviderRouter.KEY_VERSION_CODE, 0L),
            providerClass = reply.getString(VirtualProviderRouter.KEY_PROVIDER).orEmpty(),
            processName = reply.getString(VirtualProviderRouter.KEY_PROCESS).orEmpty(),
            authority = reply.getString(VirtualProviderRouter.KEY_AUTHORITY) ?: authority,
        )
    }

    /**
     * Makes the slot's process exist, be the right instance, and accept [route.authority].
     *
     * Returns the authorities the target process reports it now publishes, or null when
     * the bind failed — which is reported there rather than guessed at here.
     */
    fun bind(context: Context, route: Route): Set<String>? {
        val hostPackage = hostPackageOf(context)
        val stub = Uri.parse(
            "content://" + VirtualProviderRouter.stubAuthority(hostPackage, route.slot)
        )
        val reply = runCatching {
            context.contentResolver.call(
                stub, METHOD_BIND, null,
                Bundle().apply { putAll(route.launchParams().toBundle()) },
            )
        }.getOrElse {
            Diagnostics.error(
                DiagChannel.PROCESS, "PROVIDER_BIND_FAILED",
                mapOf(
                    "authority" to route.authority,
                    "slot" to route.slot.toString(),
                    "error" to it.toString(),
                ),
            )
            return null
        } ?: return null

        reply.getString(KEY_ERROR)?.let { error ->
            Diagnostics.error(
                DiagChannel.PROCESS, "PROVIDER_BIND_REFUSED",
                mapOf("authority" to route.authority, "reason" to error),
            )
            return null
        }
        return reply.getStringArray(KEY_AUTHORITIES)?.toSet().orEmpty()
    }

    /**
     * A client for one instance's provider, or null when it cannot be reached.
     *
     * Unstable on purpose: the guest's process can be killed at any time — by UNIQUE, by
     * the user, by low memory — and a stable client would take the caller down with it.
     * A virtual app dying must never kill UNIQUE or another instance (§3).
     *
     * The caller closes it.
     */
    fun open(context: Context, vuid: Int, authority: String): ContentProviderClient? {
        val route = resolve(context, vuid, authority) ?: return null
        val published = bind(context, route) ?: return null
        if (authority !in published) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_NOT_PUBLISHED",
                mapOf(
                    "authority" to authority,
                    "published" to published.joinToString(",").take(300),
                ),
            )
            return null
        }
        val stubAuthority =
            VirtualProviderRouter.stubAuthority(hostPackageOf(context), route.slot)
        return context.contentResolver.acquireUnstableContentProviderClient(stubAuthority)
    }

    /**
     * UNIQUE's own package name, even when called from a grafted process.
     *
     * `context.packageName` inside a virtual process answers with the *guest's* name,
     * which is the whole point of the graft and exactly wrong here: the router and the
     * stubs are components of UNIQUE. `ApplicationInfo` for the host is not reachable
     * from a grafted `Context` either, so the bootstrap records it.
     */
    private fun hostPackageOf(context: Context): String =
        AppBootstrap.hostPackageName ?: context.packageName
}
