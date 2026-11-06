"""Repository runtimes must bypass native tools locally and remain rebuildable."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import types
import unittest
from unittest.mock import patch, Mock

REPO = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare_runtime_tested", REPO / "scripts/prepare_runtime.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("runtime/artifacts.json", "native-runtime/artifacts.lock.json", "native-runtime/toolchain.lock.json"):
            dest = self.root / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / name, dest)
        shutil.copytree(REPO / "native-runtime/prebuilt", self.root / "native-runtime/prebuilt")
        self.builder = Mock()
        self.module = types.ModuleType("build")
        self.module.build = self.builder

    def run_prepare(self, *args):
        with patch.object(prepare, "ROOT", self.root), patch.object(sys, "argv", ["prepare_runtime", *args]), \
                patch.dict(sys.modules, {"build": self.module}), patch.dict(os.environ, {}, clear=True), \
                contextlib.redirect_stdout(io.StringIO()):
            prepare.main()

    def test_both_abis_work_without_ndk_or_native_build_tools(self):
        for abi in ("arm64-v8a", "x86_64"):
            self.run_prepare("--abi", abi)
            source = self.root / "native-runtime/prebuilt" / abi
            output = self.root / "runtime-artifacts/jniLibs" / abi
            for path in source.iterdir():
                self.assertEqual(path.read_bytes(), (output / path.name).read_bytes())
        self.builder.assert_not_called()

    def test_corrupt_repository_binary_is_rejected_before_packaging(self):
        binary = self.root / "native-runtime/prebuilt/arm64-v8a/libzakocoder_proot.so"
        binary.write_bytes(b"broken")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.run_prepare()
        self.builder.assert_not_called()
        self.assertFalse((self.root / "runtime-artifacts").exists())

    def test_explicit_source_build_bypasses_valid_prebuilt(self):
        source = self.root / "native-runtime/prebuilt/arm64-v8a"
        self.builder.return_value = source
        self.run_prepare("--build-from-source", "--ndk", "/test/ndk")
        self.builder.assert_called_once_with(Path("/test/ndk"), self.root / "native-runtime/build", force=True, abi="arm64-v8a")

    def test_missing_binary_falls_back_to_source_build(self):
        source = self.root / "native-runtime/prebuilt/arm64-v8a"
        backup = self.root / "build-fixture"
        shutil.copytree(source, backup)
        (source / "libzakocoder_loader.so").unlink()
        self.builder.return_value = backup
        self.run_prepare("--ndk", "/test/ndk")
        self.builder.assert_called_once_with(Path("/test/ndk"), self.root / "native-runtime/build", force=False, abi="arm64-v8a")

    def test_manifest_lock_mismatch_is_rejected(self):
        path = self.root / "runtime/artifacts.json"
        metadata = json.loads(path.read_text())
        metadata["nativeArtifacts"][0]["sha256"] = "0" * 64
        path.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "metadata differs"):
            self.run_prepare()
        self.builder.assert_not_called()
