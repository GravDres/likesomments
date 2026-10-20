#!/usr/bin/env python3
"""Apply ZIP deletions safely: only fixed paths of retired engines, never signing or game data."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parent.parent
JAVA = 'app/src/main/java/ru/hod/chess/'
FILES = [JAVA + name + '.java' for name in ('MaiaEngine', 'TookenTransport', 'ApiKeyStore')]
FILES += [JAVA + 'core/' + name + '.java' for name in (
    'MaiaModel', 'MaiaNetwork', 'MaiaPolicy', 'TacticalMaiaEngine', 'RootMoveEngine',
    'EngineMode', 'EngineStrength', 'LocalEngine', 'ApiEngine', 'ApiModel', 'ApiChessFacts', 'ApiModelBenchmark')]
FILES += ['scripts/' + name for name in ('build-stockfish.sh', 'build-maia.sh', 'export-maia.py', 'test-maia-lifecycle.sh')]
FILES += ['tests/' + name + '.java' for name in ('EngineStrengthTest', 'MaiaPolicyTest', 'MaiaInterop', 'MaiaLifecycleTest', 'TacticalMaiaTest', 'ApiEngineTest', 'ApiUpgradeTest')]
FILES += ['app/src/test/java/ru/hod/chess/TookenTransportTest.java', 'MAIA3-NOTICE.md']
FILES += ['app/src/main/assets/licenses/' + name for name in (
    'Stockfish-GPL-3.txt', 'Stockfish-AUTHORS.txt', 'Stockfish-SOURCE.txt',
    'Maia3-AGPL-3.txt', 'Maia3-SOURCE.txt', 'ONNXRuntime-LICENSE.txt', 'ONNXRuntime-ThirdPartyNotices.txt')]
DIRECTORIES = ['app/src/main/assets/maia', 'third_party/Stockfish-sf_19', 'third_party/Maia3', 'third_party/Maia3-checkpoints', 'tests/ort-stub']


def clean():
    for relative in FILES:
        path = ROOT / relative
        if path.is_symlink() or path.is_file():
            path.unlink()
    for relative in DIRECTORIES:
        path = ROOT / relative
        if path.is_symlink():
            path.unlink()
        elif path.is_dir():
            shutil.rmtree(path)
    native = ROOT / 'app/src/main/jniLibs'
    if native.is_dir():
        for directory in native.iterdir():
            if directory.is_dir() and not directory.is_symlink():
                for name in ('libstockfish.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so'):
                    file = directory / name
                    if file.is_file() or file.is_symlink():
                        file.unlink()


if __name__ == '__main__':
    clean()
    print('Retired engine files removed; signing, screenshots and game history untouched.')
