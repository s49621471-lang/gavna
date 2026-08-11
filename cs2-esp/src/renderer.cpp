#include "renderer.h"

#include "config.h"

#include <algorithm>
#include <cstdio>
#include <cwchar>
#include <initializer_list>

std::wstring Widen(const std::string& input)
{
    if (input.empty())
        return {};

    const int needed = MultiByteToWideChar(CP_UTF8, 0, input.c_str(),
                                           static_cast<int>(input.size()), nullptr, 0);

    if (needed <= 0)
        return {};

    std::wstring result(static_cast<size_t>(needed), L'\0');

    MultiByteToWideChar(CP_UTF8, 0, input.c_str(), static_cast<int>(input.size()),
                        result.data(), needed);

    return result;
}

namespace
{
    Color HealthColor(int health)
    {
        const float t = std::clamp(static_cast<float>(health) / 100.f, 0.f, 1.f);
        return { 1.f - t, t, 0.15f, 1.f };
    }

    Color TeamColor(int team)
    {
        return team == 2 ? Colors::kTerrorist : Colors::kCounterTerr;
    }
}

bool Renderer::ComputeBox(const Snapshot& snapshot, const Player& player, int w, int h,
                          float& outX, float& outY, float& outW, float& outH) const
{
    if (player.hasBones)
    {
        float minX =  1e9f, minY =  1e9f;
        float maxX = -1e9f, maxY = -1e9f;
        int   projected = 0;

        for (const BonePair& pair : kSkeleton)
        {
            for (const int index : { pair.from, pair.to })
            {
                const Vec3& bone = player.bones[index];

                if (bone.Length() < 0.01f)
                    continue;

                Vec2 screen;

                if (!WorldToScreen(snapshot.viewMatrix, bone, w, h, screen))
                    continue;

                minX = std::min(minX, screen.x);
                minY = std::min(minY, screen.y);
                maxX = std::max(maxX, screen.x);
                maxY = std::max(maxY, screen.y);
                ++projected;
            }
        }

        if (projected >= 4 && maxY > minY)
        {
            // The head bone sits at eye level, so lift the top edge over the skull
            // and give the sides a little breathing room.
            const float height = maxY - minY;
            const float padY   = height * 0.12f;
            const float padX   = std::max(2.f, height * 0.06f);

            outX = minX - padX;
            outY = minY - padY;
            outW = (maxX - minX) + padX * 2.f;
            outH = height + padY;

            return true;
        }
    }

    // Bones unavailable: fall back to the origin-to-head column.
    Vec2 feet;
    Vec2 head;

    if (!WorldToScreen(snapshot.viewMatrix, player.origin, w, h, feet))
        return false;

    if (!WorldToScreen(snapshot.viewMatrix, player.head + Vec3{ 0.f, 0.f, 8.f }, w, h, head))
        return false;

    const float height = feet.y - head.y;

    if (height <= 1.f)
        return false;

    const float width = height * 0.42f;

    outX = feet.x - width * 0.5f;
    outY = head.y;
    outW = width;
    outH = height;

    return true;
}

void Renderer::DrawSkeleton(const Snapshot& snapshot, const Player& player, int w, int h)
{
    if (!player.hasBones)
        return;

    for (const BonePair& pair : kSkeleton)
    {
        const Vec3& from = player.bones[pair.from];
        const Vec3& to   = player.bones[pair.to];

        if (from.Length() < 0.01f || to.Length() < 0.01f)
            continue;

        Vec2 a;
        Vec2 b;

        if (!WorldToScreen(snapshot.viewMatrix, from, w, h, a))
            continue;

        if (!WorldToScreen(snapshot.viewMatrix, to, w, h, b))
            continue;

        m_draw.Line(a, b, Colors::kSkeleton, g_config.skeletonWidth);
    }
}

