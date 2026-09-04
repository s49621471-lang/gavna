package com.unique.core.hook

import android.os.Build
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Non-SDK interface access.
 *
 * UNIQUE cannot work without deep reflective access to `ActivityThread`, `LoadedApk`,
 * `ServiceManager` and `ClientTransaction`, all of which are restricted from Android 9
 * onwards and further narrowed since. This is therefore a hard prerequisite, checked
 * once and cached, with the outcome reported rather than assumed.
 *
 * Strategy, in order (ARCHITECTURE.md section 4.1):
 *  1. `HiddenApiBypass` — the `sun.misc.Unsafe` + boot-classloader `Executable[]` path.
 *  2. `VMRuntime.setHiddenApiExemptions(["L"])`, reached through the bypass from step 1.
 *  3. Native fallback — not implemented; see [nativeFallbackAvailable].
 *
 * If none succeeds, UNIQUE refuses to launch virtual apps and says so plainly, instead of
 * half-working in ways that surface as unrelated crashes deep inside an app.
 */
object HiddenApi {

    enum class State { UNKNOWN, GRANTED, DENIED }

    @Volatile private var state = State.UNKNOWN
    @Volatile private var detail: String? = null

    val isGranted: Boolean get() = ensure() == State.GRANTED
    val failureDetail: String? get() = detail

    @Synchronized
    fun ensure(): State {
        if (state != State.UNKNOWN) return state

        // Below API 28 there is no restriction to lift.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            state = State.GRANTED
            return state
        }

        val attempts = mutableListOf<String>()

        // 1. HiddenApiBypass.
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }.onFailure { attempts += "HiddenApiBypass: $it" }

        if (probe()) {
            state = State.GRANTED
            Diagnostics.info(DiagChannel.HOOK, "HIDDEN_API_GRANTED", mapOf("via" to "HiddenApiBypass"))
            return state
        }

        // 2. VMRuntime.setHiddenApiExemptions, reached reflectively.
        runCatching {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = HiddenApiBypass.getDeclaredMethod(vmRuntimeClass, "getRuntime")
            val setExemptions = HiddenApiBypass.getDeclaredMethod(
                vmRuntimeClass, "setHiddenApiExemptions", Array<String>::class.java,
            )
            val runtime = getRuntime.invoke(null)
            setExemptions.invoke(runtime, arrayOf("L"))
        }.onFailure { attempts += "VMRuntime.setHiddenApiExemptions: $it" }

        if (probe()) {
            state = State.GRANTED
            Diagnostics.info(DiagChannel.HOOK, "HIDDEN_API_GRANTED", mapOf("via" to "VMRuntime"))
            return state
        }

        // 3. Native fallback is not implemented; see the property below.
        state = State.DENIED
        detail = attempts.joinToString("; ")
        Diagnostics.error(
            DiagChannel.HOOK, "HIDDEN_API_DENIED",
            mapOf("sdk" to Build.VERSION.SDK_INT.toString(), "attempts" to detail.orEmpty()),
        )
        return state
    }

    /**
     * TODO(phase-3): resolve the ART hidden-API policy through libart symbols for devices
     * where both Java-level strategies fail. Not implemented; reported as such so the
     * Diagnostics screen can say "no fallback available" instead of implying one exists.
     */
    const val nativeFallbackAvailable: Boolean = false

    /**
     * Verifies access rather than trusting that the call above worked: the bypass
     * libraries fail differently across OEM ART builds, and a silent failure here turns
     * into an unexplainable crash later.
     */
    private fun probe(): Boolean = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentActivityThread")
        val loadedApk = Class.forName("android.app.LoadedApk")
        loadedApk.getDeclaredField("mApplication")
        true
    }.getOrDefault(false)
}
