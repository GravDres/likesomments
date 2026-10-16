# Board regression pixels

These two 582×582 inputs are cropped chessboard regions from the reported recognition failure. Personal phone UI and notifications are excluded. PNG preserves the received JPEG pixels without another lossy encoding pass.

`RealBoardTest` supplies the piece diagram and FEN metadata explicitly; a screenshot cannot establish previous castling, repetition or en-passant history. It also generates translated-piece and resized test inputs in memory. A derived starting arrangement uses these photographed sprites on their starting square colors, then the original midgame image is recognized without further training. This checks generalization from start calibration, not a new photograph of an actual game. The fixtures are desktop test data and are not packaged in the Android app.
