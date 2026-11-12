#!/usr/bin/env python3
"""Exercise workflow artifact packaging without signing keys or an SDK."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


class DebugApkWorkflowTest(unittest.TestCase):
    def test_ci_four_builds_share_resolved_identity_and_inventory_version(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / ".github/workflows/ci.yml").read_text()
        job = workflow.split("  android:\n", 1)[1].split("  kernel-plugin:", 1)[0]
        self.assertIn('scripts/build_version.py --channel dev --github-output "$GITHUB_OUTPUT"', job)
        step = job.split("      - name: Check all four distribution builds and actual package inventories\n", 1)[1]
        self.assertIn("BUILD_VERSION: ${{ steps.version.outputs.version }}", step)
        self.assertIn("BUILD_VERSION_CODE: ${{ steps.version.outputs.version_code }}", step)
        command = textwrap.dedent(step.split("        run: |\n", 1)[1].split("      - name: ", 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            (fixture / "scripts").mkdir()
            gradle = fixture / "gradlew"
            gradle.write_text(textwrap.dedent('''\
                #!/usr/bin/env python3
                import json, pathlib, sys
                properties = dict(arg[2:].split('=', 1) for arg in sys.argv[1:] if arg.startswith('-P'))
                # A separate invocation would otherwise resolve a different clock value.
                apk = pathlib.Path('app/build/outputs/apk/debug/app-debug.apk')
                apk.parent.mkdir(parents=True, exist_ok=True)
                apk.write_text(json.dumps(properties))
                '''))
            gradle.chmod(0o755)
            (fixture / "scripts/distribution.py").write_text(textwrap.dedent('''\
                import json, pathlib, sys
                args = sys.argv[1:]
                version = args[args.index('--version') + 1]
                code = args[args.index('--expected-version-code') + 1]
                assert version == '0.1.2111216-dev'
                assert code == '216000000'
                apks = list(pathlib.Path('distribution-ci').glob('*.apk'))
                assert len(apks) == 4
                for apk in apks:
                    properties = json.loads(apk.read_text())
                    assert properties['buildVersion'] == version
                    assert properties['buildVersionCode'] == code
                    edition, abi = properties['distributionEdition'], properties['compatibilityAbis']
                    assert apk.name == f'ZakoCoder-{version}-{edition}-{abi}.apk'
                '''))
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", command], cwd=fixture,
                env={**os.environ, "BUILD_VERSION": "0.1.2111216-dev", "BUILD_VERSION_CODE": "216000000",
                     "ANDROID_HOME": str(fixture / "sdk")}, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_build_metadata_is_written_to_artifact_without_executing_description(self):
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/build-apk.yml"
        lines = workflow.read_text().splitlines(keepends=True)
        end = next(i for i, line in enumerate(lines) if "> apk-output/BUILD.txt" in line)
        start = max(i for i in range(end) if lines[i].lstrip().startswith("printf "))
        command = textwrap.dedent("".join(lines[start:end + 1]))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "apk-output").mkdir()
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", command], cwd=root,
                env={**os.environ, "GITHUB_SHA": "fixture-commit", "GITHUB_REF": "refs/heads/main",
                     "GITHUB_RUN_NUMBER": "12", "GITHUB_RUN_ATTEMPT": "2",
                     "BUILD_VERSION": "0.1.2111216-dev", "BUILD_VERSION_CODE": "216000000"},
                capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("", result.stdout)
            metadata = (root / "apk-output/BUILD.txt").read_text()
            self.assertIn("Commit: fixture-commit\n", metadata)
            self.assertIn("Ref: refs/heads/main\n", metadata)
            self.assertIn("Run: 12 (attempt 2)\n", metadata)
            self.assertIn("kernel plugin APK is optional", metadata)
            self.assertIn("Upgrading does not automatically replace", metadata)
            self.assertEqual(8, len(metadata.splitlines()))
            self.assertIn("Version: 0.1.2111216-dev (versionCode 216000000)", metadata)

    def test_downloads_contain_one_apk_with_its_own_checksum_and_metadata(self):
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/build-apk.yml"
        text = workflow.read_text()
        step = text.split("      - name: Prepare individual downloads\n", 1)[1]
        command = textwrap.dedent(step.split("        run: |\n", 1)[1].split("      - name: ", 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apps = root / "apk-output"
            plugins = root / "plugin-output"
            apps.mkdir()
            plugins.mkdir()
            sources = {}
            for edition in ("lite", "full"):
                for abi in ("arm64-v8a", "x86_64"):
                    name = f"ZakoCoder-0.1.2111216-dev-{edition}-{abi}.apk"
                    source = apps / name
                    source.write_bytes(f"fixture {edition} {abi}".encode())
                    sources[f"debug-{edition}-{abi}"] = source
            for abi in ("arm64-v8a", "x86_64"):
                source = plugins / f"ZakoCoder-kernel-plugin-debug-{abi}.apk"
                source.write_bytes(f"fixture kernel {abi}".encode())
                sources[f"kernel-plugin-{abi}"] = source
            (apps / "BUILD.txt").write_text("Commit: fixture-commit\n")
            (apps / "SHA256SUMS.txt").write_text("complete main checksums\n")
            (plugins / "SHA256SUMS.txt").write_text("complete plugin checksums\n")
            for name in ("native-runtime-sources.tar.gz", "distribution-validation.json", "LICENSE.txt"):
                (apps / name).write_text(f"fixture {name}\n")
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", command], cwd=root,
                env={**os.environ, "GITHUB_SHA": "fixture-commit", "GITHUB_RUN_NUMBER": "12", "BUILD_VERSION": "0.1.2111216-dev"},
                capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            output = root / "artifact-output"
            self.assertEqual(set(sources) | {"support"}, {p.name for p in output.iterdir()})
            for artifact, source in sources.items():
                target = output / artifact
                self.assertEqual({source.name, "SHA256SUMS.txt", "BUILD.txt"}, {p.name for p in target.iterdir()})
                self.assertEqual(source.read_bytes(), (target / source.name).read_bytes())
                expected = f"{hashlib.sha256(source.read_bytes()).hexdigest()}  {source.name}\n"
                self.assertEqual(expected, (target / "SHA256SUMS.txt").read_text())
                self.assertIn("Commit: fixture-commit\n", (target / "BUILD.txt").read_text())
                self.assertIn("Artifact: ", (target / "BUILD.txt").read_text())
            support = output / "support"
            self.assertEqual([], list(support.rglob("*.apk")))
            for source in apps.iterdir():
                if source.suffix != ".apk":
                    self.assertEqual(source.read_bytes(), (support / source.name).read_bytes())
            self.assertEqual((plugins / "SHA256SUMS.txt").read_bytes(),
                             (support / "kernel-plugin-SHA256SUMS.txt").read_bytes())

    def test_each_upload_and_summary_link_uses_its_individual_directory(self):
        workflow = Path(__file__).resolve().parents[1] / ".github/workflows/build-apk.yml"
        text = workflow.read_text()
        steps = text.split("      - name: ")
        uploads = [s for s in steps if "uses: actions/upload-artifact@" in s]
        expected = {f"debug-{edition}-{abi}" for edition in ("lite", "full")
                    for abi in ("arm64-v8a", "x86_64")}
        expected |= {"kernel-plugin-arm64-v8a", "kernel-plugin-x86_64", "support"}
        self.assertEqual(7, len(uploads))
        self.assertEqual(expected, {s.splitlines()[0].removeprefix("Upload ") for s in uploads})
        for step in uploads:
            artifact = step.splitlines()[0].removeprefix("Upload ")
            self.assertIn(f"path: artifact-output/{artifact}/\n", step)
            self.assertIn("if-no-files-found: error\n", step)
            self.assertIn("compression-level: 0\n", step)
            self.assertIn("retention-days: 14\n", step)
            identifier = artifact.replace("-", "_")
            self.assertIn("${{ steps." + identifier + ".outputs.artifact-url }}", text)


if __name__ == "__main__":
    unittest.main()
