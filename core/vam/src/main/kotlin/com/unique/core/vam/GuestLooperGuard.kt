package com.unique.core.vam

import android.os.Looper
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.google.GoogleStackVisibility
import com.unique.core.diagnostics.Diagnostics

/**
 * Lets a guest survive the one exception its own SDK would have survived.
 *
 * ## The crash
 *
 * Google Play services refuses a bind whose calling package does not belong to the
 * calling uid. Inside UNIQUE that is every guest, and where the refusal lands is decided
 * by which client library the app happens to link:
 *
 * ```
 * FATAL EXCEPTION: main
 * java.lang.SecurityException: Unknown calling package name 'com.gordey.standarling'.
 *     at android.os.Parcel.readException
 *     at …common.internal.c.getRemoteService (play-services-basement@@17.4.0:25)
 *     at …common.internal.c$g.handleMessage (play-services-basement@@17.4.0:35)
 *     at android.os.Handler.dispatchMessage · Looper.loop · ActivityThread.main
 * ```
 *
 * `handleMessage`. The refusal is delivered to the app's *own main looper*, so there is
 * no frame between it and `ActivityThread.main` that belongs to anybody — not the app,
 * not UNIQUE. Nothing can catch it and the process dies.
 *
 * The same refusal, in the same run, against a newer client:
 *
 * ```
 * E GoogleApiManager: Failed to get service from broker.
 * java.lang.SecurityException: Unknown calling package name 'com.openai.chatgpt'.
 * ```
 *
 * Logged and handled. One device log had 29 refusals across four apps; three were fatal,
 * and all three were the old client delivering it on the looper. **The difference is not
 * what UNIQUE did — it is which version of Google's code was in the APK.**
 *
 * ## Why catching it here is the honest answer and not a swallow
 *
 * `CrashGuard` says, in as many words, that a process which survives an unhandled
 * exception is in an undefined state and must be allowed to die. That rule is right and
 * this is not an exception to it, because this exception is *not unhandled* — it is
 * handled by every current version of the library that raises it. Catching it reproduces
 * the behaviour of `play-services-basement` 18.x exactly: the bind fails, the client is
 * told, and the app carries on without that one Google feature.
 *
 * So the guard is deliberately not general. It catches one sentence, from one API, and
 * re-throws everything else into the handler that ends the process. A guard that caught
 * more would be the crash-suppressor `CrashGuard` refuses to be.
 *
 * ## What it cost before
 *
 * The alternative already in the tree is to hide Play services from an instance after it
 * has died once, which works and costs a crash and a Google-shaped hole in the app. This
 * costs neither: the stack stays visible, `DynamiteModule`, Maps and the ad ID keep
 * working, and only the call that was refused is lost. Hiding remains as the fallback for
 * an app that hits the refusal in a loop, where surviving each one is worse than being
 * told there is no Google at all.
 */
object GuestLooperGuard {

    /**
     * How many refusals in one launch mean the app is retrying rather than recovering.
     *
     * Each catch re-enters `Looper.loop()` one frame deeper, so an app that hammers the
     * refusal would grow the stack without bound. Past this many, hiding Play services
     * from the instance — which ends the retries at the source — is the smaller cost.
     */
    private const val THRASH_LIMIT = 24

    /** Called once, with the count so far, when [THRASH_LIMIT] is reached. */
    @Volatile
    var onThrash: ((Int) -> Unit)? = null

    @Volatile
    private var installed = false

    private var survived = 0

    /**
     * Wraps the calling thread's message loop.
     *
     * Must run *on* the main thread and inside its loop — it is posted there rather than
     * called, because `Looper.loop()` does not return and the only way to wrap it is to
     * call it again from inside itself. The nesting that creates is one frame per
     * survived refusal, which is why [THRASH_LIMIT] exists.
     */
    fun install(mainLooper: Looper) {
        if (installed) return
        installed = true
        android.os.Handler(mainLooper).post { loopForever() }
    }

    private fun loopForever() {
        while (true) {
            try {
                Looper.loop()
                // Reached only if something quit the looper, which for a main looper
                // means the process is on its way out anyway.
                return
            } catch (error: Throwable) {
                if (!GoogleStackVisibility.isRefusedCallingPackage(error)) throw error
                survived++
                Diagnostics.warn(
                    DiagChannel.LAUNCH, "GOOGLE_REFUSAL_SURVIVED",
                    mapOf(
                        "count" to survived.toString(),
                        "reason" to (error.message?.take(160) ?: error.javaClass.simpleName),
                    ),
                )
                if (survived == THRASH_LIMIT) {
                    runCatching { onThrash?.invoke(survived) }
                }
                if (survived >= THRASH_LIMIT) throw error
            }
        }
    }

    /** For tests: the number of refusals this process has survived. */
    internal fun survivedCount(): Int = survived

    internal fun resetForTest() {
        installed = false
        survived = 0
        onThrash = null
    }

    /**
     * The decision [loopForever] makes, extracted so it can be tested without a Looper.
     *
     * @return true when the loop should continue, false when the throwable belongs to the
     *   uncaught handler.
     */
    internal fun shouldSurvive(error: Throwable, alreadySurvived: Int): Boolean =
        GoogleStackVisibility.isRefusedCallingPackage(error) && alreadySurvived + 1 < THRASH_LIMIT

    internal fun thrashLimit(): Int = THRASH_LIMIT
}
