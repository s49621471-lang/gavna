#!/usr/bin/env python3
"""Reads a device log from a UNIQUE run and says what failed, and why.

## What this is for

UNIQUE can only be judged on a phone. Everything below the Java graft — ARM64 guest
code, a real GPU, an OEM framework fork, an app that was not written to be tested —
is invisible to the emulator suite, and the emulator suite passing is exactly what
preceded a run in which no app launched at all.

But a phone log is 27,000 lines and the interesting part is thirty of them. Reading it
by eye is how a `SecurityException` naming a guest package gets mistaken for the app
being broken. So the reading is done here, mechanically, against the invariants a
healthy run satisfies:

    $ tools/device-log/analyze.py recorded.log --device device.txt

The exit status is 0 when every check passed, 1 when any failed. That makes a run on a
phone into something that can be attached to a change and argued with, rather than a
recollection of what somebody saw.

## What it reads

Any of the three layouts a UNIQUE log arrives in — `adb logcat -v threadtime`, a
recorder app's export, or `unique.log` from *Export diagnostics* — because the person
holding the phone should not have to care which one they sent.

## What it will not do

It does not look at pixels. A guest that draws a black screen with no error in the log
passes every check here, and `docs/PHYSICAL_DEVICE_TEST.md` step s04 exists for exactly
that reason. This tool answers "did anything go wrong that the engine can see", which
is a smaller question than "does the app work" and the one that can be answered without
a person watching.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Set, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import uniquelog  # noqa: E402
from uniquelog import Event, LogLine  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

PASS, FAIL, INFO = "PASS", "FAIL", "INFO"


@dataclass
class Finding:
    """One thing the log says, with the line that says it."""

    detail: str
    lineno: int = 0
    evidence: str = ""


@dataclass
class Check:
    """One invariant, its verdict, and everything that bears on it."""

    name: str
    question: str
    verdict: str = PASS
    findings: List[Finding] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    def fail(self, detail: str, lineno: int = 0, evidence: str = "") -> None:
        self.verdict = FAIL
        self.findings.append(Finding(detail, lineno, evidence))

    def note(self, text: str) -> None:
        self.notes.append(text)


# ---------------------------------------------------------------------------------
# What the engine itself declares, read from the source rather than duplicated
# ---------------------------------------------------------------------------------
#
# Two tables in this tool would otherwise be copies of two tables in the engine, and a
# copy that drifts is worse than no check at all: it would report a service as unhooked
# after it was hooked, and be ignored from then on. Both are read out of the Kotlin.

_SET_ENTRY = re.compile(r'"([^"]+)"')


def _read(path: str) -> str:
    try:
        with open(os.path.join(REPO_ROOT, path), encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def hooked_services() -> Set[str]:
    """Service names in `SystemServiceHook.TARGETS`."""
    source = _read("core/hook/src/main/kotlin/com/unique/core/hook/SystemServiceHook.kt")
    block = re.search(r"val TARGETS[^=]*=\s*listOf\((.*?)\n    \)", source, re.S)
    if not block:
        return set()
    return set(re.findall(r'ServiceTarget\(\s*"([^"]+)"', block.group(1)))


def runtime_permissions() -> Set[str]:
    """The engine's own list of permissions the user decides at runtime."""
    source = _read(
        "core/common/src/main/kotlin/com/unique/core/common/permission/PlatformPermissions.kt"
    )
    block = re.search(r"val RUNTIME: Set<String> = setOf\((.*?)\n    \)", source, re.S)
    if not block:
        return set()
    return set(_SET_ENTRY.findall(block.group(1)))


# `IRestrictionsManager$Stub$Proxy` is the most reliable thing in a refusal's stack: it
# names the AIDL interface, so the missing hook can be named rather than guessed at.
# Only the mapping from interface to `ServiceManager` name lives here; whether that name
# is hooked comes from the source above.
INTERFACE_TO_SERVICE = {
    "IActivityManager": "activity",
    "IActivityTaskManager": "activity_task",
    "IPackageManager": "package",
    "INotificationManager": "notification",
    "IAppOpsService": "appops",
    "IAlarmManager": "alarm",
    "IJobScheduler": "jobscheduler",
    "IWindowManager": "window",
    "IClipboard": "clipboard",
    "IAccountManager": "account",
    "IPermissionManager": "permissionmgr",
    "ISessionManager": "media_session",
    "IConnectivityManager": "connectivity",
    "IRestrictionsManager": "restrictions",
    "ILocaleManager": "locale",
    "IPowerManager": "power",
    "IWifiManager": "wifi",
    "ILocationManager": "location",
    "IAudioService": "audio",
    "IVibratorManagerService": "vibrator_manager",
    "IUsageStatsManager": "usagestats",
    "INetworkStatsService": "netstats",
    "IContentService": "content",
    "IShortcutService": "shortcut",
    "ISearchManager": "search",
    "ITelephony": "phone",
    "IDevicePolicyManager": "device_policy",
    "ICameraService": "media.camera",
    "IMediaRouterService": "media_router",
    "ITelecomService": "telecom",
    "IDownloadManager": "download",
    "IStorageManager": "mount",
    "ISearchManager": "search",
}

_STUB_PROXY = re.compile(r"\b(?:[\w.]+\.)?(I[A-Za-z0-9_]+)\$Stub\$Proxy\.([A-Za-z0-9_]+)\(")
_MANAGER_FRAME = re.compile(r"\bat (android\.[\w.]*?([A-Z]\w+))\.([A-Za-z0-9_]+)\(")


def _scoped_package(scope: str) -> str:
    """The guest a redirect scope belongs to, for a message that names it.

    The scope is a comma-separated list of absolute paths under
    `.../virtual/apk/<package>/<versionCode>/...`, and the event carries no package
    field of its own.
    """
    m = re.search(r"/virtual/apk/([\w.]+)/", scope)
    return m.group(1) if m else "a guest"


