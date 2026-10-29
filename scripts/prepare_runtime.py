#!/usr/bin/env python3
"""Download and verify pinned validation artifacts; never reads local reference source."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / 'runtime/artifacts.json').read_text())
output = ROOT / 'runtime-artifacts/jniLibs/arm64-v8a'
output.mkdir(parents=True, exist_ok=True)
source = manifest['nativeSource']
for artifact in manifest['nativeArtifacts']:
    target = output / artifact['name']
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == artifact['sha256']:
        print(f'Verified {target.name}')
        continue
    url = f'https://raw.githubusercontent.com/AAswordman/OperitTerminalCore/{source["commit"]}/{artifact["path"]}'
    temporary = target.with_suffix('.download')
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as dest:
        while chunk := response.read(128 * 1024):
            dest.write(chunk)
    if hashlib.sha256(temporary.read_bytes()).hexdigest() != artifact['sha256']:
        temporary.unlink()
        raise SystemExit(f'Checksum mismatch: {target.name}')
    temporary.replace(target)
    print(f'Downloaded and verified {target.name}')
