#!/usr/bin/env python3
"""Offline regression tests for the actual Android installer shell fragments.

Only fixed base/cache paths are redirected into TemporaryDirectory. Fragment
boundaries deliberately fail closed if the installer's structure changes; update
these boundaries when moving fetch(), rather than copying its implementation.
This checks retry/ownership side effects, not SDK downloads or ARM64 execution.
"""

import fcntl
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


INSTALLER = Path(__file__).resolve().parents[1] / "app/src/main/assets/android-build/install.sh"


class AndroidInstallerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="zakocoder-installer-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.base = self.root / "managed"
        self.cache = self.root / "downloads"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "curl-calls"
        self.payload = self.root / "payload"
        self.payload.write_bytes(b"verified offline archive\n")
        self.digest = hashlib.sha256(self.payload.read_bytes()).hexdigest()
        self.env = {
            **os.environ,
            "PATH": str(self.bin) + os.pathsep + os.defpath,
            "MOCK_PAYLOAD": str(self.payload),
            "MOCK_CURL_CALLS": str(self.calls),
        }
        self.executable(self.bin / "uname", "#!/bin/sh\nprintf 'aarch64\\n'\n")
        self.executable(self.bin / "curl", """#!/usr/bin/python3
import os
from pathlib import Path
import sys
args = sys.argv[1:]
with open(os.environ['MOCK_CURL_CALLS'], 'a') as log:
    log.write(repr(args) + '\\n')
Path(args[args.index('-o') + 1]).write_bytes(Path(os.environ['MOCK_PAYLOAD']).read_bytes())
""")
        source = INSTALLER.read_text(encoding="utf-8")
        # These assertions prevent a changed delimiter from running the rest of
        # the network installer or leaving fixed host filesystem paths active.
        self.assertEqual(1, source.count("base=/opt/zakocoder\n"))
        self.assertEqual(1, source.count("  cache=/var/cache/zakocoder-android\n"))
        source = source.replace("base=/opt/zakocoder\n", f"base={shlex.quote(str(self.base))}\n")
        source = source.replace("  cache=/var/cache/zakocoder-android\n", f"  cache={shlex.quote(str(self.cache))}\n")
        self.assertEqual(1, source.count("\nfetch() {\n"))
        self.assertEqual(1, source.count("\nfetch https://dl.google.com/"))
        self.prefix, remainder = source.split("\nfetch() {\n", 1)
        body, _ = remainder.split("\nfetch https://dl.google.com/", 1)
        self.fetch = "fetch() {\n" + body + "\n"
        self.assertTrue(self.fetch.rstrip().endswith("}"))
        self.assertNotIn("/opt/zakocoder", self.prefix + self.fetch)
        self.assertNotIn("/var/cache/zakocoder-android", self.prefix + self.fetch)

    @staticmethod
    def executable(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        path.chmod(0o700)

    def shell(self, script, expected=0):
        result = subprocess.run(
            ["/bin/bash", "-c", script], env=self.env, cwd=self.root,
            text=True, capture_output=True, timeout=10,
        )
        if expected == 0:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def run_fetch(self, digest=None, expected=0):
        return self.shell("set -eu\n" + self.fetch +
                          "fetch https://offline.invalid/archive " +
                          shlex.quote(digest or self.digest) + " " +
                          shlex.quote(str(self.root / "result.zip")), expected)

    def managed_install(self, valid=True, version="sdk36-gradle8.13-v1"):
        target = self.base / "android"
        target.mkdir(parents=True)
        (target / ".ready").write_text(version + "\n")
        if valid:
            for relative in ("sdk/platforms/android-36/android.jar", "sdk/build-tools/36.0.0/lib/apksigner.jar"):
                file = target / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b"placeholder")
            for relative in ("sdk/build-tools/36.0.0/aapt2", "gradle-8.13/bin/gradle"):
                self.executable(target / relative, "#!/bin/sh\nexit 0\n")
        return target

    def test_corrupt_cache_is_downloaded_again_and_verified(self):
        self.cache.mkdir()
        (self.cache / self.digest).write_bytes(b"corrupt")
        self.run_fetch()
        self.assertEqual(self.payload.read_bytes(), (self.cache / self.digest).read_bytes())
        self.assertEqual(self.payload.read_bytes(), (self.root / "result.zip").read_bytes())
        self.assertEqual(1, len(self.calls.read_text().splitlines()))
        self.run_fetch()
        self.assertEqual(1, len(self.calls.read_text().splitlines()), "valid cache must avoid downloading")

    def test_authenticated_socks_rejected_before_install_without_exposing_credentials(self):
        self.env["ALL_PROXY"] = "socks5://private-user:private-password@127.0.0.1:1080"
        result = self.shell(self.prefix, expected=1)
        self.assertIn("without authentication", result.stderr)
        self.assertNotIn("private-user", result.stdout + result.stderr)
        self.assertNotIn("private-password", result.stdout + result.stderr)
        self.assertFalse(self.base.exists())
        self.assertFalse(self.calls.exists())

    def test_digest_mismatch_rejects_download_and_removes_partial(self):
        wrong = "0" * 64
        self.run_fetch(wrong, expected=1)
        self.assertFalse((self.cache / wrong).exists())
        self.assertFalse((self.cache / (wrong + ".partial")).exists())
        self.assertFalse((self.root / "result.zip").exists())

    def test_fetch_rejects_symlink_cache_and_partial(self):
        self.cache.mkdir()
        protected = self.root / "protected"
        protected.write_bytes(b"keep")
        for suffix in ("", ".partial"):
            with self.subTest(suffix=suffix):
                link = self.cache / (self.digest + suffix)
                link.symlink_to(protected)
                try:
                    self.run_fetch(expected=1)
                    self.assertEqual(b"keep", protected.read_bytes())
                    self.assertFalse(self.calls.exists())
                finally:
                    link.unlink()

    def test_valid_install_short_circuits_and_cleans_only_orphan_directories(self):
        target = self.managed_install()
        orphan = self.base / ".android-install.abandoned"
        orphan.mkdir()
        (orphan / "large-download").write_bytes(b"old")
        outside = self.root / "outside"
        outside.mkdir()
        protected = outside / "keep"
        protected.write_bytes(b"keep")
        link = self.base / ".android-install.link"
        link.symlink_to(outside, target_is_directory=True)
        self.shell(self.prefix + "\nprintf unexpected >&2\nexit 99\n")
        self.assertFalse(orphan.exists())
        self.assertTrue(link.is_symlink())
        self.assertEqual(b"keep", protected.read_bytes())
        self.assertTrue((target / ".ready").is_file())
        self.assertFalse(self.calls.exists())

    def test_lock_conflict_preserves_active_staging(self):
        self.base.mkdir()
        active = self.base / ".android-install.active"
        active.mkdir()
        marker = active / "keep"
        marker.write_bytes(b"active")
        with (self.base / ".android-install.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.shell(self.prefix, expected=1)
        self.assertIn("already running", result.stderr)
        self.assertEqual(b"active", marker.read_bytes())

    def test_unknown_directory_is_not_overwritten(self):
        target = self.base / "android"
        target.mkdir(parents=True)
        marker = target / "user-content"
        marker.write_bytes(b"keep")
        self.shell(self.prefix, expected=1)
        self.assertEqual(b"keep", marker.read_bytes())
        self.assertEqual([], [path for path in self.base.glob(".android-install.*") if path.is_dir()])

    def test_future_version_is_not_overwritten(self):
        target = self.managed_install(version="future-managed-version")
        self.shell(self.prefix, expected=1)
        self.assertEqual("future-managed-version\n", (target / ".ready").read_text())
        self.assertFalse(self.calls.exists())

    def test_symlink_target_is_not_followed(self):
        self.base.mkdir()
        outside = self.root / "outside-sdk"
        outside.mkdir()
        (outside / "keep").write_bytes(b"keep")
        (self.base / "android").symlink_to(outside, target_is_directory=True)
        self.shell(self.prefix, expected=1)
        self.assertEqual(b"keep", (outside / "keep").read_bytes())

    def test_damaged_managed_install_enters_staging_without_deleting_old_sdk(self):
        target = self.managed_install(valid=False)
        probe = self.root / "staging-observed"
        script = self.prefix + "\n" + """
test -d "$staging/android/sdk/cmdline-tools"
test -f "$target/.ready"
printf '%s' "$staging" > """ + shlex.quote(str(probe)) + "\n"
        self.shell(script)
        staging = Path(probe.read_text())
        self.assertEqual(self.base, staging.parent)
        self.assertTrue(staging.name.startswith(".android-install."))
        self.assertFalse(staging.exists(), "normal exit must run the actual cleanup trap")
        self.assertTrue((target / ".ready").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
