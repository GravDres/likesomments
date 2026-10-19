#!/usr/bin/env python3
"""Restore a fixed signing key from repository secrets, never generate a new one."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys


def prepare(output, certificate):
    encoded = os.environ.get('HOD_KEYSTORE_BASE64', '')
    password = os.environ.get('HOD_KEYSTORE_PASSWORD', '')
    if not encoded or not password:
        raise ValueError('Set HOD_KEYSTORE_BASE64 and HOD_KEYSTORE_PASSWORD in GitHub Actions secrets. Run scripts/setup-signing-termux.sh once.')
    expected = certificate.read_text().strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Invalid public certificate fingerprint')
    try:
        data = base64.b64decode(''.join(encoded.split()), validate=True)
    except ValueError:
        raise ValueError('HOD_KEYSTORE_BASE64 is not valid base64') from None
    if not data or len(data) > 32768:
        raise ValueError('Invalid signing keystore size')
    output = output.resolve()
    if any(c in str(output) for c in '\r\n'):
        raise ValueError('Invalid keystore path')
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Refuse to replace an existing key, including a symlink.
    with output.open('xb') as store:
        output.chmod(0o600)
        store.write(data)
    try:
        result = subprocess.run([
            'keytool', '-exportcert', '-alias', 'hod', '-storetype', 'PKCS12',
            '-keystore', str(output), '-storepass:env', 'HOD_KEYSTORE_PASSWORD',
        ], capture_output=True, check=False)
        if result.returncode:
            raise ValueError('Cannot open signing key with alias hod. Check the two GitHub secrets.')
        actual = hashlib.sha256(result.stdout).hexdigest()
        if actual != expected:
            raise ValueError('Signing certificate mismatch. Keep the original signing key; a different one breaks Android updates.')
        if os.environ.get('GITHUB_ENV'):
            with Path(os.environ['GITHUB_ENV']).open('a') as environment:
                environment.write(f'HOD_SIGNING_STORE={output}\n')
        print(f'PASS: fixed signing certificate SHA-256 {actual}')
    except Exception:
        output.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--certificate', type=Path, default=Path(__file__).resolve().parent.parent / 'config/signing-certificate.sha256')
    args = parser.parse_args()
    try:
        prepare(args.output, args.certificate)
    except (OSError, ValueError) as error:
        print(f'Signing setup failed: {error}', file=sys.stderr)
        sys.exit(1)
