#!/usr/bin/env bash
# Linux bootstrap. Installs everything inside this project, never changes system settings.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
mkdir -p tools artifacts
download() { if [ ! -s "$2" ]; then curl --fail --location --retry 2 --connect-timeout 15 --max-time 600 "$1" -o "$2.part"; mv "$2.part" "$2"; fi; }
if [ ! -x tools/jdk/bin/javac ]; then
    download 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse' tools/jdk.tar.gz
    mkdir -p tools/jdk
    tar -xzf tools/jdk.tar.gz -C tools/jdk --strip-components=1
fi
export JAVA_HOME="$task_root/tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$task_root/tools/android-sdk"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    download 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' tools/cmdline-tools.zip
    mkdir -p tools/cmdline-unpack "$ANDROID_HOME/cmdline-tools"
    unzip -q -o tools/cmdline-tools.zip -d tools/cmdline-unpack
    mv tools/cmdline-unpack/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# Disable pipefail for 'yes': it normally exits on SIGPIPE when sdkmanager has finished.
set +o pipefail
yes | "$sdkmanager" --licenses > artifacts/sdk-licenses.log
set -o pipefail
"$sdkmanager" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358'
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
if [ ! -x tools/gradle-8.13/bin/gradle ]; then
    download 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' tools/gradle.zip
    unzip -q -o tools/gradle.zip -d tools
fi
bash scripts/test-core.sh
bash scripts/build-maia.sh
bash scripts/build-stockfish.sh
"$task_root/tools/gradle-8.13/bin/gradle" --no-daemon :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk artifacts/Hod-0.15-arm64-debug.apk
echo "APK: $task_root/artifacts/Hod-0.15-arm64-debug.apk"
