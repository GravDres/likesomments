# Texel 1.12

Author: Peter Österlund and contributors. Official project:
https://github.com/peterosterlund2/texel

Release tag: `1.12`; full source commit:
`bd5698c67f3c1841db623296cfd49d4a37abd5a3`.

Texel is licensed under GPL-3.0-or-later; see its `COPYING` and individual source
headers for contributor and third-party notices. The official source includes
the network `nndata.tbin.compr`, embedded in the executable by upstream CMake.
HOD does not substitute Caissa/Stockfish/Maia weights.

Texel runs as a separate UCI executable. Its source is unmodified. HOD supplies
Android/ARM64 build flags, UCI settings and training presentation. The exact
Texel source tree including its network is distributed in the GitHub Actions
`Hod-Corresponding-Source` artifact. The APK includes this attribution, the GPL
text and source/binary/network provenance in `assets/texel/`.

The numbers sent to `UCI_Elo` are Texel settings, not measured Lichess ratings.
No accuracy target, human playing style or protection against bans is claimed.
