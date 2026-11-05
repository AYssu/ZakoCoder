#!/usr/bin/env python3
"""Exercise the actual workflow metadata command without signing keys or an SDK."""
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


class DebugApkWorkflowTest(unittest.TestCase):
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
                     "GITHUB_RUN_NUMBER": "12", "GITHUB_RUN_ATTEMPT": "2"},
                capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("", result.stdout)
            metadata = (root / "apk-output/BUILD.txt").read_text()
            self.assertIn("Commit: fixture-commit\n", metadata)
            self.assertIn("Ref: refs/heads/main\n", metadata)
            self.assertIn("Run: 12 (attempt 2)\n", metadata)
            self.assertIn("kernel plugin APK is optional", metadata)
            self.assertIn("Upgrading does not automatically replace", metadata)
            self.assertEqual(7, len(metadata.splitlines()))


if __name__ == "__main__":
    unittest.main()
