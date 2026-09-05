"""Reads the method-reference table out of an APK's DEX files.

Every method a `.dex` calls is listed in its `method_ids` table as
`(class, name, prototype)` — including calls into the framework. Reading that table is
how this tool learns which platform APIs an app *actually* uses, without running it, and
therefore without a device.

Only the reference table is parsed, never the bytecode. That makes the whole survey a
few seconds per APK rather than a few minutes, and it is enough for the question being
asked: not "how often does this app call `getApplicationLocales`" but "does it call it
at all, so does UNIQUE need to proxy `ILocaleManager`".

Standard library only.

## DEX layout, the part that is used

    header      0x38 string_ids_size  0x3C string_ids_off
                0x40 type_ids_size    0x44 type_ids_off
                0x58 method_ids_size  0x5C method_ids_off

    string_id   u4 offset -> uleb128 utf16_size, then MUTF-8 bytes, NUL-terminated
    type_id     u4 descriptor_idx -> string_id
    method_id   u2 class_idx -> type_id, u2 proto_idx, u4 name_idx -> string_id

Both `dex` (035..041) and the container format Android 15 introduced are handled: a
`.dex` whose header is a container holds several logical dex files back to back, and each
carries its own header, so the file is scanned for every `dex\\n` magic rather than
assumed to start with one.
"""

from __future__ import annotations

import re
import struct
import zipfile
from dataclasses import dataclass
from typing import Iterator, List, Set, Tuple

DEX_MAGIC = re.compile(rb"dex\n\d{3}\x00")


@dataclass(frozen=True)
class MethodRef:
    cls: str
    name: str

    def __str__(self) -> str:
        return f"{self.cls}.{self.name}"


def _uleb128(data: bytes, at: int) -> Tuple[int, int]:
    result = shift = 0
    while True:
        byte = data[at]
        at += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, at
        shift += 7


def _descriptor_to_class(descriptor: str) -> str:
    """`Landroid/app/LocaleManager;` -> `android.app.LocaleManager`.

    Arrays are unwrapped and re-suffixed rather than passed through, so
    `[Lio/flutter/KeyData;` becomes `io.flutter.KeyData[]` and never a half-converted
    name with slashes still in it. Nothing in the survey matches on an array type, but a
    reader that emits two spellings of a class name is one whose output cannot be grepped.

    A primitive (`I`, `[J`) has no class name and is returned as written.
    """
    depth = 0
    while descriptor.startswith("["):
        descriptor = descriptor[1:]
        depth += 1
    if descriptor.startswith("L") and descriptor.endswith(";"):
        descriptor = descriptor[1:-1].replace("/", ".")
    return descriptor + "[]" * depth


def _refs_in_dex(data: bytes, base: int) -> Iterator[MethodRef]:
    """Method references of the one logical dex whose header starts at [base]."""

    def u4(at: int) -> int:
        return struct.unpack_from("<I", data, base + at)[0]

    string_ids_size, string_ids_off = u4(0x38), u4(0x3C)
    type_ids_size, type_ids_off = u4(0x40), u4(0x44)
    method_ids_size, method_ids_off = u4(0x58), u4(0x5C)
    if not method_ids_size or not string_ids_size:
        return

    # Offsets inside a dex are absolute within the *container*, not within the logical
    # dex, from Android 15's container format on. Before it, base is 0 and the two agree.
    origin = base if string_ids_off < base else 0

    def string_at(index: int) -> str:
        if index >= string_ids_size:
            return ""
        off = struct.unpack_from("<I", data, origin + string_ids_off + index * 4)[0]
        _, at = _uleb128(data, origin + off)
        end = data.index(b"\x00", at)
        return data[at:end].decode("utf-8", "replace")

    types: List[str] = []
    for i in range(type_ids_size):
        idx = struct.unpack_from("<I", data, origin + type_ids_off + i * 4)[0]
        types.append(_descriptor_to_class(string_at(idx)))

    for i in range(method_ids_size):
        class_idx, _, name_idx = struct.unpack_from(
            "<HHI", data, origin + method_ids_off + i * 8
        )
        if class_idx < len(types):
            yield MethodRef(types[class_idx], string_at(name_idx))


def refs_in_apk(path: str) -> Set[MethodRef]:
    """Every method reference in every `classes*.dex` of an APK."""
    found: Set[MethodRef] = set()
    with zipfile.ZipFile(path) as zf:
        for entry in zf.namelist():
            if not (entry.startswith("classes") and entry.endswith(".dex")):
                continue
            data = zf.read(entry)
            for match in DEX_MAGIC.finditer(data):
                if match.start() % 4:
                    continue
                try:
                    found.update(_refs_in_dex(data, match.start()))
                except (struct.error, IndexError, ValueError):
                    # A malformed or packed dex is skipped rather than failing the survey:
                    # one unreadable app must not hide the findings from the other fifty.
                    continue
    return found
