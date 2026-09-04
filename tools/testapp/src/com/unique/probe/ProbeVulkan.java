package com.unique.probe;

import android.util.Log;

/**
 * The Java side of the Vulkan probe.
 *
 * Kept separate from {@link ProbeNative} because the library it loads pulls in the
 * platform's Vulkan loader, and a device with no Vulkan must still be able to run every
 * other native test. Loading it on demand keeps that failure contained to this one probe.
 */
public final class ProbeVulkan {
    private static final String TAG = ProbeApplication.TAG;

    private ProbeVulkan() {}

    /** Newline-separated key=value lines, or a single `error=` line. */
    public static String report() {
        try {
            System.loadLibrary("probevulkan");
        } catch (Throwable t) {
            Log.e(TAG, "libprobevulkan did not load", t);
            return "ran=false\nerror=" + t + "\n";
        }
        try {
            return probe();
        } catch (Throwable t) {
            Log.e(TAG, "vulkan probe threw", t);
            return "ran=false\nerror=" + t + "\n";
        }
    }

    private static native String probe();
}
