# Reading a device log

```bash
tools/device-log/analyze.py recorded.log --device device.txt
```

Exit status 0 if every check passed, 1 if any failed. No SDK, no NDK, no Gradle, no
Flutter, no device — Python 3.8 and the standard library. That is the point: the person
who can run this is whoever has the log, and the machine that can run it is any machine.

```
MANUFACTURER=Xiaomi MODEL=23030RAC7Y SDK=35 SUPPORTED_ABIS=arm64-v8a, armeabi-v7a, armeabi
545 UNIQUE events in 26950 lines

[ok  ] engine       Did UNIQUE's own process start and grant itself platform access?
[FAIL] launch       Did every launch reach the guest's own Activity?
         - line 26573: clear.una u0 -> clear.una.MainActivity: SLOT_ALREADY_BOUND
         . 7/10 launches reached the guest's Activity
[FAIL] platform     Did any call go out under the guest's name and get refused?
         - line 9720: RestrictionsManager.getApplicationRestrictions was refused:
                      add `restrictions` to SystemServiceHook.TARGETS and hook it
...
RESULT: FAIL (8 of 10: engine, launch, slots, crash, platform, permissions, providers, ui)
```

---

## Why this exists

A virtualization engine has an unusually large number of ways to be subtly wrong, and
almost all of them look like the app's fault. That is written at the top of the project
README, and the run this tool was built from is what it looks like in practice: three
apps in a row failed to launch and ChatGPT died on its first screen, and every one of
those failures presented as the app misbehaving.

The evidence was all there. UNIQUE's own diagnostics named the cause of each one, in a
log of 26,950 lines, of which 545 were UNIQUE's and about thirty mattered. Nobody was
going to find them by scrolling.

So the reading is mechanical now. Each check is one question a healthy run answers the
same way every time, and a failure names the thing to change rather than the app to
blame.

## What it checks

| Check | The question |
|---|---|
| `engine` | Did UNIQUE start, get platform access, load its native library and install IO redirection? |
| `launch` | Did every `LAUNCH_REQUESTED` reach `BOOTSTRAP_OK` *and* a rewritten transaction? |
| `slots` | Was every `:vappN` slot handed over clean, or did one still hold the last app? |
| `crash` | Did any guest crash — from UNIQUE's record or the platform's, folded to one per crash? |
| `platform` | Did a call go out under the guest's name and get refused, and by which service? |
| `permissions` | Was a permission denied that no user could ever have granted — including one the *host* is blocked from holding? |
| `storage` | Could a guest read its own external storage, and did its expansion files reach the instance? |
| `google` | Was a guest told the phone has no Play services when it has? |
| `native` | Did a native crash follow a library the path redirector patched? |
| `hooks` | Did every shim bind to a real method, or did one bind to nothing? |
| `providers` | Did the guest's ContentProviders publish and resolve? |
| `ui` | Did UNIQUE's own Flutter interface throw? |
| `limits` | Which deliberately-unsupported paths did this run reach? (Never a failure.) |

### The two the sixth run added: `storage` and `native`

Both exist because a run came back in which every check passed and the apps were still
wrong. Six of seven launched, nothing crashed on the main thread, and the games behaved
as though they had been installed badly.

`storage` reads the app's own line rather than UNIQUE's:

```
I Unity: No permission to read external storage. Skipping OBB loading.   (x156)
```

A game that skips its expansion files is a game with no assets, and nothing in UNIQUE's
own diagnostics said so — `PERMISSION_RESULT_RECORDED … granted=false blockedByHost=true`
was there 156 times and was classified as "a runtime permission, so this may be the
user's choice". It was not: since Android 13 the platform auto-denies
`READ_EXTERNAL_STORAGE` to any app targeting 33 or later, so the host can never hold it
and no dialog exists to ask. `blockedByHost` now fails the `permissions` check on its
own, whatever kind of permission it names.

`native` pairs two lines that are six seconds and several hundred lines apart:

```
io_redirect: hooked 22 new slot(s) after loading …/libgrave.so (22 total)
E CRASH: signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x7dd33219f7
E CRASH:   #00 pc 00000000000009f7  <anonymous:0000007dd3321000>
```

A PLT hook is one pointer written into a GOT and is meant to fail as "this library was
not hooked". A code-virtualization protector checks its own relocations and answers a
patched slot by jumping into its own generated code with a corrupt dispatch value —
which lands as an unaligned PC in an anonymous page, in a tombstone that names the
game's engine and never names UNIQUE. The check names the libraries to exclude, last
one first.

### The one the eighth run added: `google`

