#!/usr/bin/env python3
"""Prepare verified repository binaries locally; rebuild vendored sources in CI."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "native-runtime"))
from checks.verify import verify  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--build-from-source", action="store_true",
                        help="force a fresh native source build instead of using repository binaries")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "runtime/artifacts.json").read_text())
    lock = json.loads((ROOT / "native-runtime/toolchain.lock.json").read_text())
    target = manifest if args.abi == "arm64-v8a" else manifest["architectures"][args.abi]
    expected = {artifact["name"]: artifact["sha256"] for artifact in target["nativeArtifacts"]}
    artifact_lock = json.loads((ROOT / "native-runtime/artifacts.lock.json").read_text())
    pinned = (artifact_lock if args.abi == "arm64-v8a" else artifact_lock["architectures"][args.abi])["artifacts"]
    if expected != pinned:
        raise ValueError("App runtime metadata differs from the independent native-runtime lock")
    # CI explicitly requests a fresh build, independent of checked-in binaries.
    rebuild = args.build_from_source
    source = ROOT / "native-runtime/prebuilt" / args.abi
    if rebuild or not all((source / name).is_file() for name in expected):
        ndk = args.ndk or os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
        if not ndk:
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            if sdk:
                ndk = Path(sdk) / "ndk" / lock["ndk"]
        if not ndk:
            parser.error("pass --ndk or set ANDROID_HOME / ANDROID_NDK_HOME")
        # Import lazily: the Linux source builder uses fcntl; consuming verified
        # binaries must also work on Windows and macOS without GNU build tools.
        from build import build
        source = build(Path(ndk), ROOT / "native-runtime/build", force=rebuild, abi=args.abi)
        origin = "Source-built"
    else:
        origin = "Repository prebuilt"
    verify(source, expected, args.abi)
    output = ROOT / "runtime-artifacts/jniLibs" / args.abi
    output.mkdir(parents=True, exist_ok=True)
    for name in expected:
        temporary = output / (name + ".tmp")
        shutil.copyfile(source / name, temporary)
        temporary.chmod(0o755)
        temporary.replace(output / name)
    verify(output, expected, args.abi)
    print(f"{origin} PRoot and loader: verified and prepared ({args.abi})")


if __name__ == "__main__":
    main()
