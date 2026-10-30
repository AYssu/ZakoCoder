"""Offline source-integrity and binary-contract regression tests (standard library only)."""
import json
from pathlib import Path
import shutil
import struct
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from checks.verify import inspect_elf, verify
import build as builder
from bundle_sources import bundle


class SourceIntegrityTest(unittest.TestCase):
    def test_all_vendored_sources_match_lock(self):
        lock = builder.verify_sources()
        self.assertEqual(3, len(lock["sources"]))

    def test_tampered_archive_is_rejected_before_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copyfile(ROOT / "toolchain.lock.json", root / "toolchain.lock.json")
            shutil.copytree(ROOT / "sources", root / "sources")
            archive = root / "sources/proot-b27d81a.tar.gz"
            archive.write_bytes(archive.read_bytes() + b"tampered")
            with patch.object(builder, "ROOT", root), self.assertRaisesRegex(ValueError, "checksum"):
                builder.verify_sources()

    def test_toolchain_and_artifact_target_agree(self):
        source = builder.verify_sources()
        artifacts = json.loads((ROOT / "artifacts.lock.json").read_text())
        for key in ("ndk", "api", "abi"):
            self.assertEqual(source[key], artifacts[key])
        self.assertEqual(set(builder.NAMES), set(artifacts["artifacts"]))

    def test_source_bundle_is_reproducible_and_excludes_build_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            first, second = [Path(temporary) / name for name in ("first.tar.gz", "second.tar.gz")]
            self.assertEqual(bundle(first), bundle(second))
            with tarfile.open(first) as archive:
                names = archive.getnames()
                self.assertIn("native-runtime/build.py", names)
                self.assertIn("native-runtime/sources/proot-b27d81a.tar.gz", names)
                self.assertIn("native-runtime/licenses/ZakoCoder-Apache-2.0.txt", names)
                self.assertFalse(any("/build/" in name or name.endswith(".so") for name in names))


@unittest.skipUnless((ROOT / "build/jniLibs/arm64-v8a/libzakocoder_proot.so").exists(), "build native runtime first for ELF tests")
class ArtifactContractTest(unittest.TestCase):
    directory = ROOT / "build/jniLibs/arm64-v8a"

    def proot(self):
        return bytearray((self.directory / builder.NAMES[0]).read_bytes())

    def test_real_artifacts_match_pins_and_contract(self):
        expected = json.loads((ROOT / "artifacts.lock.json").read_text())["artifacts"]
        self.assertEqual(set(builder.NAMES), set(verify(self.directory, expected)))

    def test_changed_artifact_rejected_even_with_valid_elf(self):
        with self.assertRaisesRegex(ValueError, "checksum"):
            verify(self.directory, {name: "0" * 64 for name in builder.NAMES})

    def test_wrong_machine_rejected(self):
        data = self.proot()
        struct.pack_into("<H", data, 18, 62)
        with self.assertRaisesRegex(ValueError, "ARM64"):
            inspect_elf(data)

    def test_host_interpreter_rejected(self):
        data = self.proot().replace(b"/system/bin/linker64", b"/untrusted/linker64x")
        with self.assertRaisesRegex(ValueError, "Android linker64"):
            inspect_elf(data)

    def test_termux_library_dependency_rejected(self):
        data = self.proot().replace(b"liblog.so\0", b"libbad.so\0")
        with self.assertRaisesRegex(ValueError, "non-system"):
            inspect_elf(data)

    def test_4k_only_segments_rejected(self):
        data = self.proot()
        phoff = struct.unpack_from("<Q", data, 32)[0]
        count = struct.unpack_from("<H", data, 56)[0]
        for i in range(count):
            offset = phoff + i * 56
            if struct.unpack_from("<I", data, offset)[0] == 1:
                struct.pack_into("<Q", data, offset + 48, 4096)
        with self.assertRaisesRegex(ValueError, "16 KiB"):
            inspect_elf(data)

    def test_loader_cannot_be_replaced_with_shared_library(self):
        with self.assertRaisesRegex(ValueError, "loader"):
            inspect_elf(self.proot(), loader=True)


if __name__ == "__main__":
    unittest.main()
