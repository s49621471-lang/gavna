# Download

Two APKs, both **arm64-v8a only**, both signed with the same key — so either can be
installed over the other. Android 12 or newer (`minSdk 31`), no root, no unlocked
bootloader.

> **A build from a different session will not install over an older one.** These are
> signed with a *test* key that the build machine generates, so two builds made on
> different machines have different keys, and Android's error for that is the unhelpful
> `App not installed`. When it happens, uninstall first — which removes the instances and
> their data with it. That is the cost of a test-signed pre-release, and it is stated here
> rather than discovered.
>
> Consecutive builds from the *same* session share a key and update in place, instances
> intact. `apksigner verify --print-certs` on the APK you have and the one you are about
> to install answers which case you are in; `SHA256SUMS` says which artifact this is.

| File | Size | What it is |
|---|---|---|
| `unique-arm64-v8a.apk` | ~27 MB | **Install this one.** The engine exactly as the acceptance suite runs it — no R8, so no keep rule can be wrong — with Flutter's UI built ahead of time so the artifact is a quarter the size of a debug build |
| `unique-arm64-v8a-minified.apk` | ~19 MB | The same thing with R8 on. **Not device-verified** — see below |

Direct links, which work in a phone browser:

- https://github.com/erundltd/gavna/raw/claude/unique-app-virtualization-bn35b2/dist/unique-arm64-v8a.apk
- https://github.com/erundltd/gavna/raw/claude/unique-app-virtualization-bn35b2/dist/unique-arm64-v8a-minified.apk

Verify what you downloaded against `SHA256SUMS`.

## Which one, and why it matters

Install **`unique-arm64-v8a.apk`**. It is the build the acceptance suite ran against, and
it is not minified — a virtualization engine is nearly all reflection, so a wrong R8 keep
rule produces an app that works everywhere except the build people install.

`unique-arm64-v8a-minified.apk` assembles, signs, and its keep rules hold *structurally*:
the minified dex still carries the stub pool, the router and shared providers,
`UniqueNative` and the native entry points, and the manifest still declares all 281
components. That was checked on this artifact. It is not the same as running: the
instrumented suite cannot be pointed at it, because `androidx.tracing.Trace` reaches
`AndroidJUnitRunner.onCreate` from R8's *classpath* rather than from program input, so no
keep rule applies. Install it only to see whether it starts — and if it behaves
differently from the other one, that difference is the finding.

## Signing

Both are signed with Android's **debug key** (`CN=Android Debug`, SHA-256
`f061acdb0b780c3a83efe0c364435a0d02f0920c6e4ff23dd53305fbb1d76489`). That is a
test-signing arrangement and is marked as such: these must not be distributed as a
release, and an app signed with a real key later will not install over them.

## What changed since the last phone run

Six apps launched in that run and three of them died seconds later, all of the same thing.
This build answers everything that log reported.

- **Play services was killing guests.** `GmsClient.getRemoteService` sends the app's own
  package name to `com.google.android.gms`, which resolves the *calling* uid — UNIQUE's —
  and answers `SecurityException: Unknown calling package name`. It arrives on a `Handler`,
  so it is fatal and no app can catch it: three of the six guests in that log died this way
  within seconds of reaching their own screen. The Google stack is now hidden from a guest
  entirely — `getPackageInfo`, the uid lookup, provider resolution, the installed list and
  both intent resolvers — so an SDK that asks finds nothing and takes the path it already
  has for a phone without Play services. `com.android.vending` stays visible, because Play's
  licence check is an ordinary bind that works and a protected app exits without it.
- **There was no keyboard.** `EditorInfo.packageName` carries the app's own name into
  `startInputOrWindowGainedFocus`, and the input-method service checks it against the
  calling uid: a mismatch is `INVALID_PACKAGE_NAME` and *no keyboard is ever bound*. The
  field takes focus, shows a caret, and accepts nothing. `input_method` is hooked now and
  the phone's own keyboard comes up, exactly as it does outside UNIQUE.
- **A guest came up with no `FileProvider`.** Publishing a provider runs the app's own
  `attachInfo`, and `FileProvider`'s reads external storage — which was still the host's at
  that moment. Providers are published after the identity hooks now.
- **"The screen does not respond."** Touch was never broken: `ACTION_DOWN` and `ACTION_UP`
  reached every guest that was alive. The unresponsive screens were the dead windows left
  by the crashes above, still on screen with nothing behind them. Fixing the crashes is the
  fix for this.
- **The interface was half in English.** Every failure the engine can report now carries a
  code the interface translates, an automatic profile name is written in the reader's own
  language, and `tools/check-translations.py` fails the build if a code has no sentence in
  both. 25 codes, both languages.
- **Some permissions could not be granted at all.** Draw-over-other-apps, exact alarms and
  unrestricted background work have no Android dialog — each is a Settings screen, and each
  is held by UNIQUE rather than by a copy. App Details lists all three with their real
  state, opens the right screen, and re-reads the answer on return.
- **The *Advanced* section is gone**, as asked: the device test, the checklist, the
  diagnostics screen and the export with it. Everything UNIQUE records still goes to
  `logcat` under one tag, so any log-recorder app on the phone captures a whole run.

## What changed in the run before that

That build answered the four things its log was actually reporting, and eighteen more
found on the way. The four:

- **Everything was drawing in software.** The `ActivityInfo` UNIQUE substitutes carried no
  `flags`, and `Activity.attach` reads exactly `FLAG_HARDWARE_ACCELERATED` out of it — so
  every app UNIQUE has ever run rendered on the CPU. That is the "screen lags" report, and
  for anything drawing through a `RenderNode` it was not slow but fatal:
  `IllegalArgumentException: Software rendering doesn't support drawRenderNode`. The window
  is now told twice — through the `ActivityInfo` and directly, before the app's `onCreate`,
  because on an Android 14 device the first was not enough.
