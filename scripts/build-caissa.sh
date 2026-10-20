#!/usr/bin/env bash
# Official release source + official network. No downloads happen inside the installed app.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
source_dir="$task_root/third_party/Caissa-2.0"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28c}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$toolchain/aarch64-linux-android29-clang++"
command -v cmake >/dev/null
mkdir -p third_party app/src/main/jniLibs/arm64-v8a app/src/main/assets/caissa app/src/main/assets/licenses
if [ ! -d "$source_dir/.git" ]; then
    pending_source=$(mktemp -d "$task_root/third_party/caissa-source.XXXXXX")
    trap 'rm -rf -- "$pending_source"' EXIT
    git clone --depth 1 --branch 2.0 https://github.com/Witek902/Caissa.git "$pending_source"
    test ! -e "$source_dir"
    mv "$pending_source" "$source_dir"
    trap - EXIT
fi
source_commit=$(git -C "$source_dir" rev-parse HEAD)
[[ "$source_commit" == bb725799e9b19ebdaa0d584f5433fc1c3019e349 ]] || { echo 'Wrong Caissa 2.0 source commit' >&2; exit 1; }
[[ "$(tr -d '\r\n ' < "$source_dir/version.txt")" == 2.0 ]] || { echo 'Wrong Caissa version' >&2; exit 1; }
git -C "$source_dir" diff --exit-code HEAD
python3 scripts/fetch-caissa-net.py
network=eval-ml-8-152B-spsa.pnn

# Run the real release through the SAME Java UCI client before producing the Android binary.
mkdir -p artifacts
cmake -S "$source_dir" -B artifacts/caissa-host -DCMAKE_BUILD_TYPE=Final -DTARGET_ARCH=x64-sse4-popcnt -DUSE_NUMA=OFF -DCMAKE_DISABLE_FIND_PACKAGE_CUDAToolkit=TRUE
cmake --build artifacts/caissa-host --target caissa -j "${HOD_BUILD_JOBS:-2}"
bash scripts/test-caissa-native.sh artifacts/caissa-host/bin/caissa "$source_dir/data/neuralNets/$network"

cmake -S "$source_dir" -B artifacts/caissa-android \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Final -DTARGET_ARCH=aarch64-neon -DUSE_NUMA=OFF -DCMAKE_DISABLE_FIND_PACKAGE_CUDAToolkit=TRUE \
    -DCMAKE_EXE_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build artifacts/caissa-android --target caissa -j "${HOD_BUILD_JOBS:-2}"
"$toolchain/llvm-strip" artifacts/caissa-android/bin/caissa
cp artifacts/caissa-android/bin/caissa app/src/main/jniLibs/arm64-v8a/libcaissa.so
chmod 755 app/src/main/jniLibs/arm64-v8a/libcaissa.so
cp "$source_dir/data/neuralNets/$network" "app/src/main/assets/caissa/$network"
cp "$source_dir/LICENSE" app/src/main/assets/licenses/Caissa-MIT.txt
cp CAISSA-NOTICE.md app/src/main/assets/licenses/Caissa-SOURCE.txt
export HOD_CAISSA_COMMIT="$source_commit"
python3 - <<'PY'
import hashlib, json, os
from pathlib import Path
root = Path('app/src/main/assets/caissa')
network = 'eval-ml-8-152B-spsa.pnn'
with (root / network).open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
binary = Path('app/src/main/jniLibs/arm64-v8a/libcaissa.so')
report = {'version': '2.0', 'commit': os.environ['HOD_CAISSA_COMMIT'], 'network': network,
          'network.sha256': digest, 'network.bytes': (root / network).stat().st_size,
          'binary.sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
          'source': 'https://github.com/Witek902/Caissa/tree/2.0',
          'network.source': 'https://github.com/Witek902/Caissa-Nets/releases/tag/eval-ml-8-152B-spsa',
          'abi': 'arm64-v8a', 'target': 'aarch64-neon', 'android.api': 29,
          'native.protocol.tests': 'passed-on-linux-host; Android-device validation still required'}
(root / 'provenance.json').write_text(json.dumps(report, indent=2) + '\n')
(root / 'engine.properties').write_text('\n'.join(f'{key}={value}' for key, value in report.items()) + '\n')
print(f'Caissa 2.0: ARM64 build + {network}, sha256={digest}')
PY
