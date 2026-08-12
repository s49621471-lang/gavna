package com.esp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen transparent view that paints the snapshot published by the native
 * poller. Touches are consumed only on the handle bar or inside an open menu;
 * everything else falls through to the game.
 */
public class EspOverlay extends View implements Choreographer.FrameCallback {

    // ---- options -----------------------------------------------------------
    private static final int TAB_ESP = 0, TAB_VISUAL = 1, TAB_INFO = 2, TAB_MISC = 3;

    private static final class Opt {
        final String key, label;
        final int tab;
        boolean on;
        Opt(int tab, String key, String label, boolean on) {
            this.tab = tab; this.key = key; this.label = label; this.on = on;
        }
    }

    private static final class Slider {
        final String key, label;
        final float min, max;
        float value;
        Slider(String key, String label, float min, float max, float value) {
            this.key = key; this.label = label; this.min = min; this.max = max; this.value = value;
        }
        float frac() { return (value - min) / (max - min); }
        void setFrac(float f) {
            if (f < 0) f = 0;
            if (f > 1) f = 1;
            value = min + f * (max - min);
        }
    }

    private final Opt[] opts = {
            new Opt(TAB_ESP,    "master",   "ESP enabled",   true),
            new Opt(TAB_ESP,    "box",      "Box",           true),
            new Opt(TAB_ESP,    "corners",  "Corner box",    false),
            new Opt(TAB_ESP,    "skeleton", "Skeleton",      true),
            new Opt(TAB_ESP,    "dead",     "Show dead",     false),

            new Opt(TAB_VISUAL, "hpbar",    "Health bar",    true),
            new Opt(TAB_VISUAL, "armorbar", "Armor bar",     true),
            new Opt(TAB_VISUAL, "snap",     "Snap line",     false),
            new Opt(TAB_VISUAL, "look",     "Look direction",false),

            new Opt(TAB_INFO,   "name",     "Name",          true),
            new Opt(TAB_INFO,   "hptext",   "Health number", false),
            new Opt(TAB_INFO,   "dist",     "Distance",      true),
            new Opt(TAB_INFO,   "kd",       "Kills / deaths",false),

            new Opt(TAB_MISC,   "status",   "Debug status",  true),
    };

    private final Slider[] sliders = {
            new Slider("thick",  "Line width",   1f,   4f,   1.4f),
            new Slider("textsz", "Text size",    8f,   16f,  11f),
            new Slider("maxdist","Max distance", 10f,  300f, 300f),
    };

    private static final String[] TAB_NAMES = { "ESP", "Visuals", "Info", "Misc" };

    /** Bone pairs, indices into the 12 joints the native side projects. */
    private static final int[] BONES = {
            0, 1,   1, 2,   2, 3,
            1, 4,   4, 6,   1, 5,   5, 7,
            3, 8,   8, 10,  3, 9,   9, 11,
    };

    private boolean on(String key) {
        for (Opt o : opts) if (o.key.equals(key)) return o.on;
        return false;
    }

    private float slider(String key) {
        for (Slider s : sliders) if (s.key.equals(key)) return s.value;
        return 0;
    }

    // ---- paint -------------------------------------------------------------
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mtext  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect   = new RectF();

    private final float[]  buf   = new float[Native.MAX_ENT * Native.STRIDE];
    private final String[] names = new String[Native.MAX_ENT];

    private final SharedPreferences prefs;
    private final float d;

    /** Latches if a native call ever throws, so one failure cannot kill the game. */
    private boolean nativeDead;
    private int lastCount;

    // ---- handle / menu state -----------------------------------------------
    private final float barW, barH, barTouchH;
    private boolean menuOpen;
    private int tab;
    private int dragSlider = -1;

    // window geometry, recomputed on resize
    private float winW, winH, winL, winT;
    private float titleH, tabH, navW, rowH, pad;

    public EspOverlay(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("bpesp", Context.MODE_PRIVATE);

        barW      = 132 * d;
        barH      = 5 * d;
        barTouchH = 44 * d;

        for (Opt o : opts) o.on = prefs.getBoolean("o_" + o.key, o.on);
        for (Slider s : sliders) s.value = prefs.getFloat("s_" + s.key, s.value);
        tab = prefs.getInt("tab", 0);

        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setFakeBoldText(true);
        mtext.setFakeBoldText(true);
        shadow.setFakeBoldText(true);
        shadow.setColor(0xC0000000);

        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        invalidate();
        if (isAttachedToWindow()) Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (!nativeDead) {
            try { Native.viewport(w, h); } catch (Throwable ignored) { }
        }
        layoutMenu();
    }

