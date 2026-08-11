package com.skullcapstudios.esp;

import com.esp.EspOverlay;
import com.esp.Native;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.DisplayMetrics;
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
        Native.load();
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

    void detach(Activity a) {
        if (overlay != null && overlay.getContext() == a) overlay = null;
    }

    void attach(Activity a) {
        if (overlay != null && overlay.isAttachedToWindow()) return;
        if (!a.getClass().getName().contains("unity3d")) return;

        try {
            DisplayMetrics dm = new DisplayMetrics();
            a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);

            overlay = new EspOverlay(a);
            a.addContentView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.bringToFront();

            Native.start(dm.widthPixels, dm.heightPixels);
            Log.i("bpesp", "overlay attached to " + a.getClass().getName());
        } catch (Throwable t) {
            Log.e("bpesp", "attach failed", t);
        }
    }
}
