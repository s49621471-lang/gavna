#!/usr/bin/env python3
"""Inject the Zyrex bootstrap call into UnityPlayerActivity.onCreate.

The call goes in as the last statement of onCreate, after UnityPlayer has been
constructed and focused, so nothing of ours runs before the game is set up.
`p0` is the Activity and an Activity is a Context, so no extra registers are
needed and the method's `.locals` count is left alone.

Idempotent: running it twice is a no-op.
"""

import sys
import pathlib

RELPATH = "smali_classes7/com/unity3d/player/UnityPlayerActivity.smali"

ANCHOR = """    invoke-virtual {p1}, Lcom/unity3d/player/UnityPlayer;->requestFocus()Z

    return-void
.end method"""

PATCHED = """    invoke-virtual {p1}, Lcom/unity3d/player/UnityPlayer;->requestFocus()Z

    invoke-static {p0}, Lcom/zyrex/dumper/Zyrex;->init(Landroid/content/Context;)V

    return-void
.end method"""

CALL = "Lcom/zyrex/dumper/Zyrex;->init"


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch_activity.py <decoded-apk-dir>", file=sys.stderr)
        return 2

    target = pathlib.Path(sys.argv[1]) / RELPATH
    if not target.is_file():
        print(f"not found: {target}", file=sys.stderr)
        return 1

    text = target.read_text(encoding="utf-8")

    if CALL in text:
        print("already patched, nothing to do")
        return 0

    count = text.count(ANCHOR)
    if count != 1:
        print(f"expected exactly one anchor, found {count} — "
              "the activity differs from the build this was written for",
              file=sys.stderr)
        return 1

    target.write_text(text.replace(ANCHOR, PATCHED), encoding="utf-8")
    print(f"patched {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
