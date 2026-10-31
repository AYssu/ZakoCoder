#!/usr/bin/env python3
"""Align/sign a Beta APK, selecting an ignored local key or the existing Debug key.

Passwords travel through environment variables, never command arguments or output.
A present custom key with incomplete configuration is an error, not a fallback.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'ZAKOCODER_RELEASE_'


def signing_configuration(environ, root=ROOT, home=None):
    explicit = environ.get(PREFIX + 'KEYSTORE')
    custom = Path(explicit).expanduser() if explicit else root / '.local/signing/release.jks'
    if not custom.is_absolute():
        custom = root / custom
    if explicit or custom.exists():
        if not custom.is_file():
            raise ValueError('Configured release keystore does not exist')
        names = ['STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD']
        if not all(environ.get(PREFIX + name) for name in names):
            raise ValueError('Release key requires ZAKOCODER_RELEASE_STORE_PASSWORD, ZAKOCODER_RELEASE_KEY_ALIAS and ZAKOCODER_RELEASE_KEY_PASSWORD')
        return custom, environ[PREFIX + 'KEY_ALIAS'], environ[PREFIX + 'STORE_PASSWORD'], environ[PREFIX + 'KEY_PASSWORD'], 'release'
    debug = Path(home or Path.home()) / '.android/debug.keystore'
    if not debug.is_file():
        raise ValueError('No custom or existing Debug keystore; build Debug first or configure a release key')
    return debug, 'androiddebugkey', 'android', 'android', 'debug-reused-for-beta'


def certificate_digest(apksigner, apk):
    result = subprocess.run([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], check=True, capture_output=True, text=True)
    matches = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', result.stdout)
    if len(matches) != 1:
        raise ValueError('Expected exactly one verified APK signer')
    return matches[0].lower()


def sign(apk, output, apksigner, zipalign, environ=None):
    environ = dict(os.environ if environ is None else environ)
    if not apk.is_file() or output.exists() or apk.resolve() == output.resolve():
        raise ValueError('APK input must exist and signed output must be a new path')
    key, alias, store_password, key_password, kind = signing_configuration(environ)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='zako-beta-sign-') as directory:
        aligned = Path(directory) / 'aligned.apk'
        subprocess.run([str(zipalign), '-f', '-P', '16', '4', str(apk), str(aligned)], check=True, capture_output=True)
        child_env = dict(environ, ZAKOCODER_SIGN_STORE_PASSWORD=store_password, ZAKOCODER_SIGN_KEY_PASSWORD=key_password)
        try:
            subprocess.run([str(apksigner), 'sign', '--ks', str(key), '--ks-key-alias', alias,
                            '--ks-pass', 'env:ZAKOCODER_SIGN_STORE_PASSWORD', '--key-pass', 'env:ZAKOCODER_SIGN_KEY_PASSWORD',
                            '--out', str(output), str(aligned)], env=child_env, check=True, capture_output=True)
            digest = certificate_digest(apksigner, output)
            subprocess.run([str(zipalign), '-c', '-P', '16', '4', str(output)], check=True, capture_output=True)
        except Exception:
            output.unlink(missing_ok=True)
            Path(str(output) + '.idsig').unlink(missing_ok=True)
            raise
    return {'signingKeyKind': kind, 'certificateSha256': digest}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk', 'output', 'apksigner', 'zipalign'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(sign(args.apk, args.output, args.apksigner, args.zipalign)))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Tool stderr can include keystore details. Return only a safe failure category.
        parser.exit(1, f'Beta signing failed: {error if isinstance(error, ValueError) else type(error).__name__}\n')


if __name__ == '__main__':
    main()
