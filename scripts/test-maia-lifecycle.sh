#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/maia-lifecycle
sources=(app/src/main/java/ru/hod/chess/core/*.java app/src/main/java/ru/hod/chess/MaiaEngine.java tests/ort-stub/ai/onnxruntime/*.java tests/MaiaLifecycleTest.java)
if command -v javac >/dev/null; then
    javac --release 17 -d artifacts/maia-lifecycle "${sources[@]}"
else
    java com.sun.tools.javac.Main -source 17 -target 17 -d artifacts/maia-lifecycle "${sources[@]}"
fi
java -ea -Xmx256m -cp artifacts/maia-lifecycle ru.hod.chess.MaiaLifecycleTest
