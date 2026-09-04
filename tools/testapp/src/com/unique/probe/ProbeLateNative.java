package com.unique.probe;

/**
 * A native library loaded from an Activity rather than from a static initializer.
 *
 * Deliberately not loaded eagerly: the point is to arrive after UNIQUE has already
 * installed its IO interception, which is the case the initial scan cannot cover.
 */
public final class ProbeLateNative {
    private ProbeLateNative() {}

    public static void load() {
        System.loadLibrary("probelate");
    }

    public static native String writeThroughLibc(String path);
}
