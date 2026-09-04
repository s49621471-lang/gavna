package com.unique.core.vam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Message
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel
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
            if (msg.what == executeTransaction) {
                transactionsSeen++
                runCatching { rewrite(msg) }.onFailure {
                    report("TRANSACTION_REWRITE_FAILED",
                        mapOf("error" to it.toString(), "seen" to transactionsSeen.toString()))
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
        runCatching {
            handlerClass.getDeclaredField("EXECUTE_TRANSACTION")
                .apply { isAccessible = true }
                .getInt(null)
        }.getOrDefault(FALLBACK_EXECUTE_TRANSACTION)

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

        val entry = AppBootstrap.resolveActivity(ready.manifest, params.targetActivity) ?: run {
            Diagnostics.error(
                DiagChannel.LAUNCH, "ACTIVITY_NOT_FOUND",
                mapOf(
                    "package" to params.packageName,
                    "requested" to (params.targetActivity ?: "<launcher>"),
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
            removeExtra(VirtualLaunchParams.KEY_ACTIVITY)
            removeExtra(VirtualLaunchParams.KEY_PROCESS)
            removeExtra(VirtualLaunchParams.KEY_SLOT)
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
