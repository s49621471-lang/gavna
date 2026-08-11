package com.skullcapstudios.esp;

import com.esp.EspOverlay;
import com.esp.Native;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;

/**
 * Replaces androidx.multidex.MultiDexApplication in the manifest and extends it,
 * so multidex still initialises exactly as before. The only addition is an
 * activity lifecycle hook that drops the overlay on top of the Unity surface.
 */
public class EspOverlayApp extends androidx.multidex.MultiDexApplication {

    private EspOverlay overlay;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Native.load()) {
            // Nothing to draw and every native entry point would throw on the
            // first frame. Leave the game completely alone.
            Log.e("bpesp", "libesp.so did not load, overlay disabled");
            return;
        }
        registerActivityLifecycleCallbacks(new Lifecycle(this));
    }

    /**
     * Static with an explicit back-reference. An inner (or anonymous) class that
     * touches outer state makes javac emit synthetic accessors that d8 8.2.2
     * refuses to translate.
     */
    static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        private final EspOverlayApp app;
        Lifecycle(EspOverlayApp app) { this.app = app; }

        @Override public void onActivityResumed(Activity a) { app.attach(a); }
        @Override public void onActivityCreated(Activity a, Bundle b) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityPaused(Activity a) { }
        @Override public void onActivityStopped(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        @Override public void onActivityDestroyed(Activity a) { app.detach(a); }
    }

    /**
     * /sdcard/Android/data/com.skullcapstudios.bps/files/bpesp.log — readable from
     * a file manager or Termux with no permission and no root. Falls back to the
     * private data dir if external storage is unavailable.
     */
    private static String logPath(Activity a) {
        java.io.File dir = a.getExternalFilesDir(null);
        if (dir == null) dir = a.getFilesDir();
        dir.mkdirs();
        String p = new java.io.File(dir, "bpesp.log").getAbsolutePath();
        Log.i("bpesp", "log -> " + p);
        return p;
    }

    void detach(Activity a) {
        if (overlay != null && overlay.getContext() == a) overlay = null;
    }

    void attach(Activity a) {
        if (overlay != null && overlay.isAttachedToWindow()) return;
        if (!a.getClass().getName().contains("unity3d")) return;

        try {
            overlay = new EspOverlay(a);
            a.addContentView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.bringToFront();

            Native.setLog(logPath(a));

            // Size comes from the view's own onSizeChanged, not from the display:
            // under MIUI freeform or split screen the window is a fraction of it.
            Native.start(0, 0);
            Log.i("bpesp", "overlay attached to " + a.getClass().getName());
        } catch (Throwable t) {
            Log.e("bpesp", "attach failed", t);
        }
    }
}
