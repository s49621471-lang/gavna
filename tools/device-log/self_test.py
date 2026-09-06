#!/usr/bin/env python3
"""Tests for the device-log analyzer. `tools/device-log/self_test.py`, no arguments.

Two halves, and both are needed.

The first drives the checks against the real runs in `fixtures/` — three captures from one
Redmi on Android 15, kept together because they fail differently. The third run is the one
in which no app launched at all; the fourth is the one in which everything launched and was
wrong anyway; the fifth is the one in which Play services killed three guests that had
started perfectly. Every finding asserted here is something that actually happened to
somebody's phone, so a check that stops reporting one has regressed, whatever it does on
synthetic input.

The second drives them against a synthetic run in which nothing goes wrong. Without it
the suite proves only that the tool says FAIL, which a tool that always says FAIL would
also pass.

Standard library only, and no Android SDK, NDK, Gradle or Flutter: this has to be
runnable by whoever is holding the log.
"""

from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import analyze  # noqa: E402
import uniquelog  # noqa: E402

FIXTURE = os.path.join(HERE, "fixtures", "redmi-android15.log")
FIXTURE_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15.device.txt")
FIXTURE4 = os.path.join(HERE, "fixtures", "redmi-android15-run4.log")
FIXTURE4_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run4.device.txt")
FIXTURE5 = os.path.join(HERE, "fixtures", "redmi-android15-run5.log")
FIXTURE6 = os.path.join(HERE, "fixtures", "redmi-android15-run6.log")
FIXTURE6_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run6.device.txt")


def findings(check: analyze.Check) -> str:
    return "\n".join(f.detail for f in check.findings)


def notes(check: analyze.Check) -> str:
    return "\n".join(check.notes)


class ParserTest(unittest.TestCase):
    def test_reads_every_layout_a_log_arrives_in(self):
        text = "\n".join(
            [
                # adb logcat -v threadtime
                "09-05 20:17:45.169 17084 17084 I Unique  : "
                "2026-09-05 20:17:45.169 I PROCESS PROCESS_START process=com.unique kind=CORE",
                # a recorder app's export
                "1788614265.175 10300 17084 17084 I Unique  : "
                "2026-09-05 20:17:45.170 I NATIVE NATIVE_LOADED pageSize=4096",
                # unique.log, which has no logcat framing at all
                "2026-09-05 20:17:45.171 I PROCESS PROVIDER_ROUTER_CREATED",
            ]
        )
        events = uniquelog.events(uniquelog.parse_log(text))
        self.assertEqual(
            [e.code for e in events],
            ["PROCESS_START", "NATIVE_LOADED", "PROVIDER_ROUTER_CREATED"],
        )
        self.assertEqual(events[0]["kind"], "CORE")
        self.assertEqual(events[1]["pageSize"], "4096")

    def test_a_field_value_may_contain_spaces(self):
        # `message=` runs to the end and `gmsVersionName=` contains a space and brackets.
        # Splitting on whitespace truncates both, and the truncation is invisible.
        event = uniquelog.parse_event(
            "2026-09-05 20:21:10.722 E LAUNCH u0 com.google.android.apps.bard BOOTSTRAP_FAILED "
            "package=clear.una vuid=0 code=SLOT_ALREADY_BOUND "
            "message=Slot 0 already serves com.google.android.apps.bard (u0); refusing."
        )
        assert event is not None
        self.assertEqual(event.channel, "LAUNCH")
        self.assertEqual(event.vuid, 0)
        self.assertEqual(event.package, "com.google.android.apps.bard")
        self.assertEqual(event.code, "BOOTSTRAP_FAILED")
        self.assertEqual(event["package"], "clear.una")
        self.assertTrue(event["message"].endswith("refusing."))

    def test_an_event_without_a_vuid_or_package_still_parses(self):
        event = uniquelog.parse_event(
            "2026-09-05 20:17:51.853 W PROCESS ALARM_EXACT_UNAVAILABLE detail=no permission"
        )
        assert event is not None
        self.assertIsNone(event.vuid)
        self.assertIsNone(event.package)
        self.assertEqual(event.code, "ALARM_EXACT_UNAVAILABLE")
        self.assertEqual(event["detail"], "no permission")

    def test_an_ordinary_log_line_is_not_an_event(self):
        self.assertIsNone(uniquelog.parse_event("I ActivityThread: TrafficStats init done"))
        self.assertIsNone(uniquelog.parse_event("2026-09-05 20:17:45.169 I NOTACHANNEL CODE"))


