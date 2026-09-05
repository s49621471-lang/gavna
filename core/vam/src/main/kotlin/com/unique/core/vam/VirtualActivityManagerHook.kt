package com.unique.core.vam

import android.content.AttributionSource
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.content.Intent
import android.content.ServiceConnection
import com.unique.core.common.apk.ComponentKind
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import android.content.pm.ProviderInfo
import com.unique.core.common.shim.MethodShim
import com.unique.core.common.shim.ShimCall
import com.unique.core.common.shim.shim
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.SystemServiceHook
import com.unique.core.hook.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Presents the host's identity to system services on the guest's behalf.
 *
 * Inside a virtual process every `Context` reports the virtual package, which is the
 * whole point — but that name also travels outward on every framework call as the
 * `callingPackage` argument, and `system_server` checks it against the *real* uid:
 *
 * ```
 * SecurityException: Given calling package com.unique.probe does not match caller's uid 10109
 * ```
 *
 * Nothing works past the first framework call without this. `PhoneWindow`'s constructor
 * alone reads a setting, which acquires a content provider, which fails.
 *
 * ## Which arguments are rewritten, and why not all of them
 *
 * The rule is: **on methods an app calls on its own behalf, any argument that is exactly
 * the virtual package name becomes the host package name.** Those methods are identified
 * by carrying an `IApplicationThread` parameter, which is the framework's own marker for
 * "this call comes from an app process about itself".
 *
 * A blanket rewrite across the whole interface would be actively dangerous:
 * `forceStopPackage(String, int)` takes a package name as *data*, and rewriting it would
 * make a guest's call stop UNIQUE itself. It has no `IApplicationThread`, so the
 * predicate excludes it. Two more rules cover the rest: an allowlist for identity-bearing
 * methods that carry no `IApplicationThread` (`getIntentSender` is the important case),
 * and a structural rule for the *interrogative* half of the interface — a method that only
 * asks a question about a package can be told the host's name safely, and there are far
 * too many of those to name one at a time. That rule was written after a real device
 * refused to start an app at all:
 *
 * ```
 * SecurityException: Permission Denial: getHistoricalProcessExitReasons
 *     from pid=22773, uid=10300 requires android.permission.DUMP
 * ```
 *
 * `getHistoricalProcessExitReasons` needs `DUMP` only when the package it is asked about
 * is not the caller's own — so a guest asking why it died last time was asking about a
 * stranger, and Crashlytics-style startup code took the whole application down with it.
 */
object VirtualActivityManagerHook {

    private const val APPLICATION_THREAD = "android.app.IApplicationThread"

    /**
     * Methods that carry a calling package but no `IApplicationThread`.
     *
     * Kept deliberately short. Each entry is here because the argument is the caller's own
     * identity, never a package being acted upon.
     */
    private val IDENTITY_METHODS = setOf(
        "getIntentSender",
        "getIntentSenderWithFeature",
        "checkUriPermission",
        "grantUriPermission",
        "revokeUriPermission",
        "setServiceForeground",
    )

    /**
     * Verbs that mean the method *acts on* the package it is given.
     *
     * Matched as a substring of the lowercased method name, so `forceStopPackage`,
     * `forceStopPackageEvenWhenStopping`, `killBackgroundProcesses`,
     * `clearApplicationUserData` and `crashApplication` are all excluded by one entry
     * each. These are the calls where rewriting the guest's package to the host's would
     * turn "stop me" into "stop UNIQUE", which is the §3 violation this whole predicate
     * exists to avoid.
     *
     * A verb this list does not know is treated as destructive, because [ASKS_ABOUT] is an
     * allowlist of question words rather than a denylist of actions. Getting that direction
     * right is the difference between a missed rewrite, which produces a visible
     * `SecurityException` naming the method, and a wrong one, which kills the host.
     */
    private val ACTS_ON = listOf(
        "kill", "forcestop", "stopapp", "clearapplication", "crash", "remove", "restart",
        "setpackage", "suspend", "unsuspend", "hibernate", "uninstall", "delete",
    )

    /**
     * Verbs that mean the method only *asks about* the package it is given.
     *
     * `get`, `is`, `check`, `has`, `query` and `report` — a call that reads a fact or files
     * a record about the caller. The virtual package is not a name this device knows, so
     * wherever it appears in such a call it can only mean "me", and the host's name is the
     * only one the platform will accept for that (§6.6.6).
     */
    private val ASKS_ABOUT = listOf("get", "is", "check", "has", "query", "report", "notice")

    /**
     * Every `IActivityManager` method that dispatches a service `Intent`.
     *
     * Matched structurally, because the platform *renames* these. `Context.bindService`
     * reached `IActivityManager.bindService` through Android 10, `bindIsolatedService` in
     * Android 11, and `bindServiceInstance` from Android 12 on. UNIQUE shimmed the first
     * two by exact name and both still exist on the API 34 interface - they are simply
     * never called any more - so the binding report looked healthy while every bind went
     * out unrewritten and `system_server` answered:
     *
     * ```
     * W ActivityManager: Unable to start service Intent { cmp=com.unique.probe/.ProbeService } U=0: not found
     * ```
     *
     * The rule is: it carries an `Intent`, and it is about a service.
     *
     * This deliberately includes the *inbound-originated* members of the family,
     * `publishService` and `unbindFinished`. `ActivityThread.handleBindService` hands the
     * intent it was given straight back to `ActivityManagerService`, which looks the
     * binding up by `Intent.FilterComparison` - so it has to be the stub intent again, or
     * the connection is never completed and `onServiceConnected` never fires. Routing
     * them through the same rewrite restores exactly the intent AMS is holding, because
     * [VirtualServiceRouter.outbound] returns the stub already reserved for that service.
     *
     * `unbindFinished` is named explicitly: it is the one member of the family whose name
     * does not say "service". Methods with no `Intent` - `unbindService`,
     * `stopServiceToken`, `serviceDoneExecuting`, `setServiceForeground` - are keyed on a
     * connection or a token and need no rewrite here.
     */
    internal fun dispatchesServiceIntent(method: Method): Boolean {
        if (method.parameterTypes.none { it == Intent::class.java }) return false
        return method.name.contains("Service") || method.name == "unbindFinished"
    }

