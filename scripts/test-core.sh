#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/core-tests
if command -v javac >/dev/null; then
    javac --release 17 -d artifacts/core-tests app/src/main/java/ru/hod/chess/core/*.java tests/CoreTest.java tests/FakeUci.java
else
    java com.sun.tools.javac.Main -source 17 -target 17 -d artifacts/core-tests app/src/main/java/ru/hod/chess/core/*.java tests/CoreTest.java tests/FakeUci.java
fi
java -ea -Xmx512m -cp artifacts/core-tests CoreTest
