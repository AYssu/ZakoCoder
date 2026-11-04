#!/usr/bin/env python3
"""Build and verify the pinned Xray-core proxy kernel; never downloads prebuilt executables.

The kernel is a Go executable packaged as a jniLib asset so Android can execute it from
nativeLibraryDir (the app cannot execute arbitrary files from its data directory). This script
builds it from the pinned module version with the pinned Go toolchain, verifies the source version,
copies it to the ignored runtime-artifacts/kernel-jniLibs directory used only by the kernel plugin module and verifies its SHA-256.

Network note: module downloads go through a Gradle-independent Go proxy. Set
PROXY_KERNEL_GOPROXY to override the default mirrors (used by this repository's CI because
proxy.golang.org is not reachable in some networks).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "native-kernel/artifacts.lock.json"
OUTPUT = ROOT / "runtime-artifacts/kernel-jniLibs"
DEFAULT_GOPROXY = "https://mirrors.aliyun.com/goproxy/,https://goproxy.cn,direct"
DEFAULT_GOSUMDB = "sum.golang.google.cn"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def require_go(expected_version: str) -> str:
    go = shutil.which("go")
    if not go:
        raise SystemExit(
            "Go is required to build the proxy kernel. Install the version in native-kernel/go.version "
            "(CI uses actions/setup-go) and re-run."
        )
    actual = subprocess.check_output([go, "version"], text=True).strip()
    if expected_version not in actual:
        raise SystemExit(
            f"Go toolchain mismatch: expected {expected_version}, found '{actual}'. "
            "The kernel hash is pinned per toolchain; use the version from native-kernel/go.version."
        )
    return go


def run(go: str, args: list[str], cwd: Path, env: dict, quiet: bool = True) -> None:
    """Run a Go command and surface its stderr instead of a bare CalledProcessError."""
    result = subprocess.run([go, *args], cwd=cwd, env=env, text=True, capture_output=True)
    if result.returncode != 0:
        raise SystemExit(
            f"Go command failed ({' '.join(args)}):\n{result.stderr.strip()[-4000:]}"
        )
    if not quiet and result.stdout.strip():
        print(result.stdout.strip())


def build(go: str, goarch: str, version: str) -> bytes:
    with tempfile.TemporaryDirectory(prefix="zakocoder-kernel-") as workdir:
        work = Path(workdir)
        env = dict(os.environ)
        env.update(
            {
                "GOOS": "linux",
                "GOARCH": goarch,
                "CGO_ENABLED": "0",
                "GOPROXY": os.environ.get("PROXY_KERNEL_GOPROXY", DEFAULT_GOPROXY),
                "GOSUMDB": os.environ.get("PROXY_KERNEL_GOSUMDB", DEFAULT_GOSUMDB),
                "GOFLAGS": "-mod=mod",
            }
        )
        run(go, ["mod", "init", "kernelbuild"], work, env)
        run(go, ["get", f"github.com/xtls/xray-core@{version}"], work, env)
        # Keep the explicit requirement: this build-only module has no source imports yet, so
        # `go mod tidy` would remove Xray and make `go build <package>` resolve the proxy's stale
        # "latest" version instead of the locked commit.
        output = work / "kernel"
        run(
            go,
            ["build", "-mod=mod", "-trimpath", "-ldflags", "-s -w", "-o", str(output),
             "github.com/xtls/xray-core/main"],
            work,
            env,
        )
        return output.read_bytes()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abis", nargs="*", help="target ABIs (default: all in the lock file)")
    parser.add_argument("--force", action="store_true", help="rebuild even when the cached hash matches")
    parser.add_argument("--strict-hash", action="store_true",
                        help="fail when the built binary hash differs from the lock (same toolchain expected)")
    parser.add_argument("--write-lock", action="store_true",
                        help="update the lock hashes from a reviewed local build (maintainers only)")
    args = parser.parse_args()

    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    version = lock["version"]
    expected_go = lock["goVersion"]
    targets = lock["targets"]
    selected = args.abis or list(targets)
    unknown = [abi for abi in selected if abi not in targets]
    if unknown:
        raise SystemExit(f"Unknown ABI(s): {', '.join(unknown)}")

    go = require_go(expected_go)
    for abi in selected:
        target = targets[abi]
        destination_dir = OUTPUT / abi
        destination = destination_dir / target["asset"]
        expected = target["sha256"]
        if destination.is_file() and not args.force and sha256(destination) == expected:
            print(f"Verified {abi}/{target['asset']}")
            continue
        release = lock.get("release", version)
        print(f"Building Xray-core {release} for {abi} ({target['goarch']})...")
        destination_dir.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".download")
        temporary.write_bytes(build(go, target["goarch"], version))
        actual = sha256(temporary)
        if args.write_lock:
            targets[abi]["sha256"] = actual
            temporary.replace(destination)
            try:
                destination.chmod(0o755)
            except OSError:
                pass
            print(f"Wrote lock hash for {abi}: {actual}")
            continue
        if actual != expected:
            temporary.unlink()
            message = (
                f"Checksum mismatch for {abi}: expected {expected}, built {actual}. "
                "The lock pins both the module version and the Go toolchain."
            )
            if args.strict_hash:
                raise SystemExit(message)
            raise SystemExit(message + " Update native-kernel/artifacts.lock.json only after a reviewed toolchain change.")
        temporary.replace(destination)
        try:
            destination.chmod(0o755)
        except OSError:
            pass
        print(f"Self-built and verified {abi}/{target['asset']} ({actual[:16]}…)")
    if args.write_lock:
        lock["targets"] = targets
        LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print("Updated", LOCK)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
