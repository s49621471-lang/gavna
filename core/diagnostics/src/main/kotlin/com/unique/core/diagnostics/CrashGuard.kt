package com.unique.core.diagnostics

import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagLevel

/**
 * Records a crash and lets the process die.
 *
 * Deliberately does *not* swallow the exception: a virtual app process that survives an
 * unhandled exception is in an undefined state and will produce worse failures later.
 * ARCHITECTURE.md section 3.1 relies on `:vappN` actually dying so `:server` observes it
 * through a death recipient and the UI can offer *Restart*.
 */
object CrashGuard {

    /** What the UI shows. Full stack traces stay in diagnostics. */
    data class CrashSummary(
        val timestampMillis: Long,
        val component: String?,
        val shortReason: String,
    )

    @Volatile private var last: CrashSummary? = null

    fun lastCrash(): CrashSummary? = last

    /**
     * Called with the crash before the process dies, for anyone who has to *record*
     * something about it.
     *
     * The one caller today writes a per-instance decision that makes the next launch
     * behave differently — which is only possible here, because a crash that kills the
     * process is the last moment anything in it can write a file. Registered rather than
     * built in so `core/diagnostics` keeps knowing nothing about instances.
     *
     * Whatever it does must be short and must not throw; it runs on a thread that is
     * already dying, and anything it leaves undone is lost with the process.
     */
    @Volatile
    var observer: ((Throwable) -> Unit)? = null

    fun install(component: () -> String?) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val summary = CrashSummary(
                    timestampMillis = System.currentTimeMillis(),
                    component = component(),
                    shortReason = shortReason(error),
                )
                last = summary
                Diagnostics.event(
                    channel = DiagChannel.CRASH,
                    level = DiagLevel.ERROR,
                    code = "UNCAUGHT_EXCEPTION",
                    fields = buildMap {
                        put("thread", thread.name)
                        summary.component?.let { put("component", it) }
                        put("reason", summary.shortReason)
                    },
                    throwable = error,
                )
                // Before the export, not after: the observer may add a diagnostic of its
                // own, and the export is meant to be the last thing that runs.
                runCatching { observer?.invoke(error) }
                // Push it out of this process before the process stops existing. A ring
                // buffer in a dying process is a record nobody will ever read, and
                // "every crash leaves a diagnostic trace" is a rule about what the *user*
                // can see afterwards, not about what was briefly in memory.
                Diagnostics.remoteSink?.let { sink ->
                    val records = Diagnostics.snapshot(DiagChannel.CRASH).map(Diagnostics::formatted)
                    runCatching { sink(records) }
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A one-line reason a non-developer can act on, derived from the exception chain's
     * root cause rather than the outermost wrapper (which is usually a framework
     * `RuntimeException` that says nothing).
     */
    internal fun shortReason(error: Throwable): String {
        var root: Throwable = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val type = root.javaClass.simpleName
        val message = root.message?.lineSequence()?.firstOrNull()?.trim()
        return if (message.isNullOrEmpty()) type else "$type: ${message.take(160)}"
    }
}
