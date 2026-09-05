package com.unique.app.engine

import android.content.Context
import android.os.Build
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.hook.HiddenApi
import com.unique.core.nativebridge.UniqueNative
import com.unique.core.vam.AppBootstrap
import com.unique.core.vam.VirtualDiagnostics
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles the diagnostics package a user can hand to someone else.
 *
 * ## What is in it
 *
 * UNIQUE's own structured event log, the same log pulled from every `:vappN` that is
 * currently alive, whatever crash records dying processes managed to push here before they
 * went, and a description of the device and the build. Enough to explain why an app did
 * not launch without anyone having to reproduce it.
 *
 * ## What is deliberately not in it
 *
 * Nothing from inside an instance's data directory: no databases, no `shared_prefs`, no
 * cookies, no tokens. That is not a filter applied at the end — this code never opens
 * those directories, which is a stronger guarantee than remembering to exclude them. The
 * instance summary lists identity that UNIQUE itself generated (`vuid`, package, version,
 * the virtual `ANDROID_ID`) because that is what a support conversation is about, and
 * nothing an app stored.
 *
 * Every line still passes through `DiagRedactor` on its way out (see
 * [Diagnostics.exportLines]), because an app is free to log its own secrets and UNIQUE
 * records what apps log.
 *
 * The file is written into UNIQUE's own cache directory — app-private storage, not
 * anything world-readable — and shared onwards only if the user chooses to.
 */
object DiagnosticsExport {

    private const val DIRECTORY = "diagnostics"

    data class Result(val file: File, val bytes: Long, val processes: Int, val lines: Int)

    /**
     * Writes a package and returns where it landed.
     *
     * @param liveSlots the `:vappN` slots believed to hold a process; each is asked, and
     *   one that has already died contributes nothing rather than failing the export.
     */
    fun write(
        context: Context,
        liveSlots: List<Int>,
        instances: List<Map<String, String>>,
    ): Result = write(context, liveSlots, instances, includeDeviceReport = true)

    fun write(
        context: Context,
        liveSlots: List<Int>,
        instances: List<Map<String, String>>,
        includeDeviceReport: Boolean,
    ): Result {
        val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        // One file per export rather than a fixed name: two exports minutes apart are
        // usually two different questions, and overwriting the first loses the answer.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val zipFile = File(dir, "unique-diagnostics-$stamp.zip")

        val own = Diagnostics.exportLines()
        val remote = VirtualDiagnostics.receivedLines()
        val perSlot = liveSlots.associateWith { slot ->
            VirtualDiagnostics.pull(context, context.packageName, slot)
        }

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            writeLines(zip, "environment.txt", environment(context))
            if (includeDeviceReport) {
                // What the *device* is, which is the other half of every result in here.
                // "The guest could not bring Vulkan up" means one thing on a phone with a
                // working driver and nothing at all on one without.
                val packages = instances.mapNotNull { it["package"] }.toSet()
                writeLines(
                    zip, "device-report.txt",
                    DeviceReport.lines(DeviceReport.collect(context, packages)),
                )
                writeLines(zip, "test-checklist.txt", TestChecklist.lines(context))
            }
            writeLines(zip, "instances.txt", instanceSummary(instances))
            writeLines(zip, "unique.log", own)
            // The part of a failure UNIQUE's own events cannot see: ART, the linker,
            // ActivityManager's kill reasons, and the stack trace of an uncaught
            // exception. Filtered to framework tags so no app's own logging travels with
            // it — see LogcatCapture for exactly what that drops and why.
            writeLines(zip, "logcat.txt", LogcatCapture.capture())
            writeLines(zip, "crash.log", Diagnostics.exportLines(DiagChannel.CRASH) + remote)
            for ((slot, lines) in perSlot) {
                writeLines(zip, "vapp$slot.log", lines)
            }
            for ((name, lines) in nativeCrashes(instances)) {
                writeLines(zip, "native-crash-$name.txt", lines)
            }
            writeLines(zip, "README.txt", readme())
        }

