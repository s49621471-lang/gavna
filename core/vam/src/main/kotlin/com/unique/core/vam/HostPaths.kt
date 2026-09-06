package com.unique.core.vam

import android.content.Context

/**
 * Where UNIQUE's own files are, remembered once so that nothing re-derives it later.
 *
 * ## The bug this exists to prevent, which is not hypothetical
 *
 * Every instance path in the engine is computed from one root:
 *
 * ```kotlin
 * VirtualPathModel(context.filesDir.absolutePath)   // /data/user/0/com.unique/files
 * ```
 *
 * That was safe for as long as `getFilesDir()` in a `:vappN` process could only ever
 * answer with UNIQUE's directory. [GuestIdentityPaths] ends that: after it runs, the
 * guest's `Context` reports `/data/user/0/<guest>/files`, because that is the whole point
 * of it. A `VirtualPathModel` built from *that* is rooted inside the guest's own public
 * directory, and every path it produces is wrong in a way that reads as correct —
 * `…/files/virtual/jobs/…` under a directory that has no `virtual` in it at all.
 *
 * The call that would have hit it first is `VirtualJobDispatcher.stop`, which looks up a
 * job's routing record through `guest.filesDir` — the guest's context, deliberately, so
 * that the guest's own `getFilesDir()` resolves into the instance. It would have found no
 * record and stopped no job, silently, on a device, months from here.
 *
 * ## What this does instead
 *
 * The root is captured at the start of the graft, from UNIQUE's own context, before any
 * identity is swapped. Afterwards every caller asks for the remembered value and only
 * falls back to reading a context when there is nothing remembered — which is the state
 * in UNIQUE's own processes, where `getFilesDir()` is still the truth.
 *
 * The value is deliberately *not* recomputed or refreshed. A second answer to this
 * question is the bug.
 */
internal object HostPaths {

    @Volatile private var remembered: String? = null

    /**
     * Records UNIQUE's own files directory. The first answer wins.
     *
     * Called from the graft with the host context, which at that moment is still
     * UNIQUE's own — the swap happens several hundred lines later, after the class loader
     * and the resources have been built from the real paths.
     */
    fun remember(context: Context): String? =
        remember(runCatching { context.filesDir?.absolutePath }.getOrNull())

    /** [remember], with the path already read. Separated so the rule can be tested. */
    fun remember(path: String?): String? {
        remembered?.let { return it }
        if (path.isNullOrBlank()) return null
        synchronized(this) {
            remembered?.let { return it }
            remembered = path
        }
        return path
    }

    /**
     * UNIQUE's files directory: the remembered one, or [context]'s if nothing is
     * remembered yet.
     *
     * The fallback is not a guess. In UNIQUE's own processes no graft has run, no identity
     * has been swapped and `context.filesDir` is the answer; in a `:vappN` the graft
     * remembers before anything can ask.
     */
    fun filesRoot(context: Context): String =
        remembered ?: context.filesDir.absolutePath.also { remember(context) }

    /** The remembered root, or null when this process has never grafted. */
    val known: String? get() = remembered

    /** Test seam. Not called by the engine. */
    internal fun forget() {
        synchronized(this) { remembered = null }
    }
}
