"""Check the distributable skill catalog and its published integrity record."""
import hashlib
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class BuiltinSkillsTest(unittest.TestCase):
    def test_published_inventory_matches_distributable_assets(self):
        manifest = json.loads((ROOT / 'docs/builtin-skills-provenance.json').read_text())
        entries = manifest['bundled']
        paths = [item['path'] for item in entries]
        actual = {str(path.relative_to(ROOT)) for path in (ROOT / 'app/src/main/assets/skills').rglob('SKILL.md')}
        self.assertEqual(len(paths), len(set(paths)))
        self.assertEqual(actual, set(paths))
        for item in entries:
            with self.subTest(skill=item['id']):
                path = ROOT / item['path']
                self.assertEqual(item['id'], path.parent.name)
                self.assertEqual(item['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                self.assertLessEqual(len(path.read_text()), 32000)

    def test_catalog_metadata_resolves_for_every_packaged_skill(self):
        source = (ROOT / 'app/src/main/java/dev/zakocoder/app/ai/AiBuiltinSkills.kt').read_text()
        entries = re.findall(r'Entry\("([a-z0-9-]+)", R.string.([a-z0-9_]+)', source)
        resources = {}
        for path in (ROOT / 'app/src/main/res/values').glob('*.xml'):
            for element in ET.parse(path).getroot().findall('string'):
                resources[element.attrib['name']] = ''.join(element.itertext())
        folders = {p.name for p in (ROOT / 'app/src/main/assets/skills').iterdir() if p.is_dir()}
        self.assertEqual(folders, {key for key, _ in entries})
        self.assertEqual(len(entries), len(folders))
        for key, resource in entries:
            with self.subTest(skill=key):
                self.assertTrue(resources[resource].strip())
                document = (ROOT / f'app/src/main/assets/skills/{key}/SKILL.md').read_text()
                self.assertRegex(document, rf'\A---\r?\nname: {re.escape(key)}\r?\n')


if __name__ == '__main__':
    unittest.main()
