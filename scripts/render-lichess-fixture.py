#!/usr/bin/env python3
"""Optional fixture regeneration. Requires Inkscape and Pillow; APK/CI use the checked-in PNG."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from PIL import Image

root = Path(__file__).resolve().parent.parent
directory = root / 'tests/fixtures/lichess'
inkscape = shutil.which('inkscape')
if not inkscape:
    raise SystemExit('Install Inkscape to regenerate this development fixture')
with tempfile.TemporaryDirectory(prefix='hod-lichess-') as temporary:
    workspace = Path(temporary)
    environment = dict(os.environ, INKSCAPE_PROFILE_DIR=str(workspace / 'profile'), XDG_CACHE_HOME=str(workspace / 'cache'))
    atlas = Image.new('RGBA', (12 * 144, 144))
    for index, piece in enumerate('PNBRQKpnbrqk'):
        name = ('w' if piece.isupper() else 'b') + piece.upper()
        target = workspace / (name + '.png')
        result = subprocess.run([inkscape, str(directory / 'cburnett' / (name + '.svg')), '--export-filename=' + str(target), '--export-width=144', '--export-height=144'], env=environment, capture_output=True)
        if result.returncode:
            raise SystemExit(result.stderr.decode(errors='replace'))
        with Image.open(target) as image:
            atlas.paste(image.convert('RGBA'), (index * 144, 0))
    atlas.save(directory / 'cburnett-atlas.png')
print(directory / 'cburnett-atlas.png')
