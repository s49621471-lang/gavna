package com.gavna;

/** JNI bridge to libgavna.so. Every entry point is failure tolerant on purpose. */
public final class Native {

    // Feature ids - must match the Feature enum in gavna_engine.cpp
    public static final int FEATURE_COINS = 0;
    public static final int FEATURE_UNLOCK_SKINS = 1;
    public static final int FEATURE_UNLOCK_ACCESSORIES = 2;
    public static final int FEATURE_IMMORTAL = 3;
    public static final int FEATURE_LENGTH = 4;
    public static final int FEATURE_COUNT = 5;

    // Value ids - must match the Value enum in gavna_engine.cpp
    public static final int VALUE_COIN_AMOUNT = 0;
    public static final int VALUE_LENGTH = 1;

    private static boolean sLoaded;
    private static String sLoadError;

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
            sLoadError = String.valueOf(t.getMessage());
            android.util.Log.e("gavna", "loadLibrary failed", t);
        }
        return sLoaded;
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static String loadError() {
        return sLoadError;
    }

    public static void init(String logDir) {
        if (!load()) {
            return;
        }
        try {
            nativeInit(logDir);
        } catch (Throwable t) {
            android.util.Log.e("gavna", "nativeInit failed", t);
        }
    }

    public static boolean setFeature(int feature, boolean on) {
        if (!sLoaded) {
            return false;
        }
        try {
            return nativeSetFeature(feature, on);
        } catch (Throwable t) {
            android.util.Log.e("gavna", "setFeature failed", t);
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
            android.util.Log.e("gavna", "setValue failed", t);
            return false;
        }
    }

    public static String status() {
        if (!sLoaded) {
            return "engine: library not loaded\n" + sLoadError;
        }
        try {
            return nativeStatus();
        } catch (Throwable t) {
            return "engine: status unavailable";
        }
    }

    public static void log(String message) {
        if (!sLoaded) {
            android.util.Log.i("gavna", message);
            return;
        }
        try {
            nativeLog(message);
        } catch (Throwable ignored) {
            // logging must never take the game down
        }
    }

    private static native void nativeInit(String logDir);

    private static native boolean nativeSetFeature(int feature, boolean on);

    private static native boolean nativeSetValue(int id, int value);

    private static native String nativeStatus();

    private static native void nativeLog(String message);
}
