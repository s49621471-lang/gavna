package com.unique.core.vam

import android.content.ContentProvider
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The `:vappN` half of cross-process provider access.
 *
 * Called by the slot's generated stub provider, which is a real component of UNIQUE and so
 * is something the platform will start, publish and hand across processes. Everything
 * specific to virtualization lives here rather than in the generated stub.
 *
 * The trick that makes this work at all is [widenAuthorities]. `ContentProvider.Transport`
 * checks an incoming URI's authority against the provider's own before it dispatches:
 *
 * ```
 * SecurityException: The authority com.example.app.files does not match the one of the
 *     contentProvider: com.unique.vprovider.0
 * ```
 *
 * The stub cannot declare a guest's authority in the host manifest — the authorities are
 * not known until an app is imported, and two clones would collide. So the stub's
 * authority set is *widened at runtime*, once the guest's providers are published, to the
 * ones this process actually serves. After that the platform's own dispatch does the rest
 * and the caller holds a genuine Binder to a genuine provider: no re-marshalling, so
 * cursors, `openFile` and `call` behave exactly as they would for an installed app.
 */
object VirtualProviderHost {

    /**
     * Makes this process serve [params]'s instance, and reports what it publishes.
     *
     * Idempotent. A second bind for the same instance is the common case — every caller
     * binds before acquiring — and returns the authorities already published.
     */
    fun bind(context: Context, stub: ContentProvider, extras: Bundle?): Bundle {
        val params = paramsFrom(extras)
            ?: return error("bind carried no launch parameters")

        val existing = AppBootstrap.current
        if (existing != null &&
            (existing.params.vuid != params.vuid ||
                existing.params.packageName != params.packageName)
        ) {
            // The router leases one slot per (instance, manifest process), so this means
            // the pool handed out a slot whose process had not yet been reaped. Refusing
            // is right: a second graft would leave the first instance's objects pointing
            // at the wrong data directory.
            return error(
                "slot ${params.slot} already serves ${existing.params.packageName} " +
                    "(u${existing.params.vuid})"
            )
        }

        if (existing == null) {
            when (val result = bootstrapOnMainThread(context, params)) {
                is AppBootstrap.Result.Ready -> Unit
                is AppBootstrap.Result.Failed -> {
                    Diagnostics.error(
                        DiagChannel.PROCESS, "PROVIDER_BIND_BOOTSTRAP_FAILED",
                        mapOf(
                            "package" to params.packageName,
                            "code" to result.code,
                            "message" to result.message,
                        ),
                    )
                    return error("${result.code}: ${result.message}")
                }
            }
        }

        val published = VirtualProviderRegistry.publishedAuthorities()
        widenAuthorities(stub, ownAuthorityOf(context, params.slot), published)
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDER_BIND_READY",
            mapOf(
                "package" to params.packageName,
                "vuid" to params.vuid.toString(),
                "slot" to params.slot.toString(),
                "authorities" to published.joinToString(",").take(300),
            ),
        )
        return Bundle().apply {
            putStringArray(VirtualProviderBridge.KEY_AUTHORITIES, published.toTypedArray())
        }
    }


    /**
     * Grafts on the main thread, blocking the binder thread that asked.
     *
     * Two things make this necessary rather than tidy.
     *
     * `Application.onCreate` must run on the main thread. Apps create `Handler`s in it,
     * start main-thread-affine libraries in it, and assume `Looper.myLooper() ==
     * Looper.getMainLooper()`. Running a guest's `Application.onCreate` on a binder thread
     * is the kind of difference that shows up much later as an unexplained crash.
     *
     * And it settles a race. `ActivityThread.handleBindApplication` installs a process's
     * content providers *before* it calls `Application.onCreate`, so this `call()` can
     * arrive while UNIQUE's own `onCreate` — the one that installs [LaunchInterceptor] —
     * has not run. A message posted to the main looper cannot be dispatched until that
     * work finishes, because it is itself running on the main thread.
     */
    private fun bootstrapOnMainThread(
        context: Context,
        params: VirtualLaunchParams,
    ): AppBootstrap.Result {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) return AppBootstrap.bootstrap(context, params)

        val done = CountDownLatch(1)
        val outcome = AtomicReference<AppBootstrap.Result>()
        Handler(main).post {
            outcome.set(
                runCatching { AppBootstrap.bootstrap(context, params) }
                    .getOrElse { AppBootstrap.Result.Failed("BOOTSTRAP_THREW", it.toString(), it) }
            )
            done.countDown()
        }
        if (!done.await(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return AppBootstrap.Result.Failed(
                "BOOTSTRAP_TIMED_OUT",
                "the main thread of slot ${params.slot} did not graft " +
                    "${params.packageName} within ${BOOTSTRAP_TIMEOUT_SECONDS}s",
            )
        }
        return outcome.get()
            ?: AppBootstrap.Result.Failed("BOOTSTRAP_NO_RESULT", "graft produced no result")
    }

    /**
     * Long enough for a cold graft on a slow device, short enough to be a report.
     *
     * A binder transaction that never returns is an ANR in the *caller*, and the caller
     * here can be UNIQUE's own UI.
     */
    private const val BOOTSTRAP_TIMEOUT_SECONDS = 20L

    /**
     * Adds the guest's authorities to the stub's own, so `Transport` accepts their URIs.
     *
     * `ContentProvider.setAuthorities` has existed since API 21 and is what `attachInfo`
     * itself calls; it is reached reflectively because it is not public API. Failing to
     * find it is reported rather than swallowed: every cross-process query would then
     * throw `SecurityException` from inside the platform, which is a far harder thing to
     * read than one line saying the method is gone.
     *
     * The stub's *own* authority is included every time, and this is not defensive
     * tidiness. `setAuthorities` replaces the set rather than adding to it, so a call
     * that lists only the guest's authorities silently revokes the stub's — and the next
     * caller to `call()` the stub, which is how every bind arrives, is refused by the
     * platform with:
     *
     * ```
     * SecurityException: The authority com.unique.vprovider.2 does not match the one of
     *     the contentProvider: com.unique.probe.altprovider
     * ```
     *
     * The first bind then works and the second does not, which is a shape of bug that
     * looks like anything except what it is.
     */
    private fun widenAuthorities(
        stub: ContentProvider,
        ownAuthority: String,
        guestAuthorities: Set<String>,
    ) {
        val all = LinkedHashSet<String>()
        all += ownAuthority
        all += guestAuthorities
        if (all == currentAuthorities) return

        val method = Reflect.findMethodByName(ContentProvider::class.java, "setAuthorities")
        if (method == null) {
            Diagnostics.error(
                DiagChannel.PROCESS, "PROVIDER_SET_AUTHORITIES_MISSING",
                mapOf("stub" to stub.javaClass.name),
            )
            return
        }
        runCatching { method.invoke(stub, all.joinToString(";")) }.fold(
            onSuccess = {
                currentAuthorities = all
                Diagnostics.info(
                    DiagChannel.PROCESS, "PROVIDER_AUTHORITIES_WIDENED",
                    mapOf(
                        "stub" to ownAuthority,
                        "authorities" to all.joinToString(",").take(300),
                    ),
                )
            },
            onFailure = {
                Diagnostics.error(
                    DiagChannel.PROCESS, "PROVIDER_SET_AUTHORITIES_FAILED",
                    mapOf("stub" to stub.javaClass.name, "error" to it.toString()),
                )
            },
        )
    }

    /** What this process's stub currently answers to. Empty until the first bind. */
    @Volatile private var currentAuthorities: Set<String> = emptySet()

    /**
     * The stub's manifest authority, computed rather than read back.
     *
     * `ContentProvider.getAuthority()` is hidden and has returned null here; and after the
     * graft `context.packageName` is the *guest's*, so the obvious construction from the
     * context is wrong in exactly the case that matters. `AppBootstrap` keeps UNIQUE's own
     * name for this.
     */
    private fun ownAuthorityOf(context: Context, slot: Int): String {
        val host = AppBootstrap.hostPackageName ?: context.packageName
        return VirtualProviderRouter.stubAuthority(host, slot)
    }

    /**
     * The guest provider an incoming URI names.
     *
     * Null means the stub was reached for an authority this process does not publish,
     * which is a routing bug rather than a missing row and is reported as one.
     */
    fun route(authority: String?, operation: String): ContentProvider? {
        val provider = VirtualProviderRegistry.providerFor(authority)
        if (provider == null) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_ROUTE_MISS",
                mapOf(
                    "authority" to authority.orEmpty(),
                    "operation" to operation,
                    "published" to VirtualProviderRegistry.publishedAuthorities()
                        .joinToString(",").take(200),
                ),
            )
        }
        return provider
    }

    private fun paramsFrom(extras: Bundle?): VirtualLaunchParams? {
        val b = extras ?: return null
        val pkg = b.getString(VirtualLaunchParams.KEY_PACKAGE) ?: return null
        val vuid = b.getInt(VirtualLaunchParams.KEY_VUID, -1)
        if (vuid < 0) return null
        return VirtualLaunchParams(
            vuid = vuid,
            packageName = pkg,
            versionCode = b.getLong(VirtualLaunchParams.KEY_VERSION_CODE, 0L),
            targetComponent = b.getString(VirtualLaunchParams.KEY_COMPONENT),
            kind = b.getString(VirtualLaunchParams.KEY_KIND)
                ?.let { runCatching { VirtualComponentKind.valueOf(it) }.getOrNull() }
                ?: VirtualComponentKind.PROVIDER,
            processName = b.getString(VirtualLaunchParams.KEY_PROCESS) ?: pkg,
            slot = b.getInt(VirtualLaunchParams.KEY_SLOT, 0),
        )
    }

    private fun error(reason: String): Bundle =
        Bundle().apply { putString(VirtualProviderBridge.KEY_ERROR, reason) }
}
