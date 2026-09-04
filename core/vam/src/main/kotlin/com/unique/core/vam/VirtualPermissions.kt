package com.unique.core.vam

import android.content.Context
import android.content.pm.PackageManager
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.common.shim.shim
import com.unique.core.common.path.VirtualPathModel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import com.unique.core.hook.SystemServiceHook
import com.unique.core.vpermission.PermissionState
import com.unique.core.vpermission.PermissionStore
import java.io.File
import java.lang.reflect.Method

/**
 * Answers the guest's permission questions from *its instance's* state.
 *
 * Without this a virtual app sees the host's grants, so every instance of every app holds
 * whatever UNIQUE holds: an app the user never granted the camera to can open the camera,
 * and two instances of the same app cannot differ. Both are wrong, and the second is the
 * whole point of instances.
 *
 * The rule in one line: **the guest sees `host grant AND instance grant`.** UNIQUE can
 * narrow, never widen. A stored grant for a permission the host lacks stays DENIED and is
 * reported as blocked by the host, because a local "granted" would be a lie the platform
 * would refuse to honour at the first real call.
 *
 * ## Which argument is the permission
 *
 * This is the one place where argument identification is genuinely hard, because the
 * platform is not consistent: `IPackageManager.checkPermission` takes
 * `(permission, package, userId)` while `IPermissionManager.checkPermission` takes
 * `(package, permission, …)` — the same two strings in the opposite order. Position is
 * therefore unusable, and so is "the first String".
 *
 * So the permission is identified *semantically*: among the string arguments, the one the
 * guest actually declares in its manifest. Anything not recognised is passed straight
 * through to the platform, which is the safe direction — an unrecognised query is answered
 * by the system exactly as it would have been without UNIQUE.
 */
object VirtualPermissions {

    private data class Binding(
        val vuid: Int,
        val packageName: String,
        val declared: Set<String>,
        val stateFile: File,
    )

    @Volatile private var binding: Binding? = null
    @Volatile private var hostContext: Context? = null

    /**
     * True on this thread while the host's own grant is being resolved.
     *
     * Without it this class calls itself forever: asking the host whether it holds a
     * permission goes through `Context.checkSelfPermission`, which reaches
     * `ActivityManagerService` — through the very shim that asks this class for the
     * answer. The guard makes that one call fall through to the platform, which is the
     * only place the truth lives.
     */
    private val resolvingHostGrant = ThreadLocal.withInitial { false }

    private val store = PermissionStore { permission ->
        val context = hostContext
        if (context == null) {
            PackageManager.PERMISSION_DENIED
        } else {
            resolvingHostGrant.set(true)
            try {
                context.checkSelfPermission(permission)
            } finally {
                resolvingHostGrant.set(false)
            }
        }
    }

    /** Permissions this process's guest declares. Empty until [bind]. */
    val declared: Set<String> get() = binding?.declared ?: emptySet()

    /**
     * Binds this process to one instance's permission state.
     *
     * Takes the three facts it needs rather than a `Result.Ready`, so it can run *before*
     * the guest's `Application` exists: an app that checks a permission in
     * `Application.onCreate` - and plenty do - must already get its instance's answer.
     */
    @Synchronized
    fun bind(vuid: Int, packageName: String, declared: Collection<String>, context: Context) {
        val host = context.applicationContext ?: context
        hostContext = host
        val model = VirtualPathModel(host.filesDir.absolutePath)
        val b = Binding(
            vuid, packageName, declared.toSet(),
            File(model.permissionsFile(vuid, packageName)),
        )
        binding = b
        disableClientSideCaches()
        val restored = restore(b)
        Diagnostics.info(
            DiagChannel.PROCESS, "PERMISSIONS_BOUND",
            mapOf(
                "package" to packageName,
                "vuid" to vuid.toString(),
                "declared" to declared.size.toString(),
                "restored" to restored.toString(),
            ),
        )
    }

