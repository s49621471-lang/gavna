package com.unique.core.vam

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics

/**
 * Gets a virtual process's diagnostics out of it.
 *
 * `Diagnostics` keeps its ring buffers per process, which is right — a misbehaving app
 * must not be able to fill UNIQUE's memory — but it means the events that matter most are
 * in the process the user cannot see, and the ones from a process that crashed are gone
 * altogether. An export assembled only from UNIQUE's own buffers would show the launch
 * request and never the launch.
 *
 * Two directions, because the two cases are genuinely different:
 *
 *  - **Pull**, on export. UNIQUE asks each live `:vappN` for its buffers through that
 *    slot's stub provider. Nothing is copied while the app is merely running, so the cost
 *    is paid once, by the person who pressed Export.
 *  - **Push**, on crash. A crashing process has seconds to live and no one will ask it
 *    anything afterwards, so it sends its crash records to UNIQUE's main process itself.
 *
 * Everything crossing either way has already been through `DiagRedactor`: see
 * [Diagnostics.exportLines].
 */
object VirtualDiagnostics {

    const val METHOD_SNAPSHOT = "unique.diagnosticsSnapshot"
    const val METHOD_PUBLISH = "unique.publishDiagnostics"

    const val KEY_LINES = "unique.lines"
    const val KEY_PROCESS = "unique.process"

    // ---------------------------------------------------------------------------------
    // In a virtual process
    // ---------------------------------------------------------------------------------

    /** Installs the crash push. Called once per virtual process, after the graft. */
    fun installRemoteSink(context: Context, hostPackage: String, processLabel: String) {
        val app = context.applicationContext ?: context
        Diagnostics.remoteSink = { lines -> publish(app, hostPackage, processLabel, lines) }
    }

    /** This process's answer to a pull. */
    fun snapshotBundle(processLabel: String): Bundle = Bundle().apply {
        putString(KEY_PROCESS, processLabel)
        putStringArray(KEY_LINES, Diagnostics.exportLines().toTypedArray())
    }

    private fun publish(
        context: Context,
        hostPackage: String,
        processLabel: String,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        runCatching {
            context.contentResolver.call(
                VirtualProviderRouter.routerUri(hostPackage),
                METHOD_PUBLISH,
                null,
                Bundle().apply {
                    putString(KEY_PROCESS, processLabel)
                    putStringArray(KEY_LINES, lines.toTypedArray())
                },
            )
        }
        // Deliberately no diagnostic on failure. This runs from an uncaught-exception
        // handler in a process that is about to die; the one thing worse than losing the
        // record would be throwing while trying to save it.
    }

    // ---------------------------------------------------------------------------------
    // In UNIQUE's own process
    // ---------------------------------------------------------------------------------

    /** Lines pushed here by other processes, newest last. Bounded, like the buffers are. */
    private val received = ArrayDeque<String>()

    private const val RECEIVED_CAPACITY = 2048

    @Synchronized
    fun absorb(extras: Bundle?): Bundle? {
        val lines = extras?.getStringArray(KEY_LINES) ?: return null
        val label = extras.getString(KEY_PROCESS) ?: "?"
        for (line in lines) {
            received.addLast("[$label] $line")
            if (received.size > RECEIVED_CAPACITY) received.removeFirst()
        }
        Diagnostics.info(
            DiagChannel.PROCESS, "DIAGNOSTICS_RECEIVED",
            mapOf("process" to label, "lines" to lines.size.toString()),
        )
        return Bundle.EMPTY
    }

    @Synchronized
    fun receivedLines(): List<String> = received.toList()

    @Synchronized
    fun clearReceived() = received.clear()

    /**
     * Pulls one live slot's buffers.
     *
     * Returns an empty list rather than throwing when the slot has no process: an export
     * must not fail because an app was closed while it was being assembled.
     */
    fun pull(context: Context, hostPackage: String, slot: Int): List<String> = runCatching {
        val stub = Uri.parse("content://" + VirtualProviderRouter.stubAuthority(hostPackage, slot))
        val reply = context.contentResolver.call(stub, METHOD_SNAPSHOT, null, null)
            ?: return emptyList()
        val label = reply.getString(KEY_PROCESS) ?: ":vapp$slot"
        reply.getStringArray(KEY_LINES).orEmpty().map { "[$label] $it" }
    }.getOrElse { emptyList() }
}
