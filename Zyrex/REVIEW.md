# Static review — Zyrex dumper

Everything below is from reading the code and inspecting the built artifact.
Nothing here was executed on a device: this container has no Android hardware
and no arm64 emulator, and the target is arm64-only. Treat "verified" as
"verified statically" throughout.

---

## Build verification

| Check | Result |
|---|---|
| APK signature | verifies — v2 `true`, v3 `true` (v1 not required at minSdk 24) |
| Manifest parses | `com.skullcapstudios.bps`, versionCode 350, versionName `1.00f3`, minSdk 24, targetSdk 35 |
| App label resolves from `resources.arsc` | yes, across all locales |
| Entries | 1622 → 1624 |
| Genuinely new entries | `classes9.dex`, `lib/arm64-v8a/libzyrexdump.so` — nothing else |
| Entries removed | none outside `res/` |
| `res/` churn | 837 added / 837 removed — 1:1, apktool's normal resource re-encode, no net loss |
| `assets/` | count identical — `global-metadata.dat` and all Unity data untouched |
| Original native libs | all 6 present and unmodified |
| `classes9.dex` | valid dex `037`, contains `Lcom/zyrex/dumper/Zyrex;`, `nativeStart`, `zyrexdump` |
| `classes7.dex` | contains `UnityPlayerActivity` **and** a reference to `Lcom/zyrex/dumper/Zyrex;` — the injection survived the smali → dex round trip |
| `libzyrexdump.so` | aarch64 ELF (machine 183), exports `Java_com_zyrex_dumper_Zyrex_nativeStart` |
| Native deps | `liblog`, `libm`, `libdl`, `libc` only — libc++ statically linked |
| Relocations | no `TEXTREL` |
| Compiler | clean at `-Wall -Wextra`, no warnings |

Dex numbering is contiguous (`classes.dex` … `classes9.dex`), which is what
ART requires to load them all without a loader change.

---

## Crash paths reviewed

### Startup cannot be blocked
`JNI_OnLoad` only returns the version. All work is on a detached pthread that
sleeps 20 s before touching anything. `Zyrex.init` is called as the final
statement of `onCreate`, after `UnityPlayer` is constructed and focused.

### The Java side cannot throw into the game
Every statement in `Zyrex.init` sits inside `try/catch (Throwable)` —
`loadLibrary`, directory creation, the JNI call, the toast, the status
watcher. Any failure logs and returns; the game continues unmodified. This
matters more than usual because an exception escaping `onCreate` is an
immediate force-close.

### Double invocation
Native `std::atomic` CAS plus a `synchronized` Java flag. A second call
returns without spawning a second thread.

### Missing symbols
The resolver splits required from optional. A missing optional symbol costs
one column of output. A missing *required* symbol aborts before any call is
made — there is no path where a null function pointer gets invoked.

### Bad pointers from tampered metadata
`MethodInfo::methodPointer` is read at offset 0 (stable across every IL2CPP
revision this game could have been built with) and only trusted if it lands
inside the mapped `libil2cpp.so` range read from `/proc/self/maps`. Anything
else records RVA 0.

### Runaway iteration
Every runtime iterator is bounded: assemblies 1024, fields 4096, methods
8192, properties 2048. A corrupted iterator terminates the loop instead of
spinning forever.

### Faults during the walk
`SIGSEGV`/`SIGBUS` handlers wrap each class walk in `sigsetjmp`. A fault
skips that class, increments a counter reported in `00_summary.txt`, and the
dump continues. The handler only acts on faults from the dumper's own thread
(`pthread_equal`); everything else is forwarded to the previously installed
handler, so the game's AppLovin crash reporter keeps working. Handlers are
restored when the dump finishes. A 64 KiB `sigaltstack` is installed so a
fault raised on an unusable stack is still catchable.

### Allocator boundaries
`il2cpp_type_get_name` returns memory from the IL2CPP allocator. It is freed
through `il2cpp_free`, never libc `free`. If `il2cpp_free` were unavailable
the buffer is leaked deliberately rather than freed across the boundary.

### Context lifetime
The completion watcher holds the **application** context, not the Activity,
so a 10-minute poll cannot pin a destroyed Activity.

---

## Residual risks

These are real and I am not going to pretend otherwise.

**1. `il2cpp_class_get_fields` triggers class initialization.**
This is the one operation here with genuine side effects. IL2CPP runs
`Class::Init` internally, which can execute static constructors. Across
~30–60k classes that means arbitrary game code runs at dump time, in an order
the game never intended. The fault guard catches memory faults from this but
cannot undo a static constructor's side effects. This is inherent to runtime
dumping, not something the design can engineer away. Practically: expect the
dump to take a while and expect a non-zero fault count.

**2. `siglongjmp` skips C++ destructors.**
Unwinding out of a fault leaves the `std::string` members of the in-flight
class job unfreed. Bounded and small — a few hundred bytes per absorbed fault
— but it is a leak, and it scales with fault count.

**3. stdio state after an absorbed fault.**
A fault raised mid-`fprintf` leaves that `FILE`'s internal lock count
unbalanced. Bionic's stdio lock is owner-tracked, and only the dumper thread
ever writes these streams, so this should not deadlock — but "should not" is
doing work in that sentence, and it is untested.

**4. The 20 s delay is a guess.**
It is tuned for "metadata registration is complete by then", not measured. On
a slow device with a long first-scene load it could still be early. The
symptom would be a partial dump or a raised fault count, not a crash, because
`resolve_api` and the domain wait both poll with their own timeouts.

**5. Nothing has been executed.**
No device, no arm64 emulator. Compilation, linking, symbol export, dex
integrity, APK structure and signature are all verified. Runtime behaviour is
not. The first real test is the first launch on your phone.

**6. Resource re-encode.**
apktool round-tripped `resources.arsc` through aapt2. The 837/837 file swap
is expected and the table resolves (the label reads back correctly across all
locales), but a full resource diff of a 300 MB commercial APK is not
something I can claim to have done exhaustively.

---

## If it fails on launch

The dumper is inert for the first 20 seconds, so a crash *at* launch points
at the repack, not the dumper. A crash roughly 20 s in points at the dump
walk.

```bash
adb logcat -s Zyrex:V DEBUG:V AndroidRuntime:E
```

`Zyrex` tag traces every stage: library load, `nativeStart` return code,
il2cpp mapping, base/size, domain attach, per-image class counts, final
totals. `_status.txt` in the output folder holds the last stage reached, so
even without logcat the failure point is recoverable.
