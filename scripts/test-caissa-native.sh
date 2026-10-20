#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/caissa-native-tests
if command -v javac >/dev/null; then
    javac --release 17 -d artifacts/caissa-native-tests app/src/main/java/ru/hod/chess/core/*.java tests/CaissaNativeTest.java
else
    java com.sun.tools.javac.Main -source 17 -target 17 -d artifacts/caissa-native-tests app/src/main/java/ru/hod/chess/core/*.java tests/CaissaNativeTest.java
fi
java -ea -Xmx256m -cp artifacts/caissa-native-tests CaissaNativeTest "$1" "$2"
