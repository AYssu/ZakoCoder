#!/usr/bin/env python3
"""Produce a deterministic corresponding-source bundle without local build output."""
import argparse
import gzip
import hashlib
import io
from pathlib import Path
import tarfile
from build import verify_sources

ROOT = Path(__file__).resolve().parent


def bundle(destination):
    verify_sources()
    files = sorted([p for p in ROOT.iterdir() if p.is_file() and p.suffix in {'.py', '.json', '.txt', '.md'}])
    for directory in ('sources', 'patches', 'licenses', 'checks'):
        files += sorted(p for p in (ROOT / directory).rglob('*')
                        if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open('wb') as output, gzip.GzipFile(filename='', fileobj=output, mode='wb', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as archive:
            for path in sorted(files):
                content = path.read_bytes()
                info = tarfile.TarInfo('native-runtime/' + path.relative_to(ROOT).as_posix())
                info.size = len(content)
                info.mode = 0o644
                archive.addfile(info, io.BytesIO(content))
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    destination.with_suffix(destination.suffix + '.sha256').write_text(f'{digest}  {destination.name}\n')
    return digest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/native-runtime-sources.tar.gz')
    print(bundle(parser.parse_args().output))
