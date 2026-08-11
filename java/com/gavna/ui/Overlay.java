package com.gavna.ui;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import android.util.Log;

/**
 * Owns the two on-screen pieces: the white bar pinned to the bottom of the
 * screen, and the menu window it toggles. Both are panel windows tied to the
 * game activity's token, so no overlay permission is involved.
 */
public final class Overlay {

    private static final String TAG = "gavna";

    private static final int MENU_WIDTH_DP = 360;
    private static final int MENU_HEIGHT_DP = 250;

    private final MenuState state = new MenuState();

    private Activity activity;
    private WindowManager windowManager;
    private View barView;
    private MenuView menuView;
    private WindowManager.LayoutParams menuParams;
    private boolean barAdded;
    private boolean menuAdded;
    private boolean usingWindowManager = true;

    private float dragStartX;
    private float dragStartY;
    private int dragOriginX;
    private int dragOriginY;

    public void attach(Activity target) {
        if (target == null) {
            return;
        }
        if (activity == target && barAdded) {
            return;
        }
        detach();
        activity = target;
        windowManager = target.getWindowManager();

        final View decor = target.getWindow().getDecorView();
        if (decor.getWindowToken() == null) {
            // The activity window is not attached yet; retry once it is.
            decor.post(new Runnable() {
                @Override
                public void run() {
                    addBar();
                }
            });
        } else {
            addBar();
        }
    }

    public void detach() {
        hideMenu();
        removeBar();
        activity = null;
        windowManager = null;
    }

    // ------------------------------------------------------------------- bar

    private void addBar() {
        if (activity == null || barAdded) {
            return;
        }
        try {
            barView = buildBar();
            WindowManager.LayoutParams params = baseParams();
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.y = Theme.dp(activity, 2);
            windowManager.addView(barView, params);
            barAdded = true;
            usingWindowManager = true;
            Log.i(TAG, "overlay bar attached (panel window)");
        } catch (Throwable t) {
            Log.e(TAG, "panel window refused, falling back to decor view", t);
            addBarToDecor();
        }
    }

    private void addBarToDecor() {
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            barView = buildBar();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.bottomMargin = Theme.dp(activity, 2);
            decor.addView(barView, params);
            barAdded = true;
            usingWindowManager = false;
            Log.i(TAG, "overlay bar attached (decor view)");
        } catch (Throwable t) {
            Log.e(TAG, "cannot attach overlay bar at all", t);
            barView = null;
            barAdded = false;
        }
    }

    private void removeBar() {
        if (!barAdded || barView == null) {
            barView = null;
            barAdded = false;
            return;
        }
        try {
            if (usingWindowManager && windowManager != null) {
                windowManager.removeViewImmediate(barView);
            } else if (barView.getParent() instanceof ViewGroup) {
                ((ViewGroup) barView.getParent()).removeView(barView);
            }
        } catch (Throwable t) {
            Log.e(TAG, "removeBar failed", t);
        }
        barView = null;
        barAdded = false;
    }

    private View buildBar() {
        FrameLayout wrapper = new FrameLayout(activity);
        int hpad = Theme.dp(activity, 16);
        int vpad = Theme.dp(activity, 10);
        wrapper.setPadding(hpad, vpad, hpad, vpad);

        View pill = new View(activity);
        pill.setBackground(Theme.rect(Theme.BAR, Theme.BAR, 0, Theme.dp(activity, 3)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Theme.dp(activity, 120), Theme.dp(activity, 5));
        params.gravity = Gravity.CENTER;
        wrapper.addView(pill, params);

        wrapper.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMenu();
            }
        });
        return wrapper;
    }

    // ------------------------------------------------------------------ menu

    public void toggleMenu() {
        if (menuAdded) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        if (activity == null || menuAdded) {
            return;
        }
        try {
            menuView = new MenuView(activity, state, new MenuView.Host() {
                @Override
                public void onCloseRequested() {
                    hideMenu();
                }

                @Override
                public void onTitleTouch(View view, MotionEvent event) {
                    handleDrag(event);
                }
            });

            int width = Math.min(Theme.dp(activity, MENU_WIDTH_DP), screenWidth() - Theme.dp(
                    activity, 16));
            int height = Math.min(Theme.dp(activity, MENU_HEIGHT_DP), screenHeight() - Theme.dp(
                    activity, 16));

            if (usingWindowManager && windowManager != null) {
                menuParams = baseParams();
                menuParams.width = width;
                menuParams.height = height;
                menuParams.gravity = Gravity.TOP | Gravity.START;
                menuParams.x = Math.max(0, (screenWidth() - width) / 2);
                menuParams.y = Math.max(0, (screenHeight() - height) / 2);
                windowManager.addView(menuView, menuParams);
            } else {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
                params.gravity = Gravity.CENTER;
                decor.addView(menuView, params);
            }
            menuAdded = true;
            Log.i(TAG, "menu opened");
        } catch (Throwable t) {
            Log.e(TAG, "cannot open menu", t);
            menuView = null;
            menuAdded = false;
        }
    }

    private void hideMenu() {
        if (!menuAdded || menuView == null) {
            menuView = null;
            menuAdded = false;
            return;
        }
        try {
            if (usingWindowManager && windowManager != null) {
                windowManager.removeViewImmediate(menuView);
            } else if (menuView.getParent() instanceof ViewGroup) {
                ((ViewGroup) menuView.getParent()).removeView(menuView);
            }
        } catch (Throwable t) {
            Log.e(TAG, "hideMenu failed", t);
        }
        menuView = null;
        menuAdded = false;
        Log.i(TAG, "menu closed");
    }

    private void handleDrag(MotionEvent event) {
        if (!usingWindowManager || menuParams == null || windowManager == null
                || menuView == null) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = event.getRawX();
                dragStartY = event.getRawY();
                dragOriginX = menuParams.x;
                dragOriginY = menuParams.y;
                break;
            case MotionEvent.ACTION_MOVE:
                menuParams.x = dragOriginX + Math.round(event.getRawX() - dragStartX);
                menuParams.y = dragOriginY + Math.round(event.getRawY() - dragStartY);
                try {
                    windowManager.updateViewLayout(menuView, menuParams);
                } catch (Throwable t) {
                    Log.e(TAG, "drag update failed", t);
                }
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- helpers

    private WindowManager.LayoutParams baseParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
        params.token = activity.getWindow().getDecorView().getWindowToken();
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        return params;
    }

    private int screenWidth() {
        return activity.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return activity.getResources().getDisplayMetrics().heightPixels;
    }
}
