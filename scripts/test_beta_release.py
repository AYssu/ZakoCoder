#!/usr/bin/env python3
"""Offline tests for Beta validation and immutable public asset preparation."""
import json
import copy
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import beta_release as beta


class BetaReleaseTest(unittest.TestCase):
    def test_accepts_only_beta_semver_and_full_trusted_ref_shape(self):
        self.assertEqual(1001, beta.validate_inputs("0.1.0-beta.1", "1001"))
        self.assertEqual(1002, beta.validate_inputs("1.2.3-beta.12", 1002, "a" * 40))
        for version in ("0.1.0", "v0.1.0-beta.1", "0.1.0-alpha.1", "0.1.0-rc.1", "0.1.0-beta.0", "01.0.0-beta.1", "0.1.0-beta.01", "0.1.0-beta.1+local", "0.1.0-beta.1\n", "$(touch /tmp/pwn)", "0.1.0-beta.1; echo bad"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                beta.validate_inputs(version, 1001)
        for ref in ("feature/test", "-option", "refs/heads/main", "HEAD", "a" * 39, "A" * 40, "main\nother", "main; echo bad"):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                beta.validate_inputs("0.1.0-beta.1", 1001, ref)

    def test_version_code_is_positive_bounded_integer_not_boolean_or_expression(self):
        for code in (0, -1, True, None, 1.0, "01", "+1", "1\n", "1;bad", "2100000001", "999999999999999"):
            with self.subTest(code=code), self.assertRaises(ValueError):
                beta.version_code(code)
        self.assertEqual(2_100_000_000, beta.version_code("2100000000"))

    def test_ordering_uses_every_published_build_not_only_latest_tag(self):
        builds = [{"schemaVersion": 1, "package": beta.PACKAGE, "versionCode": code} for code in (900, 1100, 1001)]
        beta.require_increasing_code(1101, builds)
        for code in (1001, 1100):
            with self.assertRaises(ValueError):
                beta.require_increasing_code(code, builds)
        beta.require_increasing_code(1, [])

    def test_missing_or_unknown_published_metadata_fails_closed(self):
        for build in ({}, {"schemaVersion": 2, "package": beta.PACKAGE, "versionCode": 1},
                      {"schemaVersion": 1, "package": "other.app", "versionCode": 1},
                      {"schemaVersion": 1, "package": beta.PACKAGE, "versionCode": True}):
            with self.subTest(build=build), self.assertRaises(ValueError):
                beta.require_increasing_code(1001, [build])

    def test_unreadable_prior_release_is_not_silently_ignored(self):
        with patch.object(beta, "gh_json", return_value=[[{"draft": False, "assets": []}]]):
            with self.assertRaises(ValueError):
                beta.published_builds("AYssu/ZakoCoder")
        with patch.object(beta, "gh_json", return_value=[[{"draft": True, "assets": []}]]):
            self.assertEqual([], beta.published_builds("AYssu/ZakoCoder"))

    def test_existing_tag_or_release_prevents_publication(self):
        for replies in (({"ref": "exists"},), (None, {"draft": True})):
            with patch.object(beta, "gh_json", side_effect=replies), self.assertRaises(ValueError):
                beta.require_new_tag("AYssu/ZakoCoder", "v0.1.0-beta.1")

    def test_rejects_unsafe_repository_and_commit(self):
        for repo in ("../project", "org/../project", "org/name; echo bad", "org/name\n", "org/-n/extra"):
            with self.assertRaises(ValueError):
                beta.repository(repo)
        for commit in ("main", "HEAD", "-abc", "a" * 40 + "\n"):
            with self.assertRaises(ValueError):
                beta.validate_commit(commit)

    def test_target_requires_main_ancestry_and_identical_workflow_tree(self):
        commit, latest = "a" * 40, "b" * 40
        with patch.object(beta.subprocess, "run") as run:
            run.return_value.returncode = 0
            beta.require_publishable_target(commit, latest)
            self.assertEqual(["git", "merge-base", "--is-ancestor", commit, latest], run.call_args_list[0].args[0])
            self.assertEqual(["git", "diff", "--quiet", commit, latest, "--", ".github/workflows"], run.call_args_list[1].args[0])
        for status, error in ((1, ValueError), (128, RuntimeError)):
            with patch.object(beta.subprocess, "run") as run:
                run.return_value.returncode = status
                with self.assertRaises(error):
                    beta.require_publishable_target(commit, latest)
        with patch.object(beta.subprocess, "run", side_effect=beta.subprocess.CalledProcessError(1, "git")) as run:
            with self.assertRaises(beta.subprocess.CalledProcessError):
                beta.require_publishable_target(commit, latest)
            self.assertEqual(1, run.call_count)

    def assets(self, root):
        directory = root / "public"
        directory.mkdir()
        (directory / "ZakoCoder-0.1.0-beta.1-arm64.apk").write_bytes(b"signed-arm-fixture")
        for name in ("native-runtime-sources.tar.gz", "licenses.tar.gz", "runtime-validation.tar.gz", "LICENSE.txt", "THIRD_PARTY.md"):
            (directory / name).write_bytes(name.encode())
        metadata = beta.create_metadata(directory, "0.1.0-beta.1", 1001, "a" * 40, "b" * 64,
                                        "https://github.com/AYssu/ZakoCoder/actions/runs/123")
        return directory, metadata

    def test_metadata_records_real_files_key_kind_and_immutable_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory, metadata = self.assets(Path(temporary))
            self.assertEqual(beta.sha256(directory / metadata["apk"]), metadata["apkSha256"])
            self.assertEqual("debug-reused-for-beta", metadata["signingKeyKind"])
            self.assertEqual("release", metadata["buildType"])
            self.assertTrue(metadata["minified"])
            self.assertEqual("a" * 40, metadata["commit"])
            self.assertEqual(metadata, beta.read_json(directory / "BUILD.json"))
            self.assertIn("not the long-term", (directory / "BUILD.txt").read_text())
            beta.verify_assets(directory)

    def test_corresponding_materials_are_mandatory(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "ZakoCoder-0.1.0-beta.1-arm64.apk").write_bytes(b"apk")
            with self.assertRaises(ValueError):
                beta.create_metadata(directory, "0.1.0-beta.1", 1001, "a" * 40, "b" * 64,
                                     "https://github.com/AYssu/ZakoCoder/actions/runs/123")

    def test_checksum_inventory_detects_tampering_extra_assets_and_duplicates(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory, _ = self.assets(Path(temporary))
            (directory / "LICENSE.txt").write_text("tampered")
            with self.assertRaises(ValueError):
                beta.verify_assets(directory)
            beta.checksums(directory)
            (directory / "mapping.txt").write_text("must not leak")
            with self.assertRaises(ValueError):
                beta.verify_assets(directory)
            (directory / "mapping.txt").unlink()
            manifest = directory / "SHA256SUMS.txt"
            manifest.write_text(manifest.read_text() * 2)
            with self.assertRaises(ValueError):
                beta.verify_assets(directory)

    def test_checksums_reject_symlinks_and_nested_asset_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "target").write_bytes(b"fixture")
            (directory / "linked.apk").symlink_to(directory / "target")
            with self.assertRaises(ValueError):
                beta.checksums(directory)
            (directory / "linked.apk").unlink()
            (directory / "private-mapping").mkdir()
            with self.assertRaises(ValueError):
                beta.checksums(directory)

    def test_smoke_requires_exact_qa_and_public_apks_and_code_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            qa, arm = root / "qa.apk", root / "arm.apk"
            qa.write_bytes(b"qa"); arm.write_bytes(b"arm")
            metadata = {"package": beta.PACKAGE, "version": "0.1.0-beta.1", "versionCode": 1001,
                        "signingCertificateSha256": "b" * 64}
            report = self.smoke_report(qa, arm, metadata)
            beta.validate_smoke(report, qa, arm, metadata)
            for change in ({"status": "failed"}, {"apkSha256": "c" * 64}, {"comparisonApkSha256": "c" * 64},
                           {"comparisonCodeIdentity": {"status": "unchecked"}}, {"comparisonCodeIdentity": True},
                           {"certificateSha256": "c" * 64}):
                with self.subTest(change=change), self.assertRaises(ValueError):
                    beta.validate_smoke(report | change, qa, arm, metadata)
            for field in ("apkInspection", "comparisonApkInspection"):
                for change in ({"status": "failed"}, {"sha256": "c" * 64}, {"package": "other.app"},
                               {"versionName": "0.2.0-beta.1"}, {"versionCode": 1002}, {"versionCode": "1001"},
                               {"nativeAbis": ["x86_64"]}, {"nativeAbis": ["arm64-v8a", "x86_64", "other"]}):
                    altered = copy.deepcopy(report)
                    altered[field].update(change)
                    with self.subTest(field=field, change=change), self.assertRaises(ValueError):
                        beta.validate_smoke(altered, qa, arm, metadata)
            with self.assertRaises(ValueError):
                beta.validate_smoke(report, qa, arm, metadata | {"versionCode": 1002})

    def smoke_report(self, qa, arm, metadata):
        common = {"status": "passed", "package": metadata["package"], "versionName": metadata["version"],
                  "versionCode": metadata["versionCode"]}
        return {"schemaVersion": 1, "status": "passed", "apkSha256": beta.sha256(qa),
                "comparisonApkSha256": beta.sha256(arm), "comparisonCodeIdentity": {"status": "matched"},
                "certificateSha256": metadata["signingCertificateSha256"],
                "apkInspection": common | {"sha256": beta.sha256(qa), "nativeAbis": ["arm64-v8a", "x86_64"]},
                "comparisonApkInspection": common | {"sha256": beta.sha256(arm), "nativeAbis": ["arm64-v8a"]}}

    def test_publish_rejects_missing_public_smoke_evidence_before_any_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory, metadata = self.assets(root)
            qa = root / "qa.apk"; qa.write_bytes(b"qa")
            report = root / "report.json"
            report.write_text(json.dumps(self.smoke_report(qa, directory / metadata["apk"], metadata)))
            with patch.object(beta, "gh") as network:
                with self.assertRaises(ValueError):
                    beta.publish("AYssu/ZakoCoder", directory, qa, report)
                network.assert_not_called()


    def test_finalize_preserves_candidate_hashes_and_binds_public_smoke_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory, metadata = self.assets(root)
            qa = root / "qa.apk"; qa.write_bytes(b"qa")
            evidence = root / "evidence"; evidence.mkdir()
            report = evidence / "report.json"
            report.write_text(json.dumps(self.smoke_report(qa, directory / metadata["apk"], metadata)))
            beta.finalize(directory, qa, report)
            final = beta.read_json(directory / "BUILD.json")
            self.assertEqual(beta.sha256(report), final["releaseSmokeReportSha256"])
            self.assertEqual(beta.sha256(qa), final["qaApkSha256"])
            beta.verify_assets(directory)
            with tarfile.open(directory / "release-validation.tar.gz") as archive:
                self.assertEqual(beta.read_json(report), json.load(archive.extractfile("report.json")))

    def test_finalize_and_publish_reject_metadata_manifest_mismatch_without_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory, metadata = self.assets(root)
            qa = root / "qa.apk"
            qa.write_bytes(b"qa")
            evidence = root / "evidence"
            evidence.mkdir()
            report = evidence / "report.json"
            valid = self.smoke_report(qa, directory / metadata["apk"], metadata)
            invalid = copy.deepcopy(valid)
            invalid["comparisonApkInspection"]["versionCode"] = 1002
            report.write_text(json.dumps(invalid))
            with self.assertRaises(ValueError):
                beta.finalize(directory, qa, report)
            self.assertFalse((directory / "release-validation.tar.gz").exists())
            report.write_text(json.dumps(valid))
            beta.finalize(directory, qa, report)
            final = beta.read_json(directory / "BUILD.json")
            final["signingCertificateSha256"] = "c" * 64
            (directory / "BUILD.json").write_text(json.dumps(final))
            beta.checksums(directory)
            with patch.object(beta, "gh") as network:
                with self.assertRaises(ValueError):
                    beta.publish("AYssu/ZakoCoder", directory, qa, report)
                network.assert_not_called()

    def test_finalize_rejects_private_mapping_even_if_it_has_a_checksum(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory, _ = self.assets(root)
            (directory / "mapping.txt").write_text("private")
            beta.checksums(directory)
            with self.assertRaises(ValueError):
                beta.finalize(directory, root / "qa.apk", root / "report.json")

    def test_published_metadata_reads_hidden_body_and_legacy_build_asset(self):
        record = {"schemaVersion": 1, "package": beta.PACKAGE, "version": "0.1.0-beta.1",
                  "versionCode": 1001, "commit": "a" * 40, "apkSha256": "b" * 64}
        body = "<!-- zakocoder-build\n" + json.dumps(record) + "\n-->"
        releases = [[{"draft": False, "body": body, "assets": []},
                     {"draft": False, "assets": [{"name": "BUILD.json", "id": 42}]}]]
        legacy = record | {"versionCode": 1002}
        with patch.object(beta, "gh_json", return_value=releases), patch.object(beta, "gh", return_value=json.dumps(legacy).encode()):
            builds = beta.published_builds("AYssu/ZakoCoder")
            self.assertEqual([1001, 1002], [item["versionCode"] for item in builds])
            beta.require_increasing_code(1003, builds)
            with self.assertRaises(ValueError):
                beta.require_increasing_code(1002, builds)
        for invalid in ("<!-- zakocoder-build\n{}\n-->", body + body, "<!-- zakocoder-build invalid -->"):
            with patch.object(beta, "gh_json", return_value=[[{"body": invalid, "assets": []}]]), self.assertRaises(ValueError):
                beta.published_builds("AYssu/ZakoCoder")

    def test_successful_publish_uploads_only_apk_and_apk_only_checksums(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory, metadata = self.assets(root)
            qa = root / "qa.apk"
            qa.write_bytes(b"qa")
            evidence = root / "evidence"
            evidence.mkdir()
            report = evidence / "report.json"
            report.write_text(json.dumps(self.smoke_report(qa, directory / metadata["apk"], metadata)))
            beta.finalize(directory, qa, report)
            uploads, payloads = {}, []
            tag = "v0.1.0-beta.1"
            def request_json(*args, **kwargs):
                if "POST" in args:
                    payloads.append(beta.read_json(args[-1]))
                    return {"id": 7, "draft": True, "tag_name": tag}
                if "PATCH" in args:
                    return {"draft": False, "prerelease": True, "target_commitish": metadata["commit"], "html_url": "https://github.com/release"}
                return {"draft": True, "tag_name": tag, "assets": [
                    {"name": name, "id": index, "size": len(data)} for index, (name, data) in enumerate(sorted(uploads.items()), 1)]}
            def request(*args, **kwargs):
                if "POST" in args:
                    path = Path(args[-1])
                    uploads[path.name] = path.read_bytes()
                    return b"{}"
                asset_id = int(args[1].rsplit("/", 1)[1])
                return sorted(uploads.items())[asset_id - 1][1]
            with patch.object(beta, "preflight"), patch.object(beta, "published_builds", return_value=[]), \
                    patch.object(beta, "gh_json", side_effect=request_json), patch.object(beta, "gh", side_effect=request):
                beta.publish("AYssu/ZakoCoder", directory, qa, report)
            self.assertEqual({metadata["apk"], "SHA256SUMS.txt"}, set(uploads))
            self.assertEqual(f"{metadata['apkSha256']}  {metadata['apk']}\n".encode(), uploads["SHA256SUMS.txt"])
            body = payloads[0]["body"]
            self.assertIn("### 主要功能", body)
            self.assertIn("### 安装与开始", body)
            self.assertIn("### 测试范围与已知限制", body)
            self.assertIn("https://github.com/AYssu/ZakoCoder/issues/new/choose", body)
            self.assertIn(f"/blob/{metadata['commit']}/SECURITY.md", body)
            self.assertIn("<!-- zakocoder-build\n", body)
            self.assertIn(f"/archive/{metadata['commit']}.tar.gz", body)
            self.assertEqual(beta.read_json(directory / "BUILD.json"), json.loads(body.split("<!-- zakocoder-build\n")[1].split("\n-->")[0]))
            beta.verify_assets(directory)

    def test_formal_signing_kind_is_recorded_without_debug_claims(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory, _ = self.assets(root)
            metadata = beta.create_metadata(directory, "0.1.0-beta.1", 1001, "a" * 40, "b" * 64,
                                            "https://github.com/AYssu/ZakoCoder/actions/runs/123", "release")
            self.assertEqual("release", metadata["signingKeyKind"])
            self.assertIn("configured release key", (directory / "BUILD.txt").read_text())
            self.assertNotIn("CI Debug key reused", (directory / "BUILD.txt").read_text())


if __name__ == "__main__":
    unittest.main()
