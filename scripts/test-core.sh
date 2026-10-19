#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/core-tests
if command -v javac >/dev/null; then
    javac --release 17 -d artifacts/core-tests app/src/main/java/ru/hod/chess/core/*.java tests/CoreTest.java tests/FakeUci.java tests/RealBoardTest.java tests/ModeSwitchTest.java tests/RefreshTest.java tests/BoardBoundsTest.java tests/FakeRootCapture.java tests/RootCaptureTest.java tests/LichessTest.java tests/CheckHighlightTest.java tests/EngineStrengthTest.java tests/MaiaPolicyTest.java tests/MaiaInterop.java
else
    java com.sun.tools.javac.Main -source 17 -target 17 -d artifacts/core-tests app/src/main/java/ru/hod/chess/core/*.java tests/CoreTest.java tests/FakeUci.java tests/RealBoardTest.java tests/ModeSwitchTest.java tests/RefreshTest.java tests/BoardBoundsTest.java tests/FakeRootCapture.java tests/RootCaptureTest.java tests/LichessTest.java tests/CheckHighlightTest.java tests/EngineStrengthTest.java tests/MaiaPolicyTest.java tests/MaiaInterop.java
fi
java -ea -Xmx512m -cp artifacts/core-tests CoreTest

java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests RealBoardTest
java -ea -Xmx512m -cp artifacts/core-tests ModeSwitchTest
java -ea -Xmx512m -cp artifacts/core-tests RefreshTest
java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests BoardBoundsTest
java -ea -Xmx256m -cp artifacts/core-tests RootCaptureTest
java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests LichessTest
java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests CheckHighlightTest
java -ea -Xmx256m -cp artifacts/core-tests EngineStrengthTest
java -ea -Xmx256m -cp artifacts/core-tests MaiaPolicyTest
bash scripts/test-maia-lifecycle.sh
