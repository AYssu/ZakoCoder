#!/usr/bin/env python3
"""Fail-closed Beta metadata, provenance checks and draft-only GitHub publication.

Never builds/signs APKs or reads signing material. Network mutations occur only in
an explicit `publish` invocation, after verification of the supplied smoke report.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tarfile

VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-beta\.([1-9][0-9]*)\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
PACKAGE = "dev.zakocoder.app"


def version_code(value):
    if isinstance(value, bool) or not re.fullmatch(r"[1-9][0-9]{0,9}", str(value)):
        raise ValueError("versionCode must be an explicit positive decimal integer")
    code = int(value)
    if code > 2_100_000_000:
        raise ValueError("versionCode exceeds the Android publishing limit")
    return code


def validate_inputs(version, code, target_ref="main"):
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise ValueError("Only MAJOR.MINOR.PATCH-beta.N versions are accepted")
    if target_ref != "main" and not COMMIT.fullmatch(target_ref):
        raise ValueError("Target must be main or a full lowercase commit SHA")
    return version_code(code)


def validate_commit(commit):
    if not COMMIT.fullmatch(commit):
        raise ValueError("An immutable full commit SHA is required")


def repository(value):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value) or any(part in (".", "..") for part in value.split("/")):
        raise ValueError("Invalid GitHub repository")
    return value


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text())


def gh(*arguments, allow_missing=False):
    result = subprocess.run(["gh", *map(str, arguments)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        if allow_missing and re.search(rb"HTTP 404\b", result.stderr):
            return None
        # Do not relay CLI output that might contain signed download URLs or headers.
        raise RuntimeError("GitHub request failed; verify repository access and service status")
    return result.stdout


def gh_json(*arguments, allow_missing=False):
    data = gh(*arguments, allow_missing=allow_missing)
    return None if data is None else json.loads(data)


def require_new_tag(repo, tag):
    repo = repository(repo)
    if gh_json("api", f"repos/{repo}/git/ref/tags/{tag}", allow_missing=True) is not None:
        raise ValueError("Tag already exists; published versions are immutable")
    if gh_json("api", f"repos/{repo}/releases/tags/{tag}", allow_missing=True) is not None:
        raise ValueError("Release already exists; choose a new Beta version")


def published_builds(repo):
    pages = gh_json("api", "--paginate", "--slurp", f"repos/{repository(repo)}/releases?per_page=100")
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ValueError("Unexpected releases response")
    builds = []
    for release in (item for page in pages for item in page):
        if release.get("draft"):
            continue
        assets = [item for item in release.get("assets", []) if item.get("name") == "BUILD.json"]
        if len(assets) != 1 or type(assets[0].get("id")) is not int:
            raise ValueError("Every published release needs exactly one readable BUILD.json for versionCode ordering")
        data = gh("api", f"repos/{repo}/releases/assets/{assets[0]['id']}", "-H", "Accept: application/octet-stream")
        if len(data) > 1_000_000:
            raise ValueError("Published BUILD.json is too large")
        builds.append(json.loads(data))
    return builds


def require_increasing_code(code, builds):
    proposed = version_code(code)
    for build in builds:
        if not isinstance(build, dict) or build.get("schemaVersion") != 1 or build.get("package") != PACKAGE:
            raise ValueError("Published build metadata has an unsupported schema or package")
        if proposed <= version_code(build.get("versionCode")):
            raise ValueError("versionCode must exceed every published Android release")


def preflight(repo, version, code):
    validate_inputs(version, code)
    require_new_tag(repo, "v" + version)
    require_increasing_code(code, published_builds(repo))


def checksums(directory):
    directory = Path(directory)
    paths = sorted(path for path in directory.iterdir() if path.name != "SHA256SUMS.txt")
    if not paths or any(not path.is_file() or path.is_symlink() for path in paths):
        raise ValueError("Public assets must be regular top-level files")
    if any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", path.name) for path in paths):
        raise ValueError("Unsafe public asset filename")
    (directory / "SHA256SUMS.txt").write_text("".join(f"{sha256(path)}  {path.name}\n" for path in paths))


def create_metadata(directory, version, code, commit, certificate, run_url, signing_key_kind="debug-reused-for-beta"):
    code = validate_inputs(version, code)
    validate_commit(commit)
    if signing_key_kind not in ("release", "debug-reused-for-beta"):
        raise ValueError("Unknown signing key kind")
    if not DIGEST.fullmatch(certificate):
        raise ValueError("Signing certificate must be a SHA-256 fingerprint")
    if not re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/[0-9]+", run_url):
        raise ValueError("Invalid workflow provenance URL")
    directory = Path(directory)
    apks = list(directory.glob("*.apk"))
    required = ("native-runtime-sources.tar.gz", "licenses.tar.gz", "runtime-validation.tar.gz", "LICENSE.txt", "THIRD_PARTY.md")
    if len(apks) != 1 or apks[0].name != f"ZakoCoder-{version}-arm64.apk":
        raise ValueError("Expected exactly the public ARM64 Beta APK")
    if any(not (directory / name).is_file() or (directory / name).is_symlink() for name in required):
        raise ValueError("Corresponding source, licenses and runtime evidence must accompany the APK")
    metadata = {"schemaVersion": 1, "version": version, "versionCode": code, "package": PACKAGE,
                "commit": commit, "abi": "arm64-v8a", "buildType": "release", "minified": True,
                "prerelease": True, "signingKeyKind": signing_key_kind, "apk": apks[0].name, "apkSha256": sha256(apks[0]),
                "signingCertificateSha256": certificate, "workflowRun": run_url,
                "createdAt": datetime.now(timezone.utc).isoformat(),
                "correspondingSourceSha256": sha256(directory / required[0])}
    (directory / "BUILD.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    (directory / "BUILD.txt").write_text(f"ZakoCoder {version} / Android versionCode {code}\nCommit: {commit}\n"
        f"ARM64 minified Release Beta; not a stable release.\nAPK SHA-256: {metadata['apkSha256']}\n"
        f"Certificate SHA-256: {certificate}\nWorkflow: {run_url}\n" +
        ("Signing key: existing CI Debug key reused for Beta; not the long-term production signing key.\n"
         if signing_key_kind == "debug-reused-for-beta" else "Signing key: configured release key.\n") +
        "Release x86_64 emulator smoke verifies shared application code/resources; it does not prove ARM64 hardware compatibility.\n")
    checksums(directory)
    return metadata


def validate_smoke(report, qa_apk, public_apk, metadata):
    if not isinstance(report, dict) or report.get("schemaVersion") != 1 or report.get("status") != "passed":
        raise ValueError("Release smoke report must explicitly pass")
    if report.get("apkSha256") != sha256(qa_apk) or report.get("comparisonApkSha256") != sha256(public_apk):
        raise ValueError("Smoke report does not identify these exact signed APKs")
    identity = report.get("comparisonCodeIdentity")
    if not isinstance(identity, dict) or identity.get("status") != "matched":
        raise ValueError("Release QA and public APK code/resource identity was not proven")
    expected = {"package": metadata.get("package"), "versionName": metadata.get("version"),
                "versionCode": metadata.get("versionCode")}
    validate_inputs(expected["versionName"], expected["versionCode"])
    if expected["package"] != PACKAGE or type(expected["versionCode"]) is not int:
        raise ValueError("Invalid BUILD application identity")
    for field, apk in (("apkInspection", qa_apk), ("comparisonApkInspection", public_apk)):
        inspection = report.get(field)
        if not isinstance(inspection, dict) or inspection.get("status") != "passed":
            raise ValueError("Both actual APK inspections must pass")
        if inspection.get("sha256") != sha256(apk):
            raise ValueError("APK inspection digest differs from the supplied APK")
        if any(inspection.get(key) != value for key, value in expected.items()):
            raise ValueError("APK manifest identity differs from BUILD metadata")
        if type(inspection.get("versionCode")) is not int:
            raise ValueError("APK versionCode must be an integer")
        abis = inspection.get("nativeAbis")
        required = ["arm64-v8a", "x86_64"] if field == "apkInspection" else ["arm64-v8a"]
        if abis != required:
            raise ValueError("APK native ABI inventory differs from the release contract")
    certificate = metadata.get("signingCertificateSha256")
    if not isinstance(certificate, str) or not DIGEST.fullmatch(certificate) or report.get("certificateSha256") != certificate:
        raise ValueError("Actual APK signing certificate differs from BUILD metadata")


def verify_assets(directory):
    directory = Path(directory)
    expected = {}
    for line in (directory / "SHA256SUMS.txt").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)", line)
        if not match or match[2] in expected or match[2] == "SHA256SUMS.txt":
            raise ValueError("Malformed or duplicate asset checksum entry")
        expected[match[2]] = match[1]
    actual = {path.name for path in directory.iterdir()}
    if actual != set(expected) | {"SHA256SUMS.txt"}:
        raise ValueError("Public assets differ from checksum inventory")
    for name, digest in expected.items():
        path = directory / name
        if path.is_symlink() or not path.is_file() or sha256(path) != digest:
            raise ValueError("Public asset checksum mismatch")
    return expected



def public_files(version, with_smoke):
    names = {f"ZakoCoder-{version}-arm64.apk", "BUILD.json", "BUILD.txt", "SHA256SUMS.txt", "LICENSE.txt",
             "THIRD_PARTY.md", "licenses.tar.gz", "native-runtime-sources.tar.gz", "runtime-validation.tar.gz"}
    if with_smoke:
        names.add("release-validation.tar.gz")
    return names


def finalize(directory, qa_apk, report_file):
    directory, report_file = Path(directory), Path(report_file)
    verify_assets(directory)
    metadata = read_json(directory / "BUILD.json")
    if {path.name for path in directory.iterdir()} != public_files(metadata["version"], False):
        raise ValueError("Unexpected public assets; QA APKs and mapping files must remain private")
    apk = directory / f"ZakoCoder-{metadata['version']}-arm64.apk"
    if metadata.get("apkSha256") != sha256(apk):
        raise ValueError("Candidate APK changed before smoke validation")
    validate_smoke(read_json(report_file), qa_apk, apk, metadata)
    evidence = report_file.parent
    files = sorted(evidence.rglob("*"))
    if any(path.is_symlink() or (not path.is_dir() and not path.is_file()) for path in files):
        raise ValueError("Smoke evidence must contain only regular files and directories")
    if sum(path.stat().st_size for path in files if path.is_file()) > 100_000_000:
        raise ValueError("Smoke evidence exceeds the publication size bound")
    with tarfile.open(directory / "release-validation.tar.gz", "w:gz") as archive:
        for path in files:
            if path.is_file():
                archive.add(path, arcname=path.relative_to(evidence).as_posix(), recursive=False)
    metadata["qaApkSha256"] = sha256(qa_apk)
    metadata["releaseSmokeReportSha256"] = sha256(report_file)
    (directory / "BUILD.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    checksums(directory)


def publish(repo, directory, qa_apk, report_file):
    repo = repository(repo)
    directory = Path(directory)
    verify_assets(directory)
    metadata = read_json(directory / "BUILD.json")
    version, code, commit = metadata["version"], metadata["versionCode"], metadata["commit"]
    if {path.name for path in directory.iterdir()} != public_files(version, True):
        raise ValueError("Only the approved public Beta assets may be uploaded")
    validate_inputs(version, code)
    validate_commit(commit)
    if (metadata.get("schemaVersion") != 1 or metadata.get("package") != PACKAGE or metadata.get("abi") != "arm64-v8a"
            or metadata.get("buildType") != "release" or metadata.get("minified") is not True or metadata.get("prerelease") is not True):
        raise ValueError("Invalid public build metadata")
    apk = directory / f"ZakoCoder-{version}-arm64.apk"
    if metadata.get("apk") != apk.name or metadata.get("apkSha256") != sha256(apk):
        raise ValueError("BUILD metadata does not identify the public APK")
    if (metadata.get("qaApkSha256") != sha256(qa_apk) or metadata.get("releaseSmokeReportSha256") != sha256(report_file)
            or metadata.get("correspondingSourceSha256") != sha256(directory / "native-runtime-sources.tar.gz")):
        raise ValueError("Finalized provenance differs from supplied files")
    smoke = read_json(report_file)
    validate_smoke(smoke, qa_apk, apk, metadata)
    with tarfile.open(directory / "release-validation.tar.gz") as evidence:
        reports = [member for member in evidence.getmembers() if member.name in ("report.json", "./report.json") and member.isfile()]
        if len(reports) != 1 or reports[0].size > 1_000_000:
            raise ValueError("Public smoke evidence needs the exact validated report")
        if json.loads(evidence.extractfile(reports[0]).read()) != smoke:
            raise ValueError("Public smoke report differs from the validated report")
    preflight(repo, version, code)
    tag = "v" + version
    key_kind = metadata.get("signingKeyKind")
    if key_kind not in ("release", "debug-reused-for-beta"):
        raise ValueError("BUILD must identify its actual signing key kind")
    signing_note = ("Signed with the existing CI Debug key for Beta only; this is not the long-term production signing key. "
                    if key_kind == "debug-reused-for-beta" else "Signed with the configured release key. ")
    notes = (f"ZakoCoder {version} — ARM64 Beta\n\n"
             "Minified, non-debuggable Release build. " + signing_note + "Verify SHA256SUMS.txt before installation. "
             "Use the same signing certificate for upgrades; do not uninstall to bypass a signature mismatch.\n\n"
             "Includes corresponding native-runtime source, licenses and validation evidence. "
             "Release emulator smoke covers shared code/resources; ARM64 hardware and vendor-specific behavior remain limited.\n\n"
             f"Commit: {commit}\nWorkflow: {metadata['workflowRun']}\n")
    payload = {"tag_name": tag, "target_commitish": commit, "name": tag, "body": notes,
               "draft": True, "prerelease": True, "make_latest": "false"}
    created = None
    try:
        with tempfile.TemporaryDirectory(prefix="zako-beta-") as temporary:
            body = Path(temporary) / "release.json"
            body.write_text(json.dumps(payload))
            created = gh_json("api", "--method", "POST", f"repos/{repo}/releases", "--input", body)
        release_id = created["id"]
        if type(release_id) is not int or not created.get("draft") or created.get("tag_name") != tag:
            raise ValueError("GitHub did not return the expected new draft")
        files = sorted(directory.iterdir())
        for path in files:
            # Address only this newly created draft ID. The uploads API rejects duplicate
            # asset names; there is no overwrite or tag-based race with another release.
            gh("api", "--method", "POST", f"https://uploads.github.com/repos/{repo}/releases/{release_id}/assets?name={path.name}",
               "-H", "Content-Type: application/octet-stream", "--input", path)
        remote = gh_json("api", f"repos/{repo}/releases/{release_id}")
        expected = {path.name: path for path in files}
        assets = remote.get("assets", [])
        if not remote.get("draft") or remote.get("tag_name") != tag or {asset.get("name") for asset in assets} != set(expected):
            raise ValueError("Draft asset inventory differs from prepared public assets")
        for asset in assets:
            path = expected[asset["name"]]
            if type(asset.get("id")) is not int or asset.get("size") != path.stat().st_size:
                raise ValueError("Uploaded asset size differs")
            data = gh("api", f"repos/{repo}/releases/assets/{asset['id']}", "-H", "Accept: application/octet-stream")
            if hashlib.sha256(data).hexdigest() != sha256(path):
                raise ValueError("Uploaded asset SHA-256 differs")
        # A concurrent publication must not silently make this versionCode stale.
        require_increasing_code(code, published_builds(repo))
        with tempfile.TemporaryDirectory(prefix="zako-beta-publish-") as temporary:
            body = Path(temporary) / "publish.json"
            body.write_text(json.dumps({"draft": False, "prerelease": True, "make_latest": "false"}))
            released = gh_json("api", "--method", "PATCH", f"repos/{repo}/releases/{release_id}", "--input", body)
        if released.get("draft") or not released.get("prerelease") or released.get("target_commitish") != commit:
            raise ValueError("Published release state was not confirmed")
        print(released["html_url"])
    except BaseException:
        if isinstance(created, dict) and type(created.get("id")) is int:
            # Never delete a tag, an older draft or a release that already became public.
            current = gh_json("api", f"repos/{repo}/releases/{created['id']}", allow_missing=True)
            if current and current.get("draft") and current.get("tag_name") == tag:
                gh("api", "--method", "DELETE", f"repos/{repo}/releases/{created['id']}")
        raise



def require_publishable_target(commit, latest_main):
    """Keep automatic tagging within the token's existing workflow permissions."""
    validate_commit(commit)
    validate_commit(latest_main)
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, latest_main], check=True)
    result = subprocess.run(["git", "diff", "--quiet", commit, latest_main, "--", ".github/workflows"], check=False)
    if result.returncode == 1:
        raise ValueError("Target workflow files differ from main; choose main or a commit with identical workflows")
    if result.returncode:
        raise RuntimeError("Could not verify the target workflow tree")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    resolve = sub.add_parser("resolve")
    resolve.add_argument("--target-ref", default="main")
    resolve.add_argument("--version", required=True)
    resolve.add_argument("--version-code", required=True)
    resolve.add_argument("--repository", required=True)
    resolve.add_argument("--github-output", type=Path)
    meta = sub.add_parser("metadata")
    for name in ("version", "version-code", "commit", "certificate", "run-url"):
        meta.add_argument("--" + name, required=True)
    meta.add_argument("--directory", required=True, type=Path)
    meta.add_argument("--signing-key-kind", choices=("release", "debug-reused-for-beta"), default="debug-reused-for-beta")
    final = sub.add_parser("finalize")
    for name in ("directory", "qa-apk", "report"):
        final.add_argument("--" + name, required=True, type=Path)
    publish_parser = sub.add_parser("publish")
    publish_parser.add_argument("--repository", required=True)
    for name in ("directory", "qa-apk", "report"):
        publish_parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    if args.command == "resolve":
        validate_inputs(args.version, args.version_code, args.target_ref)
        latest_main = subprocess.check_output(["git", "rev-parse", "--verify", "origin/main^{commit}"], text=True).strip()
        commit = latest_main if args.target_ref == "main" else args.target_ref
        validate_commit(commit)
        require_publishable_target(commit, latest_main)
        preflight(args.repository, args.version, args.version_code)
        if args.github_output:
            with args.github_output.open("a") as output:
                output.write(f"commit={commit}\nversion={args.version}\nversion_code={version_code(args.version_code)}\n")
        print(json.dumps({"commit": commit, "version": args.version, "versionCode": version_code(args.version_code)}))
    elif args.command == "metadata":
        create_metadata(args.directory, args.version, args.version_code, args.commit, args.certificate, args.run_url, args.signing_key_kind)
    elif args.command == "finalize":
        finalize(args.directory, args.qa_apk, args.report)
    else:
        publish(args.repository, args.directory, args.qa_apk, args.report)


if __name__ == "__main__":
    main()
