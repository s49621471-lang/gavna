package com.unique.core.diagnostics

import android.util.Log
import com.unique.core.common.diag.DiagChannel
import com.unique.core.common.diag.DiagEvent
import com.unique.core.common.diag.DiagLevel
import com.unique.core.common.diag.DiagRedactor
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Process-local structured diagnostics.
 *
 * Every virtual app process keeps its own ring buffers and hands them to `:server` on
 * demand or on death. Ring buffers rather than unbounded lists because a misbehaving app
 * can emit thousands of events per second, and the diagnostics subsystem must never be
 * the reason a device runs out of memory.
 */
object Diagnostics {

    private const val TAG = "Unique"
    private const val CAPACITY_PER_CHANNEL = 512

    private val buffers = ConcurrentHashMap<DiagChannel, RingBuffer>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(DiagEvent) -> Unit>()

    @Volatile var verbose: Boolean = false

    /** Set once per process so events carry their origin without every call site repeating it. */
    @Volatile var vuid: Int? = null
    @Volatile var packageName: String? = null

    fun event(
        channel: DiagChannel,
        level: DiagLevel,
        code: String,
        fields: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        val e = DiagEvent(
            timestampMillis = System.currentTimeMillis(),
            channel = channel,
            level = level,
            code = code,
            vuid = vuid,
            packageName = packageName,
            fields = fields,
            throwable = throwable?.let { Log.getStackTraceString(it) },
        )
        buffers.getOrPut(channel) { RingBuffer(CAPACITY_PER_CHANNEL) }.add(e)
        listeners.forEach { runCatching { it(e) } }

        if (verbose || level >= DiagLevel.WARN) {
            val priority = when (level) {
                DiagLevel.DEBUG -> Log.DEBUG
                DiagLevel.INFO -> Log.INFO
                DiagLevel.WARN -> Log.WARN
                DiagLevel.ERROR -> Log.ERROR
            }
            Log.println(priority, TAG, format(e))
        }
    }

    fun info(channel: DiagChannel, code: String, fields: Map<String, String> = emptyMap()) =
        event(channel, DiagLevel.INFO, code, fields)

    fun warn(channel: DiagChannel, code: String, fields: Map<String, String> = emptyMap(), t: Throwable? = null) =
        event(channel, DiagLevel.WARN, code, fields, t)

    fun error(channel: DiagChannel, code: String, fields: Map<String, String> = emptyMap(), t: Throwable? = null) =
        event(channel, DiagLevel.ERROR, code, fields, t)

    fun snapshot(channel: DiagChannel? = null): List<DiagEvent> =
        if (channel != null) buffers[channel]?.toList().orEmpty()
        else buffers.values.flatMap { it.toList() }.sortedBy { it.timestampMillis }

    fun addListener(l: (DiagEvent) -> Unit) { listeners += l }
    fun removeListener(l: (DiagEvent) -> Unit) { listeners -= l }

    fun clear() = buffers.clear()

    /**
     * Writes the export package described in ARCHITECTURE.md section 14.
     *
     * Every event goes through [DiagRedactor] on the way out. The redactor has its own
     * unit tests because a diagnostics export that leaks an OAuth token is a security
     * bug, not a cosmetic one.
     */
    fun exportTo(zipFile: File, environment: Map<String, String>, appInfo: Map<String, String>) {
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            writeJson(zip, "diagnostics/app.json", appInfo.mapValues { DiagRedactor.redact(it.value) })
            writeJson(zip, "diagnostics/environment.json", environment.mapValues { DiagRedactor.redact(it.value) })
            writeLog(zip, "diagnostics/runtime.log", snapshot().filter { it.channel != DiagChannel.CRASH && it.channel != DiagChannel.GOOGLE })
            writeLog(zip, "diagnostics/crash.log", snapshot(DiagChannel.CRASH))
            writeLog(zip, "diagnostics/gms.log", snapshot(DiagChannel.GOOGLE))
        }
    }

    private fun writeLog(zip: ZipOutputStream, name: String, events: List<DiagEvent>) {
        zip.putNextEntry(ZipEntry(name))
        val w = OutputStreamWriter(zip, Charsets.UTF_8)
        events.forEach { w.write(format(DiagRedactor.redact(it)) + "\n") }
        w.flush()
        zip.closeEntry()
    }

    private fun writeJson(zip: ZipOutputStream, name: String, values: Map<String, String>) {
        zip.putNextEntry(ZipEntry(name))
        val w = OutputStreamWriter(zip, Charsets.UTF_8)
        w.write(values.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) ->
            "${quote(k)}: ${quote(v)}"
        })
        w.flush()
        zip.closeEntry()
    }

    private fun quote(s: String): String =
        buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

    private fun format(e: DiagEvent): String = buildString {
        append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(e.timestampMillis)))
        append(' ').append(e.level.name.first())
        append(' ').append(e.channel.name)
        e.vuid?.let { append(" u").append(it) }
        e.packageName?.let { append(' ').append(it) }
        append(' ').append(e.code)
        if (e.fields.isNotEmpty()) {
            append(' ').append(e.fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
        e.throwable?.let { append('\n').append(it) }
    }

    private class RingBuffer(private val capacity: Int) {
        private val items = arrayOfNulls<DiagEvent>(capacity)
        private var next = 0
        private var size = 0

        @Synchronized fun add(e: DiagEvent) {
            items[next] = e
            next = (next + 1) % capacity
            if (size < capacity) size++
        }

        @Synchronized fun toList(): List<DiagEvent> {
            val out = ArrayList<DiagEvent>(size)
            val start = if (size < capacity) 0 else next
            for (i in 0 until size) items[(start + i) % capacity]?.let(out::add)
            return out
        }
    }
}
