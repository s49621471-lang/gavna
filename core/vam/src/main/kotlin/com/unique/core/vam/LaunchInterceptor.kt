package com.unique.core.vam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Message
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
import com.unique.core.common.permission.PlatformPermissions
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.Reflect
import java.lang.reflect.Field

/**
 * Rewrites the activity-launch transaction before `ActivityThread` acts on it.
 *
 * The system will only ever launch a component declared in UNIQUE's own manifest, so a
 * virtual activity is started through a stub. By the time the launch reaches the target
 * process it is a `ClientTransaction` carrying the stub's `Intent` and `ActivityInfo`.
 * Swapping those two for the virtual app's own - before `performLaunchActivity` reads
 * them - is what makes the platform instantiate the app's real Activity class, from the
 * app's own class loader, with the app's own theme and package name. The stub class is
 * never instantiated on the success path.
 *
 * Why here and not in `Instrumentation.newActivity`: the activity's `Context` is built
 * from `ActivityInfo.applicationInfo`, so an interception that only replaces the class
 * leaves the activity reporting UNIQUE's package name and UNIQUE's data directory.
 *
 * Why by field *type* and not by field name: `LaunchActivityItem`'s layout has changed in
 * most recent releases. Its `Intent` and `ActivityInfo` fields have not changed type, and
 * there is exactly one of each.
 */
object LaunchInterceptor {

    private const val FALLBACK_EXECUTE_TRANSACTION = 159

    /**
     * Service lifecycle still arrives as plain `ActivityThread.H` messages, not as
     * `ClientTransaction`s, so the same callback covers them. Ids are read from the
     * class rather than hard-coded; the fallbacks are the values that have held since
     * API 21 and are only used if the field cannot be read.
     */
    private val SERVICE_MESSAGES = mapOf(
        "CREATE_SERVICE" to 114,
        "SERVICE_ARGS" to 115,
        "STOP_SERVICE" to 116,
        "BIND_SERVICE" to 121,
        "UNBIND_SERVICE" to 122,
    )

    @Volatile private var installed = false
    @Volatile private var hostContext: Context? = null

    /** Counted so a launch that never reaches the callback is distinguishable. */
    @Volatile private var transactionsSeen = 0
    @Volatile private var messagesSeen = 0

    val isInstalled: Boolean get() = installed

    /**
     * Installs the interceptor on this process's `ActivityThread`.
     *
     * `Handler.mCallback` runs before `Handler.handleMessage`, so this sees every
     * transaction on the main thread, synchronously, before the framework does. The
     * callback always returns false: UNIQUE rewrites the message, it does not consume it.
     */
    @Synchronized
    fun install(context: Context): Boolean {
        if (installed) return true
        // getApplicationContext() is null while the Application is still being attached,
        // which is exactly when this runs. Holding the base context instead is correct -
        // and holding null here made every launch fail silently at the guard below,
        // producing no diagnostic at all. That silence is what made this expensive to
        // find, so the guard now reports too.
        hostContext = context.applicationContext ?: context

        val activityThreadClass = Reflect.findClass("android.app.ActivityThread") ?: run {
            report("INTERCEPTOR_INSTALL_FAILED", mapOf("reason" to "no ActivityThread"))
            return false
        }
        val activityThread = Reflect.findMethod(activityThreadClass, "currentActivityThread")
            ?.invoke(null) ?: run {
            report("INTERCEPTOR_INSTALL_FAILED", mapOf("reason" to "no current ActivityThread"))
            return false
        }
        val handler = Reflect.get(activityThreadClass, "mH", activityThread) as? Handler ?: run {
            report("INTERCEPTOR_INSTALL_FAILED", mapOf("reason" to "no mH"))
            return false
        }

        val executeTransaction = resolveExecuteTransaction(handler.javaClass)
        val serviceMessages = SERVICE_MESSAGES.mapValues { (name, fallback) ->
            resolveMessageId(handler.javaClass, name, fallback)
        }
        val createService = serviceMessages.getValue("CREATE_SERVICE")
        val serviceIntentMessages = setOf(
            serviceMessages.getValue("SERVICE_ARGS"),
            serviceMessages.getValue("BIND_SERVICE"),
            serviceMessages.getValue("UNBIND_SERVICE"),
        )
        val previous = Reflect.get(Handler::class.java, "mCallback", handler) as? Handler.Callback

        val callback = Handler.Callback { msg ->
            // Liveness. Without this, "the callback never ran" and "the callback ran but
            // recognised nothing" produce identical evidence: silence.
            if (messagesSeen < 4) {
                messagesSeen++
                Diagnostics.event(
                    DiagChannel.LAUNCH, DiagLevel.DEBUG, "CALLBACK_ALIVE",
                    mapOf("what" to msg.what.toString(), "obj" to (msg.obj?.javaClass?.name ?: "-")),
                )
            }
            when (msg.what) {
                executeTransaction -> {
                    transactionsSeen++
                    runCatching { rewrite(msg) }.onFailure {
                        report("TRANSACTION_REWRITE_FAILED",
                            mapOf("error" to it.toString(), "seen" to transactionsSeen.toString()))
                    }
                    runCatching { rewriteNewIntents(msg) }.onFailure {
                        report("NEW_INTENT_REWRITE_FAILED", mapOf("error" to it.toString()))
                    }
                    runCatching { observePermissionResult(msg) }.onFailure {
                        report("PERMISSION_RESULT_READ_FAILED", mapOf("error" to it.toString()))
                    }
                }
                createService -> runCatching { rewriteCreateService(msg) }.onFailure {
                    report("CREATE_SERVICE_REWRITE_FAILED", mapOf("error" to it.toString()))
                }
                in serviceIntentMessages -> runCatching { rewriteServiceIntent(msg) }.onFailure {
                    report("SERVICE_INTENT_REWRITE_FAILED", mapOf("error" to it.toString()))
                }
            }
            previous?.handleMessage(msg) ?: false
        }

        if (!Reflect.set(Handler::class.java, "mCallback", handler, callback)) {
            report("INTERCEPTOR_INSTALL_FAILED", mapOf("reason" to "mCallback not writable"))
            return false
        }
        installed = true
        Diagnostics.info(
            DiagChannel.LAUNCH, "INTERCEPTOR_INSTALLED",
            mapOf("executeTransaction" to executeTransaction.toString()),
        )
        return true
    }

