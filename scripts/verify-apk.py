#!/usr/bin/env python3
"""Verify local-engine APK, network integrity, ARM64, native alignment and removed components."""
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
        assert sorted(libraries) == ['lib/arm64-v8a/libcaissa.so', 'lib/arm64-v8a/libtexel.so'], f'Unexpected native libraries: {libraries}'
        binary = archive.read('lib/arm64-v8a/libcaissa.so')
        elf(binary, 'libcaissa.so')
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
        texel_binary = archive.read('lib/arm64-v8a/libtexel.so')
        elf(texel_binary, 'libtexel.so')
        texel = json.loads(archive.read('assets/texel/provenance.json'))
        assert texel['version'] == '1.12' and texel['commit'] == 'bd5698c67f3c1841db623296cfd49d4a37abd5a3', 'Wrong Texel release'
        assert texel['abi'] == 'arm64-v8a' and texel['target'] == 'aarch64-neon'
        assert texel['network'] == 'nndata.tbin.compr' and texel['network.embedded'] is True and texel['network.bytes'] > 1000, 'Texel network not embedded'
        assert len(texel['network.sha256']) == 64 and all(c in '0123456789abcdef' for c in texel['network.sha256']), 'Invalid Texel network digest'
        assert hashlib.sha256(texel_binary).hexdigest() == texel['binary.sha256'], 'Texel binary mismatch'
        texel_properties = dict(line.split('=', 1) for line in archive.read('assets/texel/engine.properties').decode().splitlines() if '=' in line)
        for key in ('version', 'commit', 'binary.sha256', 'network', 'network.sha256'):
            assert texel_properties[key] == texel[key], f'Texel manifest mismatch: {key}'
        for license_name in ('Texel-GPL-3.txt', 'Texel-SOURCE.txt'):
            assert len(archive.read('assets/licenses/' + license_name)) > 20, f'Missing license: {license_name}'
        assert not any('stockfish' in n.lower() or 'onnxruntime' in n.lower() or n.startswith('assets/maia/') for n in names), 'Retired engine asset leaked'
        manifest = archive.read('AndroidManifest.xml')
        assert b'android.permission.INTERNET' not in manifest and 'android.permission.INTERNET'.encode('utf-16le') not in manifest, 'Offline app must not request INTERNET'
        dex = b''.join(archive.read(n) for n in names if n.startswith('classes') and n.endswith('.dex'))
        for entry in (b'Lru/hod/chess/RootCaptureMain;', b'Lru/hod/chess/core/RootCaptureWire;', b'Lru/hod/chess/core/PanelPlacement;', b'Lru/hod/chess/core/UciEngine;', b'Lru/hod/chess/LegacyUpgrade;', b'Lru/hod/chess/core/TrainingChoice;', b'Lru/hod/chess/core/EngineKind;', b'Lru/hod/chess/core/PositionCoach;', b'Lru/hod/chess/core/HintVisibility;', b'Lru/hod/chess/core/ExchangeSafety;'):
            assert entry in dex, f'Missing class: {entry}'
        for entry in (b'Lru/hod/chess/MaiaEngine;', b'Lru/hod/chess/core/ApiEngine;', b'Lru/hod/chess/TookenTransport;', b'Lru/hod/chess/core/LocalEngine;', b'Lru/hod/chess/core/EngineStrength;'):
            assert entry not in dex, f'Retired engine class leaked: {entry}'
        assert b'org.lichess.mobileV2' in dex, 'Official Lichess package missing'
    print(f'PASS: {Path(apk).name}, Caissa 2.0 + Texel 1.12 ARM64, verified networks, 16 KiB alignment, offline manifest and no retired engines')


if __name__ == '__main__':
    verify(sys.argv[1])
