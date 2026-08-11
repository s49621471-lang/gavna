package com.gavna.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Control factory: every widget is built in code so no APK resources are touched. */
public final class Widgets {

    public interface BoolListener {
        void onChanged(boolean value);
    }

    public interface IntListener {
        void onChanged(int value);
    }

    public interface ClickListener {
        void onClick();
    }

    private Widgets() {
    }

    public static TextView label(Context ctx, String text, int color, float sizeSp) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        return tv;
    }

    /** Vertical container with a border, matching the two panes in the reference menu. */
    public static LinearLayout panel(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.panel(ctx));
        int pad = Theme.dp(ctx, 8);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    /** "[ ] label ................ [right]" */
    public static View checkRow(final Context ctx, String text, String rightText, boolean checked,
                                final BoolListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = Theme.dp(ctx, 4);
        row.setPadding(0, vpad, 0, vpad);

        final View box = new View(ctx);
        int size = Theme.dp(ctx, 9);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(size, size);
        boxParams.rightMargin = Theme.dp(ctx, 7);
        box.setLayoutParams(boxParams);
        box.setBackground(checkBoxDrawable(ctx, checked));
        row.addView(box);

        TextView tv = label(ctx, text, Theme.LABEL, 11f);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(textParams);
        row.addView(tv);

        if (rightText != null) {
            row.addView(label(ctx, rightText, Theme.LABEL_DIM, 10f));
        }

        final boolean[] state = {checked};
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                box.setBackground(checkBoxDrawable(ctx, state[0]));
                if (listener != null) {
                    listener.onChanged(state[0]);
                }
            }
        });
        return row;
    }

    private static Drawable checkBoxDrawable(Context ctx, boolean checked) {
        return Theme.rect(checked ? Theme.ACCENT : Theme.CONTROL_BG,
                checked ? Theme.ACCENT_LIGHT : Theme.CONTROL_BORDER,
                Math.max(1, Theme.dp(ctx, 1)), Theme.dp(ctx, 1));
    }

    /** Label, slider, then the live value underneath - same stacking as the reference. */
    public static View sliderRow(final Context ctx, String text, final int min, final int max,
                                 int value, final IntListener listener) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int vpad = Theme.dp(ctx, 4);
        column.setPadding(0, vpad, 0, vpad);

        column.addView(label(ctx, text, Theme.LABEL, 11f));

        SeekBar bar = new SeekBar(ctx);
        bar.setProgressDrawable(sliderTrack(ctx));
        bar.setThumb(sliderThumb(ctx));
        bar.setThumbOffset(0);
        bar.setPadding(0, Theme.dp(ctx, 4), 0, Theme.dp(ctx, 2));
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(clamp(value, min, max) - min);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(ctx, 14));
        barParams.topMargin = Theme.dp(ctx, 2);
        bar.setLayoutParams(barParams);
        column.addView(bar);

        final TextView valueText = label(ctx, String.valueOf(clamp(value, min, max)),
                Theme.LABEL_DIM, 10f);
        column.addView(valueText);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = min + progress;
                valueText.setText(String.valueOf(actual));
                if (fromUser && listener != null) {
                    listener.onChanged(actual);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return column;
    }

    private static Drawable sliderTrack(Context ctx) {
        GradientDrawable track = Theme.rect(Theme.CONTROL_BG, Theme.CONTROL_BORDER,
                Math.max(1, Theme.dp(ctx, 1)), Theme.dp(ctx, 1));
        GradientDrawable fill = Theme.rect(Theme.ACCENT, Theme.ACCENT, 0, Theme.dp(ctx, 1));
        ClipDrawable clipped = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clipped});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private static Drawable sliderThumb(Context ctx) {
        GradientDrawable thumb = Theme.rect(Theme.ACCENT_LIGHT, Theme.ACCENT_LIGHT, 0,
                Theme.dp(ctx, 1));
        int w = Theme.dp(ctx, 7);
        int h = Theme.dp(ctx, 12);
        thumb.setSize(w, h);
        thumb.setBounds(0, 0, w, h);
        return thumb;
    }

    /** Dropdown look-alike; tapping advances to the next entry. */
    public static View dropdownRow(final Context ctx, String text, final String[] options,
                                   int selected, final IntListener listener) {
        if (options == null || options.length == 0) {
            return label(ctx, text, Theme.LABEL_DIM, 11f);
        }
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        int vpad = Theme.dp(ctx, 4);
        column.setPadding(0, vpad, 0, vpad);
        column.addView(label(ctx, text, Theme.LABEL, 11f));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Theme.control(ctx));
        int hpad = Theme.dp(ctx, 6);
        int bpad = Theme.dp(ctx, 4);
        box.setPadding(hpad, bpad, hpad, bpad);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.topMargin = Theme.dp(ctx, 3);
        box.setLayoutParams(boxParams);

        final int start = clamp(selected, 0, options.length - 1);
        final TextView current = label(ctx, options[start], Theme.LABEL_DIM, 10f);
        current.setLayoutParams(
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(current);
        box.addView(label(ctx, "▼", Theme.LABEL_DIM, 8f));

        final int[] index = {start};
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                index[0] = (index[0] + 1) % options.length;
                current.setText(options[index[0]]);
                if (listener != null) {
                    listener.onChanged(index[0]);
                }
            }
        });

        column.addView(box);
        return column;
    }

    public static View button(final Context ctx, String text, final ClickListener listener) {
        TextView tv = label(ctx, text, Theme.LABEL, 11f);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(Theme.control(ctx));
        int hpad = Theme.dp(ctx, 8);
        int vpad = Theme.dp(ctx, 6);
        tv.setPadding(hpad, vpad, hpad, vpad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Theme.dp(ctx, 6);
        tv.setLayoutParams(params);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onClick();
                }
            }
        });
        return tv;
    }

    public static TextView tab(Context ctx, String text, boolean active, boolean sidebar) {
        TextView tv = label(ctx, text, active ? Theme.TAB_TEXT_ACTIVE : Theme.TAB_TEXT, 11f);
        tv.setGravity(Gravity.CENTER);
        int hpad = Theme.dp(ctx, sidebar ? 6 : 10);
        int vpad = Theme.dp(ctx, 7);
        tv.setPadding(hpad, vpad, hpad, vpad);
        tv.setBackgroundColor(active ? Theme.TAB_ACTIVE_BG : Color.TRANSPARENT);
        return tv;
    }

    public static View spacer(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Theme.dp(ctx, heightDp)));
        return v;
    }

    public static View separator(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Theme.dp(ctx, 1)));
        params.topMargin = Theme.dp(ctx, 5);
        params.bottomMargin = Theme.dp(ctx, 5);
        v.setLayoutParams(params);
        v.setBackgroundColor(Theme.PANEL_BORDER);
        return v;
    }

    public static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
