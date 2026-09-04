package com.unique.probe;

import android.app.Application;
import android.util.Log;

/**
 * Records that Application.onCreate ran, and in what order relative to the Activity.
 *
 * Lifecycle order is part of what PHASE 2 has to prove: an Activity that starts before its
 * Application has been created is a broken app in ways that surface much later.
 */
public class ProbeApplication extends Application {

    public static final String TAG = "UniqueProbe";

    /** Set before any Activity can run, and read by the Activity to prove the ordering. */
    public static volatile long applicationOnCreateAt = 0L;

    /**
     * Startup timing, on the clock the platform uses for it.
     *
     * `SystemClock.uptimeMillis` rather than wall time: it does not move when the clock is
     * set, and it is the same base as `Process.getStartUptimeMillis`, so the difference
     * between the two is the honest answer to "how long from fork to the app's first
     * line". Comparing a wall-clock stamp against a process-start uptime would produce a
     * number that looks plausible and means nothing.
     */
    public static volatile long applicationOnCreateUptime = 0L;

    /** When the kernel forked this process, as the platform reports it. */
    public static volatile long processStartUptime = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        // Touching the native class here loads the library in Application.onCreate, which
        // is where an app that needs native code at startup does it - and is the timing
        // UNIQUE's IO interception is designed around.
        try {
            android.util.Log.i(TAG, "native arch=" + ProbeNative.arch());
        } catch (Throwable t) {
            android.util.Log.e(TAG, "native library unavailable", t);
        }
        applicationOnCreateAt = System.nanoTime();
        applicationOnCreateUptime = android.os.SystemClock.uptimeMillis();
        try {
            processStartUptime = android.os.Process.getStartUptimeMillis();
        } catch (Throwable t) {
            // API 24+. Recorded as zero rather than guessed; a fabricated start time
            // would silently turn every startup measurement into a different number.
            processStartUptime = 0L;
        }
        Log.i(TAG, "Application.onCreate package=" + getPackageName()
                + " class=" + getClass().getName()
                + " filesDir=" + getFilesDir().getAbsolutePath());
    }
}
