
import io
import json
import unittest
import urllib.error
import zipfile
from unittest.mock import patch

import publish_modrinth as publish

def version(identifier, games=None):
    return {
        'id': identifier, 'version_number': '1.0.2',
        'date_published': identifier, 'game_versions': games or [],
        'files': [{'primary': True, 'filename': 'api.jar',
                   'url': 'https://example.com/api.jar'}],
    }

def jar(predicate):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as archive:
        archive.writestr('fabric.mod.json', json.dumps({'depends': {'minecraft': predicate}}))
    return io.BytesIO(data.getvalue())

class DependencyTest(unittest.TestCase):
    def resolve(self, candidates, games):
        with patch.object(publish, 'call', side_effect=[{'id': 'project'}, candidates]):
            return publish.dependency('hypixel-mod-api', '>=1.0.2', games)

    def test_tagged_version_needs_no_download(self):
        with patch.object(publish.urllib.request, 'urlopen') as download:
            entry, _ = self.resolve([version('tagged', ['26.3'])], ['26.3'])
        self.assertEqual('tagged', entry['version_id'])
        download.assert_not_called()

    def test_untagged_open_range_pins_hypixel_for_26_3(self):
        with patch.object(publish.urllib.request, 'urlopen', return_value=jar('>=26.1')):
            entry, label = self.resolve([version('compatible', ['26.1'])], ['26.3'])
        self.assertEqual({'project_id': 'project', 'version_id': 'compatible',
                          'dependency_type': 'required'}, entry)
        self.assertEqual('1.0.2', label)

    def test_fallback_requires_every_game_to_match(self):
        with patch.object(publish.urllib.request, 'urlopen', return_value=jar('>=26.3 <26.4')):
            self.assertIsNone(publish.declared_compatible([version('candidate')], ['26.2', '26.3']))

    def test_array_ranges_are_alternatives(self):
        with patch.object(publish.urllib.request, 'urlopen', return_value=jar(['~26.1', '>=26.3 <26.4'])):
            self.assertEqual('candidate', publish.declared_compatible(
                [version('candidate')], ['26.1.2', '26.3'])['id'])

    def test_missing_or_unrestricted_declaration_does_not_pin(self):
        for predicate in (None, '*', ''):
            with self.subTest(predicate=predicate), patch.object(
                    publish.urllib.request, 'urlopen', return_value=jar(predicate)):
                entry, _ = self.resolve([version('candidate')], ['26.3'])
                self.assertNotIn('version_id', entry)

    def test_unreadable_candidates_do_not_prevent_later_match(self):
        with patch.object(publish.urllib.request, 'urlopen', side_effect=[
                urllib.error.URLError('offline'), io.BytesIO(b'not a jar'), jar('>=26.1')]):
            chosen = publish.declared_compatible(
                [version('offline'), version('broken'), version('good')], ['26.3'])
        self.assertEqual('good', chosen['id'])

    def test_incompatible_version_is_not_pinned(self):
        with patch.object(publish.urllib.request, 'urlopen', return_value=jar('>=26.1 <26.3')):
            entry, _ = self.resolve([version('candidate')], ['26.3'])
        self.assertNotIn('version_id', entry)

    def test_mod_version_requirement_filters_candidates_before_download(self):
        candidate = version('old')
        candidate['version_number'] = '1.0.1'
        with patch.object(publish.urllib.request, 'urlopen') as download:
            entry, _ = self.resolve([candidate], ['26.3'])
        self.assertNotIn('version_id', entry)
        download.assert_not_called()

if __name__ == '__main__':
    unittest.main()
