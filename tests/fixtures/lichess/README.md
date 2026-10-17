# Lichess vision test data

These are rendered fixtures, not screenshots from the user's phone.

- `cburnett/*.svg`: Colin M. L. Burnett's Cburnett artwork, GPL-2.0-or-later. The paths were extracted from the `PIECES` data in [python-chess SVG](https://github.com/niklasf/python-chess/blob/master/chess/svg.py), retrieved 2026-10-17. The same artwork family is Lichess's default piece set. [Lichess attribution](https://github.com/lichess-org/lila/blob/master/COPYING.md) identifies the Cburnett author and license. This distribution elects GPL v3; the full license is the repository's `COPYING`.
- `cburnett-atlas.png`: derived transparent raster of those SVGs, Inkscape, 144 pixels per square, ordered `PNBRQKpnbrqk`. It is desktop test data and is not bundled in the Android APK. `scripts/render-lichess-fixture.py` regenerates it from the included SVGs.
- `palettes.json`: light/dark colour pairs from [flutter-chessground board_color_scheme.dart](https://github.com/lichess-org/flutter-chessground/blob/main/lib/src/board_color_scheme.dart), retrieved 2026-10-17. These are colour-pair tests. The bitmap textures of Wood, Marble and similar themes are **not** reproduced; passing a palette test does not prove its actual texture works. The separate System theme can change dynamically and is not represented by one fixed palette.

`LichessTest` renders the atlas on these backgrounds, adds corner coordinates and last-move highlights, follows a legal opening including both castles, and checks promotion and en passant. It also tests board relocation, landscape layouts, colour orientation, independent platform histories and search messages. It tests Cburnett silhouettes, not every other piece set. Testing another font requires separate artwork or phone captures.

Runtime learning is not a lookup of these palette values: it learns the actual starting board and rejects indistinguishable labels. The specially disguised Lichess sets, where different roles look identical, cannot be safely learned from an image alone. For initial phone validation, use Lichess's default Brown + Cburnett. A theme change needs a fresh starting-board calibration.

Official app defaults and available boards: [board_preferences.dart](https://github.com/lichess-org/mobile/blob/main/lib/src/model/settings/board_preferences.dart). Piece catalog and asset definitions: [piece_set.dart](https://github.com/lichess-org/flutter-chessground/blob/main/lib/src/piece_set.dart). Android package: [mobile build.gradle.kts](https://github.com/lichess-org/mobile/blob/main/android/app/build.gradle.kts).
