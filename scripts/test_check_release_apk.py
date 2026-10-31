#!/usr/bin/env python3
"""Offline tests of the release APK gate; no SDK, device or credentials needed."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import check_release_apk as gate

MANIFEST = '''E: manifest (line=1)
  A: package="dev.zakocoder.app" (Raw: "dev.zakocoder.app")
  A: android:versionCode(0x0101021b)=(type 0x10)0xa
  A: android:versionName(0x0101021c)="0.1.0-beta.1" (Raw: "0.1.0-beta.1")
  E: uses-permission (line=2)
    A: android:name(0x01010003)="android.permission.INTERNET"
  E: application (line=3)
    A: android:networkSecurityConfig(0x01010527)=@0x7f010001
    E: provider (line=4)
      A: android:name(0x01010003)="androidx.core.content.FileProvider"
'''
RESOURCES = '''resource 0x7f010001 dev.zakocoder.app:xml/network_security_config: t=0x03
  (string8) "res/xml/network_security_config.xml"
'''
NETWORK = '''E: network-security-config (line=1)
  E: base-config (line=2)
    A: cleartextTrafficPermitted=(type 0x12)0xffffffff
'''


class ReleaseApkGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.lock = {"nativeArtifacts": [
            {"name": "libzakocoder_proot.so", "sha256": hashlib.sha256(b"proot").hexdigest()},
            {"name": "libzakocoder_loader.so", "sha256": hashlib.sha256(b"loader").hexdigest()},
        ], "architectures": {"x86_64": {"nativeArtifacts": [
            {"name": "libzakocoder_proot.so", "sha256": hashlib.sha256(b"x-proot").hexdigest()},
            {"name": "libzakocoder_loader.so", "sha256": hashlib.sha256(b"x-loader").hexdigest()},
        ]}}}
        (self.root / "runtime").mkdir()
        (self.root / "runtime/artifacts.json").write_text(json.dumps(self.lock))
        self.files = {"classes.dex": b"dex-protocol", "classes10.dex": b"dex-ten",
                      "resources.arsc": b"resources", "assets/editor/python.json": b"grammar",
                      "res/xml/network_security_config.xml": b"compiled-xml",
                      "lib/arm64-v8a/libzakocoder_proot.so": b"proot",
                      "lib/arm64-v8a/libzakocoder_loader.so": b"loader",
                      "lib/arm64-v8a/libtermux.so": b"pty"}
        self.manifest, self.resources, self.network = MANIFEST, RESOURCES, NETWORK
        self.addCleanup(patch.stopall)
        patch.object(gate, "REPOSITORY", self.root).start()
        patch.object(gate.subprocess, "run", side_effect=self.run_aapt).start()

    def run_aapt(self, args, **kwargs):
        self.assertTrue(kwargs["check"])
        if args[1:4] == ["dump", "--values", "resources"]:
            text = self.resources
        elif args[1:3] == ["dump", "xmltree"]:
            text = self.manifest if args[4] == "AndroidManifest.xml" else self.network
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(args, 0, text, "")

    def apk(self, name="public.apk", files=None):
        path = self.root / name
        with zipfile.ZipFile(path, "w") as archive:
            for entry, data in (self.files if files is None else files).items():
                archive.writestr(entry, data)
        return path

    def inspect(self):
        return gate.inspect_apk(self.apk(), Path("aapt"))

    def test_valid_beta_and_high_dex_index(self):
        report = self.inspect()
        self.assertEqual("passed", report["status"])
        self.assertEqual(10, report["versionCode"])
        self.assertEqual(["classes.dex", "classes10.dex"], report["dexFiles"])
        self.assertEqual(["android.permission.INTERNET"], report["permissions"])
        self.assertEqual(64, len(report["sha256"]))

    def test_reject_wrong_package_dev_versions_flags_and_instrumentation(self):
        edits = [("dev.zakocoder.app\"", "dev.zakocoder.app.runtimecheck\""),
                 ("0.1.0-beta.1", "0.1.0-dev"), ("0.1.0-beta.1", "0.1.0-beta"),
                 ("0.1.0-beta.1", "0.1.0-beta.0"), ("(type 0x10)0xa", "(type 0x10)0x0"),
                 ("    E: provider", "    A: android:debuggable(0x1)=(type 0x12)0xffffffff\n    E: provider"),
                 ("    E: provider", "    A: android:testOnly(0x1)=@0x7f000001\n    E: provider"),
                 ("  E: application", "  E: instrumentation\n  E: application")]
        for before, after in edits:
            with self.subTest(after=after):
                self.manifest = MANIFEST.replace(before, after)
                with self.assertRaises(ValueError):
                    self.inspect()

    def test_reject_test_providers_and_fixture_dex(self):
        self.manifest = MANIFEST.replace("androidx.core.content.FileProvider", "dev.zakocoder.app.ImportFixtureProvider")
        with self.assertRaises(ValueError):
            self.inspect()
        self.manifest = MANIFEST
        for marker in gate.FIXTURE_DESCRIPTORS:
            with self.subTest(marker=marker):
                self.files["classes10.dex"] = b"dex" + marker
                with self.assertRaisesRegex(ValueError, "fixture descriptor"):
                    self.inspect()

    def test_reject_network_debug_overrides_user_or_raw_ca(self):
        for fragment in ["  E: debug-overrides (line=3)",
                         '  E: certificates (line=3)\n    A: src="user"',
                         '  E: certificates (line=3)\n    A: src=@0x7f020001']:
            with self.subTest(fragment=fragment):
                self.network = NETWORK + fragment
                with self.assertRaises(ValueError):
                    self.inspect()
        self.network = NETWORK + '  E: certificates (line=3)\n    A: src="system"'
        self.assertEqual("passed", self.inspect()["status"])

    def test_checks_versioned_and_optimized_network_xml_paths(self):
        self.resources = RESOURCES + '''resource 0x7f010001 dev.zakocoder.app:xml/network_security_config: t=0x03
  (string8) "res/a.xml"
'''
        with self.assertRaisesRegex(ValueError, "missing"):
            self.inspect()
        self.files["res/a.xml"] = b"compiled-xml"
        self.assertEqual(2, len(self.inspect()["networkSecurityResources"]))
        self.resources = "no matching entry"
        with self.assertRaisesRegex(ValueError, "resolve"):
            self.inspect()

    def test_native_pins_missing_extra_wrong_and_unknown_abi(self):
        original = self.files.copy()
        mutations = [lambda d: d.pop("lib/arm64-v8a/libzakocoder_loader.so"),
                     lambda d: d.update({"lib/arm64-v8a/libzakocoder_extra.so": b"extra"}),
                     lambda d: d.update({"lib/arm64-v8a/libzakocoder_proot.so": b"modified"}),
                     lambda d: d.update({"lib/x86/libtermux.so": b"unknown"})]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.files = original.copy()
                mutate(self.files)
                with self.assertRaises(ValueError):
                    self.inspect()

    def test_test_ca_and_stored_app_data_zip_paths(self):
        for name in ["res/raw/test_ca.pem", "assets/test-fixtures/fixture.json", "shared_prefs/models.xml"]:
            with self.subTest(name=name):
                files = self.files | {name: b"fixture"}
                with self.assertRaises(ValueError):
                    gate.inspect_apk(self.apk(files=files), Path("aapt"))

    def test_compare_code_and_common_native_bytes(self):
        qa_files = self.files | {"lib/x86_64/libzakocoder_proot.so": b"x-proot",
                                "lib/x86_64/libzakocoder_loader.so": b"x-loader"}
        qa = self.apk("qa.apk", qa_files)
        public = self.apk()
        result = gate.compare_code_identity(qa, public)
        self.assertEqual("matched", result["status"])
        self.assertEqual(["arm64-v8a"], result["sharedNativeAbis"])
        self.assertIn("classes10.dex", result["entrySha256"])
        for name in ["classes.dex", "classes10.dex", "resources.arsc", "assets/editor/python.json",
                     "res/xml/network_security_config.xml", "lib/arm64-v8a/libtermux.so"]:
            with self.subTest(name=name):
                modified = self.apk("modified.apk", self.files | {name: b"changed"})
                with self.assertRaises(ValueError):
                    gate.compare_code_identity(qa, modified)
        with self.assertRaises(ValueError):
            gate.compare_code_identity(qa, self.apk("added.apk", self.files | {"assets/new": b"new"}))

    def test_reject_duplicate_zip_paths(self):
        apk = self.apk()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("classes.dex", b"second")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            gate.inspect_apk(apk, Path("aapt"))

    def test_cli_writes_failure_json_and_rejects_version_mismatch(self):
        apk = self.apk()
        report = self.root / "report.json"
        args = ["check_release_apk.py", "--apk", str(apk), "--aapt", "aapt", "--output", str(report)]
        with patch("sys.argv", args), patch("builtins.print"):
            self.assertEqual(0, gate.main())
        self.assertEqual("passed", json.loads(report.read_text())["status"])
        self.manifest = MANIFEST.replace("0.1.0-beta.1", "0.1.0-dev")
        with patch("sys.argv", args), patch("builtins.print"):
            self.assertEqual(1, gate.main())
        self.assertEqual("failed", json.loads(report.read_text())["status"])
        valid = {"status": "passed", "package": "dev.zakocoder.app", "versionName": "0.1.0-beta.1", "versionCode": 1}
        changed = valid | {"versionCode": 2}
        with patch("sys.argv", args + ["--comparison-apk", str(apk)]), patch("builtins.print"), \
             patch.object(gate, "inspect_apk", side_effect=[valid, changed]):
            self.assertEqual(1, gate.main())
        self.assertIn("version differs", json.loads(report.read_text())["error"])

    def test_aapt_failure_is_not_success(self):
        with patch.object(gate.subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["aapt"])):
            with self.assertRaises(subprocess.CalledProcessError):
                self.inspect()


if __name__ == "__main__":
    unittest.main()