        val lines = own.size + remote.size + perSlot.values.sumOf { it.size }
        Diagnostics.info(
            DiagChannel.STORAGE, "DIAGNOSTICS_EXPORTED",
            mapOf(
                "file" to zipFile.name,
                "bytes" to zipFile.length().toString(),
                "processes" to perSlot.count { it.value.isNotEmpty() }.toString(),
                "lines" to lines.toString(),
            ),
        )
        prune(dir)
        return Result(
            file = zipFile,
            bytes = zipFile.length(),
            processes = perSlot.count { it.value.isNotEmpty() },
            lines = lines,
        )
    }

    /**
     * Native crash records left behind by processes that are no longer alive.
     *
     * A SIGSEGV in a guest `.so` kills the process before anything can be asked of it, so
     * this is not pulled from anywhere — it is read off disk, where the signal handler
     * wrote it with a single `write(2)` on its way out (§14.3). Empty on a healthy
     * instance, which is why an empty entry is not written for one.
     */
    private fun nativeCrashes(instances: List<Map<String, String>>): Map<String, List<String>> =
        buildMap {
            for (row in instances) {
                val vuid = row["vuid"]?.toIntOrNull() ?: continue
                val packageName = row["package"] ?: continue
                val dir = File(UniqueEngine.storage.model.diagnosticsDir(vuid, packageName))
                val records = dir.listFiles { f ->
                    f.isFile && f.name.startsWith(AppBootstrap.NATIVE_CRASH_PREFIX) &&
                        f.length() > 0L
                }?.sortedByDescending { it.lastModified() }.orEmpty()
                for ((index, file) in records.withIndex()) {
                    val lines = runCatching { file.readLines() }.getOrNull() ?: continue
                    val suffix = if (index == 0) "" else "-$index"
                    put("u$vuid-$packageName$suffix", lines + "recordedFile=${file.absolutePath}")
                }
            }
        }

    /** Keeps the newest few. A cache directory is not a place to accumulate. */
    private fun prune(dir: File, keep: Int = 5) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    private fun environment(context: Context): List<String> = buildList {
        add("exportedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(System.currentTimeMillis())}")
        add("uniqueVersion=" + runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull())
        add("device=${Build.MANUFACTURER} ${Build.MODEL}")
        add("fingerprint=${Build.FINGERPRINT}")
        add("sdkInt=${Build.VERSION.SDK_INT}")
        add("release=${Build.VERSION.RELEASE}")
        add("securityPatch=${Build.VERSION.SECURITY_PATCH}")
        add("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        add("is64BitOnly=${Build.SUPPORTED_32_BIT_ABIS.isEmpty()}")
        add("pageSizeBytes=${UniqueNative.pageSize()}")
        add("nativeLoaded=${UniqueNative.isLoaded}")
        add("nativeLoadError=${UniqueNative.loadFailure ?: "-"}")
        add("hiddenApiGranted=${HiddenApi.isGranted}")
        add("hiddenApiDetail=${HiddenApi.failureDetail ?: "-"}")
    }

    private fun instanceSummary(instances: List<Map<String, String>>): List<String> =
        if (instances.isEmpty()) listOf("no instances") else instances.map { row ->
            row.entries.joinToString(" ") { "${it.key}=${it.value}" }
        }

    private fun readme(): List<String> = listOf(
        "UNIQUE diagnostics package",
        "",
        "environment.txt  the device and this build of UNIQUE",
        "instances.txt    which apps are imported, and the identity UNIQUE gave each",
        "device-report.txt  what this device is: ABIs, page size, Vulkan, WebView, Google",
        "test-checklist.txt the physical-device sequence, and what the tester observed",
        "unique.log       UNIQUE's own structured event log",
        "logcat.txt       the system log for UNIQUE's own uid: ART, the linker,",
        "                 ActivityManager kill reasons, uncaught exceptions. Framework",
        "                 tags only — no app-authored logging is included",
        "crash.log        crash records, including ones pushed here by processes that died",
        "vappN.log        the event log of virtual process slot N, pulled while it was alive",
        "",
        "This package contains no data from inside a virtualized app: no databases, no",
        "shared preferences, no cookies and no tokens. Log lines are passed through",
        "UNIQUE's redactor before being written, because an app may log its own secrets.",
    )

    private fun writeLines(zip: ZipOutputStream, name: String, lines: List<String>) {
        zip.putNextEntry(ZipEntry(name))
        val w = OutputStreamWriter(zip, Charsets.UTF_8)
        if (lines.isEmpty()) w.write("(nothing recorded)\n") else lines.forEach { w.write(it + "\n") }
        w.flush()
        zip.closeEntry()
    }
}
