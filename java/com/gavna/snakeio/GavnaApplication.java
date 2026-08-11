package com.gavna.snakeio;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.gavna.GavnaLog;
import com.gavna.Native;
import com.gavna.ui.Overlay;

/**
 * Injected entry point.
 *
 * Extends the game's own Application class so the original start-up path is
 * untouched - the manifest just points at this subclass instead. The fully
 * qualified name is deliberately the same length as
 * com.kooapps.unity.UnityApplication so the binary manifest can be patched by
 * an in-place string swap, without re-encoding any resource table.
 */
public class GavnaApplication extends com.kooapps.unity.UnityApplication {

    private final Overlay overlay = new Overlay();

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            GavnaLog.installExceptionHandler();
            String dir = GavnaLog.resolveDir(this);
            Native.init(dir);
            GavnaLog.i("gavna attached to " + getPackageName() + ", log dir " + dir);
            registerActivityLifecycleCallbacks(new Callbacks());
        } catch (Throwable t) {
            // A failure here must never stop the game from booting.
            GavnaLog.e("gavna init failed", t);
        }
    }

    private final class Callbacks implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (!isGameActivity(activity)) {
                return;
            }
            try {
                overlay.attach(activity);
            } catch (Throwable t) {
                GavnaLog.e("overlay attach failed", t);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (!isGameActivity(activity)) {
                return;
            }
            try {
                overlay.detach();
            } catch (Throwable t) {
                GavnaLog.e("overlay detach failed", t);
            }
        }

        private boolean isGameActivity(Activity activity) {
            if (activity == null) {
                return false;
            }
            // The Unity player activity is the only one worth decorating; ad and
            // web-view activities come and go on top of it.
            String name = activity.getClass().getName();
            return name.contains("UnityPlayerActivity") || name.contains("kooapps.unity");
        }
    }
}