# ---------------------------------------------------------------------------------
# The run, reconstructed
# ---------------------------------------------------------------------------------


@dataclass
class Launch:
    """One attempt to start a virtual app, and how far it got."""

    package: str
    vuid: int
    activity: str
    lineno: int
    bootstrapped: bool = False
    rewritten: bool = False
    failure: str = ""
    failure_line: int = 0


@dataclass
class Run:
    events: List[Event]
    lines: List[LogLine]
    device: Dict[str, str] = field(default_factory=dict)
    _pid_packages: Optional[Dict[int, str]] = None

    def by_code(self, *codes: str) -> List[Event]:
        wanted = set(codes)
        return [e for e in self.events if e.code in wanted]

    @property
    def imported(self) -> Set[str]:
        out = {e["package"] for e in self.by_code("PACKAGE_IMPORTED") if e["package"]}
        out |= {e.package for e in self.events if e.package}
        out.discard("com.unique")
        return {p for p in out if p}

    @property
    def guest_pids(self) -> Set[int]:
        """Pids that were serving a guest.

        A slot announces its pid when it finishes grafting, so this is exact rather than
        inferred from a process name. It is what lets a refusal with no package name in
        it — `Neither user 10300 nor current process has …` — still be attributed to a
        guest rather than to UNIQUE or to some unrelated app on the phone.
        """
        out: Set[int] = set()
        for event in self.by_code("PROVIDER_SLOT_READY", "PROVIDER_SLOT_STARTING"):
            pid = event["pid"]
            if pid and pid.isdigit():
                out.add(int(pid))
        return out

    def package_of_pid(self, pid: Optional[int]) -> Optional[str]:
        """Which guest a process was serving, from what that process said about itself.

        A crash line and a Unity line carry a pid and nothing else, and attributing them
        needs the mapping. It is built from UNIQUE's own events, which carry both — a
        `:vappN` names its guest on every line once the graft has bound the diagnostics
        context — so the answer is what the process reported rather than a guess from a
        process name that was renamed to the guest's anyway.
        """
        if pid is None:
            return None
        if self._pid_packages is None:
            mapping: Dict[int, str] = {}
            by_lineno = {line.lineno: line for line in self.lines}
            for event in self.events:
                line = by_lineno.get(event.lineno)
                if line is None or line.pid is None:
                    continue
                package = event.package or event["package"]
                if package and package != "com.unique":
                    mapping[line.pid] = package
            self._pid_packages = mapping
        return self._pid_packages.get(pid)

    def launches(self) -> List[Launch]:
        """Pairs each `LAUNCH_REQUESTED` with what became of it.

        Matched by (package, vuid) in order rather than by a launch id, because the
        engine does not carry one. A second request for a package already running is a
        real and common case — the user tapping again after nothing happened — so each
        request is matched to the first later outcome that has not been claimed.
        """
        launches: List[Launch] = []
        for event in self.events:
            if event.code == "LAUNCH_REQUESTED":
                launches.append(
                    Launch(
                        package=event["package"] or "?",
                        vuid=int(event["vuid"] or 0),
                        activity=event["activity"] or "?",
                        lineno=event.lineno,
                    )
                )
                continue
            if not launches:
                continue
            if event.code == "BOOTSTRAP_OK":
                for launch in reversed(launches):
                    if launch.package == event["package"] and not launch.bootstrapped:
                        launch.bootstrapped = True
                        break
            elif event.code == "TRANSACTION_REWRITTEN":
                for launch in reversed(launches):
                    if launch.package == event["package"] and not launch.rewritten:
                        launch.rewritten = True
                        break
            elif event.code == "BOOTSTRAP_FAILED":
                # The diagnostic context is the process's *current* occupant, which on a
                # poisoned slot is the previous app; the package that failed is in the
                # fields.
                target = event["package"]
                for launch in reversed(launches):
                    if launch.package == target and not launch.failure:
                        launch.failure = event["code_"] or event["code"] or "BOOTSTRAP_FAILED"
                        launch.failure_line = event.lineno
                        break
        return launches


