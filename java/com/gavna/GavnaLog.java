package com.gavna;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Java side of the log file. Everything ends up in
 * Android/data/com.amelosinteractive.snake/files/gavna/gavna.log next to the
 * native log lines.
 */
public final class GavnaLog {

    private static final String TAG = "gavna";
    private static File sDir;

    private GavnaLog() {
    }

    /** Resolves (and creates) the log directory inside the game's own folder. */
    public static String resolveDir(Context context) {
        File base = null;
        try {
            base = context.getExternalFilesDir(null);
        } catch (Throwable t) {
            Log.w(TAG, "getExternalFilesDir failed", t);
        }
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "gavna");
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                dir = base;
            }
        } catch (Throwable t) {
            dir = base;
        }
        sDir = dir;
        return dir.getAbsolutePath();
    }

    public static String dir() {
        return sDir != null ? sDir.getAbsolutePath() : "<unset>";
    }

    public static void i(String message) {
        Log.i(TAG, message);
        Native.log(message);
    }

    public static void e(String message, Throwable t) {
        Log.e(TAG, message, t);
        Native.log(message + "\n" + stackTrace(t));
    }

    public static String stackTrace(Throwable t) {
        if (t == null) {
            return "<no throwable>";
        }
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** Records uncaught Java exceptions, then hands the crash back to the game. */
    public static void installExceptionHandler() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    Native.log("uncaught exception on thread " + thread.getName() + "\n"
                            + stackTrace(throwable));
                } catch (Throwable ignored) {
                    // nothing useful left to do
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }
}
