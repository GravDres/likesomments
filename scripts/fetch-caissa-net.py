#!/usr/bin/env python3
"""Fetch Caissa 2.0's exact release network, verify GitHub's published asset digest."""
import hashlib
import json
from pathlib import Path
import urllib.request

TAG = 'eval-ml-8-152B-spsa'
NAME = TAG + '.pnn'
ROOT = Path(__file__).resolve().parent.parent
DESTINATION = ROOT / 'third_party/Caissa-2.0/data/neuralNets' / NAME
API = 'https://api.github.com/repos/Witek902/Caissa-Nets/releases/tags/' + TAG


def fetch():
    request = urllib.request.Request(API, headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'Hod-Caissa-build'})
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    if release.get('tag_name') != TAG:
        raise RuntimeError('Unexpected Caissa network release')
    matches = [a for a in release.get('assets', []) if a.get('name') == NAME]
    if len(matches) != 1:
        raise RuntimeError('Official Caissa neural network asset is missing or ambiguous')
    asset = matches[0]
    digest = asset.get('digest', '')
    if not digest.startswith('sha256:') or len(digest) != 71:
        raise RuntimeError('GitHub did not publish the network SHA-256; refusing an unverified network')
    expected = digest[7:]
    int(expected, 16)
    expected_size = asset['size']
    if not 1_000_000 < expected_size <= 256 * 1024 * 1024:
        raise RuntimeError('Unexpected network size')
    url = f'https://github.com/Witek902/Caissa-Nets/releases/download/{TAG}/{NAME}'
    if asset.get('browser_download_url') != url:
        raise RuntimeError('Unexpected asset download URL')
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    if DESTINATION.is_file() and DESTINATION.stat().st_size == expected_size:
        with DESTINATION.open('rb') as existing:
            if hashlib.file_digest(existing, 'sha256').hexdigest() == expected:
                return expected
    temporary = DESTINATION.with_suffix('.pnn.part')
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Hod-Caissa-build'}), timeout=60) as source, temporary.open('wb') as target:
            digest = hashlib.sha256()
            size = 0
            while block := source.read(128 * 1024):
                size += len(block)
                if size > expected_size:
                    raise RuntimeError('Network exceeds its declared size')
                digest.update(block)
                target.write(block)
        if size != expected_size or digest.hexdigest() != expected:
            raise RuntimeError('Caissa network SHA-256 or size mismatch')
        temporary.replace(DESTINATION)
    finally:
        temporary.unlink(missing_ok=True)
    return expected


if __name__ == '__main__':
    print(f'{NAME}: SHA-256 verified: {fetch()}')