    /** The bar is pinned to the bottom centre and is not draggable. */
    private float handleX() { return getWidth() * 0.5f; }
    private float handleY() { return getHeight() - 14 * d; }

    /** Sizes the window to the view, so it fits freeform and split screen too. */
    private void layoutMenu() {
        float vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        winW = Math.min(420 * d, vw - 24 * d);
        winH = Math.min(260 * d, vh - barTouchH - 24 * d);
        winL = (vw - winW) * 0.5f;
        winT = Math.max(12 * d, handleY() - barTouchH - winH);

        titleH = Math.max(20 * d, winH * 0.11f);
        tabH   = Math.max(20 * d, winH * 0.12f);
        navW   = winW * 0.24f;
        pad    = 8 * d;
        rowH   = Math.max(18 * d, (winH - titleH - tabH - pad * 3) / 6f);

        mtext.setTextSize(Math.min(11 * d, rowH * 0.42f));
    }

    // ---- drawing -----------------------------------------------------------
    @Override protected void onDraw(Canvas c) {
        text.setTextSize(slider("textsz") * d);
        shadow.setTextSize(slider("textsz") * d);

        int state = -1;
        if (!nativeDead) {
            try {
                state = Native.state();
                if (on("master") && state == 2) drawEntities(c);
                else lastCount = 0;
            } catch (Throwable t) {
                nativeDead = true;
                Log.e("bpesp", "native call failed, overlay going quiet", t);
            }
        }

        if (on("status")) drawStatus(c, state);
        drawHandle(c);
        if (menuOpen) drawMenu(c);
    }

    private void drawStatus(Canvas c, int state) {
        String s;
        if (nativeDead)      s = "native unavailable";
        else if (state == 0) s = "waiting for il2cpp";
        else if (state == 1) s = "scanning: " + Native.status();
        else if (state == 2) s = "live · " + lastCount + " ent";
        else if (state < 0)  s = "starting";
        else                 s = Native.status();
        label(c, "[esp] " + s, 10 * d, 18 * d, state == 2 ? 0xFF66FF99 : 0xFFFFCC44);
    }

    private void drawEntities(Canvas c) {
        int n = Native.fetch(buf, names);
        lastCount = n;
        boolean showDead = on("dead");
        float maxDist = slider("maxdist");
        float lw = slider("thick") * d;
        float vw = getWidth(), vh = getHeight();
        float cx = vw * 0.5f, cy = vh;

        for (int i = 0; i < n; i++) {
            int b = i * Native.STRIDE;
            boolean alive = buf[b + Native.F_ALIVE] > 0.5f;
            if (!alive && !showDead) continue;
            if (buf[b + Native.F_DIST] > maxDist) continue;

            float fx = buf[b + Native.F_FEET_X] * vw;
            float fy = buf[b + Native.F_FEET_Y] * vh;
            float hy = buf[b + Native.F_HEAD_Y] * vh;
            float bw = Math.abs(fy - hy) * buf[b + Native.F_BOX_W];
            if (bw < 2 * d) bw = 2 * d;

            float left = fx - bw * 0.5f, right = fx + bw * 0.5f;
            float top = Math.min(fy, hy), bottom = Math.max(fy, hy);
            if (right < 0 || left > vw || bottom < 0 || top > vh) continue;

            int hp = (int) buf[b + Native.F_HP];
            int col = alive ? hpColor(hp) : 0xFF808080;

            if (on("snap")) {
                stroke.setColor(0x66FFFFFF);
                stroke.setStrokeWidth(lw);
                c.drawLine(cx, cy, fx, bottom, stroke);
            }
            if (on("look")) {
                stroke.setColor(0xAA44CCFF);
                stroke.setStrokeWidth(lw);
                c.drawLine(fx, (top + bottom) * 0.5f,
                           buf[b + Native.F_DIR_X] * vw, buf[b + Native.F_DIR_Y] * vh, stroke);
            }
            if (on("skeleton")) drawSkeleton(c, b, vw, vh, col, lw);

            rect.set(left, top, right, bottom);
            if (on("corners")) drawCorners(c, rect, col, lw);
            else if (on("box")) {
                stroke.setColor(0x99000000);
                stroke.setStrokeWidth(lw + 1.2f * d);
                c.drawRect(rect, stroke);
                stroke.setColor(col);
                stroke.setStrokeWidth(lw);
                c.drawRect(rect, stroke);
            }

            float barX = left - 5 * d;
            if (on("hpbar")) {
                drawBar(c, barX, top, bottom, hp / 100f, col);
                barX -= 5 * d;
            }
            if (on("armorbar")) {
                int ar = (int) buf[b + Native.F_ARMOR];
                if (ar > 0) drawBar(c, barX, top, bottom, ar / 100f, 0xFF3399FF);
            }

            if (on("name")) {
                String nm = names[i] == null ? "?" : names[i];
                centered(c, nm, fx, top - 4 * d, alive ? 0xFFFFFFFF : 0xFF9E9E9E);
            }

            StringBuilder sb = new StringBuilder();
            if (on("hptext")) sb.append(hp).append("hp");
            if (on("dist")) {
                if (sb.length() > 0) sb.append(' ');
                sb.append((int) buf[b + Native.F_DIST]).append('m');
            }
            if (on("kd")) {
                if (sb.length() > 0) sb.append(' ');
                sb.append((int) buf[b + Native.F_KILLS]).append('/')
                  .append((int) buf[b + Native.F_DEATHS]);
            }
            if (sb.length() > 0) centered(c, sb.toString(), fx, bottom + 12 * d, 0xFFE0E0E0);
        }
    }

