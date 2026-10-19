# Maia-3 in ХОД

Maia-3 / Chessformer is by the University of Toronto Computational Social Science
Lab: Daniel Monroe, George Eilender, Philip Chalmers, Zhenwei Tang and Ashton Anderson.
Official implementation: https://github.com/CSSLab/maia3
Official checkpoints: https://huggingface.co/UofTCSSLab/Maia3-5M and
https://huggingface.co/UofTCSSLab/Maia3-23M

Maia-3 and the input/policy adapter based on its documented encoding are supplied
under AGPL-3.0. This integration was written for ХОД on 2026-10-19. It is an
independent integration and is not endorsed by the Maia authors. Model levels
describe conditioning, not an independently measured playing rating or a promise
of indistinguishability from a person.

The full upstream AGPL license is bundled in the APK as licenses/Maia3-AGPL-3.txt.
The corresponding-source artifact contains the exact upstream implementation,
original checkpoints and the export script. Exported ONNX hashes and resolved
upstream/Hugging Face revisions are recorded in maia/provenance.json in the APK.
Source-only archives fetch upstream main during building; they do not claim that
two builds on different dates resolve the same revision.

The rest of the existing application and Stockfish remain under their existing
GPLv3 terms; GPLv3 section 13 permits combination with AGPLv3 portions. ONNX Runtime
1.23.2 is by Microsoft under MIT; its license and notices are also bundled. There
is no warranty. The corresponding source is available alongside the APK in
GitHub Actions as Hod-Corresponding-Source.
