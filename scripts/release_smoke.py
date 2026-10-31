#!/usr/bin/env python3
"""Black-box smoke of the actual minified Beta APK on a fresh dedicated emulator.

Does not add test entry points/CA to the product or touch a physical phone. Only the
package installed by this run is removed. Evidence is bound to both APK hashes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from check_release_apk import inspect_apk, compare_code_identity
from sign_beta_apk import certificate_digest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.zakocoder.app'


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


class Device:
    def __init__(self, serial, output):
        self.serial, self.output = serial, output
        self.labels = {}
        for file in (ROOT / 'app/src/main/res/values').glob('*.xml'):
            for item in ET.parse(file).getroot().findall('string'):
                self.labels[item.attrib['name']] = ''.join(item.itertext())
        self.last_xml = ''

    def adb(self, *args, binary=False):
        result = subprocess.run(['adb', '-s', self.serial, *map(str, args)], check=True,
                                capture_output=True, text=not binary, timeout=45)
        return result.stdout

    def shell(self, *args):
        return self.adb('shell', shlex.join(map(str, args)))

    def dump(self):
        self.shell('uiautomator', 'dump', '/sdcard/zako-beta-smoke.xml')
        self.last_xml = self.shell('cat', '/sdcard/zako-beta-smoke.xml')
        return ET.fromstring(self.last_xml)

    def find(self, label, *, click=False, scroll=False, timeout=45):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            tree = self.dump()
            matches = [n for n in tree.iter('node') if
                       label in (n.get('text'), n.get('content-desc')) and n.get('enabled') == 'true']
            for node in matches:
                rect = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
                if len(rect) != 4 or rect[2] <= rect[0] or rect[3] <= rect[1]:
                    continue
                if click:
                    self.shell('input', 'tap', (rect[0]+rect[2])//2, (rect[1]+rect[3])//2)
                return node
            if scroll:
                size = re.findall(r'(\d+)x(\d+)', self.shell('wm', 'size'))[-1]
                width, height = map(int, size)
                self.shell('input', 'swipe', width//2, int(height*.74), width//2, int(height*.35), 250)
            time.sleep(.3)
        raise RuntimeError(f'Expected visible UI label: {label}')

    def click(self, resource, **kwargs):
        return self.find(self.labels.get(resource, resource), click=True, **kwargs)

    def text(self, resource, value, scroll=False):
        self.click(resource, scroll=scroll)
        self.shell('input', 'keycombination', 113, 29)  # Ctrl+A
        self.shell('input', 'keyevent', 67)
        self.shell('input', 'text', value)
        self.hide_keyboard()

    def hide_keyboard(self):
        state = self.shell('dumpsys', 'input_method')
        if re.search(r'(?:mInputShown|mIsInputViewShown|isInputViewShown)=true', state):
            self.shell('input', 'keyevent', 4)

    def shot(self, name):
        (self.output / (name + '.png')).write_bytes(self.adb('exec-out', 'screencap', '-p', binary=True))
        self.dump()
        (self.output / (name + '.xml')).write_text(self.last_xml, encoding='utf-8')

    def launch(self):
        self.shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')

    def restart(self):
        self.shell('am', 'force-stop', PACKAGE)
        self.launch()


def run(args):
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / 'report.json').exists():
        raise ValueError('Output already contains a smoke report')
    report = {'schemaVersion': 1, 'status': 'failed', 'apkSha256': sha256(args.apk),
              'checks': [], 'limitations': [
                  'API 35 emulator smoke; not ARM64 Release OEM/runtime/SSH/LSP end-to-end acceptance.',
                  'Model configuration persistence is checked without sending paid API requests.']}
    device = Device(args.serial, args.output)
    installed = False
    old_night = None
    old_ime = None
    try:
        report['apkInspection'] = inspect_apk(args.apk, args.aapt)
        apksigner = args.aapt.with_name('apksigner')
        report['certificateSha256'] = certificate_digest(apksigner, args.apk)
        if args.comparison_apk:
            report['comparisonApkSha256'] = sha256(args.comparison_apk)
            report['comparisonApkInspection'] = inspect_apk(args.comparison_apk, args.aapt)
            for field in ('package', 'versionName', 'versionCode'):
                if report['apkInspection'][field] != report['comparisonApkInspection'][field]:
                    raise ValueError('Validation and public APK identity/version differs')
            report['comparisonCodeIdentity'] = compare_code_identity(args.apk, args.comparison_apk)
            if certificate_digest(apksigner, args.comparison_apk) != report['certificateSha256']:
                raise ValueError('Validation and public APK certificates differ')
        if not args.serial.startswith('emulator-') or device.shell('getprop', 'ro.kernel.qemu').strip() != '1':
            raise ValueError('Smoke only runs on a dedicated Android emulator')
        report['api'] = int(device.shell('getprop', 'ro.build.version.sdk').strip())
        report['deviceFingerprint'] = device.shell('getprop', 'ro.build.fingerprint').strip()
        if report['api'] != 35:
            raise ValueError('Beta gate currently requires API 35')
        if ('package:' + PACKAGE) in device.shell('pm', 'list', 'packages', PACKAGE).splitlines():
            raise ValueError('Dedicated emulator must not already contain the production package')
        old_night = device.shell('cmd', 'uimode', 'night').strip()
        old_ime = device.shell('settings', 'get', 'secure', 'show_ime_with_hard_keyboard').strip()
        device.shell('settings', 'put', 'secure', 'show_ime_with_hard_keyboard', 1)
        device.shell('cmd', 'uimode', 'night', 'no')
        result = device.adb('install', '--no-incremental', str(args.apk))
        if 'Success' not in result:
            raise ValueError('APK installation did not report success')
        installed = True
        device.launch()
        device.click('mobile_add_project')
        device.click('mobile_create_empty')
        name = 'beta-smoke-' + uuid.uuid4().hex[:10]
        device.text('project_name', name)
        device.click('template_python')
        device.click('create', scroll=True)
        device.find(name, timeout=35)
        report['checks'].append('project-created-through-real-ui')
        device.click('browse_files')
        device.find('main.py')
        device.click('main.py')
        device.click('editor_content')
        marker = 'beta_release_smoke_' + uuid.uuid4().hex[:10]
        device.shell('input', 'text', '%s#' + marker)
        device.click('editor_save')
        device.click('editor_back')
        device.click('main.py')
        device.dump()
        if marker not in device.last_xml:
            raise RuntimeError('Editor did not expose the saved marker after reopening')
        report['checks'].append('minified-editor-edit-save-reopen')
        device.shot('editor-day')
        device.shell('cmd', 'uimode', 'night', 'yes')
        time.sleep(1)
        device.shot('editor-night')
        device.click('editor_back')
        device.click('back_to_library')
        device.click('nav_settings')
        device.click('ai_settings', scroll=True)
        device.click('ai_models_add_first')
        profile = 'beta-config-' + uuid.uuid4().hex[:8]
        device.text('ai_models_name', profile)
        device.text('ai_endpoint', 'https://example.invalid/v1', scroll=True)
        device.text('ai_model', 'beta-smoke', scroll=True)
        device.click('editor_save')
        device.find(profile)
        device.restart()
        device.click('nav_settings')
        device.click('ai_settings', scroll=True)
        device.find(profile)
        report['checks'].append('model-configuration-retained-after-real-force-stop')
        device.shot('models-night')
        device.shell('cmd', 'uimode', 'night', 'no')
        time.sleep(1)
        device.shot('models-day')
        device.click('ai_models_back')
        device.click('nav_workspace')
        device.find(name)
        device.click('browse_files')
        device.click('main.py')
        device.dump()
        if marker not in device.last_xml:
            raise RuntimeError('Saved source did not survive process restart')
        report['checks'].append('project-and-saved-file-retained-after-real-force-stop')
        report['status'] = 'passed'
    except Exception as error:
        report['error'] = str(error)
        if installed:
            try:
                device.shot('failure')
            except Exception:
                pass
        raise
    finally:
        if installed:
            try:
                device.adb('uninstall', PACKAGE)
            except Exception as error:
                report['status'] = 'failed'
                report['cleanupError'] = str(error)
        if old_night:
            old_mode = old_night.rsplit(' ', 1)[-1]
            if old_mode in ('yes', 'no', 'auto'):
                device.shell('cmd', 'uimode', 'night', old_mode)
        if old_ime is not None:
            if old_ime == 'null':
                device.shell('settings', 'delete', 'secure', 'show_ime_with_hard_keyboard')
            else:
                device.shell('settings', 'put', 'secure', 'show_ime_with_hard_keyboard', old_ime)
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if report['status'] != 'passed':
        raise RuntimeError('Smoke cleanup failed')
    print('Actual Release APK UI smoke: passed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('serial', 'apk', 'aapt', 'output'):
        parser.add_argument('--' + name, required=True, type=str if name == 'serial' else Path)
    parser.add_argument('--comparison-apk', type=Path)
    args = parser.parse_args()
    try:
        run(args)
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f'Release smoke failed: {error}\n')


if __name__ == '__main__':
    main()
