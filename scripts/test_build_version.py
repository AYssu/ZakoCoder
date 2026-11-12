"""Tests for timestamp display names and monotonic Android upgrade identifiers."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.build_version import EPOCH, MAX_VERSION_CODE, resolve_version


class BuildVersionTest(unittest.TestCase):
    def test_beijing_display_matches_user_format(self):
        moment = datetime.fromisoformat("2026-11-12T08:00:00+00:00")
        self.assertEqual("0.1.2111216-dev", resolve_version("0.1.2", "dev", moment)["version"])
        self.assertEqual("0.1.2111216", resolve_version("0.1.2", "release", moment)["version"])

    def test_zero_patch_keeps_canonical_numeric_version(self):
        moment = datetime.fromisoformat("2026-11-12T08:00:00+00:00")
        self.assertEqual("0.2.111216-dev", resolve_version("0.2.0", "dev", moment)["version"])

    def test_prerelease_suffix_follows_timestamp(self):
        moment = datetime.fromisoformat("2026-11-12T16:00:00+08:00")
        for suffix in ("beta.1", "rc.12"):
            self.assertEqual(f"0.1.2111216-{suffix}", resolve_version(f"0.1.2-{suffix}", "release", moment)["version"])
            self.assertEqual(f"0.1.2111216-{suffix}-dev", resolve_version(f"0.1.2-{suffix}", "dev", moment)["version"])

    def test_version_code_uses_seconds_and_grows_across_years(self):
        before = datetime.fromisoformat("2026-12-31T23:59:59+08:00")
        after = before + timedelta(seconds=1)
        first = resolve_version("0.1.2", "dev", before)
        second = resolve_version("0.1.2", "release", after)
        self.assertEqual(1, second["version_code"] - first["version_code"])
        self.assertTrue(first["version"].startswith("0.1.2123123"))
        self.assertEqual("0.1.2010100", second["version"])

    def test_same_instant_and_timezone_produce_same_identity(self):
        utc = datetime.fromisoformat("2026-11-12T20:00:00+00:00")
        local = utc.astimezone(timezone(timedelta(hours=8)))
        self.assertEqual(resolve_version("0.1.2", "dev", utc), resolve_version("0.1.2", "dev", local))
        self.assertEqual("0.1.2111304-dev", resolve_version("0.1.2", "dev", utc)["version"])

    def test_rejects_invalid_versions_channels_and_naive_times(self):
        now = datetime.now(timezone.utc)
        for version in ("v0.1.2", "0.1", "01.1.2", "0.1.2-dev", "0.1.2-rc.0", "0.1.2\n", "0.1.2;echo", "2147483648.1.2", "0.2147483648.2", "0.1.999999", "0.1." + "9" * 5000):
            with self.subTest(version=version), self.assertRaises(ValueError):
                resolve_version(version, "dev", now)
        with self.assertRaises(ValueError):
            resolve_version("0.1.2", "other", now)
        with self.assertRaises(ValueError):
            resolve_version("0.1.2", "dev", now.replace(tzinfo=None))

    def test_version_code_range(self):
        for offset in (0, -1, MAX_VERSION_CODE + 1):
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                resolve_version("0.1.2", "dev", EPOCH + timedelta(seconds=offset))
        self.assertEqual(MAX_VERSION_CODE, resolve_version("0.1.2", "release", EPOCH + timedelta(seconds=MAX_VERSION_CODE))["version_code"])

    def test_cli_writes_same_values_to_github_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory, "output")
            output.write_text("existing=value\n", encoding="utf-8")
            command = [sys.executable, "scripts/build_version.py", "--base-version", "0.1.2", "--channel", "dev", "--now", "2026-11-12T08:00:00Z", "--github-output", str(output)]
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            self.assertEqual("existing=value\n" + result.stdout, output.read_text(encoding="utf-8"))
            self.assertIn("version=0.1.2111216-dev\n", result.stdout)
            invalid = subprocess.run(command + ["--base-version", "0.1.2-dev"], capture_output=True, text=True)
            self.assertNotEqual(0, invalid.returncode)
            self.assertEqual("existing=value\n" + result.stdout, output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