void Renderer::DrawHealthBar(const Player& player, float boxX, float boxY, float boxH)
{
    constexpr float kBarWidth = 3.f;

    const float barX   = boxX - kBarWidth - 5.f;
    const float ratio  = std::clamp(static_cast<float>(player.health) / 100.f, 0.f, 1.f);
    const float filled = boxH * ratio;

    // Backdrop first so a nearly-empty bar still reads against bright geometry.
    m_draw.FilledRect(barX - 1.f, boxY - 1.f, kBarWidth + 2.f, boxH + 2.f, Colors::kOutline);
    m_draw.FilledRect(barX, boxY + (boxH - filled), kBarWidth, filled, HealthColor(player.health));

    if (player.health < 100)
    {
        wchar_t buffer[8]{};
        swprintf(buffer, ARRAYSIZE(buffer), L"%d", player.health);
        m_draw.Text(buffer, barX - 3.f, boxY + (boxH - filled) - m_draw.FontSize() * 0.5f,
                    Colors::kText, TextAlign::Right);
    }
}

void Renderer::Render(Overlay& overlay, const Snapshot& snapshot, float fps)
{
    if (!snapshot.valid)
        return;

    const int w = overlay.Width();
    const int h = overlay.Height();

    if (g_config.showFps)
    {
        wchar_t header[96]{};
        swprintf(header, ARRAYSIZE(header), L"gavna  %.0f fps  %u targets%s",
                 fps, static_cast<unsigned>(snapshot.players.size()),
                 g_config.enabled ? L"" : L"  [paused]");
        m_draw.Text(header, 12.f, 10.f, Colors::kText, TextAlign::Left);
    }

    if (!g_config.enabled)
        return;

    for (const Player& player : snapshot.players)
    {
        float x = 0.f, y = 0.f, boxW = 0.f, boxH = 0.f;

        if (!ComputeBox(snapshot, player, w, h, x, y, boxW, boxH))
            continue;

        // Fully off-screen boxes cost nothing to skip and keep the batch small.
        if (x + boxW < 0.f || y + boxH < 0.f || x > static_cast<float>(w) || y > static_cast<float>(h))
            continue;

        const Color team = TeamColor(player.team);

        if (g_config.drawSnaplines)
        {
            const Vec2 from{ static_cast<float>(w) * 0.5f, static_cast<float>(h) };
            const Vec2 to{ x + boxW * 0.5f, y + boxH };
            m_draw.Line(from, to, Colors::kSnapline, 1.f);
        }

        if (g_config.drawSkeleton)
            DrawSkeleton(snapshot, player, w, h);

        if (g_config.drawBox)
        {
            // Dark stroke on both sides of the coloured one: readable on any map.
            m_draw.Rect(x - 1.f, y - 1.f, boxW + 2.f, boxH + 2.f, Colors::kOutline, 1.f);
            m_draw.Rect(x + 1.f, y + 1.f, boxW - 2.f, boxH - 2.f, Colors::kOutline, 1.f);
            m_draw.Rect(x, y, boxW, boxH, team, g_config.boxThickness);
        }

        if (g_config.drawHealthBar)
            DrawHealthBar(player, x, y, boxH);

        if (g_config.drawName)
            m_draw.Text(Widen(player.name), x + boxW * 0.5f, y - m_draw.FontSize() - 5.f,
                        team, TextAlign::Center);

        float below = y + boxH + 3.f;

        if (player.armor > 0)
        {
            const float armorRatio = std::clamp(static_cast<float>(player.armor) / 100.f, 0.f, 1.f);
            m_draw.FilledRect(x, y + boxH + 2.f, boxW * armorRatio, 2.f, Colors::kArmor);
            below += 3.f;
        }

        if (g_config.drawDistance)
        {
            wchar_t buffer[32]{};
            swprintf(buffer, ARRAYSIZE(buffer), L"%.0f m", player.distance);
            m_draw.Text(buffer, x + boxW * 0.5f, below, Colors::kText, TextAlign::Center);
            below += m_draw.FontSize() + 1.f;
        }

        if (g_config.drawWeapon && player.scoped)
            m_draw.Text(L"scoped", x + boxW * 0.5f, below, Colors::kText, TextAlign::Center);
    }
}
