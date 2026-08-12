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

    private static final int TAB_VISUALS = 0, TAB_COMBAT = 1;
    private static final String[] TAB_NAMES = { "Visuals", "Combat" };

    // ---- options -----------------------------------------------------------
    private static final class Opt {
        final String key, label;
        final int tab;
        final String[] choices;   // null for a plain toggle
        boolean on;
        int choice;
        Opt(int tab, String key, String label, boolean on) {
            this(tab, key, label, on, null);
        }
        Opt(int tab, String key, String label, boolean on, String[] choices) {
            this.tab = tab; this.key = key; this.label = label;
            this.on = on; this.choices = choices;
        }
    }

    private static final class Slider {
        final int tab;
        final String key, label;
        final float min, max;
        final boolean integral;
        float value;
        Slider(int tab, String key, String label, float min, float max, float value,
               boolean integral) {
            this.tab = tab; this.key = key; this.label = label;
            this.min = min; this.max = max; this.value = value; this.integral = integral;
        }
        float frac() { return (value - min) / (max - min); }
        void setFrac(float f) {
            if (f < 0) f = 0;
            if (f > 1) f = 1;
            value = min + f * (max - min);
            if (integral) value = Math.round(value);
        }
        String text() {
            return integral ? String.valueOf((int) value) : String.format("%.1f", value);
        }
    }

    private final Opt[] opts = {
            new Opt(TAB_VISUALS, "master",   "ESP enabled",    true),
            new Opt(TAB_VISUALS, "box",      "Box",            true),
            new Opt(TAB_VISUALS, "corners",  "Corner box",     false),
            new Opt(TAB_VISUALS, "skeleton", "Skeleton",       true),
            new Opt(TAB_VISUALS, "hpbar",    "Health bar",     true),
            new Opt(TAB_VISUALS, "armorbar", "Armor bar",      true),
            new Opt(TAB_VISUALS, "name",     "Name",           true),
            new Opt(TAB_VISUALS, "hptext",   "Health number",  false),
            new Opt(TAB_VISUALS, "dist",     "Distance",       true),
            new Opt(TAB_VISUALS, "kd",       "Kills / deaths", false),
            new Opt(TAB_VISUALS, "snap",     "Snap line",      false),
            new Opt(TAB_VISUALS, "look",     "Look direction", false),
            new Opt(TAB_VISUALS, "team",     "Enemies only",   true),
            new Opt(TAB_VISUALS, "dead",     "Show dead",      false),
            new Opt(TAB_VISUALS, "status",   "Debug status",   true),

            new Opt(TAB_COMBAT,  "aimbot",   "Aimbot",         false),
            new Opt(TAB_COMBAT,  "bone",     "Target",         true,
                    new String[] { "Head", "Chest", "Hip", "Nearest" }),
            new Opt(TAB_COMBAT,  "rcs",      "Recoil control", false),
    };

    private final Slider[] sliders = {
            new Slider(TAB_VISUALS, "thick",   "Line width",   1f,  4f,   1.4f, false),
            new Slider(TAB_VISUALS, "textsz",  "Text size",    8f,  16f,  11f,  false),
            new Slider(TAB_VISUALS, "maxdist", "Max distance", 10f, 300f, 300f, true),

            new Slider(TAB_COMBAT,  "fov",     "Aim FOV",      1f,  360f, 90f,  true),
            new Slider(TAB_COMBAT,  "speed",   "Aim speed",    1f,  100f, 25f,  true),
            new Slider(TAB_COMBAT,  "rcspow",  "RCS power",    0f,  100f, 60f,  true),
    };

    /** Bone pairs, indices into the 12 joints the native side projects. */
    private static final int[] BONES = {
            0, 1,   1, 2,   2, 3,
            1, 4,   4, 6,   1, 5,   5, 7,
            3, 8,   8, 10,  3, 9,   9, 11,
    };

    private Opt opt(String key) {
        for (Opt o : opts) if (o.key.equals(key)) return o;
        return null;
    }

    private boolean on(String key) {
        Opt o = opt(key);
        return o != null && o.on;
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

    private boolean nativeDead;
    private int lastCount;

    // ---- handle / menu state -----------------------------------------------
    private final float barW, barH, barTouchH;
    private boolean menuOpen;
    private int tab;
    private int dragSlider = -1;

    private float winW, winH, winL, winT;
    private float titleH, tabH, navW, rowH, pad;
    private int rowsPerCol = 1, cols = 1;
    private float colW;

    public EspOverlay(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("bpesp", Context.MODE_PRIVATE);

        barW      = 132 * d;
        barH      = 5 * d;
        barTouchH = 44 * d;

        for (Opt o : opts) {
            o.on = prefs.getBoolean("o_" + o.key, o.on);
            o.choice = prefs.getInt("c_" + o.key, 0);
        }
        for (Slider s : sliders) s.value = prefs.getFloat("s_" + s.key, s.value);
        tab = prefs.getInt("tab", 0);
        if (tab >= TAB_NAMES.length) tab = 0;

        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setFakeBoldText(true);
        mtext.setFakeBoldText(true);
        shadow.setFakeBoldText(true);
        shadow.setColor(0xC0000000);

        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        pushConfig();
    }

    /** Menu state is authoritative; the poller only ever reads what it is told. */
    private void pushConfig() {
        if (nativeDead) return;
        try {
            Opt bone = opt("bone");
            Native.config(on("team"), on("aimbot"),
                          slider("fov"), slider("speed"),
                          bone == null ? 0 : bone.choice,
                          on("rcs"), slider("rcspow"));
        } catch (Throwable t) {
            Log.e("bpesp", "config push failed", t);
        }
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

    private void layoutMenu() {
        float vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        winW = Math.min(440 * d, vw - 24 * d);
        winH = Math.min(280 * d, vh - barTouchH - 20 * d);
        winL = (vw - winW) * 0.5f;
        winT = Math.max(10 * d, handleY() - barTouchH * 0.6f - winH);

        titleH = Math.max(20 * d, winH * 0.10f);
        tabH   = 0;                       // navigation lives in the sidebar only
        navW   = winW * 0.24f;
        pad    = 6 * d;
        rowH   = 22 * d;

        float contentH = winH - titleH - sliderAreaH() - pad * 2;
        rowsPerCol = Math.max(1, (int) (contentH / rowH));

        int maxOpts = 0;
        for (int t = 0; t < TAB_NAMES.length; t++) {
            int c = 0;
            for (Opt o : opts) if (o.tab == t) c++;
            maxOpts = Math.max(maxOpts, c);
        }
        cols = Math.max(1, (maxOpts + rowsPerCol - 1) / rowsPerCol);
        colW = (winW - navW) / cols;

        mtext.setTextSize(Math.min(10 * d, rowH * 0.44f));
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

        if (on("aimbot") || on("rcs")) {
            boolean ready = false;
            try { ready = Native.aimReady(); } catch (Throwable ignored) { }
            s += ready ? " · aim locked" : " · aim searching";
        }
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
    private float contentTop()  { return winT + titleH + tabH + pad; }
    private float contentLeft() { return winL + navW; }
    private float sliderAreaH() { return countSliders() * 20 * d + pad * 2; }

    private int countSliders() {
        int max = 0;
        for (int t = 0; t < TAB_NAMES.length; t++) {
            int c = 0;
            for (Slider s : sliders) if (s.tab == t) c++;
            max = Math.max(max, c);
        }
        return max;
    }

    private void drawMenu(Canvas c) {
        if (winW <= 0) layoutMenu();
        if (winW <= 0) return;

        rect.set(winL, winT, winL + winW, winT + winH);
        fill.setColor(0xF2101012);
        c.drawRoundRect(rect, 6 * d, 6 * d, fill);
        stroke.setColor(0xFF2A2A2E);
        stroke.setStrokeWidth(1 * d);
        c.drawRoundRect(rect, 6 * d, 6 * d, stroke);

        // title bar + close
        rect.set(winL, winT, winL + winW, winT + titleH);
        fill.setColor(0xFF17171A);
        c.drawRoundRect(rect, 6 * d, 6 * d, fill);
        c.drawRect(winL, winT + titleH - 6 * d, winL + winW, winT + titleH, fill);
        menuText(c, "BPESP", winL + 10 * d, winT + titleH * 0.66f, 0xFF6E6E76);

        float cxx = winL + winW - 14 * d, cyy = winT + titleH * 0.5f, cs = 4.5f * d;
        stroke.setColor(0xFFB9B9C0);
        stroke.setStrokeWidth(1.6f * d);
        c.drawLine(cxx - cs, cyy - cs, cxx + cs, cyy + cs, stroke);
        c.drawLine(cxx + cs, cyy - cs, cxx - cs, cyy + cs, stroke);

        // divider between the sidebar and the content area
        stroke.setColor(0xFF232329);
        stroke.setStrokeWidth(1 * d);
        c.drawLine(winL + navW - 2 * d, winT + titleH + 2 * d,
                   winL + navW - 2 * d, winT + winH - sliderAreaH(), stroke);

        // sidebar — the only navigation
        for (int i = 0; i < TAB_NAMES.length; i++) {
            float ry = contentTop() + i * rowH;
            if (i == tab) {
                rect.set(winL + 5 * d, ry, winL + navW - 4 * d, ry + rowH - 2 * d);
                fill.setColor(0xFF232329);
                c.drawRoundRect(rect, 3 * d, 3 * d, fill);
            }
            fill.setColor(i == tab ? 0xFF34C759 : 0xFF3A3A42);
            c.drawCircle(winL + 14 * d, ry + rowH * 0.45f, 3f * d, fill);
            menuText(c, TAB_NAMES[i], winL + 22 * d,
                     ry + rowH * 0.45f + mtext.getTextSize() * 0.36f,
                     i == tab ? 0xFFE8E8EE : 0xFF6E6E76);
        }

        // option rows, flowed into columns
        int shown = 0;
        for (Opt o : opts) {
            if (o.tab != tab) continue;
            int col = shown / rowsPerCol, row = shown % rowsPerCol;
            if (col >= cols) break;
            float ox = contentLeft() + col * colW;
            float ry = contentTop() + row * rowH;
            float mid = ry + rowH * 0.5f;

            if (o.choices != null) {
                String v = o.choices[o.choice % o.choices.length];
                float bw2 = 46 * d;
                rect.set(ox + colW - bw2 - 8 * d, mid - 7 * d, ox + colW - 8 * d, mid + 7 * d);
                fill.setColor(0xFF2C2C32);
                c.drawRoundRect(rect, 3 * d, 3 * d, fill);
                menuText(c, v, rect.left + 5 * d, mid + mtext.getTextSize() * 0.36f, 0xFFE8E8EE);
            } else {
                float knob = 5f * d, sw = 24 * d;
                float sx = ox + colW - sw - 8 * d;
                rect.set(sx, mid - knob * 1.3f, sx + sw, mid + knob * 1.3f);
                fill.setColor(o.on ? 0xFF34C759 : 0xFF3A3A42);
                c.drawRoundRect(rect, knob * 1.3f, knob * 1.3f, fill);
                fill.setColor(0xFFF0F0F4);
                c.drawCircle(o.on ? rect.right - knob * 1.15f : rect.left + knob * 1.15f,
                             mid, knob, fill);
            }

            menuText(c, o.label, ox + 5 * d, mid + mtext.getTextSize() * 0.36f,
                     (o.choices != null || o.on) ? 0xFFE8E8EE : 0xFF8A8A92);
            shown++;
        }

        // sliders for this tab along the bottom
        int si = 0;
        for (Slider s : sliders) {
            if (s.tab != tab) continue;
            float sy = winT + winH - sliderAreaH() + pad + si * 20 * d + 8 * d;
            float sl = contentLeft() + 5 * d, sr = winL + winW - 34 * d;

            menuText(c, s.label, winL + 10 * d, sy + mtext.getTextSize() * 0.36f, 0xFF6E6E76);
            stroke.setColor(0xFF33333A);
            stroke.setStrokeWidth(2.5f * d);
            c.drawLine(sl, sy, sr, sy, stroke);
            float px = sl + (sr - sl) * s.frac();
            stroke.setColor(0xFF34C759);
            c.drawLine(sl, sy, px, sy, stroke);
            fill.setColor(0xFFF0F0F4);
            c.drawCircle(px, sy, 4.5f * d, fill);
            menuText(c, s.text(), sr + 6 * d, sy + mtext.getTextSize() * 0.36f, 0xFFB9B9C0);
            si++;
        }
    }

    private int optionAt(float x, float y) {
        int shown = 0;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i].tab != tab) continue;
            int col = shown / rowsPerCol, row = shown % rowsPerCol;
            if (col < cols) {
                float ox = contentLeft() + col * colW;
                float ry = contentTop() + row * rowH;
                if (x >= ox && x < ox + colW && y >= ry && y < ry + rowH) return i;
            }
            shown++;
        }
        return -1;
    }

    private int navAt(float x, float y) {
        if (x < winL || x > winL + navW) return -1;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            float ry = contentTop() + i * rowH;
            if (y >= ry && y < ry + rowH) return i;
        }
        return -1;
    }

    private int sliderAt(float x, float y) {
        if (x < winL || x > winL + winW) return -1;
        int si = 0;
        for (int i = 0; i < sliders.length; i++) {
            if (sliders[i].tab != tab) continue;
            float sy = winT + winH - sliderAreaH() + pad + si * 20 * d + 8 * d;
            if (Math.abs(y - sy) <= 10 * d) return i;
            si++;
        }
        return -1;
    }

    private void applySlider(int i, float x) {
        Slider s = sliders[i];
        float sl = contentLeft() + 5 * d, sr = winL + winW - 34 * d;
        s.setFrac((x - sl) / (sr - sl));
        prefs.edit().putFloat("s_" + s.key, s.value).apply();
        pushConfig();
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

                if (y <= winT + titleH) {
                    if (x >= winL + winW - 28 * d) menuOpen = false;
                    return true;
                }

                int t = navAt(x, y);
                if (t >= 0) {
                    tab = t;
                    prefs.edit().putInt("tab", tab).apply();
                    return true;
                }

                int sl = sliderAt(x, y);
                if (sl >= 0) { dragSlider = sl; applySlider(sl, x); return true; }

                int oi = optionAt(x, y);
                if (oi >= 0) {
                    Opt o = opts[oi];
                    if (o.choices != null) {
                        o.choice = (o.choice + 1) % o.choices.length;
                        prefs.edit().putInt("c_" + o.key, o.choice).apply();
                    } else {
                        o.on = !o.on;
                        prefs.edit().putBoolean("o_" + o.key, o.on).apply();
                    }
                    pushConfig();
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
