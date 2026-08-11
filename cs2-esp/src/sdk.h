#pragma once

#include "memory.h"
#include "vec.h"

#include <array>
#include <cstdint>
#include <string>
#include <vector>

// Bone indices in the CS2 player skeleton. Kept small on purpose: only the
// joints the skeleton renderer actually connects.
enum Bone : int
{
    BONE_PELVIS       = 0,
    BONE_SPINE_2      = 2,
    BONE_SPINE_1      = 4,
    BONE_NECK         = 5,
    BONE_HEAD         = 6,
    BONE_ARM_UPPER_L  = 8,
    BONE_ARM_LOWER_L  = 9,
    BONE_HAND_L       = 10,
    BONE_ARM_UPPER_R  = 13,
    BONE_ARM_LOWER_R  = 14,
    BONE_HAND_R       = 15,
    BONE_LEG_UPPER_L  = 22,
    BONE_LEG_LOWER_L  = 23,
    BONE_ANKLE_L      = 24,
    BONE_LEG_UPPER_R  = 25,
    BONE_LEG_LOWER_R  = 26,
    BONE_ANKLE_R      = 27,

    BONE_COUNT        = 30
};

struct BonePair
{
    int from;
    int to;
};

// Connection table the skeleton pass walks.
inline constexpr BonePair kSkeleton[] = {
    { BONE_HEAD,        BONE_NECK        },
    { BONE_NECK,        BONE_SPINE_1     },
    { BONE_SPINE_1,     BONE_SPINE_2     },
    { BONE_SPINE_2,     BONE_PELVIS      },

    { BONE_NECK,        BONE_ARM_UPPER_L },
    { BONE_ARM_UPPER_L, BONE_ARM_LOWER_L },
    { BONE_ARM_LOWER_L, BONE_HAND_L      },

    { BONE_NECK,        BONE_ARM_UPPER_R },
    { BONE_ARM_UPPER_R, BONE_ARM_LOWER_R },
    { BONE_ARM_LOWER_R, BONE_HAND_R      },

    { BONE_PELVIS,      BONE_LEG_UPPER_L },
    { BONE_LEG_UPPER_L, BONE_LEG_LOWER_L },
    { BONE_LEG_LOWER_L, BONE_ANKLE_L     },

    { BONE_PELVIS,      BONE_LEG_UPPER_R },
    { BONE_LEG_UPPER_R, BONE_LEG_LOWER_R },
    { BONE_LEG_LOWER_R, BONE_ANKLE_R     },
};

struct Player
{
    std::string                  name;
    int                          health   = 0;
    int                          armor    = 0;
    int                          team     = 0;
    bool                         scoped   = false;
    float                        distance = 0.f;

    Vec3                         origin;                 // feet
    Vec3                         head;                   // head bone, falls back to origin + 72
    bool                         hasBones = false;
    std::array<Vec3, BONE_COUNT> bones{};
};

// One consistent frame of game state. The view matrix is captured alongside the
// positions so projection never mixes data from two different ticks.
struct Snapshot
{
    ViewMatrix          viewMatrix;
    std::vector<Player> players;
    int                 localTeam   = 0;
    Vec3                localOrigin;
    bool                valid       = false;
};

class Game
{
public:
    // Resolves client.dll. Safe to call repeatedly; re-resolves after a restart.
    bool Resolve(Memory& memory);

    uintptr_t ClientBase() const { return m_client; }

    // Walks the entity list and fills out a fresh snapshot.
    void BuildSnapshot(Memory& memory, Snapshot& out) const;

private:
    // Converts a CHandle into the entity pointer it refers to.
    uintptr_t ResolveHandle(Memory& memory, uintptr_t entityList, uint32_t handle) const;

    uintptr_t m_client = 0;
};
