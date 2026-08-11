#pragma once

#include "overlay.h"
#include "sdk.h"
#include "vec.h"

#include <string>
#include <wrl/client.h>

struct Color
{
    float r = 1.f;
    float g = 1.f;
    float b = 1.f;
    float a = 1.f;
};

namespace Colors
{
    inline constexpr Color kTerrorist   = { 0.94f, 0.66f, 0.23f, 1.f  };
    inline constexpr Color kCounterTerr = { 0.35f, 0.66f, 0.95f, 1.f  };
    inline constexpr Color kOutline     = { 0.f,   0.f,   0.f,   0.85f };
    inline constexpr Color kSkeleton    = { 1.f,   1.f,   1.f,   0.75f };
    inline constexpr Color kText        = { 1.f,   1.f,   1.f,   1.f  };
    inline constexpr Color kShadow      = { 0.f,   0.f,   0.f,   0.9f  };
    inline constexpr Color kSnapline    = { 0.85f, 0.85f, 0.85f, 0.45f };
    inline constexpr Color kArmor       = { 0.35f, 0.66f, 0.95f, 0.9f  };
}

enum class TextAlign
{
    Left,
    Center,
    Right
};

class Renderer
{
public:
    bool Init(Overlay& overlay);
    void Shutdown();

    // Draws one frame of ESP from an already-captured snapshot.
    void Render(Overlay& overlay, const Snapshot& snapshot, float fps);

private:
    void Line(const Vec2& a, const Vec2& b, const Color& color, float width);
    void Rect(float x, float y, float w, float h, const Color& color, float thickness);
    void FilledRect(float x, float y, float w, float h, const Color& color);

    // Text goes through DWrite layouts: exact metrics for alignment, and it
    // sidesteps the DrawText macro winuser.h drops on every translation unit.
    void Text(const std::wstring& text, float x, float y, const Color& color, TextAlign align);

    // Screen-space extents of a player, fitted to the bones when they are valid.
    bool ComputeBox(const Snapshot& snapshot, const Player& player, int w, int h,
                    float& outX, float& outY, float& outW, float& outH) const;

    void DrawSkeleton(const Snapshot& snapshot, const Player& player, int w, int h);
    void DrawHealthBar(const Player& player, float boxX, float boxY, float boxH);

    ID2D1DeviceContext* m_ctx    = nullptr;
    IDWriteFactory*     m_dwrite = nullptr;

    Microsoft::WRL::ComPtr<ID2D1SolidColorBrush> m_brush;
    Microsoft::WRL::ComPtr<IDWriteTextFormat>    m_font;
};

// UTF-8 to UTF-16 for the sanitized player names coming out of the game.
std::wstring Widen(const std::string& input);
