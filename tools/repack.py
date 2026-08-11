#!/usr/bin/env python3
"""Rebuilds the APK: copies every original entry, swaps the patched ones and
appends the mod's dex and native library.

Entries are copied with their original compression method so the Unity asset
bundles that shipped stored stay stored, and the old signature block is dropped
because the result gets re-signed afterwards.
"""

import argparse
import os
import sys
import zipfile


def load_replacements(pairs):
    out = {}
    for pair in pairs:
        if "=" not in pair:
            raise ValueError("expected NAME=PATH, got %r" % pair)
        name, path = pair.split("=", 1)
        if not os.path.isfile(path):
            raise ValueError("no such file: %s" % path)
        out[name] = path
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source_apk")
    parser.add_argument("output_apk")
    parser.add_argument("--replace", action="append", default=[],
                        help="NAME=PATH, overwrites an existing entry")
    parser.add_argument("--add", action="append", default=[],
                        help="NAME=PATH, adds a new entry (deflated)")
    parser.add_argument("--store", action="append", default=[],
                        help="NAME=PATH, adds a new entry stored uncompressed")
    args = parser.parse_args()

    replacements = load_replacements(args.replace)
    additions = load_replacements(args.add)
    stored = load_replacements(args.store)

    for name in list(additions) + list(stored):
        if name in replacements:
            raise ValueError("%s is both added and replaced" % name)

    copied = 0
    matched = set()
    with zipfile.ZipFile(args.source_apk, "r") as src, \
            zipfile.ZipFile(args.output_apk, "w", allowZip64=True) as dst:
        existing = set(src.namelist())
        for name in list(additions) + list(stored):
            if name in existing:
                raise ValueError("%s already exists, use --replace" % name)

        for info in src.infolist():
            # The old v1 signature no longer matches once we touch the contents.
            if info.filename.startswith("META-INF/") and info.filename.upper().endswith(
                    (".SF", ".RSA", ".DSA", ".EC")):
                continue
            if info.filename == "META-INF/MANIFEST.MF":
                continue
            if info.filename.endswith("/"):
                continue

            if info.filename in replacements:
                with open(replacements[info.filename], "rb") as handle:
                    payload = handle.read()
                matched.add(info.filename)
            else:
                payload = src.read(info.filename)
                copied += 1

            out_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out_info.compress_type = info.compress_type
            out_info.external_attr = info.external_attr
            out_info.create_system = info.create_system
            dst.writestr(out_info, payload)

        for name, path in additions.items():
            with open(path, "rb") as handle:
                dst.writestr(_info(name), handle.read(), zipfile.ZIP_DEFLATED)
        for name, path in stored.items():
            with open(path, "rb") as handle:
                dst.writestr(_info(name), handle.read(), zipfile.ZIP_STORED)

    missing = set(replacements) - matched
    print("copied %d entries, replaced %d, added %d" %
          (copied, len(matched), len(additions) + len(stored)))
    if missing:
        print("ERROR: replacement(s) never matched an entry: %s" % sorted(missing),
              file=sys.stderr)
        return 1
    print("wrote %s (%d bytes)" % (args.output_apk, os.path.getsize(args.output_apk)))
    return 0


def _info(name):
    info = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 1))
    info.external_attr = 0o644 << 16
    info.create_system = 0
    return info


if __name__ == "__main__":
    sys.exit(main())
