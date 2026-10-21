# Board regression pixels

These two 582×582 inputs are cropped chessboard regions from the reported recognition failure. Personal phone UI and notifications are excluded. PNG preserves the received JPEG pixels without another lossy encoding pass.

`RealBoardTest` supplies the piece diagram and FEN metadata explicitly; a screenshot cannot establish previous castling, repetition or en-passant history. It also generates translated-piece and resized test inputs in memory. A derived starting arrangement uses these photographed sprites on their starting square colors, then the original midgame image is recognized without further training. This checks generalization from start calibration, not a new photograph of an actual game. The fixtures are desktop test data and are not packaged in the Android app.

`chesscom-selector-1.png` and `chesscom-selector-2.png` are 582×699 crops of the later report, limited to the board and bot message. These already contain the old selector's stretching, grid or transparent double image. They are negative inputs for `BoardBoundsTest`, not clean screenshots used for training. The positive boundary regression constructs a complete board next to a solid message strip and reproduces the old one-rank error with its stale rectangle.

`legacy-v04.hod` and `legacy-v06.hod` contain a synthetic game after `e2e4`, with the old contrast-based templates. They were generated with the actual Java core from the v0.11 source bundle, not by relabeling a new snapshot. Version 0.4 removes the base FEN from the 0.6 wire format. They verify decoding, continued recognition, and migration to the extended format. They contain no personal information or screen captures.

The `lichess` directory contains separately documented, licensed Cburnett SVG/raster artwork and colour-pair fixtures; these are generated boards rather than phone screenshots.

`chesscom-selected-knight.png` is the 582×582 board-only crop from the selected-knight report. It preserves the original received JPEG pixels. Phone UI, notifications, profile text and chat are excluded. `MoveIndicatorTest` uses a separately photographed earlier board to train the piece theme, then checks the untouched seven move dots and selected knight. A legal Sicilian opening is supplied as explicit test history; castling rights, counters and prior moves are not inferred from the photograph. It also tests rendered light/dark dots, fresh starting-board calibration, deselection, both orientations, resizing and opaque piece negatives.
