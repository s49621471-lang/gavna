package com.unique.core.nativebridge

import com.unique.core.common.path.RedirectRule

/** Result of installing a native subsystem. Mirrors `unique::InstallStatus`. */
enum class InstallStatus(val code: Int) {
    OK(0),

    /**
     * The subsystem is built and its data structures work, but the mechanism that makes
     * it take effect is not implemented yet. Reported to Diagnostics so an unimplemented
     * subsystem is visible as unimplemented rather than appearing to succeed.
     */
    NOT_IMPLEMENTED(1),
    UNSUPPORTED_DEVICE(2),
    FAILED(3),

    /** Installed earlier in this process. Not an error, and not a second install. */
    ALREADY_INSTALLED(4),

    /**
     * Installed, and nothing in scope was loaded to hook *yet*.
     *
     * Not an error. A guest with no native code never has anything to hook, and one that
     * loads its libraries from its own engine's initialiser - Unity, most obviously - has
     * nothing to hook at bootstrap and is covered by the dlopen watch a moment later.
     * This used to be reported as the not-implemented status, which said the subsystem
     * did not exist when in fact it was working.
     */
    NOTHING_TO_HOOK(5);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: FAILED
    }
}

/**
 * The only entry point to `libunique_native`.
 *
 * Keeping every `external fun` in one object means the JNI surface is enumerable: a
 * reviewer can see the whole native contract in one screen, and the diagnostics reporter
 * can describe the state of every native subsystem without reaching into other modules.
 */
