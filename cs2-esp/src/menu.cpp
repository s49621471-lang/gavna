#include "menu.h"

#include "config.h"

#include <algorithm>
#include <cstdio>
#include <cwchar>

namespace
{
    constexpr float kWidth     = 274.f;
    constexpr float kPad       = 14.f;
    constexpr float kTitleH    = 38.f;
    constexpr float kRowH      = 25.f;
    constexpr float kSectionH  = 28.f;
    constexpr float kSliderH   = 38.f;
    constexpr float kFooterH   = 26.f;
    constexpr float kRadius    = 7.f;

    constexpr Color kPanel      = { 0.055f, 0.060f, 0.075f, 0.96f };
    constexpr Color kPanelEdge  = { 0.16f,  0.17f,  0.21f,  1.f   };
    constexpr Color kTitleBar   = { 0.085f, 0.092f, 0.115f, 1.f   };
    constexpr Color kAccent     = { 0.96f,  0.66f,  0.20f,  1.f   };
    constexpr Color kLabel      = { 0.86f,  0.87f,  0.90f,  1.f   };
    constexpr Color kMuted      = { 0.48f,  0.50f,  0.56f,  1.f   };
    constexpr Color kRowHover   = { 1.f,    1.f,    1.f,    0.055f };
    constexpr Color kTrack      = { 0.20f,  0.21f,  0.26f,  1.f   };
    constexpr Color kBoxOff     = { 0.17f,  0.18f,  0.22f,  1.f   };

