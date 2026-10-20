# Caissa 2.0

Author: Michał Witanowski. MIT License, see upstream LICENSE distributed in the APK.

- Source: https://github.com/Witek902/Caissa/tree/2.0
- Release tag: `2.0`, release commit `bb725799e9b19ebdaa0d584f5433fc1c3019e349`.
- Network: `eval-ml-8-152B-spsa.pnn` from https://github.com/Witek902/Caissa-Nets/releases/tag/eval-ml-8-152B-spsa
- The build verifies GitHub's published network SHA-256 and records the full source commit, network hash, binary hash and build target in `assets/caissa/provenance.json`.
- `scripts/build-caissa.sh` builds the unmodified release for ARM64 NEON with NDK r28c, Android API 29, static libc++ and 16 KiB ELF alignment. Source and neural network are included in the corresponding-source CI artifact.
- No upstream engine ratings establish the strength of a particular phone, search budget or user account. No human-style or detection-avoidance claims are made.

ХОД remains GPL-3.0-or-later. Caissa is a separate local UCI process under its upstream MIT license.