    private void drawSkeleton(Canvas c, int b, float vw, float vh, int col, float lw) {
        int j = b + Native.F_JOINTS;
        for (int pass = 0; pass < 2; pass++) {
            stroke.setColor(pass == 0 ? 0x99000000 : col);
            stroke.setStrokeWidth(pass == 0 ? lw + 1.2f * d : lw);
            for (int k = 0; k < BONES.length; k += 2) {
                int a = j + BONES[k] * 2, e = j + BONES[k + 1] * 2;
                c.drawLine(buf[a] * vw, buf[a + 1] * vh,
                           buf[e] * vw, buf[e + 1] * vh, stroke);
            }
        }
        float hx = buf[j] * vw, hyy = buf[j + 1] * vh;
        float hr = Math.abs(buf[j + 3] * vh - hyy) * 0.9f;
        if (hr > 1) {
            stroke.setColor(col);
            stroke.setStrokeWidth(lw);
            c.drawCircle(hx, hyy - hr * 0.3f, hr, stroke);
        }
    }

    private void drawCorners(Canvas c, RectF r, int col, float lw) {
        float len = Math.min(r.width(), r.height()) * 0.28f;
        for (int pass = 0; pass < 2; pass++) {
            stroke.setColor(pass == 0 ? 0x99000000 : col);
            stroke.setStrokeWidth(pass == 0 ? lw + 1.4f * d : lw);
            c.drawLine(r.left, r.top, r.left + len, r.top, stroke);
            c.drawLine(r.left, r.top, r.left, r.top + len, stroke);
            c.drawLine(r.right, r.top, r.right - len, r.top, stroke);
            c.drawLine(r.right, r.top, r.right, r.top + len, stroke);
            c.drawLine(r.left, r.bottom, r.left + len, r.bottom, stroke);
            c.drawLine(r.left, r.bottom, r.left, r.bottom - len, stroke);
            c.drawLine(r.right, r.bottom, r.right - len, r.bottom, stroke);
            c.drawLine(r.right, r.bottom, r.right, r.bottom - len, stroke);
        }
    }

    private void drawBar(Canvas c, float x, float top, float bottom, float frac, int col) {
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        float w = 3 * d;
        fill.setColor(0xAA000000);
        c.drawRect(x - w, top, x, bottom, fill);
        fill.setColor(col);
        c.drawRect(x - w, bottom - (bottom - top) * frac, x, bottom, fill);
    }

    private static int hpColor(int hp) {
        if (hp < 0) hp = 0;
        if (hp > 100) hp = 100;
        int r = (int) (255 * (1f - hp / 100f));
        int g = (int) (255 * (hp / 100f));
        return 0xFF000000 | (r << 16) | (g << 8) | 0x30;
    }

    private void centered(Canvas c, String s, float x, float y, int col) {
        label(c, s, x - text.measureText(s) * 0.5f, y, col);
    }

    private void label(Canvas c, String s, float x, float y, int col) {
        c.drawText(s, x + 1, y + 1, shadow);
        text.setColor(col);
        c.drawText(s, x, y, text);
    }

    private void menuText(Canvas c, String s, float x, float y, int col) {
        mtext.setColor(col);
        c.drawText(s, x, y, mtext);
    }

