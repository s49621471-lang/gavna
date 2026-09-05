package com.unique.app.runtime

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.unique.core.common.diag.DiagChannel
import com.unique.core.diagnostics.Diagnostics
import java.io.File

/**
 * Answers "can this device render a page in a WebView at all", in a process of its own.
 *
 * The acceptance suite needs this to tell a UNIQUE failure from a device failure: if a
 * WebView cannot render *outside* virtualization, recording that against UNIQUE would be
 * the one thing a compatibility matrix must never do.
 *
 * It has its own process because the check can be fatal to whoever runs it. When a
 * Chromium renderer dies, the embedding process goes with it:
 *
 * ```
 * FATAL:crashpad_client_linux.cc(732)] Render process's crash wasn't handled by all
 *     associated webviews, triggering application crash
 * ```
 *
 * On the verification emulator that happens reliably, and doing the check in the
 * instrumentation process took the whole run down with it — twice, and each time the
 * failure landed on whichever test happened to be running. Here, the same crash kills only
 * this process, the result file never appears, and "no result" *is* the answer.
 *
 * Not part of the product. It exists so a test can ask the device a question safely.
 */
class WebViewProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_RESULT_PATH)
        if (path == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val timeout = intent.getLongExtra(EXTRA_TIMEOUT_MILLIS, 60_000L)
        run(File(path), timeout) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    /**
     * Loads a page that needs no network and reports whether its JavaScript ran.
     *
     * Asynchronous, like the guest-side probe and for the same reason: the callbacks land
     * on this thread, so blocking it to wait for them cannot work.
     */
    private fun run(result: File, timeoutMillis: Long, done: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var web: WebView? = null

        fun finish(rendered: Boolean, detail: String) {
            if (finished) return
            finished = true
            runCatching {
                web?.stopLoading()
                web?.destroy()
            }
            runCatching {
                result.parentFile?.mkdirs()
                result.writeText("rendered=$rendered\ndetail=$detail\n")
            }
            Diagnostics.info(
                DiagChannel.WEBVIEW, "WEBVIEW_HOST_PROBE",
                mapOf("rendered" to rendered.toString(), "detail" to detail),
            )
            done()
        }

        runCatching {
            val view = WebView(this)
            web = view
            view.settings.javaScriptEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    finish(view.title == "ready:2", "title=${view.title}")
                }

                override fun onReceivedError(
                    view: WebView, errorCode: Int, description: String?, failingUrl: String?,
                ) = finish(false, "error=$errorCode $description")
            }
            view.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
        }.onFailure { finish(false, "create=$it") }

        handler.postDelayed({ finish(false, "timeout") }, timeoutMillis)
    }

    companion object {
        const val EXTRA_RESULT_PATH = "unique.webview.result"
        const val EXTRA_TIMEOUT_MILLIS = "unique.webview.timeout"

        private const val PAGE =
            "<html><head><title>loading</title></head><body>" +
                "<script>document.title='ready:'+(1+1);</script></body></html>"
    }
}
