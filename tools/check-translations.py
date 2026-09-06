#!/usr/bin/env python3
"""Checks that every failure the engine can report has a sentence in every language.

`tools/check-translations.py`, no arguments. Standard library only.

The interface shows an engine failure by translating the *code* the engine sends and
falling back to the engine's own English prose. That fallback is deliberate — a code
UNIQUE gains tomorrow says something specific rather than nothing — but it is also silent,
so a Russian reader finds out about a missing translation and nobody else does. This is
what makes it loud instead.

It reads the codes out of the Kotlin, the keys out of `strings.dart`, and reports:

  - a code the engine sends that neither language translates;
  - a code translated in one language and not the other;
  - an `engine.*` key no code produces, which is a translation of nothing.
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STRINGS = os.path.join(ROOT, "ui", "lib", "l10n", "strings.dart")
KOTLIN_ROOTS = [os.path.join(ROOT, "app", "src", "main"), os.path.join(ROOT, "core")]

# How a failure code is written in Kotlin. Every form the engine actually uses; a new
# one that this misses shows up as an untranslated code on somebody's phone, so it is
# worth adding here at the same time as the call site.
CODE_PATTERNS = [
    re.compile(r'"code"\s+to\s+"([A-Z0-9_]+)"'),
    # `"code" to if (permanent) "A" else "B"` — both branches are codes the UI can show.
    re.compile(
        r'"code"\s+to\s+if\s*\([^)]*\)\s*"([A-Z0-9_]+)"\s*\n?\s*else\s+"([A-Z0-9_]+)"'
    ),
    re.compile(r'\bLaunchResult\.Failed\(\s*\n?\s*"([A-Z0-9_]+)"'),
    re.compile(r'\b(?:Create|Update)Result\.Rejected\(\s*\n?\s*"([A-Z0-9_]+)"'),
    re.compile(r'override\s+val\s+code:\s*String\s+get\(\)\s*=\s*"([A-Z0-9_]+)"'),
]

# Codes the interface never shows: they are consumed before any snackbar is built, or
# they name an internal contract rather than a failure a person can read.
NOT_SHOWN = {
    "SLOT_ALREADY_BOUND",   # retried internally; the user sees the retry's result
}


def kotlin_codes() -> set[str]:
    found: set[str] = set()
    for root in KOTLIN_ROOTS:
        for dirpath, _dirs, files in os.walk(root):
            if "/test/" in dirpath or "/androidTest/" in dirpath:
                continue
            for name in files:
                if not name.endswith(".kt"):
                    continue
                text = open(os.path.join(dirpath, name), encoding="utf-8").read()
                for pattern in CODE_PATTERNS:
                    for match in pattern.findall(text):
                        # A pattern with more than one group hands back a tuple.
                        found.update(match if isinstance(match, tuple) else (match,))
    return found - NOT_SHOWN


def dart_keys() -> tuple[set[str], set[str]]:
    """The `engine.CODE` keys each language map declares."""
    text = open(STRINGS, encoding="utf-8").read()
    maps = {}
    for language in ("_en", "_ru"):
        start = text.index(f"static const Map<String, String> {language} = {{")
        # The map ends at the first line that closes it at the class's indentation.
        end = text.index("\n  };", start)
        maps[language] = {
            m.group(1)
            for m in re.finditer(r"'engine\.([A-Z0-9_]+)'\s*:", text[start:end])
        }
    return maps["_en"], maps["_ru"]


def main() -> int:
    codes = kotlin_codes()
    if not codes:
        print("FAIL  no failure codes found in the Kotlin — the patterns have gone stale")
        return 1

    english, russian = dart_keys()
    problems: list[str] = []
    for code in sorted(codes - (english | russian)):
        problems.append(f"  {code}: the engine sends it, no language translates it")
    for code in sorted((codes & russian) - english):
        problems.append(f"  {code}: translated into Russian, missing from English")
    for code in sorted((codes & english) - russian):
        problems.append(f"  {code}: translated into English, missing from Russian")
    for key in sorted((english | russian) - codes):
        problems.append(f"  {key}: translated, but no engine code produces it")

    if problems:
        print(f"FAIL  {len(problems)} problem(s):")
        print("\n".join(problems))
        return 1

    print(f"PASS  {len(codes)} engine failure codes, all translated into both languages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
