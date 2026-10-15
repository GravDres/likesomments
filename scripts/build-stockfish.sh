#!/usr/bin/env bash
# Build the official, unmodified engine for Android arm64. Network is only used at build time.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
source_dir="third_party/Stockfish-sf_19"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28 or newer}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$toolchain/aarch64-linux-android29-clang++"
mkdir -p third_party app/src/main/jniLibs/arm64-v8a app/src/main/assets/licenses
if [ ! -f "$source_dir/.hod-source-ready" ]; then
    curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 \
        https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_19.tar.gz -o third_party/stockfish-sf_19.tar.gz.part
    mv third_party/stockfish-sf_19.tar.gz.part third_party/stockfish-sf_19.tar.gz
    mkdir -p "$source_dir"
    tar -xzf third_party/stockfish-sf_19.tar.gz -C "$source_dir" --strip-components=1
    touch "$source_dir/.hod-source-ready"
fi
# Upstream net.sh fetches the precise NNUE networks and verifies their content hashes.
export PATH="$toolchain:$PATH"
make -C "$source_dir/src" -j"${HOD_BUILD_JOBS:-2}" build ARCH=armv8 COMP=ndk \
    COMPCXX="$toolchain/aarch64-linux-android29-clang++" \
    EXTRALDFLAGS='-Wl,-z,max-page-size=16384'
"$toolchain/llvm-strip" "$source_dir/src/stockfish"
cp "$source_dir/src/stockfish" app/src/main/jniLibs/arm64-v8a/libstockfish.so
chmod 755 app/src/main/jniLibs/arm64-v8a/libstockfish.so
cp "$source_dir/Copying.txt" app/src/main/assets/licenses/Stockfish-GPL-3.txt
cp "$source_dir/AUTHORS" app/src/main/assets/licenses/Stockfish-AUTHORS.txt
printf 'Stockfish 19 (sf_19), official unmodified source.\nhttps://github.com/official-stockfish/Stockfish/tree/sf_19\nBuild: scripts/build-stockfish.sh\n' > app/src/main/assets/licenses/Stockfish-SOURCE.txt
echo "Built: $task_root/app/src/main/jniLibs/arm64-v8a/libstockfish.so"
