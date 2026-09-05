# The verification emulator

Every on-device result in `docs/STATUS.md` that is not marked `ARM64` came from one
emulator, described here exactly so a run can be reproduced rather than trusted.

```
Android 14 (API 34) · AOSP ATD · x86_64 · software rendering · no KVM
emulator 37.1.11 · platform-tools 37.0.1 · system image aosp_atd revision 1
```

`aosp_atd` — "Automated Test Device" — is a stripped AOSP image built for CI: no Google
apps, no Play services, no launcher animations. That makes it fast enough to be usable
without hardware acceleration, and it is also why **every Google answer on it is correctly
"absent"** and why `docs/GOOGLE_DEVICE_TEST.md` needs a different device.

## Installing it

The emulator is not downloaded by hand; `sdkmanager` fetches it and the system image
together. Get the command-line tools first:

- **Command-line tools** (the "Command line tools only" archive at the bottom of the page):
  https://developer.android.com/studio#command-line-tools-only
- Emulator command reference: https://developer.android.com/studio/run/emulator-commandline
- Older emulator builds, if one ever needs pinning:
  https://developer.android.com/studio/emulator_archive

```bash
export ANDROID_HOME=/opt/android-sdk

# Unpack the command-line tools so sdkmanager sits at the path it expects:
#   $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
mkdir -p "$ANDROID_HOME/cmdline-tools"
unzip commandlinetools-linux-*.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

sdk="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdk" --licenses

"$sdk" \
    "platform-tools" \
    "emulator" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "ndk;27.0.12077973" \
    "system-images;android-34;aosp_atd;x86_64"
```

Sizes are worth knowing before starting: the system image is **8.2 GB**, the emulator
821 MB, one NDK about 2 GB. Disk is the single most common reason this stops working —
see *When it breaks* below.

## Creating the AVD

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
    --name unique-test \
    --package "system-images;android-34;aosp_atd;x86_64" \
    --device pixel_6
```

Then, in `~/.android/avd/unique-test.avd/config.ini`, the settings that matter here:

```ini
hw.ramSize = 3072                       # 3 GB; the host has 15 GB and 4 cores
hw.cpu.ncore = 2
disk.dataPartition.size = 6442450944    # 6 GB — Android 15-era APEX needs room
hw.gpu.enabled = no                     # no GPU on this host, so no point pretending
hw.gpu.mode = off
hw.audioInput = no
hw.audioOutput = no
PlayStore.enabled = no                  # aosp_atd has no Play services at all
```

## Launching it

This is the exact command every run in `docs/STATUS.md` used:

```bash
export ANDROID_HOME=/opt/android-sdk

"$ANDROID_HOME/emulator/emulator" -avd unique-test \
    -no-window -no-audio -no-boot-anim \
    -no-snapshot \
    -gpu off -accel off \
    -memory 3072 -partition-size 2048
```

| Flag | Why |
|---|---|
| `-no-window` | Headless. There is no display on the build host, and the suite never looks at pixels — which is exactly why `docs/PHYSICAL_DEVICE_TEST.md` exists |
| `-no-audio` | No audio device on the host; without this the emulator retries and logs about it forever |
| `-no-boot-anim` | Boot animation on a software renderer is minutes of wasted CPU |
| `-no-snapshot` | Every run starts from the same state. A snapshot would carry over whatever the previous run left installed, and the whole point of the suite is that the probe is *not* installed |
| `-gpu off` | No GPU. OpenGL and Vulkan run on `llvmpipe`, a software rasteriser — which is why hardware Vulkan stays `NOT_TESTED` |
| `-accel off` | No `/dev/kvm` in this container, so QEMU translates x86_64 instructions in software. This is the single biggest reason everything here is slow |
| `-memory 3072` | 3 GB, leaving room on a 15 GB host for Gradle. See *When it breaks* |
| `-partition-size 2048` | System partition. The default is too small for this image |

Run it detached so it survives the shell that started it:

```bash
setsid nohup "$ANDROID_HOME/emulator/emulator" -avd unique-test \
    -no-window -no-audio -no-boot-anim -no-snapshot -gpu off -accel off \
    -memory 3072 -partition-size 2048 > /tmp/emulator.log 2>&1 < /dev/null &