- **A landscape game opened portrait.** The platform takes a window's orientation from the
  manifest entry it has *installed*, which under UNIQUE is a stub declaring `unspecified`.
  The app's own `android:screenOrientation` is applied before it reads the display size.
- **`ApplicationInfo.metaData` was empty**, so Google Play services threw on every app that
  declares `com.google.android.gms.version` — which is most of them. Meta-data is carried
  now in both shapes, and a `@integer` reference is resolved against the app's own
  resources.
- **Play's licence check could not bind**, because UNIQUE never declared
  `com.android.vending.CHECK_LICENSE`. A PAIRIP-protected app calls `System.exit(0)` when
  that bind is refused, which is an app vanishing with no crash and no message.

And, from putting all of that on the emulator:

- A **second tap on a running app did nothing**: the platform delivers the launch to the
  activity that is already there, and what arrived was UNIQUE's routing intent rather than
  the app's own — which `ActivityThread` then stores as `getIntent()` for the rest of that
  screen's life.
- **External storage was unusable.** `getExternalFilesDir()` named a scoped-storage
  directory UNIQUE may not create, so apps that keep downloads or caches there saw storage
  as unavailable. It now resolves inside the instance.
- **An app could not tell you who it was.** `/proc/self/cmdline` read `com.unique:vapp0`,
  the process list showed UNIQUE's processes, `getLaunchIntentForPackage` for its own
  package returned null, and `getInstallerPackageName` threw. All four are answers an app
  expects to be able to get about itself, and apps that check them concluded they were
  running somewhere they should not.
- **A permission screen the app opened about itself went nowhere**, because the package it
  named is not installed. It is retargeted to UNIQUE, which is the uid that actually holds
  the access.

Also: a guest gets its **own** network security policy instead of UNIQUE's (an app died in
Conscrypt closing a TLS socket, and pinning and cleartext rules were the host's either
way), and a `<provider>` with no `android:name` no longer produces six
`ClassNotFoundException`s per launch.

Seven real applications from F-Droid were then imported and launched with nothing written
for them — **Termux**, **Fossify Gallery**, **NewPipe**, **Shattered Pixel Dungeon**,
**AntennaPod**, **KeePassDX** and **Aegis**. Four of the seven failed, each differently,
and all seven now reach their own main screen:

- a guest was held to **UNIQUE's** target SDK rather than its own, by two separate compat
  mechanisms, so an app built against Android 9 threw on `registerReceiver` and again on
  `PendingIntent`;
- `startForeground(id, notification)` — every app written before Android 10 — asked for
  *every* foreground-service type UNIQUE declares at once, and was refused for the first
  one whose permission the phone had not granted;
- an app asking about its own home-screen widget died in `onCreate`, because the service
  that answers that names the caller in a `ComponentName` and nothing was rewriting it;
- an app enabling *its own* component — which is how a launcher icon is changed and how a
  keyboard is switched on — was refused by the platform, because the component belongs to a
  package it has never installed; UNIQUE keeps that state per instance now;
- and an app publishing a shortcut killed itself in `Application.onCreate`. That one is
  refused rather than fixed: it now gets the "not accepted" answer the API already defines,
  and shortcuts from a virtual app stay a thing UNIQUE does not do.

The on-device suite is **46 of 46** on an Android 14 x86_64 emulator, and all seven of
those applications still pass against everything in this build. None of it has been back on
a phone; `docs/COMPATIBILITY.md` says so per row. And nothing here looks at the screen:
"the app reached its own main activity and stayed up" is the whole claim.

## What changed since the first phone run

That run imported two apps and launched neither. Both causes are fixed here.

- **A guest could not read a setting**, so it could not open a database, could not attach
  an Activity, and never started. The settings provider was cached before the graft and
  UNIQUE's identity rewrite never got in front of it.
- **`getHistoricalProcessExitReasons` went out under the guest's own name**, which needs
  `DUMP` for any package but the caller's — so startup crash-reporting code took the app
  down with it.
- **Every imported app was called `@7f010000`** and had no icon. Names and icons are now
  read from the stored APK through the platform's own parser, in the phone's language.
- **The log was nearly empty of UNIQUE's own events.** This build writes the full
  structured trace to logcat, so a capture from a logcat app on the phone now contains
  everything about a run. That is a much better starting point than the log before it,
  which held only warnings and errors.
- **The interface speaks English and Russian**, following the phone by default and
  switchable in *Settings → Appearance → Language*.

Neither engine fix is re-proven on hardware: the emulator never reproduced them, so it
cannot confirm them either. `docs/COMPATIBILITY.md` still says `BROKEN` for both on ARM64.

## What to do next

`docs/PHYSICAL_DEVICE_TEST.md` is the twelve-step sequence. It needs no `adb`, no root and
no computer to run: start any log-recorder app on the phone, work down the twelve steps,
and send what it captured. UNIQUE writes everything it records to `logcat` under one tag,
so an ordinary capture carries the whole run — `tools/device-log/analyze.py` reads it.

## What is not claimed

ARM64 native code, hardware Vulkan, WebView rendering, Google Play services and any real
application are all `NOT_TESTED` in `docs/COMPATIBILITY.md`, and stay that way until a
physical-device run says otherwise. Everything verified so far ran on an x86_64 Android 14
emulator with software rendering.
