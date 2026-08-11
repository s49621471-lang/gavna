package com.gavna.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gavna.Native;

/**
 * The menu window: title bar, sidebar tabs, top tabs and a two pane content
 * area, laid out after the reference screenshot.
 */
public final class MenuView extends LinearLayout {

    public interface Host {
        void onCloseRequested();

        void onTitleTouch(View view, android.view.MotionEvent event);
    }

    private static final String[] SIDE_TABS = {"unlocks", "player"};
    private static final String[][] TOP_TABS = {
            {"skins", "accessories"},
            {"god", "snake"},
    };

    private static final String[] LENGTH_PRESETS = {"tiny (10)", "small (100)", "big (1000)",
            "huge (10000)", "max (50000)"};
    private static final int[] LENGTH_PRESET_VALUES = {10, 100, 1000, 10000, 50000};

    private final Host host;
    private final MenuState state;
    private LinearLayout sidebar;
    private LinearLayout topTabRow;
    private LinearLayout pageHolder;
    private int sideIndex;
    private int topIndex;

    public MenuView(Context context, MenuState state, Host host) {
        super(context);
        this.state = state;
        this.host = host;
        build();
    }

    private void build() {
        Context ctx = getContext();
        setOrientation(VERTICAL);
        setBackground(Theme.window(ctx));

        addView(buildTitleBar());

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(HORIZONTAL);
        body.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sidebar = new LinearLayout(ctx);
        sidebar.setOrientation(VERTICAL);
        sidebar.setBackgroundColor(Theme.SIDEBAR_BG);
        sidebar.setLayoutParams(new LayoutParams(Theme.dp(ctx, 68),
                ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(sidebar);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(VERTICAL);
        int pad = Theme.dp(ctx, 6);
        content.setPadding(pad, 0, pad, pad);
        content.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        topTabRow = new LinearLayout(ctx);
        topTabRow.setOrientation(HORIZONTAL);
        content.addView(topTabRow);
        content.addView(Widgets.separator(ctx));

        ScrollView scroller = new ScrollView(ctx);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        pageHolder = new LinearLayout(ctx);
        pageHolder.setOrientation(HORIZONTAL);
        scroller.addView(pageHolder, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroller);

        body.addView(content);
        addView(body);

        rebuildSidebar();
        rebuildTopTabs();
        rebuildPage();
    }

    private View buildTitleBar() {
        Context ctx = getContext();
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Theme.TITLE_BG);
        int hpad = Theme.dp(ctx, 8);
        int vpad = Theme.dp(ctx, 6);
        bar.setPadding(hpad, vpad, hpad, vpad);

        TextView title = Widgets.label(ctx, "gavna  |  snake.io", Theme.TITLE_TEXT, 11f);
        title.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        TextView close = Widgets.label(ctx, "x", Theme.TITLE_TEXT, 12f);
        close.setPadding(Theme.dp(ctx, 10), 0, Theme.dp(ctx, 2), 0);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (host != null) {
                    host.onCloseRequested();
                }
            }
        });
        bar.addView(close);

        bar.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (host != null) {
                    host.onTitleTouch(v, event);
                }
                return true;
            }
        });
        return bar;
    }

    private void rebuildSidebar() {
        sidebar.removeAllViews();
        for (int i = 0; i < SIDE_TABS.length; i++) {
            final int index = i;
            TextView tab = Widgets.tab(getContext(), SIDE_TABS[i], i == sideIndex, true);
            tab.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (sideIndex == index) {
                        return;
                    }
                    sideIndex = index;
                    topIndex = 0;
                    rebuildSidebar();
                    rebuildTopTabs();
                    rebuildPage();
                }
            });
            sidebar.addView(tab);
        }
    }

    private void rebuildTopTabs() {
        topTabRow.removeAllViews();
        String[] tabs = TOP_TABS[sideIndex];
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            TextView tab = Widgets.tab(getContext(), tabs[i], i == topIndex, false);
            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (topIndex == index) {
                        return;
                    }
                    topIndex = index;
                    rebuildTopTabs();
                    rebuildPage();
                }
            });
            topTabRow.addView(tab);
        }
    }

    private void rebuildPage() {
        Context ctx = getContext();
        pageHolder.removeAllViews();

        LinearLayout left = Widgets.panel(ctx);
        LinearLayout right = Widgets.panel(ctx);
        LayoutParams leftParams = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftParams.rightMargin = Theme.dp(ctx, 5);
        leftParams.topMargin = Theme.dp(ctx, 2);
        LayoutParams rightParams = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightParams.topMargin = Theme.dp(ctx, 2);
        left.setLayoutParams(leftParams);
        right.setLayoutParams(rightParams);

        if (sideIndex == 0) {
            buildUnlocksPage(left, right);
        } else {
            buildPlayerPage(left, right);
        }

        pageHolder.addView(left);
        pageHolder.addView(right);
    }

    // ------------------------------------------------------------------ pages

    private void buildUnlocksPage(LinearLayout left, LinearLayout right) {
        final Context ctx = getContext();
        if (topIndex == 0) {
            left.addView(Widgets.checkRow(ctx, "unlock all snakes", null,
                    state.isOn(Native.FEATURE_UNLOCK_SKINS), new Widgets.BoolListener() {
                        @Override
                        public void onChanged(boolean value) {
                            state.setFeature(Native.FEATURE_UNLOCK_SKINS, value);
                        }
                    }));
            right.addView(Widgets.label(ctx, "every skin reads", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "as owned in the", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "shop and locker", Theme.LABEL_DIM, 10f));
        } else {
            left.addView(Widgets.checkRow(ctx, "unlock accessories", null,
                    state.isOn(Native.FEATURE_UNLOCK_ACCESSORIES), new Widgets.BoolListener() {
                        @Override
                        public void onChanged(boolean value) {
                            state.setFeature(Native.FEATURE_UNLOCK_ACCESSORIES, value);
                        }
                    }));
            right.addView(Widgets.label(ctx, "hats, capes and", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "the rest of the", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "accessory slots", Theme.LABEL_DIM, 10f));
        }
    }

    private void buildPlayerPage(LinearLayout left, LinearLayout right) {
        final Context ctx = getContext();
        if (topIndex == 0) {
            left.addView(Widgets.checkRow(ctx, "immortality", null,
                    state.isOn(Native.FEATURE_IMMORTAL), new Widgets.BoolListener() {
                        @Override
                        public void onChanged(boolean value) {
                            state.setFeature(Native.FEATURE_IMMORTAL, value);
                        }
                    }));
            right.addView(Widgets.label(ctx, "player snake only", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "bots still die", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.separator(ctx));
            right.addView(Widgets.label(ctx, "collision, death", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "and respawn are", Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "all short circuited", Theme.LABEL_DIM, 10f));
        } else {
            left.addView(Widgets.checkRow(ctx, "hold length", null,
                    state.isOn(Native.FEATURE_LENGTH), new Widgets.BoolListener() {
                        @Override
                        public void onChanged(boolean value) {
                            state.setFeature(Native.FEATURE_LENGTH, value);
                        }
                    }));
            left.addView(Widgets.sliderRow(ctx, "length", MenuState.MIN_LENGTH,
                    MenuState.MAX_LENGTH, state.length(), new Widgets.IntListener() {
                        @Override
                        public void onChanged(int value) {
                            state.setLength(value);
                        }
                    }));
            right.addView(Widgets.dropdownRow(ctx, "preset", LENGTH_PRESETS, state.lengthPreset(),
                    new Widgets.IntListener() {
                        @Override
                        public void onChanged(int index) {
                            state.setLengthPreset(index);
                            state.setLength(LENGTH_PRESET_VALUES[index]);
                            rebuildPage();
                        }
                    }));
            right.addView(Widgets.label(ctx, "target " + state.length(), Theme.LABEL_DIM, 10f));
            right.addView(Widgets.label(ctx, "applied in game", Theme.LABEL_DIM, 10f));
        }
    }
}
