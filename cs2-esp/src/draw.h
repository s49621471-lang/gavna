#pragma once

#include "overlay.h"
#include "vec.h"

#include <string>
#include <wrl/client.h>

struct Color
{
    float r = 1.f;
    float g = 1.f;
    float b = 1.f;
    float a = 1.f;

    constexpr Color WithAlpha(float alpha) const { return { r, g, b, alpha }; }
};

enum class TextAlign
{
    Left,
    Center,
    Right
};

// Thin wrapper over the D2D context. Everything that puts pixels on the overlay
// goes through here so the ESP and the menu share one brush and one font.
class Draw
{
public:
    bool Init(Overlay& overlay);
    void Shutdown();

    // Recreates the text format only when the size actually changed.
    void SetFontSize(float size);

    void Line(const Vec2& a, const Vec2& b, const Color& color, float width);
    void Rect(float x, float y, float w, float h, const Color& color, float thickness);
    void FilledRect(float x, float y, float w, float h, const Color& color);
    void RoundedRect(float x, float y, float w, float h, float radius, const Color& color, float thickness);
    void FilledRoundedRect(float x, float y, float w, float h, float radius, const Color& color);
    void Triangle(const Vec2& a, const Vec2& b, const Vec2& c, const Color& color);
    void FilledCircle(const Vec2& center, float radius, const Color& color);

    // Text goes through DWrite layouts: exact metrics for alignment, and it
    // sidesteps the DrawText macro winuser.h drops on every translation unit.
    void Text(const std::wstring& text, float x, float y, const Color& color,
              TextAlign align = TextAlign::Left, bool shadow = true);

    Vec2 Measure(const std::wstring& text);

    float FontSize() const { return m_fontSize; }

private:
    Microsoft::WRL::ComPtr<IDWriteTextLayout> MakeLayout(const std::wstring& text);

    ID2D1DeviceContext* m_ctx     = nullptr;
    IDWriteFactory*     m_dwrite  = nullptr;
    ID2D1Factory1*      m_factory = nullptr;

    Microsoft::WRL::ComPtr<ID2D1SolidColorBrush> m_brush;
    Microsoft::WRL::ComPtr<IDWriteTextFormat>    m_font;

    float m_fontSize = 13.f;
};