def load(log_path: str, device_path: Optional[str]) -> Run:
    with open(log_path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    lines = uniquelog.parse_log(text)
    device: Dict[str, str] = {}
    if device_path:
        with open(device_path, encoding="utf-8", errors="replace") as f:
            for raw in f:
                if ":" in raw:
                    key, _, value = raw.partition(":")
                    device[key.strip()] = value.strip()
    return Run(events=uniquelog.events(lines), lines=lines, device=device)


# ---------------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------------


def check_engine_started(run: Run) -> Check:
    check = Check("engine", "Did UNIQUE's own process start and grant itself platform access?")
    starts = [e for e in run.by_code("PROCESS_START") if e["kind"] == "CORE"]
    if not starts:
        check.fail("no PROCESS_START for the core process; this log may not cover a UNIQUE run")
        return check
    check.note(f"sdk={starts[0]['sdk']} abi={starts[0]['abi']}")
    if not run.by_code("HIDDEN_API_GRANTED"):
        check.fail("hidden API access was never granted; nothing else can work")
    if not run.by_code("NATIVE_LOADED"):
        check.fail("libunique_native never loaded")
    for event in run.by_code("IO_REDIRECT_INSTALLED"):
        who = _scoped_package(event["scope"] or "")
        status, watch = event["status"], event["watch"]
        if status == "OK":
            continue
        # NOT_IMPLEMENTED is what builds before this status existed reported for the same
        # state, and logs from them are still worth reading.
        if status in ("NOTHING_TO_HOOK", "NOT_IMPLEMENTED") and watch == "OK":
            # Expected for a guest whose libraries load from its own engine's initialiser
            # rather than from Application.onCreate. The watch is what covers those, so
            # this is only worth a note — and only a failure when the watch also missed.
            check.note(
                f"{who}: nothing to hook at bootstrap; the dlopen watch is installed and "
                f"covers libraries loaded later"
            )
            continue
        check.fail(
            f"libc IO redirection is not in effect for {who}: status={status} watch={watch}",
            event.lineno,
        )
    return check


def check_launches(run: Run) -> Check:
    check = Check("launch", "Did every launch reach the guest's own Activity?")
    launches = run.launches()
    if not launches:
        check.note("no launch was attempted in this log")
        return check
    for launch in launches:
        where = f"{launch.package} u{launch.vuid} -> {launch.activity}"
        if launch.failure:
            check.fail(f"{where}: {launch.failure}", launch.failure_line or launch.lineno)
        elif not launch.bootstrapped:
            check.fail(f"{where}: no BOOTSTRAP_OK followed the request", launch.lineno)
        elif not launch.rewritten:
            check.fail(
                f"{where}: grafted, but the launch transaction was never rewritten, so the "
                f"stub was shown instead of the guest's Activity",
                launch.lineno,
            )
    ok = sum(1 for x in launches if x.bootstrapped and x.rewritten and not x.failure)
    check.note(f"{ok}/{len(launches)} launches reached the guest's Activity")
    return check


def check_slots(run: Run) -> Check:
    check = Check("slots", "Was every process slot handed over clean?")
    for event in run.by_code("BOOTSTRAP_FAILED"):
        if event["code"] == "SLOT_ALREADY_BOUND":
            check.fail(
                f"{event['package']} was given a slot that still held {event.package}; "
                f"the slot was released without ending its process",
                event.lineno,
                event["message"] or "",
            )
    for event in run.by_code("ACTIVITY_HANDOFF_DID_NOT_HAPPEN"):
        check.fail(
            f"the stub was shown instead of {event['component']}",
            event.lineno,
            f"interceptorInstalled={event['interceptorInstalled']}",
        )
    for event in run.by_code("PROCESS_POOL_EXHAUSTED"):
        check.fail("every process slot was in use", event.lineno, event["requested"] or "")
    for event in run.by_code("SLOT_PROCESS_STALE"):
        check.note(
            f"slot {event['slot']} still had a process when it was reassigned to "
            f"{event['requested']}; it was ended first"
        )
    return check


def check_crashes(run: Run) -> Check:
    """Every distinct crash, from whichever source recorded it.

    Two sources see the same crash and neither sees all of them. UNIQUE's own
    `UNCAUGHT_EXCEPTION` record carries the instance and survives the process; the
    platform's `AndroidRuntime` line is the only trace of a crash that killed the process
    before the record could be written. Reporting both unfiltered double-counts, so they
    are folded on the exception text, which is the part both agree on.
    """
    check = Check("crash", "Did any guest crash?")
    imported = run.imported
    seen: Set[str] = set()

    def add(package: str, reason: str, lineno: int, thread: str = "") -> None:
        kind = _exception_kind(reason)
        key = f"{package}|{kind}"
        if key in seen:
            return
        seen.add(key)
        where = f" on {thread}" if thread else ""
        # The exception belongs on the line itself, not only under --verbose. Three guests
        # in the fifth phone run died of the same `SecurityException: Unknown calling
        # package name`, and a report that says only "crashed on main" three times hides
        # the one fact that identifies the cause.
        why = f": {_exception_line(reason)}" if reason.strip() else ""
        check.fail(f"{package or 'a guest'} crashed{where}{why}", lineno, reason[:200])

    for i, line in enumerate(run.lines):
        if line.tag != "AndroidRuntime" or "FATAL EXCEPTION" not in line.message:
            continue
        block = run.lines[i : i + 4]
        process = ""
        for candidate in block:
            m = re.search(r"Process: ([\w.]+)", candidate.message)
            if m:
                process = m.group(1)
                break
        if process and process not in imported:
            continue
        thread = line.message.split("FATAL EXCEPTION:", 1)[-1].strip()
        reason = next(
            (c.message.strip() for c in block[2:] if "Exception" in c.message or "Error" in c.message),
            "",
        )
        add(process, reason, line.lineno, thread)

    for event in run.by_code("UNCAUGHT_EXCEPTION"):
        add(event.package or "", event["reason"] or "", event.lineno, event["thread"] or "")
    for event in run.by_code("NATIVE_CRASH"):
        add(event.package or "", f"native: {event.fields}", event.lineno)
    return check


_EXCEPTION = re.compile(r"([A-Za-z]+(?:Exception|Error))(?::\s*([^\n]{0,60}))?")


def _exception_kind(reason: str) -> str:
    """A crash's identity for folding: the exception class and the start of its message.

    Not the whole message — the platform wraps the same failure in
    `RuntimeException: Unable to start activity …` while UNIQUE records the cause — so
    the innermost recognisable exception plus a short prefix is what makes two records of
    one crash compare equal without collapsing two genuinely different ones.
    """
    matches = _EXCEPTION.findall(reason)
    if not matches:
        return reason[:60]
    name, message = matches[-1]
    # The simple name only. The platform's record says `java.lang.Error` where UNIQUE's
    # says `Error`, and folding on the qualified name reported one crash twice.
    return f"{name.rsplit('.', 1)[-1]}:{message.strip()[:40]}"


def _exception_line(reason: str) -> str:
    """The same crash, written for a person rather than for the fold.

    [_exception_kind] is deliberately short because it is an identity — two records of one
    crash must compare equal. What goes on the report line is allowed to be longer: the
    package name inside `Unknown calling package name 'com.example.app'` is exactly the
    part a 40-character identity cuts off, and exactly the part that says which guest.
    """
    matches = _EXCEPTION.findall(reason)
    if not matches:
        return reason.strip()[:110]
    name, message = matches[-1]
    message = message.strip()
    return f"{name}: {message[:100]}" if message else name


def check_platform_refusals(run: Run) -> Check:
    """The failure class that took two apps down on a real phone.

    A `SecurityException` raised inside a guest is almost never the app's fault: it means
    a call went out to `system_server` carrying a package name that does not belong to
    UNIQUE's uid, and the service it went to is not proxied. Three shapes of the same
    thing, all seen in one run:

        Only system may: get application restrictions for other user/app com.openai.chatgpt
        Package com.openai.chatgpt does not belong to 10300
        getApplicationLocales: Neither user 10300 nor current process has …

    Only the first names the guest, so recognising them by message text misses two out of
    three. They are recognised by *where they happened* instead — a pid that announced
    itself as a slot — and the `IXxx$Stub$Proxy` frame in the stack names the service to
    hook rather than leaving it to be guessed at.
    """
    check = Check("platform", "Did any call go out under the guest's name and get refused?")
    hooked = hooked_services()
    imported = run.imported
    guest_pids = run.guest_pids
    if not hooked:
        check.note("SystemServiceHook.TARGETS could not be read; hook coverage not checked")

    seen: Set[str] = set()
    for i, line in enumerate(run.lines):
        if "SecurityException" not in line.message:
            continue
        in_guest = line.pid in guest_pids if line.pid is not None else False
        names_guest = any(p and p in line.message for p in imported)
        if not (in_guest or names_guest or "does not belong to" in line.message):
            continue

        interface = method = ""
        entry = ""
        for candidate in run.lines[i : i + 30]:
            if not interface:
                m = _STUB_PROXY.search(candidate.message)
                if m:
                    interface, method = m.group(1), m.group(2)
                    continue
            # The manager frame is the one *after* the Stub$Proxy: the app-facing API.
            # Taken before it, the frame is whatever wrapped the exception on its way up —
            # `ActivityThread.performLaunchActivity` — which names the symptom, not the call.
            if interface and not entry:
                m = _MANAGER_FRAME.search(candidate.message)
                if m and "Parcel" not in m.group(1):
                    entry = f"{m.group(2)}.{m.group(3)}"
        if not interface:
            continue

        key = f"{interface}.{method}"
        if key in seen:
            continue
        seen.add(key)

        service = INTERFACE_TO_SERVICE.get(interface, "")
        if service and service in hooked:
            where = (
                f"`{service}` is in SystemServiceHook.TARGETS in the current tree — either "
                f"this log predates that, or the rewrite did not reach this call"
            )
        elif service:
            where = f"add `{service}` to SystemServiceHook.TARGETS and hook its caller package"
        else:
            where = f"{interface} is not proxied and this tool knows no service name for it"
        entry = entry or f"{interface}.{method}"
        check.fail(f"{entry} was refused: {where}", line.lineno, line.message.strip()[:200])
    return check


def check_permissions(run: Run) -> Check:
    """Install-time permissions must never be denied — there is no way to grant them."""
    check = Check("permissions", "Was any permission denied that no user could have granted?")
    runtime = runtime_permissions()
    if not runtime:
        check.note("PlatformPermissions.RUNTIME could not be read; denials not classified")
        return check

    denied: Dict[str, int] = {}
    for event in run.by_code("PERMISSION_CHECK"):
        name = event["permission"] or ""
        if event["result"] != "DENIED" or not name:
            continue
        denied[name] = denied.get(name, 0) + 1
        if name not in denied:
            continue
    # A denial the *host* caused is never the user's choice, whatever kind of permission
    # it is. `blockedByHost=true` means UNIQUE itself does not hold it, and for the
    # external-storage pair that is permanent: since Android 13 the platform auto-denies
    # them to any app targeting 33 or later, so no dialog exists and no setting helps.
    # Filed under "the user may have refused it" they were invisible, which is how a game
    # spent a whole run reading its own assets as missing.
    host_blocked: Dict[str, int] = {}
    for event in run.by_code("PERMISSION_RESULT_RECORDED"):
        if event["granted"] == "false" and event["blockedByHost"] == "true":
            name = event["permission"] or "?"
            host_blocked[name] = host_blocked.get(name, 0) + 1
    for name, count in sorted(host_blocked.items()):
        check.fail(
            f"{name} was refused to the guest {count}x because UNIQUE does not hold it — "
            f"not a decision any user could have made or can undo",
            0,
        )
    for event in run.by_code("HOST_PERMISSION_REFUSED"):
        if event["permanent"] == "true":
            check.fail(
                f"the platform will not grant {event['permissions']} to UNIQUE and will "
                f"not ask again; a guest that needs it can never have it",
                event.lineno,
            )

    for name, count in sorted(denied.items()):
        if name in runtime or name.startswith("android.permission.health."):
            check.note(f"{name} denied {count}x — a runtime permission, so this may be the user's choice")
        elif name.startswith("android.permission."):
            check.fail(
                f"{name} denied {count}x, but it is an install-time permission: no dialog "
                f"exists for it and the guest declared it",
                0,
            )
        else:
            check.note(
                f"{name} denied {count}x — defined by another app, which UNIQUE does not hold"
            )
    for event in run.by_code("PERMISSIONS_BOUND"):
        if event["installTime"] is not None:
            check.note(
                f"{event['package']}: {event['declared']} declared, "
                f"{event['installTime']} granted at install, {event['runtime']} for the user"
            )
    return check


def check_hooks(run: Run) -> Check:
    check = Check("hooks", "Did every shim bind to something?")
    for event in run.by_code("HOOK_MATCHED_NOTHING"):
        check.fail(
            f"no method of {event['interface']} matched any shim for service "
            f"{event['service']}",
            event.lineno,
        )
    for event in run.by_code("HOOK_BIND_FAILED"):
        check.fail(
            f"service {event['service']}: {event['unbound']} bound to nothing "
            f"(sdk {event['sdk']})",
            event.lineno,
        )
    for event in run.by_code("IDENTITY_HOOK_FAILED", "PERMISSION_MANAGER_HOOK_SKIPPED"):
        check.fail(
            f"service {event['service'] or '?'} was not proxied: {event['reason']}", event.lineno
        )
    for event in run.by_code("PERMISSION_CACHE_STILL_ON"):
        check.fail(
            "the framework's client-side permission cache could not be disabled; every "
            "answer after the first is stale",
            event.lineno,
            event["detail"] or "",
        )
    for event in run.by_code("IDENTITY_HOOKS_INSTALLED"):
        if event["skipped"]:
            check.note(f"not available on this device: {event['skipped']}")
    return check


def check_providers(run: Run) -> Check:
    check = Check("providers", "Did the guest's ContentProviders publish and resolve?")
    for event in run.by_code("PROVIDER_PUBLISH_FAILED"):
        check.fail(
            f"{event['provider']} did not publish", event.lineno, (event["error"] or "")[:200]
        )
    for event in run.by_code("PROVIDER_INSTALL_FAILED", "PROVIDER_BIND_BOOTSTRAP_FAILED"):
        check.fail(f"{event.code} for {event['package']}", event.lineno, event["error"] or "")
    for event in run.by_code("PROVIDER_ROUTE_UNKNOWN"):
        check.note(f"an authority was asked for that no instance declares (u{event['vuid']})")
    return check


def check_isolation(run: Run) -> Check:
    """Where a guest's intents went, when they did not stay in the guest.

    Not a failure. An app opening a browser or a share sheet leaves its own process on a
    real device too, and trapping that inside the guest would break behaviour that works.
    It is reported because it is the one way a virtual app reaches an *installed* app with
    that app's data, and because it explains an outcome that otherwise looks like a bug:
    Gemini launched into a fresh instance and showed the real account, because its shell
    activity fires an implicit `ACTION_VIEW` that the host's Google app answers.
    """
    check = Check("isolation", "Did any of a guest's intents leave the virtual space?")
    for event in run.by_code("ACTIVITY_IMPLICIT_LEFT_GUEST", "ACTIVITY_IMPLICIT_NO_GUEST_MATCH"):
        handlers = event["handledByHost"] or "not recorded"
        check.note(
            f"{event.package or event['package']}: {event['action']} left the guest — "
            f"handled by {handlers}"
        )
    for event in run.by_code("ACTIVITY_IMPLICIT_HOST_PREFERRED"):
        check.note(
            f"{event['package']}: {event['action']} went to the host even though the guest "
            f"declares {event['guestMatches']} filter(s) for it"
        )
    return check


def check_ui(run: Run) -> Check:
    check = Check("ui", "Did UNIQUE's own interface survive?")
    for i, line in enumerate(run.lines):
        if line.tag != "flutter" or "Unhandled Exception" not in line.message:
            continue
        frame = ""
        for candidate in run.lines[i + 1 : i + 4]:
            if "package:unique_ui" in candidate.message:
                frame = candidate.message.strip()
                break
        check.fail(
            line.message.split("Unhandled Exception:", 1)[-1].strip()[:160], line.lineno, frame
        )
    # One throw repeats on every rebuild of the screen; the first is the finding.
    if len(check.findings) > 1:
        first = check.findings[0]
        check.findings = [Finding(f"{first.detail} (x{len(check.findings)})", first.lineno, first.evidence)]
    return check


def check_known_limits(run: Run) -> Check:
    """Deliberate refusals. Not failures — but a run that hits many is worth seeing."""
    check = Check("limits", "Which known-unsupported paths did this run reach?")
    codes = {
        "PENDING_INTENT_RECEIVER_UNSUPPORTED": "a PendingIntent aimed at a guest receiver",
        "SERVICE_INTENT_IMPLICIT": "an implicit service intent no service of the guest matched",
        "ALARM_EXACT_UNAVAILABLE": "exact alarms, which UNIQUE does not hold the permission for",
        "APP_LOCALE_SET_UNSUPPORTED": "a guest setting its own app locale",
        "FGS_TYPE_UNSUPPORTED": "a foreground-service type UNIQUE cannot declare",
    }
    for code, description in codes.items():
        hits = run.by_code(code)
        if hits:
            packages = sorted({e.package or e["package"] or "" for e in hits} - {""}) or ["?"]
            check.note(f"{description}: {len(hits)}x ({', '.join(packages)})")
    return check


def check_rendering(run: Run) -> Check:
    """Whether any guest was put on the software rasteriser.

    Added because a whole phone run went by without this being named. Every guest UNIQUE
    had ever launched was rendering in software — the substituted `ActivityInfo` carried
    no `flags`, and `Activity.attach` reads exactly `FLAG_HARDWARE_ACCELERATED` out of it
    — and the only evidence in 39,958 lines was one app's crash, which reads like the
    app's bug until you know what to look for:

        IllegalArgumentException: Software rendering doesn't support drawRenderNode

    Two signals, because they fail at different times. The crash is loud and late; a
    `drawSoftware` frame in any stack from a guest process is the same fact, quietly,
    whether or not anything crashed.
    """
    check = Check("render", "Was any guest rendering in software?")
    seen: Set[str] = set()
    for line in run.lines:
        if "Software rendering doesn't support" in line.message:
            if "renderNode" in seen:
                continue
            seen.add("renderNode")
            check.fail(
                "a guest drew through a RenderNode on the software rasteriser — its "
                "ActivityInfo has no FLAG_HARDWARE_ACCELERATED",
                line.lineno,
                line.message.strip()[:200],
            )
        elif "ViewRootImpl.drawSoftware" in line.message and "drawSoftware" not in seen:
            seen.add("drawSoftware")
            check.fail(
                "a guest's window was drawn by ViewRootImpl.drawSoftware, so hardware "
                "acceleration was off for it",
                line.lineno,
                line.message.strip()[:200],
            )
    return check


def check_startup_refusals(run: Run) -> Check:
    """Refusals that make an app exit rather than misbehave.

    A crash is visible; these are not. Play's licence check binds to a service guarded by
    `com.android.vending.CHECK_LICENSE`, and every application Google re-signs with PAIRIP
    treats a failed bind as tampering:

        E LicenseClient: Not allowed to bind with the licensing service
        I System.exit called, status: 0

    There is no exception, no tombstone and no UNIQUE event — the app simply stops, and the
    log says only that a process went away. The same shape covers Play services being told
    the app has no manifest, which throws on a worker thread where nothing catches it.
    """
    check = Check("startup", "Did an app refuse to start for a reason UNIQUE can fix?")
    # Folded on the cause, not reported per line: the licence client retries three times
    # per launch and the log carried twenty copies of one fact, which buries the others.
    causes: Dict[str, Tuple[int, int, str]] = {}

    def add(cause: str, line: LogLine) -> None:
        first, count, evidence = causes.get(cause, (line.lineno, 0, line.message.strip()[:200]))
        causes[cause] = (first, count + 1, evidence)

    exits = 0
    for line in run.lines:
        message = line.message
        if "Not allowed to bind" in message and "licensing" in message.lower():
            add(
                "Play's licence check could not bind: UNIQUE must declare "
                "com.android.vending.CHECK_LICENSE, and a PAIRIP-signed app exits without it",
                line,
            )
        elif "com.google.android.gms.version" in message and "does not exist" in message:
            add(
                "Google Play services found no gms.version meta-data: "
                "ApplicationInfo.metaData was not populated for the guest",
                line,
            )
        elif "System.exit called" in message:
            exits += 1
    for cause, (lineno, count, evidence) in causes.items():
        suffix = f" (x{count})" if count > 1 else ""
        check.fail(cause + suffix, lineno, evidence)
    if exits:
        check.note(f"a guest called System.exit {exits}x — an app deciding to stop, not a crash")
    return check


def check_orientation(run: Run) -> Check:
    """Whether a guest that declared an orientation was given it.

    The platform builds a window's orientation from the `ActivityRecord` it made in
    `system_server` from the *stub's* manifest entry, which says `unspecified`. UNIQUE has
    to put the guest's own back with `setRequestedOrientation`, and when it does it says
    so; a launch with no such event is one where the app got whatever the phone was
    holding.
    """
    check = Check("orientation", "Was a guest's declared orientation applied?")
    applied = run.by_code("ACTIVITY_ORIENTATION_APPLIED")
    for event in applied:
        if (event["applied"] or "true") != "true":
            check.fail(
                f"{event['activity']} declares orientation {event['orientation']} and the "
                "platform refused it",
                event.lineno,
            )
    if applied:
        check.note(f"{len(applied)} activity launches carried a declared orientation")
    else:
        check.note("no launch in this run declared an orientation")
    return check


def check_storage(run: Run) -> Check:
    """Could a guest read its own files — its expansion files above all?

    Two halves, and a game needs both. The permission half is UNIQUE's answer to
    `checkSelfPermission(READ_EXTERNAL_STORAGE)`; the files half is whether the
    instance's own `Android/obb/<pkg>` has anything in it. Either one missing looks
    identical from inside the app, and identical to a corrupt download:

        I Unity: No permission to read external storage. Skipping OBB loading.   (x156)

    That line is the app's own and is the strongest evidence there is, so it is read
    directly rather than inferred from UNIQUE's events.

    A `SOURCE_UNREADABLE` finding used to say "grant UNIQUE all-files access". That was
    wrong: `MANAGE_EXTERNAL_STORAGE` does not cover `Android/data` or `Android/obb`, so
    the grant cannot help and the user who gave it saw no change. The engine no longer
    infers the outcome from a hidden directory, and this check no longer prescribes the
    grant — see `check_obb_advice` for the assertion that keeps both true.
    """
    check = Check("storage", "Could a guest read its own external storage and expansion files?")

    skipped: Dict[str, int] = {}
    for line in run.lines:
        if "Skipping OBB loading" not in line.message:
            continue
        package = run.package_of_pid(line.pid) or "a guest"
        skipped[package] = skipped.get(package, 0) + 1
    for package, count in sorted(skipped.items()):
        check.fail(
            f"{package} skipped its own expansion files {count}x for lack of "
            f"READ_EXTERNAL_STORAGE — the permission is UNIQUE's to answer, and the "
            f"storage it names is a directory UNIQUE owns",
            0,
        )

    for event in run.by_code("GUEST_OBB_IMPORT", "GUEST_EXTERNAL_DATA_IMPORT"):
        outcome = event["outcome"]
        if outcome == "SOURCE_UNREADABLE":
            check.fail(
                f"{event['package']}: {event['source'] or 'the source directory'} could "
                f"not be read; add the files through the instance's file browser "
                f"(all-files access does not cover Android/obb)",
                event.lineno,
                event["detail"] or "",
            )
        elif outcome == "IMPORTED":
            check.note(f"{event['package']}: imported {event['files']} file(s), {event['bytes']} bytes")

    for event in run.by_code("EXTERNAL_ROOT_UNAVAILABLE", "EXTERNAL_VOLUME_SHAPE_UNKNOWN"):
        check.fail(
            f"the instance's external storage was not installed: {event.code}",
            event.lineno,
            event["path"] or event["class"] or "",
        )
    return check


_REFUSED_CALLER = re.compile(r"Unknown calling package name")


def check_google_stack(run: Run) -> Check:
    """Was a guest told the device has no Play services when it has?

    This check exists because a build shipped that told every app exactly that, and
    nothing in the previous fifteen checks noticed:

        GOOGLE_ENVIRONMENT gmsPresent=true gmsVersionCode=263234035
        GOOGLE_STACK_HIDDEN hidden=true reason=SDK_TOO_OLD gmsVersion=12451000   (x3)
        W GooglePlayServicesUtil: com.openai.chatgpt requires Google Play services,
            but they are missing.

    The phone had GmsCore 26.32.34 installed. `gmsVersion=12451000` is the guest's
    `com.google.android.gms.version` meta-data — the *minimum* GmsCore version the client
    accepts, frozen by Google for years — and three unrelated apps declared the same
    number, which is what gives it away as a constant rather than a version. Hiding is
    legitimate for a guest that has proved it cannot use the real stack; hiding it from a
    guest that has proved nothing is a lie the user reads as "install Play services".

    So the pairing is: the device has Play services, and a guest was told it does not.
    Which is asserted against this run, so a rule of that shape cannot come back quietly.
    """
    check = Check("google", "Was a guest told Play services is missing when the phone has it?")

    present = None
    for event in run.by_code("GOOGLE_ENVIRONMENT"):
        if event["gmsPresent"] is not None:
            present = event["gmsPresent"] == "true"
            check.note(
                f"the phone has Play services {event['gmsVersionName'] or event['gmsVersionCode'] or ''}".strip()
                if present else "the phone has no Play services"
            )
            break

    hidden: Dict[str, "Event"] = {}
    for event in run.by_code("GOOGLE_STACK_HIDDEN"):
        if event["hidden"] == "false":
            continue
        hidden[event["reason"] or "?"] = event

    for reason, event in sorted(hidden.items()):
        if reason in ("AUTO_HIDDEN_AFTER_CRASH", "OVERRIDDEN"):
            # Earned: this instance died of the refusal, or a person asked for it.
            check.note(f"hidden from an instance that earned it ({reason})")
            continue
        if present:
            check.fail(
                f"Play services is installed on this phone and a guest was told it is "
                f"not, on the strength of {reason} — an app that believes that shows "
                f"\"install Google Play services\" and stops",
                event.lineno,
                event["detail"] or "",
            )

    missing = sorted({
        line.message.split(" requires", 1)[0].strip()
        for line in run.lines
        if line.tag == "GooglePlayServicesUtil" and "but they are missing" in line.message
    })
    for package in missing:
        if present:
            check.fail(
                f"{package} looked for Play services inside its instance and did not "
                f"find it, on a phone that has it",
                0,
            )
        else:
            check.note(f"{package} wants Play services, which this phone does not have")

    # Whether the calling-package rewrite is doing its job. A run with refusals and no
    # rewrites means the broker was never wrapped; a run with both means it was wrapped
    # and something still got through, which is a different bug and worth telling apart.
    rewritten = run.by_code("GMS_CALLING_PACKAGE_REWRITTEN")
    wrapped = run.by_code("GMS_BROKER_WRAPPED")
    refused = [
        line for line in run.lines
        if _REFUSED_CALLER.search(line.message)
    ]
    if wrapped:
        check.note(f"the Play services broker was wrapped for {len(wrapped)} bind(s)")
    if rewritten:
        check.note(f"{len(rewritten)} request(s) went out under UNIQUE's own name")
    if refused and not wrapped:
        check.fail(
            f"Play services refused a guest's identity {len(refused)}x and the broker was "
            f"never wrapped — the bind did not go through the connection UNIQUE corrects",
            refused[0].lineno,
            refused[0].message.strip()[:160],
        )
    elif refused:
        check.fail(
            f"Play services still refused a guest's identity {len(refused)}x with the "
            f"broker wrapped — a request reached it by a route the rewrite does not cover",
            refused[0].lineno,
            refused[0].message.strip()[:160],
        )

    # The one failure the rewrite cannot fix, named so it is not mistaken for one it can.
    for line in run.lines:
        if "DEVELOPER_ERROR" not in line.message:
            continue
        package = run.package_of_pid(line.pid) or "a guest"
        check.note(
            f"{package}: Google answered DEVELOPER_ERROR — sign-in identifies an app by "
            f"package and signing certificate, and inside UNIQUE the call arrives as "
            f"UNIQUE. Not fixable without in-space Play services"
        )
        break
    return check

_HOOKED_LIBRARY = re.compile(r"hooked \d+ new slot\(s\) after loading (\S+)")


def check_native_hooks(run: Run) -> Check:
    """Did a native crash follow a library UNIQUE patched?

    The path redirector writes one pointer into each guest library's GOT. That is safe
    for ordinary code and is not safe for a code-virtualization protector, which checks
    its own relocations and answers a patched slot by jumping into generated code with a
    corrupt dispatch value. What comes back is a crash with UNIQUE's name nowhere in it:

        io_redirect: hooked 22 new slot(s) after loading …/libgrave.so (22 total)
        E CRASH: signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x7dd33219f7
        E CRASH:   #00 pc 00000000000009f7  <anonymous:0000007dd3321000>

    Nobody reading that tombstone would suspect a GOT write, so the pairing is done
    here: a native fatal signal in a process where a guest library was hooked names the
    libraries to try excluding, newest first, because the last one hooked is the one the
    crash followed.
    """
    check = Check("native", "Did a native crash follow a library UNIQUE hooked?")

    hooked_by_pid: Dict[int, List[str]] = {}
    for line in run.lines:
        if line.tag != "UniqueNative" or line.pid is None:
            continue
        m = _HOOKED_LIBRARY.search(line.message)
        if m:
            hooked_by_pid.setdefault(line.pid, []).append(m.group(1).rsplit("/", 1)[-1])
        if "excluded (not hooked, on purpose)" in line.message:
            check.note(f"left alone on purpose: {line.message.rsplit(':', 1)[-1].strip()}")

    reported: Set[int] = set()
    for index, line in enumerate(run.lines):
        fatal = (
            (line.tag in ("CRASH", "DEBUG") and "signal" in line.message)
            or (line.tag == "libc" and "Fatal signal" in line.message)
        )
        if not fatal or line.pid is None or line.pid in reported:
            continue
        reported.add(line.pid)
        package = run.package_of_pid(line.pid) or "a guest"
        libraries = hooked_by_pid.get(line.pid)

        landing = _wild_jump_landing(run.lines, index)
        if landing is not None:
            # A jump to an address that is not instruction-aligned, into a page that is
            # not a library. Compiled code cannot produce the first, and a GOT slot UNIQUE
            # has patched points at a real function in a real library, so it cannot
            # produce either. Reported as its own thing rather than blamed on the last
            # library that happened to load, because that blame sent one investigation
            # down a wrong path already.
            check.fail(
                f"{package} jumped to an unaligned address in {landing} — not a hooked "
                f"library, and not something compiled code produces; the pointer it "
                f"followed was already wrong",
                line.lineno,
                line.message.strip()[:160],
            )
            continue

        if not libraries:
            continue
        check.fail(
            f"{package} died on a native signal after UNIQUE patched slots while "
            f"{', '.join(reversed(libraries))} were loading — try the last one first in "
            f"runtime/native/<vuid>/<package>.exclude",
            line.lineno,
            line.message.strip()[:160],
        )
    return check


_TOP_FRAME = re.compile(r"#00 pc [0-9a-f]+\s+(\S+)")


def _wild_jump_landing(lines: List[LogLine], index: int) -> Optional[str]:
    """Where a crash landed, when it landed somewhere no code should be.

    Two facts together, and neither alone says anything. `BUS_ADRALN` means the program
    counter was not instruction-aligned — compiled code never produces that. And the top
    frame names something that is not a shared library: an anonymous mapping, a `memfd`,
    or a file marked `(deleted)`. Two physical runs of one game produced both, at the
    same offset into the page, and the second run had the library UNIQUE hooked excluded:

        run 6  #00 pc …9f7  <anonymous:0000007dd3321000>
        run 7  #00 pc …9f7  /memfd:gralloc_shared_memory (deleted)

    Which is why this is reported as itself rather than attributed to whichever library
    loaded last. A pointer was already wrong before the jump; the page it happened to
    land in is where the allocator had got to, and says nothing about the cause.

    Indexed by position in the parsed list rather than by `lineno`, because the parser
    drops lines it does not recognise and the two stop agreeing after the first one.
    """
    if "BUS_ADRALN" not in lines[index].message:
        return None
    for candidate in lines[index : index + 40]:
        match = _TOP_FRAME.search(candidate.message)
        if match is None:
            continue
        where = match.group(1)
        if where.startswith("<anonymous") or where.startswith("/memfd:"):
            return where
        if "(deleted)" in candidate.message:
            return where
        return None
    return None


CHECKS = (
    check_engine_started,
    check_launches,
    check_slots,
    check_crashes,
    check_platform_refusals,
    check_permissions,
    check_storage,
    check_google_stack,
    check_native_hooks,
    check_hooks,
    check_providers,
    check_isolation,
    check_ui,
    check_rendering,
    check_startup_refusals,
    check_orientation,
    check_known_limits,
)


def run_checks(run: Run) -> List[Check]:
    return [fn(run) for fn in CHECKS]


# ---------------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------------


def report(run: Run, checks: Sequence[Check], verbose: bool = False) -> str:
    out: List[str] = []
    if run.device:
        wanted = ("MANUFACTURER", "MODEL", "SDK", "SUPPORTED_ABIS")
        summary = " ".join(f"{k}={run.device[k]}" for k in wanted if k in run.device)
        out.append(summary)
    out.append(f"{len(run.events)} UNIQUE events in {len(run.lines)} lines")
    out.append("")

    for check in checks:
        mark = "FAIL" if check.verdict == FAIL else "ok  "
        out.append(f"[{mark}] {check.name:<12} {check.question}")
        for finding in check.findings:
            where = f"line {finding.lineno}: " if finding.lineno else ""
            out.append(f"         - {where}{finding.detail}")
            if finding.evidence and verbose:
                out.append(f"           {finding.evidence}")
        for note in check.notes:
            out.append(f"         . {note}")
    out.append("")

    failed = [c.name for c in checks if c.verdict == FAIL]
    if failed:
        out.append(f"RESULT: FAIL ({len(failed)} of {len(checks)}: {', '.join(failed)})")
    else:
        out.append(f"RESULT: OK ({len(checks)} checks)")
    return "\n".join(out)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Reads a device log from a UNIQUE run and says what failed.",
    )
    parser.add_argument("log", help="the recorded log: logcat, a recorder export, or unique.log")
    parser.add_argument("--device", help="the device description file, if one was captured")
    parser.add_argument(
        "-v", "--verbose", action="store_true", help="print the log line behind each finding"
    )
    args = parser.parse_args(argv)

    run = load(args.log, args.device)
    checks = run_checks(run)
    print(report(run, checks, args.verbose))
    return 1 if any(c.verdict == FAIL for c in checks) else 0


if __name__ == "__main__":
    sys.exit(main())
