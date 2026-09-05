package com.unique.probe;

/**
 * The probe's JNI surface.
 *
 * Loaded the ordinary way, from a library the system has never installed, in a process
 * whose package the system has never heard of.
 */
public final class ProbeNative {

    /**
     * Loaded in a static initializer, which is where real apps load native code — and
     * therefore before UNIQUE installs its IO interception, which walks what is loaded at
     * that moment.
     */
    static {
        System.loadLibrary("probenative");
    }

    private ProbeNative() {}

    public static native String arch();

    /**
     * Crashes this process in native code, on purpose.
     *
     * Not reachable by accident: nothing calls it unless a launch asks for it. A Java
     * exception takes a completely different path — the JVM's uncaught-exception handler
     * rather than a POSIX signal — so this is the only way to exercise the native one.
     */
    public static native void crash();
    public static native long pageSize();
    public static native int pid();
    public static native String echo(String input);
    public static native String writeThroughLibc(String path);
}
