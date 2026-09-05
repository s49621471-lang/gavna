# Download

Two APKs, both **arm64-v8a only**, both signed with the same key — so either can be
installed over the other. Android 12 or newer (`minSdk 31`), no root, no unlocked
bootloader.

| File | Size | What it is |
|---|---|---|
| `unique-arm64-v8a.apk` | ~25 MB | **Install this one.** The engine exactly as the acceptance suite runs it — no R8, so no keep rule can be wrong — with Flutter's UI built ahead of time so the artifact is a quarter the size of a debug build |
| `unique-arm64-v8a-minified.apk` | ~18 MB | The same thing with R8 on. **Not device-verified** — see below |

Direct links, which work in a phone browser:

- https://github.com/s49621471-lang/gavna/raw/claude/unique-app-virtualization-bn35b2/dist/unique-arm64-v8a.apk
- https://github.com/s49621471-lang/gavna/raw/claude/unique-app-virtualization-bn35b2/dist/unique-arm64-v8a-minified.apk

Verify what you downloaded against `SHA256SUMS`.

## Which one, and why it matters

Install **`unique-arm64-v8a.apk`**. It is the build the acceptance suite ran against, and
it is not minified — a virtualization engine is nearly all reflection, so a wrong R8 keep
rule produces an app that works everywhere except the build people install.

`unique-arm64-v8a-minified.apk` assembles, signs, and its keep rules hold *structurally*:
the minified dex still carries the stub pool, the router and shared providers,
`UniqueNative` and the native entry points, and the manifest still declares 272 stub
components. That was checked on the artifact. It is not the same as running: the
instrumented suite cannot be pointed at it, because `androidx.tracing.Trace` reaches
`AndroidJUnitRunner.onCreate` from R8's *classpath* rather than from program input, so no
keep rule applies. Install it only to see whether it starts — and if it behaves
differently from the other one, that difference is the finding.

## Signing

Both are signed with Android's **debug key** (`CN=Android Debug`, SHA-256
`f061acdb0b780c3a83efe0c364435a0d02f0920c6e4ff23dd53305fbb1d76489`). That is a
test-signing arrangement and is marked as such: these must not be distributed as a
release, and an app signed with a real key later will not install over them.

## What to do next

`docs/PHYSICAL_DEVICE_TEST.md` is the twelve-step sequence. It needs no `adb`, no root and
no computer — *Settings → Advanced → Device test* inside the app holds the sequence and
the device report, and the last step shares one diagnostics package out through the
ordinary share sheet.

## What is not claimed

ARM64 native code, hardware Vulkan, WebView rendering, Google Play services and any real
application are all `NOT_TESTED` in `docs/COMPATIBILITY.md`, and stay that way until a
physical-device run says otherwise. Everything verified so far ran on an x86_64 Android 14
emulator with software rendering.
