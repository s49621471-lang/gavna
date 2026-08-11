# cs2-esp

External ESP for Counter-Strike 2. Read-only memory access from a separate
process, drawn on a click-through DirectComposition overlay. No injection, no
hooks, no third-party libraries — Windows SDK only.

## What it draws

- Fitted bounding boxes (built from the projected bone matrix, so they follow
  crouch, lean and death animations instead of guessing a capsule)
- Skeletons
- Health bars with numeric readout under 100 HP
- Armor strip under the box
- Player names (sanitized name from the controller)
- Distance in metres
- Optional snaplines from the bottom of the screen
- Frame counter and target count

## Layout

```
cs2-esp/
├── CMakeLists.txt
├── build.bat              one-shot configure + build
├── config/offsets.ini     every address that moves on a game patch
└── src/
    ├── main.cpp           attach, hotkeys, producer/consumer loop
    ├── memory.h/.cpp      process attach, module lookup, RPM wrappers
    ├── offsets.h/.cpp     offset table + ini override loader
    ├── sdk.h/.cpp         entity list walk, bone matrix, snapshot building
    ├── vec.h              vectors, view matrix, world-to-screen
    ├── overlay.h/.cpp     DComp window, D3D11 device, D2D context
    ├── renderer.h/.cpp    all drawing
    └── config.h           runtime toggles
```

## Build

Requires Visual Studio 2022 (Desktop C++ workload) and CMake 3.20+.

```bat
build.bat
```

Or by hand:

```bat
cmake -S . -B build -A x64
cmake --build build --config Release
```

Output lands in `build\Release\cs2-esp.exe` with `config\offsets.ini` copied
next to it.

## Run

1. Set CS2 to **Fullscreen Windowed**. Exclusive fullscreen takes over the
   swapchain presentation path and nothing composites on top of it.
2. Launch `cs2-esp.exe` as administrator — CS2 runs elevated, and `OpenProcess`
   with `PROCESS_VM_READ` needs matching rights.
3. The console reports attach state, module base, and offset source.

### Hotkeys

| Key | Action |
|-----|--------|
| `INS` | toggle ESP drawing |
| `DEL` | toggle skeletons |
| `HOME` | toggle snaplines |
| `PGUP` | toggle team check |
| `END` | exit |

## Offsets

`config/offsets.ini` overrides the compiled-in defaults at startup. After a CS2
update, run any offset dumper and paste the new values in — no rebuild. Keys
you leave out keep their built-in value, and section headers are ignored, so
you can paste in whatever grouping your dumper emits.

The defaults in `src/offsets.h` are a snapshot; if boxes appear in the wrong
place or no players resolve at all, the offsets are stale, not the code.

## How it works

**Attach.** `Toolhelp32` finds `cs2.exe` and `client.dll`, then everything is
`ReadProcessMemory` against a `PROCESS_VM_READ` handle. Nothing is written to
the target and nothing is loaded into it.

**Entity list.** Source 2 stores entity identities in chunks of 512. A given
index resolves as:

```
chunk  = [entityList + 0x8 * (index >> 9) + 0x10]
entity = [chunk + 120 * (index & 0x1FF)]
```

Controllers live at indices 1–64. Each controller holds a `CHandle` to its pawn
in `m_hPlayerPawn`; the handle's low 15 bits go back through the same chunk
lookup to reach the pawn. Health, team, origin and the scene node come off the
pawn; the name comes off the controller.

**Bones.** `m_pGameSceneNode → m_dwBoneArray` points at an array of
`{ float pos[3]; float quat[4]; float scale; }` — 32 bytes per bone. The whole
array is pulled in a single read per player rather than one call per joint,
which keeps the poll loop cheap even with a full server.

**Projection.** The view matrix is read as a row-major 4×4 in the same pass
that reads positions, so projection never mixes two ticks:

```
w = m[3] · p
screen.x = (width  / 2) * (1 + (m[0] · p) / w)
screen.y = (height / 2) * (1 - (m[1] · p) / w)
```

`w < 0.01` means the point is behind the camera and gets dropped.

**Threading.** A producer thread polls memory at `updateRateHz` and publishes
completed snapshots under a mutex. The render thread copies the latest snapshot
and draws it. `ReadProcessMemory` stalls never show up as frame hitches.

**Overlay.** `WS_EX_NOREDIRECTIONBITMAP` plus a composition swapchain with
`DXGI_ALPHA_MODE_PREMULTIPLIED` gives real per-pixel alpha — no colour-key
transparency, no black fringing on antialiased lines. `WS_EX_TRANSPARENT` and
an `HTTRANSPARENT` hit-test make it click-through, and the window tracks the
game's client rect every frame so alt-tab, moves and resolution changes stay
aligned.

## Tuning

`src/config.h` holds the defaults — feature toggles, `maxDistance` (units,
0 disables the cull), box and skeleton line widths, font size, and the memory
poll rate.

## Known behaviour

- Dormant entities are skipped. Their last-known position is stale, so drawing
  them puts boxes on players who left the PVS.
- No visibility check. Adding one means a ray trace against the BSP or reading
  the visibility flags off the pawn — currently everything in range draws.
- Bone-fitted boxes briefly fall back to the origin column while a player's
  bone matrix is mid-update; the fallback is the head-to-feet estimate.
