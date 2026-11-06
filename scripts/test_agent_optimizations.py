#!/usr/bin/env python3
"""Run isolated local-model agent acceptance tests and retain device-specific evidence.

Never uninstalls the application, installs Ubuntu, or calls a paid model service.
Debug APK upgrades still require a matching installed signing certificate.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CLASSES = (
    "AiSubagentsDeviceTest", "AiSubagentProtocolsDeviceTest", "AiAgentOptimizationDeviceTest", "AiCompactionFallbackDeviceTest", "AiContextDeviceTest",
    "AiToolRegistryDeviceTest", "AgentRunnerControllerDeviceTest", "AiServiceOptionsConfigDeviceTest",
    "AiProviderTransportDeviceTest", "AiSteeringHistoryDeviceTest", "AiTaskQueueDeviceTest",
    "AiUserQuestionDeviceTest", "ui.AiAgentSettingsUiDeviceTest", "ui.AiDelegationEvidenceUiDeviceTest", "ui.AiProtocolUiDeviceTest", "ui.AiServiceOptionsUiDeviceTest",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Exact adb device serial; no automatic selection")
    parser.add_argument("--build", action="store_true", help="Build APKs, JVM tests and lint before installation")
    parser.add_argument("--output", type=Path, help="Ignored evidence directory (default build/agent-validation/<UTC>)")
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = shutil.which("adb") or (str(Path(sdk) / "platform-tools/adb") if sdk else None)
    if not adb:
        parser.error("adb not found; configure ANDROID_HOME or PATH")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = args.output or ROOT / "build/agent-validation" / stamp
    output.mkdir(parents=True, exist_ok=False)
    report = {"started_utc": stamp, "serial": args.serial, "checks": [], "classes": list(CLASSES)}

    def capture(command):
        return subprocess.check_output(command, cwd=ROOT, text=True, timeout=30).strip()

    def run(name, command, timeout=900):
        log = output / (name + ".txt")
        try:
            with log.open("w") as stream:
                completed = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
            code = completed.returncode
        except subprocess.TimeoutExpired:
            code = 124
        report["checks"].append({"name": name, "exit_code": code, "log": log.name})
        if code:
            raise RuntimeError(f"{name} failed ({code}); see {log}")
        return log.read_text(errors="replace")

    device = [adb, "-s", args.serial]
    device_start = None
    try:
        report["commit"] = capture(["git", "rev-parse", "HEAD"])
        diff = capture(["git", "diff", "HEAD"])
        report["tracked_diff_sha256"] = hashlib.sha256(diff.encode()).hexdigest()
        # Include untracked source names so an uncommitted test build cannot masquerade as HEAD.
        report["git_status"] = capture(["git", "status", "--short"])
        changed = capture(["git", "ls-files", "--modified", "--others", "--exclude-standard"]).splitlines()
        report["changed_source_sha256"] = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
            for name in sorted(set(changed)) if (ROOT / name).is_file()}
        report["abi"] = capture(device + ["shell", "getprop", "ro.product.cpu.abi"])
        report["api"] = capture(device + ["shell", "getprop", "ro.build.version.sdk"])
        if args.build:
            run("build", [str(ROOT / "gradlew"), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                ":app:testDebugUnitTest", ":app:lintDebug", ":terminal-emulator:testDebugUnitTest",
                "-PcompatibilityAbis=arm64-v8a,x86_64", "--console=plain"], timeout=1800)
        for name, relative in (("app", "app/build/outputs/apk/debug/app-debug.apk"),
                               ("tests", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")):
            apk = ROOT / relative
            report[name + "_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
            run("install-" + name, device + ["install", "-r", str(apk)], timeout=180)
        device_start = capture(device + ["shell", "date", "+%s"]) + ".000"
        text = run("instrumentation", device + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
            ",".join("dev.zakocoder.app." + name for name in CLASSES),
            "dev.zakocoder.app.test/androidx.test.runner.AndroidJUnitRunner"], timeout=900)
        match = re.search(r"OK \((\d+) tests?\)", text)
        if not match or "FAILURES!!!" in text or "INSTRUMENTATION_FAILED" in text:
            raise RuntimeError("Instrumentation did not report a passing suite; inspect instrumentation.txt and device-log.txt")
        report["passed_tests"] = int(match.group(1))
        report["passed"] = True
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        report["passed"] = False
        report["error"] = str(error)
        print(error, file=sys.stderr)
    finally:
        if device_start is not None:
            try:
                run("device-log", device + ["logcat", "-d", "-T", device_start, "-v", "threadtime",
                    "ZakoAgentBenchmark:I", "AndroidRuntime:E", "TestRunner:I", "*:S"], timeout=30)
            except (RuntimeError, OSError, subprocess.SubprocessError) as log_error:
                report["passed"] = False
                report["log_error"] = str(log_error)
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(f"Evidence: {output}")
    return 0 if report.get("passed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
