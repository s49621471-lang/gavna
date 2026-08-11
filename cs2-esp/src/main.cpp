#include "config.h"
#include "draw.h"
#include "memory.h"
#include "menu.h"
#include "offsets.h"
#include "overlay.h"
#include "renderer.h"
#include "sdk.h"

#include <windows.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>

Config g_config;

namespace
{
    constexpr wchar_t kProcessName[] = L"cs2.exe";
    constexpr wchar_t kWindowClass[] = L"SDL_app";
    constexpr wchar_t kWindowTitle[] = L"Counter-Strike 2";

    std::atomic<bool> g_running{ true };

    Snapshot   g_snapshot;
    std::mutex g_snapshotMutex;

    // Rising-edge detection so a held key toggles once.
    bool KeyPressed(int vk)
    {
        static bool held[256] = {};

        const bool down = (GetAsyncKeyState(vk) & 0x8000) != 0;
        const bool edge = down && !held[vk];

        held[vk] = down;
        return edge;
    }

    std::wstring ExecutableDirectory()
    {
        wchar_t path[MAX_PATH]{};

        if (GetModuleFileNameW(nullptr, path, MAX_PATH) == 0)
            return {};

        std::wstring full(path);
        const auto slash = full.find_last_of(L"\\/");

        return slash == std::wstring::npos ? std::wstring{} : full.substr(0, slash);
    }

    void EnableDpiAwareness()
    {
        // Per-monitor v2 keeps the overlay pixel-aligned on scaled displays. The
        // handle is resolved by value rather than by SDK macro so the build does
        // not depend on the WINVER the toolchain happens to default to.
        using SetContextFn = BOOL(WINAPI*)(HANDLE);

        constexpr INT_PTR kPerMonitorAwareV2 = -4;

        if (HMODULE user32 = GetModuleHandleW(L"user32.dll"))
        {
            auto setContext = reinterpret_cast<SetContextFn>(
                reinterpret_cast<void*>(GetProcAddress(user32, "SetProcessDpiAwarenessContext")));

            if (setContext != nullptr &&
                setContext(reinterpret_cast<HANDLE>(kPerMonitorAwareV2)))
                return;
        }

        SetProcessDPIAware();
    }

    HWND FindGameWindow()
    {
        if (HWND hwnd = FindWindowW(kWindowClass, kWindowTitle))
            return hwnd;

        return FindWindowW(nullptr, kWindowTitle);
    }

    // Producer thread: keeps a fresh snapshot ready so the render loop never
    // blocks on ReadProcessMemory.
    void MemoryLoop(Memory* memory, Game* game)
    {
        Snapshot local;

        const auto interval = std::chrono::microseconds(
            1000000 / (g_config.updateRateHz > 0 ? g_config.updateRateHz : 144));

        while (g_running.load(std::memory_order_relaxed))
        {
            const auto start = std::chrono::steady_clock::now();

            if (!memory->IsAlive())
            {
                g_running = false;
                break;
            }

            game->BuildSnapshot(*memory, local);

            {
                std::lock_guard<std::mutex> lock(g_snapshotMutex);
                g_snapshot = local;
            }

            const auto elapsed = std::chrono::steady_clock::now() - start;

            if (elapsed < interval)
                std::this_thread::sleep_for(interval - elapsed);
        }
    }
}

int wmain()
{
    EnableDpiAwareness();

    wprintf(L"gavna cs2 esp\n");
    wprintf(L"  INS  menu       DEL  toggle esp       END  exit\n\n");

    const std::wstring exeDir = ExecutableDirectory();

    if (g_offsets.LoadFromFile(exeDir + L"\\config\\offsets.ini") ||
        g_offsets.LoadFromFile(exeDir + L"\\offsets.ini"))
        wprintf(L"[offsets] loaded from ini\n");
    else
        wprintf(L"[offsets] no ini found, using built-in defaults\n");

    Memory memory;

    wprintf(L"[attach] waiting for cs2.exe\n");

    while (g_running && !memory.Attach(kProcessName))
    {
        if ((GetAsyncKeyState(VK_END) & 0x8000) != 0)
            return 0;

        Sleep(500);
    }

    wprintf(L"[attach] pid %lu\n", memory.Pid());

    Game game;

    while (g_running && !game.Resolve(memory))
    {
        wprintf(L"[modules] waiting for client.dll\n");
        Sleep(500);
    }

    wprintf(L"[modules] client.dll at 0x%llX\n", static_cast<unsigned long long>(game.ClientBase()));

    HWND gameWindow = nullptr;

    while (g_running && (gameWindow = FindGameWindow()) == nullptr)
    {
        wprintf(L"[window] waiting for the game window\n");
        Sleep(500);
    }

    Overlay overlay;

    if (!overlay.Create(L"gavna"))
    {
        wprintf(L"[overlay] creation failed\n");
        return 1;
    }

    Draw draw;

    if (!draw.Init(overlay))
    {
        wprintf(L"[draw] init failed\n");
        overlay.Destroy();
        return 1;
    }

    draw.SetFontSize(g_config.fontSize);

    Renderer renderer(draw);
    Menu     menu(draw);

    wprintf(L"[ready] drawing — press INS for the menu\n");

    std::thread worker(MemoryLoop, &memory, &game);

    Snapshot frame;
    float    fps       = 0.f;
    auto     lastFrame = std::chrono::steady_clock::now();

    while (g_running)
    {
        if (!overlay.PumpMessages())
            break;

        if (KeyPressed(VK_INSERT))
        {
            menu.Toggle();
            overlay.SetInteractive(menu.IsOpen(), gameWindow);
        }

        if (menu.IsOpen() && KeyPressed(VK_ESCAPE))
        {
            menu.Close();
            overlay.SetInteractive(false, gameWindow);
        }

        if (KeyPressed(VK_DELETE))
        {
            g_config.enabled = !g_config.enabled;
            wprintf(L"[esp] %s\n", g_config.enabled ? L"on" : L"off");
        }

        if (KeyPressed(VK_END))
        {
            wprintf(L"[exit] shutting down\n");
            g_running = false;
            break;
        }

        if (!IsWindow(gameWindow))
        {
            gameWindow = FindGameWindow();

            if (gameWindow == nullptr)
            {
                Sleep(200);
                continue;
            }
        }

        if (!overlay.TrackTarget(gameWindow))
        {
            Sleep(100);
            continue;
        }

        menu.Update(overlay);

        {
            std::lock_guard<std::mutex> lock(g_snapshotMutex);
            frame = g_snapshot;
        }

        const auto now = std::chrono::steady_clock::now();
        const float delta = std::chrono::duration<float>(now - lastFrame).count();
        lastFrame = now;

        if (delta > 0.f)
            fps = fps * 0.9f + (1.f / delta) * 0.1f;   // smoothed, avoids a jittery counter

        if (overlay.BeginFrame())
        {
            renderer.Render(overlay, frame, fps);
            menu.Render(overlay);
            overlay.EndFrame();
        }
    }

    g_running = false;

    if (worker.joinable())
        worker.join();

    overlay.SetInteractive(false, gameWindow);
    draw.Shutdown();
    overlay.Destroy();

    wprintf(L"[exit] done\n");
    return 0;
}
