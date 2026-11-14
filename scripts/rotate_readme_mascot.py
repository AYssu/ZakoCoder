#!/usr/bin/env python3
"""Rotate the README illustration in ordinary local commits, using only staged assets."""
import os
from pathlib import Path
import re
import subprocess
import sys

PATTERN = re.compile(rb'(<img\b[^>]*\balign="right"[^>]*\bsrc=")[^"]+("[^>]*>)')


def git(*args, data=None):
    return subprocess.check_output(['git', *args], input=data)


def rotate():
    if os.environ.get('SKIP_README_MASCOT') == '1':
        return
    os.chdir(os.fsdecode(git('rev-parse', '--show-toplevel')).strip())
    # Merge/conflict recovery commits must not acquire unrelated documentation changes.
    for marker in ('MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'rebase-merge', 'rebase-apply'):
        if Path(os.fsdecode(git('rev-parse', '--git-path', marker)).strip()).exists():
            return
    if not git('diff', '--cached', '--name-only').strip():
        return
    staged = git('show', ':README.md')
    readme = Path('README.md')
    if readme.read_bytes() != staged:
        print('README mascot unchanged: README has unstaged changes.', file=sys.stderr)
        return
    match = PATTERN.search(staged)
    if not match:
        return
    entries = git('ls-files', '--stage', '-z', '--', 'docs/assets/brand/mascots/*.webp').split(b'\0')
    variants = sorted(os.fsdecode(entry.split(b'\t', 1)[1]) for entry in entries
                      if entry and entry.split(b'\t', 1)[0].endswith(b' 0'))
    if len(variants) < 2:
        return
    current = match.group(0).split(b'src="', 1)[1].split(b'"', 1)[0].decode()
    # The original fixed illustration is the first pose.
    index = variants.index(current) if current in variants else 0
    selected = variants[(index + 1) % len(variants)]
    updated = staged[:match.start()] + match.group(1) + selected.encode() + match.group(2) + staged[match.end():]
    mode = git('ls-files', '--stage', '--', 'README.md').split()[0].decode()
    blob = git('hash-object', '-w', '--stdin', data=updated).decode().strip()
    git('update-index', '--cacheinfo', mode, blob, 'README.md')
    readme.write_bytes(updated)
    print(f'README mascot: {selected}')


if __name__ == '__main__':
    rotate()