    /** `ActivityThread.H.EXECUTE_TRANSACTION`, read rather than hard-coded. */
    private fun resolveExecuteTransaction(handlerClass: Class<*>): Int =
        resolveMessageId(handlerClass, "EXECUTE_TRANSACTION", FALLBACK_EXECUTE_TRANSACTION)

    private fun resolveMessageId(handlerClass: Class<*>, name: String, fallback: Int): Int =
        runCatching {
            handlerClass.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
        }.getOrDefault(fallback)

    // ---------------------------------------------------------------------------------
    // Services
    // ---------------------------------------------------------------------------------

    /**
     * Replaces the stub's `ServiceInfo` with the guest's.
     *
     * `CreateServiceData` carries no `Intent` on every release, so which virtual service
     * this stands for is resolved from the *stub's identity* through
     * [VirtualServiceRouter] — which is why each concurrently-running virtual service
     * gets a stub of its own.
     */
    private fun rewriteCreateService(msg: Message) {
        val data = msg.obj ?: return
        val infoField = fieldOfType(data.javaClass, ServiceInfo::class.java) ?: run {
            report("CREATE_SERVICE_SHAPE_UNKNOWN", mapOf("class" to data.javaClass.name))
            return
        }
        val stubInfo = infoField.get(data) as? ServiceInfo ?: return

        // A cold broadcast delivery starts a stub service purely to bring the process up,
        // and the *stub* is what must run - there is no guest service to swap in. Leaving
        // its ServiceInfo alone is what lets StubServiceBase see the intent and hand the
        // broadcast to the guest's receiver.
        //
        // Recognised from the stub's index rather than from a flag set by the engine: the
        // engine runs in UNIQUE's own process and this runs in `:vappN`, so anything it
        // "told" this object would be told to a different copy of it.
        if (StubRouter.parseStubService(stubInfo.name)?.second ==
            VirtualServiceRouter.COLD_BROADCAST_STUB_INDEX
        ) {
            Diagnostics.info(
                DiagChannel.PROCESS, "CREATE_SERVICE_COLD_BROADCAST",
                mapOf("stub" to stubInfo.name),
            )
            return
        }

        val entry = VirtualServiceRouter.resolve(stubInfo.name)
        if (entry == null) {
            // Not one of ours, or a stub whose reservation is gone. Either way, leaving it
            // alone lets the stub run and report, which is visible.
            Diagnostics.event(
                DiagChannel.PROCESS, DiagLevel.DEBUG, "CREATE_SERVICE_UNMAPPED",
                mapOf("stub" to stubInfo.name),
            )
            return
        }
        val ready = AppBootstrap.current
        if (ready == null) {
            report("CREATE_SERVICE_BEFORE_BOOTSTRAP",
                mapOf("stub" to stubInfo.name, "service" to entry.className))
            return
        }
        val virtualInfo = AppBootstrap.serviceInfoFor(ready, entry.className) ?: return
        infoField.set(data, virtualInfo)

        // The Intent, when the release carries one, still names the stub.
        fieldOfType(data.javaClass, Intent::class.java)?.let { intentField ->
            (intentField.get(data) as? Intent)?.let { stubIntent ->
                intentField.set(data, unwrapServiceIntent(stubIntent, entry.className))
            }
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "CREATE_SERVICE_REWRITTEN",
            mapOf("stub" to stubInfo.name, "service" to entry.className),
        )
    }

