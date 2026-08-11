package com.esp;

public final class Native {
    /** floats per entity in the snapshot buffer */
    public static final int STRIDE = 14;
    public static final int MAX_ENT = 64;

    public static final int F_FEET_X = 0;
    public static final int F_FEET_Y = 1;
    public static final int F_HEAD_X = 2;
    public static final int F_HEAD_Y = 3;
    public static final int F_BOX_W  = 4;
    public static final int F_HP     = 5;
    public static final int F_ARMOR  = 6;
    public static final int F_DIST   = 7;
    public static final int F_KILLS  = 8;
    public static final int F_DEATHS = 9;
    public static final int F_SCORE  = 10;
    public static final int F_ALIVE  = 11;
    public static final int F_DIR_X  = 12;
    public static final int F_DIR_Y  = 13;

    private static boolean loaded;

    public static synchronized boolean load() {
        if (loaded) return true;
        try {
            System.loadLibrary("esp");
            loaded = true;
        } catch (Throwable t) {
            android.util.Log.e("bpesp", "loadLibrary failed", t);
        }
        return loaded;
    }

    /** Must be called before {@link #start} for the discovery trace to be captured. */
    public static native void setLog(String path);
    public static native void start(int screenW, int screenH);
    /** Surface size the game renders into — not the physical display. */
    public static native void viewport(int w, int h);
    public static native int state();          // 0 wait, 1 scanning, 2 live, 3 no list
    public static native String status();
    public static native int fetch(float[] data, String[] names);
}
