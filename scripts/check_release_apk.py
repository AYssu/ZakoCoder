#!/usr/bin/env python3
"""Fail-closed static gate for locally built ZakoCoder Beta APKs.

No build, download, installation, signing, or application-data access occurs here.
Signing-certificate equality is deliberately checked separately by apksigner.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

REPOSITORY = Path(__file__).resolve().parents[1]
BETA_VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+-beta\.[1-9][0-9]*")
FIXTURE_DESCRIPTORS = (
    b"Lmockwebserver3/", b"Lokhttp3/mockwebserver/", b"Lokhttp3/tls/HeldCertificate;",
    b"Ldev/zakocoder/app/CompatibilityDocumentsProvider;",
    b"Ldev/zakocoder/app/ImportFixtureProvider;",
    b"Ldev/zakocoder/app/CompatibilityPersistenceDeviceTest;",
)
FIXTURE_NAMES = re.compile(
    r"CompatibilityDocumentsProvider|ImportFixtureProvider|MockWebServer|"
    r"(?:^|[/_.-])(?:androidTest|test-fixtures|test[-_]ca|compatibility[-_]ca)(?:[/_.-]|$)",
    re.IGNORECASE,
)


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _aapt(aapt: Path, apk: Path, *args: str) -> str:
    completed = subprocess.run([str(aapt), "dump", *args[:1], str(apk), *args[1:]],
                               check=True, capture_output=True, text=True, timeout=120)
    return completed.stdout


def _tree(text: str) -> list[dict]:
    """Read aapt's compiled XML tree; attributes stay scoped to their element."""
    nodes: list[dict] = []
    stack: list[tuple[int, dict]] = []
    for line in text.splitlines():
        indent = len(line) - len(line.lstrip())
        element = re.match(r"\s*E: ([\w.-]+)(?:\s|$)", line)
        if element:
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = {"name": element[1], "attrs": {}, "parent": stack[-1][1] if stack else None}
            nodes.append(node)
            stack.append((indent, node))
        else:
            attr = re.match(r"\s*A: ([\w:.-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)", line)
            if attr and stack:
                stack[-1][1]["attrs"][attr[1]] = attr[2].strip()
    return nodes


def _string(value: str | None, label: str) -> str:
    matched = re.match(r'^"([^"\n]*)"(?:\s|$)', value or "")
    if not matched:
        raise ValueError(f"Missing or nonliteral {label}")
    return matched[1]


def _false_attribute(attrs: dict, name: str) -> None:
    if name not in attrs:
        return  # Android's documented default for these flags is false.
    value = attrs[name]
    if value not in ("false", "(type 0x12)0x0"):
        raise ValueError(f"Release {name} must be literal false")


