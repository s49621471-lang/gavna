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
        applicationOnCreateAt = System.nanoTime();
        Log.i(TAG, "Application.onCreate package=" + getPackageName()
                + " class=" + getClass().getName()
                + " filesDir=" + getFilesDir().getAbsolutePath());
    }
}
