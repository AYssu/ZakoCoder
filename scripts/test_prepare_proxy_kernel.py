"""Source pin and toolchain invariants for the self-built optional kernel."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import prepare_proxy_kernel as kernel


class KernelSourcePinTest(unittest.TestCase):
    def test_toolchain_version_must_match_exactly(self):
        with patch.object(kernel.shutil, "which", return_value="go"), patch.object(
            kernel.subprocess, "check_output", return_value="go1.27.30\n"
        ):
            with self.assertRaisesRegex(SystemExit, "toolchain mismatch"):
                kernel.require_go("go1.27.3")

    def test_selected_toolchain_is_resolved_before_local_build(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "bin" / ("go.exe" if kernel.os.name == "nt" else "go")
            executable.parent.mkdir()
            executable.touch()
            with patch.object(kernel.shutil, "which", return_value="wrapper"), patch.object(
                kernel.subprocess, "check_output", side_effect=["go1.27.3\n", directory + "\n"]
            ):
                self.assertEqual(str(executable), kernel.require_go("go1.27.3"))

    def build_fixture(self, actual_version):
        calls = []
        expected_version = "v1.260327.1-0.20261031132019-a4a3dff5b20c"

        def command(go, args, cwd, env, quiet=True):
            calls.append((args, env))
            if args[0] == "build":
                Path(args[args.index("-o") + 1]).write_bytes(b"fixture-binary")

        metadata = "binary: go1.27.3\n\tmod\tgithub.com/xtls/xray-core\t" + actual_version + "\th1:example\n"
        with patch.object(kernel, "run", side_effect=command), patch.object(
            kernel.subprocess, "check_output", return_value=metadata
        ):
            result = kernel.build("go", "arm64", expected_version)
        return result, calls

    def test_build_retains_exact_requirement_and_disables_toolchain_switches(self):
        result, calls = self.build_fixture("v1.260327.1-0.20261031132019-a4a3dff5b20c")
        self.assertEqual(b"fixture-binary", result)
        self.assertFalse(any(args[:2] == ["mod", "tidy"] for args, _ in calls))
        self.assertTrue(any(args == ["get", "github.com/xtls/xray-core@v1.260327.1-0.20261031132019-a4a3dff5b20c"] for args, _ in calls))
        for _, env in calls:
            self.assertEqual("local", env["GOTOOLCHAIN"])
            self.assertEqual("off", env["GOWORK"])
            self.assertEqual("", env["GOEXPERIMENT"])

    def test_re_resolved_or_wrong_module_is_rejected(self):
        with self.assertRaisesRegex(SystemExit, "module version differs"):
            self.build_fixture("v1.260327.0")


if __name__ == "__main__":
    unittest.main()
