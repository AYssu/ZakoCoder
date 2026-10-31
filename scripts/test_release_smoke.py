import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_smoke


class SmokeSafetyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.apk = self.root / 'fixture.apk'
        self.apk.write_bytes(b'fixture-not-an-apk')
        self.args = argparse.Namespace(serial='physical-fixture', apk=self.apk, comparison_apk=None,
                                      aapt=self.root / 'aapt', output=self.root / 'evidence')

    def test_never_operates_or_screenshots_a_physical_device(self):
        with patch.object(release_smoke, 'inspect_apk', return_value={}), \
             patch.object(release_smoke, 'certificate_digest', return_value='a'*64), \
             patch.object(release_smoke, 'Device') as device:
            with self.assertRaisesRegex(ValueError, 'dedicated Android emulator'):
                release_smoke.run(self.args)
            device.return_value.shell.assert_not_called()
            device.return_value.adb.assert_not_called()
            device.return_value.shot.assert_not_called()
        report = json.loads((self.args.output / 'report.json').read_text())
        self.assertEqual('failed', report['status'])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), report['apkSha256'])

    def test_existing_emulator_install_is_preserved_without_screenshot(self):
        self.args.serial = 'emulator-5554'
        with patch.object(release_smoke, 'inspect_apk', return_value={}), \
             patch.object(release_smoke, 'certificate_digest', return_value='a'*64), \
             patch.object(release_smoke, 'Device') as device:
            device.return_value.shell.side_effect = ['1', '35', 'fixture-fingerprint', 'package:dev.zakocoder.app']
            with self.assertRaisesRegex(ValueError, 'already contain'):
                release_smoke.run(self.args)
            device.return_value.adb.assert_not_called()
            device.return_value.shot.assert_not_called()

    def test_manifest_mismatch_prevents_any_device_access(self):
        self.args.comparison_apk = self.apk
        first = {'package': 'dev.zakocoder.app', 'versionName': '0.1.0-beta.1', 'versionCode': 1001}
        second = dict(first, versionCode=1002)
        with patch.object(release_smoke, 'inspect_apk', side_effect=[first, second]), \
             patch.object(release_smoke, 'certificate_digest', return_value='a'*64), \
             patch.object(release_smoke, 'Device') as device:
            with self.assertRaisesRegex(ValueError, 'identity/version differs'):
                release_smoke.run(self.args)
            device.return_value.shell.assert_not_called()
            device.return_value.shot.assert_not_called()


if __name__ == '__main__':
    unittest.main()
