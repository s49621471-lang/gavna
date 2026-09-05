# What real apps actually call

```bash
tools/apk-survey/survey.py /path/to/apks/*.apk
tools/apk-survey/survey.py /path/to/apks/*.apk --methods locale   # which methods, exactly
```

No SDK, no device, no Gradle — Python 3 and the standard library. A 22 MB APK is read in
about a tenth of a second.

---

## Why this exists

Three system services went unproxied until a phone found them, and each killed a real app
on its first screen:

```
RestrictionsManager.getApplicationRestrictions   → ChatGPT died in Activity.onCreate
LocaleManager.getApplicationLocales              → ChatGPT's network thread died
ConnectivityManager.getNetworkCapabilities       → the guest saw no network
```

The emulator suite passed **38 of 38** against all three, and a better emulator would not
have helped. The suite runs against `com.unique.probe`, an app written to be probed, and
the probe calls none of those APIs. Every bug the third device run produced was pure Java
logic that would have reproduced on any Android at all.

**The gap was never *emulator versus phone*. It was *a test app versus a real one*.**

So this reads real apps. Not by running them — that needs a device — but by reading the
`method_ids` table out of their DEX, which lists every method they reference. The first
random app pulled from F-Droid, a microblogging client, references both
`LocaleManager.getApplicationLocales` and `ConnectivityManager.getNetworkCapabilities`.

Both bugs were findable here, for free, before anybody installed anything.

## What it reports

Three states, and the middle one is the point:

| | |
|---|---|
| `yes` | some hook actually calls `SystemServiceHook.install` for this service |
| `DECLARED ONLY` | the name is in `SystemServiceHook.TARGETS` and **nothing installs it** |
| `NO` | not in `TARGETS` at all |

`DECLARED ONLY` is worse than `NO`, because it reads as done. From a 63-app corpus:

```
In TARGETS but nothing installs them — they read as done and are not:
  - window         (WindowManager,          60/63 apps)
  - media_session  (MediaSessionManager,     3/63 apps)
  - account        (AccountManager,          3/63 apps)

Not in TARGETS at all, and real apps call them:
  - phone          (TelephonyManager,       34/63 apps)
  - download       (DownloadManager,         5/63 apps)
  - device_policy  (DevicePolicyManager,     3/63 apps)
  - media.camera   (CameraManager,           3/63 apps)
  - telecom        (TelecomManager,          1/63 apps)
  - media_router   (MediaRouter,             1/63 apps)
```

`account` is the one worth reading twice: it is declared, never installed, and until it is,
`AccountManager.getAccounts()` from a guest returns **the phone's real Google accounts**.

Both lists come out of the Kotlin sources, never a copy — a drifted copy would report a
proxied service as unproxied, and a check that lies once gets ignored for ever.

## Getting a corpus

Any APKs will do. F-Droid is convenient because everything on it is freely
redistributable and the index is a plain JSON file:

```bash
curl -O https://f-droid.org/repo/index-v1.jar && unzip -o index-v1.jar index-v1.json
# pick apkName values out of index-v1.json, then:
curl -O https://f-droid.org/repo/<apkName>
```

Nothing is checked in: the corpus is disposable, a bigger one is strictly better, and
sixty APKs is a gigabyte nobody needs in git.

## What it cannot say

**A reference is not a call.** An app that references `getApplicationLocales` on a path it
never takes still appears here, and one that reaches the API by reflection does not appear
at all. This ranks **risk**, not behaviour: a service thirty apps reference is one to proxy
before a phone finds it; a service nobody references can wait.

It does not replace `docs/PHYSICAL_DEVICE_TEST.md`. It decides what to fix first.

## Tests

```bash
tools/apk-survey/self_test.py
```

15 tests. The DEX reader is checked against a real checked-in APK rather than bytes
written by the test to satisfy the test — a parser that quietly returns too few references
would report a service as unused and get it left unproxied, which is the exact failure
this tool exists to prevent.
