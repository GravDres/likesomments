#!/usr/bin/env python3
"""Cryptographically verify APK signatures and match the permanent certificate."""
import argparse
from pathlib import Path
import re
import subprocess
import sys


def verify(apksigner, apk, certificate):
    expected = certificate.read_text().strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise ValueError('Invalid pinned signing certificate')
    result = subprocess.run([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], capture_output=True, text=True)
    if result.returncode:
        raise ValueError('apksigner rejected the APK signature')
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$', result.stdout, re.MULTILINE)
    if len(signers) != 1 or signers[0].lower() != expected:
        raise ValueError('APK signing certificate differs from the permanent key')
    print(f'PASS: APK signature verified, permanent certificate SHA-256 {expected}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--apksigner', type=Path, required=True)
    parser.add_argument('--certificate', type=Path, default=Path(__file__).resolve().parent.parent / 'config/signing-certificate.sha256')
    args = parser.parse_args()
    try:
        verify(args.apksigner, args.apk, args.certificate)
    except (OSError, ValueError) as error:
        print(f'APK signature check failed: {error}', file=sys.stderr)
        sys.exit(1)
