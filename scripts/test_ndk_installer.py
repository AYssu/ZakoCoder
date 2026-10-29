#!/usr/bin/env python3
"""Exercise the real NDK installer offline with bounded fixture archives.

Only managed/cache roots and the pinned digest are redirected. ARM64 executables
are replaced by shell fixtures, so these tests check installation state and path
ownership, not whether the real toolchain compiles Android binaries.
"""

import fcntl
import hashlib
import io
import os
from pathlib import Path
import shlex
import subprocess
import tarfile
import tempfile
import unittest

INSTALLER = Path(__file__).resolve().parents[1] / "app/src/main/assets/android-build/install-ndk.sh"
PINNED_DIGEST = "3e961b2dac764b0e326b271307e7059c2ef1ce3dac5223e75302400727cc046b"
VERSION = "ndk28c-arm64-v1"


class NdkInstallerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="zakocoder-ndk-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.base = self.root / "managed"
        self.cache = self.root / "cache"
        self.target = self.base / "android-ndk-r28c"
        self.sdk = self.base / "android/sdk"
        self.registration = self.sdk / "ndk/28.2.13676358"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "curl-calls"
        self.archive = self.root / "fixture.tar.xz"
        self.write_archive()
        self.env = {**os.environ, "PATH": str(self.bin) + os.pathsep + os.defpath,
                    "MOCK_PAYLOAD": str(self.archive), "MOCK_CURL_CALLS": str(self.calls)}
        for name in ("uname", "cmake", "ninja"):
            self.executable(self.bin / name, "#!/bin/sh\nprintf '" + ("aarch64" if name == "uname" else "fixture") + "\\n'\n")
        self.executable(self.bin / "curl", """#!/usr/bin/python3
import os
from pathlib import Path
import sys
with open(os.environ['MOCK_CURL_CALLS'], 'a') as output:
    output.write('fetch\\n')
args = sys.argv[1:]
Path(args[args.index('-o') + 1]).write_bytes(Path(os.environ['MOCK_PAYLOAD']).read_bytes())
""")
        self.sdk.mkdir(parents=True)
        (self.sdk.parent / ".ready").write_text("sdk36-gradle8.13-v1\n")
        android_jar = self.sdk / "platforms/android-36/android.jar"
        android_jar.parent.mkdir(parents=True)
        android_jar.write_bytes(b"fixture")

    @staticmethod
    def executable(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(0o700)

    def write_archive(self, extra=None):
        entries = {
            "NOTICE": (b"fixture license\n", 0o644),
            "source.properties": (b"Pkg.Revision = 28.2.13676358\n", 0o644),
            "build/cmake/android.toolchain.cmake": (b"# fixture\n", 0o644),
            "toolchains/llvm/prebuilt/linux-arm64/bin/clang": (b"#!/bin/sh\nexit 0\n", 0o755),
            "toolchains/llvm/prebuilt/linux-arm64/bin/ld.lld": (b"#!/bin/sh\nexit 0\n", 0o755),
        }
        with tarfile.open(self.archive, "w:xz") as archive:
            for name, (data, mode) in entries.items():
                member = tarfile.TarInfo("android-ndk-r28c/" + name)
                member.size = len(data)
                member.mode = mode
                archive.addfile(member, io.BytesIO(data))
            if extra:
                archive.addfile(extra)

    def source(self):
        source = INSTALLER.read_text()
        replacements = {
            "base=/opt/zakocoder\n": "base=" + shlex.quote(str(self.base)) + "\n",
            "cache=/var/cache/zakocoder-android\n": "cache=" + shlex.quote(str(self.cache)) + "\n",
            "digest=" + PINNED_DIGEST + "\n": "digest=" + hashlib.sha256(self.archive.read_bytes()).hexdigest() + "\n",
        }
        for before, after in replacements.items():
            self.assertEqual(1, source.count(before), "installer boundary changed")
            source = source.replace(before, after)
        self.assertNotIn("/opt/zakocoder", source)
        self.assertNotIn("/var/cache/zakocoder-android", source)
        return source

    def install(self, success=True):
        result = subprocess.run(["/bin/bash", "-c", self.source()], env=self.env,
                                cwd=self.root, capture_output=True, text=True, timeout=15)
        if success:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def managed(self, valid=True, version=VERSION):
        self.target.mkdir(parents=True)
        (self.target / ".ready").write_text(version + "\n")
        if valid:
            with tarfile.open(self.archive, "r:xz") as archive:
                archive.extractall(self.base, filter="data")
        return self.target

    def test_fresh_install_is_registered_and_ready(self):
        self.install()
        self.assertEqual(VERSION + "\n", (self.target / ".ready").read_text())
        self.assertEqual(str(self.target), os.readlink(self.registration))
        self.assertFalse(list(self.base.glob(".ndk-install.*")))

    def test_ready_retry_restores_missing_registration_without_download(self):
        self.managed()
        self.install()
        self.assertEqual(str(self.target), os.readlink(self.registration))
        self.assertFalse(self.calls.exists())

    def test_ready_retry_cleans_orphans_without_following_links(self):
        self.managed()
        abandoned = self.base / ".ndk-install.abandoned"
        abandoned.mkdir()
        (abandoned / "partial").write_bytes(b"old")
        outside = self.root / "outside"
        outside.mkdir()
        protected = outside / "keep"
        protected.write_bytes(b"keep")
        orphan_link = self.base / ".ndk-install.link"
        orphan_link.symlink_to(outside, target_is_directory=True)
        self.install()
        self.assertFalse(abandoned.exists())
        self.assertTrue(orphan_link.is_symlink())
        self.assertEqual(b"keep", protected.read_bytes())

    def test_lock_conflict_preserves_active_install(self):
        active = self.base / ".ndk-install.active"
        active.mkdir()
        protected = active / "keep"
        protected.write_bytes(b"keep")
        with (self.base / ".android-install.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.install(success=False)
        self.assertIn("already running", result.stderr)
        self.assertEqual(b"keep", protected.read_bytes())
        self.assertFalse(self.calls.exists())

    def test_unknown_target_is_preserved(self):
        self.target.mkdir()
        protected = self.target / "keep"
        protected.write_bytes(b"keep")
        self.install(success=False)
        self.assertEqual(b"keep", protected.read_bytes())
        self.assertFalse(self.calls.exists())

    def test_future_target_is_preserved(self):
        self.managed(version="future-ndk")
        self.install(success=False)
        self.assertEqual("future-ndk\n", (self.target / ".ready").read_text())
        self.assertFalse(self.calls.exists())

    def test_symlink_target_is_rejected(self):
        outside = self.root / "user-ndk"
        outside.mkdir()
        (outside / "keep").write_bytes(b"keep")
        self.target.symlink_to(outside, target_is_directory=True)
        self.install(success=False)
        self.assertEqual(b"keep", (outside / "keep").read_bytes())
        self.assertFalse(self.calls.exists())

    def test_conflicting_registration_is_preserved_before_downloading(self):
        for kind in ("file", "directory", "symlink"):
            with self.subTest(kind=kind):
                self.registration.parent.mkdir(exist_ok=True)
                if kind == "file":
                    self.registration.write_bytes(b"keep")
                elif kind == "directory":
                    self.registration.mkdir()
                else:
                    self.registration.symlink_to(self.root / "other-ndk")
                try:
                    self.install(success=False)
                    self.assertTrue(self.registration.exists() or self.registration.is_symlink())
                    self.assertFalse(self.calls.exists())
                    self.assertFalse(self.target.exists())
                finally:
                    if self.registration.is_dir() and not self.registration.is_symlink():
                        self.registration.rmdir()
                    else:
                        self.registration.unlink()

    def test_registration_parent_symlink_is_rejected_before_installing(self):
        outside = self.root / "other-ndks"
        outside.mkdir()
        (outside / "keep").write_bytes(b"keep")
        (self.sdk / "ndk").symlink_to(outside, target_is_directory=True)
        self.install(success=False)
        self.assertFalse(self.calls.exists(), "ownership conflict must fail before download")
        self.assertFalse(self.target.exists(), "ownership conflict must not create a ready target")
        self.assertEqual(b"keep", (outside / "keep").read_bytes())

    def test_damaged_managed_install_is_replaced_with_backup(self):
        self.managed(valid=False)
        (self.target / "keep").write_bytes(b"old")
        self.install()
        backups = list(self.base.glob(".ndk-backup.*"))
        self.assertEqual(1, len(backups))
        self.assertEqual(b"old", (backups[0] / "keep").read_bytes())
        self.assertFalse((self.target / "keep").exists())
        self.assertEqual(str(self.target), os.readlink(self.registration))

    def test_failed_validation_preserves_previous_managed_install(self):
        self.managed(valid=False)
        (self.target / "keep").write_bytes(b"old")
        self.executable(self.bin / "ninja", "#!/bin/sh\nexit 1\n")
        self.install(success=False)
        self.assertEqual(b"old", (self.target / "keep").read_bytes())
        self.assertFalse(self.registration.exists())
        self.assertFalse(list(self.base.glob(".ndk-install.*")))

    def test_escaping_archive_link_is_rejected(self):
        extra = tarfile.TarInfo("android-ndk-r28c/escape")
        extra.type = tarfile.SYMTYPE
        extra.linkname = "../../outside"
        self.write_archive(extra)
        self.install(success=False)
        self.assertFalse(self.target.exists())
        self.assertFalse(self.registration.exists())
        self.assertFalse(list(self.base.glob(".ndk-install.*")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
