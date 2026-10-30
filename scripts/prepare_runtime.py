#!/usr/bin/env python3
"""Build and verify the vendored native runtime; never download prebuilt executables."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "native-runtime"))
from build import build  # noqa: E402
from checks.verify import verify  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path)
    args = parser.parse_args()
    manifest = json.loads((ROOT / "runtime/artifacts.json").read_text())
    lock = json.loads((ROOT / "native-runtime/toolchain.lock.json").read_text())
    ndk = args.ndk or os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if not ndk:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if sdk:
            ndk = Path(sdk) / "ndk" / lock["ndk"]
    if not ndk:
        parser.error("pass --ndk or set ANDROID_HOME / ANDROID_NDK_HOME")
    expected = {artifact["name"]: artifact["sha256"] for artifact in manifest["nativeArtifacts"]}
    pinned = json.loads((ROOT / "native-runtime/artifacts.lock.json").read_text())["artifacts"]
    if expected != pinned:
        raise ValueError("App runtime metadata differs from the independent native-runtime lock")
    source = build(Path(ndk), ROOT / "native-runtime/build")
    verify(source, expected)
    output = ROOT / "runtime-artifacts/jniLibs" / lock["abi"]
    output.mkdir(parents=True, exist_ok=True)
    for name in expected:
        temporary = output / (name + ".tmp")
        shutil.copyfile(source / name, temporary)
        temporary.chmod(0o755)
        temporary.replace(output / name)
    verify(output, expected)
    print("Self-built PRoot and loader: verified and prepared")


if __name__ == "__main__":
    main()