def _entries(apk: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(apk) as archive:
        names = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries")
        if any(name.startswith("/") or ".." in name.split("/") for name in names):
            raise ValueError("APK contains unsafe ZIP paths")
        return {name: archive.read(name) for name in names}


def _network_paths(resources: str, resource_id: str) -> set[str]:
    paths: set[str] = set()
    lines = resources.splitlines()
    for index, line in enumerate(lines):
        if re.search(r"\bresource " + re.escape(resource_id) + r"\s", line) and "spec resource" not in line:
            if index + 1 < len(lines):
                value = re.search(r'\(string(?:8|16)\) "([^"]+)"', lines[index + 1])
                if value:
                    paths.add(value[1])
    if not paths:
        raise ValueError("Cannot resolve compiled network security configuration")
    return paths


def inspect_apk(apk: Path, aapt: Path) -> dict:
    entries = _entries(apk)
    manifest = _tree(_aapt(aapt, apk, "xmltree", "AndroidManifest.xml"))
    roots = [node for node in manifest if node["name"] == "manifest" and node["parent"] is None]
    apps = [node for node in manifest if node["name"] == "application"]
    if len(roots) != 1 or len(apps) != 1 or apps[0]["parent"] is not roots[0]:
        raise ValueError("Malformed compiled manifest")
    attributes = roots[0]["attrs"]
    package = _string(attributes.get("package"), "package")
    if package != "dev.zakocoder.app":
        raise ValueError("APK must use production package dev.zakocoder.app")
    version = _string(attributes.get("android:versionName"), "versionName")
    if not BETA_VERSION.fullmatch(version):
        raise ValueError("versionName must be major.minor.patch-beta.N with positive N")
    code = re.fullmatch(r"\(type 0x10\)0x([0-9a-fA-F]+)", attributes.get("android:versionCode", ""))
    if code is None or int(code[1], 16) <= 0:
        raise ValueError("versionCode must be a positive compiled integer")
    app = apps[0]["attrs"]
    _false_attribute(app, "android:debuggable")
    _false_attribute(app, "android:testOnly")
    for node in manifest:
        if node["name"] == "instrumentation":
            raise ValueError("Production APK must not declare instrumentation")
        if any(FIXTURE_NAMES.search(value) for value in node["attrs"].values()):
            raise ValueError("Test fixture component found in compiled manifest")
    reference = re.fullmatch(r"@(0x[0-9a-fA-F]+)", app.get("android:networkSecurityConfig", ""))
    if not reference:
        raise ValueError("Missing literal networkSecurityConfig resource reference")
    # --values resolves actual file paths for every resource configuration, even
    # when resource paths have been optimized or a versioned override was added.
    resources = subprocess.run([str(aapt), "dump", "--values", "resources", str(apk)],
                               check=True, capture_output=True, text=True, timeout=120).stdout
    network_paths = _network_paths(resources, reference[1])
    for path in sorted(network_paths):
        if path not in entries:
            raise ValueError("Network security resource missing from APK")
        config = _tree(_aapt(aapt, apk, "xmltree", path))
        if not config or config[0]["name"] != "network-security-config":
            raise ValueError("Invalid compiled network security XML")
        for node in config:
            if node["name"] == "debug-overrides":
                raise ValueError("Release network configuration contains debug-overrides")
            if node["name"] == "certificates":
                if _string(node["attrs"].get("src"), "certificate src") != "system":
                    raise ValueError("Release certificates must trust system only")
    dex = {name: data for name, data in entries.items() if name.endswith(".dex")}
    if "classes.dex" not in dex:
        raise ValueError("APK is missing classes.dex")
    for name, data in entries.items():
        if FIXTURE_NAMES.search(name) or name.startswith(("shared_prefs/", "databases/", "files/")):
            raise ValueError(f"Test fixture or stored application data ZIP entry: {name}")
        if name in dex and any(marker in data for marker in FIXTURE_DESCRIPTORS):
            raise ValueError(f"Test fixture descriptor found in {name}")
    lock = json.loads((REPOSITORY / "runtime/artifacts.json").read_text())
    architectures = {"arm64-v8a": lock, **lock.get("architectures", {})}
    natives: dict[str, dict[str, str]] = {}
    for name, data in entries.items():
        match = re.fullmatch(r"lib/([^/]+)/([^/]+\.so)", name)
        if match:
            natives.setdefault(match[1], {})[match[2]] = _sha(data)
    if not natives:
        raise ValueError("APK contains no native libraries")
    for abi, libraries in natives.items():
        if abi not in architectures:
            raise ValueError(f"Unsupported native ABI: {abi}")
        expected = {artifact["name"]: artifact["sha256"] for artifact in architectures[abi]["nativeArtifacts"]}
        actual = {name: digest for name, digest in libraries.items() if name.startswith("libzakocoder")}
        if actual != expected:
            raise ValueError(f"Pinned runtime native files differ for {abi}")
    permissions = sorted({_string(node["attrs"].get("android:name"), "permission")
                          for node in manifest if node["name"] in ("uses-permission", "uses-permission-sdk-23")})
    return {"status": "passed", "apk": str(apk.resolve()), "sha256": _sha(apk.read_bytes()),
            "package": package, "versionName": version, "versionCode": int(code[1], 16),
            "debuggable": False, "testOnly": False, "nativeAbis": sorted(natives),
            "nativeSha256": natives, "permissions": permissions,
            "networkSecurityResources": sorted(network_paths), "dexFiles": sorted(dex)}


def _code_entries(entries: dict[str, bytes]) -> dict[str, bytes]:
    return {name: data for name, data in entries.items()
            if name == "resources.arsc" or name.startswith(("res/", "assets/"))
            or name.endswith(".dex")}


def compare_code_identity(qa: Path, arm: Path) -> dict:
    left, right = _entries(qa), _entries(arm)
    left_code, right_code = _code_entries(left), _code_entries(right)
    if "classes.dex" not in left_code or "resources.arsc" not in left_code:
        raise ValueError("Comparison APK lacks required code/resources")
    if left_code != right_code:
        changed = sorted(name for name in left_code.keys() | right_code.keys()
                         if left_code.get(name) != right_code.get(name))
        raise ValueError("QA and public APK code/resource mismatch: " + ", ".join(changed))
    abis = lambda data: {name.split('/')[1] for name in data if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name)}
    shared = abis(left) & abis(right)
    if "arm64-v8a" not in shared:
        raise ValueError("QA/public APK comparison must include common ARM64 native files")
    common_native: dict[str, bytes] = {}
    for abi in sorted(shared):
        prefix = f"lib/{abi}/"
        a = {name: data for name, data in left.items() if name.startswith(prefix)}
        b = {name: data for name, data in right.items() if name.startswith(prefix)}
        if a != b:
            raise ValueError(f"QA/public native files differ for common ABI {abi}")
        common_native.update(a)
    hashes = {name: _sha(data) for name, data in sorted((left_code | common_native).items())}
    identity = _sha(json.dumps(hashes, sort_keys=True, separators=(",", ":")).encode())
    return {"status": "matched", "sha256": identity, "sharedNativeAbis": sorted(shared),
            "entrySha256": hashes, "qaApkSha256": _sha(qa.read_bytes()), "publicApkSha256": _sha(arm.read_bytes())}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--comparison-apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = inspect_apk(args.apk, args.aapt)
        if args.comparison_apk:
            report["comparisonInspection"] = inspect_apk(args.comparison_apk, args.aapt)
            if any(report[key] != report["comparisonInspection"][key]
                   for key in ("package", "versionName", "versionCode")):
                raise ValueError("QA/public APK package or version differs")
            report["comparisonCodeIdentity"] = compare_code_identity(args.comparison_apk, args.apk)
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
        report = {"status": "failed", "error": str(exc)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "report": str(args.output)}))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
