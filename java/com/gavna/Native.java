package com.gavna;

import android.util.Log;

/** JNI bridge to libgavna.so. Every entry point is failure tolerant on purpose. */
public final class Native {

    // Feature ids - must match the Feature enum in gavna_engine.cpp
    public static final int FEATURE_UNLOCK_SKINS = 0;
    public static final int FEATURE_UNLOCK_ACCESSORIES = 1;
    public static final int FEATURE_IMMORTAL = 2;
    public static final int FEATURE_LENGTH = 3;
    public static final int FEATURE_COUNT = 4;

    // Value ids - must match the Value enum in gavna_engine.cpp
    public static final int VALUE_LENGTH = 0;

    private static final String TAG = "gavna";

    private static boolean sLoaded;

    private Native() {
    }

    public static synchronized boolean load() {
        if (sLoaded) {
            return true;
        }
        try {
            System.loadLibrary("gavna");
            sLoaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary failed", t);
        }
        return sLoaded;
    }

    public static void init() {
        if (!load()) {
            return;
        }
        try {
            nativeInit();
        } catch (Throwable t) {
            Log.e(TAG, "nativeInit failed", t);
        }
    }

    public static boolean setFeature(int feature, boolean on) {
        if (!sLoaded) {
            return false;
        }
        try {
            return nativeSetFeature(feature, on);
        } catch (Throwable t) {
            Log.e(TAG, "setFeature failed", t);
            return false;
        }
    }

    public static boolean setValue(int id, int value) {
        if (!sLoaded) {
            return false;
        }
        try {
            return nativeSetValue(id, value);
        } catch (Throwable t) {
            Log.e(TAG, "setValue failed", t);
            return false;
        }
    }

    /** Tells the engine the game window is up; used as a readiness fallback. */
    public static void onPlayerResumed() {
        if (!sLoaded) {
            return;
        }
        try {
            nativeOnPlayerResumed();
        } catch (Throwable t) {
            Log.e(TAG, "onPlayerResumed failed", t);
        }
    }

    private static native void nativeInit();

    private static native boolean nativeSetFeature(int feature, boolean on);

    private static native boolean nativeSetValue(int id, int value);

    private static native void nativeOnPlayerResumed();
}
