#include "draw.h"

#include <cmath>

using Microsoft::WRL::ComPtr;

namespace
{
    D2D1_COLOR_F ToD2D(const Color& color)
    {
        return D2D1::ColorF(color.r, color.g, color.b, color.a);
    }
}

bool Draw::Init(Overlay& overlay)
{
    m_ctx     = overlay.Context();
    m_dwrite  = overlay.DWrite();
    m_factory = overlay.Factory();

    if (m_ctx == nullptr || m_dwrite == nullptr || m_factory == nullptr)
        return false;

    if (FAILED(m_ctx->CreateSolidColorBrush(D2D1::ColorF(D2D1::ColorF::White), &m_brush)))
        return false;

    const float requested = m_fontSize;
    m_fontSize = 0.f;               // force the first creation
    SetFontSize(requested);

    return m_font != nullptr;
}

void Draw::Shutdown()
{
    m_font.Reset();
    m_brush.Reset();
    m_ctx     = nullptr;
    m_dwrite  = nullptr;
    m_factory = nullptr;
}

void Draw::SetFontSize(float size)
{
    if (m_dwrite == nullptr)
        return;

    size = size < 8.f ? 8.f : (size > 40.f ? 40.f : size);

    if (std::fabs(size - m_fontSize) < 0.05f && m_font != nullptr)
        return;

    ComPtr<IDWriteTextFormat> format;

    if (FAILED(m_dwrite->CreateTextFormat(
            L"Tahoma", nullptr,
            DWRITE_FONT_WEIGHT_SEMI_BOLD, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL,
            size, L"en-us", &format)))
        return;

    format->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP);

    m_font     = format;
    m_fontSize = size;
}

void Draw::Line(const Vec2& a, const Vec2& b, const Color& color, float width)
{
    m_brush->SetColor(ToD2D(color));
    m_ctx->DrawLine(D2D1::Point2F(a.x, a.y), D2D1::Point2F(b.x, b.y), m_brush.Get(), width);
}

void Draw::Rect(float x, float y, float w, float h, const Color& color, float thickness)
{
    m_brush->SetColor(ToD2D(color));
    m_ctx->DrawRectangle(D2D1::RectF(x, y, x + w, y + h), m_brush.Get(), thickness);
}

void Draw::FilledRect(float x, float y, float w, float h, const Color& color)
{
    m_brush->SetColor(ToD2D(color));
    m_ctx->FillRectangle(D2D1::RectF(x, y, x + w, y + h), m_brush.Get());
}

void Draw::RoundedRect(float x, float y, float w, float h, float radius, const Color& color, float thickness)
{
    m_brush->SetColor(ToD2D(color));
    const D2D1_ROUNDED_RECT rect = D2D1::RoundedRect(D2D1::RectF(x, y, x + w, y + h), radius, radius);
    m_ctx->DrawRoundedRectangle(rect, m_brush.Get(), thickness);
}

void Draw::FilledRoundedRect(float x, float y, float w, float h, float radius, const Color& color)
{
    m_brush->SetColor(ToD2D(color));
    const D2D1_ROUNDED_RECT rect = D2D1::RoundedRect(D2D1::RectF(x, y, x + w, y + h), radius, radius);
    m_ctx->FillRoundedRectangle(rect, m_brush.Get());
}

void Draw::Triangle(const Vec2& a, const Vec2& b, const Vec2& c, const Color& color)
{
    ComPtr<ID2D1PathGeometry> geometry;

    if (FAILED(m_factory->CreatePathGeometry(&geometry)))
        return;

    ComPtr<ID2D1GeometrySink> sink;

    if (FAILED(geometry->Open(&sink)))
        return;

    sink->BeginFigure(D2D1::Point2F(a.x, a.y), D2D1_FIGURE_BEGIN_FILLED);
    sink->AddLine(D2D1::Point2F(b.x, b.y));
    sink->AddLine(D2D1::Point2F(c.x, c.y));
    sink->EndFigure(D2D1_FIGURE_END_CLOSED);

    if (FAILED(sink->Close()))
        return;

    m_brush->SetColor(ToD2D(color));
    m_ctx->FillGeometry(geometry.Get(), m_brush.Get());
}

void Draw::FilledCircle(const Vec2& center, float radius, const Color& color)
{
    m_brush->SetColor(ToD2D(color));
    m_ctx->FillEllipse(D2D1::Ellipse(D2D1::Point2F(center.x, center.y), radius, radius), m_brush.Get());
}

ComPtr<IDWriteTextLayout> Draw::MakeLayout(const std::wstring& text)
{
    ComPtr<IDWriteTextLayout> layout;

    if (m_dwrite == nullptr || m_font == nullptr || text.empty())
        return layout;

    if (FAILED(m_dwrite->CreateTextLayout(text.c_str(), static_cast<UINT32>(text.size()),
                                          m_font.Get(), 4096.f, 256.f, &layout)))
        layout.Reset();

    return layout;
}

Vec2 Draw::Measure(const std::wstring& text)
{
    ComPtr<IDWriteTextLayout> layout = MakeLayout(text);

    if (layout == nullptr)
        return {};

    DWRITE_TEXT_METRICS metrics{};

    if (FAILED(layout->GetMetrics(&metrics)))
        return {};

    return { metrics.width, metrics.height };
}

void Draw::Text(const std::wstring& text, float x, float y, const Color& color,
                TextAlign align, bool shadow)
{
    ComPtr<IDWriteTextLayout> layout = MakeLayout(text);

    if (layout == nullptr)
        return;

    DWRITE_TEXT_METRICS metrics{};

    if (FAILED(layout->GetMetrics(&metrics)))
        return;

    float drawX = x;

    if (align == TextAlign::Center)
        drawX = x - metrics.width * 0.5f;
    else if (align == TextAlign::Right)
        drawX = x - metrics.width;

    if (shadow)
    {
        m_brush->SetColor(D2D1::ColorF(0.f, 0.f, 0.f, 0.9f * color.a));
        m_ctx->DrawTextLayout(D2D1::Point2F(drawX + 1.f, y + 1.f), layout.Get(), m_brush.Get(),
                              D2D1_DRAW_TEXT_OPTIONS_NONE);
    }

    m_brush->SetColor(ToD2D(color));
    m_ctx->DrawTextLayout(D2D1::Point2F(drawX, y), layout.Get(), m_brush.Get(),
                          D2D1_DRAW_TEXT_OPTIONS_NONE);
}
