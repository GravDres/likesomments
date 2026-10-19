#!/usr/bin/env python3
"""Verify that the distributed APK really contains the ARM64 NNUE engine."""
import pathlib
import struct
import sys
import zipfile
import hashlib
import json

apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, 'Corrupt APK'
    binary = archive.read('lib/arm64-v8a/libstockfish.so')
    assert binary[:4] == b'\x7fELF', 'Engine is not ELF'
    assert binary[4] == 2, 'Engine is not 64 bit'
    assert struct.unpack_from('<H', binary, 18)[0] == 183, 'Engine is not AArch64'
    networks = list(pathlib.Path('third_party/Stockfish-sf_19/src').glob('*.nnue'))
    assert networks, 'NNUE source files missing: cannot verify embedding'
    network_bytes = sum(p.stat().st_size for p in networks)
    assert network_bytes > 1_000_000, 'NNUE networks unexpectedly small'
    assert len(binary) >= network_bytes, 'Engine too small to contain the downloaded NNUE network'
    # The Android 16 device can use 16 KiB pages. All load segments must support it.
    offset = struct.unpack_from('<Q', binary, 32)[0]
    size, count = struct.unpack_from('<HH', binary, 54)
    for i in range(count):
        program = offset + i * size
        if struct.unpack_from('<I', binary, program)[0] == 1:
            alignment = struct.unpack_from('<Q', binary, program + 48)[0]
            assert alignment >= 16384, f'ELF LOAD alignment too small: {alignment}'
    for name in ('assets/licenses/Stockfish-GPL-3.txt', 'assets/licenses/Stockfish-AUTHORS.txt', 'assets/licenses/Stockfish-SOURCE.txt'):
        assert len(archive.read(name)) > 20, f'Missing attribution: {name}'
    assert 'AndroidManifest.xml' in archive.namelist()
    for name in archive.namelist():
        if not (name.startswith('lib/') and name.endswith('.so')):
            continue
        assert name.startswith('lib/arm64-v8a/'), f'Unexpected ABI: {name}'
        library=archive.read(name)
        assert library[:4]==b'\x7fELF' and library[4]==2 and struct.unpack_from('<H',library,18)[0]==183, f'Not ARM64: {name}'
        phoff=struct.unpack_from('<Q',library,32)[0]
        phsize,phcount=struct.unpack_from('<HH',library,54)
        for i in range(phcount):
            entry=phoff+i*phsize
            if struct.unpack_from('<I',library,entry)[0]==1:
                assert struct.unpack_from('<Q',library,entry+48)[0]>=16384, f'16 KiB page alignment missing: {name}'
    assert any('onnxruntime' in n and n.endswith('.so') for n in archive.namelist()), 'ONNX Runtime missing'
    manifest=dict(line.split('=',1) for line in archive.read('assets/maia/models.properties').decode().splitlines() if '=' in line)
    report=json.loads(archive.read('assets/maia/provenance.json'))
    assert len(report['upstream'])==40 and len(report['models'])==2, 'Maia provenance missing'
    for size in ('5m','23m'):
        name=f'maia3-{size}.onnx';model=archive.read('assets/maia/'+name)
        assert len(model)>1_000_000, f'Model is not a full network: {name}'
        assert hashlib.sha256(model).hexdigest()==manifest[name+'.sha256'], f'Model hash mismatch: {name}'
        parity=next(m for m in report['models'] if m['name']==name)
        assert parity['sha256']==manifest[name+'.sha256'] and parity['max_output_error']<=1e-4 and parity['parity_cases']>=32, f'Unverified export: {name}'
    for name in ('Maia3-AGPL-3.txt','Maia3-SOURCE.txt','ONNXRuntime-LICENSE.txt','ONNXRuntime-ThirdPartyNotices.txt'):
        assert len(archive.read('assets/licenses/'+name))>20, f'Missing license: {name}'
    dex = [archive.read(name) for name in archive.namelist() if name.startswith('classes') and name.endswith('.dex')]
    for entry in (b'Lru/hod/chess/RootCaptureMain;', b'Lru/hod/chess/core/RootCaptureWire;', b'Lru/hod/chess/core/ChessPlatform;', b'Lru/hod/chess/core/EngineStrength;', b'Lru/hod/chess/MaiaEngine;', b'Lru/hod/chess/core/MaiaPolicy;'):
        assert any(entry in data for data in dex), f'Missing application entry point: {entry}'
    assert any(b'org.lichess.mobileV2' in data for data in dex), 'Official Lichess app package missing'
print(f'PASS: {apk.name}, ARM64 Stockfish + NNUE ({len(binary):,} bytes), Maia-3 5M/23M + hashes + export parity, 16 KiB alignment of all native libraries, licenses and capture')
