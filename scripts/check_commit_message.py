#!/usr/bin/env python3
"""Validate a Conventional Commit subject for commits and squash PR titles."""
import argparse
from pathlib import Path
import re
import sys

PATTERN = re.compile(r'(feat|fix|docs|refactor|test|build|ci|chore|perf|revert)(\([a-z][a-z0-9-]*\))?!?: \S.+')


def validate(subject: str) -> bool:
    return len(subject) <= 100 and PATTERN.fullmatch(subject) is not None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--file', type=Path, help='Git commit message file')
    inputs.add_argument('--title', help='Pull request title')
    args = parser.parse_args()
    if args.file is not None:
        lines = args.file.read_text(encoding='utf-8').splitlines()
        subject = lines[0] if lines else ''
    else:
        subject = args.title
    if not validate(subject):
        print('Use type(scope): summary (maximum 100 characters); see CONTRIBUTING.md.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
