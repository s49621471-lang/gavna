#!/usr/bin/env python3
"""Which platform APIs do real apps actually call, and does UNIQUE proxy them?

    tools/apk-survey/survey.py /path/to/apks/*.apk

## Why this exists

Three system services went unproxied until a phone found them, and each one killed a
real app on its first screen:

    RestrictionsManager.getApplicationRestrictions   -> ChatGPT died in Activity.onCreate
    LocaleManager.getApplicationLocales              -> ChatGPT's network thread died
    ConnectivityManager.getNetworkCapabilities       -> the guest saw no network

The emulator suite passed 38 of 38 against all three. It was not the emulator's fault and
a better emulator would not have helped: the suite runs against `com.unique.probe`, an app
written to be probed, and the probe calls none of those APIs. The gap was never
*emulator versus phone* — it was *a test app versus a real one*.

So this reads real apps instead. Not by running them, which needs a device: by reading the
`method_ids` table out of their DEX, which lists every method they reference. A 22 MB APK
yields forty thousand references in a tenth of a second, and the first random app from
F-Droid calls both `LocaleManager.getApplicationLocales` and
`ConnectivityManager.getNetworkCapabilities`.

That is the whole point. Both bugs were findable here, from an open-source app, with no
device, before anybody installed anything.

## What it cannot say

A reference is not a call. An app that references `getApplicationLocales` on a code path
it never takes still shows up here, and one that reaches the API through reflection does
not show up at all. So this ranks *risk*, not behaviour: a service that forty apps
reference is one to proxy before a phone finds it, and a service nobody references can
wait. It does not replace `docs/PHYSICAL_DEVICE_TEST.md`; it decides what to fix first.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import defaultdict
from typing import Dict, List, Set

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import dexrefs  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


# The framework classes whose calls carry the caller's own package name into
# `system_server`, mapped to the `ServiceManager` name behind each. Only these matter for
# the failure this tool exists to catch: the platform checks that name against the calling
# uid, UNIQUE's uid is the host's, and an unproxied service answers with a
# `SecurityException` naming the guest.
#
# The manager is what an app writes; the service is what `SystemServiceHook.TARGETS`
# holds. Keeping both is what lets a finding say "add `locale`" rather than "something
# about locales".
MANAGER_TO_SERVICE: Dict[str, str] = {
    "android.app.ActivityManager": "activity",
    "android.app.AlarmManager": "alarm",
    "android.app.NotificationManager": "notification",
    "android.app.AppOpsManager": "appops",
    "android.app.job.JobScheduler": "jobscheduler",
    "android.app.usage.UsageStatsManager": "usagestats",
    "android.app.usage.NetworkStatsManager": "netstats",
    "android.app.LocaleManager": "locale",
    "android.app.SearchManager": "search",
    "android.app.admin.DevicePolicyManager": "device_policy",
    "android.content.RestrictionsManager": "restrictions",
    "android.content.ClipboardManager": "clipboard",
    "android.content.ContentResolver": "content",
    "android.content.pm.PackageManager": "package",
    "android.content.pm.ShortcutManager": "shortcut",
    "android.net.ConnectivityManager": "connectivity",
    "android.net.wifi.WifiManager": "wifi",
    "android.location.LocationManager": "location",
    "android.media.AudioManager": "audio",
    "android.media.session.MediaSessionManager": "media_session",
    "android.media.MediaRouter": "media_router",
    "android.os.PowerManager": "power",
    "android.os.storage.StorageManager": "mount",
    "android.os.Vibrator": "vibrator_manager",
    "android.os.VibratorManager": "vibrator_manager",
    "android.accounts.AccountManager": "account",
    "android.telephony.TelephonyManager": "phone",
    "android.telecom.TelecomManager": "telecom",
    "android.hardware.camera2.CameraManager": "media.camera",
    "android.app.DownloadManager": "download",
    "android.view.WindowManager": "window",
    "android.appwidget.AppWidgetManager": "appwidget",
    "android.view.inputmethod.InputMethodManager": "input_method",
    "android.permission.PermissionManager": "permissionmgr",
}


def _read(path: str) -> str:
    try:
        with open(os.path.join(REPO_ROOT, path), encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def declared_services() -> Set[str]:
    """Service names in `SystemServiceHook.TARGETS`."""
    source = _read("core/hook/src/main/kotlin/com/unique/core/hook/SystemServiceHook.kt")
    block = re.search(r"val TARGETS[^=]*=\s*listOf\((.*?)\n    \)", source, re.S)
    if not block:
        return set()
    return set(re.findall(r'ServiceTarget\(\s*"([^"]+)"', block.group(1)))


def installed_services() -> Set[str]:
    """Service names some hook actually installs, which is a smaller set than [declared_services].

    Being in `TARGETS` only says a service *could* be proxied; it is a table of interface
    names, and a name can sit in it with nothing ever calling `install` for it. Three do
    exactly that today — `window`, `account` and `media_session` — and `window` is
    referenced by 45 of 49 real apps.

    Reporting those as proxied is the failure this project's own rules name: a shim that
    binds to nothing looks exactly like one that works. So the two sets are read
    separately and the report distinguishes them.

    Two install shapes exist and both are read:
      - `SystemServiceHook.TARGETS.first { it.serviceName == "x" }` in a dedicated hook;
      - the table in `VirtualIdentityHooks.CALLER_PACKAGE_SERVICES`, installed in a loop.
    """
    found: Set[str] = set()
    for name in ("VirtualPackageManagerHook", "VirtualActivityManagerHook",
                 "VirtualActivityTaskManagerHook", "VirtualAppOpsHook",
                 "VirtualNotificationHook", "VirtualJobSchedulerHook",
                 "VirtualPermissions", "VirtualIdentityHooks"):
        source = _read(f"core/vam/src/main/kotlin/com/unique/core/vam/{name}.kt")
        found.update(re.findall(r'serviceName == "([^"]+)"', source))

    identity = _read("core/vam/src/main/kotlin/com/unique/core/vam/VirtualIdentityHooks.kt")
    block = re.search(
        r"CALLER_PACKAGE_SERVICES[^=]*=\s*listOf\((.*?)\n    \)", identity, re.S
    )
    if block:
        found.update(re.findall(r'"([^"]+)" to "', block.group(1)))
    return found


def deliberate_omissions() -> Set[str]:
    """Services `VirtualIdentityHooks` records as *deliberately* not proxied.

    Without this the survey nags for ever about two names that were considered and
    declined — `search`, whose interface carries no caller package at all, and `window`,
    where the evidence for hooking it does not exist. A tool that keeps reporting a
    decision as a defect is one people stop reading.
    """
    source = _read("core/vam/src/main/kotlin/com/unique/core/vam/VirtualIdentityHooks.kt")
    block = re.search(r"NOT_PROXIED_ON_PURPOSE\s*=\s*setOf\(([^)]*)\)", source)
    if not block:
        return set()
    return set(re.findall(r'"([^"]+)"', block.group(1)))


def survey(paths: List[str]) -> Dict[str, Dict[str, object]]:
    """For each framework manager: which apps reference it, and which methods."""
    found: Dict[str, Dict[str, object]] = defaultdict(
        lambda: {"apps": set(), "methods": set()}
    )
    for path in paths:
        app = os.path.basename(path).split("_")[0]
        try:
            refs = dexrefs.refs_in_apk(path)
        except Exception as exc:  # noqa: BLE001 - one bad APK must not stop the survey
            print(f"  ! {app}: unreadable ({exc})", file=sys.stderr)
            continue
        for ref in refs:
            if ref.cls in MANAGER_TO_SERVICE:
                entry = found[ref.cls]
                entry["apps"].add(app)  # type: ignore[union-attr]
                entry["methods"].add(ref.name)  # type: ignore[union-attr]
    return found


def report(
    found: Dict[str, Dict[str, object]],
    total: int,
    installed: Set[str],
    declared: Set[str] | None = None,
    by_design: Set[str] | None = None,
) -> str:
    declared = declared if declared is not None else installed
    by_design = by_design if by_design is not None else deliberate_omissions()
    rows = sorted(found.items(), key=lambda kv: -len(kv[1]["apps"]))  # type: ignore[arg-type]
    out: List[str] = [f"{total} app(s) surveyed", ""]

    missing: List[str] = []
    dead: List[str] = []
    out.append(f"{'service':<18}{'framework class':<45}{'apps':>5}  proxied")
    out.append("-" * 82)
    for cls, entry in rows:
        service = MANAGER_TO_SERVICE[cls]
        apps = len(entry["apps"])  # type: ignore[arg-type]
        where = f"{cls.rsplit('.', 1)[-1]}, {apps}/{total} apps"
        if service in installed:
            mark = "yes"
        elif service in by_design:
            mark = "by design"
        elif service in declared:
            mark = "DECLARED ONLY"
            dead.append(f"{service} ({where})")
        else:
            mark = "NO"
            missing.append(f"{service} ({where})")
        out.append(f"{service:<18}{cls:<45}{apps:>5}  {mark}")

    if dead:
        out.append("")
        out.append("In TARGETS but nothing installs them — they read as done and are not:")
        for row in dead:
            out.append(f"  - {row}")
    if missing:
        out.append("")
        out.append("Not in TARGETS at all, and real apps call them:")
        for row in missing:
            out.append(f"  - {row}")
    out.append("")
    out.append(
        f"RESULT: {len(missing) + len(dead)} service(s) real apps use and UNIQUE does not "
        f"proxy ({len(dead)} of them declared but never installed)"
    )
    return "\n".join(out)


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("apks", nargs="+", help="APK files to survey")
    parser.add_argument(
        "--methods",
        metavar="SERVICE",
        help="list the methods real apps call on this service's manager",
    )
    args = parser.parse_args(argv)

    paths = [p for p in args.apks if p.endswith(".apk") and os.path.isfile(p)]
    if not paths:
        print("no APKs given", file=sys.stderr)
        return 2

    found = survey(paths)
    installed = installed_services()
    declared = declared_services()

    if args.methods:
        for cls, entry in sorted(found.items()):
            if MANAGER_TO_SERVICE[cls] != args.methods:
                continue
            print(f"{cls}  ({len(entry['apps'])} apps)")  # type: ignore[arg-type]
            for name in sorted(entry["methods"]):  # type: ignore[arg-type]
                print(f"  {name}")
        return 0

    print(report(found, len(paths), installed, declared, deliberate_omissions()))
    return 1 if any(MANAGER_TO_SERVICE[c] not in installed for c in found) else 0


if __name__ == "__main__":
    sys.exit(main())
