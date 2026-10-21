#!/usr/bin/env python3
"""Offline tests for release asset verification, ZIP migration and Caissa-only packaging."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parent.parent


class CaissaBuildTest(unittest.TestCase):
    def setUp(self):
        self.sandbox = tempfile.TemporaryDirectory(prefix='hod-caissa-build-')
        self.work = Path(self.sandbox.name)

    def tearDown(self):
        self.sandbox.cleanup()

    def module(self):
        spec = importlib.util.spec_from_file_location('caissa_fetch', ROOT / 'scripts/fetch-caissa-net.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.DESTINATION = self.work / module.NAME
        return module

    def release(self, module, data):
        return {'tag_name': module.TAG, 'assets': [{'name': module.NAME, 'size': len(data),
                'digest': 'sha256:' + hashlib.sha256(data).hexdigest(),
                'browser_download_url': f'https://github.com/Witek902/Caissa-Nets/releases/download/{module.TAG}/{module.NAME}'}]}

    def opener(self, release, data, calls):
        def open_url(request, timeout):
            calls.append(request.full_url)
            return io.BytesIO(json.dumps(release).encode() if 'api.github.com' in request.full_url else data)
        return open_url

    def test_verified_download_reuse_and_repair(self):
        module = self.module()
        data = b'n' * 1_000_001
        release = self.release(module, data)
        calls = []
        with patch.object(module.urllib.request, 'urlopen', side_effect=self.opener(release, data, calls)):
            digest = module.fetch()
            self.assertEqual(module.DESTINATION.read_bytes(), data)
            self.assertEqual(digest, hashlib.sha256(data).hexdigest())
            module.fetch()
            self.assertEqual(len(calls), 3)  # two metadata checks, one asset download
            module.DESTINATION.write_bytes(b'bad cached data')
            module.fetch()
            self.assertEqual(len(calls), 5)
            self.assertEqual(module.DESTINATION.read_bytes(), data)

    def test_missing_digest_wrong_release_size_and_redirect_source_rejected(self):
        module = self.module()
        data = b'n' * 1_000_001
        normal = self.release(module, data)
        changes = [lambda r: r['assets'][0].pop('digest'),
                   lambda r: r.update(tag_name='other'),
                   lambda r: r['assets'][0].update(size=1),
                   lambda r: r['assets'][0].update(browser_download_url='https://other.invalid/model.pnn'),
                   lambda r: r['assets'].append(copy.deepcopy(r['assets'][0]))]
        for change in changes:
            release = copy.deepcopy(normal)
            change(release)
            with patch.object(module.urllib.request, 'urlopen', side_effect=self.opener(release, data, [])):
                with self.assertRaises(RuntimeError):
                    module.fetch()
            self.assertFalse(module.DESTINATION.exists())

    def test_corrupt_download_does_not_replace_existing_network(self):
        module = self.module()
        original = b'keep this old file'
        module.DESTINATION.write_bytes(original)
        data = b'n' * 1_000_001
        release = self.release(module, data)
        for bad in (data[:-1], b'x' * len(data), data + b'x'):
            with patch.object(module.urllib.request, 'urlopen', side_effect=self.opener(release, bad, [])):
                with self.assertRaises(RuntimeError):
                    module.fetch()
            self.assertEqual(module.DESTINATION.read_bytes(), original)
            self.assertFalse(module.DESTINATION.with_suffix('.pnn.part').exists())

    def project_script(self, name):
        scripts = self.work / 'scripts'
        scripts.mkdir(exist_ok=True)
        shutil.copyfile(ROOT / 'scripts' / name, scripts / name)
        return scripts / name

    def test_zip_migration_removes_only_retired_components_and_is_repeatable(self):
        script = self.project_script('clean-legacy.py')
        retired = ['app/src/main/java/ru/hod/chess/TookenTransport.java',
                   'app/src/main/java/ru/hod/chess/core/LocalEngine.java',
                   'app/src/main/jniLibs/arm64-v8a/libstockfish.so',
                   'app/src/main/assets/maia/old.onnx', 'third_party/Stockfish-sf_19/src/file.cpp']
        retained = ['app/src/main/java/ru/hod/chess/core/UciEngine.java',
                    'app/src/main/jniLibs/arm64-v8a/libcaissa.so',
                    'app/src/main/assets/caissa/network.pnn', 'config/signing-certificate.sha256',
                    '.hod-signing/hod-update.p12', 'my-game.pgn', 'tests/LocalEngine.java']
        for name in retired + retained:
            path = self.work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture')
        for _ in range(2):
            subprocess.run([sys.executable, script], check=True, capture_output=True)
            self.assertTrue(all(not (self.work / name).exists() for name in retired))
            self.assertTrue(all((self.work / name).read_text() == 'fixture' for name in retained))

    def test_source_archive_contains_caissa_network_and_excludes_builds_and_private_keys(self):
        script = self.project_script('package-source.py')
        names = ['README.md', 'third_party/Caissa-2.0/src/main.cpp',
                 'third_party/Caissa-2.0/data/neuralNets/network.pnn',
                 'third_party/Caissa-2.0/.git/config', 'third_party/Caissa-2.0/bin/caissa',
                 'third_party/Caissa-2.0/object.o', 'third_party/Stockfish-sf_19/Copying.txt',
                 '.hod-signing/private.p12', 'private.p12', 'keystore-password.txt']
        for name in names:
            path = self.work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture')
        output = self.work / 'result.zip'
        subprocess.run([sys.executable, script, '--with-engine', '--output', output], check=True, capture_output=True)
        with zipfile.ZipFile(output) as archive:
            entries = archive.namelist()
        for included in names[:3]:
            self.assertIn(included, entries)
        for excluded in names[3:]:
            self.assertNotIn(excluded, entries)

    def test_apk_verifier_detects_corruption_and_retired_runtime(self):
        import struct
        spec = importlib.util.spec_from_file_location('caissa_verify_apk', ROOT / 'scripts/verify-apk.py')
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        binary = bytearray(120)
        binary[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', binary, 18, 183)
        struct.pack_into('<Q', binary, 32, 64)
        struct.pack_into('<HH', binary, 54, 56, 1)
        struct.pack_into('<I', binary, 64, 1)
        struct.pack_into('<Q', binary, 112, 16384)
        network = b'n' * 1_000_001
        report = {'version': '2.0', 'commit': 'bb725799e9b19ebdaa0d584f5433fc1c3019e349',
                  'abi': 'arm64-v8a', 'target': 'aarch64-neon',
                  'binary.sha256': hashlib.sha256(binary).hexdigest(),
                  'network': 'eval-ml-8-152B-spsa.pnn', 'network.bytes': len(network),
                  'network.sha256': hashlib.sha256(network).hexdigest()}
        base = {'lib/arm64-v8a/libcaissa.so': binary,
                'assets/caissa/provenance.json': json.dumps(report).encode(),
                'assets/caissa/engine.properties': ('\n'.join(f'{k}={v}' for k, v in report.items())).encode(),
                'assets/caissa/' + report['network']: network,
                'assets/licenses/Caissa-MIT.txt': b'license fixture ' * 3,
                'assets/licenses/Caissa-SOURCE.txt': b'attribution fixture ' * 3,
                'AndroidManifest.xml': b'Android manifest fixture',
                'classes.dex': b'org.lichess.mobileV2 Lru/hod/chess/RootCaptureMain; Lru/hod/chess/core/RootCaptureWire; Lru/hod/chess/core/PanelPlacement; Lru/hod/chess/core/UciEngine; Lru/hod/chess/LegacyUpgrade;'}
        def archive(files):
            path = self.work / 'fixture.apk'
            with zipfile.ZipFile(path, 'w') as output:
                for name, data in files.items():
                    output.writestr(name, data)
            return path
        # A structural fixture exercises the verifier; this is not an installable APK.
        verifier.verify(archive(base))
        changes = [('assets/caissa/' + report['network'], b'damaged network'),
                   ('lib/arm64-v8a/libcaissa.so', bytes(binary) + b'changed'),
                   ('lib/arm64-v8a/libstockfish.so', binary),
                   ('AndroidManifest.xml', 'android.permission.INTERNET'.encode('utf-16le')),
                   ('classes.dex', base['classes.dex'] + b'Lru/hod/chess/core/ApiEngine;')]
        for key, data in changes:
            altered = dict(base)
            altered[key] = data
            with self.assertRaises(AssertionError):
                verifier.verify(archive(altered))

    def test_project_offline_configuration_and_permanent_certificate(self):
        import xml.etree.ElementTree as ET
        root = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        permissions = [node.attrib.get('{http://schemas.android.com/apk/res/android}name') for node in root.findall('uses-permission')]
        self.assertNotIn('android.permission.INTERNET', permissions)
        self.assertEqual((ROOT / 'config/signing-certificate.sha256').read_text().strip(), '5063e2ff5159bef0bbe90158f6c5037c1959e826097338fe37db55e13e47dc14')
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertNotIn("implementation 'com.microsoft.onnxruntime", gradle)
        self.assertIn('versionCode 25', gradle)
        self.assertIn('signingConfig signingConfigs.persistent', gradle)


if __name__ == '__main__':
    unittest.main()