    /** `SERVICE_ARGS`, `BIND_SERVICE` and `UNBIND_SERVICE` all carry the stub Intent. */
    private fun rewriteServiceIntent(msg: Message) {
        val data = msg.obj ?: return
        val intentField = fieldOfType(data.javaClass, Intent::class.java) ?: return
        val stubIntent = intentField.get(data) as? Intent ?: return
        val params = VirtualLaunchParams.from(stubIntent) ?: return
        // An out-of-band process start must reach the stub with its extras intact: the
        // stub is the thing that runs, and it needs the parameters to know what to be. A
        // cold broadcast carries the guest's intent inside; a provider warm carries only
        // the identity to graft.
        if (params.kind == VirtualComponentKind.RECEIVER ||
            params.kind == VirtualComponentKind.PROVIDER
        ) {
            return
        }
        val target = params.targetComponent ?: return
        intentField.set(data, unwrapServiceIntent(stubIntent, target))
    }

    /** Restores the guest's own component and removes UNIQUE's extras. */
    private fun unwrapServiceIntent(stubIntent: Intent, className: String): Intent {
        val params = VirtualLaunchParams.from(stubIntent)
        return Intent(stubIntent).apply {
            component = ComponentName(params?.packageName ?: className.substringBeforeLast('.'), className)
            setPackage(null)
            removeExtra(VirtualLaunchParams.KEY_VUID)
            removeExtra(VirtualLaunchParams.KEY_PACKAGE)
            removeExtra(VirtualLaunchParams.KEY_VERSION_CODE)
            removeExtra(VirtualLaunchParams.KEY_COMPONENT)
            removeExtra(VirtualLaunchParams.KEY_KIND)
            removeExtra(VirtualLaunchParams.KEY_PROCESS)
            removeExtra(VirtualLaunchParams.KEY_SLOT)
            removeExtra(VirtualLaunchIntent.KEY_GUEST_IDENTIFIER)
        }
    }

    // ---------------------------------------------------------------------------------
    // Runtime permission results
    // ---------------------------------------------------------------------------------

    private const val EXTRA_PERMISSION_NAMES =
        "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"
    private const val EXTRA_PERMISSION_RESULTS =
        "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS"