    bool MouseHeld()
    {
        return (GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0;
    }
}

void Menu::Update(Overlay& overlay)
{
    if (!m_open)
    {
        m_down          = false;
        m_clicked       = false;
        m_dragging      = false;
        m_activeSlider  = nullptr;
        return;
    }

    m_mouse = overlay.CursorPosition();

    const bool down = MouseHeld();

    m_clicked = down && !m_down;
    m_down    = down;

    if (!down)
    {
        m_dragging     = false;
        m_activeSlider = nullptr;
    }
}

bool Menu::Hovered(float x, float y, float w, float h) const
{
    return m_mouse.x >= x && m_mouse.x <= x + w &&
           m_mouse.y >= y && m_mouse.y <= y + h;
}

void Menu::Section(const wchar_t* label)
{
    const float y = m_cursorY + 8.f;

    m_draw.Text(label, m_panelX + kPad, y, kAccent, TextAlign::Left, false);

    const float lineY = y + m_draw.FontSize() * 0.5f + 1.f;
    const Vec2  size  = m_draw.Measure(label);

    m_draw.Line({ m_panelX + kPad + size.x + 8.f, lineY },
                { m_panelX + kWidth - kPad, lineY },
                kPanelEdge, 1.f);

    m_cursorY += kSectionH;
}

bool Menu::Checkbox(const wchar_t* label, bool& value)
{
    const float x = m_panelX + kPad;
    const float y = m_cursorY;
    const float w = kWidth - kPad * 2.f;

    const bool hovered = Hovered(x, y, w, kRowH);

    if (hovered)
        m_draw.FilledRoundedRect(x - 4.f, y, w + 8.f, kRowH, 4.f, kRowHover);

    constexpr float kBox = 16.f;

    const float boxX = x + w - kBox;
    const float boxY = y + (kRowH - kBox) * 0.5f;

    m_draw.FilledRoundedRect(boxX, boxY, kBox, kBox, 4.f, value ? kAccent : kBoxOff);

    if (value)
    {
        // Tick mark, drawn as two strokes rather than a glyph so it stays crisp
        // at any panel scale.
        const Color mark{ 0.06f, 0.06f, 0.08f, 1.f };
        m_draw.Line({ boxX + 3.6f, boxY + 8.2f }, { boxX + 6.6f, boxY + 11.6f }, mark, 2.f);
        m_draw.Line({ boxX + 6.6f, boxY + 11.6f }, { boxX + 12.4f, boxY + 4.8f }, mark, 2.f);
    }
    else
    {
        m_draw.RoundedRect(boxX, boxY, kBox, kBox, 4.f, kPanelEdge, 1.f);
    }

    m_draw.Text(label, x, y + (kRowH - m_draw.FontSize() * 1.25f) * 0.5f,
                value ? kLabel : kMuted, TextAlign::Left, false);

    m_cursorY += kRowH;

    if (hovered && m_clicked)
    {
        value     = !value;
        m_clicked = false;   // one click, one widget
        return true;
    }

    return false;
}

bool Menu::Slider(const wchar_t* label, float& value, float min, float max, const wchar_t* format)
{
    const float x = m_panelX + kPad;
    const float y = m_cursorY;
    const float w = kWidth - kPad * 2.f;

    m_draw.Text(label, x, y, kLabel, TextAlign::Left, false);

    wchar_t readout[32]{};
    swprintf(readout, ARRAYSIZE(readout), format, value);
    m_draw.Text(readout, x + w, y, kAccent, TextAlign::Right, false);

    const float trackY = y + m_draw.FontSize() + 8.f;
    const float trackH = 4.f;

    const float span = (max - min);
    float       t    = span > 0.f ? std::clamp((value - min) / span, 0.f, 1.f) : 0.f;

    m_draw.FilledRoundedRect(x, trackY, w, trackH, trackH * 0.5f, kTrack);
    m_draw.FilledRoundedRect(x, trackY, w * t, trackH, trackH * 0.5f, kAccent);
    m_draw.FilledCircle({ x + w * t, trackY + trackH * 0.5f }, 6.f, kAccent);

    // The grab band is taller than the track so the knob is easy to catch.
    const bool overGrab = Hovered(x - 6.f, trackY - 9.f, w + 12.f, 22.f);

    if (m_clicked && overGrab)
    {
        m_activeSlider = &value;
        m_clicked      = false;
    }

    bool changed = false;

    if (m_activeSlider == &value && m_down)
    {
        t = std::clamp((m_mouse.x - x) / w, 0.f, 1.f);

        const float updated = min + t * span;

        if (updated != value)
        {
            value   = updated;
            changed = true;
        }
    }

    m_cursorY += kSliderH;
    return changed;
}

void Menu::DrawCursor()
{
    const Vec2 tip{ m_mouse.x, m_mouse.y };
    const Vec2 tail{ m_mouse.x + 0.5f, m_mouse.y + 15.f };
    const Vec2 wing{ m_mouse.x + 10.5f, m_mouse.y + 10.5f };

    const Vec2 shadowTip{ tip.x - 1.f, tip.y - 1.f };
    const Vec2 shadowTail{ tail.x + 1.f, tail.y + 2.f };
    const Vec2 shadowWing{ wing.x + 2.f, wing.y + 1.f };

    m_draw.Triangle(shadowTip, shadowTail, shadowWing, { 0.f, 0.f, 0.f, 0.8f });
    m_draw.Triangle(tip, tail, wing, { 1.f, 1.f, 1.f, 1.f });
}

void Menu::Render(Overlay& overlay)
{
    if (!m_open)
        return;

    const float screenW = static_cast<float>(overlay.Width());
    const float screenH = static_cast<float>(overlay.Height());

    // Panel drag, resolved before anything is drawn so the frame is consistent.
    const bool overTitle = Hovered(m_panelX, m_panelY, kWidth, kTitleH);

    if (m_clicked && overTitle)
    {
        m_dragging  = true;
        m_dragGrab  = { m_mouse.x - m_panelX, m_mouse.y - m_panelY };
        m_clicked   = false;
    }

    if (m_dragging && m_down)
    {
        m_panelX = m_mouse.x - m_dragGrab.x;
        m_panelY = m_mouse.y - m_dragGrab.y;
    }

    m_panelX = std::clamp(m_panelX, 0.f, std::max(0.f, screenW - kWidth));
    m_panelY = std::clamp(m_panelY, 0.f, std::max(0.f, screenH - kTitleH));

    // Background uses last frame's measured height, so the panel never flickers
    // a wrong size on the frame a control is added or removed.
    m_draw.FilledRoundedRect(m_panelX - 1.f, m_panelY - 1.f, kWidth + 2.f, m_height + 2.f,
                             kRadius + 1.f, kPanelEdge);
    m_draw.FilledRoundedRect(m_panelX, m_panelY, kWidth, m_height, kRadius, kPanel);
    m_draw.FilledRoundedRect(m_panelX, m_panelY, kWidth, kTitleH, kRadius, kTitleBar);
    m_draw.FilledRect(m_panelX, m_panelY + kTitleH - kRadius, kWidth, kRadius, kTitleBar);
    m_draw.FilledRect(m_panelX, m_panelY + kTitleH - 1.f, kWidth, 1.f, kAccent.WithAlpha(0.55f));

    m_draw.Text(L"gavna", m_panelX + kPad, m_panelY + (kTitleH - m_draw.FontSize() * 1.3f) * 0.5f,
                kAccent, TextAlign::Left, false);
    m_draw.Text(L"cs2", m_panelX + kWidth - kPad,
                m_panelY + (kTitleH - m_draw.FontSize() * 1.3f) * 0.5f,
                kMuted, TextAlign::Right, false);

    m_cursorY = m_panelY + kTitleH + 2.f;

    Section(L"ESP");
    Checkbox(L"Enabled",        g_config.enabled);
    Checkbox(L"Boxes",          g_config.drawBox);
    Checkbox(L"Skeleton",       g_config.drawSkeleton);
    Checkbox(L"Health bar",     g_config.drawHealthBar);
    Checkbox(L"Names",          g_config.drawName);
    Checkbox(L"Distance",       g_config.drawDistance);
    Checkbox(L"Snaplines",      g_config.drawSnaplines);
    Checkbox(L"Scoped tag",     g_config.drawWeapon);

    Section(L"Filters");
    Checkbox(L"Team check",     g_config.teamCheck);
    Checkbox(L"Show overlay stats", g_config.showFps);
    Slider(L"Max distance", g_config.maxDistance, 0.f, 8000.f, L"%.0f u");

    Section(L"Style");
    Slider(L"Box thickness",  g_config.boxThickness,  0.5f, 4.f,  L"%.1f");
    Slider(L"Skeleton width", g_config.skeletonWidth, 0.5f, 4.f,  L"%.1f");

    if (Slider(L"Font size", g_config.fontSize, 9.f, 24.f, L"%.0f"))
        m_draw.SetFontSize(g_config.fontSize);

    const float footerY = m_cursorY + 4.f;

    m_draw.Line({ m_panelX + kPad, footerY }, { m_panelX + kWidth - kPad, footerY },
                kPanelEdge, 1.f);
    m_draw.Text(L"INS  close        END  exit", m_panelX + kWidth * 0.5f, footerY + 7.f,
                kMuted, TextAlign::Center, false);

    m_height = (footerY + kFooterH) - m_panelY;

    DrawCursor();

    // A click that reached the end of the frame hit nothing; drop it so it does
    // not leak into the next one.
    m_clicked = false;
}
