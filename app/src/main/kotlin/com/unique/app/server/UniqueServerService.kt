package com.unique.app.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import com.unique.core.vprocess.ProcessPool

/**
 * The VirtualCore server.
 *
 * Runs in `:server` and is the single writer for all virtual state: packages, instances,
 * device profiles, permissions. Everything else asks it. Keeping one writer is what makes
 * the state database safe without distributed locking between a dozen processes.
 */
class UniqueServerService : Service() {

    private lateinit var processPool: ProcessPool

    override fun onCreate() {
        super.onCreate()
        processPool = ProcessPool(capacity = VAPP_SLOTS)
        Diagnostics.info(
            DiagChannel.PROCESS, "SERVER_STARTED",
            mapOf("slots" to VAPP_SLOTS.toString()),
        )
    }

    // TODO(phase-2): expose the VirtualCore AIDL surface.
    // Until the interface exists, binding returns null rather than a half-built Binder,
    // so a caller fails immediately and visibly instead of on the first transaction.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Diagnostics.info(DiagChannel.PROCESS, "SERVER_STOPPED")
        super.onDestroy()
    }

    companion object {
        /** Must equal `vappProcessCount` in the root build script. */
        const val VAPP_SLOTS = 16
    }
}