    /**
     * Turns off the framework's *client-side* permission caches in this process.
     *
     * Without this the permission layer works once and then quietly stops. Since Android
     * 12 both routes answer from a `PropertyInvalidatedCache` living in the app's own
     * process: the first `checkSelfPermission` for a given permission goes through Binder
     * — through UNIQUE's shim — and every later one is answered from that cache without a
     * Binder call at all. The cache is invalidated when the *platform's* permission state
     * changes, and UNIQUE's per-instance state is invisible to it, so a guest that is
     * denied before it asks stays denied afterwards no matter what the instance records:
     *
     * ```
     * cameraBefore=DENIED   result.CAMERA=GRANTED   cameraAfter=GRANTED
     * cameraViaPm=DENIED    …                       cameraViaPmAfter=DENIED
     * ```
     *
     * — and no `PERMISSION_CHECK` event for either "after", because the shim was never
     * reached. This is the general hazard of Binder interception and is worth stating
     * plainly: **a shim only sees calls that actually cross the process boundary.**
     *
     * The per-process disable is what the platform's own tests use. It costs one Binder
     * call per check, which is what an unhooked app pays anyway on a cache miss.
     */
    private fun disableClientSideCaches() {
        val disabled = ArrayList<String>(2)
        val failed = ArrayList<String>(2)
        for (name in CACHE_DISABLERS) {
            val (owner, method) = name.substringBeforeLast('.') to name.substringAfterLast('.')
            val result = runCatching {
                val clazz = Reflect.findClass(owner) ?: error("no $owner")
                val m = Reflect.findMethodByName(clazz, method) ?: error("no $method")
                m.isAccessible = true
                m.invoke(null)
            }
            if (result.isSuccess) disabled += method else failed += "$method: ${result.exceptionOrNull()}"
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "PERMISSION_CACHE_DISABLED",
            mapOf(
                "disabled" to disabled.joinToString(","),
                "failed" to failed.joinToString(";").take(300),
            ),
        )
        if (disabled.isEmpty()) {
            // Not fatal, but the guest's permission answers will be stale after the first
            // one, and that has to be visible rather than looking like a broken store.
            Diagnostics.error(
                DiagChannel.PROCESS, "PERMISSION_CACHE_STILL_ON",
                mapOf("detail" to failed.joinToString(";").take(300)),
            )
        }
    }

    /**
     * The platform's own per-process cache disables, tried in order.
     *
     * Named rather than discovered because these are `@TestApi` statics with no common
     * shape; a name that disappears is reported, and the fallback is the platform's
     * unvirtualized behaviour rather than a wrong answer.
     */
    private val CACHE_DISABLERS = listOf(
        "android.permission.PermissionManager.disablePermissionCache",
        "android.permission.PermissionManager.disablePackageNamePermissionCache",
        "android.app.ActivityManager.disableAppOpCache",
        "android.content.pm.PackageManager.disablePackageManagerCache",
    )