    // ---- handle ------------------------------------------------------------
    private void drawHandle(Canvas c) {
        float hx = handleX(), hy = handleY();
        fill.setColor(0x66000000);
        c.drawRoundRect(hx - barW * 0.5f - 1.5f * d, hy - barH * 0.5f - 1.5f * d,
                        hx + barW * 0.5f + 1.5f * d, hy + barH * 0.5f + 1.5f * d,
                        barH, barH, fill);
        fill.setColor(menuOpen ? 0xFF34C759 : 0xFFFFFFFF);
        c.drawRoundRect(hx - barW * 0.5f, hy - barH * 0.5f,
                        hx + barW * 0.5f, hy + barH * 0.5f,
                        barH * 0.5f, barH * 0.5f, fill);
    }

    private boolean hitHandle(float x, float y) {
        return Math.abs(x - handleX()) <= barW * 0.5f + 12 * d
            && Math.abs(y - handleY()) <= barTouchH * 0.5f;
    }

    // ---- menu --------------------------------------------------------------
    private float contentTop() { return winT + titleH + tabH; }
    private float contentLeft() { return winL + navW; }
    private float contentW() { return winW - navW; }

    private void drawMenu(Canvas c) {
        if (winW <= 0) layoutMenu();
        if (winW <= 0) return;

        // window
        rect.set(winL, winT, winL + winW, winT + winH);
        fill.setColor(0xF2101012);
        c.drawRoundRect(rect, 6 * d, 6 * d, fill);
        stroke.setColor(0xFF2A2A2E);
        stroke.setStrokeWidth(1 * d);
        c.drawRoundRect(rect, 6 * d, 6 * d, stroke);

        // title bar
        rect.set(winL, winT, winL + winW, winT + titleH);
        fill.setColor(0xFF17171A);
        c.drawRoundRect(rect, 6 * d, 6 * d, fill);
        fill.setColor(0xFF17171A);
        c.drawRect(winL, winT + titleH - 6 * d, winL + winW, winT + titleH, fill);
        menuText(c, "BPESP", winL + 10 * d, winT + titleH * 0.66f, 0xFF6E6E76);

        float cxx = winL + winW - 14 * d, cyy = winT + titleH * 0.5f, cs = 4.5f * d;
        stroke.setColor(0xFFB9B9C0);
        stroke.setStrokeWidth(1.6f * d);
        c.drawLine(cxx - cs, cyy - cs, cxx + cs, cyy + cs, stroke);
        c.drawLine(cxx + cs, cyy - cs, cxx - cs, cyy + cs, stroke);

        // tab strip
        float tw = (winW - 20 * d) / (TAB_NAMES.length + 1);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            float tx = winL + 10 * d + i * tw;
            rect.set(tx, winT + titleH + 4 * d, tx + tw - 4 * d, winT + titleH + tabH - 4 * d);
            fill.setColor(i == tab ? 0xFF2C2C32 : 0xFF1B1B1F);
            c.drawRoundRect(rect, 3 * d, 3 * d, fill);
            menuText(c, TAB_NAMES[i], tx + 8 * d, rect.centerY() + mtext.getTextSize() * 0.36f,
                     i == tab ? 0xFFE8E8EE : 0xFF6E6E76);
        }

        // left nav — tab list mirrored as a column, current one highlighted
        for (int i = 0; i < TAB_NAMES.length; i++) {
            float ry = contentTop() + pad + i * rowH;
            if (i == tab) {
                rect.set(winL + 6 * d, ry, winL + navW - 4 * d, ry + rowH - 3 * d);
                fill.setColor(0xFF232329);
                c.drawRoundRect(rect, 3 * d, 3 * d, fill);
            }
            fill.setColor(i == tab ? 0xFF34C759 : 0xFF3A3A42);
            c.drawCircle(winL + 15 * d, ry + rowH * 0.45f, 3.2f * d, fill);
            menuText(c, TAB_NAMES[i], winL + 24 * d, ry + rowH * 0.45f + mtext.getTextSize() * 0.36f,
                     i == tab ? 0xFFE8E8EE : 0xFF6E6E76);
        }

