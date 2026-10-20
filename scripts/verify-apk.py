#!/usr/bin/env python3
"""Verify Caissa-only APK, network integrity, ARM64, native alignment and removed components."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


def elf(binary, name):
    assert binary[:4] == b'\x7fELF' and binary[4] == 2 and binary[5] == 1, f'Not ELF64 LE: {name}'
    assert struct.unpack_from('<H', binary, 18)[0] == 183, f'Not AArch64: {name}'
    offset = struct.unpack_from('<Q', binary, 32)[0]
    size, count = struct.unpack_from('<HH', binary, 54)
    load = 0
    for i in range(count):
        entry = offset + i * size
        if struct.unpack_from('<I', binary, entry)[0] == 1:
            load += 1
            assert struct.unpack_from('<Q', binary, entry + 48)[0] >= 16384, f'16 KiB LOAD alignment missing: {name}'
    assert load, f'No ELF load segments: {name}'


def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, 'Corrupt APK'
        names = archive.namelist()
        libraries = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
        assert libraries == ['lib/arm64-v8a/libcaissa.so'], f'Unexpected native libraries: {libraries}'
        binary = archive.read(libraries[0])
        elf(binary, libraries[0])
        report = json.loads(archive.read('assets/caissa/provenance.json'))
        assert report['version'] == '2.0' and report['commit'] == 'bb725799e9b19ebdaa0d584f5433fc1c3019e349', 'Wrong Caissa release'
        assert report['abi'] == 'arm64-v8a' and report['target'] == 'aarch64-neon'
        assert hashlib.sha256(binary).hexdigest() == report['binary.sha256'], 'Engine binary mismatch'
        name = report['network']
        assert name == 'eval-ml-8-152B-spsa.pnn', 'Wrong neural network'
        network = archive.read('assets/caissa/' + name)
        assert len(network) == report['network.bytes'] and len(network) > 1_000_000, 'Network size mismatch'
        assert hashlib.sha256(network).hexdigest() == report['network.sha256'], 'Network hash mismatch'
        properties = dict(line.split('=', 1) for line in archive.read('assets/caissa/engine.properties').decode().splitlines() if '=' in line)
        for key in ('version', 'network', 'network.sha256', 'commit', 'binary.sha256'):
            assert properties[key] == report[key], f'Engine manifest mismatch: {key}'
        for license_name in ('Caissa-MIT.txt', 'Caissa-SOURCE.txt'):
            assert len(archive.read('assets/licenses/' + license_name)) > 20, f'Missing license: {license_name}'
        assert not any('stockfish' in n.lower() or 'onnxruntime' in n.lower() or n.startswith('assets/maia/') for n in names), 'Retired engine asset leaked'
        manifest = archive.read('AndroidManifest.xml')
        assert b'android.permission.INTERNET' not in manifest and 'android.permission.INTERNET'.encode('utf-16le') not in manifest, 'Offline app must not request INTERNET'
        dex = b''.join(archive.read(n) for n in names if n.startswith('classes') and n.endswith('.dex'))
        for entry in (b'Lru/hod/chess/RootCaptureMain;', b'Lru/hod/chess/core/RootCaptureWire;', b'Lru/hod/chess/core/PanelPlacement;', b'Lru/hod/chess/core/UciEngine;', b'Lru/hod/chess/LegacyUpgrade;'):
            assert entry in dex, f'Missing class: {entry}'
        for entry in (b'Lru/hod/chess/MaiaEngine;', b'Lru/hod/chess/core/ApiEngine;', b'Lru/hod/chess/TookenTransport;', b'Lru/hod/chess/core/LocalEngine;', b'Lru/hod/chess/core/EngineStrength;'):
            assert entry not in dex, f'Retired engine class leaked: {entry}'
        assert b'org.lichess.mobileV2' in dex, 'Official Lichess package missing'
    print(f'PASS: {Path(apk).name}, Caissa 2.0 ARM64, verified network, 16 KiB alignment, offline manifest and no retired engines')


if __name__ == '__main__':
    verify(sys.argv[1])
