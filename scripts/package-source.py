#!/usr/bin/env python3
"""Create a source bundle; CI includes the exact Stockfish and Maia sources/models."""
import argparse
import pathlib
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--with-engine', action='store_true')
parser.add_argument('--output', default='artifacts/Hod-Source.zip')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
output = pathlib.Path(args.output).resolve()
output.parent.mkdir(parents=True, exist_ok=True)
skip = {'tools', 'artifacts', '.gradle', '.git', '.idea', 'build', '__pycache__', 'jniLibs'}
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for file in sorted(root.rglob('*')):
        if not file.is_file():
            continue
        relative = file.relative_to(root)
        if any(part in skip for part in relative.parts) or file.name == 'local.properties':
            continue
        if relative.parts[0] == 'third_party':
            if not args.with_engine or relative.parts[1] not in {'Stockfish-sf_19', 'Maia3', 'Maia3-checkpoints'}:
                continue
            if file.suffix in {'.o', '.a', '.so'} or file.name in {'stockfish', '.depend'}:
                continue
        archive.write(file, relative)
print(output)
