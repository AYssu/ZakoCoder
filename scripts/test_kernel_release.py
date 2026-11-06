import hashlib
from pathlib import Path
import tempfile
import zipfile
import unittest
from unittest.mock import patch
import kernel_release as release


class KernelReleaseTest(unittest.TestCase):
    def test_reads_package_and_version(self):
        self.assertEqual(('dev.zakocoder.app.kernel', 1006), release.identity(
            "package: name='dev.zakocoder.app.kernel' versionCode='1006' versionName='0.1.1'"))

    def test_rejects_missing_identity(self):
        with self.assertRaises(ValueError):
            release.identity('application: label=example')

    def test_records_main_module_and_linked_dependencies(self):
        actual = release.modules('binary: go1.26.4\n\tmod\txray\tv1.2.3\th1:abc\n\tdep\tdep\tv2.0.0\th1:def')
        self.assertEqual(['xray', 'dep'], [entry['path'] for entry in actual])

    def test_rejects_unpinned_or_replaced_dependencies(self):
        for info in ('\tmod\txray\t(devel)', '\t=>\t../local', 'binary: go1.26.4'):
            with self.subTest(info=info), self.assertRaises(ValueError):
                release.modules(info)

    def test_rejects_ambiguous_or_missing_signer(self):
        from pathlib import Path
        for output in ('', '\n'.join(f'Signer #{i} certificate SHA-256 digest: ' + 'a' * 64 for i in (1, 2))):
            with patch.object(release, 'run', return_value=output), self.assertRaises(ValueError):
                release.certificate(Path('fixture.apk'), Path('/tools'))

    def test_rejects_signer_kernel_abi_and_debug_mismatches(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / 'plugin.apk'
            kernel = b'regression-fixture'
            lock = {'targets': {'arm64-v8a': {'sha256': hashlib.sha256(kernel).hexdigest()}}}
            main = "package: name='dev.zakocoder.app' versionCode='1006'"
            plugin = "package: name='dev.zakocoder.app.kernel' versionCode='1006'"
            cases = [
                ('signer', plugin, 'arm64-v8a', kernel, ['a', 'b']),
                ('kernel', plugin, 'arm64-v8a', b'wrong', ['a', 'a']),
                ('abi', plugin, 'x86_64', kernel, ['a', 'a']),
                ('debug', plugin + '\napplication-debuggable', 'arm64-v8a', kernel, ['a', 'a']),
                ('version', plugin.replace('1006', '1005'), 'arm64-v8a', kernel, ['a', 'a']),
            ]
            for label, badging, abi, binary, signers in cases:
                with self.subTest(label=label):
                    with zipfile.ZipFile(apk, 'w') as archive:
                        archive.writestr(f'lib/{abi}/libzakocoder_xray.so', binary)
                    with patch.object(release, 'run', side_effect=[main, badging]), patch.object(release, 'certificate', side_effect=signers), self.assertRaises(ValueError):
                        release.validate(apk, Path('main.apk'), Path('/tools'), lock)

    def test_accepts_matching_release_and_exact_locked_kernel(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / 'plugin.apk'
            kernel = b'regression-fixture'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libzakocoder_xray.so', kernel)
            lock = {'targets': {'arm64-v8a': {'sha256': hashlib.sha256(kernel).hexdigest()}}}
            with patch.object(release, 'run', side_effect=["package: name='dev.zakocoder.app' versionCode='1006'", "package: name='dev.zakocoder.app.kernel' versionCode='1006'"]), patch.object(release, 'certificate', return_value='a'):
                self.assertEqual((1006, 'a', kernel), release.validate(apk, Path('main.apk'), Path('/tools'), lock))

    def test_x86_plugin_must_be_single_abi_and_match_its_own_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / 'plugin.apk'
            kernel = b'x86 regression fixture'
            lock = {'targets': {'x86_64': {'sha256': hashlib.sha256(kernel).hexdigest()}}}
            for abi, binary, succeeds in [('x86_64', kernel, True), ('arm64-v8a', kernel, False), ('x86_64', b'wrong', False)]:
                with self.subTest(abi=abi, succeeds=succeeds):
                    with zipfile.ZipFile(apk, 'w') as archive:
                        archive.writestr(f'lib/{abi}/libzakocoder_xray.so', binary)
                    with patch.object(release, 'run', side_effect=[
                            "package: name='dev.zakocoder.app' versionCode='1006'",
                            "package: name='dev.zakocoder.app.kernel' versionCode='1006'"
                    ]), patch.object(release, 'certificate', return_value='a'):
                        if succeeds:
                            self.assertEqual((1006, 'a', kernel), release.validate(apk, Path('main.apk'), Path('/tools'), lock, 'x86_64'))
                        else:
                            with self.assertRaises(ValueError):
                                release.validate(apk, Path('main.apk'), Path('/tools'), lock, 'x86_64')

    def test_two_plugin_packages_require_same_version_and_certificate(self):
        arguments = [Path(name) for name in ('arm.apk', 'x86.apk', 'main-arm.apk', 'main-x86.apk', 'tools')]
        for x86, succeeds in [((1006, 'a', b'x86'), True), ((1005, 'a', b'x86'), False), ((1006, 'b', b'x86'), False)]:
            with self.subTest(x86=x86, succeeds=succeeds), patch.object(release, 'validate', side_effect=[(1006, 'a', b'arm'), x86]) as validate:
                if succeeds:
                    self.assertEqual({'arm64-v8a', 'x86_64'}, set(release.validate_distributions(*arguments, {})))
                    self.assertEqual('arm64-v8a', validate.call_args_list[0].args[-1])
                    self.assertEqual('x86_64', validate.call_args_list[1].args[-1])
                else:
                    with self.assertRaises(ValueError):
                        release.validate_distributions(*arguments, {})

    def test_plugin_workflow_builds_and_signs_both_architectures(self):
        text = (Path(__file__).resolve().parents[1] / '.github/workflows/release-kernel-plugin.yml').read_text()
        self.assertIn('for abi in arm64-v8a x86_64', text)
        self.assertIn('-PcompatibilityAbis="$abi"', text)
        self.assertIn('--x86-apk out/plugin/kernel-plugin-x86_64.apk', text)
        self.assertIn('--force --strict-hash', text)


if __name__ == '__main__':
    unittest.main()
