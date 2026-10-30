#!/usr/bin/env python3
"""Run replacement tests against a separate application ID on an explicitly selected device."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
PACKAGE = "dev.zakocoder.app.runtimecheck"
CLASS_PACKAGE = "dev.zakocoder.app"
CLASSES = [
    "RuntimeCompatibilityTest",
    "RuntimeDeviceTest#guestExecutionCwdUnicodeExitCancellationAndPty",
    "RuntimeDeviceTest#installedProjectToolsActuallyExecute",
    "RuntimeShellDeviceTest",
    "McpRuntimeDeviceTest",
    "NativeRuntimeArtifactDeviceTest",
    "RuntimeInstallerDeviceTest",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--image", required=True, type=Path, help="locally downloaded pinned Ubuntu archive")
    parser.add_argument("--install", action="store_true", help="install only APKs built with -PruntimeVerification=true")
    parser.add_argument("--provision", action="store_true", help="install Ubuntu and Python/Node tools in the isolated package")
    parser.add_argument("--apk-root", type=Path, default=REPO, help="source snapshot containing app/build/outputs/apk")
    args = parser.parse_args()
    manifest = json.loads((REPO / "runtime/artifacts.json").read_text())
    if hashlib.sha256(args.image.read_bytes()).hexdigest() != manifest["rootfs"]["sha256"]:
        parser.error("Ubuntu archive checksum mismatch")
    serial_hash = hashlib.sha256(args.serial.encode()).hexdigest()[:16]
    lock = Path(tempfile.gettempdir()) / f"zakocoder-device-{serial_hash}.lock"
    with lock.open("a") as owner:
        fcntl.flock(owner, fcntl.LOCK_EX)
        adb = ["adb", "-s", args.serial]

        def run(command, **kwargs):
            return subprocess.run(adb + command, check=True, **kwargs)

        # Package isolation allows another app's tests to continue on the same device.
        if args.install:
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            if not sdk:
                parser.error("--install requires ANDROID_HOME for aapt2 package verification")
            aapt = Path(sdk) / "build-tools/36.0.0/aapt2"
            app = args.apk_root / "app/build/outputs/apk/debug/app-debug.apk"
            test = args.apk_root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
            for apk, package in ((app, PACKAGE), (test, PACKAGE + ".test")):
                metadata = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
                match = re.search(r"^package: name='([^']+)'", metadata, re.M)
                if not match or match[1] != package:
                    raise ValueError(f"Refusing to install an APK outside isolated package {package}")
            with zipfile.ZipFile(app) as archive:
                for artifact in manifest["nativeArtifacts"]:
                    data = archive.read("lib/arm64-v8a/" + artifact["name"])
                    if hashlib.sha256(data).hexdigest() != artifact["sha256"]:
                        raise ValueError("APK does not contain the pinned self-built runtime")
            run(["install", "-r", str(app)])
            run(["install", "-r", str(test)])
        run(["shell", "run-as", PACKAGE, "mkdir", "-p", "cache/native-runtime-verification"])
        with args.image.open("rb") as source:
            run(["shell", "run-as", PACKAGE, "sh", "-c", "'cat > cache/native-runtime-verification/rootfs.tar.xz'"], stdin=source)
        output = ROOT / "build/device-results.txt"
        output.parent.mkdir(parents=True, exist_ok=True)

        def instrument(classes, destination, timeout):
            command = ["shell", "am", "instrument", "-w", "-r", "-e", "nativeRuntimeImage", "true", "-e", "class",
                       ",".join(CLASS_PACKAGE + "." + name for name in classes),
                       PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
            print(f"Running {PACKAGE} checks; results: {destination}", flush=True)
            with destination.open("w") as log:
                process = subprocess.Popen(adb + command, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + timeout
                observed = 0
                try:
                    while process.poll() is None:
                        text = destination.read_text()
                        blocks = text.split("INSTRUMENTATION_STATUS_CODE: 1")
                        starts = len(blocks) - 1
                        if starts > observed:
                            # Let ActivityScenario launch first; a duplicate CLEAR_TASK launch would
                            # destroy its activity and invalidate recreate(). Rescue only blocked starts.
                            time.sleep(1)
                            activities = run(["shell", "dumpsys", "activity", "activities"],
                                             capture_output=True, text=True).stdout
                            foreground = any(PACKAGE + "/" in line and
                                             ("mResumedActivity" in line or "topResumedActivity" in line)
                                             for line in activities.splitlines())
                            if not foreground:
                                run(["shell", "am", "start", "-a", "android.intent.action.MAIN", "-c",
                                     "android.intent.category.LAUNCHER", "-f", "0x10008000", "-n",
                                     PACKAGE + "/" + CLASS_PACKAGE + ".MainActivity"], capture_output=True)
                            observed = starts
                        if time.monotonic() > deadline:
                            raise subprocess.TimeoutExpired(command, timeout)
                        time.sleep(0.1)
                    if process.returncode != 0:
                        raise RuntimeError("ADB instrumentation command failed")
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait()
            result = destination.read_text()
            print(result[-4000:], flush=True)
            return result

        try:
            if args.provision:
                setup = instrument(["NativeRuntimeProvisioningDeviceTest"], output.with_name("device-provisioning.txt"), 1200)
                if "OK (1 test)" not in setup or "shortMsg=" in setup:
                    raise RuntimeError("Isolated environment provisioning failed")
            result = instrument(CLASSES, output, 600)
        finally:
            run(["shell", "run-as", PACKAGE, "rm", "-f", "cache/native-runtime-verification/rootfs.tar.xz"])
        if "OK (9 tests)" not in result or "FAILURES" in result or "shortMsg=" in result:
            raise RuntimeError("Runtime validation did not finish with all 9 tests passing; inspect device-results.txt")


if __name__ == "__main__":
    main()
