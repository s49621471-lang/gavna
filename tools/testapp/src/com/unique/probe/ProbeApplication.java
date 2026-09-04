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
        Log.i(TAG, "Application.onCreate package=" + getPackageName()
                + " class=" + getClass().getName()
                + " filesDir=" + getFilesDir().getAbsolutePath());
    }
}
