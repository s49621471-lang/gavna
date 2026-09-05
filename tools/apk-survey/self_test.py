#!/usr/bin/env python3
"""Tests for the APK survey. `tools/apk-survey/self_test.py`, no arguments.

The DEX reader is the part worth testing: everything the survey concludes rests on it
parsing a real `method_ids` table correctly, and a parser that silently returns fewer
references than it should would report a service as unused and get it left unproxied.
So the fixture is a real APK — `dist/unique-arm64-v8a.apk`, which is checked in — rather
than bytes written by this test to match this test.

Standard library only, no SDK, no device.
"""

from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)

import dexrefs  # noqa: E402
import survey  # noqa: E402

FIXTURE_APK = os.path.join(REPO_ROOT, "dist", "unique-arm64-v8a.apk")


class DexReaderTest(unittest.TestCase):
    """Against a real, checked-in APK."""

    @classmethod
    def setUpClass(cls):
        if not os.path.isfile(FIXTURE_APK):
            raise unittest.SkipTest(f"{FIXTURE_APK} is not present")
        cls.refs = dexrefs.refs_in_apk(FIXTURE_APK)

    def test_a_real_apk_yields_a_large_reference_table(self):
        # A parser that finds a handful of references has found a header and given up.
        self.assertGreater(len(self.refs), 10_000)

    def test_class_names_are_java_form_not_dex_descriptors(self):
        classes = {r.cls for r in self.refs}
        self.assertIn("android.app.Activity", classes)
        self.assertNotIn("Landroid/app/Activity;", classes)

    def test_the_engine_s_own_platform_calls_are_visible(self):
        # UNIQUE reaches ServiceManager and ActivityThread by reflection, but it also
        # calls plenty of ordinary framework API, and that must be readable.
        found = {f"{r.cls}.{r.name}" for r in self.refs}
        self.assertIn("android.content.Context.getSystemService", found)

    def test_every_reference_is_well_formed(self):
        for ref in list(self.refs)[:5000]:
            self.assertTrue(ref.cls)
            self.assertTrue(ref.name)
            self.assertNotIn("/", ref.cls)


class DescriptorTest(unittest.TestCase):
    def test_descriptors_become_class_names(self):
        self.assertEqual(
            dexrefs._descriptor_to_class("Landroid/app/LocaleManager;"),
            "android.app.LocaleManager",
        )

    def test_a_primitive_descriptor_is_left_alone(self):
        self.assertEqual(dexrefs._descriptor_to_class("I"), "I")
        self.assertEqual(dexrefs._descriptor_to_class("[I"), "I[]")

    def test_an_array_of_objects_is_fully_converted(self):
        # Half-converting this was a real bug the suite caught: the name kept its slashes
        # and the same class appeared under two spellings.
        self.assertEqual(
            dexrefs._descriptor_to_class("[Landroid/app/Activity;"),
            "android.app.Activity[]",
        )
        self.assertEqual(
            dexrefs._descriptor_to_class("[[Landroid/app/Activity;"),
            "android.app.Activity[][]",
        )


class ServiceMapTest(unittest.TestCase):
    def test_the_three_services_a_phone_found_are_mapped(self):
        # The whole reason this tool exists. If any of these lost its mapping, the survey
        # would stop reporting the exact class of bug it was built for.
        for manager, service in (
            ("android.content.RestrictionsManager", "restrictions"),
            ("android.app.LocaleManager", "locale"),
            ("android.net.ConnectivityManager", "connectivity"),
        ):
            self.assertEqual(survey.MANAGER_TO_SERVICE[manager], service)

    def test_every_mapped_manager_is_a_framework_class(self):
        for manager in survey.MANAGER_TO_SERVICE:
            self.assertTrue(manager.startswith("android."), manager)

    def test_the_installed_list_is_read_from_the_kotlin(self):
        installed = survey.installed_services()
        for service in ("activity", "package", "restrictions", "locale", "connectivity"):
            self.assertIn(service, installed)

    def test_installed_is_a_subset_of_declared_and_smaller_than_it(self):
        # The distinction the tool exists to make. TARGETS is a table of interface names;
        # a name sits there whether or not anything ever installs it, and three do not.
        installed, declared = survey.installed_services(), survey.declared_services()
        self.assertTrue(installed <= declared, sorted(installed - declared))
        self.assertTrue(declared - installed, "nothing is declared-but-dead any more")

    def test_a_service_this_tool_names_is_spelled_as_the_engine_spells_it(self):
        # A typo here reports a proxied service as unproxied for ever, which is the
        # failure mode that gets a check ignored.
        mapped = set(survey.MANAGER_TO_SERVICE.values())
        unknown = survey.declared_services() - mapped - {"activity_task"}
        self.assertEqual(
            unknown,
            set(),
            f"TARGETS names no manager maps to: {sorted(unknown)}",
        )


class ReportTest(unittest.TestCase):
    def test_an_unproxied_service_is_named_with_what_to_add(self):
        found = {
            "android.telephony.TelephonyManager": {"apps": {"a", "b"}, "methods": {"getDeviceId"}},
            "android.app.LocaleManager": {"apps": {"a"}, "methods": {"getApplicationLocales"}},
        }
        text = survey.report(found, total=2, installed={"locale"}, declared={"locale"})
        self.assertIn("phone", text)
        self.assertIn("Not in TARGETS at all", text)
        self.assertIn("TelephonyManager, 2/2 apps", text)
        self.assertIn("RESULT: 1 service(s)", text)

    def test_declared_but_never_installed_is_called_out_separately(self):
        # "window" is in TARGETS, nothing installs it, and 45 of 49 real apps call it.
        # Reporting that as proxied is worse than reporting it as missing.
        found = {"android.view.WindowManager": {"apps": {"a"}, "methods": {"addView"}}}
        text = survey.report(found, total=1, installed=set(), declared={"window"})
        self.assertIn("DECLARED ONLY", text)
        self.assertIn("read as done and are not", text)
        self.assertIn("1 of them declared but never installed", text)

    def test_a_fully_proxied_survey_reports_none(self):
        found = {"android.app.LocaleManager": {"apps": {"a"}, "methods": {"x"}}}
        text = survey.report(found, total=1, installed={"locale"}, declared={"locale"})
        self.assertIn("RESULT: 0 service(s)", text)
        self.assertNotIn("Not in TARGETS at all", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
