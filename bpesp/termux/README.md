# Termux patcher

Turns your own copy of the BLOCKPOST APK into the patched one. No Java, no
apktool, no root, and nothing to download beyond this ~80 KB bundle — the
344 MB APK never leaves your phone.

## Once

```sh
pkg install python
pip install cryptography
```

## Every time

```sh
python bppatch.py "BLOCKPOST SERVER_1.00f4.apk"
```

Out comes `BLOCKPOST SERVER_1.00f4-esp.apk` next to it. Install with
`termux-open`, having uninstalled the previous build first — the signing key
differs from the store's, so it will not upgrade in place.

A signing key is generated on first run and kept in this folder as
`esp.key.pem`. Keep it: patched builds signed with the same key upgrade over
each other, so you can skip the uninstall from then on. Lose it and the next
build needs an uninstall again.

## Why it is quick

The APK is a third of a gigabyte and none of it is recompressed. Every original
entry is copied as one contiguous byte range, the three new ones are appended,
and the central directory — the only index Android actually reads — is rebuilt
to point at them. The old signature files are dropped simply by leaving them out
of that index.

The signature is APK Signature Scheme v2, built here rather than shelled out to
`apksigner`, which is what removes the JDK dependency. `targetSdk` is 35, so a
v1 JAR signature alone would be rejected by Android 11 and later; v2 is the
minimum that installs.

## What it changes

Three entries, and nothing else is touched:

1. `AndroidManifest.xml` — the application class is repointed from
   `androidx.multidex.MultiDexApplication` to
   `com.skullcapstudios.esp.EspOverlayApp`. Both names are exactly 37
   characters, so the edit is a byte swap inside the binary XML string pool and
   no offset moves.
2. `classes9.dex` — the overlay, in the next free multidex slot.
3. `lib/arm64-v8a/libesp.so` — the reader.

Verified against the 1.00f4 APK: 1628 entries in, none missing, none altered,
two added, and `apksigner verify` reports the v2 signature as valid.
