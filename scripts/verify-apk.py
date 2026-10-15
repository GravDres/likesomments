#!/usr/bin/env python3
"""Verify that the distributed APK really contains the ARM64 NNUE engine."""
import pathlib
import struct
import sys
import zipfile

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
print(f'PASS: {apk.name}, ARM64 engine + NNUE ({len(binary):,} bytes), 16 KiB ELF alignment, licenses')
