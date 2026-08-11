package com.gavna.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** Colours and drawable factories for the menu chrome. */
public final class Theme {

    public static final int WINDOW_BG = Color.parseColor("#FF1B1B1B");
    public static final int WINDOW_BORDER = Color.parseColor("#FF090909");
    public static final int TITLE_BG = Color.parseColor("#FF262626");
    public static final int TITLE_TEXT = Color.parseColor("#FFD2D2D2");

    public static final int SIDEBAR_BG = Color.parseColor("#FF1E1E1E");
    public static final int TAB_TEXT = Color.parseColor("#FF8C8C8C");
    public static final int TAB_TEXT_ACTIVE = Color.parseColor("#FF5A9BFF");
    public static final int TAB_ACTIVE_BG = Color.parseColor("#FF2B2B2B");

    public static final int PANEL_BG = Color.parseColor("#FF222222");
    public static final int PANEL_BORDER = Color.parseColor("#FF343434");

    public static final int LABEL = Color.parseColor("#FFB4B4B4");
    public static final int LABEL_DIM = Color.parseColor("#FF6E6E6E");

    public static final int CONTROL_BG = Color.parseColor("#FF2A2A2A");
    public static final int CONTROL_BORDER = Color.parseColor("#FF3C3C3C");

    public static final int ACCENT = Color.parseColor("#FF2F6FE0");
    public static final int ACCENT_LIGHT = Color.parseColor("#FF6FA8FF");

    public static final int BAR = Color.parseColor("#F2FFFFFF");

    private Theme() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static float sp(Context context, float value) {
        return value;
    }

    public static GradientDrawable rect(int fill, int stroke, int strokeWidthPx, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, stroke);
        }
        if (radiusPx > 0) {
            d.setCornerRadius(radiusPx);
        }
        return d;
    }

    public static GradientDrawable panel(Context context) {
        return rect(PANEL_BG, PANEL_BORDER, Math.max(1, dp(context, 1)), dp(context, 2));
    }

    public static GradientDrawable window(Context context) {
        return rect(WINDOW_BG, WINDOW_BORDER, Math.max(1, dp(context, 1)), dp(context, 2));
    }

    public static GradientDrawable control(Context context) {
        return rect(CONTROL_BG, CONTROL_BORDER, Math.max(1, dp(context, 1)), dp(context, 2));
    }
}