```

## Waiting for it

`adb devices` reporting `device` is **not** enough — it says the daemon connected, not that
Android is up. `sys.boot_completed` is unreliable on this image (it stays empty). The
signal that actually means "ready" is the package manager answering:

```bash
until [ "$("$ANDROID_HOME/platform-tools/adb" shell pm list packages 2>/dev/null | wc -l)" -gt 50 ]; do
    sleep 20
done
```

A cold boot takes **4–6 minutes** here. After `-wipe-data` it is longer, and the first ten
minutes afterwards are spent optimising system apps — installing during that window fails
with

```
NullPointerException: … PackageManagerInternal.freeStorage(…) on a null object reference
```

which means "not ready yet", not "broken". Retry the install rather than debugging it.

## Running the suite against it

```bash
export ANDROID_HOME=/opt/android-sdk
BUILD_TYPE=verify ./tools/verify-device.sh
```

It derives the ABI from the attached device, builds UNIQUE and the probe, installs both,
keeps the probe uninstalled on the device, runs 38 instrumented tests and writes everything
to `build/device-verification/<run-id>/`. `RUN_ID`, `TESTS`, `SKIP_BUILD` and `BUILD_TYPE`
are the knobs; the script's own header documents them.

## When it breaks

Three failures accounted for every bad run here, and none of them was UNIQUE's fault.

**1. Disk.** The container has a fixed allowance and the SDK alone is ~12 GB. When it fills,
the emulator does not say so — it fails to activate its APEX modules and dies in a boot
loop:

```
CANNOT LINK EXECUTABLE "/system/bin/netd": library "libnetd_resolv.so" not found
System zygote died with fatal exception
```

Check `df -h /` first, always. Deleting `app/build/intermediates` (about 3 GB) and any
unused NDK is usually enough; then `-wipe-data` on the next boot.

**2. The Gradle and Kotlin daemons.** Together they hold about **5 GB**, which on a host
sized for one emulator is the emulator's memory. With them resident the emulator ran at
load 10 and the platform started killing processes before they could attach —

```
Process ProcessRecord{… com.unique:vapp2} failed to attach
Killing 11888:com.unique:vapp2 (adj -10000): start timeout
```

— which fails tests that have nothing to do with what they are testing. `com.android.bluetooth`
was killed the same way in the same second, which is how it was finally identified as the
machine and not the engine. `tools/verify-device.sh` now runs `./gradlew --stop` after
building and before instrumenting; the same emulator went to load 1 and cold start dropped
from 37.5 s to 12.1 s.

**3. A degraded long-running instance.** After many hours the emulator gets slower in ways
that look like engine flakiness — a broadcast delivered two seconds after its 180-second
timeout, for example. The remedy is to restart it, not to raise a timeout. A timeout raised
to accommodate a sick machine stops measuring anything.

## What this emulator cannot answer

Written here because it is the reason `docs/COMPATIBILITY.md` has a second column:

- **ARM64.** It is x86_64. Every native result on it proves the plumbing and nothing about
  the ABI.
- **A real GPU.** OpenGL passes on a software rasteriser; Vulkan enumerates
  `llvmpipe (LLVM 21.0.0, 256 bits)`, device type `cpu`. A hardware ICD is a different code
  path.
- **WebView rendering.** Chromium's renderer crashes here *outside* virtualization too, so
  the test asserts only that the WebView was created with the instance's own data directory.
- **Anything Google.** `aosp_atd` has no Play services, so every answer is correctly
  "absent" and nothing about a real Google stack is exercised.
- **OEM framework forks.** HyperOS, One UI and the rest diverge from AOSP in exactly the
  places this engine touches. Both faults that stopped guests launching on a Xiaomi
  Android 15 phone were invisible here.