    /**
     * Every method that builds a `PendingIntent`, matched on the `Intent[]` it carries.
     *
     * `getIntentSender` gained a `getIntentSenderWithFeature` sibling in Android 11 and
     * the platform calls whichever exists, so neither name can be relied on alone.
     */
    internal fun buildsIntentSender(method: Method): Boolean =
        method.name.startsWith("getIntentSender") &&
            method.parameterTypes.any { it == Array<Intent>::class.java }

    /**
     * `setServiceForeground` and any renaming of it.
     *
     * The shape is pinned rather than assumed: a `ComponentName`, a `Notification` and at
     * least three ints, which on every release so far are `(id, flags,
     * foregroundServiceType)`. Ints carry no evidence about themselves (§6.7.1), so if a
     * release changes that shape the shim declines to bind and the platform's own
     * behaviour is what the guest sees — a visible failure rather than a service started
     * with a mangled type.
     */
    internal fun startsForegroundService(method: Method): Boolean {
        if (!method.name.contains("ServiceForeground")) return false
        val types = method.parameterTypes
        if (types.none { it == ComponentName::class.java }) return false
        if (types.none { it == Notification::class.java }) return false
        return types.count { it == Int::class.javaPrimitiveType } >= 3
    }

    @Volatile private var installedFor: String? = null

    val boundPackage: String? get() = installedFor

    @Synchronized
    fun install(virtualPackage: String, hostContext: Context): Boolean {
        if (installedFor == virtualPackage) return true

        val hostPackage = hostContext.packageName
        val target = SystemServiceHook.TARGETS.first { it.serviceName == "activity" }
        val report = SystemServiceHook.install(
            target,
            shims(virtualPackage, hostPackage, hostSourceFor(hostContext, hostPackage)),
        )
        if (!report.installed) {
            Diagnostics.error(
                DiagChannel.LAUNCH, "VAM_HOOK_FAILED",
                mapOf("package" to virtualPackage, "reason" to (report.reason ?: "?")),
            )
            return false
        }
        installedFor = virtualPackage
        Diagnostics.info(
            DiagChannel.LAUNCH, "VAM_HOOK_INSTALLED",
            mapOf(
                "package" to virtualPackage,
                "host" to hostPackage,
                "methods" to (report.bind?.bound?.size?.toString() ?: "0"),
                // The concrete names, not the shim labels: a shim that binds to nothing
                // the platform still calls is the failure mode this hook already had once.
                // Long enough to name every shim. It was 400, and a truncated list left it
                // an open question whether `getContentProvider` had bound at all — which is
                // exactly the question a physical-device log needed to answer.
                "matched" to (report.bind?.describeMatches()?.take(1600) ?: "-"),
            ),
        )
        return true
    }

    /**
     * The `AttributionSource` every outbound provider call will carry.
     *
     * Normally the host `Context`'s own, which is already valid for this uid and carries a
     * token the system registered. But this runs *inside* a `:vappN` that is in the middle
     * of becoming a guest, and a context whose source has already been rebuilt from the
     * grafted `LoadedApk` would name the guest — substituting that changes nothing and is
     * invisible, because the call fails exactly as it would have anyway.
     *
     * So the name is checked, and a plain source for this uid and this package is built if
     * it is wrong. An untokened source is what an ordinary app has; the token matters for
     * permission delegation, and none is being delegated here.
     */
    internal fun hostSourceFor(hostContext: Context, hostPackage: String): AttributionSource {
        val existing = runCatching { hostContext.attributionSource }.getOrNull()
        if (existing != null && existing.packageName == hostPackage) return existing
        Diagnostics.warn(
            DiagChannel.LAUNCH, "HOST_ATTRIBUTION_REBUILT",
            mapOf(
                "was" to (existing?.packageName ?: "null"),
                "host" to hostPackage,
            ),
        )
        return AttributionSource.Builder(android.os.Process.myUid())
            .setPackageName(hostPackage)
            .build()
    }