    /**
     * Reads back what the user has already decided for this instance.
     *
     * Without this a guest is asked for every permission again on every cold start, which
     * users read as the app being broken. The file holds only decisions UNIQUE recorded;
     * it is still intersected with the host's live grant on every check, so a stale
     * GRANTED here can never outlive the user revoking it from UNIQUE.
     */
    private fun restore(b: Binding): Int {
        if (!b.stateFile.isFile) return 0
        var count = 0
        runCatching {
            b.stateFile.readLines().forEach { line ->
                val name = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) return@forEach
                val state = runCatching { PermissionState.valueOf(value) }.getOrNull()
                    ?: return@forEach
                store.set(b.vuid, b.packageName, name, state)
                count++
            }
        }.onFailure {
            Diagnostics.warn(
                DiagChannel.PROCESS, "PERMISSIONS_RESTORE_FAILED",
                mapOf("package" to b.packageName, "error" to it.toString()),
            )
        }
        return count
    }

    /** Writes the whole record; small enough that a rewrite beats a merge. */
    private fun persist(b: Binding) {
        runCatching {
            b.stateFile.parentFile?.mkdirs()
            b.stateFile.writeText(
                store.snapshot(b.vuid, b.packageName)
                    .entries.joinToString("\n") { "${it.key}=${it.value.name}" } + "\n"
            )
        }.onFailure {
            Diagnostics.error(
                DiagChannel.PROCESS, "PERMISSIONS_PERSIST_FAILED",
                mapOf("package" to b.packageName, "error" to it.toString()),
            )
        }
    }

    /** What the guest observes for [permission]: the host's grant narrowed by the instance's. */
    fun check(permission: String): Int {
        val b = binding ?: return PackageManager.PERMISSION_DENIED
        return store.checkPermission(b.vuid, b.packageName, permission)
    }

    /**
     * Records that the guest was granted [permission] for this instance.
     *
     * Called when the guest's own request came back granted, which is the only moment a
     * grant may appear without the user touching UNIQUE's own settings: the app asked, the
     * platform showed the dialog, the user said yes.
     */
    @Synchronized
    fun recordGrant(permission: String, granted: Boolean) {
        val b = binding ?: return
        store.set(
            b.vuid, b.packageName, permission,
            if (granted) PermissionState.GRANTED else PermissionState.DENIED,
        )
        persist(b)
        Diagnostics.info(
            DiagChannel.PROCESS, "PERMISSION_RESULT_RECORDED",
            mapOf(
                "package" to b.packageName,
                "vuid" to b.vuid.toString(),
                "permission" to permission,
                "granted" to granted.toString(),
                "blockedByHost" to
                    store.effectiveState(b.vuid, b.packageName, permission).blockedByHost.toString(),
            ),
        )
    }

    /** Set from UNIQUE's own per-instance settings, not from the guest. */
    @Synchronized
    fun setStored(permission: String, state: PermissionState) {
        val b = binding ?: return
        store.set(b.vuid, b.packageName, permission, state)
        persist(b)
    }

    @Synchronized
    fun snapshot(): Map<String, PermissionState> {
        val b = binding ?: return emptyMap()
        return store.snapshot(b.vuid, b.packageName)
    }

    @Synchronized
    fun reset() {
        binding?.let { store.clear(it.vuid, it.packageName) }
        binding = null
    }

    /**
     * Hooks `IPermissionManager`, which owns the *rationale* question and nothing else.
     *
     * Worth stating, because the obvious assumption is wrong and cost a run to disprove:
     * on API 34 `IPermissionManager` declares **no** `checkPermission` or
     * `checkUidPermission` at all. Its members are the grant/revoke, flags, allowlist and
     * one-time-session operations. `Context.checkSelfPermission` reaches
     * `IActivityManager`, and `PackageManager.checkPermission` reaches `IPackageManager`;
     * both are hooked, and `t12` proves both by observing GRANTED through each after the
     * guest's request. The interface's own method list is now printed by
     * `HOOK_MATCHED_NOTHING` when a hook matches nothing, which is how this was settled.
     *
     * `shouldShowRequestPermissionRationale` does live here, and it has to follow the
     * *instance's* state: "the user denied this once, explain before asking again" is a
     * question about this instance's history, not about UNIQUE's.
     */
    @Synchronized
    fun installManagerHook(virtualPackage: String, hostPackage: String): Boolean {
        val target = SystemServiceHook.TARGETS.firstOrNull { it.serviceName == "permissionmgr" }
            ?: return false
        val report = SystemServiceHook.install(
            target,
            listOf(
                shim("shouldShowRequestPermissionRationale") {
                    rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
                    replaceWith { call ->
                        val permission = permissionArgOf(call.args)
                        if (permission == null) call.proceed() else shouldShowRationale(permission)
                    }
                },
            ),
        )
        if (!report.installed) {
            Diagnostics.warn(
                DiagChannel.HOOK, "PERMISSION_MANAGER_HOOK_SKIPPED",
                mapOf("package" to virtualPackage, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        Diagnostics.info(
            DiagChannel.HOOK, "PERMISSION_MANAGER_HOOKED",
            mapOf("matched" to (report.bind?.describeMatches()?.take(300) ?: "-")),
        )
        return true
    }

    /**
     * Whether the guest should explain itself before asking again.
     *
     * The platform's rule is "the user has denied this once, but not permanently". The
     * instance's own record answers exactly that question and the host's cannot: two
     * instances of the same app have separate histories, which is the point of instances.
     *
     * `ASK` - never decided - is false, matching the platform before an app's first
     * request. A grant is false. A denial recorded against this instance is true. A
     * denial that exists only because UNIQUE itself lacks the permission is *not* the
     * user refusing this app, so it is false: the guest showing a rationale would ask the
     * user to fix something in the wrong place.
     */
    fun shouldShowRationale(permission: String): Boolean {
        val b = binding ?: return false
        val effective = store.effectiveState(b.vuid, b.packageName, permission)
        if (effective.blockedByHost) return false
        return store.stored(b.vuid, b.packageName, permission) == PermissionState.DENIED
    }

    // ---------------------------------------------------------------------------------
    // Shim support
    // ---------------------------------------------------------------------------------

    /**
     * True for the "does this app hold this permission" family.
     *
     * Two interfaces answer it and they disagree about argument order:
     * `IPackageManager.checkPermission` takes `(permission, package, userId)` and
     * `IActivityManager.checkPermission` takes `(permission, pid, uid)`.
     *
     * URI permissions are excluded by name: `checkUriPermission` asks a different
     * question - whether a *grant on a content URI* exists - and answering it from this
     * store would break `FileProvider` sharing in a way that looks like a storage bug.
     */
    fun isPermissionCheck(method: Method): Boolean {
        val name = method.name
        if (!name.startsWith("check") || !name.contains("Permission")) return false
        if (name.contains("Uri")) return false
        if (method.returnType != Int::class.javaPrimitiveType) return false
        return method.parameterTypes.any { it == String::class.java }
    }

    /**
     * The declared permission among a call's arguments, or null when there is not exactly
     * one.
     *
     * "Exactly one" matters: a call carrying two recognised permissions is a shape UNIQUE
     * does not understand, and guessing between them would silently answer the wrong
     * question. The platform answers it instead.
     */
    fun permissionArgOf(args: Array<Any?>): String? {
        // The host-grant lookup below must reach the platform, not come back here.
        if (resolvingHostGrant.get()) return null
        val declared = binding?.declared ?: return null
        val matches = args.filterIsInstance<String>().filter { it in declared }
        return matches.singleOrNull()
    }

    /** Reports a check that was answered locally, at debug level. */
    fun reportAnswer(permission: String, result: Int) {
        Diagnostics.event(
            DiagChannel.PROCESS, DiagLevel.DEBUG, "PERMISSION_CHECK",
            mapOf(
                "permission" to permission,
                "result" to if (result == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED",
            ),
        )
    }
}
