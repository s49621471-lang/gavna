#pragma once

#include <cstdint>
#include <string>

// Every address CS2 moves on an update lives here and nowhere else.
// The compiled-in values are the fallback; config/offsets.ini overrides them at
// startup so a game patch only costs an ini edit, not a rebuild.
struct Offsets
{
    // client.dll signatures
    uintptr_t dwEntityList            = 0x1A0F0D8;
    uintptr_t dwLocalPlayerController = 0x1A31F68;
    uintptr_t dwLocalPlayerPawn       = 0x1948E58;
    uintptr_t dwViewMatrix            = 0x1A2A5A0;
    uintptr_t dwPlantedC4             = 0x1A32748;

    // CBasePlayerController
    uintptr_t m_hPlayerPawn             = 0x7EC;
    uintptr_t m_sSanitizedPlayerName    = 0x760;
    uintptr_t m_bPawnIsAlive            = 0x7E4;

    // C_BaseEntity / C_CSPlayerPawn
    uintptr_t m_iHealth          = 0x344;
    uintptr_t m_iTeamNum         = 0x3E3;
    uintptr_t m_lifeState        = 0x348;
    uintptr_t m_ArmorValue       = 0x1650;
    uintptr_t m_vOldOrigin       = 0x1324;
    uintptr_t m_pGameSceneNode   = 0x328;
    uintptr_t m_bIsScoped        = 0x2400;
    uintptr_t m_pClippingWeapon  = 0x13C0;

    // CGameSceneNode
    uintptr_t m_dwBoneArray      = 0x1F0;
    uintptr_t m_bDormant         = 0xEF;
    uintptr_t m_vecAbsOrigin     = 0xD0;

    // CSkeletonInstance bone stride: position + quaternion + scale.
    uintptr_t boneStride         = 0x20;

    // Entity list chunking. Source 2 stores identities in 512-entry chunks.
    uintptr_t chunkStride        = 0x8;
    uintptr_t chunkBase          = 0x10;
    uintptr_t entryStride        = 120;

    // Reads config/offsets.ini next to the executable when present.
    // Missing file or missing keys simply leave the defaults in place.
    bool LoadFromFile(const std::wstring& path);
};

extern Offsets g_offsets;