class SourceTableTest(unittest.TestCase):
    """The tool reads the engine's tables rather than copying them; check it still can."""

    def test_reads_the_hooked_service_list(self):
        services = analyze.hooked_services()
        self.assertIn("activity", services)
        self.assertIn("package", services)

    def test_reads_the_runtime_permission_list(self):
        runtime = analyze.runtime_permissions()
        self.assertIn("android.permission.CAMERA", runtime)
        self.assertIn("android.permission.POST_NOTIFICATIONS", runtime)
        # The three that a real run showed denied are install-time, and the permissions
        # check depends on them not being in this set.
        self.assertNotIn("android.permission.INTERNET", runtime)
        self.assertNotIn("android.permission.ACCESS_NETWORK_STATE", runtime)
        self.assertNotIn("android.permission.WAKE_LOCK", runtime)


class RedmiRunTest(unittest.TestCase):
    """The Redmi Note 12, Android 15, the run in which nothing launched."""

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE, FIXTURE_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_run_is_read_at_all(self):
        self.assertGreater(len(self.parsed.events), 500)
        self.assertEqual(self.parsed.device["SDK"], "35")
        self.assertIn("com.openai.chatgpt", self.parsed.imported)
        self.assertIn("com.google.android.apps.bard", self.parsed.imported)
        self.assertTrue(self.parsed.guest_pids)

    def test_the_poisoned_slot_is_reported_against_every_app_it_took_down(self):
        text = findings(self.checks["slots"])
        for package in ("clear.una", "com.f0x1d.logfox", "bin.mt.plus"):
            self.assertIn(package, text)
        self.assertIn("com.google.android.apps.bard", text)
        self.assertIn("released without ending its process", text)

    def test_a_launch_that_never_reached_the_guest_is_a_failure(self):
        check = self.checks["launch"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("SLOT_ALREADY_BOUND", findings(check))
        self.assertIn("7/10 launches", notes(check))

    def test_both_of_the_guest_crashes_are_reported_once_each(self):
        check = self.checks["crash"]
        self.assertEqual(check.verdict, analyze.FAIL)
        # The platform and UNIQUE both record the main-thread crash; folding them is the
        # difference between two findings and four.
        self.assertEqual(len(check.findings), 2)
        self.assertIn("on main", findings(check))
        self.assertIn("nfz Dispatcher", findings(check))

    def test_each_refusal_names_the_api_and_the_service_to_hook(self):
        text = findings(self.checks["platform"])
        for entry, service in (
            ("RestrictionsManager.getApplicationRestrictions", "restrictions"),
            ("LocaleManager.getApplicationLocales", "locale"),
            ("ConnectivityManager.getNetworkCapabilities", "connectivity"),
        ):
            self.assertIn(entry, text)
            self.assertIn(f"`{service}`", text)

    def test_a_refusal_that_never_names_the_guest_is_still_found(self):
        # "getApplicationLocales: Neither user 10300 nor current process has …" contains
        # no package name at all. It is attributed by the pid it was raised in.
        self.assertIn("LocaleManager.getApplicationLocales", findings(self.checks["platform"]))

    def test_install_time_denials_fail_and_runtime_ones_do_not(self):
        check = self.checks["permissions"]
        self.assertEqual(check.verdict, analyze.FAIL)
        text = findings(check)
        for name in ("INTERNET", "ACCESS_NETWORK_STATE", "WAKE_LOCK"):
            self.assertIn(f"android.permission.{name}", text)
        # A permission another app defines is not UNIQUE's to grant, and saying so is a
        # note rather than a failure.
        self.assertIn("READ_GSERVICES", notes(check))
        self.assertNotIn("READ_GSERVICES", text)

    def test_an_intent_that_left_the_guest_is_reported_and_is_not_a_failure(self):
        # Gemini's shell activity fires an implicit ACTION_VIEW that the host's own Google
        # app answers, which is why it launched into a fresh instance and showed the real
        # account. Faithful to the app, confusing to the person, and worth naming.
        check = self.checks["isolation"]
        self.assertEqual(check.verdict, analyze.PASS)
        self.assertIn("com.google.android.apps.bard", notes(check))
        self.assertIn("android.intent.action.VIEW", notes(check))

    def test_the_flutter_crash_is_reported_once_with_its_repeat_count(self):
        check = self.checks["ui"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertEqual(len(check.findings), 1)
        self.assertIn("is not a subtype of type 'bool?'", check.findings[0].detail)
        self.assertIn("x15", check.findings[0].detail)

    def test_an_empty_native_scan_with_a_working_watch_is_not_a_failure(self):
        check = self.checks["engine"]
        self.assertIn("nothing to hook at bootstrap", notes(check))
        self.assertNotIn("not in effect", findings(check))

    def test_known_limits_are_listed_and_are_not_failures(self):
        check = self.checks["limits"]
        self.assertEqual(check.verdict, analyze.PASS)
        self.assertIn("PendingIntent", notes(check))

    def test_the_run_fails_overall(self):
        self.assertTrue(any(c.verdict == analyze.FAIL for c in self.checks.values()))


class RedmiRun4Test(unittest.TestCase):
    """The fourth run on the same phone: every launch reached the guest's Activity.

    A second fixture rather than a replacement, because the two runs fail differently and
    both are worth keeping. The third run is the one where nothing launched; this is the
    one where everything launched and was wrong anyway — rendering in software, refused a
    licence check, and handed an empty meta-data bundle. Every one of those went unnamed
    the first time this log was read, which is why the checks that name them exist.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE4, FIXTURE4_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_every_launch_reached_the_guest(self):
        check = self.checks["launch"]
        self.assertEqual(check.verdict, analyze.PASS)
        self.assertIn("10/10", notes(check))

    def test_software_rendering_is_named_once_per_cause(self):
        check = self.checks["render"]
        self.assertEqual(check.verdict, analyze.FAIL)
        # Both signals are present in this run and each is reported once: the crash that
        # names the RenderNode, and the `drawSoftware` frame that is the same fact quietly.
        self.assertIn("FLAG_HARDWARE_ACCELERATED", findings(check))
        self.assertIn("hardware acceleration was off", findings(check))
        self.assertEqual(len(check.findings), 2)

    def test_the_licence_bind_is_reported_with_its_repeat_count(self):
        check = self.checks["startup"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("CHECK_LICENSE", findings(check))
        # Folded: the licence client retries, and twenty copies of one fact bury the rest.
        self.assertEqual(len([f for f in check.findings if "CHECK_LICENSE" in f.detail]), 1)
        self.assertIn("System.exit", notes(check))

    def test_the_missing_gms_meta_data_is_found(self):
        self.assertIn("metaData", findings(self.checks["startup"]))


class RedmiRun5Test(unittest.TestCase):
    """The fifth run: everything launched, and Play services killed three of them.

    Loaded with **no device file**, deliberately. The in-app export that used to produce
    one was removed, so from this run onwards a log arrives as a bare capture from a
    recorder app and nothing else. A tool that needs the sidecar to work would be a tool
    that stopped working the day the sidecar did.

    The three crashes are one fault with three victims, and it is the fault this pass
    fixed: `GmsClient.getRemoteService` sends the *guest's* package name to
    `com.google.android.gms`, which resolves the calling uid to UNIQUE's packages and
    answers `SecurityException: Unknown calling package name`. It arrives on a `Handler`,
    so it is fatal. The fix is to hide the Google stack from a guest entirely; these
    assertions are about the analyzer naming the fault, which stays true of this log
    whatever the engine does next.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE5, None)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_run_is_read_without_a_device_file(self):
        self.assertGreater(len(self.parsed.events), 900)
        self.assertEqual(self.parsed.device, {})

    def test_every_google_crash_is_named_with_its_exception(self):
        check = self.checks["crash"]
        self.assertEqual(check.verdict, analyze.FAIL)
        detail = findings(check)
        for package in (
            "com.gordey.standarling",
            "com.a0soft.gphone.acc.free",
            "com.Chillow.CustomRise",
        ):
            self.assertIn(package, detail)
        # The reason belongs on the line, not only under --verbose: three identical
        # "crashed on main" lines hide the one fact that identifies the cause.
        self.assertEqual(detail.count("Unknown calling package name"), 3)

    def test_the_provider_that_failed_to_publish_is_named(self):
        # FileProvider's attachInfo reads external storage, which is the guest's own only
        # after the identity hooks are in. Publishing before them left the guest with no
        # provider of its own, and this is the line that says so.
        check = self.checks["providers"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("androidx.core.content.FileProvider", findings(check))

    def test_an_install_time_denial_the_user_could_not_have_caused_fails(self):
        check = self.checks["permissions"]
        self.assertEqual(check.verdict, analyze.FAIL)
        # SYSTEM_ALERT_WINDOW has no runtime dialog, so a denial is UNIQUE's own missing
        # declaration or grant — never the user's choice. App Details now offers it.
        self.assertIn("SYSTEM_ALERT_WINDOW", findings(check))

    def test_a_packer_that_never_reached_the_application_is_reported(self):
        # bin.mt.plus decrypts its own classes in a static initialiser and threw
        # UnsatisfiedLinkError before UNIQUE's graft could finish. Unexplained, and the
        # analyzer must keep saying so rather than passing it over.
        check = self.checks["launch"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("bin.mt.plus", findings(check))
        self.assertIn("NO_APPLICATION", findings(check))

    def test_the_launches_that_did_work_are_counted(self):
        # Six of eight, so the report cannot be read as "nothing launched" — which is what
        # the third run was, and the difference between the two is the whole point.
        self.assertIn("6/8", notes(self.checks["launch"]))

    def test_no_guest_was_rendering_in_software(self):
        # The fault the fourth run was full of, fixed before this one and still fixed.
        self.assertEqual(self.checks["render"].verdict, analyze.PASS)


class RedmiRun6Test(unittest.TestCase):
    """The sixth run: the games launched, and could not find their own assets.

    Seven launches, six of which reached the guest's Activity — and the report was that
    apps start and then behave as though they had been installed wrong. Every one of the
    faults below is in this log and none of them is a launch failure, which is why the
    two checks this run added exist at all: `storage` and `native` ask questions no
    earlier check asked, and the answers are what the user was actually seeing.

    The assertions are about the analyzer naming each fault. They stay true of this log
    whatever the engine does next, which is the point of a fixture.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE6, FIXTURE6_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_game_that_could_not_read_its_expansion_files_is_named(self):
        # `I Unity: No permission to read external storage. Skipping OBB loading.` — the
        # app's own line, and the strongest evidence there is that a game is running with
        # none of its assets. It is the app saying what is wrong, so it is read directly
        # rather than inferred.
        check = self.checks["storage"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("com.axlebolt.standoff2", findings(check))
        self.assertIn("READ_EXTERNAL_STORAGE", findings(check))

    def test_a_host_blocked_permission_is_not_excused_as_the_user_s_choice(self):
        # READ_EXTERNAL_STORAGE is a runtime permission, so the old classification filed
        # it under "the user may have refused it" and said nothing. `blockedByHost=true`
        # says otherwise: UNIQUE cannot hold it on any phone from Android 13 on, so no
        # dialog exists and no setting helps.
        check = self.checks["permissions"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("because UNIQUE does not hold it", findings(check))

    def test_the_native_crash_is_traced_to_the_library_UNIQUE_hooked(self):
        # SIGBUS at an unaligned address inside an anonymous page, six seconds after 22
        # GOT slots were written into a code-virtualization protector. Nothing in the
        # tombstone names UNIQUE; the pairing is what makes it findable.
        check = self.checks["native"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("com.gordey.standarling", findings(check))
        self.assertIn("libgrave.so", findings(check))

    def test_the_fatal_notification_call_is_named_with_its_service(self):
        # The hook was installed nine milliseconds too late: providers ran first, and a
        # provider's attachInfo started a thread that asked for a notification channel
        # under the guest's own name.
        check = self.checks["platform"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("NotificationManager.getNotificationChannel", findings(check))

    def test_the_packer_still_has_no_application(self):
        check = self.checks["launch"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("bin.mt.plus", findings(check))
        self.assertIn("NO_APPLICATION", findings(check))

    def test_the_launches_that_did_work_are_counted(self):
        self.assertIn("6/7", notes(self.checks["launch"]))

    def test_no_guest_was_rendering_in_software(self):
        self.assertEqual(self.checks["render"].verdict, analyze.PASS)


HEALTHY = """\
2026-01-01 10:00:00.000 I PROCESS PROCESS_START process=com.unique kind=CORE sdk=35 abi=arm64-v8a
2026-01-01 10:00:00.010 I NATIVE NATIVE_LOADED pageSize=4096
2026-01-01 10:00:00.020 I HOOK HIDDEN_API_GRANTED via=HiddenApiBypass
2026-01-01 10:00:01.000 I STORAGE PACKAGE_IMPORTED package=com.example.app versionCode=7
2026-01-01 10:00:02.000 I PROCESS PROCESS_ASSIGNED slot=:vapp0 vuid=0 process=com.example.app
2026-01-01 10:00:02.100 I LAUNCH LAUNCH_REQUESTED package=com.example.app vuid=0 \
activity=com.example.app.Main slot=:vapp0 launchMode=0
2026-01-01 10:00:02.500 I PROCESS PERMISSIONS_BOUND package=com.example.app vuid=0 declared=9 \
restored=0 runtime=2 installTime=7 selfDefined=0
2026-01-01 10:00:02.600 D PROCESS u0 com.example.app PERMISSION_CHECK \
permission=android.permission.INTERNET result=GRANTED
2026-01-01 10:00:02.700 D PROCESS u0 com.example.app PERMISSION_CHECK \
permission=android.permission.CAMERA result=DENIED
2026-01-01 10:00:02.800 I HOOK IDENTITY_HOOKS_INSTALLED package=com.example.app \
installed=alarm,clipboard,restrictions,locale,connectivity skipped=
2026-01-01 10:00:02.900 I NATIVE IO_REDIRECT_INSTALLED status=OK watch=OK rules=8 slots=12 \
scope=/data/user/0/com.unique/files/virtual/apk/com.example.app/7/lib/arm64-v8a
2026-01-01 10:00:03.000 I LAUNCH u0 com.example.app BOOTSTRAP_OK package=com.example.app vuid=0 slot=0
2026-01-01 10:00:03.010 I LAUNCH u0 com.example.app TRANSACTION_REWRITTEN package=com.example.app \
activity=com.example.app.Main vuid=0 item=LaunchActivityItem theme=1
2026-01-01 10:00:03.020 I PROCESS PROVIDERS_PUBLISHED package=com.example.app declared=1
2026-01-01 10:00:03.030 I PROCESS PROVIDER_SLOT_READY slot=0 vuid=0 pid=4242
"""


class HealthyRunTest(unittest.TestCase):
    """A run in which nothing goes wrong must pass every check."""

    @classmethod
    def setUpClass(cls):
        import tempfile

        cls.tmp = tempfile.NamedTemporaryFile("w", suffix=".log", delete=False)
        cls.tmp.write(HEALTHY)
        cls.tmp.close()
        cls.parsed = analyze.load(cls.tmp.name, None)
        cls.checks = analyze.run_checks(cls.parsed)

    @classmethod
    def tearDownClass(cls):
        os.unlink(cls.tmp.name)

    def test_every_check_passes(self):
        failed = {c.name: findings(c) for c in self.checks if c.verdict == analyze.FAIL}
        self.assertEqual(failed, {})

    def test_a_denied_runtime_permission_is_not_a_failure(self):
        check = next(c for c in self.checks if c.name == "permissions")
        self.assertIn("android.permission.CAMERA", notes(check))
        self.assertEqual(check.verdict, analyze.PASS)

    def test_the_report_says_ok_and_the_exit_status_is_zero(self):
        text = analyze.report(self.parsed, self.checks)
        self.assertIn("RESULT: OK", text)
        self.assertEqual(analyze.main([self.tmp.name]), 0)


class BrokenRunTest(unittest.TestCase):
    """Each check must react to its own failure and to nothing else."""

    def check_for(self, extra: str, name: str) -> analyze.Check:
        import tempfile

        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as f:
            f.write(HEALTHY + extra)
            path = f.name
        try:
            parsed = analyze.load(path, None)
            return next(c for c in analyze.run_checks(parsed) if c.name == name)
        finally:
            os.unlink(path)

    def test_a_hook_that_binds_to_nothing_fails(self):
        check = self.check_for(
            "2026-01-01 10:00:04.000 W HOOK HOOK_MATCHED_NOTHING service=alarm "
            "interface=android.app.IAlarmManager methods=set,cancel\n",
            "hooks",
        )
        self.assertEqual(check.verdict, analyze.FAIL)

    def test_a_denied_install_time_permission_fails(self):
        check = self.check_for(
            "2026-01-01 10:00:04.000 D PROCESS u0 com.example.app PERMISSION_CHECK "
            "permission=android.permission.WAKE_LOCK result=DENIED\n",
            "permissions",
        )
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("install-time", findings(check))

    def test_an_exhausted_pool_fails(self):
        check = self.check_for(
            "2026-01-01 10:00:04.000 W PROCESS PROCESS_POOL_EXHAUSTED capacity=16 "
            "requested=com.example.other\n",
            "slots",
        )
        self.assertEqual(check.verdict, analyze.FAIL)

    def test_a_stale_slot_that_was_cleaned_up_is_a_note_not_a_failure(self):
        # The engine now ends the process and says so. That is the fix working, not a
        # failure, and reporting it as one would train people to ignore the check.
        check = self.check_for(
            "2026-01-01 10:00:04.000 W PROCESS SLOT_PROCESS_STALE slot=:vapp0 "
            "requested=com.example.other detail=a process was still running\n",
            "slots",
        )
        self.assertEqual(check.verdict, analyze.PASS)
        self.assertIn("it was ended first", notes(check))

    def test_a_render_node_crash_on_the_software_rasteriser_fails(self):
        check = self.check_for(
            "2026-01-01 10:00:05.000 E AndroidRuntime java.lang.IllegalArgumentException: "
            "Software rendering doesn't support drawRenderNode\n",
            "render",
        )
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("FLAG_HARDWARE_ACCELERATED", findings(check))

    def test_a_healthy_run_is_not_reported_as_rendering_in_software(self):
        check = self.check_for("", "render")
        self.assertEqual(check.verdict, analyze.PASS)

    def test_the_missing_gms_meta_data_is_a_startup_failure(self):
        check = self.check_for(
            "2026-01-01 10:00:05.000 E FA Task exception on worker thread: "
            "java.lang.IllegalStateException: A required meta-data tag in your app's "
            "AndroidManifest.xml does not exist. You must have the following declaration "
            "within the <application> element: <meta-data "
            'android:name="com.google.android.gms.version" />\n',
            "startup",
        )
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("metaData", findings(check))

    def test_an_exit_on_its_own_is_a_note_not_a_failure(self):
        # An app may legitimately call System.exit. It is worth seeing beside a refusal
        # and is not evidence of one on its own.
        check = self.check_for(
            "2026-01-01 10:00:05.000 I om.unique:vapp0 System.exit called, status: 0\n",
            "startup",
        )
        self.assertEqual(check.verdict, analyze.PASS)
        self.assertIn("System.exit", notes(check))

    def test_a_refused_orientation_fails_and_an_applied_one_does_not(self):
        refused = self.check_for(
            "2026-01-01 10:00:05.000 W LAUNCH u0 com.example.app ACTIVITY_ORIENTATION_APPLIED "
            "activity=com.example.app.Game orientation=6 applied=false\n",
            "orientation",
        )
        self.assertEqual(refused.verdict, analyze.FAIL)
        applied = self.check_for(
            "2026-01-01 10:00:05.000 I LAUNCH u0 com.example.app ACTIVITY_ORIENTATION_APPLIED "
            "activity=com.example.app.Game orientation=6 applied=true\n",
            "orientation",
        )
        self.assertEqual(applied.verdict, analyze.PASS)
        self.assertIn("declared orientation", notes(applied))

    def test_a_native_scan_with_a_broken_watch_fails(self):
        check = self.check_for(
            "2026-01-01 10:00:04.000 I NATIVE IO_REDIRECT_INSTALLED status=NOTHING_TO_HOOK "
            "watch=FAILED rules=8 slots=0 "
            "scope=/data/user/0/com.unique/files/virtual/apk/com.example.app/7/lib/arm64-v8a\n",
            "engine",
        )
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("com.example.app", findings(check))


if __name__ == "__main__":
    unittest.main(verbosity=2)
