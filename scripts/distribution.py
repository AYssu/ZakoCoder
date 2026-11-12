#!/usr/bin/env python3
"""One inventory for the four independently installable Android distributions."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess

EDITIONS = ("lite", "full")
ABIS = ("arm64-v8a", "x86_64")
VARIANTS = tuple((edition, abi) for edition in EDITIONS for abi in ABIS)


def apk_name(version, edition, abi):
    if (edition, abi) not in VARIANTS:
        raise ValueError("Unknown distribution")
    return f"ZakoCoder-{version}-{edition}-{abi}.apk"


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect(apk, edition, abi, aapt, signer, release=True):
    """Inspect the actual archive, manifest, pinned runtime and signing certificate."""
    from check_release_apk import inspect_apk, _entries
    from sign_beta_apk import certificate_digest
    apk = Path(apk)
    if release:
        report = inspect_apk(apk, Path(aapt))
    else:
        entries = _entries(apk)
        report = {"status": "passed", "sha256": digest(apk), "nativeAbis": sorted({n.split('/')[1] for n in entries if n.startswith('lib/') and n.endswith('.so')})}
    if report['nativeAbis'] != [abi]:
        raise ValueError("Distribution must contain exactly its named native ABI")
    entries = _entries(apk)
    if any(name.lower().endswith('.apk') for name in entries):
        raise ValueError("Distribution must not embed a separate plugin APK")
    marker = json.loads(entries.get('assets/distribution.json', b'{}'))
    if (marker.get('edition') != edition or marker.get('abis') != [abi] or
            marker.get('bundledRootfs') is not (edition == 'full') or marker.get('bundledKernel') is not (edition == 'full')):
        raise ValueError('Packaged distribution marker differs from the artifact name')
    root = Path(__file__).resolve().parents[1]
    runtime = json.loads((root / 'runtime/artifacts.json').read_text())
    target = runtime if abi == 'arm64-v8a' else runtime['architectures'][abi]
    for native in target['nativeArtifacts']:
        name = f"lib/{abi}/{native['name']}"
        if name not in entries or hashlib.sha256(entries[name]).hexdigest() != native['sha256']:
            raise ValueError('Packaged runtime differs from the pinned source build')
    rootfs_names = {n for n in entries if n.startswith('assets/ubuntu-rootfs/')}
    kernel_names = {n for n in entries if n.endswith('/libzakocoder_xray.so')}
    if edition == 'full':
        rootfs_name = f'assets/ubuntu-rootfs/{abi}.tar.xz'
        kernel_name = f'lib/{abi}/libzakocoder_xray.so'
        if rootfs_names != {rootfs_name} or kernel_names != {kernel_name}:
            raise ValueError('Full must bundle exactly its own Ubuntu and kernel ABI')
        rootfs = target['rootfs']
        if len(entries[rootfs_name]) != rootfs['bytes'] or hashlib.sha256(entries[rootfs_name]).hexdigest() != rootfs['sha256']:
            raise ValueError('Bundled Ubuntu does not match its pinned rootfs')
        kernel = json.loads((root / 'native-kernel/artifacts.lock.json').read_text())
        if hashlib.sha256(entries[kernel_name]).hexdigest() != kernel['targets'][abi]['sha256']:
            raise ValueError('Bundled kernel does not match its source build lock')
    elif rootfs_names or kernel_names:
        raise ValueError('Lite must not include bundled Ubuntu or kernel')
    if not release:
        badging = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], check=True,
                                 capture_output=True, text=True, timeout=120).stdout
        identity = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
        if identity is None or identity[1] != 'dev.zakocoder.app':
            raise ValueError('Debug distribution needs the main application identity')
        report.update(package=identity[1], versionName=identity[3], versionCode=int(identity[2]))
    report.update(edition=edition, abi=abi, certificateSha256=certificate_digest(Path(signer), apk))
    return report


def write_checksums(directory, version):
    directory = Path(directory)
    names = [apk_name(version, *variant) for variant in VARIANTS]
    if {p.name for p in directory.glob('*.apk')} != set(names):
        raise ValueError('Exactly four named main APKs are required')
    if any((directory / name).is_symlink() or not (directory / name).is_file() for name in names):
        raise ValueError('APKs must be regular files')
    (directory / 'SHA256SUMS.txt').write_text(''.join(f'{digest(directory / name)}  {name}\n' for name in names))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--apksigner', required=True)
    parser.add_argument('--debug', action='store_true')
    parser.add_argument('--expected-version-code', type=int)
    args = parser.parse_args()
    reports = []
    for edition, abi in VARIANTS:
        reports.append(inspect(args.directory / apk_name(args.version, edition, abi), edition, abi,
                               args.aapt, args.apksigner, not args.debug))
    if len({r['certificateSha256'] for r in reports}) != 1:
        raise ValueError('All four distributions must share a signing certificate')
    if len({(r['package'], r['versionName'], r['versionCode']) for r in reports}) != 1:
        raise ValueError('All four distributions must share package and version')
    if any(r['versionName'] != args.version for r in reports):
        raise ValueError('APK versionName must match the requested build version')
    if args.expected_version_code is not None and any(r['versionCode'] != args.expected_version_code for r in reports):
        raise ValueError('APK versionCode must match the resolved build versionCode')
    write_checksums(args.directory, args.version)
    (args.directory / 'distribution-validation.json').write_text(json.dumps(reports, indent=2) + '\n')


if __name__ == '__main__':
    main()