    /**
     * Records the outcome of a permission request the *guest* made.
     *
     * `Activity.requestPermissions` is a `startActivityForResult` at heart: the platform
     * shows its dialog for the caller — which under virtualization is UNIQUE, correctly,
     * because UNIQUE is the process the kernel will check — and hands the answer back as
     * an ordinary activity result carrying two parallel arrays.
     *
     * Reading it here is what lets a grant belong to *one instance*. UNIQUE never widens:
     * the recorded grant is intersected with the host's own at every check, so this can
     * only ever turn an instance's DENIED into GRANTED once the platform has already said
     * yes to UNIQUE.
     *
     * Observed, and rewritten in exactly one case: a permission UNIQUE serves itself.
     *
     * The platform's answer for those is not about the guest and cannot be made to be.
     * UNIQUE targets SDK 36, and since 33 the platform auto-denies
     * `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` to any such app — with no
     * dialog shown, because there is no dialog. The guest asked about *its own* storage,
     * which is a directory inside UNIQUE's `filesDir` that it can already read, so
     * handing that denial back is answering a different question:
     *
     * ```
     * PERMISSION_RESULT_RECORDED permission=…READ_EXTERNAL_STORAGE granted=false   (x156)
     * I Unity: No permission to read external storage. Skipping OBB loading.       (x156)
     * ```
     *
     * 156 is the game asking again every frame, and never getting a different answer.
     * For those permissions the array is rewritten to GRANTED before the guest sees it,
     * which is what [PlatformPermissions.SELF_SERVED] already answers to a direct
     * `checkSelfPermission`; without it the two disagree and an app that trusts its
     * callback stays broken. Everything else still reaches
     * `onRequestPermissionsResult` exactly as the platform sent it.
     */
    private fun observePermissionResult(msg: Message) {
        val transaction = msg.obj ?: return
        val ready = AppBootstrap.current ?: return
        for (intent in resultIntents(transaction)) {
            // A result carries the guest's own Parcelables as often as a launch does —
            // an activity that returns its own configuration object, for one — and the
            // loader has to be set before anything reads it. See adoptGuestClassLoader.
            adoptGuestClassLoader(intent, ready)
            val names = intent.getStringArrayExtra(EXTRA_PERMISSION_NAMES) ?: continue
            val results = intent.getIntArrayExtra(EXTRA_PERMISSION_RESULTS) ?: continue
            if (names.size != results.size) {
                report(
                    "PERMISSION_RESULT_MALFORMED",
                    mapOf("names" to names.size.toString(), "results" to results.size.toString()),
                )
                continue
            }
            val corrected = ArrayList<String>(1)
            names.forEachIndexed { i, permission ->
                var granted = results[i] == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted && PlatformPermissions.isSelfServed(permission)) {
                    results[i] = android.content.pm.PackageManager.PERMISSION_GRANTED
                    granted = true
                    corrected += permission
                }
                VirtualPermissions.recordGrant(permission, granted)
            }
            if (corrected.isNotEmpty()) {
                // Written back so the guest's own callback sees the corrected array. The
                // int[] is the same object the Bundle holds, but putExtra is what makes
                // the change survive a Bundle that has already been unparcelled lazily.
                intent.putExtra(EXTRA_PERMISSION_RESULTS, results)
                report(
                    "PERMISSION_RESULT_SELF_SERVED",
                    mapOf(
                        "permissions" to corrected.joinToString(","),
                        "detail" to "the platform denies these to UNIQUE on API 33+ and " +
                            "they are about the instance's own directory, not the device's",
                    ),
                )
            }
        }
    }

    /**
     * Gives an `Intent`'s extras the guest's own class loader.
     *
     * ## The crash this is for
     *
     * An `Intent` crosses a process boundary as a parcelled `Bundle` that is not read
     * until something asks for a value. Whatever loader the `Bundle` is carrying at that
     * moment is the one that has to find the class — and inside a `:vappN` the default is
     * UNIQUE's, which knows nothing about the guest's APK. So a guest that puts one of
     * *its own* `Parcelable` classes into an `Intent` gets it back as this:
     *
     * ```
     * BadParcelableException: ClassNotFoundException when unmarshalling:
     *     com.google.android.gms.auth.api.signin.internal.SignInConfiguration
     *   at android.os.Parcel.readParcelableCreatorInternal
     *   at android.os.Bundle.getParcelable
     *   at …signin.internal.SignInHubActivity.onCreate
     * FATAL EXCEPTION: main
     * ```
     *
     * That is Google Sign-In. The flow had started correctly, the activity had launched
     * inside the guest, and it died reading the configuration it had written itself one
     * step earlier. The class is in the guest's own APK — the app bundles
     * `play-services-auth` — so nothing was missing except the loader that could see it.
     *
     * It is not a Google-specific bug and the fix is not Google-specific either: any app
     * passing its own `Parcelable` through an `Intent` hits exactly this, which is why it
     * is applied to every intent UNIQUE hands to a guest rather than to a special case.
     *
     * Setting the loader does not unparcel anything; it only decides who will be asked
     * when something finally does.
     */
    private fun adoptGuestClassLoader(intent: Intent, ready: AppBootstrap.Result.Ready) {
        runCatching {
            intent.setExtrasClassLoader(ready.application.classLoader)
        }.onFailure {
            report("INTENT_CLASSLOADER_UNSET", mapOf("error" to it.toString()))
        }
    }

    /**
     * Every `Intent` carried by a `ResultInfo` in this transaction.
     *
     * Found by type rather than by field name, for the reason `LaunchActivityItem` is:
     * `ActivityResultItem` holds its results in a list whose field name has changed, and
     * `ResultInfo`'s `Intent` field has not changed type.
     */
    private fun resultIntents(transaction: Any): List<Intent> {
        val out = ArrayList<Intent>(1)
        forEachNestedList(transaction) { element ->
            if (element.javaClass.simpleName == "ResultInfo") {
                fieldOfType(element.javaClass, Intent::class.java)
                    ?.let { it.get(element) as? Intent }
                    ?.let(out::add)
            }
        }
        return out
    }

    /** Visits every element of every list-valued field, one level of nesting deep. */
    private inline fun forEachNestedList(root: Any, visit: (Any) -> Unit) {
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 64) {
            val node = queue.removeFirst()
            seen++
            var clazz: Class<*>? = node.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    field.isAccessible = true
                    val value = runCatching { field.get(node) }.getOrNull() ?: continue
                    if (value is List<*>) {
                        for (element in value) if (element != null) {
                            visit(element)
                            queue.add(element)
                        }
                    }
                }
                clazz = clazz.superclass
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Redelivered intents
    // ---------------------------------------------------------------------------------

    /**
     * Gives a redelivered intent back to the guest before `onNewIntent` sees it.
     *
     * A launch does not always create an activity. `FLAG_ACTIVITY_NEW_TASK` alone is
     * enough for the platform to find the task that is already running the same component
     * and hand the intent to the activity on top of it instead —
     *
     * ```
     * START u0 {…cmp=com.unique/.stub.ActivityStub_p0_m0_a0} … result code=3
     * ```
     *
     * `START_DELIVERED_TO_TOP`, which is what "tap the app again and it comes back where
     * you left it" is. It is the right behaviour and UNIQUE keeps it. But the intent that
     * arrives is the *stub's*: it names `com.unique/.stub.ActivityStub_p0_m0_a0` and
     * carries UNIQUE's routing extras, and `ActivityThread.deliverNewIntents` assigns it
     * to `Activity.mIntent` — so from that moment `getIntent()` answers with it too, for
     * the rest of the activity's life.
     *
     * An app that reads its own intent in `onNewIntent` is not doing anything unusual:
     * that is how a notification tap, a deep link and every `singleTop` screen carry their
     * argument. Left alone it would read a component that is not its own and find none of
     * the extras it sent.
     *
     * Rewritten **in place** rather than replaced: the list is declared
     * `List<ReferrerIntent>` and the platform reads `mReferrer` off each element, so a
     * plain `Intent` substituted for one would lose the referrer and could not be stored
     * back into the list on a release that copies it.
     */
    private fun rewriteNewIntents(msg: Message) {
        val transaction = msg.obj ?: return
        // Cheapest test first: this runs on the main thread for every transaction the
        // process receives, and before the graft there is nothing to rewrite anyway.
        val ready = AppBootstrap.current ?: return
        val items = findItems(transaction, "NewIntentItem")
        if (items.isEmpty()) return

        for (item in items) {
            val listField = fieldOfType(item.javaClass, List::class.java) ?: run {
                report("NEW_INTENT_SHAPE_UNKNOWN", mapOf("class" to item.javaClass.name))
                null
            } ?: continue
            val intents = listField.get(item) as? List<*> ?: continue
            for (element in intents) {
                val stubIntent = element as? Intent ?: continue
                val params = VirtualLaunchParams.from(stubIntent) ?: continue
                if (params.packageName != ready.params.packageName) {
                    report(
                        "NEW_INTENT_WRONG_GUEST",
                        mapOf("carried" to params.packageName, "running" to ready.params.packageName),
                    )
                    continue
                }
                val entry = AppBootstrap.resolveActivity(ready.manifest, params.targetComponent)
                if (entry == null) {
                    report(
                        "NEW_INTENT_ACTIVITY_NOT_FOUND",
                        mapOf(
                            "package" to params.packageName,
                            "requested" to (params.targetComponent ?: "<launcher>"),
                        ),
                    )
                    continue
                }
                unwrapActivityIntentInPlace(stubIntent, params.packageName, entry.className)
                Diagnostics.info(
                    DiagChannel.LAUNCH, "NEW_INTENT_REWRITTEN",
                    mapOf(
                        "package" to params.packageName,
                        "activity" to entry.className,
                        "vuid" to params.vuid.toString(),
                        "item" to item.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    /** Restores the guest's component and identifier and strips UNIQUE's routing extras. */
    private fun unwrapActivityIntentInPlace(intent: Intent, packageName: String, className: String) {
        val guestIdentifier = intent.getStringExtra(VirtualLaunchIntent.KEY_GUEST_IDENTIFIER)
        intent.component = ComponentName(packageName, className)
        intent.setPackage(null)
        intent.identifier = guestIdentifier
        intent.removeExtra(VirtualLaunchParams.KEY_VUID)
        intent.removeExtra(VirtualLaunchParams.KEY_PACKAGE)
        intent.removeExtra(VirtualLaunchParams.KEY_VERSION_CODE)
        intent.removeExtra(VirtualLaunchParams.KEY_COMPONENT)
        intent.removeExtra(VirtualLaunchParams.KEY_KIND)
        intent.removeExtra(VirtualLaunchParams.KEY_PROCESS)
        intent.removeExtra(VirtualLaunchParams.KEY_SLOT)
        intent.removeExtra(VirtualLaunchIntent.KEY_GUEST_IDENTIFIER)
    }

    // ---------------------------------------------------------------------------------

    private fun rewrite(msg: Message) {
        val transaction = msg.obj ?: return
        val item = findLaunchItem(transaction)
        if (item == null) {
            // Most transactions are not launches, so this is usually correct and silent.
            // It is reported at debug level with the transaction's shape because the one
            // time it matters - a launch whose item UNIQUE failed to recognise - is
            // otherwise indistinguishable from the common case.
            Diagnostics.event(
                DiagChannel.LAUNCH, DiagLevel.DEBUG, "NO_LAUNCH_ITEM",
                mapOf(
                    "transaction" to transaction.javaClass.name,
                    "shape" to describe(transaction),
                    "seen" to transactionsSeen.toString(),
                ),
            )
            return
        }

        val intentField = fieldOfType(item.javaClass, Intent::class.java) ?: run {
            report("LAUNCH_ITEM_SHAPE_UNKNOWN",
                mapOf("class" to item.javaClass.name, "missing" to "Intent"))
            return
        }
        val infoField = fieldOfType(item.javaClass, ActivityInfo::class.java) ?: run {
            report("LAUNCH_ITEM_SHAPE_UNKNOWN",
                mapOf("class" to item.javaClass.name, "missing" to "ActivityInfo"))
            return
        }

        val stubIntent = intentField.get(item) as? Intent
        if (stubIntent == null) {
            report("LAUNCH_ITEM_NO_INTENT", mapOf("class" to item.javaClass.name))
            return
        }
        val params = VirtualLaunchParams.from(stubIntent)
        if (params == null) {
            // A launch of one of UNIQUE's own activities - the UI, say - is not ours to
            // rewrite. Reported at debug with the component so a launch that *should*
            // have carried parameters and did not is visible.
            Diagnostics.event(
                DiagChannel.LAUNCH, DiagLevel.DEBUG, "LAUNCH_NOT_VIRTUAL",
                mapOf(
                    "component" to (stubIntent.component?.flattenToShortString() ?: "-"),
                    "extras" to (stubIntent.extras?.keySet()?.joinToString(",") ?: "-"),
                ),
            )
            return
        }

        val context = hostContext
        if (context == null) {
            report("INTERCEPTOR_NO_CONTEXT", mapOf("package" to params.packageName))
            return
        }
        val ready = when (val result = AppBootstrap.bootstrap(context, params)) {
            is AppBootstrap.Result.Ready -> result
            is AppBootstrap.Result.Failed -> {
                // The stub is left in place. It reports and finishes, which surfaces as a
                // clear failure instead of an activity that renders nothing.
                Diagnostics.error(
                    DiagChannel.LAUNCH, "BOOTSTRAP_FAILED",
                    mapOf(
                        "package" to params.packageName,
                        "vuid" to params.vuid.toString(),
                        "code" to result.code,
                        "message" to result.message,
                    ),
                )
                return
            }
        }

        val entry = AppBootstrap.resolveActivity(ready.manifest, params.targetComponent) ?: run {
            Diagnostics.error(
                DiagChannel.LAUNCH, "ACTIVITY_NOT_FOUND",
                mapOf(
                    "package" to params.packageName,
                    "requested" to (params.targetComponent ?: "<launcher>"),
                ),
            )
            return
        }
        val activityInfo = AppBootstrap.activityInfoFor(ready, entry.className) ?: return

        // The real intent the app will see. Flags, categories and data carry over from
        // the stub intent so behaviour requested at the call site is preserved; UNIQUE's
        // own extras are removed so the app never sees them.
        val realIntent = Intent(stubIntent).apply {
            component = ComponentName(params.packageName, entry.className)
            setPackage(null)
            removeExtra(VirtualLaunchParams.KEY_VUID)
            removeExtra(VirtualLaunchParams.KEY_PACKAGE)
            removeExtra(VirtualLaunchParams.KEY_VERSION_CODE)
            removeExtra(VirtualLaunchParams.KEY_COMPONENT)
            removeExtra(VirtualLaunchParams.KEY_PROCESS)
            removeExtra(VirtualLaunchParams.KEY_SLOT)
            // UNIQUE's stub identity comes off here; the guest gets its own back.
            VirtualLaunchIntent.restoreGuestIdentity(this, stubIntent)
            // And so does the loader that can read what is inside it. See below.
            adoptGuestClassLoader(this, ready)
        }

        intentField.set(item, realIntent)
        infoField.set(item, activityInfo)

        Diagnostics.info(
            DiagChannel.LAUNCH, "TRANSACTION_REWRITTEN",
            mapOf(
                "package" to params.packageName,
                "activity" to entry.className,
                "vuid" to params.vuid.toString(),
                "item" to item.javaClass.simpleName,
                "theme" to activityInfo.theme.toString(),
            ),
        )
    }

    /**
     * Finds the `LaunchActivityItem` inside a `ClientTransaction`.
     *
     * Its location has moved: a dedicated `mActivityCallbacks` list in Android 12-14, and
     * a merged item list in newer releases. Rather than encoding either layout, every
     * list-valued field is scanned along with direct item fields.
     */
    private fun findLaunchItem(transaction: Any): Any? {
        var clazz: Class<*>? = transaction.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                field.isAccessible = true
                val value = runCatching { field.get(transaction) }.getOrNull() ?: continue
                if (value is List<*>) {
                    value.firstOrNull { isLaunchItem(it) }?.let { return it }
                } else if (isLaunchItem(value)) {
                    return value
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun isLaunchItem(value: Any?): Boolean =
        value != null && value.javaClass.simpleName == "LaunchActivityItem"

    /**
     * Every item in a `ClientTransaction` whose class has this simple name.
     *
     * Same reason as [findLaunchItem] for scanning both list-valued and direct fields, and
     * the same reason for matching on the simple name: these classes are `@hide` and not
     * on the app's classpath, so there is no type to compare against.
     */
    private fun findItems(transaction: Any, simpleName: String): List<Any> {
        val out = ArrayList<Any>(1)
        var clazz: Class<*>? = transaction.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                field.isAccessible = true
                val value = runCatching { field.get(transaction) }.getOrNull() ?: continue
                if (value is List<*>) {
                    for (element in value) {
                        if (element != null && element.javaClass.simpleName == simpleName) {
                            out.add(element)
                        }
                    }
                } else if (value.javaClass.simpleName == simpleName) {
                    out.add(value)
                }
            }
            clazz = clazz.superclass
        }
        return out
    }

    /** One-line description of a transaction's fields, for the NO_LAUNCH_ITEM report. */
    private fun describe(transaction: Any): String = buildString {
        var clazz: Class<*>? = transaction.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                field.isAccessible = true
                val value = runCatching { field.get(transaction) }.getOrNull()
                append(field.name).append(':')
                when (value) {
                    null -> append("null")
                    is List<*> -> append("[").append(
                        value.joinToString("|") { it?.javaClass?.simpleName ?: "null" }
                    ).append("]")
                    else -> append(value.javaClass.simpleName)
                }
                append(' ')
            }
            clazz = clazz.superclass
        }
    }.trim()

    /**
     * The single field of [type] on [clazz].
     *
     * Returns null when there is more than one candidate: guessing between two fields of
     * the same type would silently rewrite the wrong one, and a reported unknown shape is
     * far better than an activity launched with the wrong intent.
     */
    private fun fieldOfType(clazz: Class<*>, type: Class<*>): Field? {
        val matches = ArrayList<Field>(1)
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            c.declaredFields.filterTo(matches) { it.type == type }
            c = c.superclass
        }
        if (matches.size != 1) return null
        return matches.single().apply { isAccessible = true }
    }

    private fun report(code: String, fields: Map<String, String>) =
        Diagnostics.error(DiagChannel.LAUNCH, code, fields)
}
