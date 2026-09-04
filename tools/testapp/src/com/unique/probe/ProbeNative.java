package com.unique.probe;

/**
 * The probe's JNI surface.
 *
 * Loaded the ordinary way, from a library the system has never installed, in a process
 * whose package the system has never heard of.
 */
public final class ProbeNative {
    private ProbeNative() {}

    public static native String arch();
    public static native long pageSize();
    public static native int pid();
    public static native String echo(String input);
    public static native String writeThroughLibc(String path);
}
