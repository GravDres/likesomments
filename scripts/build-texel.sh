#!/usr/bin/env bash
# Pinned official source; its compressed NNUE is embedded by upstream CMake.
set -euo pipefail
cd "$(dirname "$0")/.."
task_root="$PWD"
source_dir="$task_root/third_party/Texel-1.12"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28c}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$toolchain/aarch64-linux-android29-clang++"
mkdir -p third_party artifacts app/src/main/jniLibs/arm64-v8a app/src/main/assets/texel app/src/main/assets/licenses
if [ ! -d "$source_dir/.git" ]; then
    pending_source=$(mktemp -d "$task_root/third_party/texel-source.XXXXXX")
    trap 'rm -rf -- "$pending_source"' EXIT
    git clone --depth 1 --branch 1.12 https://github.com/peterosterlund2/texel.git "$pending_source"
    test ! -e "$source_dir"
    mv "$pending_source" "$source_dir"
    trap - EXIT
fi
source_commit=$(git -C "$source_dir" rev-parse HEAD)
[[ "$source_commit" == bd5698c67f3c1841db623296cfd49d4a37abd5a3 ]] || { echo 'Wrong Texel 1.12 source commit' >&2; exit 1; }
git -C "$source_dir" diff --exit-code HEAD
git -C "$source_dir" ls-files --error-unmatch nndata.tbin.compr >/dev/null
test -s "$source_dir/nndata.tbin.compr"
cmake -S "$source_dir" -B artifacts/texel-host -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DUSE_NUMA=OFF -DUSE_CLUSTER=OFF
cmake --build artifacts/texel-host --target texel -j "${HOD_BUILD_JOBS:-2}"
bash scripts/test-texel-native.sh artifacts/texel-host/texel
cmake -S "$source_dir" -B artifacts/texel-android \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DUSE_NEON=ON -DUSE_NEON_DOT=OFF \
    -DUSE_CTZ=ON -DUSE_PREFETCH=ON -DCMAKE_EXE_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build artifacts/texel-android --target texel -j "${HOD_BUILD_JOBS:-2}"
"$toolchain/llvm-strip" artifacts/texel-android/texel
cp artifacts/texel-android/texel app/src/main/jniLibs/arm64-v8a/libtexel.so
chmod 755 app/src/main/jniLibs/arm64-v8a/libtexel.so
cp "$source_dir/COPYING" app/src/main/assets/licenses/Texel-GPL-3.txt
cp TEXEL-NOTICE.md app/src/main/assets/licenses/Texel-SOURCE.txt
python3 - <<'PY'
import hashlib, json
from pathlib import Path
binary = Path('app/src/main/jniLibs/arm64-v8a/libtexel.so')
network = Path('third_party/Texel-1.12/nndata.tbin.compr')
report = {'version': '1.12', 'commit': 'bd5698c67f3c1841db623296cfd49d4a37abd5a3',
          'source': 'https://github.com/peterosterlund2/texel/tree/1.12',
          'binary.sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
          'network': 'nndata.tbin.compr', 'network.embedded': True,
          'network.sha256': hashlib.sha256(network.read_bytes()).hexdigest(),
          'network.bytes': network.stat().st_size, 'abi': 'arm64-v8a',
          'target': 'aarch64-neon', 'android.api': 29,
          'native.protocol.tests': 'passed-on-linux-host; Android-device validation still required'}
root=Path('app/src/main/assets/texel')
(root/'provenance.json').write_text(json.dumps(report,indent=2)+'\n')
(root/'engine.properties').write_text('\n'.join(f'{k}={v}' for k,v in report.items())+'\n')
print('Texel 1.12: ARM64 with embedded official network; host protocol tests passed')
PY