        // option rows for the active tab
        int shown = 0;
        for (Opt o : opts) {
            if (o.tab != tab) continue;
            float ry = contentTop() + pad + shown * rowH;
            if (ry + rowH > winT + winH - sliderAreaH()) break;

            float knob = Math.min(5.5f * d, rowH * 0.24f);
            float sw = 26 * d;
            float sx = contentLeft() + contentW() - sw - 12 * d;
            rect.set(sx, ry + rowH * 0.5f - knob * 1.3f, sx + sw, ry + rowH * 0.5f + knob * 1.3f);
            fill.setColor(o.on ? 0xFF34C759 : 0xFF3A3A42);
            c.drawRoundRect(rect, knob * 1.3f, knob * 1.3f, fill);
            fill.setColor(0xFFF0F0F4);
            c.drawCircle(o.on ? rect.right - knob * 1.15f : rect.left + knob * 1.15f,
                         rect.centerY(), knob, fill);

            menuText(c, o.label, contentLeft() + 6 * d,
                     ry + rowH * 0.5f + mtext.getTextSize() * 0.36f,
                     o.on ? 0xFFE8E8EE : 0xFF8A8A92);
            shown++;
        }

        // sliders along the bottom
        float sy0 = winT + winH - sliderAreaH() + pad;
        for (int i = 0; i < sliders.length; i++) {
            Slider s = sliders[i];
            float sy = sy0 + i * (sliderAreaH() - pad * 2) / sliders.length;
            float sl = contentLeft() + 6 * d, sr = winL + winW - 12 * d;

            menuText(c, s.label, winL + 10 * d, sy + mtext.getTextSize() * 0.36f, 0xFF6E6E76);
            stroke.setColor(0xFF33333A);
            stroke.setStrokeWidth(2.5f * d);
            c.drawLine(sl, sy, sr, sy, stroke);
            float px = sl + (sr - sl) * s.frac();
            stroke.setColor(0xFF34C759);
            c.drawLine(sl, sy, px, sy, stroke);
            fill.setColor(0xFFF0F0F4);
            c.drawCircle(px, sy, 4.5f * d, fill);
        }
    }

    private float sliderAreaH() { return sliders.length * 16 * d + pad * 2; }

    private int optionAt(float x, float y) {
        int shown = 0;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i].tab != tab) continue;
            float ry = contentTop() + pad + shown * rowH;
            if (ry + rowH > winT + winH - sliderAreaH()) break;
            if (x >= contentLeft() && x <= winL + winW && y >= ry && y < ry + rowH) return i;
            shown++;
        }
        return -1;
    }

    private int navAt(float x, float y) {
        if (x < winL || x > winL + navW) return -1;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            float ry = contentTop() + pad + i * rowH;
            if (y >= ry && y < ry + rowH) return i;
        }
        return -1;
    }

    private int tabAt(float x, float y) {
        if (y < winT + titleH || y > winT + titleH + tabH) return -1;
        float tw = (winW - 20 * d) / (TAB_NAMES.length + 1);
        int i = (int) ((x - winL - 10 * d) / tw);
        return (i >= 0 && i < TAB_NAMES.length) ? i : -1;
    }

    private int sliderAt(float x, float y) {
        float sy0 = winT + winH - sliderAreaH() + pad;
        for (int i = 0; i < sliders.length; i++) {
            float sy = sy0 + i * (sliderAreaH() - pad * 2) / sliders.length;
            if (Math.abs(y - sy) <= 12 * d && x >= winL && x <= winL + winW) return i;
        }
        return -1;
    }

    private void applySlider(int i, float x) {
        Slider s = sliders[i];
        float sl = contentLeft() + 6 * d, sr = winL + winW - 12 * d;
        s.setFrac((x - sl) / (sr - sl));
        prefs.edit().putFloat("s_" + s.key, s.value).apply();
    }

    // ---- input -------------------------------------------------------------
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (hitHandle(x, y)) { menuOpen = !menuOpen; return true; }
                if (!menuOpen) return false;

                boolean inWindow = x >= winL && x <= winL + winW
                                && y >= winT && y <= winT + winH;
                if (!inWindow) { menuOpen = false; return true; }

                // close button
                if (y <= winT + titleH) {
                    if (x >= winL + winW - 28 * d) menuOpen = false;
                    return true;
                }

                int t = tabAt(x, y);
                if (t < 0) t = navAt(x, y);
                if (t >= 0) {
                    tab = t;
                    prefs.edit().putInt("tab", tab).apply();
                    return true;
                }

                int sl = sliderAt(x, y);
                if (sl >= 0) { dragSlider = sl; applySlider(sl, x); return true; }

                int o = optionAt(x, y);
                if (o >= 0) {
                    opts[o].on = !opts[o].on;
                    prefs.edit().putBoolean("o_" + opts[o].key, opts[o].on).apply();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragSlider >= 0) { applySlider(dragSlider, x); return true; }
                return menuOpen;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean had = dragSlider >= 0;
                dragSlider = -1;
                return had || menuOpen;
            }
        }
        return false;
    }
}
