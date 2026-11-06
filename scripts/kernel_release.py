#!/usr/bin/env python3
"""Verify the signed optional plugin and prepare its public release materials."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True).strip()


def identity(badging):
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
    if not match:
        raise ValueError('Missing APK identity')
    return match[1], int(match[2])


def certificate(apk, tools):
    output = run(tools / 'apksigner', 'verify', '--print-certs', apk)
    values = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$', output, re.M)
    if len(values) != 1:
        raise ValueError('Expected exactly one verified APK signer')
    return values[0]


def modules(build_info):
    result = []
    for line in build_info.splitlines():
        fields = line.split()
        if fields and fields[0] == '=>':
            raise ValueError('Unrecorded Go module replacement')
        if fields and fields[0] in ('mod', 'dep'):
            if len(fields) != 4 or not fields[2].startswith('v') or not fields[3].startswith('h1:'):
                raise ValueError('Unpinned Go module')
            result.append({'path': fields[1], 'version': fields[2], 'sum': fields[3]})
    if not result:
        raise ValueError('Missing Go module provenance')
    return result


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate(apk, main_apk, tools, lock, abi="arm64-v8a"):
    if abi not in ("arm64-v8a", "x86_64"):
        raise ValueError("Unsupported plugin ABI")
    main_package, main_version = identity(run(tools / 'aapt', 'dump', 'badging', main_apk))
    badging = run(tools / 'aapt', 'dump', 'badging', apk)
    package, version = identity(badging)
    if main_package != 'dev.zakocoder.app' or package != 'dev.zakocoder.app.kernel':
        raise ValueError('Wrong main or plugin package')
    if version != main_version or version <= 0:
        raise ValueError('Plugin versionCode must match the published main APK')
    if 'application-debuggable' in badging:
        raise ValueError('The public plugin must be a non-debuggable Release build')
    signer = certificate(apk, tools)
    if signer != certificate(main_apk, tools):
        raise ValueError('Plugin signer differs from the published main APK')
    with zipfile.ZipFile(apk) as archive:
        libraries = [name for name in archive.namelist() if name.startswith('lib/') and not name.endswith('/')]
        expected = f'lib/{abi}/libzakocoder_xray.so'
        if libraries != [expected]:
            raise ValueError(f'Expected only the {abi} Xray kernel')
        kernel = archive.read(expected)
        if hashlib.sha256(kernel).hexdigest() != lock['targets'][abi]['sha256']:
            raise ValueError('Packaged kernel differs from the reviewed lock')
    return version, signer, kernel


def validate_distributions(arm_apk, x86_apk, main_apk, x86_main_apk, tools, lock):
    distributions = {
        'arm64-v8a': validate(arm_apk, main_apk, tools, lock, 'arm64-v8a'),
        'x86_64': validate(x86_apk, x86_main_apk or main_apk, tools, lock, 'x86_64'),
    }
    version, signer, _ = distributions['arm64-v8a']
    if any(item[:2] != (version, signer) for item in distributions.values()):
        raise ValueError('Plugin distributions must share versionCode and signing certificate')
    return distributions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('apk', 'main-apk', 'tools', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    parser.add_argument('--x86-apk', required=True, type=Path)
    parser.add_argument('--x86-main-apk', type=Path)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--main-tag', required=True)
    args = parser.parse_args()
    if not re.fullmatch('[0-9a-f]{40}', args.commit):
        raise ValueError('Expected an immutable source commit')
    lock = json.loads((ROOT / 'native-kernel/artifacts.lock.json').read_text())
    distributions = validate_distributions(args.apk, args.x86_apk, args.main_apk,
                                           args.x86_main_apk, args.tools, lock)
    version, signer, _ = distributions['arm64-v8a']
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='kernel-release-') as temporary:
        dependencies_by_path = {}
        for abi, (_, _, kernel) in distributions.items():
            binary = Path(temporary) / f'kernel-{abi}'
            binary.write_bytes(kernel)
            build_info = run('go', 'version', '-m', binary)
            if build_info.splitlines()[0].split()[-1] != lock['goVersion']:
                raise ValueError('Packaged Go toolchain differs from the lock')
            dependencies = modules(build_info)
            if not any(item['path'] == lock['sources']['module'] and item['version'] == lock['version'] for item in dependencies):
                raise ValueError('Packaged Xray source version differs from the lock')
            for item in dependencies:
                previous = dependencies_by_path.setdefault(item['path'], item)
                if previous != item:
                    raise ValueError('Plugin ABI dependency versions or source checksums differ')
        dependencies = [dependencies_by_path[name] for name in sorted(dependencies_by_path)]
        # Ship the exact sources of every linked Go module, including its upstream notices.
        with tarfile.open(args.output / 'kernel-corresponding-sources.tar.gz', 'w:gz') as bundle:
            for index, item in enumerate(dependencies):
                downloaded = json.loads(run('go', 'mod', 'download', '-json', f"{item['path']}@{item['version']}"))
                if downloaded.get('Sum') != item['sum']:
                    raise ValueError('Go source checksum differs from the linked binary')
                source = Path(downloaded['Zip'])
                item['archive'] = f'modules/{index:03d}-{source.name}'
                item['archiveSha256'] = sha256(source)
                bundle.add(source, arcname=item['archive'])
            manifest = Path(temporary) / 'dependencies.json'
            manifest.write_text(json.dumps(dependencies, indent=2) + '\n')
            bundle.add(manifest, arcname=manifest.name)
            bundle.add(Path(run('go', 'env', 'GOROOT')) / 'LICENSE', arcname='Go-LICENSE')
            for relative in ('native-kernel/artifacts.lock.json', 'native-kernel/go.version',
                             'scripts/prepare_proxy_kernel.py', 'kernel-plugin', 'kernel-shared', 'LICENSE', 'THIRD_PARTY.md'):
                bundle.add(ROOT / relative, arcname=relative, filter=lambda info: None if '/build/' in info.name or info.name.endswith('/build') else info)
    (args.output / 'BUILD.json').write_text(json.dumps({
        'versionCode': version, 'mainRelease': args.main_tag, 'commit': args.commit,
        'package': 'dev.zakocoder.app.kernel', 'abis': ['arm64-v8a', 'x86_64'], 'contractVersion': 1,
        'signingCertificateSha256': signer, 'kernel': lock,
    }, indent=2) + '\n')
    (args.output / 'RELEASE.md').write_text(
        '# ZakoCoder 代理内核插件\n\n'
        '仅受支持的 VMess／VLESS／SS／Trojan 节点需要，直连和手动 HTTP／SOCKS5 无需安装。\n\n'
        '按设备架构下载 `kernel-plugin-arm64-v8a.apk` 或 `kernel-plugin-x86_64.apk`，在 Lite 应用设置中选择本地安装；'
        '也可直接使用设置页的在线安装。系统安装器需要用户确认。'
        '部分手机系统可能需要允许主应用关联启动插件；服务不可用时可显式打开插件并导出诊断。\n\n'
        f'ARM64 / x86_64 · Android 8+ · 接口 v1 · versionCode {version} · 与 {args.main_tag} 主程序同证书。\n\n'
        f'[完整构建源码](https://github.com/AYssu/ZakoCoder/archive/{args.commit}.tar.gz)。'
        f'源码提交：`{args.commit}`。Xray {lock.get("upstreamTag", lock["version"])}（MPL-2.0），未修改上游源码。'
        f' Go 模块锁定 `{lock["version"]}`。'
        '对应源码包含链接的 Go 模块完整源码、原始许可证、依赖清单及构建锁。\n\n'
        '本发布页是接口 v1 的当前下载通道，后续同接口版本更新资产；'
        '不替代主程序 Latest 发布页。校验值见 `SHA256SUMS.txt`。\n'
    )
    files = sorted(path for path in args.output.iterdir() if path.is_file() and path.name != 'SHA256SUMS.txt')
    (args.output / 'SHA256SUMS.txt').write_text(''.join(f'{sha256(path)}  {path.name}\n' for path in files))


if __name__ == '__main__':
    main()
