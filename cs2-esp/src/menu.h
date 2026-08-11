#pragma once

#include "draw.h"
#include "overlay.h"
#include "vec.h"

#include <string>

// Immediate-mode menu drawn straight onto the overlay. No retained widget tree:
// each frame lays itself out top to bottom and hit-tests against the cursor.
class Menu
{
public:
    explicit Menu(Draw& draw) : m_draw(draw) {}

    void Toggle() { m_open = !m_open; }
    bool IsOpen() const { return m_open; }
    void Close() { m_open = false; }

    // Samples cursor and mouse button once per frame.
    void Update(Overlay& overlay);

    // Lays out and draws every control, writing straight into g_config.
    void Render(Overlay& overlay);

private:
    bool Hovered(float x, float y, float w, float h) const;

    void Section(const wchar_t* label);
    bool Checkbox(const wchar_t* label, bool& value);
    bool Slider(const wchar_t* label, float& value, float min, float max, const wchar_t* format);

    void DrawCursor();

    Draw& m_draw;

    bool m_open = false;

    // Panel position persists across frames; height is measured from the
    // previous layout so the background can be drawn before the contents.
    float m_panelX   = 60.f;
    float m_panelY   = 60.f;
    float m_height   = 420.f;
    float m_cursorY  = 0.f;

    bool  m_dragging = false;
    Vec2  m_dragGrab;

    Vec2  m_mouse;
    bool  m_down     = false;
    bool  m_clicked  = false;

    // Identity of the slider currently being dragged, null when none.
    const void* m_activeSlider = nullptr;
};