    private fun shims(
        virtualPackage: String,
        hostPackage: String,
        hostSource: AttributionSource,
    ): List<MethodShim> = listOf(

        // Registered first, because the first shim that binds to a method wins and this
        // one needs both halves: the outbound package rewrite *and* a wrapped provider.
        shim("getContentProvider") {
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            replaceWith { call ->
                val ready = AppBootstrap.current
                // A guest authority this process publishes is answered right here:
                // ActivityManagerService resolves authorities against *installed*
                // packages and would return null.
                val local = call.args.filterIsInstance<String>()
                    .firstOrNull { VirtualProviderRegistry.owns(it) }
                if (local != null && ready != null) {
                    VirtualProviderRegistry.holderFor(local, ready) ?: call.proceed()
                } else {
                    val redirected = redirectToOwningSlot(call, ready, hostPackage)
                    wrapProviderHolder(call.proceed(), hostSource)
                        ?.also { if (redirected != null) restoreAuthority(it, redirected) }
                }
            }
        },

        // ContextImpl.checkSelfPermission reaches ActivityManagerService, so the same
        // answer has to be given here as on IPackageManager - a guest that gets DENIED
        // from one and GRANTED from the other is worse than either alone.
        shim("permissionCheck") {
            matchMethods { method -> VirtualPermissions.isPermissionCheck(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            replaceWith { call ->
                val permission = VirtualPermissions.permissionArgOf(call.args)
                if (permission == null) call.proceed()
                else VirtualPermissions.check(permission)
                    .also { VirtualPermissions.reportAnswer(permission, it) }
            }
        },

        // A foreground service start. Registered before callerIdentity, which would
        // otherwise claim setServiceForeground for the package rewrite alone.
        shim("foregroundService") {
            matchMethods { method -> startsForegroundService(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteAll<ComponentName>(matching = { it.packageName == virtualPackage }) { name ->
                // Remembered before it is replaced, because the type rule below needs to
                // know *which* of the guest's services is starting and by then the
                // component is the stub's. Rules of one call run in order on one thread.
                foregroundService.set(name.className)
                stubComponentFor(hostPackage, name)
            }
            // Three ints, and none of them says what it is: `(id, flags, type)`. The
            // matcher pins that shape so first and last mean what they are read to mean.
            rewriteFirst<Int> { id -> foregroundNotificationId(id) }
            rewriteLast<Int> { type -> resolveForegroundType(type, foregroundService.get()) }
            rewriteAll<Notification> { n -> VirtualNotificationHook.adaptForeground(n) }
        },

        // A PendingIntent is built by system_server from the Intent it is handed and
        // then fired *later*, by whoever holds it - a notification tap, an alarm, a
        // widget. So the Intent inside has to be a stub Intent at creation time, or the
        // eventual fire names a component the system cannot resolve, long after any
        // UNIQUE code could intervene.
        shim("intentSender") {
            matchMethods { method -> buildsIntentSender(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteAll<Array<Intent>> { intents ->
                Array(intents.size) { routePendingIntent(hostPackage, intents[it]) }
            }
        },

        // Service starts and binds have to be routed onto a stub the host declares:
        // system_server will not start a component of a package it has never installed.
        shim("serviceDispatch") {
            matchMethods { method -> dispatchesServiceIntent(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteFirst<Intent> { intent -> routeService(hostPackage, intent) }
            // The connection callback travels outward on a bind and comes back holding
            // the *stub's* name. See wrapServiceConnection.
            rewriteAll<Any>(matching = { isServiceConnection(it) }) { connection ->
                renameServiceConnection(connection)
            }
        },

        // What the guest sees when it looks at the process table.
        //
        // Registered before `callerIdentity`, which would otherwise claim these methods
        // for the package rewrite alone and leave the result untouched.
        shim("runningProcesses") {
            matchMethods { method ->
                method.name == "getRunningAppProcesses" || method.name == "getServices"
            }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            rewriteResult { result -> rewriteProcessList(result, virtualPackage, hostPackage) }
        },

        // A dynamic receiver, registered under the rules of the app that registered it.
        //
        // Registered before `callerIdentity` for the same reason as `runningProcesses`:
        // that shim would otherwise claim the method for the package rewrite alone.
        shim("registerReceiver") {
            matchMethods { method -> method.name.startsWith("registerReceiver") }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
            replaceWith { call ->
                applyReceiverExportDefault(call)
                call.proceed()
            }
        },

        shim("callerIdentity") {
            matchMethods { method -> carriesCallerIdentity(method) }
            rewriteAll<String>(matching = { it == virtualPackage }) { hostPackage }
        },
    )

    /** `ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST`, which the two-argument call sends. */
    private const val TYPE_FROM_MANIFEST = -1

    /**
     * The guest service whose `startForeground` is being rewritten, for the length of one
     * call. A thread-local because the rules of one call run in order on one thread, and
     * two virtual services can start on two threads at once.
     */
    private val foregroundService = ThreadLocal<String?>()

    /**
     * The two export flags, read from `Context` rather than written down.
     *
     * Public since API 33 and compile-time constants, so referring to them costs nothing
     * and cannot drift. `minSdk` is 31; on 31 and 32 the flags exist and are ignored,
     * which is the behaviour a guest targeting those releases should get anyway.
     */
    private const val RECEIVER_EXPORTED = Context.RECEIVER_EXPORTED
    private const val RECEIVER_NOT_EXPORTED = Context.RECEIVER_NOT_EXPORTED

    /** Android 14, where declaring one of the two became mandatory. */
    private const val EXPORT_REQUIRED_FROM_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Where the export flags sit in `registerReceiver*`.
     *
     * The *last* `int` on every release that has one:
     *
     * ```
     * Intent registerReceiverWithFeature(IApplicationThread caller, String callerPackage,
     *         String callingFeatureId, String receiverId, IIntentReceiver receiver,
     *         IntentFilter filter, String requiredPermission, int userId, int flags);
     * ```
     *
     * Resolved from the method rather than assumed at a fixed index, and internal so the
     * assumption that `flags` follows `userId` is checked against the shapes this has had
     * rather than only against the one on the machine the tests run on.
     */
    internal fun receiverFlagsIndex(types: Array<Class<*>>): Int =
        types.indexOfLast { it == Int::class.javaPrimitiveType }

    /**
     * Supplies the export flag the *guest's* own target SDK would have implied.
     *
     * From Android 14 a dynamic receiver must say whether it is exported:
     *
     * ```
     * SecurityException: com.unique: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
     *   should be specified when a receiver isn't being registered exclusively for
     *   system broadcasts
     *     at IActivityManager$Stub$Proxy.registerReceiverWithFeature
     *     at com.termux.app.TermuxActivity.onStart          <- died here, on its first screen
     * ```
     *
     * The rule is a compat change, and `ActivityManagerService` evaluates it against the
     * **calling uid** — which under virtualization is UNIQUE's. So an app built against
     * Android 9, entitled to the two-argument `registerReceiver` it has always used, is
     * held to UNIQUE's target SDK and refused. Nothing the app can do about it, and
     * nothing about it is the app's fault.
     *
     * `RECEIVER_EXPORTED` rather than the safer-sounding `RECEIVER_NOT_EXPORTED`, because
     * that is what the platform itself does for a pre-34 app: a dynamic receiver was
     * exported by default, and quietly making it private would break a guest that really
     * is talked to by something else on the device — a failure far harder to find than
     * this one.
     *
     * Only when the guest passed neither flag, and only when the guest's own target SDK
     * predates the rule. A guest that targets 34 and passes nothing is making the mistake
     * the rule exists to catch, and gets the platform's own answer.
     */
    private fun applyReceiverExportDefault(call: ShimCall) {
        val ready = AppBootstrap.current ?: return
        if (ready.manifest.targetSdk >= EXPORT_REQUIRED_FROM_SDK) return

        val index = receiverFlagsIndex(call.method.parameterTypes)
        if (index < 0 || index >= call.args.size) return
        val flags = call.args[index] as? Int ?: return
        if (flags and (RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED) != 0) return

        call.args[index] = flags or RECEIVER_EXPORTED
        Diagnostics.event(
            DiagChannel.PROCESS, DiagLevel.DEBUG, "RECEIVER_EXPORT_DEFAULTED",
            mapOf(
                "package" to ready.params.packageName,
                "targetSdk" to ready.manifest.targetSdk.toString(),
                "flag" to "RECEIVER_EXPORTED",
            ),
        )
    }

    /**
     * Rewrites the process table so a guest sees itself, and not UNIQUE.
     *
     * Three separate wrongs in one list, and the first is the one apps notice:
     *
     *  - **Its own process is UNIQUE's.** `RunningAppProcessInfo.processName` is
     *    `com.unique:vapp0` and `pkgList` is `["com.unique"]`, so the extremely common
     *    "am I in the foreground / is my own process running" check answers *no* about an
     *    app that is plainly running. It is also the check a virtualization detector makes
     *    first.
     *  - **UNIQUE's other processes are visible.** `:core`, `:server` and the WebView probe
     *    are in the list under UNIQUE's own name, which is a straightforward tell.
     *  - **So are sibling instances.** Two instances of one app run in two `:vappN`
     *    processes of the same uid; leaving both in the list lets either see the other,
     *    which is the isolation this engine exists to provide.
     *
     * Everything of UNIQUE's uid other than this process is dropped, and this process is
     * renamed to the guest's. Processes of *other* apps are untouched: they are what the
     * platform would have shown, and hiding them would be inventing a device.
     */
    private fun rewriteProcessList(result: Any?, virtualPackage: String, hostPackage: String): Any? {
        val list = ParceledLists.unwrap(result) ?: return null
        if (list.isEmpty()) return null
        val myPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid()
        val ready = AppBootstrap.current

        var changed = false
        val kept = ArrayList<Any?>(list.size)
        for (element in list) {
            if (element == null) continue
            val uid = intField(element, "uid")
            if (uid != myUid) {
                kept += element
                continue
            }
            if (intField(element, "pid") != myPid) {
                // Another of UNIQUE's processes, including a sibling instance.
                changed = true
                continue
            }
            changed = renameProcessEntry(element, virtualPackage, hostPackage, ready) || changed
            kept += element
        }
        if (!changed) return null
        return ParceledLists.wrap(
            if (result is List<*>) List::class.java else result!!.javaClass, kept,
        )
    }

    /** Points one entry at the guest. Returns true when anything was written. */
    private fun renameProcessEntry(
        element: Any,
        virtualPackage: String,
        hostPackage: String,
        ready: AppBootstrap.Result.Ready?,
    ): Boolean {
        var wrote = false
        val processName = ready?.params?.processName ?: virtualPackage
        if (Reflect.set(element.javaClass, "processName", element, processName)) wrote = true
        // `service` on a RunningServiceInfo, whose ComponentName names a stub.
        runCatching {
            val field = element.javaClass.getDeclaredField("service").apply { isAccessible = true }
            val component = field.get(element) as? ComponentName
            if (component != null && component.packageName == hostPackage) {
                val guest = StubRouter.parseStubService(component.className)
                val entry = guest?.let { VirtualServiceRouter.resolve(component.className) }
                if (entry != null) {
                    field.set(element, ComponentName(virtualPackage, entry.className))
                    wrote = true
                }
            }
        }
        runCatching {
            val field = element.javaClass.getDeclaredField("pkgList").apply { isAccessible = true }
            if (field.get(element) is Array<*>) {
                field.set(element, arrayOf(virtualPackage))
                wrote = true
            }
        }
        return wrote
    }

    private fun intField(element: Any, name: String): Int = runCatching {
        element.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(element)
    }.getOrDefault(-1)

    /**
     * The guest's own service an implicit intent names, if exactly one does.
     *
     * `new Intent(ACTION_MY_SERVICE)` is how a great many SDKs reach their own worker —
     * the action is a constant they own, and on a device the resolution never leaves
     * their package. Under virtualization it leaves nothing at all: the guest is not
     * installed, so `PackageManagerService` has no filters to match and the start reaches
     * no one. Resolving it here against the guest's own manifest is the same answer the
     * platform would have given.
     *
     * Three rules keep it from doing harm:
     *
     *  - An intent already scoped to *another* package — `pkg=com.android.vending` for
     *    Play's licensing service is the common one — is left alone. That is an ordinary
     *    cross-app bind and the platform resolves it correctly. Warning about it filled a
     *    device log with thirty lines that named nothing wrong, while the real failure was
     *    a missing `<uses-permission>`.
     *  - More than one match is refused rather than guessed. The platform would refuse
     *    too: `bindService` with an implicit intent that resolves to several services is
     *    an error, not a choice.
     *  - No match is reported and left alone, so the start still reaches the platform and
     *    whatever the device would have done, it still does.
     */
    private fun resolveImplicitService(
        ready: AppBootstrap.Result.Ready,
        intent: Intent,
    ): ComponentName? {
        val guest = ready.params.packageName
        val target = intent.`package`
        if (target != null && target != guest) {
            Diagnostics.event(
                DiagChannel.PROCESS, DiagLevel.DEBUG, "SERVICE_INTENT_CROSS_APP",
                mapOf("action" to (intent.action ?: "-"), "target" to target),
            )
            return null
        }
        val matches = GuestIntentResolution.serviceEntries(ready.manifest, intent, guest)
        if (matches.size != 1) {
            Diagnostics.warn(
                DiagChannel.PROCESS, "SERVICE_INTENT_IMPLICIT",
                mapOf(
                    "action" to (intent.action ?: "-"),
                    "package" to guest,
                    "matches" to matches.size.toString(),
                ),
            )
            return null
        }
        val resolved = ComponentName(guest, matches.single().className)
        Diagnostics.info(
            DiagChannel.PROCESS, "SERVICE_INTENT_RESOLVED_IN_GUEST",
            mapOf("action" to (intent.action ?: "-"), "service" to resolved.className),
        )
        return resolved
    }

    /**
     * Rewrites a service intent onto a stub, or returns it unchanged.
     *
     * Unchanged is the right answer for anything that is not a virtual service: UNIQUE's
     * own components share this process, and rewriting their intents would break them.
     *
     * An intent with no component is resolved against the *guest's* own manifest first,
     * because the platform cannot: the guest's package is not installed, so
     * `PackageManagerService` has no filters for it. Only a match inside the guest is
     * acted on, and only when there is exactly one — see [resolveImplicitService].
     */
    internal fun routeService(hostPackage: String, intent: Intent): Intent {
        val ready = AppBootstrap.current ?: return intent
        val component = intent.component ?: resolveImplicitService(ready, intent)
        if (component == null) return intent
        if (component.packageName != ready.params.packageName) return intent

        val entry = ready.manifest.components.firstOrNull {
            it.kind == ComponentKind.SERVICE && it.className == component.className
        } ?: run {
            Diagnostics.warn(
                DiagChannel.PROCESS, "SERVICE_NOT_DECLARED",
                mapOf("service" to component.className, "package" to ready.params.packageName),
            )
            return intent
        }

        val routed = VirtualServiceRouter.outbound(hostPackage, intent, ready.params, entry)
            ?: return intent
        Diagnostics.event(
            DiagChannel.PROCESS, DiagLevel.DEBUG, "SERVICE_INTENT_ROUTED",
            mapOf(
                "service" to component.className,
                "stub" to (routed.component?.className ?: "-"),
                "action" to (intent.action ?: "-"),
            ),
        )
        return routed
    }

    /**
     * The stub standing in for a guest service, or the name unchanged.
     *
     * `ActivityManagerService` finds the `ServiceRecord` by token and then checks the
     * `ComponentName` against it. The record's name is the stub's — that is what the
     * system started — so a guest name here is rejected with "Service not registered",
     * from inside `startForeground`, several seconds before the platform kills the app for
     * not having called it.
     */
    private fun stubComponentFor(hostPackage: String, guest: ComponentName): ComponentName? {
        val ready = AppBootstrap.current ?: return null
        val entry = ready.manifest.components.firstOrNull {
            it.kind == ComponentKind.SERVICE && it.className == guest.className
        } ?: return null
        val stub = VirtualServiceRouter.reserve(entry, ready.params.vuid) ?: return null
        return ComponentName(hostPackage, stub)
    }

    private fun foregroundNotificationId(id: Int): Int {
        val ready = AppBootstrap.current ?: return id
        return StubRouter.hostNotificationId(ready.params.vuid, id)
    }

    /**
     * Intersects the guest service's declared foreground type with the host's superset.
     *
     * The type the *guest* passes is not the whole story: Android 14 checks it against the
     * manifest of the service that calls `startForeground`, which is the stub. So the
     * effective request is what the guest asked for, narrowed to what the guest declared,
     * narrowed again to what the stub declares — and an empty result is refused, never
     * silently downgraded. A downgraded foreground service dies later with a
     * `ForegroundServiceDidNotStartInTimeException` that a user cannot interpret and a
     * developer cannot reproduce.
     */
    private fun resolveForegroundType(requested: Int, guestClass: String?): Int {
        val ready = AppBootstrap.current ?: return requested
        val declared = ready.manifest.components
            .firstOrNull {
                it.kind == ComponentKind.SERVICE &&
                    if (guestClass != null) it.className == guestClass
                    else it.foregroundServiceType != 0
            }
            ?.foregroundServiceType ?: 0

        // `FOREGROUND_SERVICE_TYPE_MANIFEST` is what the two-argument `startForeground`
        // sends, which is what every app written before Android 10 uses. It means "the
        // type on this service's manifest entry" — and the entry `ActivityManagerService`
        // reads is the *stub's*, which declares every type UNIQUE can host. Passed
        // through, it asks for all of them at once:
        //
        //   FGS_TYPE_RESOLVED requested=0xffffffff declared=0x0 granted=0x400008ff
        //   SecurityException: Starting FGS with type microphone … requires
        //     android.permission.RECORD_AUDIO                    <- Termux died here
        //
        // Resolved against the guest's own entry instead. Zero — an app old enough not to
        // declare one — becomes `specialUse` in `ForegroundServiceTypes.decide`, which is
        // the type that exists for work the taxonomy does not name and the one the host
        // manifest carries a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` for.
        val asked = when {
            requested == TYPE_FROM_MANIFEST -> declared
            requested != 0 -> requested
            else -> declared
        }
        return when (val decision = ForegroundServiceTypes.decide(asked)) {
            is ForegroundServiceTypes.Decision.Allow -> {
                Diagnostics.info(
                    DiagChannel.PROCESS, "FGS_TYPE_RESOLVED",
                    mapOf(
                        "requested" to "0x${Integer.toHexString(requested)}",
                        "declared" to "0x${Integer.toHexString(declared)}",
                        "granted" to "0x${Integer.toHexString(decision.type)}",
                    ),
                )
                decision.type
            }
            is ForegroundServiceTypes.Decision.NotRequired -> requested
            is ForegroundServiceTypes.Decision.Refuse -> {
                Diagnostics.error(
                    DiagChannel.PROCESS, "FGS_REFUSED",
                    mapOf(
                        "package" to ready.params.packageName,
                        "requested" to "0x${Integer.toHexString(decision.requested)}",
                        "reason" to decision.reason,
                    ),
                )
                throw IllegalArgumentException("UNIQUE refused the foreground service: ${decision.reason}")
            }
        }
    }

    /**
     * Routes one Intent inside a `PendingIntent`, by what the component *is*.
     *
     * Dispatching on the guest's own manifest rather than on the framework's
     * `INTENT_SENDER_*` constant keeps this signature-agnostic: the constant is an `int`
     * among several `int` arguments and could only be found by position, while the
     * component's kind is a fact about the app.
     */
    private fun routePendingIntent(hostPackage: String, intent: Intent): Intent {
        val ready = AppBootstrap.current ?: return intent
        val component = intent.component ?: return intent
        if (component.packageName != ready.params.packageName) return intent

        val entry = ready.manifest.components.firstOrNull { it.className == component.className }
            ?: return intent
        return when (entry.kind) {
            ComponentKind.ACTIVITY, ComponentKind.ACTIVITY_ALIAS ->
                VirtualActivityTaskManagerHook.routeActivity(hostPackage, intent)
            ComponentKind.SERVICE -> routeService(hostPackage, intent)
            ComponentKind.RECEIVER -> {
                // A guest's manifest receiver exists only as a *dynamic* registration
                // inside the live process (§6.3), and a dynamic receiver is matched by
                // filter, never by component - so an explicit broadcast PendingIntent
                // aimed at one has nothing to be rewritten onto. It needs a host stub
                // receiver that re-dispatches, which is the same :server work waking a
                // dead guest needs. Left untouched and reported rather than pointed at a
                // component that will fail to resolve when it eventually fires.
                Diagnostics.warn(
                    DiagChannel.LAUNCH, "PENDING_INTENT_RECEIVER_UNSUPPORTED",
                    mapOf(
                        "receiver" to component.className,
                        "package" to ready.params.packageName,
                    ),
                )
                intent
            }
            ComponentKind.PROVIDER -> intent
        }
    }


    /**
     * Points an acquisition at the slot that actually publishes the authority.
     *
     * A guest's provider may live in another of its own processes — `android:process` on a
     * `<provider>` is ordinary and apps rely on it — or another instance's. Neither is
     * something `ActivityManagerService` can resolve, so the authority argument is
     * rewritten to the *stub* provider of the slot that serves it, which AMS does know:
     * it is declared in UNIQUE's manifest. Acquiring it starts that process, and the bind
     * that precedes the rewrite makes the process be the right instance and widens the
     * stub's authority set so the caller's own URIs are accepted afterwards.
     *
     * Returns the guest authority when a redirect happened, so the holder can be told
     * about it, or null when this was not a guest authority at all.
     */
    private fun redirectToOwningSlot(
        call: ShimCall,
        ready: AppBootstrap.Result.Ready?,
        hostPackage: String,
    ): String? {
        if (ready == null) return null
        val index = call.args.indices.firstOrNull { i ->
            val value = call.args[i]
            value is String && isCandidateAuthority(value, hostPackage)
        } ?: return null
        val authority = call.args[index] as String

        // Asking the router about every unknown authority would put an IPC in front of
        // every `settings` and `media` read the guest makes. One miss is remembered.
        if (authority in notGuestAuthorities) return null

        val context = ready.application
        val route = VirtualProviderBridge.resolve(context, ready.params.vuid, authority)
        if (route == null) {
            notGuestAuthorities += authority
            return null
        }
        if (VirtualProviderBridge.bind(context, route) == null) return null

        call.args[index] = VirtualProviderRouter.stubAuthority(hostPackage, route.slot)
        Diagnostics.info(
            DiagChannel.PROCESS, "PROVIDER_ACQUIRE_REDIRECTED",
            mapOf(
                "authority" to authority,
                "slot" to route.slot.toString(),
                "package" to route.packageName,
            ),
        )
        return authority
    }

    // A note on provider *stability*, because the obvious change here is wrong twice over.
    //
    // ActivityManager kills a client whose stable provider's process dies:
    //
    //   Killing …:com.unique:vapp0 (adj 0): depends on provider
    //       com.unique/.stub.ProviderStub_p2 in dying proc com.unique:vapp2
    //
    // which looks like a violation of §3 until you notice what the two processes are: one
    // instance's main process and the same instance's `:alt`. An *installed* app with a
    // provider in `android:process=":alt"` gets exactly this from the platform. It is the
    // contract the guest asked for, not something UNIQUE introduced, and reproducing it is
    // right.
    //
    // Forcing the acquisition unstable to avoid it was tried and is worse than the problem.
    // `stable` is not only a message to ActivityManager: `ActivityThread` keeps its own
    // stable/unstable reference counts on the client side and hands them back on release.
    // Rewriting the flag in flight leaves the two ledgers disagreeing, ActivityManager
    // drops a connection the client still believes it holds, and the next query returns no
    // cursor at all — `t27` and `t32` both went red on it.
    //
    // What §3 actually promises is that one *instance* cannot kill another, or UNIQUE.
    // Nothing a guest writes can name another instance's authority, and UNIQUE's own
    // process reaches guest providers through `acquireUnstableContentProviderClient`
    // (see VirtualProviderBridge.open), so both hold without touching this flag. `t32`
    // asserts them.

    /**
     * Authorities remembered as "not a guest's", so the router is asked at most once.
     *
     * Only ever grows within a process, and only with answers the host gave. An authority
     * that becomes a guest's *after* this process asked would be missed - but that means
     * an app was imported after this virtual process started, and this process cannot be
     * serving it.
     */
    private val notGuestAuthorities =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /** UNIQUE's own authorities are never redirected: that is how the recursion ends. */
    private fun isCandidateAuthority(value: String, hostPackage: String): Boolean =
        value != hostPackage &&
            value != "$hostPackage.router" &&
            !value.startsWith("$hostPackage.vprovider.") &&
            value.contains('.')

    /**
     * Lets `ActivityThread` cache the holder under the authority the caller asked for.
     *
     * `installProvider` keys its map by `holder.info.authority`, which after the redirect
     * names the stub. Without this every acquisition of the guest authority would miss
     * that cache and repeat the whole resolve-and-bind round trip.
     */
    private fun restoreAuthority(holder: Any, guestAuthority: String) {
        runCatching {
            val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                ?: error("ContentProviderHolder.info is not a ProviderInfo")
            val existing = info.authority.orEmpty()
            if (guestAuthority !in existing.split(';')) {
                info.authority = if (existing.isEmpty()) guestAuthority
                else "$existing;$guestAuthority"
            }
            Diagnostics.event(
                DiagChannel.PROCESS, DiagLevel.DEBUG, "PROVIDER_HOLDER_AUTHORITY_EXTENDED",
                mapOf("authority" to info.authority.orEmpty()),
            )
        }.onFailure {
            // Not fatal: the acquisition still works, it just will not be cached, so the
            // next one repeats the resolve and bind. Reported because "slow for no
            // visible reason" is the hardest kind of regression to notice.
            Diagnostics.warn(
                DiagChannel.PROCESS, "PROVIDER_HOLDER_AUTHORITY_UNCHANGED",
                mapOf("authority" to guestAuthority, "error" to it.toString()),
            )
        }
    }

    /**
     * Gives `onServiceConnected` the guest's own `ComponentName`.
     *
     * A bind comes back naming the component AMS actually started, which is a stub:
     *
     * ```
     * onServiceConnected com.unique/.stub.ServiceStub_p0_s0
     * ```
     *
     * That is the name AMS holds and it cannot be anything else, but the app never asked
     * about a stub. Plenty of code switches on `name.getClassName()` to tell two of its own
     * services apart, and every such branch silently takes the wrong path.
     *
     * **The obvious interception does not work, and the reason is worth keeping.** Wrapping
     * the `IServiceConnection` argument on its way out to `system_server` accomplishes
     * nothing: only `asBinder()` is marshalled, so AMS ends up holding the *real*
     * `InnerConnection` and calls back on that, straight past the wrapper. The first
     * version did exactly this and the rename never fired once.
     *
     * The callback is therefore intercepted where it lands. `InnerConnection` holds a weak
     * reference to its `LoadedApk.ServiceDispatcher`, and the dispatcher holds the app's
     * own `ServiceConnection` — so the outgoing argument is used only as a *handle* to
     * reach that field and replace it. What crosses the Binder is untouched.
     */
    private fun renameServiceConnection(connection: Any?): Any? {
        if (connection == null) return connection
        runCatching {
            val dispatcher = dispatcherOf(connection) ?: return connection
            val field = dispatcher.javaClass.getDeclaredField("mConnection")
                .apply { isAccessible = true }
            val existing = field.get(dispatcher) as? ServiceConnection ?: return connection
            if (existing is RenamingConnection) return connection
            field.set(dispatcher, RenamingConnection(existing))
            Diagnostics.event(
                DiagChannel.PROCESS, DiagLevel.DEBUG, "SERVICE_CONNECTION_WRAPPED",
                mapOf("connection" to existing.javaClass.name),
            )
        }.onFailure {
            // Reported rather than swallowed: the bind still works, but every
            // onServiceConnected will name a stub, and that is a fidelity bug the app
            // cannot see the cause of.
            Diagnostics.warn(
                DiagChannel.PROCESS, "SERVICE_CONNECTION_WRAP_FAILED",
                mapOf("class" to connection.javaClass.name, "error" to it.toString()),
            )
        }
        return connection
    }

    /** The `LoadedApk.ServiceDispatcher` behind an `InnerConnection`, if that is what this is. */
    private fun dispatcherOf(connection: Any): Any? {
        val field = connection.javaClass.declaredFields.firstOrNull {
            java.lang.ref.Reference::class.java.isAssignableFrom(it.type)
        } ?: return null
        field.isAccessible = true
        return (field.get(connection) as? java.lang.ref.Reference<*>)?.get()
    }

    private fun isServiceConnection(value: Any?): Boolean {
        if (value == null) return false
        val iface = Reflect.findClass("android.app.IServiceConnection") ?: return false
        return iface.isInstance(value)
    }

    /**
     * The app's own `ServiceConnection`, with stub names translated back.
     *
     * Only names UNIQUE itself invented are translated; anything else — a host service the
     * guest legitimately bound — is passed through exactly as it arrived.
     */
    private class RenamingConnection(private val delegate: ServiceConnection) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) =
            delegate.onServiceConnected(translate(name), service)

        override fun onServiceDisconnected(name: ComponentName?) =
            delegate.onServiceDisconnected(translate(name))

        override fun onBindingDied(name: ComponentName?) = delegate.onBindingDied(translate(name))

        override fun onNullBinding(name: ComponentName?) = delegate.onNullBinding(translate(name))

        private fun translate(name: ComponentName?): ComponentName? {
            if (name == null) return null
            val ready = AppBootstrap.current ?: return name
            val entry = VirtualServiceRouter.resolve(name.className) ?: return name
            val real = ComponentName(ready.params.packageName, entry.className)
            Diagnostics.event(
                DiagChannel.PROCESS, DiagLevel.DEBUG, "SERVICE_CONNECTION_RENAMED",
                mapOf("stub" to name.className, "service" to entry.className),
            )
            return real
        }
    }

    /**
     * Wraps the `IContentProvider` handed back by `getContentProvider`.
     *
     * Acquiring the provider is only half the problem. Every call *through* it carries an
     * `AttributionSource`, built from the guest `Context` and therefore naming the virtual
     * package, and the provider side checks it against the caller's uid:
     *
     * ```
     * SecurityException: Package com.unique.probe does not belong to 10109
     * ```
     *
     * The host's own `AttributionSource` is substituted rather than a rewritten copy of
     * the guest's: an `AttributionSource` carries a token the system registered for it,
     * and editing the package on a tokened source would produce something the platform
     * has every right to reject. The host's is already valid for the host's uid.
     */
    private fun wrapProviderHolder(holder: Any?, hostSource: AttributionSource): Any? {
        if (holder == null) return null
        val providerField = runCatching {
            holder.javaClass.getField("provider")
        }.getOrElse {
            Diagnostics.warn(
                DiagChannel.LAUNCH, "PROVIDER_HOLDER_SHAPE_UNKNOWN",
                mapOf("class" to holder.javaClass.name),
            )
            return holder
        }
        val provider = runCatching { providerField.get(holder) }.getOrNull() ?: return holder
        val wrapped = VirtualProviderProxy.wrap(provider, hostSource) ?: return holder
        runCatching { providerField.set(holder, wrapped) }
        return holder
    }

    internal fun carriesCallerIdentity(method: Method): Boolean {
        if (method.name in IDENTITY_METHODS) return true
        if (method.parameterTypes.any { it.name == APPLICATION_THREAD }) return true
        return asksAboutTheCaller(method)
    }

    /**
     * Whether a method merely asks a question about the package it is handed.
     *
     * Both halves have to hold: the name has to start with a question word, and it must
     * not contain a verb that acts. `getPackageProcessState` passes; `killBackgroundProcesses`
     * does not, and neither would a future `getAndClearSomething`. There must also be a
     * `String` to rewrite, or there is nothing here to do.
     */
    private fun asksAboutTheCaller(method: Method): Boolean {
        if (method.parameterTypes.none { it == String::class.java }) return false
        val name = method.name.lowercase()
        if (ACTS_ON.any { name.contains(it) }) return false
        return ASKS_ABOUT.any { name.startsWith(it) }
    }
}
