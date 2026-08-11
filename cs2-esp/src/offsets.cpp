#include "offsets.h"

#include <fstream>
#include <string>

Offsets g_offsets;

namespace
{
    struct Binding
    {
        const char*           key;
        uintptr_t Offsets::*  member;
    };

    const Binding kBindings[] = {
        { "dwEntityList",             &Offsets::dwEntityList            },
        { "dwLocalPlayerController",  &Offsets::dwLocalPlayerController  },
        { "dwLocalPlayerPawn",        &Offsets::dwLocalPlayerPawn        },
        { "dwViewMatrix",             &Offsets::dwViewMatrix             },
        { "dwPlantedC4",              &Offsets::dwPlantedC4              },
        { "m_hPlayerPawn",            &Offsets::m_hPlayerPawn            },
        { "m_sSanitizedPlayerName",   &Offsets::m_sSanitizedPlayerName   },
        { "m_bPawnIsAlive",           &Offsets::m_bPawnIsAlive           },
        { "m_iHealth",                &Offsets::m_iHealth                },
        { "m_iTeamNum",               &Offsets::m_iTeamNum               },
        { "m_lifeState",              &Offsets::m_lifeState              },
        { "m_ArmorValue",             &Offsets::m_ArmorValue             },
        { "m_vOldOrigin",             &Offsets::m_vOldOrigin             },
        { "m_pGameSceneNode",         &Offsets::m_pGameSceneNode         },
        { "m_bIsScoped",              &Offsets::m_bIsScoped              },
        { "m_pClippingWeapon",        &Offsets::m_pClippingWeapon        },
        { "m_dwBoneArray",            &Offsets::m_dwBoneArray            },
        { "m_bDormant",               &Offsets::m_bDormant               },
        { "m_vecAbsOrigin",           &Offsets::m_vecAbsOrigin           },
        { "boneStride",               &Offsets::boneStride               },
        { "chunkStride",              &Offsets::chunkStride              },
        { "chunkBase",                &Offsets::chunkBase                },
        { "entryStride",              &Offsets::entryStride              },
    };

    std::string Trim(const std::string& input)
    {
        const auto first = input.find_first_not_of(" \t\r\n");

        if (first == std::string::npos)
            return {};

        const auto last = input.find_last_not_of(" \t\r\n");
        return input.substr(first, last - first + 1);
    }

    bool ParseValue(const std::string& text, uintptr_t& out)
    {
        if (text.empty())
            return false;

        try
        {
            const int base = (text.size() > 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) ? 16 : 10;
            size_t consumed = 0;
            const unsigned long long parsed = std::stoull(text, &consumed, base);

            if (consumed == 0)
                return false;

            out = static_cast<uintptr_t>(parsed);
            return true;
        }
        catch (...)
        {
            return false;
        }
    }
}

bool Offsets::LoadFromFile(const std::wstring& path)
{
    std::ifstream file(path);

    if (!file.is_open())
        return false;

    std::string line;

    while (std::getline(file, line))
    {
        // Strip comments before parsing so trailing notes are legal.
        const auto comment = line.find_first_of(";#");

        if (comment != std::string::npos)
            line.erase(comment);

        const auto equals = line.find('=');

        if (equals == std::string::npos)
            continue;

        const std::string key   = Trim(line.substr(0, equals));
        const std::string value = Trim(line.substr(equals + 1));

        uintptr_t parsed = 0;

        if (!ParseValue(value, parsed))
            continue;

        for (const Binding& binding : kBindings)
        {
            if (key == binding.key)
            {
                this->*binding.member = parsed;
                break;
            }
        }
    }

    return true;
}
