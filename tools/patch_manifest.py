#!/usr/bin/env python3
"""In-place swap of the <application android:name> value in a binary manifest.

The replacement class name is the same length as the original, so the string
pool entry keeps its byte count and every offset in the AndroidManifest.xml
chunk stays valid. Nothing else in the APK is re-encoded.
"""

import argparse
import sys

DEFAULT_OLD = "com.kooapps.unity.UnityApplication"
DEFAULT_NEW = "com.gavna.snakeio.GavnaApplication"


def encodings(text):
    """Both encodings an AXML string pool can use for an ASCII string."""
    return [("utf-8", text.encode("utf-8")), ("utf-16-le", text.encode("utf-16-le"))]


def patch(data, old, new):
    if len(old) != len(new):
        raise ValueError(
            "class names must be the same length: %r (%d) vs %r (%d)"
            % (old, len(old), new, len(new))
        )

    replacements = 0
    for (name, old_bytes), (_, new_bytes) in zip(encodings(old), encodings(new)):
        assert len(old_bytes) == len(new_bytes)
        count = data.count(old_bytes)
        if count:
            data = data.replace(old_bytes, new_bytes)
            replacements += count
            print("  replaced %d %s occurrence(s)" % (count, name))
    return data, replacements


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest_in")
    parser.add_argument("manifest_out")
    parser.add_argument("--old", default=DEFAULT_OLD)
    parser.add_argument("--new", default=DEFAULT_NEW)
    args = parser.parse_args()

    with open(args.manifest_in, "rb") as handle:
        data = handle.read()

    print("patching application class %s -> %s" % (args.old, args.new))
    data, count = patch(data, args.old, args.new)
    if count == 0:
        print("ERROR: %r not found in %s" % (args.old, args.manifest_in), file=sys.stderr)
        return 1

    with open(args.manifest_out, "wb") as handle:
        handle.write(data)
    print("wrote %s (%d bytes)" % (args.manifest_out, len(data)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
