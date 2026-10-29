#!/usr/bin/env python3
"""Check public repository structure without reading local references or user data."""
from pathlib import Path, PurePosixPath
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    'README.md', 'LICENSE', 'CONTRIBUTING.md', 'CODE_OF_CONDUCT.md',
    'SECURITY.md', 'THIRD_PARTY.md', '.gitignore', '.editorconfig',
    'gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar',
    'docs/PRODUCT.md', 'docs/ARCHITECTURE.md', 'docs/RUNTIME.md',
    'docs/DESIGN.md', 'docs/DEVELOPMENT.md', 'docs/RELEASING.md',
    'docs/AI_WORKFLOW.md', 'docs/EDITOR.md', 'docs/ROADMAP.md',
    '.github/workflows/ci.yml', '.github/pull_request_template.md',
)
errors = [f'Missing required file: {name}' for name in REQUIRED if not (ROOT / name).is_file()]

for path in (ROOT / 'app/src/main').rglob('*.xml'):
    try:
        ET.parse(path)
    except ET.ParseError as error:
        errors.append(f'Invalid XML: {path.relative_to(ROOT)}: {error}')

# Check only our public documentation; never recurse into local reference projects.
documents = [path for path in ROOT.glob('*.md') if path.name not in {'AGENTS.md', 'AGENT.md', 'CLAUDE.md'}]
documents += list((ROOT / 'docs').rglob('*.md'))
for path in documents:
    for target in re.findall(r'\[[^\]]*\]\(([^\s)]+)\)', path.read_text(encoding='utf-8')):
        if '://' in target or target.startswith(('#', 'mailto:')):
            continue
        local = target.split('#', 1)[0]
        if local and not (path.parent / local).exists():
            errors.append(f'Broken local link: {path.relative_to(ROOT)} -> {target}')

manifest = ROOT / 'runtime/artifacts.json'
packaged = ROOT / 'app/src/main/assets/runtime-artifacts.json'
if json.loads(manifest.read_text()) != json.loads(packaged.read_text()):
    errors.append('Runtime manifest and packaged metadata differ')

ignore_lines = (ROOT / '.gitignore').read_text().splitlines()
for rule in ('/example/', '/参考源码/', 'local.properties', '*.keystore', '*.jks', '/.local/', '/outputs/', '/AGENTS.md'):
    if rule not in ignore_lines:
        errors.append(f'Missing ignore rule: {rule}')

probe = subprocess.run(
    ['git', '-C', str(ROOT), 'rev-parse', '--show-toplevel'],
    text=True, capture_output=True, check=False,
)
if probe.returncode == 0 and Path(probe.stdout.strip()).resolve() == ROOT:
    tracked = subprocess.run(
        ['git', '-C', str(ROOT), 'ls-files', '-z'],
        capture_output=True, check=True,
    ).stdout.decode('utf-8').split('\0')
    for name in filter(None, tracked):
        path = PurePosixPath(name)
        forbidden = (
            path.parts[0] in {'example', '参考源码', 'runtime-artifacts', '.aws', '.codex', '.agents', '.local', 'outputs', '.cursor', '.continue', '.gemini'}
            or (len(path.parts) == 1 and path.name in {'AGENTS.md', 'AGENT.md', 'CLAUDE.md', '.cursorrules', '.mcp.json'})
            or any(part in {'build', '.gradle', '.kotlin', '.idea', '__pycache__', '.venv', '.cxx', '.externalNativeBuild'} for part in path.parts)
            or path.parts[:2] in {('docs', 'design'), ('docs', 'validation'), ('docs', 'decisions')}
            or name == 'docs/VALIDATION.md'
            or path.name == 'local.properties'
            or (path.name.startswith('.env') and path.name != '.env.example')
            or path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pem', '.key', '.apk', '.aab', '.pyc', '.pyo', '.log', '.hprof'}
        )
        if forbidden:
            errors.append(f'Forbidden tracked file: {name}')
else:
    print('NOTE: no Git repository; tracked-file check was not run.')

if errors:
    for error in errors:
        print(f'ERROR: {error}', file=sys.stderr)
    raise SystemExit(1)
print('Repository structure, XML, local documentation links and ignore rules: OK')
