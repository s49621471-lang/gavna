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
 * poller. Touches are only consumed on the handle bar or inside an open menu;
 * everything else falls through to the game.
 */
public class EspOverlay extends View implements Choreographer.FrameCallback {

    // ---- options -----------------------------------------------------------
    private static final class Opt {
        final String key, label;
        boolean on;
        Opt(String key, String label, boolean on) { this.key = key; this.label = label; this.on = on; }
    }

    private final Opt[] opts = {
            new Opt("master",   "ESP enabled",     true),
            new Opt("box",      "Box",             true),
            new Opt("corners",  "Corner box",      false),
            new Opt("name",     "Name",            true),
            new Opt("hpbar",    "Health bar",      true),
            new Opt("armorbar", "Armor bar",       true),
            new Opt("hptext",   "Health number",   false),
            new Opt("dist",     "Distance",        true),
            new Opt("kd",       "Kills / deaths",  false),
            new Opt("snap",     "Snap line",       false),
            new Opt("look",     "Look direction",  false),
            new Opt("dead",     "Show dead",       false),
            new Opt("status",   "Debug status",    true),
    };

    private boolean on(String key) {
        for (Opt o : opts) if (o.key.equals(key)) return o.on;
        return false;
    }

    // ---- paint -------------------------------------------------------------
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect   = new RectF();

    private final float[]  buf   = new float[Native.MAX_ENT * Native.STRIDE];
    private final String[] names = new String[Native.MAX_ENT];

    private final SharedPreferences prefs;
    private final float d;

    /** Latches if a native call ever throws, so one failure cannot kill the game. */
    private boolean nativeDead;

    // ---- handle / menu state -----------------------------------------------
    private final float barW, barH, barTouchH, rowH, panelW;
    private float handleX = -1, handleY = -1;
    private boolean menuOpen, dragging, onHandle;
    private float downX, downY, grabDX, grabDY;

    public EspOverlay(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("bpesp", Context.MODE_PRIVATE);

        barW      = 132 * d;
        barH      = 5 * d;
        barTouchH = 44 * d;
        rowH      = 34 * d;
        panelW    = 190 * d;

        handleX = prefs.getFloat("bx", -1);
        handleY = prefs.getFloat("by", -1);
        for (Opt o : opts) o.on = prefs.getBoolean("o_" + o.key, o.on);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.4f * d);
        fill.setStyle(Paint.Style.FILL);
        text.setTextSize(11 * d);
        text.setFakeBoldText(true);
        shadow.setTextSize(11 * d);
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

    /** Screen size is unknown in the constructor, so the bar is placed on first draw. */
    private void placeHandle() {
        if (handleX >= 0 && handleY >= 0) return;
        handleX = getWidth() * 0.5f;
        handleY = getHeight() - 14 * d;
    }

