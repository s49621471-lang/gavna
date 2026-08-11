#pragma once

#include "draw.h"
#include "sdk.h"
#include "vec.h"

#include <string>

namespace Colors
{
    inline constexpr Color kTerrorist   = { 0.94f, 0.66f, 0.23f, 1.f  };
    inline constexpr Color kCounterTerr = { 0.35f, 0.66f, 0.95f, 1.f  };
    inline constexpr Color kOutline     = { 0.f,   0.f,   0.f,   0.85f };
    inline constexpr Color kSkeleton    = { 1.f,   1.f,   1.f,   0.75f };
    inline constexpr Color kText        = { 1.f,   1.f,   1.f,   1.f  };
    inline constexpr Color kSnapline    = { 0.85f, 0.85f, 0.85f, 0.45f };
    inline constexpr Color kArmor       = { 0.35f, 0.66f, 0.95f, 0.9f  };
}

class Renderer
{
public:
    explicit Renderer(Draw& draw) : m_draw(draw) {}

    // Draws one frame of ESP from an already-captured snapshot.
    void Render(Overlay& overlay, const Snapshot& snapshot, float fps);

private:
    // Screen-space extents of a player, fitted to the bones when they are valid.
    bool ComputeBox(const Snapshot& snapshot, const Player& player, int w, int h,
                    float& outX, float& outY, float& outW, float& outH) const;

    void DrawSkeleton(const Snapshot& snapshot, const Player& player, int w, int h);
    void DrawHealthBar(const Player& player, float boxX, float boxY, float boxH);

    Draw& m_draw;
};

// UTF-8 to UTF-16 for the sanitized player names coming out of the game.
std::wstring Widen(const std::string& input);
