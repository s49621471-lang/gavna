#include "sdk.h"

#include "config.h"
#include "offsets.h"

#include <cstring>
#include <vector>

namespace
{
    constexpr int      kMaxControllers = 64;
    constexpr uint32_t kInvalidHandle  = 0xFFFFFFFF;

    // Source units to metres.
    constexpr float kUnitsToMetres = 0.01905f;
}

bool Game::Resolve(Memory& memory)
{
    m_client = memory.GetModuleBase(L"client.dll");
    return m_client != 0;
}

uintptr_t Game::ResolveHandle(Memory& memory, uintptr_t entityList, uint32_t handle) const
{
    if (entityList == 0 || handle == kInvalidHandle)
        return 0;

    const uint32_t index = handle & 0x7FFF;

    const uintptr_t chunk = memory.Read<uintptr_t>(
        entityList + g_offsets.chunkStride * (index >> 9) + g_offsets.chunkBase);

    if (chunk == 0)
        return 0;

    return memory.Read<uintptr_t>(chunk + g_offsets.entryStride * (index & 0x1FF));
}

void Game::BuildSnapshot(Memory& memory, Snapshot& out) const
{
    out.players.clear();
    out.valid = false;

    if (m_client == 0)
        return;

    const uintptr_t entityList = memory.Read<uintptr_t>(m_client + g_offsets.dwEntityList);
    const uintptr_t localPawn  = memory.Read<uintptr_t>(m_client + g_offsets.dwLocalPlayerPawn);

    if (entityList == 0 || localPawn == 0)
        return;

    out.viewMatrix  = memory.Read<ViewMatrix>(m_client + g_offsets.dwViewMatrix);
    out.localTeam   = memory.Read<uint8_t>(localPawn + g_offsets.m_iTeamNum);
    out.localOrigin = memory.Read<Vec3>(localPawn + g_offsets.m_vOldOrigin);

    // One allocation reused for every player's bone matrix.
    std::vector<uint8_t> boneBuffer(static_cast<size_t>(BONE_COUNT) * g_offsets.boneStride);

    for (int i = 1; i <= kMaxControllers; ++i)
    {
        const uintptr_t chunk = memory.Read<uintptr_t>(
            entityList + g_offsets.chunkStride * (static_cast<uint32_t>(i) >> 9) + g_offsets.chunkBase);

        if (chunk == 0)
            continue;

        const uintptr_t controller = memory.Read<uintptr_t>(chunk + g_offsets.entryStride * (i & 0x1FF));

        if (controller == 0)
            continue;

        const uint32_t pawnHandle = memory.Read<uint32_t>(controller + g_offsets.m_hPlayerPawn);
        const uintptr_t pawn      = ResolveHandle(memory, entityList, pawnHandle);

        if (pawn == 0 || pawn == localPawn)
            continue;

        const int health = memory.Read<int>(pawn + g_offsets.m_iHealth);

        if (health <= 0 || health > 100)
            continue;

        if (memory.Read<int>(pawn + g_offsets.m_lifeState) != 0)
            continue;

        const int team = memory.Read<uint8_t>(pawn + g_offsets.m_iTeamNum);

        if (team != 2 && team != 3)
            continue;

        if (g_config.teamCheck && team == out.localTeam)
            continue;

        const uintptr_t sceneNode = memory.Read<uintptr_t>(pawn + g_offsets.m_pGameSceneNode);

        if (sceneNode == 0)
            continue;

        // Dormant entities keep stale positions; drawing them puts boxes on ghosts.
        if (memory.Read<uint8_t>(sceneNode + g_offsets.m_bDormant) != 0)
            continue;

        Player player;
        player.health = health;
        player.team   = team;
        player.armor  = memory.Read<int>(pawn + g_offsets.m_ArmorValue);
        player.scoped = memory.Read<uint8_t>(pawn + g_offsets.m_bIsScoped) != 0;
        player.origin = memory.Read<Vec3>(pawn + g_offsets.m_vOldOrigin);

        const float units = player.origin.DistanceTo(out.localOrigin);

        if (g_config.maxDistance > 0.f && units > g_config.maxDistance)
            continue;

        player.distance = units * kUnitsToMetres;

        player.name = memory.ReadString(controller + g_offsets.m_sSanitizedPlayerName, 32);

        if (player.name.empty())
            player.name = "player";

        // Bone matrix in one read, then unpack the positions we care about.
        const uintptr_t boneArray = memory.Read<uintptr_t>(sceneNode + g_offsets.m_dwBoneArray);

        if (boneArray != 0 && memory.ReadRaw(boneArray, boneBuffer.data(), boneBuffer.size()))
        {
            player.hasBones = true;

            for (int b = 0; b < BONE_COUNT; ++b)
            {
                Vec3 bone{};
                std::memcpy(&bone, boneBuffer.data() + static_cast<size_t>(b) * g_offsets.boneStride, sizeof(Vec3));
                player.bones[b] = bone;
            }

            player.head = player.bones[BONE_HEAD];

            // A collapsed skeleton means the matrix was mid-update; fall back.
            if (player.head.Length() < 0.01f)
            {
                player.hasBones = false;
                player.head     = player.origin + Vec3{ 0.f, 0.f, 72.f };
            }
        }
        else
        {
            player.head = player.origin + Vec3{ 0.f, 0.f, 72.f };
        }

        out.players.push_back(std::move(player));
    }

    out.valid = true;
}