    // ---- drawing -----------------------------------------------------------
    @Override protected void onDraw(Canvas c) {
        placeHandle();

        int state = -1;
        if (!nativeDead) {
            try {
                state = Native.state();
                if (on("master") && state == 2) drawEntities(c);
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
        else if (state == 2) s = "live";
        else if (state < 0)  s = "starting";
        else                 s = Native.status();
        label(c, "[esp] " + s, 10 * d, 18 * d, state == 2 ? 0xFF66FF99 : 0xFFFFCC44);
    }

    private void drawEntities(Canvas c) {
        int n = Native.fetch(buf, names);
        boolean showDead = on("dead");
        float cx = getWidth() * 0.5f, cy = getHeight();

        for (int i = 0; i < n; i++) {
            int b = i * Native.STRIDE;
            boolean alive = buf[b + Native.F_ALIVE] > 0.5f;
            if (!alive && !showDead) continue;

            float fx = buf[b + Native.F_FEET_X];
            float fy = buf[b + Native.F_FEET_Y];
            float hy = buf[b + Native.F_HEAD_Y];
            float bw = buf[b + Native.F_BOX_W];
            if (bw < 2 * d) bw = 2 * d;

            float left = fx - bw * 0.5f, right = fx + bw * 0.5f;
            float top = Math.min(fy, hy), bottom = Math.max(fy, hy);
            if (right < 0 || left > getWidth() || bottom < 0 || top > getHeight()) continue;

            int hp = (int) buf[b + Native.F_HP];
            int col = alive ? hpColor(hp) : 0xFF808080;

            if (on("snap")) {
                stroke.setColor(0x66FFFFFF);
                stroke.setStrokeWidth(1.4f * d);
                c.drawLine(cx, cy, fx, bottom, stroke);
            }

            if (on("look")) {
                stroke.setColor(0xAA44CCFF);
                stroke.setStrokeWidth(1.4f * d);
                c.drawLine(fx, (top + bottom) * 0.5f,
                           buf[b + Native.F_DIR_X], buf[b + Native.F_DIR_Y], stroke);
            }

            rect.set(left, top, right, bottom);
            if (on("corners")) drawCorners(c, rect, col);
            else if (on("box")) {
                stroke.setColor(0x99000000);
                stroke.setStrokeWidth(2.6f * d);
                c.drawRect(rect, stroke);
                stroke.setColor(col);
                stroke.setStrokeWidth(1.4f * d);
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

            float ty = top - 4 * d;
            if (on("name")) {
                String nm = names[i] == null ? "?" : names[i];
                centered(c, nm, fx, ty, alive ? 0xFFFFFFFF : 0xFF9E9E9E);
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

    private void drawCorners(Canvas c, RectF r, int col) {
        float len = Math.min(r.width(), r.height()) * 0.28f;
        for (int pass = 0; pass < 2; pass++) {
            stroke.setColor(pass == 0 ? 0x99000000 : col);
            stroke.setStrokeWidth(pass == 0 ? 3.0f * d : 1.6f * d);
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

    // ---- handle ------------------------------------------------------------
    /** The gesture-pill style bar that opens the menu. */
    private void drawHandle(Canvas c) {
        rect.set(handleX - barW * 0.5f, handleY - barH * 0.5f,
                 handleX + barW * 0.5f, handleY + barH * 0.5f);
        float r = barH * 0.5f;

        // dark halo so the bar stays visible over a light scene
        fill.setColor(0x66000000);
        c.drawRoundRect(handleX - barW * 0.5f - 1.5f * d, handleY - barH * 0.5f - 1.5f * d,
                        handleX + barW * 0.5f + 1.5f * d, handleY + barH * 0.5f + 1.5f * d,
                        r + 1.5f * d, r + 1.5f * d, fill);

        fill.setColor(menuOpen ? 0xFF34C759 : 0xFFFFFFFF);
        c.drawRoundRect(rect, r, r, fill);
    }

    private boolean hitHandle(float x, float y) {
        return Math.abs(x - handleX) <= barW * 0.5f + 12 * d
            && Math.abs(y - handleY) <= barTouchH * 0.5f;
    }

    private float panelHeight() { return rowH * (opts.length + 1); }

    private float panelLeft() {
        float l = handleX - panelW * 0.5f;
        if (l + panelW > getWidth()) l = getWidth() - panelW;
        if (l < 0) l = 0;
        return l;
    }

    /** Menu opens upward from the bar, since the bar lives at the bottom. */
    private float panelTop() {
        float t = handleY - barTouchH * 0.5f - 8 * d - panelHeight();
        if (t < 0) t = 0;
        if (t + panelHeight() > getHeight()) t = getHeight() - panelHeight();
        return t;
    }

    private void drawMenu(Canvas c) {
        float l = panelLeft(), t = panelTop(), h = panelHeight();

        rect.set(l, t, l + panelW, t + h);
        fill.setColor(0xE81A1A1A);
        c.drawRoundRect(rect, 8 * d, 8 * d, fill);
        stroke.setColor(0xFF34C759);
        stroke.setStrokeWidth(1.5f * d);
        c.drawRoundRect(rect, 8 * d, 8 * d, stroke);

        label(c, "BLOCKPOST ESP", l + 12 * d, t + rowH * 0.65f, 0xFF34C759);

        for (int i = 0; i < opts.length; i++) {
            float ry = t + rowH * (i + 1);
            Opt o = opts[i];

            rect.set(l + panelW - 44 * d, ry + 9 * d, l + panelW - 12 * d, ry + 25 * d);
            fill.setColor(o.on ? 0xFF34C759 : 0xFF4A4A4A);
            c.drawRoundRect(rect, 8 * d, 8 * d, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(o.on ? rect.right - 8 * d : rect.left + 8 * d,
                         (rect.top + rect.bottom) * 0.5f, 6 * d, fill);

            label(c, o.label, l + 12 * d, ry + rowH * 0.65f,
                  o.on ? 0xFFFFFFFF : 0xFF9E9E9E);
        }
    }

    // ---- input -------------------------------------------------------------
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (hitHandle(x, y)) {
                    onHandle = true;
                    dragging = false;
                    downX = x; downY = y;
                    grabDX = handleX - x; grabDY = handleY - y;
                    return true;
                }
                onHandle = false;

                if (menuOpen) {
                    float l = panelLeft(), t = panelTop();
                    if (x >= l && x <= l + panelW && y >= t && y <= t + panelHeight()) {
                        int row = (int) ((y - t) / rowH) - 1;
                        if (row >= 0 && row < opts.length) {
                            opts[row].on = !opts[row].on;
                            prefs.edit().putBoolean("o_" + opts[row].key, opts[row].on).apply();
                        }
                        return true;
                    }
                    menuOpen = false;   // tap outside closes it
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!onHandle) return false;
                if (Math.abs(x - downX) > 8 * d || Math.abs(y - downY) > 8 * d) dragging = true;
                if (dragging) {
                    handleX = clamp(x + grabDX, barW * 0.5f, getWidth() - barW * 0.5f);
                    handleY = clamp(y + grabDY, barTouchH * 0.5f, getHeight() - barH);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!onHandle) return false;
                if (dragging) prefs.edit().putFloat("bx", handleX).putFloat("by", handleY).apply();
                else if (e.getActionMasked() == MotionEvent.ACTION_UP) menuOpen = !menuOpen;
                dragging = false;
                onHandle = false;
                return true;
            }
        }
        return false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