object UniqueNative {

    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    /**
     * Loads the library. Returns false and records the reason rather than throwing,
     * because a failure here must degrade UNIQUE to Java-only path handling with a
     * diagnostic, not crash the process the user is trying to launch an app in.
     */
    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("unique_native")
            loaded = true
            true
        } catch (t: Throwable) {
            loadError = t.toString()
            false
        }
    }

    val isLoaded: Boolean get() = loaded
    val loadFailure: String? get() = loadError

    /**
     * The kernel page size. 16384 on Android 15/16 devices configured with 16 KB pages,
     * where a 4 KB-aligned `.so` will not map at all.
     */
    fun pageSize(): Int = if (loaded) nativePageSize() else 4096

    /** Publishes the rule table produced by `VirtualPathModel.redirectionRules`. */
    fun setRedirectRules(rules: List<RedirectRule>) {
        if (!loaded) return
        nativeSetRedirectRules(
            rules.map { it.from }.toTypedArray(),
            rules.map { it.to }.toTypedArray(),
        )
    }

    fun clearRedirectRules() { if (loaded) nativeClearRedirectRules() }

    fun redirectRuleCount(): Int = if (loaded) nativeRedirectRuleCount() else 0

    /**
     * Applies the native table to [path], returning null when no rule matched.
     *
     * Used by the on-device consistency suite to assert that the native table agrees
     * with `VirtualPathModel`. A disagreement means an app can read one instance's data
     * while writing another's, so it is checked rather than assumed.
     */
    fun redirect(path: String): String? = if (loaded) nativeRedirect(path) else null

    /**
     * Limits the interception to libraries whose path contains one of [paths].
     *
     * Must be set before [installIoRedirect], which refuses an empty scope. Patching every
     * library in the process would redirect UNIQUE's own file operations as well as the
     * guest's, and "redirect everything" is not a thing to do by accident.
     */
    fun setRedirectScope(paths: List<String>) {
        if (loaded) nativeSetRedirectScope(paths.toTypedArray())
    }

    /**
     * Keeps the interception *out* of libraries whose path contains one of [paths].
     *
     * Applies on top of [setRedirectScope], and exists because a PLT hook is safe for
     * ordinary code and unsafe for a library that inspects its own GOT. A
     * code-virtualization protector does exactly that, and answers a patched slot by
     * jumping into its own generated code with a corrupt dispatch value — which is not a
     * crash in the hooked library but one in an anonymous page, several frames and
     * several seconds away from anything that names UNIQUE. See
     * `GuestNativeExclusions` for the run that established this and the list it produced.
     *
     * The cost is bounded and stated: an excluded library's hard-coded
     * `/data/data/<pkg>` paths are not rewritten. Every exclusion that actually applied
     * is named in logcat by the native layer, so "not hooked on purpose" is never
     * confused with "the scan missed it".
     */
    fun setRedirectExclusions(paths: List<String>) {
        if (loaded) nativeSetRedirectExclusions(paths.toTypedArray())
    }

    /** How many exclusions the native layer is holding. */
    fun redirectExclusionCount(): Int = if (loaded) nativeRedirectExclusionCount() else 0

    /** GOT entries patched by the last [installIoRedirect]. Zero means nothing was hooked. */
    fun redirectSlotsPatched(): Int = if (loaded) nativeRedirectSlotsPatched() else 0

    /**
     * Keeps the interception current as the guest loads more libraries.
     *
     * The initial hook walks what is loaded at that moment, so a library loaded later has
     * an untouched GOT. This notices the load and re-scans.
     */
    fun watchLibraryLoads(): InstallStatus =
        if (loaded) InstallStatus.of(nativeWatchLibraryLoads()) else InstallStatus.FAILED

    fun installIoRedirect(): InstallStatus =
        if (loaded) InstallStatus.of(nativeInstallIoRedirect()) else InstallStatus.FAILED

    /**
     * Records a native crash to [path] before the process dies.
     *
     * The platform's own tombstone is still produced — the previous handler is chained to,
     * not replaced. What this adds is a record UNIQUE can read afterwards and put in a
     * diagnostics export, which is what rule 10 is about: a trace the *user* can hand to
     * someone, not one that exists only in `logcat` on a device they no longer have.
     */
    fun installCrashHandler(path: String): InstallStatus =
        if (loaded) InstallStatus.of(nativeInstallCrashHandler(path)) else InstallStatus.FAILED

    /**
     * What Vulkan this device has, as newline-separated `key=value` lines.
     *
     * Creates a real instance, physical device, logical device and graphics queue — not a
     * `dlopen` check. `libvulkan.so` is present on essentially every Android 10+ device,
     * including ones whose loader finds no driver at all, so "the library exists" is worth
     * nothing as an answer.
     *
     * Run from UNIQUE's own process, which is the point: it is what a guest's result is
     * compared *against*, so a device with no working Vulkan is never recorded as UNIQUE
     * having broken it.
     */
    fun probeVulkan(): Map<String, String> {
        if (!loaded) return mapOf("ran" to "false", "error" to (loadFailure ?: "native library not loaded"))
        return runCatching { parse(nativeProbeVulkan()) }
            .getOrElse { mapOf("ran" to "false", "error" to it.toString()) }
    }

    private fun parse(report: String): Map<String, String> =
        report.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    fun setProperty(key: String, value: String) { if (loaded) nativeSetProperty(key, value) }
    fun clearProperties() { if (loaded) nativeClearProperties() }
    fun lookupProperty(key: String): String? = if (loaded) nativeLookupProperty(key) else null

    fun installPropertyVirtualization(): InstallStatus =
        if (loaded) InstallStatus.of(nativeInstallPropertyVirtualization()) else InstallStatus.FAILED

    @JvmStatic private external fun nativePageSize(): Int
    @JvmStatic private external fun nativeSetRedirectRules(from: Array<String>, to: Array<String>)
    @JvmStatic private external fun nativeClearRedirectRules()
    @JvmStatic private external fun nativeRedirectRuleCount(): Int
    @JvmStatic private external fun nativeRedirect(path: String): String?
    @JvmStatic private external fun nativeSetRedirectScope(paths: Array<String>)
    @JvmStatic private external fun nativeSetRedirectExclusions(paths: Array<String>)
    @JvmStatic private external fun nativeRedirectExclusionCount(): Int
    @JvmStatic private external fun nativeRedirectSlotsPatched(): Int
    @JvmStatic private external fun nativeWatchLibraryLoads(): Int
    @JvmStatic private external fun nativeInstallIoRedirect(): Int
    @JvmStatic private external fun nativeSetProperty(key: String, value: String)
    @JvmStatic private external fun nativeClearProperties()
    @JvmStatic private external fun nativeLookupProperty(key: String): String?
    @JvmStatic private external fun nativeInstallPropertyVirtualization(): Int
    @JvmStatic private external fun nativeInstallCrashHandler(path: String): Int
    @JvmStatic private external fun nativeProbeVulkan(): String
}
