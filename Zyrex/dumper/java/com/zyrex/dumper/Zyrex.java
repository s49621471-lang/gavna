package com.zyrex.dumper;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;

/**
 * Zyrex bootstrap.
 *
 * Called from UnityPlayerActivity.onCreate. Every path here is wrapped so that
 * no failure in the dumper can take the game down with it — if anything at all
 * goes wrong we log it and return, and the game carries on unmodified.
 */
public final class Zyrex {

    private static final String TAG = "Zyrex";
    private static boolean started = false;

    private Zyrex() {}

    public static synchronized void init(Context context) {
        if (started) {
            Log.w(TAG, "init called twice, ignoring");
            return;
        }
        started = true;

        try {
            System.loadLibrary("zyrexdump");
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary failed", t);
            return;
        }

        final File outDir;
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null) base = context.getFilesDir();
            outDir = new File(base, "zyrex");
            if (!outDir.exists() && !outDir.mkdirs()) {
                Log.w(TAG, "could not create " + outDir.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.e(TAG, "output directory setup failed", t);
            return;
        }

        try {
            int rc = nativeStart(outDir.getAbsolutePath());
            Log.i(TAG, "nativeStart -> " + rc + "  dir=" + outDir.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "nativeStart failed", t);
            return;
        }

        // Hold the application context, never the Activity — the completion
        // watcher below outlives onCreate by minutes and would otherwise pin
        // the Activity for the whole dump.
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;

        toast(appContext, "Zyrex: dumping to " + outDir.getAbsolutePath());
        watchForCompletion(appContext, outDir);
    }

    /**
     * Polls the status marker the native side writes so the user gets told when
     * the dump has actually landed, rather than having to guess.
     */
    private static void watchForCompletion(final Context context, final File outDir) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final File status = new File(outDir, "_status.txt");
        final long deadline = System.currentTimeMillis() + (10 * 60 * 1000);

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                String state = read(status);
                if ("done".equals(state)) {
                    toast(context, "Zyrex: dump complete — " + outDir.getAbsolutePath());
                    return;
                }
                if (state != null && state.startsWith("failed")) {
                    toast(context, "Zyrex: " + state);
                    return;
                }
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 5000);
                }
            }
        }, 5000);
    }

    private static String read(File f) {
        try {
            if (!f.isFile()) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void toast(final Context context, final String message) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            } else {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        try {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private static native int nativeStart(String outDir);
}
