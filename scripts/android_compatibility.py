#!/usr/bin/env python3
"""Run local Android compatibility checks on one explicitly selected device.

Requires prebuilt base, instrumentation, and higher-version upgrade APKs. Never
builds, downloads APKs/images, uninstalls, or clears application data. Explicit
--with-python permits the device provisioning test to install Python via apt.
Use a dedicated test
device: APK installation replaces that package's existing installed version.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import uuid

DEFAULT_CLASSES = tuple("dev.zakocoder.app." + name for name in (
    "HttpTlsCompatibilityDeviceTest", "ProjectFileDeviceTest", "EditorDeviceTest",
    "MobileEntryUiDeviceTest", "AiModelsDeviceTest", "AiConversationDeviceTest",
    "NativeRuntimeArtifactDeviceTest#installedNativeFilesMatchSelfBuiltManifest",
    "SafDirectoryCompatibilityDeviceTest", "LocalDirectoryBrowserDeviceTest",
    "CapabilityPackagesDeviceTest", "ui.EditorPreviewDeviceTest",
    "ui.AiProtocolUiDeviceTest", "ui.AiComposerUsageUiDeviceTest",
))
PERSISTENCE = "dev.zakocoder.app.CompatibilityPersistenceDeviceTest"


def instrumentation_result(output):
    """am instrument can exit zero after a failing or crashed test runner."""
    failures = (r"FAILURES!!!", r"INSTRUMENTATION_FAILED", r"INSTRUMENTATION_ABORTED",
                r"shortMsg=", r"Process crashed", r"INSTRUMENTATION_STATUS_CODE:\s*-[12345]\b")
    if any(re.search(pattern, output) for pattern in failures):
        raise ValueError("Instrumentation reported a test failure or aborted run")
    matches = re.findall(r"^OK\s*\((\d+)\s+tests?\)\s*$", output, re.MULTILINE)
    if len(matches) != 1 or int(matches[0]) < 1:
        raise ValueError("Instrumentation did not prove a nonempty successful test run")
    return int(matches[0])


def locate_aapt(explicit):
    if explicit:
        return str(explicit.resolve())
    executable = shutil.which("aapt2") or shutil.which("aapt")
    if executable:
        return executable
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = sorted((Path(sdk) / "build-tools").glob("*/aapt2"), reverse=True)
        if candidates:
            return str(candidates[0])
    raise ValueError("Pass --aapt or expose Android SDK aapt2/aapt on PATH")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--upgrade-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rootfs", type=Path, help="Explicit local pinned Ubuntu archive; opt in to runtime tests")
    parser.add_argument("--with-python", action="store_true", help="With --rootfs, install Python and verify a real local HTTP service")
    parser.add_argument("--classes", default=",".join(DEFAULT_CLASSES), help="Comma-separated instrumentation classes or class#method")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt", type=Path)
    parser.add_argument("--timeout", type=int, default=1200, help="Timeout per instrumentation invocation, seconds")
    args = parser.parse_args()
    if args.with_python and not args.rootfs:
        parser.error("--with-python requires --rootfs")
    if args.timeout < 1:
        parser.error("--timeout must be positive")
    classes = args.classes.split(",")
    if not classes or any(not re.fullmatch(r"[A-Za-z_$][\w.$]*(?:#[A-Za-z_$][\w$]*)?", name) for name in classes):
        parser.error("--classes must contain Java class names, optionally followed by #method")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / "report.json").exists():
        parser.error("output already contains report.json; choose a new evidence directory")
    report = {"schemaVersion": 1, "startedAt": datetime.now(timezone.utc).isoformat(),
              "serial": args.serial, "runId": str(uuid.uuid4()), "status": "running", "steps": []}

    def command(label, argv, timeout=120, stdin=None, output_filter=None):
        index = len(report["steps"]) + 1
        log = output / f"{index:02d}-{label}.log"
        step = {"name": label, "command": list(map(str, argv)), "log": log.name}
        report["steps"].append(step)
        print(label, flush=True)
        try:
            result = subprocess.run(list(map(str, argv)), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, errors="replace", timeout=timeout, check=False, stdin=stdin)
            captured = output_filter(result.stdout) if output_filter else result.stdout
            log.write_text(captured)
            step["exitCode"] = result.returncode
            if result.returncode:
                raise RuntimeError(f"{label} exited {result.returncode}; see {log}")
            return captured
        except subprocess.TimeoutExpired as error:
            data = error.stdout or b""
            captured = data.decode(errors="replace") if isinstance(data, bytes) else data
            log.write_text(output_filter(captured) if output_filter else captured)
            step["timedOut"] = True
            raise RuntimeError(f"{label} timed out; see {log}") from error

    def adb(label, *arguments, timeout=120):
        return command(label, [args.adb, "-s", args.serial, *arguments], timeout)

    def shell(label, *arguments, timeout=120):
        return adb(label, "shell", shlex.join(map(str, arguments)), timeout=timeout)

    def instrument(label, selection, provision=False):
        extra = ("-e", "compatibilityProvisionRuntime", "true") if provision else ()
        text = shell(label, "am", "instrument", "-w", "-r", "-e", "class", selection,
                     "-e", "compatibilityRunId", report["runId"], *extra, runner, timeout=args.timeout)
        count = instrumentation_result(text)
        report["steps"][-1]["passedTests"] = count

    try:
        aapt = locate_aapt(args.aapt)
        report["apks"] = {}
        for kind, path in (("base", args.apk), ("test", args.test_apk), ("upgrade", args.upgrade_apk)):
            path = path.resolve(strict=True)
            badging = command(f"inspect-{kind}-apk", [aapt, "dump", "badging", path])
            match = re.search(r"^package: name='([^']+)' versionCode='(\d*)'", badging, re.MULTILINE)
            if not match or not re.fullmatch(r"[\w.]+", match[1]) or (kind != "test" and not match[2]):
                raise ValueError(f"Cannot identify {kind} APK package/version")
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            native = re.search(r"^native-code:\s*(.*)$", badging, re.MULTILINE)
            native_abis = re.findall(r"'([A-Za-z0-9_-]+)'", native[1]) if native else []
            report["apks"][kind] = {"path": str(path), "sha256": digest, "nativeAbis": native_abis,
                                    "package": match[1], "versionCode": int(match[2]) if match[2] else None}
        base, test, upgrade = (report["apks"][key] for key in ("base", "test", "upgrade"))
        if base["package"] != upgrade["package"] or upgrade["versionCode"] <= base["versionCode"]:
            raise ValueError("Upgrade must have the same package and a strictly greater versionCode")
        package = base["package"]
        if package != "dev.zakocoder.app.runtimecheck":
            raise ValueError("Compatibility runner only installs the isolated dev.zakocoder.app.runtimecheck package")
        if test["package"] != package + ".test":
            raise ValueError("Test APK must belong to the isolated compatibility package family")
        if adb("device-state", "get-state").strip() != "device":
            raise ValueError("Selected device is not ready")
        report["device"] = {}
        for prop in ("ro.build.version.sdk", "ro.build.version.release", "ro.product.cpu.abilist",
                     "ro.product.model", "ro.build.fingerprint"):
            report["device"][prop] = shell("property-" + prop, "getprop", prop).strip()
        device_abis = report["device"]["ro.product.cpu.abilist"].split(",")
        abi = next((item for item in device_abis if item in base["nativeAbis"]), None)
        if abi not in ("arm64-v8a", "x86_64"):
            raise ValueError("Cannot determine a supported ABI shared by the base APK and selected device")
        if abi not in upgrade["nativeAbis"]:
            raise ValueError("Upgrade APK does not contain the selected base application ABI")
        report["selectedAppAbi"] = abi

        def verify_installed_abi(label):
            # dumpsys contains much more than the ABI. Keep only this exact field
            # in evidence; neither its other metadata nor partial timeout output
            # should be copied into logs or reports.
            def abi_fields(text):
                values = re.findall(r"^\s*primaryCpuAbi=([^\s]+)", text, re.MULTILINE)
                return "".join(f"primaryCpuAbi={value}\n" for value in values)
            text = command(label, [args.adb, "-s", args.serial, "shell",
                                  shlex.join(("dumpsys", "package", package))], output_filter=abi_fields)
            values = re.findall(r"^primaryCpuAbi=([^\s]+)$", text, re.MULTILINE)
            if values != [abi]:
                raise ValueError(f"Installed application ABI is unknown or differs from selected APK/device ABI ({abi})")
            report.setdefault("installedAppAbis", {})[label] = values[0]

        report["device"]["pageSize"] = shell("device-page-size", "getconf", "PAGESIZE").strip()
        if args.rootfs:
            image = args.rootfs.resolve(strict=True)
            with image.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            manifest = json.loads((Path(__file__).resolve().parents[1] / "runtime/artifacts.json").read_text())
            target = manifest if abi == "arm64-v8a" else manifest["architectures"][abi]
            expected = target["rootfs"]
            report["rootfs"] = {"path": str(image), "sha256": digest, "bytes": image.stat().st_size,
                                "abi": abi, "version": expected["version"], "withPython": args.with_python}
            if digest != expected["sha256"] or image.stat().st_size != expected["bytes"]:
                raise ValueError("Supplied rootfs does not match the pinned manifest for the selected device ABI")
        # A prior successful run leaves the higher-version debug APK installed.
        # Permit same-signature debug downgrade only for the isolated package;
        # retain data rather than uninstalling or clearing between runs.
        adb("install-base", "install", "-r", "-d", base["path"])
        verify_installed_abi("installed-base-abi")
        # Old Android 11 DocumentsUI lacks provider-discovery package visibility.
        # This exposes only the test fixture package; it grants no document permissions.
        adb("install-test", "install", "-r", "--force-queryable", test["path"])
        listing = shell("instrumentation-list", "pm", "list", "instrumentation")
        matches = re.findall(r"instrumentation:([\w.]+/[\w.]+)\s+\(target=([\w.]+)\)", listing)
        runners = [component for component, target in matches if target == package and component.split("/")[0] == test["package"]]
        if len(runners) != 1:
            raise ValueError("Expected one installed test runner targeting the supplied base APK")
        runner = runners[0]
        report["runner"] = runner
        instrument("base-compatibility", ",".join(classes))
        instrument("persistence-prepare", PERSISTENCE + "#prepare")
        shell("force-stop", "am", "force-stop", package)
        instrument("persistence-process-death", PERSISTENCE + "#verifyAfterProcessDeath")
        adb("install-upgrade", "install", "-r", upgrade["path"])
        verify_installed_abi("installed-upgrade-abi")
        instrument("persistence-upgrade-and-clean", PERSISTENCE + "#verifyAfterUpgradeAndClean")
        if args.rootfs:
            # Transfer directly into the debug package's private cache. Host input is
            # streamed from a file descriptor, not loaded into memory or staged in
            # shared/public storage. The real installer independently verifies SHA.
            cache = "cache/native-runtime-verification"
            shell("runtime-cache-directory", "run-as", package, "mkdir", "-p", cache)
            transfer = shlex.join(("run-as", package, "sh", "-c", f"cat > {cache}/rootfs.tar.xz"))
            with image.open("rb") as stream:
                command("runtime-transfer-rootfs", [args.adb, "-s", args.serial, "shell", transfer],
                        timeout=args.timeout, stdin=stream)
            method = "installIsolatedPythonEnvironment" if args.with_python else "installIsolatedBaseEnvironment"
            instrument("runtime-provision", "dev.zakocoder.app.NativeRuntimeProvisioningDeviceTest#" + method, provision=True)
            instrument("runtime-execution", "dev.zakocoder.app.RuntimeDeviceTest#guestExecutionCwdUnicodeExitCancellationAndPty")
            if args.with_python:
                instrument("runtime-http-service", "dev.zakocoder.app.RuntimeHttpCompatibilityDeviceTest")
        report["status"] = "passed"
    except (OSError, ValueError, RuntimeError) as error:
        report["status"] = "failed"
        report["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
