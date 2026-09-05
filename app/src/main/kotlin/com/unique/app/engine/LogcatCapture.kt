package com.unique.app.engine

import android.os.Process
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagRedactor
import com.unique.core.diagnostics.Diagnostics
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * The part of a failure that only the system log knows.
 *
 * UNIQUE's own structured events say what UNIQUE did. They cannot say that ART refused to
 * verify a class, that the linker could not map a library on a 16 KB-page device, that
 * `system_server` killed a `:vappN` for `bg anr`, or what the stack trace of an uncaught
 * exception was — all of which are the actual answer often enough to matter, and all of
 * which have needed `adb logcat` to see. A tester with a phone and no computer had no way
 * to get any of it, which is the gap this closes.
 *
 * ## What it can read
 *
 * Only UNIQUE's own lines. `logd` filters a read to the caller's uid unless the caller
 * holds `READ_LOGS`, which a third-party app is not granted; so this returns UNIQUE and
 * its `:vappN` processes, and nothing about any other app on the device. That is a
 * platform guarantee, not a filter applied here.
 *
 * ## What it deliberately drops
 *
 * UNIQUE's own uid includes *the guests*, and an app is free to log whatever it likes,
 * including its own credentials. So lines are kept only when their tag is one of
 * [SYSTEM_TAGS] — the framework, runtime, linker and UNIQUE itself — and app-authored
 * logging is dropped rather than reasoned about. What survives that still goes through
 * [DiagRedactor], because an exception *message* can carry a token and exception messages
 * are exactly what is being kept.
 *
 * The trade is deliberate and worth naming: a guest's own `Log.d` is often what would
 * explain its behaviour, and it is not here. Dropping it is the only version of this
 * feature that can be put in a file a user is invited to mail to a stranger.
 */
object LogcatCapture {

    /**
     * Tags worth keeping, matched on a whole-word basis against the line's tag.
     *
     * Framework and runtime only. Everything here is written by the platform or by UNIQUE;
     * none of it is written by an app about its own data.
     */
    private val SYSTEM_TAGS = setOf(
        // UNIQUE's own.
        "Unique", "UniqueNative", "UniqueProbe",
        // Crashes, ANRs and the process lifecycle.
        "AndroidRuntime", "ActivityManager", "ActivityTaskManager", "DEBUG", "libc",
        "System.err", "Process", "am_crash", "ActivityThread",
        // Class loading, the runtime and the linker — where a 16 KB-page or ABI failure
        // announces itself, and where nothing else will.
        "art", "dalvikvm", "linker", "nativeloader", "ziparchive", "DexFile",
        // Graphics and WebView, for the three things only a phone can answer.
        "vulkan", "libEGL", "EGL_emulation", "OpenGLRenderer", "chromium", "cr_",
        "WebViewFactory", "WebViewZygote",
        // Storage and permissions.
        "PackageManager", "AppOps", "SQLiteLog", "SQLiteDatabase",
    )

    /** A `-v time` line: `09-05 07:05:44.338 I/tag ( 1234): message`. */
    private val LINE = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} ([VDIWEF])/([^(]*)\(\s*(\d+)\):\s?(.*)$""")

    private const val TIMEOUT_SECONDS = 20L

    /**
     * Reads the log and returns what is safe to keep, newest last.
     *
     * Never throws: a device that refuses the read contributes an explanation instead of
     * failing the export it was part of. A failure here is a missing section, not a
     * missing package.
     */
    fun capture(maxLines: Int = 4_000): List<String> {
        val started = System.currentTimeMillis()
        val raw = runCatching { read() }.getOrElse {
            return listOf(
                "logcat could not be read on this device: $it",
                "This is not fatal — the rest of the package is unaffected. Some OEM builds",
                "restrict the log even for an app reading its own lines.",
            )
        }
        if (raw.isEmpty()) {
            return listOf("logcat returned nothing for this app's own uid.")
        }

        var dropped = 0
        val kept = ArrayList<String>(minOf(raw.size, maxLines))
        for (line in raw) {
            val m = LINE.matchEntire(line)
            if (m == null) {
                // A continuation line — a stack trace's frames arrive this way. Keep it
                // only if the line it continues was kept, which is what `kept` last holds.
                if (kept.isNotEmpty() && line.startsWith("\t")) kept += DiagRedactor.redact(line)
                else dropped++
                continue
            }
            val tag = m.groupValues[2].trim()
            if (!isKept(tag)) { dropped++; continue }
            kept += DiagRedactor.redact(line)
        }

        val trimmed = if (kept.size > maxLines) kept.subList(kept.size - maxLines, kept.size) else kept
        Diagnostics.info(
            DiagChannel.STORAGE, "LOGCAT_CAPTURED",
            mapOf(
                "read" to raw.size.toString(),
                "kept" to trimmed.size.toString(),
                // Not "droppedAppAuthored": the redactor normalises a field name and
                // drops anything containing `auth`, so that one came out as `[redacted]`
                // and the count was unreadable. Being conservative there is right; naming
                // a counter something that trips it is not.
                "droppedNonSystem" to dropped.toString(),
                "millis" to (System.currentTimeMillis() - started).toString(),
            ),
        )
        return buildList {
            add("# UNIQUE's own uid only — the platform will not show another app's log.")
            add("# App-authored lines are dropped: $dropped of ${raw.size}. See LogcatCapture.")
            add("")
            addAll(trimmed)
        }
    }

    /**
     * Whether a tag survives the filter.
     *
     * Prefix matching for the two families that number their tags — Chromium writes
     * `cr_Something` and ART writes `art`, `artd` and so on — and exact matching for the
     * rest, because a substring rule would let an app named `libcurl` through as `libc`.
     */
    private fun isKept(tag: String): Boolean =
        tag in SYSTEM_TAGS || SYSTEM_TAGS.any { it.endsWith("_") && tag.startsWith(it) }

    private fun read(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "-v", "time")
            .redirectErrorStream(true)
            .start()
        val lines = try {
            process.inputStream.bufferedReader().use(BufferedReader::readLines)
        } finally {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        return lines
    }

    /** The pid this ran in, so a reader can tell UNIQUE's own lines from a guest's. */
    fun hostPid(): Int = Process.myPid()
}
