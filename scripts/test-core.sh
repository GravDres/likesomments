#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/core-tests
caissa_tests=(tests/CaissaClientTest.java)
if command -v javac >/dev/null; then
    javac --release 17 -d artifacts/core-tests app/src/main/java/ru/hod/chess/core/*.java tests/LocalEngine.java tests/CoreTest.java tests/FakeUci.java tests/RealBoardTest.java tests/ModeSwitchTest.java tests/RefreshTest.java tests/BoardBoundsTest.java tests/FakeRootCapture.java tests/RootCaptureTest.java tests/LichessTest.java tests/CheckHighlightTest.java tests/MoveIndicatorTest.java tests/PanelPlacementTest.java "${caissa_tests[@]}"
else
    java com.sun.tools.javac.Main -source 17 -target 17 -d artifacts/core-tests app/src/main/java/ru/hod/chess/core/*.java tests/LocalEngine.java tests/CoreTest.java tests/FakeUci.java tests/RealBoardTest.java tests/ModeSwitchTest.java tests/RefreshTest.java tests/BoardBoundsTest.java tests/FakeRootCapture.java tests/RootCaptureTest.java tests/LichessTest.java tests/CheckHighlightTest.java tests/MoveIndicatorTest.java tests/PanelPlacementTest.java "${caissa_tests[@]}"
fi
java -ea -Xmx512m -cp artifacts/core-tests CoreTest

java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests RealBoardTest
java -ea -Xmx512m -cp artifacts/core-tests ModeSwitchTest
java -ea -Xmx512m -cp artifacts/core-tests RefreshTest
java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests BoardBoundsTest
java -ea -Xmx256m -cp artifacts/core-tests RootCaptureTest
java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests LichessTest
java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests CheckHighlightTest
java -ea -Xmx512m -Djava.awt.headless=true -cp artifacts/core-tests MoveIndicatorTest
java -ea -Xmx128m -cp artifacts/core-tests PanelPlacementTest
java -ea -Xmx256m -cp artifacts/core-tests CaissaClientTest
