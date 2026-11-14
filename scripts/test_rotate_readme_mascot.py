"""Exercise rotation against a disposable real Git index, never the developer's index."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('rotate_readme_mascot.py').resolve()


class ReadmeMascotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.readme = self.root / 'README.md'
        self.readme.write_text('<img align="right" src="docs/assets/brand/mascot.webp" alt="mascot" />\n')
        assets = self.root / 'docs/assets/brand/mascots'
        assets.mkdir(parents=True)
        for name in ('01-first', '02-second', '03-third'):
            (assets / f'{name}.webp').write_bytes(b'test-only')
        self.git('add', '.')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root)

    def rotate(self, **env):
        subprocess.run(['python3', str(SCRIPT)], cwd=self.root, check=True,
                       env={**os.environ, 'SKIP_README_MASCOT': '0', **env}, capture_output=True)

    def test_rotation_updates_index_and_worktree_and_wraps(self):
        for expected in ('02-second', '03-third', '01-first'):
            self.rotate()
            self.assertIn(expected, self.readme.read_text())
            self.assertEqual(self.readme.read_bytes(), self.git('show', ':README.md'))

    def test_unstaged_readme_and_other_files_are_preserved(self):
        staged = self.git('show', ':README.md')
        self.readme.write_text('private unstaged changes')
        unrelated = self.root / 'private.txt'
        unrelated.write_text('not staged')
        self.rotate()
        self.assertEqual(staged, self.git('show', ':README.md'))
        self.assertEqual('private unstaged changes', self.readme.read_text())
        self.assertNotIn('private.txt', self.git('ls-files').decode())

    def test_explicit_skip_preserves_index(self):
        before = self.git('write-tree')
        self.rotate(SKIP_README_MASCOT='1')
        self.assertEqual(before, self.git('write-tree'))

    def test_merge_skips_rotation(self):
        before = self.git('write-tree')
        (self.root / '.git/MERGE_HEAD').write_text('0' * 40)
        self.rotate()
        self.assertEqual(before, self.git('write-tree'))
