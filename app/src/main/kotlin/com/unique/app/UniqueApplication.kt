package com.unique.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.unique.core.common.diag.DiagChannel
import com.unique.core.compat.CompatDatabase
import com.unique.core.diagnostics.CrashGuard
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.HiddenApi
import com.unique.core.nativebridge.UniqueNative
import com.unique.app.engine.UniqueEngine
import com.unique.core.vam.LaunchInterceptor

/** Which of UNIQUE's processes this code is running in. */
enum class UniqueProcess {
    /** Flutter UI and orchestration. Deliberately hook-free. */
    CORE,

    /** VirtualCore server: package/activity/permission state, single writer. */
    SERVER,

    /** A virtual app process. Hooks are installed here and only here. */
    VAPP,

    /** Notification trampolines, FCM, host-GMS bridge. Never loads virtual code. */
    HELPER,
    ;

    companion object {
        fun of(processName: String?, packageName: String): UniqueProcess = when {
            processName == null || processName == packageName -> CORE
            processName.endsWith(":server") -> SERVER
            processName.contains(":vapp") -> VAPP
            else -> HELPER
        }
    }
}

/**
 * Host application entry point.
 *
 * The critical decision here is *what runs where*. Hooks are installed only in `:vappN`
 * processes: the UI process must stay an ordinary Android app, or a bug in the engine
 * takes the whole product down with it, and Flutter would be running against a patched
 * framework for no reason.
 */
class UniqueApplication : Application() {

    lateinit var processKind: UniqueProcess
        private set

    var vappIndex: Int = -1
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        val processName = currentProcessName(base)
        processKind = UniqueProcess.of(processName, base.packageName)
        vappIndex = processName?.substringAfter(":vapp", "")?.toIntOrNull() ?: -1

        Diagnostics.verbose = BuildConfig.DEBUG
        CrashGuard.install { processName }

        Diagnostics.info(
            DiagChannel.PROCESS, "PROCESS_START",
            mapOf(
                "process" to (processName ?: "?"),
                "kind" to processKind.name,
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "abi" to Build.SUPPORTED_ABIS.joinToString(","),
            ),
        )

        when (processKind) {
            UniqueProcess.CORE -> initCore()
            UniqueProcess.SERVER -> initServer()
            UniqueProcess.VAPP -> initVirtualApp()
            UniqueProcess.HELPER -> Unit
        }
    }

    private fun initCore() {
        CompatDatabase.load(this)
        // The native library is loaded here only to report page size and load failures in
        // Diagnostics; the UI process installs no hooks.
        if (UniqueNative.load()) {
            Diagnostics.info(
                DiagChannel.NATIVE, "NATIVE_LOADED",
                mapOf("pageSize" to UniqueNative.pageSize().toString()),
            )
        } else {
            Diagnostics.error(
                DiagChannel.NATIVE, "NATIVE_LOAD_FAILED",
                mapOf("error" to UniqueNative.loadFailure.orEmpty()),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Engine initialisation happens here rather than in attachBaseContext: it opens
        // the state database, and doing that before the Application is fully attached is
        // both unnecessary and, as an earlier crash showed, unsafe.
        if (processKind == UniqueProcess.CORE) {
            UniqueEngine.init(this)
        }
    }

    private fun initServer() {
        CompatDatabase.load(this)
        UniqueNative.load()
        // Hidden-API access is checked once here so the UI can report it before the user
        // tries to launch anything, rather than failing at first launch.
        HiddenApi.ensure()
    }

    /**
     * Prepares a virtual app process.
     *
     * No package is bound yet: which instance this slot serves is decided by the launch
     * transaction, and binding here would mean guessing. [LaunchInterceptor] performs the
     * bootstrap synchronously on the main thread when the transaction arrives, which is
     * also what guarantees `Application.onCreate` runs before the first Activity.
     *
     * If the interceptor cannot be installed, the process stays a plain host process and
     * the stub activity reports and finishes - a visible failure rather than an app
     * running under UNIQUE's identity in UNIQUE's own data directory.
     */
    private fun initVirtualApp() {
        if (HiddenApi.ensure() != HiddenApi.State.GRANTED) {
            Diagnostics.error(
                DiagChannel.LAUNCH, "VAPP_HIDDEN_API_DENIED",
                mapOf("slot" to vappIndex.toString(), "detail" to HiddenApi.failureDetail.orEmpty()),
            )
            return
        }
        UniqueNative.load()
        if (!LaunchInterceptor.install(this)) {
            Diagnostics.error(
                DiagChannel.LAUNCH, "VAPP_INTERCEPTOR_UNAVAILABLE",
                mapOf("slot" to vappIndex.toString()),
            )
            return
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "VAPP_READY",
            mapOf("slot" to vappIndex.toString()),
        )
    }

    /**
     * The process name.
     *
     * `Application.getProcessName()` exists from API 28 and is the only reliable source;
     * the `/proc/self/cmdline` fallback covers OEM builds that have been observed to
     * return null from it.
     */
    private fun currentProcessName(context: Context): String? =
        runCatching { getProcessName() }.getOrNull()
            ?: runCatching {
                java.io.File("/proc/self/cmdline").readText().takeWhile { it.code > 0 }.trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: context.applicationInfo.processName
}
