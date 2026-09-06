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
FIXTURE7 = os.path.join(HERE, "fixtures", "redmi-android15-run7.log")
FIXTURE7_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run7.device.txt")
FIXTURE8 = os.path.join(HERE, "fixtures", "redmi-android15-run8.log")
FIXTURE8_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run8.device.txt")
FIXTURE9 = os.path.join(HERE, "fixtures", "redmi-android15-run9.log")
FIXTURE9_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run9.device.txt")
FIXTURE10 = os.path.join(HERE, "fixtures", "redmi-android15-run10.log")
FIXTURE10_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run10.device.txt")
FIXTURE11 = os.path.join(HERE, "fixtures", "redmi-android15-run11.log")
FIXTURE11_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run11.device.txt")
FIXTURE12 = os.path.join(HERE, "fixtures", "redmi-android15-run12.log")
FIXTURE12_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run12.device.txt")
FIXTURE13 = os.path.join(HERE, "fixtures", "redmi-android15-run13.log")
FIXTURE13_DEVICE = os.path.join(HERE, "fixtures", "redmi-android15-run13.device.txt")


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

    def test_the_native_crash_is_reported_without_blaming_the_hook(self):
        # This assertion used to read "traced to the library UNIQUE hooked", because 22
        # GOT slots had been written into `libgrave.so` six seconds earlier and the
        # pairing looked conclusive. The next run disproved it: the same game, the same
        # crash, at the same offset into the page, with `libgrave.so` excluded. The
        # pairing was a coincidence of ordering, and what the log actually says is
        # narrower — a jump to an address that is not instruction-aligned, into a page
        # that is not a library.
        check = self.checks["native"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("com.gordey.standarling", findings(check))
        self.assertIn("unaligned address", findings(check))

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


class RedmiRun7Test(unittest.TestCase):
    """The seventh run: three apps launched, and Google was the whole story.

    This is the first log in which nothing UNIQUE does breaks a launch — 3 of 3 reach the
    guest's Activity, no `platform` refusal, no poisoned slot. What it says instead is
    what the *previous* pass's fixes did and did not reach, and the assertions are about
    the analyzer being able to tell those two apart.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE7, FIXTURE7_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_every_launch_reached_the_guest(self):
        self.assertEqual(self.checks["launch"].verdict, analyze.PASS)
        self.assertIn("3/3", notes(self.checks["launch"]))

    def test_nothing_went_out_under_the_guest_s_name_and_was_refused(self):
        # The notification call that killed an app one run earlier. The hooks now precede
        # the providers, and this is the check that says so.
        self.assertEqual(self.checks["platform"].verdict, analyze.PASS)

    def test_the_expansion_files_still_could_not_be_read(self):
        # The import ran and said exactly why, for all three apps: Android/obb is closed
        # to UNIQUE until the user grants all-files access. Naming the directory is the
        # point — "the game has no assets" points nowhere.
        check = self.checks["storage"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("Android/obb/com.axlebolt.standoff2", findings(check))
        self.assertIn("all-files access", findings(check))

    def test_the_excluded_protector_is_reported_as_left_alone(self):
        # libgrave.so was hooked in run 6 and excluded here, and the exclusion has to be
        # visible: "not hooked on purpose" and "the scan missed it" are the same zero.
        self.assertIn("libgrave.so", notes(self.checks["native"]))

    def test_the_wild_jump_is_not_blamed_on_a_hooked_library(self):
        # The same game still died, with libgrave excluded — so the crash was never the
        # hook. An unaligned PC in a gralloc buffer is a pointer that was already wrong,
        # and saying "after UNIQUE hooked libgrave.so" sent one investigation the wrong
        # way already.
        check = self.checks["native"]
        self.assertEqual(check.verdict, analyze.FAIL)
        detail = findings(check)
        self.assertIn("unaligned address", detail)
        self.assertIn("memfd:gralloc_shared_memory", detail)
        self.assertNotIn("after UNIQUE hooked", detail)


class RedmiRun8Test(unittest.TestCase):
    """The eighth run: the run that disproved a rule this repository had just shipped.

    The pass before it decided Google Play services visibility per app, from the
    `com.google.android.gms.version` meta-data. This log killed that idea in one line:

        GOOGLE_STACK_HIDDEN hidden=true reason=SDK_TOO_OLD gmsVersion=12451000   (x3)

    — the same number for 1Tap Cleaner, ChatGPT and a Unity game, on a phone carrying
    GmsCore 26.32.34. It is the *minimum* version a client accepts, not the client's own,
    and Google froze it in 2018. So every guest was told a fully Googled phone has no
    Play services, and the user was shown "Установите сервисы Google Play" by an app
    running on a device that has it.

    These assertions are the reason a rule of that shape cannot come back quietly: the
    `google` check pairs "the phone has it" with "a guest was told it does not", and this
    fixture is a real log in which that pairing is true.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE8, FIXTURE8_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_phone_had_play_services_and_the_guests_were_told_otherwise(self):
        check = self.checks["google"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("26.32.34", notes(check))
        self.assertIn("SDK_TOO_OLD", findings(check))

    def test_the_app_s_own_complaint_is_read_not_just_unique_s_event(self):
        # `GooglePlayServicesUtil: … requires Google Play services, but they are missing`
        # is the app speaking. A build could stop logging GOOGLE_STACK_HIDDEN and this
        # would still catch it, which is the point of reading both.
        self.assertIn("com.openai.chatgpt", findings(self.checks["google"]))

    def test_a_launch_still_reached_every_guest(self):
        # Worth stating: the Google fault is not a launch fault. All three apps started;
        # what was broken was what they were told once they were running.
        self.assertEqual(self.checks["launch"].verdict, analyze.PASS)
        self.assertIn("3/3", notes(self.checks["launch"]))

    def test_the_expansion_files_were_still_unreadable(self):
        # Unchanged from run 7, and it is the user's grant to give: `Android/obb` stays
        # closed until all-files access is allowed. The import now runs at every launch,
        # so the finding appears once per launch rather than once per import.
        check = self.checks["storage"]
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("all-files access", findings(check))

    def test_the_protector_is_still_left_alone_on_purpose(self):
        self.assertIn("libgrave.so", notes(self.checks["native"]))


class RedmiRun9Test(unittest.TestCase):
    """The ninth run: what making Play services visible actually cost.

    The previous build stopped hiding Google from guests, and this log is the bill. Three
    apps died of the refusal the hiding was protecting them from — on the main looper,
    where nothing can catch it — while a fourth hit the same refusal ten times and
    survived it, because its client library is newer:

        FATAL  SecurityException: Unknown calling package name 'com.gordey.standarling'
               at …c.getRemoteService (play-services-basement@@17.4.0:25)
               at …c$g.handleMessage · Looper.loop

        E GoogleApiManager: Failed to get service from broker.
          SecurityException: Unknown calling package name 'com.openai.chatgpt'

    Same refusal, same run, opposite outcomes, and the difference is Google's code and not
    UNIQUE's. That is what `GuestLooperGuard` is for.

    The other thing this log settles is the expansion-file message. It appears here for
    `com.openai.chatgpt`, `bin.mt.plus` and `com.a0soft.gphone.acc.free` — a chat app, a
    file manager and a cleaner, none of which has ever had an OBB file — because the
    engine inferred "blocked" from "cannot see it", and on Android 11 and later nothing
    can see it. The user granted all-files access, which does not cover `Android/obb`, and
    nothing changed. It could not have.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE9, FIXTURE9_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_refusal_killed_the_apps_with_the_old_client(self):
        check = self.checks["crash"]
        self.assertEqual(check.verdict, analyze.FAIL)
        detail = findings(check)
        self.assertIn("Unknown calling package name", detail)
        # Three, not one: this is a property of the client library each app ships, so it
        # hits several unrelated apps at once and none of them is at fault.
        self.assertGreaterEqual(detail.count("Unknown calling package name"), 3)

    def test_the_same_refusal_was_survived_by_a_newer_client_in_the_same_run(self):
        # The evidence that the crash is not inherent. If this stops being true the
        # looper guard is solving a problem that does not exist in this shape.
        survived = [
            line for line in self.parsed.lines
            if "Failed to get service from broker" in line.message
        ]
        self.assertTrue(survived)

    def test_the_expansion_message_reached_apps_that_have_no_expansion_files(self):
        # The false positive, stated as itself. `bin.mt.plus` is a file manager.
        findings_text = findings(self.checks["storage"])
        self.assertIn("bin.mt.plus", findings_text)
        self.assertIn("com.openai.chatgpt", findings_text)

    def test_the_advice_no_longer_prescribes_a_grant_that_cannot_help(self):
        # The message the user acted on and got nothing for. `MANAGE_EXTERNAL_STORAGE`
        # does not cover `Android/data` or `Android/obb`, so telling anyone to grant it
        # for expansion files is telling them to do something that has no effect.
        text = findings(self.checks["storage"])
        self.assertNotIn("grant UNIQUE all-files access", text)
        self.assertIn("file browser", text)

    def test_the_broker_was_never_wrapped_in_this_run(self):
        # The build that produced this log had no calling-package rewrite: 29 refusals
        # and no `GMS_BROKER_WRAPPED`. That pairing is the assertion, because the same
        # check must fail differently once the wrap exists — refusals *with* a wrap mean
        # a route the rewrite does not cover, which is a different bug.
        text = findings(self.checks["google"])
        self.assertIn("never wrapped", text)

    def test_the_sign_in_limit_is_named_from_this_phone_rather_than_reasoned_about(self):
        # `ConnectionResult{statusCode=DEVELOPER_ERROR}`, from the user's own device.
        # Google identifies an app by package and signing certificate; inside UNIQUE the
        # call arrives as UNIQUE, so this is the answer and no rewriting changes it.
        self.assertIn("DEVELOPER_ERROR", notes(self.checks["google"]))

    def test_the_packer_still_refuses_to_start(self):
        # Unchanged and still unsolved: `bin.mt.plus` unpacks its real application at
        # runtime and UNIQUE never finds one. Asserted so it stays visible rather than
        # being quietly forgotten between runs.
        self.assertIn("NO_APPLICATION", findings(self.checks["launch"]))


class RedmiRun10Test(unittest.TestCase):
    """The tenth run: the rewrite works, and it exposed three things behind it.

    The calling-package rewrite went in and did its job — seventeen binds wrapped,
    seventeen requests sent under UNIQUE's own name, and the expansion-file check green
    for the first time. What the log then showed is what that had been hiding:

    1. **Firebase Analytics was still refused.** It never uses the service broker; it
       binds `AppMeasurementService` directly, so an allowlist of interfaces missed it:

           GMS_BROKER_WRAPPED descriptor=…IGmsServiceBroker package=com.gordey.standarling
           E FA: Task exception while flushing queue:
               SecurityException: Unknown calling package name 'com.gordey.standarling'.

    2. **Google Sign-In got further than anyone had seen and died in UNIQUE's own
       plumbing.** `SignInHubActivity` launched *inside the guest* and crashed reading the
       configuration it had written one step earlier, because the `Intent`'s extras had no
       class loader that could see the guest's APK. That is not a Google bug and not a
       Google fix: any app passing its own `Parcelable` through an `Intent` hits it.

    3. **Standoff 2 asked for Play services to be installed** — from a build in which
       Google worked. Its instance still carried the automatic-hide mark written when it
       crashed under the *previous* build, and a mark is a statement about a mechanism
       that no longer exists.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE10, FIXTURE10_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_rewrite_reached_play_services(self):
        notes_text = notes(self.checks["google"])
        self.assertIn("under UNIQUE's own name", notes_text)
        self.assertIn("broker was wrapped", notes_text)

    def test_a_refusal_that_got_past_a_wrapped_broker_is_reported_as_its_own_thing(self):
        # "Refused and never wrapped" and "refused with a wrap in place" are different
        # bugs — the first is plumbing, the second is a route the rewrite does not cover
        # — and telling them apart is what sent this fix at the measurement service
        # rather than back at the connection code.
        findings_text = findings(self.checks["google"])
        self.assertIn("with the broker wrapped", findings_text)
        self.assertNotIn("never wrapped", findings_text)

    def test_the_expansion_file_message_is_gone(self):
        # The previous run reported it for every app including ones with no expansion
        # files. Nothing is inferred now, and this is the log that says so.
        self.assertEqual(self.checks["storage"].verdict, analyze.PASS)

    def test_sign_in_died_reading_the_guest_s_own_class(self):
        # The crash that says the flow was working: `SignInHubActivity` is the guest's
        # own activity, running, and the class it could not find is in the guest's APK.
        detail = findings(self.checks["crash"])
        self.assertIn("ClassNotFoundException", detail)

    def test_a_stale_hide_is_visible_as_the_reason_an_app_saw_no_google(self):
        # Standoff 2. The mark was earned under a build with no answer for the refusal
        # and outlived it, which is why marks carry a generation now.
        self.assertIn("AUTO_HIDDEN_AFTER_CRASH", notes(self.checks["google"]))
        self.assertIn("com.axlebolt.standoff2", findings(self.checks["google"]))


class RedmiRun11Test(unittest.TestCase):
    """The eleventh run: a game reached its own login screen, and a slot was stolen.

    The first log in which Standoff 2 runs. Unity comes up on the hardware renderer, the
    expansion file loads out of the instance's own storage, FMOD, Firebase and AppMetrica
    all initialise, and the game draws its login screen — everything up to the point where
    the user taps *Sign in with Google*, which is where the tenth run's class-loader fault
    was already fixed and is not in this build.

    What is new here, and worse, is underneath. `bin.mt.plus` failed to construct its
    `Application` — a packed app whose protector loaded and then declined to register its
    natives — and the engine recorded nothing about the process, because what it recorded
    was the *result* of a graft and there was none. Sixteen seconds later a job for
    Standoff 2 fired into that same process:

        BOOTSTRAP_FAILED  package=bin.mt.plus code=NO_APPLICATION
        CREATE_SERVICE_UNMAPPED stub=com.unique.stub.JobStub_p0
        PROCESS_RENAMED   argv0=com.axlebolt.standoff2
        PROFILE_REBIND_IGNORED current=028dca45-… requested=6c904a63-…

    That last line is a game running under a file manager's `ANDROID_ID`. Separate
    identity per instance is the one promise this engine makes, and no check in this tool
    would have said a word about it.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE11, FIXTURE11_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_a_stolen_slot_is_reported_as_the_identity_fault_it_is(self):
        detail = findings(self.checks["slots"])
        self.assertIn("running under the first one's identity", detail)

    def test_the_packed_app_is_still_reported_as_unlaunchable(self):
        # bin.mt.plus is the app that produced the stolen slot by failing first. It is
        # not claimed to work, and the log has to keep saying so.
        self.assertIn("NO_APPLICATION", findings(self.checks["launch"]))

    def test_the_game_launched(self):
        # Two launches of com.axlebolt.standoff2 reached its own Activity. The check
        # counts them, and this is the run that first had any to count.
        self.assertIn("reached the guest's Activity", notes(self.checks["launch"]))

    def test_an_install_time_permission_no_dialog_can_grant_is_named(self):
        # ACCESS_ADSERVICES_ATTRIBUTION, denied twice. There is no screen anywhere on the
        # device that could have granted it: UNIQUE has to declare it or nothing can.
        self.assertIn("ACCESS_ADSERVICES_ATTRIBUTION", findings(self.checks["permissions"]))

    def test_this_build_published_no_proc_view_and_the_check_says_so(self):
        # The run predates the view, so the check must be silent about it rather than
        # inventing a pass. A build that publishes one and leaks is a different report.
        self.assertEqual(self.checks["detection"].verdict, analyze.PASS)
        self.assertIn("does not publish a /proc view", notes(self.checks["detection"]))


class RedmiRun12Test(unittest.TestCase):
    """The twelfth run: the first build with a `/proc` view, and it caught its own gap.

    The point of making the engine check its own work was that a table with a wrong prefix
    in it and no table at all produce the same "installed" line. This is the log where
    that paid:

    ```
    PROC_VIEW_INSTALLED package=com.axlebolt.standoff2 rules=6 named=15 leaked=2
        first=/data/data/com.unique/files/virtual/apk/com.axlebolt.standoff2/203908
    ```

    Thirteen of fifteen mappings renamed and two left naming UNIQUE — which for this
    purpose is the same as none, because a check only has to find one. `/data/data/<pkg>`
    is a symlink to `/data/user/0/<pkg>` and the view was built from only the second
    spelling. The line names the directory the rule is missing for, which is the whole
    diagnosis.

    It also shows the class-loader fix from the run before doing nothing at all: Google
    Sign-In crashes identically, because `setExtrasClassLoader` after the first read of an
    extra changes nothing, and UNIQUE always reads first.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE12, FIXTURE12_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_view_reports_its_own_leak_and_names_the_missing_rule(self):
        detail = findings(self.checks["detection"])
        self.assertIn("still name UNIQUE", detail)
        self.assertIn("/data/data/com.unique/files/virtual/apk", detail)

    def test_the_engine_is_no_longer_the_story_for_slots_or_platform_calls(self):
        # The eleventh run's stolen slot and its refused platform calls are both gone.
        for name in ("slots", "platform", "storage", "native", "hooks", "isolation"):
            self.assertEqual(self.checks[name].verdict, analyze.PASS, name)

    def test_sign_in_still_dies_on_the_guest_s_own_class(self):
        # The fix that shipped one run earlier could not have worked, and this is the log
        # that says so rather than a re-reading of the code that says it should have.
        self.assertIn("ClassNotFoundException", findings(self.checks["crash"]))

    def test_a_relaunch_into_a_live_process_is_not_a_failed_launch(self):
        # A launch into a process already grafted for that instance does not graft again,
        # so it has a rewritten transaction and no BOOTSTRAP_OK. Counting that as a
        # failure marked one of this run's two Standoff 2 launches broken while the game
        # was plainly running.
        self.assertNotIn("no BOOTSTRAP_OK", findings(self.checks["launch"]))


class RedmiRun13Test(unittest.TestCase):
    """The thirteenth run: everything closes except Google, and Google is named exactly.

    The build under this log carries the `/proc` view with the alias the twelfth run's
    self-check found missing, and the forwarding class loader that replaced the
    `setExtrasClassLoader` fix the twelfth run disproved. Both show:

    ```
    PROC_VIEW_INSTALLED package=com.axlebolt.standoff2 rules=16 named=16 leaked=0
    ```

    Sixteen mappings named UNIQUE and none of them readable through the view, against
    `named=15 leaked=2` one run earlier. The game launches, runs, and does not crash on
    its own class any more.

    What is left is one route, and the value of this fixture is that the route is a
    number rather than a suspicion:

    ```
    GMS_PACKAGE_NOT_REWRITTEN descriptor=android.os.IMessenger code=1
        package=com.axlebolt.standoff2 bareAt=144 size=308
    ```

    Firebase Analytics sends the guest's package as a bare string inside a Bundle in an
    `IMessenger` transaction — not as a SafeParcel field, which is the only shape the
    rewrite can edit without recomputing an enclosing length. Six refusals follow, from
    `FA`, with the broker wrapped for fifteen binds and sixteen requests already
    corrected. "The rewrite did not fire" and "the rewrite fired and one route is not
    covered" are different bugs and this log distinguishes them.
    """

    @classmethod
    def setUpClass(cls):
        cls.parsed = analyze.load(FIXTURE13, FIXTURE13_DEVICE)
        cls.checks = {c.name: c for c in analyze.run_checks(cls.parsed)}

    def test_the_proc_view_leaks_nothing(self):
        # The twelfth run's own self-check reported two mappings still naming UNIQUE.
        # This is the assertion that the alias closed them, made against the engine's
        # measurement rather than against the rule table it was built from.
        check = self.checks["detection"]
        self.assertEqual(check.verdict, analyze.PASS)
        self.assertIn("none readable through the view", notes(check))

    def test_the_guest_no_longer_dies_on_its_own_class(self):
        # The twelfth run crashed in `ClassNotFoundException` on every sign-in attempt.
        self.assertEqual(self.checks["crash"].verdict, analyze.PASS)
        self.assertNotIn("ClassNotFoundException", findings(self.checks["crash"]))

    def test_the_last_google_route_is_named_with_its_offset(self):
        # A byte offset and a transaction code, so the next pass at this has somewhere to
        # start that is not a re-reading of the same code.
        detail = findings(self.checks["google"])
        self.assertIn("android.os.IMessenger transaction 1", detail)
        self.assertIn("bare string at byte 144 of 308", detail)

    def test_the_refusals_are_reported_as_a_route_and_not_as_a_missing_wrap(self):
        # The broker *was* wrapped here, so the finding must not repeat run 10's
        # diagnosis. Reporting "never wrapped" against a log with fifteen wraps in it
        # would send the next fix to the wrong layer.
        detail = findings(self.checks["google"])
        self.assertIn("with the broker wrapped", detail)
        self.assertNotIn("never wrapped", detail)

    def test_unique_s_own_forecast_is_not_reported_as_google_s_answer(self):
        # Play services never answered `DEVELOPER_ERROR` in this run. The only line
        # containing the word is UNIQUE's `GOOGLE_ROUTE` explanation of what would happen
        # if a guest signed in — which the check used to match and attribute to Google.
        text = notes(self.checks["google"])
        self.assertIn("never answered DEVELOPER_ERROR", text)
        self.assertNotIn("Google answered DEVELOPER_ERROR", text)

    def test_google_is_the_only_failing_check(self):
        failing = sorted(
            name for name, check in self.checks.items()
            if check.verdict == analyze.FAIL
        )
        self.assertEqual(failing, ["google"])


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
2026-01-01 10:00:02.850 I NATIVE IO_REDIRECT_ARMED package=com.example.app rules=13 \
procView=6 watch=OK
2026-01-01 10:00:02.950 I NATIVE PROC_VIEW_INSTALLED package=com.example.app rules=6 \
named=14 leaked=0 first=-
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

    def test_hiding_play_services_from_a_guest_on_a_googled_phone_fails(self):
        # The synthetic half of the run-8 finding. Without this, the check could pass by
        # never firing, and the fixture alone would not say which half of the pairing it
        # needs.
        check = self.check_for(
            "2026-01-01 10:00:04.000 I LAUNCH u0 com.example.app GOOGLE_ENVIRONMENT "
            "gmsPresent=true gmsEnabled=true gmsVersionCode=263234035 gmsVersionName=26.32.34\n"
            "2026-01-01 10:00:04.100 I LAUNCH u0 com.example.app GOOGLE_STACK_HIDDEN "
            "hidden=true reason=SDK_TOO_OLD gmsVersion=12451000\n",
            "google",
        )
        self.assertEqual(check.verdict, analyze.FAIL)

    def test_hiding_it_from_a_guest_that_died_of_the_refusal_is_not_a_failure(self):
        # The one legitimate hide. An instance that crashed on "Unknown calling package
        # name" is better off being told there is no Play services, and a check that
        # failed on that would push the engine back to lying to everyone.
        check = self.check_for(
            "2026-01-01 10:00:04.000 I LAUNCH u0 com.example.app GOOGLE_ENVIRONMENT "
            "gmsPresent=true gmsEnabled=true gmsVersionCode=263234035 gmsVersionName=26.32.34\n"
            "2026-01-01 10:00:04.100 I LAUNCH u0 com.example.app GOOGLE_STACK_HIDDEN "
            "hidden=true reason=AUTO_HIDDEN_AFTER_CRASH\n",
            "google",
        )
        self.assertEqual(check.verdict, analyze.PASS)

    def test_a_phone_without_play_services_is_not_unique_s_fault(self):
        # Same complaint from the app, opposite cause. Reporting it as a failure would
        # send someone looking for a bug in the engine on a de-Googled phone.
        check = self.check_for(
            "2026-01-01 10:00:04.000 I LAUNCH u0 com.example.app GOOGLE_ENVIRONMENT "
            "gmsPresent=false gmsEnabled=false\n",
            "google",
        )
        self.assertEqual(check.verdict, analyze.PASS)

    def test_a_view_that_leaves_unique_readable_fails(self):
        # The check that matters most for the applications this engine exists to run, and
        # the one most able to pass by doing nothing: a table with the wrong prefix in it
        # and no table at all produce the same "installed" line and opposite behaviour.
        # So the engine checks its own work and reports the count, and this is the shape
        # of the report when the work is wrong.
        check = self.check_for(
            "2026-01-01 10:00:04.000 W NATIVE PROC_VIEW_INSTALLED package=com.example.app "
            "rules=6 named=14 leaked=3 first=/data/user/0/com.unique/files/virtual/runtime\n",
            "detection",
        )
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("/data/user/0/com.unique/files/virtual/runtime", findings(check))

    def test_a_guest_grafted_with_no_view_at_all_fails(self):
        check = self.check_for(
            "2026-01-01 10:00:04.000 I NATIVE IO_REDIRECT_ARMED package=com.other.app "
            "rules=13 procView=0 watch=OK\n",
            "detection",
        )
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("no /proc view", findings(check))

    def test_a_second_graft_in_one_process_fails(self):
        # The eleventh run's worst fault, synthetically. The fixture has it too; this is
        # here so the check cannot start passing by never firing if that fixture goes.
        check = self.check_for(
            "2026-01-01 10:00:04.000 W LAUNCH PROFILE_REBIND_IGNORED "
            "current=028dca45-b67b-4d99-b849-a72edb754f1f "
            "requested=6c904a63-1c8a-4c6c-9d6e-02a8a7249395\n",
            "slots",
        )
        self.assertEqual(check.verdict, analyze.FAIL)
        self.assertIn("under the first one's identity", findings(check))

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
