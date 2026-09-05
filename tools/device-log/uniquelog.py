"""Parsing for UNIQUE's structured diagnostics as they appear in a device log.

Separate from the checks in `analyze.py` so that the parser can be tested on its own:
almost every wrong verdict this tool could produce would come from misreading a line,
and a parser that is only exercised through the checks is one whose bugs look like
engine bugs.

Nothing here is UNIQUE-version-specific beyond the event format itself, which is
`Diagnostics.format`:

    yyyy-MM-dd HH:mm:ss.SSS <L> <CHANNEL>[ u<vuid>][ <package>] <CODE>[ k=v ...]

Python 3.8+, standard library only. It has to run wherever the log lands.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional

UNIQUE_TAG = "Unique"

CHANNELS = {
    "LAUNCH", "PROCESS", "NATIVE", "STORAGE", "GOOGLE",
    "WEBVIEW", "NOTIFICATION", "CRASH", "HOOK",
}

LEVELS = {"D": "DEBUG", "I": "INFO", "W": "WARN", "E": "ERROR"}


@dataclass
class LogLine:
    """One line of a device log, whatever layout it arrived in."""

    lineno: int
    level: str
    tag: str
    message: str
    pid: Optional[int] = None
    raw: str = ""


@dataclass
class Event:
    """One UNIQUE diagnostic event, as recovered from the log."""

    lineno: int
    timestamp: str
    level: str
    channel: str
    code: str
    vuid: Optional[int]
    package: Optional[str]
    fields: Dict[str, str] = field(default_factory=dict)

    def __getitem__(self, key: str) -> Optional[str]:
        return self.fields.get(key)


# ---------------------------------------------------------------------------------
# Log layouts
# ---------------------------------------------------------------------------------
#
# Three shapes reach this tool and all three are legitimate, so the parser recognises
# the *tail* of a line — "<level> <tag>: <message>" — rather than the prefix, which is
# the part that differs:
#
#   adb logcat -v threadtime   09-05 20:17:45.169  17084 17084 I Unique  : ...
#   a recorder app's export    1788614265.175 10300 17084 17084 I Unique  : ...
#   UNIQUE's own unique.log    2026-09-05 20:17:45.169 I PROCESS PROCESS_START ...
#
# The third has no logcat framing at all; it is handled by `parse_log` falling through
# to reading the line as an event directly.

_TAGGED = re.compile(r"^(?P<prefix>.*?)\s(?P<level>[VDIWEFS])\s(?P<tag>[^:]{1,40}?)\s*:\s(?P<msg>.*)$")
_PID = re.compile(r"(?:^|\s)(\d{2,7})\s+(\d{2,7})\s+[VDIWEFS]\s")

# `2026-09-05 20:17:45.169 I PROCESS CODE k=v`
_EVENT = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s"
    r"(?P<level>[DIWE])\s"
    r"(?P<channel>[A-Z]+)\s"
    r"(?P<rest>.*)$"
)

# Field boundaries. Values may contain spaces — `message=Slot 0 already serves …` and
# `gmsVersionName=26.32.34 (260400-968093310)` both occur — so fields are split at the
# next `key=`, never at whitespace.
_FIELD_START = re.compile(r"(?:^|\s)([A-Za-z][A-Za-z0-9_.]*)=")

_VUID = re.compile(r"^u(\d+)$")


def parse_log(text: str) -> List[LogLine]:
    """Reads any of the layouts above into a flat list of lines.

    A line that carries no recognisable tag is kept with `tag=""`, because stack-trace
    continuations belong to the exception above them and dropping them would leave a
    crash with no frames.
    """
    out: List[LogLine] = []
    for i, raw in enumerate(text.splitlines(), start=1):
        if not raw.strip():
            continue
        m = _TAGGED.match(raw)
        if m:
            pid = None
            pm = _PID.search(raw)
            if pm:
                pid = int(pm.group(1))
            out.append(
                LogLine(
                    lineno=i,
                    level=m.group("level"),
                    tag=m.group("tag").strip(),
                    message=m.group("msg"),
                    pid=pid,
                    raw=raw,
                )
            )
        else:
            out.append(LogLine(lineno=i, level="", tag="", message=raw, raw=raw))
    return out


def parse_fields(rest: str) -> Dict[str, str]:
    """Splits `k=v k2=v2` where a value may itself contain spaces."""
    starts = [(m.start(1), m.group(1), m.end()) for m in _FIELD_START.finditer(rest)]
    fields: Dict[str, str] = {}
    for idx, (_, key, value_at) in enumerate(starts):
        end = starts[idx + 1][0] if idx + 1 < len(starts) else len(rest)
        fields[key] = rest[value_at:end].strip()
    return fields


def parse_event(message: str, lineno: int = 0) -> Optional[Event]:
    """Reads one `Diagnostics.format` line, or returns None if it is not one."""
    m = _EVENT.match(message.strip())
    if not m or m.group("channel") not in CHANNELS:
        return None
    rest = m.group("rest").split()
    if not rest:
        return None

    vuid: Optional[int] = None
    package: Optional[str] = None
    at = 0
    vm = _VUID.match(rest[0])
    if vm:
        vuid = int(vm.group(1))
        at = 1
        # A package always follows a vuid when both are present, and is never a bare
        # upper-case identifier — that is the event code.
        if at < len(rest) and not rest[at].isupper():
            package = rest[at]
            at += 1
    if at >= len(rest):
        return None
    code = rest[at]

    tail = m.group("rest").split(code, 1)
    field_text = tail[1] if len(tail) > 1 else ""
    return Event(
        lineno=lineno,
        timestamp=m.group("ts"),
        level=LEVELS.get(m.group("level"), m.group("level")),
        channel=m.group("channel"),
        code=code,
        vuid=vuid,
        package=package,
        fields=parse_fields(field_text),
    )


def events(lines: Iterable[LogLine]) -> List[Event]:
    """Every UNIQUE event in the log, in order."""
    out: List[Event] = []
    for line in lines:
        if line.tag and line.tag != UNIQUE_TAG:
            continue
        event = parse_event(line.message, line.lineno)
        if event is not None:
            out.append(event)
    return out
