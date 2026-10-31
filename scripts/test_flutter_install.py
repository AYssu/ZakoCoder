#!/usr/bin/env python3
"""Exercise installer ownership/failure gates offline, not real Flutter compilation."""
import fcntl
import hashlib
import os
from pathlib import Path
import shlex
import signal
import time
import subprocess
import tempfile
import unittest

INSTALLER = Path(__file__).resolve().parents[1] / 'app/src/main/assets/flutter-build/install.sh'
REVISION = 'c197ca5199e9ee5661e865596c9c07294dfab51c'
ENGINE = '89c39edfdfae2949f6806e4a315c0c08522238b7'


class FlutterInstallerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='zako-flutter-install-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.base = self.root / 'managed'
        self.target = self.base / 'flutter'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.calls = self.root / 'calls'
        self.cache = self.root / 'cache'
        self.deb_data = b'fixture bounded deb payload'
        self.qemu_data = b'#!/bin/sh\nexit 0\n'
        self.deb_digest = hashlib.sha256(self.deb_data).hexdigest()
        self.qemu_digest = hashlib.sha256(self.qemu_data).hexdigest()
        self.env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.defpath,
                    'FIXTURE_ROOT': str(self.root), 'FIXTURE_REVISION': REVISION}
        self.executable('uname', '#!/bin/sh\necho aarch64\n')
        self.executable('df', '#!/bin/sh\necho "fixture 99999999 1 99999998 1% /"\n')
        self.executable('qemu-x86_64', '#!/bin/sh\nexit 0\n')
        self.executable('curl', """#!/usr/bin/python3
import os, pathlib, sys
args = sys.argv[1:]
data = b'wrong digest' if os.environ.get('BAD_DOWNLOAD') else b'fixture bounded deb payload'
pathlib.Path(args[args.index('-o')+1]).write_bytes(data)
""")
        self.executable('dpkg-deb', """#!/usr/bin/python3
import os, pathlib, sys
root = pathlib.Path(sys.argv[-1])
binary = root/'usr/bin/qemu-x86_64'
binary.parent.mkdir(parents=True)
binary.write_bytes(b'#!/bin/sh\\nexit 0\\n')
if os.environ.get('BAD_BINARY'):
    binary.write_bytes(b'corrupt binary')
binary.chmod(0o755)
notice = root/'usr/share/doc/qemu-user/copyright'
notice.parent.mkdir(parents=True)
notice.write_text('fixture QEMU copyright')
""")
        self.executable('git', '''#!/usr/bin/python3
import os, pathlib, sys, time
args = sys.argv[1:]
if 'rev-parse' in args:
    print(os.environ['FIXTURE_REVISION'])
else:
    root = pathlib.Path(args[-1])
    root.mkdir(parents=True)
    if os.environ.get('PAUSE_CLONE'):
        (root/'partial-download').write_text('incomplete')
        pathlib.Path(os.environ['FIXTURE_ROOT']+'/clone-started').touch()
        time.sleep(60)
    (root/'LICENSE').write_text('upstream license')
    (root/'bin/internal').mkdir(parents=True)
    (root/'bin/internal/engine.version').write_text('89c39edfdfae2949f6806e4a315c0c08522238b7\\n')
    for name in ('flutter', 'dart'):
        path = root/'bin'/name
        path.write_text('#!/bin/sh\\n[ "${FAIL_FLUTTER:-}" != yes ] || exit 7\\nexit 0\\n')
        path.chmod(0o755)
    for mode in ('profile', 'release'):
        path = root/f'bin/cache/artifacts/engine/android-arm64-{mode}/linux-x64/gen_snapshot'
        path.parent.mkdir(parents=True)
        path.write_text('#!/bin/sh\\nexit 0\\n')
        path.chmod(0o755)
    with open(os.environ['FIXTURE_ROOT']+'/calls', 'a') as log:
        log.write('clone\\n')
''')

    def executable(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def source(self):
        source = INSTALLER.read_text()
        before = 'base=/opt/zakocoder\n'
        self.assertEqual(1, source.count(before))
        source = source.replace(before, 'base=' + shlex.quote(str(self.base)) + '\n')
        source = source.replace('cache=/var/cache/zakocoder-flutter', 'cache=' + shlex.quote(str(self.cache)))
        source = source.replace('qemu_digest=bb3ff9cb31b508bf6ce1b3f6bb4afe2b9a4d20a625a5d102ea3d78d753e5d4d0',
                                'qemu_digest=' + self.deb_digest)
        source = source.replace('qemu_binary_digest=90458b484b67436a47c480fbfaad909cda316020dfb514e3ee40726521605d38',
                                'qemu_binary_digest=' + self.qemu_digest)
        # The CI host need not have Ubuntu's cross libc directory.
        source = source.replace('test -d /usr/x86_64-linux-gnu', 'test -d /tmp')
        return source

    def install(self, success=True):
        result = subprocess.run(['/bin/bash', '-c', self.source()], env=self.env, cwd=self.root,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def test_install_then_retry_preserves_upstream_and_avoids_download(self):
        self.install()
        self.assertEqual('flutter3.47.9-arm64-v2\n', (self.target/'.ready').read_text())
        self.assertEqual('upstream license', (self.target/'LICENSE').read_text())
        self.install()
        self.assertEqual('clone\n', self.calls.read_text())

    def test_qemu_package_and_binary_are_both_verified(self):
        self.install()
        qemu = self.target/'.zakocoder/qemu'
        self.assertEqual(self.qemu_digest, hashlib.sha256((qemu/'qemu-x86_64').read_bytes()).hexdigest())
        self.assertIn('fixture QEMU copyright', (qemu/'copyright').read_text())
        self.assertIn('qemu_9.2.1+ds-1ubuntu5.2.dsc', (qemu/'SOURCE.txt').read_text())
        wrapper = self.target/'bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot'
        self.assertIn('$sdk/.zakocoder/qemu/qemu-x86_64', wrapper.read_text())
        self.assertNotIn('/usr/bin/qemu-x86_64', wrapper.read_text())

    def test_bad_qemu_download_is_rejected_before_ready(self):
        self.env['BAD_DOWNLOAD'] = 'yes'
        self.install(False)
        self.assertFalse(self.target.exists())
        self.assertFalse((self.cache/(self.deb_digest+'.deb')).exists())
        self.assertFalse((self.cache/(self.deb_digest+'.deb.partial')).exists())

    def test_bad_extracted_binary_is_rejected_before_ready(self):
        self.env['BAD_BINARY'] = 'yes'
        self.install(False)
        self.assertFalse(self.target.exists())

    def test_installer_pins_official_package_and_binary(self):
        source = INSTALLER.read_text()
        self.assertIn('qemu_digest=bb3ff9cb31b508bf6ce1b3f6bb4afe2b9a4d20a625a5d102ea3d78d753e5d4d0', source)
        self.assertIn('qemu_binary_digest=90458b484b67436a47c480fbfaad909cda316020dfb514e3ee40726521605d38', source)
        self.assertIn('https://ports.ubuntu.com/ubuntu-ports/pool/universe/q/qemu/qemu-user_9.2.1+ds-1ubuntu5.2_arm64.deb', source)
        self.assertIn('https://archive.ubuntu.com/ubuntu/pool/main/q/qemu/qemu_9.2.1+ds-1ubuntu5.2.dsc', source)
        self.assertNotIn('apt-get', source)

    def test_corrupt_pinned_qemu_is_detected_on_retry(self):
        self.install()
        (self.target/'.zakocoder/qemu/qemu-x86_64').write_text('corrupt')
        self.install(False)
        self.assertEqual('clone\n', self.calls.read_text())

    def test_old_ready_version_is_preserved_but_not_accepted(self):
        self.target.mkdir(parents=True)
        marker = self.target/'.ready'
        marker.write_text('flutter3.47.9-arm64-v1\n')
        self.install(False)
        self.assertEqual('flutter3.47.9-arm64-v1\n', marker.read_text())
        self.assertFalse(self.calls.exists())

    def test_probe_keeps_version_as_first_visible_line(self):
        source = (INSTALLER.parents[2]/'java/dev/zakocoder/app/runtime/FlutterToolchain.kt').read_text()
        probe = next(line for line in source.splitlines() if 'const val PROBE =' in line)
        self.assertIn('sha256sum --status -c - && CI=true $HOME/bin/flutter --version', probe)
        self.assertNotIn('CI=true', source.replace(probe, ''))

    def test_unknown_directory_is_preserved(self):
        self.target.mkdir(parents=True)
        keep = self.target/'user-code.dart'
        keep.write_text('user')
        self.install(False)
        self.assertEqual('user', keep.read_text())
        self.assertFalse(self.calls.exists())

    def test_symlink_target_is_preserved(self):
        self.base.mkdir()
        self.target.symlink_to(self.root, target_is_directory=True)
        self.install(False)
        self.assertTrue(self.target.is_symlink())
        self.assertFalse(self.calls.exists())

    def test_wrong_commit_never_becomes_ready(self):
        self.env['FIXTURE_REVISION'] = '0'*40
        self.install(False)
        self.assertFalse(self.target.exists())
        self.assertFalse([path for path in self.base.glob('.flutter-install.*') if path.is_dir()])

    def test_failed_bootstrap_never_becomes_ready(self):
        self.env['FAIL_FLUTTER'] = 'yes'
        self.install(False)
        self.assertFalse(self.target.exists())
        self.assertFalse([path for path in self.base.glob('.flutter-install.*') if path.is_dir()])

    def test_small_disk_does_not_download(self):
        self.executable('df', '#!/bin/sh\necho "fixture 1000 10 990 1% /"\n')
        self.install(False)
        self.assertFalse(self.calls.exists())

    def test_wrong_architecture_does_not_download(self):
        self.executable('uname', '#!/bin/sh\necho x86_64\n')
        self.install(False)
        self.assertFalse(self.calls.exists())

    def test_concurrent_install_fails_without_download(self):
        self.base.mkdir()
        with open(self.base/'.flutter-install.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.install(False)
        self.assertFalse(self.calls.exists())

    def test_sigkill_retry_reclaims_only_owned_staging_before_disk_check(self):
        self.env['PAUSE_CLONE'] = 'yes'
        process = subprocess.Popen(['/bin/bash', '-c', self.source()], env=self.env,
                                   cwd=self.root, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            deadline = time.monotonic() + 5
            while not (self.root/'clone-started').exists():
                self.assertIsNone(process.poll(), 'fixture installer stopped early')
                self.assertLess(time.monotonic(), deadline, 'fixture clone did not start')
                time.sleep(0.01)
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        staging = self.base/'.flutter-install.staging'
        self.assertTrue((staging/'flutter/partial-download').exists())
        self.assertFalse(self.target.exists())
        self.env.pop('PAUSE_CLONE')
        # Reclamation must happen before df; free space only returns after cleanup.
        self.executable('df', '#!/bin/sh\nif test -d ' + shlex.quote(str(staging)) +
                        '; then echo "fixture 1000 10 990 1% /"; else echo "fixture 99999999 1 99999998 1% /"; fi\n')
        self.install()
        self.assertFalse(staging.exists())
        self.assertTrue((self.target/'.ready').exists())

    def test_unknown_staging_directory_is_preserved(self):
        staging = self.base/'.flutter-install.staging'
        staging.mkdir(parents=True)
        keep = staging/'user-source'
        keep.write_text('keep')
        self.install(False)
        self.assertEqual('keep', keep.read_text())
        self.assertFalse(self.calls.exists())

    def test_staging_symlink_never_removes_target(self):
        self.base.mkdir()
        protected = self.root/'outside'
        protected.mkdir()
        (protected/'.owner').write_text('zakocoder-flutter-installer-staging-v1\n')
        (protected/'keep').write_text('keep')
        staging = self.base/'.flutter-install.staging'
        staging.symlink_to(protected, target_is_directory=True)
        self.install(False)
        self.assertTrue(staging.is_symlink())
        self.assertEqual('keep', (protected/'keep').read_text())

    def test_owner_marker_symlink_is_not_ownership(self):
        staging = self.base/'.flutter-install.staging'
        staging.mkdir(parents=True)
        marker = self.root/'forged-owner'
        marker.write_text('zakocoder-flutter-installer-staging-v1\n')
        (staging/'.owner').symlink_to(marker)
        (staging/'keep').write_text('keep')
        self.install(False)
        self.assertTrue((staging/'.owner').is_symlink())
        self.assertEqual('keep', (staging/'keep').read_text())

    def test_owned_staging_cleanup_does_not_follow_nested_links(self):
        staging = self.base/'.flutter-install.staging'
        staging.mkdir(parents=True)
        (staging/'.owner').write_text('zakocoder-flutter-installer-staging-v1\n')
        outside = self.root/'outside-source'
        outside.mkdir()
        (outside/'keep').write_text('keep')
        (staging/'linked-source').symlink_to(outside, target_is_directory=True)
        legacy = self.base/'.flutter-install.unknown'
        legacy.mkdir()
        (legacy/'keep').write_text('unknown')
        self.install()
        self.assertEqual('keep', (outside/'keep').read_text())
        self.assertEqual('unknown', (legacy/'keep').read_text())

    def test_active_lock_preserves_owned_staging(self):
        staging = self.base/'.flutter-install.staging'
        staging.mkdir(parents=True)
        (staging/'.owner').write_text('zakocoder-flutter-installer-staging-v1\n')
        (staging/'partial').write_text('live')
        with open(self.base/'.flutter-install.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.install(False)
        self.assertEqual('live', (staging/'partial').read_text())

    def test_corrupt_managed_sdk_is_not_overwritten(self):
        self.install()
        (self.target/'LICENSE').unlink()
        self.install(False)
        self.assertTrue((self.target/'.ready').exists())
        self.assertEqual('clone\n', self.calls.read_text())


if __name__ == '__main__':
    unittest.main()
