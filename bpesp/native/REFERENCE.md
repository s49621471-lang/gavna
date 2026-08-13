# What the reference implementation does

Notes taken from `libblockpost_internal.so`, an arm64 build of the same cheat
that aims correctly. Recorded because they explain why several approaches here
failed, and what the working shape actually is.

## It hooks the rotation setters, it does not call them

```
Transform.set_rotation hooked: trampoline=%p
Transform.set_eulerAngles hooked: trampoline=%p
failed to hook Transform.set_rotation
```

This is the whole answer to the aimbot. The game sets the camera's rotation
itself, every frame, from its own input handling. Writing that rotation from
outside is therefore pointless — whatever is written is overwritten before the
next frame is drawn, which is exactly what happened here across several
attempts, first against float fields that mirrored the view and then against the
transform directly.

An inline hook on the setter inverts the relationship. The game's own call
arrives at our code with the rotation it intends to apply; the aim-corrected
rotation is substituted and passed to the trampoline. It cannot be overwritten
because it *is* the write, and it runs on whichever thread the game updates
from, so there is no race to lose.

## Rendering rides the frame

```
first eglSwapBuffers hook call: %dx%d
ERROR: ImGui_ImplOpenGL3_CreateDeviceObjects: ...
ProggyClean.ttf, ProjMtx
```

ImGui on GLES3, drawn from inside an `eglSwapBuffers` hook. That is why its menu
looks the way it does, and it is also why its Unity calls are safe: they happen
at a frame boundary rather than from a free-running poll thread.

## Nothing is hardcoded by offset

```
IL2CPP ready: base=%p array_header=0x%x
unexpected IL2CPP array header size: %u
PLH or Controll class is unavailable
required static field is unavailable
```

Even the il2cpp array header size is measured rather than assumed. The only
game-specific constants in the whole library are five obfuscated names:

| name          | role                              | used here |
|---------------|-----------------------------------|-----------|
| `EJOPJMFHJJA` | `PLH` roster array                | yes       |
| `PDPCCKAHCBC` | `Controll` local player reference | yes       |
| `CAIHJKCEBOG` | unidentified                      | no        |
| `GECCHGCLEEO` | unidentified                      | no        |
| `KHAHOLDCONB` | unidentified                      | no        |

Notably absent are the entity field names used here — `KAEOBIMDFOF` for health,
`HBFFGFGNIPL` for position and the rest — so it reaches player state by a route
this build does not, most likely through the model's own transform rather than
the replicated vectors. That would also explain how it gets exact bone
positions.

## Other machinery visible in its imports

- `UnityEngine.PhysicsModule` → `Physics.Linecast`, so only visible enemies are
  targeted.
- `UnityEngine.InputLegacyModule` → `Input.GetTouch`, `Input.touchCount`, so aim
  can be conditioned on what the player is actually touching.
