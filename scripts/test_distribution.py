"""Regression gates for four-package inventory and Release provenance."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import beta_release as beta
import distribution as dist


class DistributionTest(unittest.TestCase):
    def fixture(self, directory):
        version = '1.2.3-beta.1'
        for variant in dist.VARIANTS:
            (directory / dist.apk_name(version, *variant)).write_bytes(('-'.join(variant)).encode())
        for name in ('native-runtime-sources.tar.gz', 'licenses.tar.gz', 'runtime-validation.tar.gz', 'LICENSE.txt', 'THIRD_PARTY.md'):
            (directory / name).write_bytes(b'corresponding source fixture')
        reports = [{'status': 'passed', 'edition': edition, 'abi': abi, 'nativeAbis': [abi],
                    'package': beta.PACKAGE, 'versionName': version, 'versionCode': 1009,
                    'sha256': dist.digest(directory / dist.apk_name(version, edition, abi)),
                    'certificateSha256': 'b' * 64} for edition, abi in dist.VARIANTS]
        (directory / 'distribution-validation.json').write_text(json.dumps(reports))
        metadata = beta.create_metadata(directory, version, 1009, 'a' * 40, 'b' * 64,
                                       'https://github.com/AYssu/ZakoCoder/actions/runs/123')
        return metadata, reports

    def test_cli_rejects_old_apk_identity_even_when_all_four_agree(self):
        import sys
        reports = [{'package': beta.PACKAGE, 'versionName': '0.1.0-dev',
                    'versionCode': 1, 'certificateSha256': 'b' * 64}] * 4
        args = ['distribution.py', '--directory', 'fixture', '--version', '0.1.2111216-dev',
                '--expected-version-code', '216000000', '--debug', '--aapt', 'aapt', '--apksigner', 'signer']
        with patch.object(sys, 'argv', args), patch.object(dist, 'inspect', side_effect=reports):
            with self.assertRaisesRegex(ValueError, 'versionName'):
                dist.main()
        for report in reports:
            report['versionName'] = '0.1.2111216-dev'
        with patch.object(sys, 'argv', args), patch.object(dist, 'inspect', side_effect=reports):
            with self.assertRaisesRegex(ValueError, 'versionCode'):
                dist.main()

    def test_four_package_checksums_cover_only_main_apks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, _ = self.fixture(root)
            dist.write_checksums(root, metadata['version'])
            lines = (root / 'SHA256SUMS.txt').read_text().splitlines()
            self.assertEqual(4, len(lines))
            self.assertTrue(all(line.endswith('.apk') for line in lines))
            (root / 'plugin.apk').write_bytes(b'plugin')
            with self.assertRaises(ValueError):
                dist.write_checksums(root, metadata['version'])

    def test_matrix_metadata_requires_each_exact_abi_edition_identity_and_signature(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, reports = self.fixture(root)
            self.assertEqual(4, len(metadata['distributions']))
            for field, value in [('certificateSha256', 'c' * 64), ('nativeAbis', ['arm64-v8a', 'x86_64']),
                                 ('versionCode', 1008), ('edition', 'other')]:
                broken = json.loads(json.dumps(reports))
                broken[0][field] = value
                with self.subTest(field=field), self.assertRaises(ValueError):
                    beta.validate_distribution_inspections(root, metadata, broken)
            (root / metadata['distributions'][0]['apk']).write_bytes(b'changed after inspection')
            with self.assertRaises(ValueError):
                beta.validate_distribution_inspections(root, metadata, reports)

    def test_both_editions_require_smoke_of_exact_published_apks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata, _ = self.fixture(root)
            reports = {'status': 'passed', 'editions': {edition: {
                'status': 'passed', 'certificateSha256': 'b' * 64, 'comparisonCodeIdentity': {'status': 'matched'},
                'apkSha256': dist.digest(root / dist.apk_name(metadata['version'], edition, 'x86_64')),
                'comparisonApkSha256': dist.digest(root / dist.apk_name(metadata['version'], edition, 'arm64-v8a')),
            } for edition in dist.EDITIONS}}
            beta.validate_matrix_smoke(root, metadata, reports)
            reports['editions']['full']['comparisonApkSha256'] = reports['editions']['lite']['comparisonApkSha256']
            with self.assertRaises(ValueError):
                beta.validate_matrix_smoke(root, metadata, reports)
            del reports['editions']['full']
            with self.assertRaises(ValueError):
                beta.validate_matrix_smoke(root, metadata, reports)

    def test_debug_inventory_rejects_embedded_plugin_and_wrong_marker(self):
        from check_release_apk import _entries
        from sign_beta_apk import certificate_digest
        with patch('check_release_apk._entries', return_value={
            'lib/arm64-v8a/libzakocoder_proot.so': b'fixture',
            'assets/distribution.json': json.dumps({'edition': 'lite', 'abis': ['arm64-v8a'],
                'bundledRootfs': False, 'bundledKernel': False}).encode(),
            'assets/plugin.apk': b'plugin',
        }), patch('distribution.digest', return_value='a' * 64):
            with self.assertRaisesRegex(ValueError, 'separate plugin'):
                dist.inspect('fake.apk', 'lite', 'arm64-v8a', 'aapt', 'apksigner', False)

    def test_matrix_finalize_and_publish_only_four_apks_and_checksum(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = root / 'candidate'
            candidate.mkdir()
            metadata, _ = self.fixture(candidate)
            evidence = root / 'evidence'
            evidence.mkdir()
            report = evidence / 'report.json'
            smoke = {'status': 'passed', 'editions': {edition: {
                'status': 'passed', 'certificateSha256': 'b' * 64, 'comparisonCodeIdentity': {'status': 'matched'},
                'apkSha256': dist.digest(candidate / dist.apk_name(metadata['version'], edition, 'x86_64')),
                'comparisonApkSha256': dist.digest(candidate / dist.apk_name(metadata['version'], edition, 'arm64-v8a')),
            } for edition in dist.EDITIONS}}
            report.write_text(json.dumps(smoke))
            qa = candidate / dist.apk_name(metadata['version'], 'lite', 'x86_64')
            beta.finalize(candidate, qa, report)
            uploads = {}
            tag = 'v' + metadata['version']
            def request_json(*args, **kwargs):
                if 'POST' in args:
                    return {'id': 7, 'draft': True, 'tag_name': tag}
                if 'PATCH' in args:
                    return {'draft': False, 'prerelease': True, 'target_commitish': metadata['commit'], 'html_url': 'fixture-release'}
                return {'draft': True, 'tag_name': tag, 'assets': [
                    {'name': name, 'id': index, 'size': len(data)} for index, (name, data) in enumerate(sorted(uploads.items()), 1)]}
            def request(*args, **kwargs):
                if 'POST' in args:
                    source = Path(args[-1])
                    uploads[source.name] = source.read_bytes()
                    return b'{}'
                return sorted(uploads.items())[int(args[1].rsplit('/', 1)[1]) - 1][1]
            with patch.object(beta, 'preflight'), patch.object(beta, 'published_builds', return_value=[]), \
                    patch.object(beta, 'gh_json', side_effect=request_json), patch.object(beta, 'gh', side_effect=request):
                beta.publish('AYssu/ZakoCoder', candidate, qa, report)
            self.assertEqual({dist.apk_name(metadata['version'], *v) for v in dist.VARIANTS} | {'SHA256SUMS.txt'}, set(uploads))
            self.assertEqual(4, len(uploads['SHA256SUMS.txt'].splitlines()))
