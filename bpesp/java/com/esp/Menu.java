package com.esp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The menu window, laid out to match the reference design: a title bar with a
 * close cross, a strip of square tab buttons beneath it, then a body split into
 * a narrow navigation column, a main panel, and a taller side panel, with a
 * slider tray tucked under the main panel.
 *
 * Geometry is expressed as fractions of the window so it lands the same on any
 * screen, including a freeform one.
 */
final class Menu {

    // palette
    static final int BG        = 0xF00E0E10;
    static final int BG_BAR    = 0xFF141417;
    static final int PANEL     = 0xFF121215;
    static final int PANEL_HI  = 0xFF1B1B20;
    static final int LINE      = 0xFF232329;
    static final int TEXT      = 0xFFE6E6EC;
    static final int TEXT_DIM  = 0xFF6B6B74;
    static final int ACCENT    = 0xFF3DDC84;
    static final int TRACK     = 0xFF2A2A31;
    static final int KNOB      = 0xFFEDEDF2;

    final float d;
    float x, y, w, h;
    float titleH, tabH, navW, gap, rowH;
    float mainX, mainW, mainH, sideX, sideW, trayY, trayH;

    Menu(float density) { d = density; }

    void layout(float viewW, float viewH, float bottomInset) {
        w = Math.min(560 * d, viewW - 28 * d);
        h = Math.min(340 * d, viewH - bottomInset - 24 * d);
        x = (viewW - w) * 0.5f;
        y = Math.max(12 * d, (viewH - bottomInset - h) * 0.5f);

        titleH = h * 0.115f;
        tabH   = h * 0.125f;
        gap    = 7 * d;
        navW   = w * 0.205f;
        rowH   = 26 * d;

        float bodyY = y + titleH + tabH;
        float bodyH = y + h - bodyY - gap;

        mainX = x + navW + gap;
        sideX = x + w * 0.655f;
        mainW = sideX - mainX - gap;
        sideW = x + w - sideX - gap;

        mainH = bodyH * 0.60f;
        trayY = bodyY + mainH + gap;
        trayH = y + h - trayY - gap;
    }

    float bodyY() { return y + titleH + tabH; }

    // ---- primitives --------------------------------------------------------
    void panel(Canvas c, Paint f, Paint s, RectF r, float rad, int fillCol) {
        f.setColor(fillCol);
        c.drawRoundRect(r, rad, rad, f);
        s.setColor(LINE);
        s.setStrokeWidth(1 * d);
        c.drawRoundRect(r, rad, rad, s);
    }

    /** Pill switch, drawn the way the reference does: track plus offset knob. */
    void toggle(Canvas c, Paint f, float cx, float cy, boolean on) {
        float tw = 26 * d, th = 13 * d, k = th * 0.42f;
        RectF r = new RectF(cx - tw * 0.5f, cy - th * 0.5f, cx + tw * 0.5f, cy + th * 0.5f);
        f.setColor(on ? ACCENT : TRACK);
        c.drawRoundRect(r, th * 0.5f, th * 0.5f, f);
        f.setColor(KNOB);
        c.drawCircle(on ? r.right - th * 0.5f : r.left + th * 0.5f, cy, k, f);
    }

    void slider(Canvas c, Paint f, Paint s, float lx, float rx, float cy, float frac) {
        s.setColor(TRACK);
        s.setStrokeWidth(2.5f * d);
        c.drawLine(lx, cy, rx, cy, s);
        float px = lx + (rx - lx) * Math.max(0f, Math.min(1f, frac));
        s.setColor(ACCENT);
        c.drawLine(lx, cy, px, cy, s);
        f.setColor(KNOB);
        c.drawCircle(px, cy, 4.5f * d, f);
    }
}
