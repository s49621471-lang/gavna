package com.unique.probe;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Brings up a WebView and reports what it managed to do.
 *
 * WebView is the shape most likely to expose a virtualization layer, for reasons that have
 * nothing to do with rendering:
 *
 *  - It is a *separate APK* loaded into the app's process as a shared library, so the
 *    class loader and the native loader both have to cope with an app whose own package
 *    the system never installed.
 *  - It keeps its data in a directory derived from the app's data directory, which UNIQUE
 *    redirects. If the redirect is wrong, WebView writes into UNIQUE's own storage, or
 *    fails to write at all.
 *  - Since Android P it refuses to run in two processes against one data directory without
 *    a distinct suffix, and every `:vappN` is another process of *UNIQUE* as far as it can
 *    tell.
 *
 * Everything is reported rather than asserted here, including the failures: a device with
 * no WebView provider must produce a readable answer, not a timeout.
 *
 * Asynchronous on purpose. The page load delivers its callbacks to the main thread, so
 * blocking that thread to wait for them is not a slow way to do this — it is a way that
 * cannot work at all, and produces a timeout that looks like a broken WebView.
 */
public final class ProbeWebView {
    private static final String TAG = ProbeApplication.TAG;

    /** A page that needs no network and still exercises the renderer and JavaScript. */
    private static final String PAGE =
            "<html><head><title>loading</title></head><body><h1>unique</h1>"
                    + "<script>document.title='ready:'+(1+1);</script></body></html>";

    /**
     * Called on the main thread, twice: once as soon as the WebView exists, and once when
     * the load ends.
     *
     * Twice rather than once because Chromium kills the embedding process when its
     * renderer dies:
     *
     * ```
     * FATAL:crashpad_client_linux.cc(732)] Render process's crash wasn't handled by all
     *     associated webviews, triggering application crash
     * ```
     *
     * A probe that only reports at the end turns that into a timeout — an empty file and
     * no idea whether the WebView was ever created, which data directory it chose, or
     * which provider it used. Those are the facts about *virtualization*; whether
     * Chromium's renderer survives on a given device is a fact about the device.
     */
    public interface Done {
        void onResult(Map<String, String> result, boolean finished);
    }

    private ProbeWebView() {}

    public static void start(Activity activity, long timeoutMillis, final Done done) {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("sdkInt", String.valueOf(Build.VERSION.SDK_INT));
        try {
            // Reported before anything else: on a device with no WebView provider this is
            // the line that explains every other failure below it.
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
                out.put("provider", provider == null ? "none" : provider.packageName);
                out.put("providerVersion", provider == null ? "-" : provider.versionName);
            }
        } catch (Throwable t) {
            out.put("providerError", t.toString());
        }

        final boolean[] finished = {false};
        final WebView[] holder = new WebView[1];
        final Handler handler = new Handler(Looper.getMainLooper());

        final Runnable complete = new Runnable() {
            @Override
            public void run() {
                if (finished[0]) return;
                finished[0] = true;
                try {
                    if (holder[0] != null) {
                        out.put("title", String.valueOf(holder[0].getTitle()));
                        holder[0].destroy();
                        out.put("destroyed", "true");
                    }
                } catch (Throwable t) {
                    out.put("destroyError", t.toString());
                }
                done.onResult(out, true);
            }
        };

        try {
            long createdAt = android.os.SystemClock.uptimeMillis();
            WebView web = new WebView(activity);
            holder[0] = web;
            out.put("constructMillis",
                    String.valueOf(android.os.SystemClock.uptimeMillis() - createdAt));
            web.getSettings().setJavaScriptEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url,
                                          android.graphics.Bitmap favicon) {
                    // Distinguishes "the renderer never started" from "it started and
                    // never finished". A timeout without this line says only that
                    // something took too long, which is the least useful thing to know.
                    out.put("pageStarted", "true");
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    out.put("pageFinished", "true");
                    complete.run();
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description,
                                            String failingUrl) {
                    out.put("pageError", errorCode + ": " + description);
                    complete.run();
                }
            });
            web.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    out.put("progress", String.valueOf(newProgress));
                }
            });
            out.put("created", "true");
            out.put("userAgent", String.valueOf(web.getSettings().getUserAgentString()));
            out.put("databasePath", String.valueOf(activity.getDataDir().getAbsolutePath()));
            // Reported before the load starts, so it survives a renderer crash.
            done.onResult(out, false);
            web.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null);
        } catch (Throwable t) {
            // The interesting failure, and the one worth a full string: on Android P+ a
            // second process sharing one WebView data directory throws here, and the
            // message names the directory.
            out.put("created", "false");
            out.put("createError", t.toString());
            Log.e(TAG, "WebView could not be created", t);
            finished[0] = true;
            done.onResult(out, true);
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished[0]) return;
                out.put("timedOut", "true");
                complete.run();
            }
        }, timeoutMillis);
    }
}
