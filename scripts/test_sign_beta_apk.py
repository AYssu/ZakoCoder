import tempfile
import unittest
from pathlib import Path
from sign_beta_apk import signing_configuration


class SigningSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / 'home'
        self.debug = self.home / '.android/debug.keystore'
        self.debug.parent.mkdir(parents=True)
        self.debug.touch()

    def config(self, env=None):
        return signing_configuration(env or {}, self.root, self.home)

    def test_default_uses_existing_debug(self):
        result = self.config()
        self.assertEqual(self.debug, result[0])
        self.assertEqual('debug-reused-for-beta', result[-1])

    def test_present_release_never_silently_falls_back(self):
        custom = self.root / '.local/signing/release.jks'
        custom.parent.mkdir(parents=True)
        custom.touch()
        with self.assertRaises(ValueError):
            self.config()
        env = {'ZAKOCODER_RELEASE_' + key: value for key, value in
               [('STORE_PASSWORD', 'fixture-store'), ('KEY_ALIAS', 'fixture'), ('KEY_PASSWORD', 'fixture-key')]}
        self.assertEqual(custom, self.config(env)[0])
        self.assertEqual('release', self.config(env)[-1])

    def test_explicit_missing_key_is_error(self):
        with self.assertRaises(ValueError):
            self.config({'ZAKOCODER_RELEASE_KEYSTORE': 'missing.jks'})

    def test_no_key_is_error_not_key_generation(self):
        self.debug.unlink()
        with self.assertRaises(ValueError):
            self.config()


if __name__ == '__main__':
    unittest.main()