This one exists because a build shipped a wrong answer and the sixteen checks above all
said the run was fine. The pass before it had decided Play services visibility per app,
from the version meta-data every Play services client declares:

```
GOOGLE_ENVIRONMENT gmsPresent=true gmsVersionCode=263234035 gmsVersionName=26.32.34
GOOGLE_STACK_HIDDEN hidden=true reason=SDK_TOO_OLD gmsVersion=12451000   (x3)
W GooglePlayServicesUtil: com.openai.chatgpt requires Google Play services, but they
    are missing.
```

`12451000` — the same number for 1Tap Cleaner, ChatGPT and a Unity game. It is
`com.google.android.gms.version`, the *minimum* GmsCore version the client accepts, which
Google froze years ago; it cannot tell two client libraries apart, so a rule built on it
told every guest that a phone carrying GmsCore 26.32.34 has no Play services. What the
user saw was a notification asking them to install something they already had.

So the check pairs two facts that are individually unremarkable: **the device has Play
services**, and **a guest was told it does not**. Hiding it from an instance that died of
`Unknown calling package name` is not a failure — that instance earned it. Hiding it from
one that has proved nothing is, and so is the app's own
`requires Google Play services, but they are missing` on a phone that has them.

### The one worth explaining: `platform`

A `SecurityException` inside a guest is almost never the guest's fault. It means a call
reached `system_server` carrying a package name that does not belong to UNIQUE's uid,
and the service it went to is not proxied. One run produced three spellings of that same
sentence:

```
Only system may: get application restrictions for other user/app com.openai.chatgpt
Package com.openai.chatgpt does not belong to 10300
getApplicationLocales: Neither user 10300 nor current process has …
```

Only the first names the guest, so matching on the package name finds one in three. They
are found by *where* they were raised instead — a pid that announced itself as a slot —
and the `IRestrictionsManager$Stub$Proxy` frame in the stack is what turns the finding
from "something was refused" into `add restrictions to SystemServiceHook.TARGETS`.

Whether a service is already hooked is read out of `SystemServiceHook.kt`, and whether a
permission is the user's to grant is read out of `PlatformPermissions.kt`. Copies of
those two tables would drift, and a drifted copy reports a service as unhooked after it
was hooked — which gets the check ignored, which is worse than not having it.

## What it does not do

**It does not look at pixels.** A guest that draws a black screen and logs nothing passes
every check here. `docs/PHYSICAL_DEVICE_TEST.md` step s04 exists for exactly that, and
nothing in this tool replaces someone looking at the phone.

The question it answers is "did anything go wrong that the engine can see", which is
smaller than "does the app work" — and it is the one that can be answered without a
person watching, which is what makes it worth automating.

## Where the log comes from

Any of these; the tool recognises the layout itself.

- **UNIQUE's own export.** *Settings → Advanced → Export diagnostics → Share*. Point the
  tool at `unique.log` from inside the zip. Needs no computer and no `adb`.
- **A log recorder on the phone.** Any app that saves logcat to a file. This is how the
  fixture below was captured, on a phone with no PC anywhere near it.
- **`adb logcat -v threadtime`**, if there is a computer.

`--device` is optional and takes the device description that some recorders save
alongside the log — the same fields `environment.txt` carries in UNIQUE's own export. It
only affects the header line.

## Tests

```bash
tools/device-log/self_test.py
```

62 tests, under a second, no dependencies. Three kinds:

- **`fixtures/redmi-android15.log`** — a real run on a Redmi Note 12, Android 15, ARM64:
  the run in which no app launched. Every finding asserted against it is something that
  happened to a real phone, so a check that stops reporting one has regressed. It is the
  full run filtered to the lines that carry evidence (941 of 26,950); the verdicts are
  identical to those from the unfiltered log.
- **`fixtures/redmi-android15-run4.log` … `-run8.log`** — the runs after it,
  each filtered the same way. Every fault a run found has an assertion here, so a check
  that stops reporting one is a regression in the tool rather than progress in the
  engine. The sixth is the run in which six of seven apps launched and the games could
  not find their own assets; it is what `storage` and `native` were written against. The
  seventh is the one that disproved the sixth's native finding — the same crash with the
  library UNIQUE had hooked excluded — and the assertion that used to say "traced to the
  library UNIQUE hooked" now says what both logs actually support. The eighth disproved a
  rule shipped one pass earlier: three unrelated apps, one of them ChatGPT, all declaring
  `gmsVersion=12451000` on a phone running GmsCore 26.32.34, which is what `google` was
  written against.
- **A synthetic healthy run** — every check must pass on it. Without that the suite would
  prove only that the tool says FAIL, which a tool that always says FAIL would also
  pass.
