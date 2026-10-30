#!/usr/bin/env python3
"""Offline Android ARM64 PRoot + external loader build. No Gradle/app dependency."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parent
NAMES = ("libzakocoder_proot.so", "libzakocoder_loader.so")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_sources():
    lock = json.loads((ROOT / "toolchain.lock.json").read_text())
    for source in lock["sources"]:
        archive = ROOT / "sources" / source["archive"]
        if sha256(archive) != source["sha256"]:
            raise ValueError(f"Source checksum mismatch: {archive.name}")
    return lock


def fingerprint(ndk):
    """Include all build inputs; a changed recipe never reuses previous artifacts."""
    paths = [ROOT / "build.py", ROOT / "toolchain.lock.json", ROOT / "talloc-cross-answers.txt"]
    paths += sorted((ROOT / "patches").glob("*.patch"))
    paths += [ndk / "source.properties"]
    return hashlib.sha256("".join(sha256(p) for p in paths).encode()).hexdigest()


def run(args, cwd, env, log):
    log.write("$ " + shlex.join(map(str, args)) + "\n")
    log.flush()
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)


def build(ndk, build_dir, force=False):
    build_dir = build_dir.resolve()
    build_dir.mkdir(parents=True, exist_ok=True)
    with (build_dir / ".build.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return _build(ndk, build_dir, force)


def _build(ndk, build_dir, force):
    lock = verify_sources()
    ndk = ndk.resolve()
    properties = (ndk / "source.properties").read_text()
    if f"Pkg.Revision = {lock['ndk']}" not in properties.splitlines():
        raise ValueError(f"Expected Android NDK {lock['ndk']}")
    tools = ndk / "toolchains/llvm/prebuilt" / lock["host"] / "bin"
    cc = tools / f"aarch64-linux-android{lock['api']}-clang"
    if not cc.is_file():
        raise ValueError(f"Missing Linux x86_64 NDK compiler: {cc}")
    build_dir = build_dir.resolve()
    output = build_dir / "jniLibs" / lock["abi"]
    stamp = build_dir / "build-record.json"
    identity = fingerprint(ndk)
    if not force and stamp.is_file():
        record = json.loads(stamp.read_text())
        if record["inputFingerprint"] == identity and all(
            (output / name).is_file() and sha256(output / name) == record["artifacts"].get(name)
            for name in NAMES
        ):
            return output
    build_dir.mkdir(parents=True, exist_ok=True)
    # A fresh temporary source tree prevents stale configure results/objects from surviving changes.
    work = build_dir / "work"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir()
    for source in lock["sources"]:
        with tarfile.open(ROOT / "sources" / source["archive"]) as archive:
            archive.extractall(work, filter="data")
    proot, talloc, shmem = [work / source["directory"] for source in lock["sources"]]
    prefix = work / "prefix"
    (prefix / "include/sys").mkdir(parents=True)
    (prefix / "lib").mkdir()
    # Do not inherit compiler flags, preload, Termux paths or user configure caches.
    env = {key: value for key, value in os.environ.items() if key in ("PATH", "HOME", "TMPDIR")}
    env.update(LANG="C", LC_ALL="C", TZ="UTC", PYTHONHASHSEED="1", SOURCE_DATE_EPOCH="0",
               CC=str(cc), AR=str(tools / "llvm-ar"), RANLIB=str(tools / "llvm-ranlib"))
    flags = f"-O2 -fPIC -ffile-prefix-map={shlex.quote(str(work))}=/src -fdebug-prefix-map={shlex.quote(str(work))}=/src"
    env["CFLAGS"] = flags
    log_path = build_dir / "build.log"
    try:
        with log_path.open("w") as log:
            run(["patch", "-p1", "--batch", "--forward", "-i", ROOT / "patches/0001-shmem-private-tmp.patch"], shmem, env, log)
            run(["patch", "-p1", "--batch", "--forward", "-i", ROOT / "patches/0002-proot-string-header.patch"], proot, env, log)
            shutil.copyfile(ROOT / "talloc-cross-answers.txt", talloc / "cross-answers.txt")
            run(["./configure", "--prefix=/__zakocoder_build_only__", "--disable-rpath", "--disable-python",
                 "--cross-compile", "--cross-answers=cross-answers.txt"], talloc, env, log)
            run([sys.executable, "buildtools/bin/waf", "build", "--targets=talloc", "-j4"], talloc, env, log)
            run([tools / "llvm-ar", "rcs", prefix / "lib/libtalloc.a",
                 *sorted((talloc / "bin/default").glob("talloc*.o")),
                 *sorted((talloc / "bin/default/lib/replace").glob("*.2.o"))], work, env, log)
            shutil.copyfile(talloc / "talloc.h", prefix / "include/talloc.h")
            run([cc, *shlex.split(flags), "-std=c11", "-c", "shmem.c", "-o", "shmem.o"], shmem, env, log)
            run([tools / "llvm-ar", "rcs", prefix / "lib/libandroid-shmem.a", shmem / "shmem.o"], work, env, log)
            shutil.copyfile(shmem / "shm.h", prefix / "include/sys/shm.h")
            env.update(
                CPPFLAGS=f'-DARG_MAX=131072 -DVERSION=\\"zakocoder-b27d81a\\" -I{shlex.quote(str(prefix / "include"))}',
                CFLAGS=flags + " -std=gnu11",
                LDFLAGS=f'-L{shlex.quote(str(prefix / "lib"))} -llog -landroid -Wl,-z,max-page-size=16384',
                LOADER_LDFLAGS="-Wl,-z,max-page-size=16384",
            )
            run(["make", "-C", "src", "-j4", "GIT=false", "PROOT_WITH_LIBANDROID_SHMEM=true",
                 "PROOT_UNBUNDLE_LOADER=/__zakocoder_requires_PROOT_LOADER__",
                 f"STRIP={tools}/llvm-strip", f"OBJCOPY={tools}/llvm-objcopy", f"OBJDUMP={tools}/llvm-objdump"], proot, env, log)
            output.mkdir(parents=True, exist_ok=True)
            for source, name in zip((proot / "src/proot", proot / "src/loader/loader"), NAMES):
                temporary = output / (name + ".tmp")
                shutil.copyfile(source, temporary)
                run([tools / "llvm-strip", "--strip-debug", temporary], work, env, log)
                temporary.chmod(0o755)
                temporary.replace(output / name)
    except subprocess.CalledProcessError:
        print(log_path.read_text()[-6000:], file=sys.stderr)
        raise
    record = {"schemaVersion": 1, "inputFingerprint": identity, "ndk": lock["ndk"],
              "abi": lock["abi"], "api": lock["api"],
              "artifacts": {name: sha256(output / name) for name in NAMES}}
    stamp.write_text(json.dumps(record, indent=2) + "\n")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT"))
    parser.add_argument("--build-dir", type=Path, default=ROOT / "build")
    parser.add_argument("--force", action="store_true", help="rebuild in a fresh source tree")
    parser.add_argument("--verify-sources", action="store_true", help="verify vendored archives without a toolchain")
    args = parser.parse_args()
    if args.verify_sources:
        verify_sources()
        print("Vendored source checksums: OK")
        return
    if not args.ndk:
        parser.error("set ANDROID_NDK_HOME or pass --ndk (NDK 28.2.13676358)")
    print(build(args.ndk, args.build_dir, args.force))


if __name__ == "__main__":
    main()
