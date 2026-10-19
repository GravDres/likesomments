#!/usr/bin/env python3
"""Real temporary keystore checks; APK/Termux command tests use local fixtures."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent


class SigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sandbox = tempfile.TemporaryDirectory(prefix='hod-signing-tests-')
        cls.work = Path(cls.sandbox.name)
        cls.password = secrets.token_urlsafe(24)
        cls.environment = dict(os.environ, HOD_KEYSTORE_PASSWORD=cls.password, GITHUB_ENV=str(cls.work / 'github-env'))
        cls.key = cls.work / 'test.p12'
        subprocess.run(['keytool', '-genkeypair', '-alias', 'hod', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Temporary Test', '-storetype', 'PKCS12', '-keystore', str(cls.key), '-storepass:env', 'HOD_KEYSTORE_PASSWORD', '-keypass:env', 'HOD_KEYSTORE_PASSWORD', '-noprompt'], env=cls.environment, capture_output=True, check=True)
        cert = subprocess.run(['keytool', '-exportcert', '-alias', 'hod', '-keystore', str(cls.key), '-storepass:env', 'HOD_KEYSTORE_PASSWORD'], env=cls.environment, capture_output=True, check=True).stdout
        cls.fingerprint = hashlib.sha256(cert).hexdigest()
        cls.pin = cls.work / 'pin.sha256';cls.pin.write_text(cls.fingerprint + '\n')
        cls.environment['HOD_KEYSTORE_BASE64'] = base64.b64encode(cls.key.read_bytes()).decode()

    @classmethod
    def tearDownClass(cls):
        cls.sandbox.cleanup()

    def restore(self, output, certificate=None, **overrides):
        env = dict(self.environment, **overrides)
        return subprocess.run([sys.executable, str(ROOT / 'scripts/prepare-signing.py'), '--output', str(output), '--certificate', str(certificate or self.pin)], env=env, capture_output=True, text=True)

    def test_repeat_builds_keep_identical_key_and_private_permissions(self):
        for i in range(2):
            output = self.work / f'job-{i}/key.p12';result = self.restore(output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_bytes(), self.key.read_bytes())
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertIn(self.fingerprint, result.stdout)
            self.assertNotIn(self.password, result.stdout + result.stderr)
            self.assertNotIn(self.environment['HOD_KEYSTORE_BASE64'], result.stdout + result.stderr)

    def test_missing_damaged_or_wrong_secrets_fail_without_new_key(self):
        for i, overrides in enumerate([{'HOD_KEYSTORE_BASE64': ''}, {'HOD_KEYSTORE_PASSWORD': ''}, {'HOD_KEYSTORE_BASE64': 'invalid!'}, {'HOD_KEYSTORE_PASSWORD': 'wrong-password'}, {'HOD_KEYSTORE_BASE64': base64.b64encode(b'not-a-keystore').decode()}]):
            with self.subTest(overrides=list(overrides)):
                output = self.work / f'invalid-{i}/key.p12';result = self.restore(output, **overrides)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output.exists())
                self.assertNotIn(self.password, result.stdout + result.stderr)

    def test_wrong_certificate_rejected_and_existing_key_not_overwritten(self):
        wrong = self.work / 'wrong.sha256';wrong.write_text('0' * 64)
        output = self.work / 'wrong-pin/key.p12';self.assertNotEqual(self.restore(output, wrong).returncode, 0);self.assertFalse(output.exists())
        existing = self.work / 'existing.p12';existing.write_bytes(b'keep-this-original')
        self.assertNotEqual(self.restore(existing).returncode, 0)
        self.assertEqual(existing.read_bytes(), b'keep-this-original')

    def test_public_source_archive_never_includes_signing_files(self):
        project = self.work / 'packaging';(project / 'scripts').mkdir(parents=True)
        shutil.copyfile(ROOT / 'scripts/package-source.py', project / 'scripts/package-source.py')
        names = ['README.md', 'config/signing-certificate.sha256', 'keys/update.JKS', 'private.p12', 'private.keystore', 'private.pfx', 'private.key', 'keystore.base64', 'keystore-password.txt', 'Hod-Signing-Key.zip', 'Hod-Signing-Key (1).zip', 'signing-private/readme.txt', '.hod-signing/anything.txt']
        for name in names:
            file = project / name;file.parent.mkdir(parents=True, exist_ok=True);file.write_text('fixture')
        archive = project / 'Source.zip'
        subprocess.run([sys.executable, str(project / 'scripts/package-source.py'), '--output', str(archive)], capture_output=True, check=True)
        with zipfile.ZipFile(archive) as z:
            self.assertEqual(set(z.namelist()), {'README.md', 'config/signing-certificate.sha256', 'scripts/package-source.py'})

    def test_apk_verifier_rejects_wrong_multiple_or_unverified_signers(self):
        signer = self.work / 'apksigner-fixture'
        signer.write_text(f'#!{sys.executable}\nimport json,sys\nf=json.load(open(sys.argv[-1]))\nprint(f["output"])\nsys.exit(f["status"])\n');signer.chmod(0o700)
        correct = f'Signer #1 certificate SHA-256 digest: {self.fingerprint}\n'
        for i, (status, output, accepted) in enumerate([(0, correct, True), (0, correct.replace(self.fingerprint, '0' * 64), False), (1, correct, False), (0, correct + correct.replace('#1', '#2'), False), (0, '', False)]):
            apk = self.work / f'fake-{i}.apk';apk.write_text(json.dumps({'status': status, 'output': output}))
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/verify-signature.py'), str(apk), '--apksigner', str(signer), '--certificate', str(self.pin)], capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, accepted)

    def test_termux_upload_is_repeatable_and_keeps_key_outside_project(self):
        project = self.work / 'termux-project';(project / 'scripts').mkdir(parents=True);(project / 'config').mkdir()
        shutil.copyfile(ROOT / 'scripts/setup-signing-termux.sh', project / 'scripts/setup-signing-termux.sh')
        (project / 'config/signing-certificate.sha256').write_text(self.fingerprint + '\n')
        bundle = self.work / 'kit.zip'
        with zipfile.ZipFile(bundle, 'w') as z:
            z.writestr('certificate.sha256', self.fingerprint + '\n');z.writestr('keystore.base64', self.environment['HOD_KEYSTORE_BASE64']);z.writestr('keystore-password.txt', self.password)
        binary = self.work / 'fake-bin';binary.mkdir();log = self.work / 'secret-upload.jsonl';gh = binary / 'gh'
        gh.write_text(f'#!{sys.executable}\nimport hashlib,json,os,sys\nif sys.argv[1:3] == ["auth","status"]: sys.exit(0)\nif sys.argv[1:3] != ["secret","set"]: sys.exit(1)\nwith open(os.environ["HOD_TEST_LOG"],"a") as log: log.write(json.dumps([sys.argv[3],hashlib.sha256(sys.stdin.buffer.read()).hexdigest()])+"\\n")\n');gh.chmod(0o700)
        private = self.work / 'termux-private'
        env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'], HOD_TEST_LOG=str(log), HOD_SIGNING_DIRECTORY=str(private))
        for i in range(2):
            result = subprocess.run(['bash', str(project / 'scripts/setup-signing-termux.sh'), str(bundle), 'test-owner/test-repo'], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn(self.password, result.stdout + result.stderr)
        records = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertEqual(len(records), 4);self.assertEqual(records[:2], records[2:])
        self.assertEqual(records[0][0], 'HOD_KEYSTORE_BASE64');self.assertEqual(records[1][0], 'HOD_KEYSTORE_PASSWORD')
        self.assertEqual(private.stat().st_mode & 0o777, 0o700)
        self.assertFalse((project / 'keystore-password.txt').exists())
        other = self.work / 'wrong-kit.zip'
        with zipfile.ZipFile(other, 'w') as z:z.writestr('certificate.sha256', '0' * 64)
        result = subprocess.run(['bash', str(project / 'scripts/setup-signing-termux.sh'), str(other)], env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0);self.assertEqual(len(log.read_text().splitlines()), 4)


if __name__ == '__main__':
    unittest.main(verbosity=2)
