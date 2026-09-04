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
    FAILED(3);

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
    @JvmStatic private external fun nativeRedirectSlotsPatched(): Int
    @JvmStatic private external fun nativeWatchLibraryLoads(): Int
    @JvmStatic private external fun nativeInstallIoRedirect(): Int
    @JvmStatic private external fun nativeSetProperty(key: String, value: String)
    @JvmStatic private external fun nativeClearProperties()
    @JvmStatic private external fun nativeLookupProperty(key: String): String?
    @JvmStatic private external fun nativeInstallPropertyVirtualization(): Int
}
