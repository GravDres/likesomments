#!/usr/bin/env python3
"""Create a source bundle; CI includes the exact Caissa sources and neural network."""
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
skip = {'tools', 'artifacts', '.gradle', '.git', '.idea', 'build', '__pycache__', 'jniLibs', '.hod-signing', 'signing-private'}
private_names = {'keystore.base64', 'keystore-password.txt', 'Hod-Signing-Key.zip'}
private_suffixes = {'.jks', '.keystore', '.p12', '.pfx', '.key'}
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for file in sorted(root.rglob('*')):
        if not file.is_file():
            continue
        relative = file.relative_to(root)
        private = file.name in private_names or file.suffix.lower() in private_suffixes or (file.name.startswith('Hod-Signing-Key') and file.suffix.lower() == '.zip')
        if any(part in skip for part in relative.parts) or file.name == 'local.properties' or private or file.resolve() == output:
            continue
        if relative.parts[0] == 'third_party':
            if not args.with_engine or relative.parts[1] not in {'Caissa-2.0', 'Texel-1.12'}:
                continue
            if file.suffix in {'.o', '.a', '.so'} or file.name in {'caissa', 'texel', 'texelutil', 'utils', '.depend'}:
                continue
        archive.write(file, relative)
print(output)
