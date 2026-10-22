#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts/texel-native-tests
if command -v javac >/dev/null; then
    javac --release 17 -d artifacts/texel-native-tests app/src/main/java/ru/hod/chess/core/*.java tests/TexelNativeTest.java
else
    java com.sun.tools.javac.Main -source 17 -target 17 -d artifacts/texel-native-tests app/src/main/java/ru/hod/chess/core/*.java tests/TexelNativeTest.java
fi
java -ea -Xmx256m -cp artifacts/texel-native-tests TexelNativeTest "$1"
