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
| `permissions` | Was a permission denied that no user could ever have granted? |
| `hooks` | Did every shim bind to a real method, or did one bind to nothing? |
| `providers` | Did the guest's ContentProviders publish and resolve? |
| `ui` | Did UNIQUE's own Flutter interface throw? |
| `limits` | Which deliberately-unsupported paths did this run reach? (Never a failure.) |

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

25 tests, under a second, no dependencies. Two halves:

- **`fixtures/redmi-android15.log`** — a real run on a Redmi Note 12, Android 15, ARM64:
  the run in which no app launched. Every finding asserted against it is something that
  happened to a real phone, so a check that stops reporting one has regressed. It is the
  full run filtered to the lines that carry evidence (941 of 26,950); the verdicts are
  identical to those from the unfiltered log.
- **A synthetic healthy run** — every check must pass on it. Without that half the suite
  would prove only that the tool says FAIL, which a tool that always says FAIL would also
  pass.
