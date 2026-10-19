#!/usr/bin/env bash
# Fetch the official Maia-3 implementation and export verified CPU models.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party tools app/src/main/assets/maia app/src/main/assets/licenses
if [ ! -d third_party/Maia3/.git ]; then
    git clone --depth 1 https://github.com/CSSLab/maia3.git third_party/Maia3
fi
if [ ! -x tools/maia-env/bin/python ]; then python3 -m venv tools/maia-env; fi
tools/maia-env/bin/python -m pip install 'torch==2.5.1' --index-url https://download.pytorch.org/whl/cpu
tools/maia-env/bin/python -m pip install 'numpy==1.26.4' 'onnx==1.17.0' 'onnxruntime==1.23.2' 'huggingface-hub==0.36.0' 'python-chess==1.999'
tools/maia-env/bin/python -m pip install --no-deps ./third_party/Maia3
tools/maia-env/bin/python scripts/export-maia.py
cp third_party/Maia3/LICENSE app/src/main/assets/licenses/Maia3-AGPL-3.txt
cp MAIA3-NOTICE.md app/src/main/assets/licenses/Maia3-SOURCE.txt
curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/LICENSE -o app/src/main/assets/licenses/ONNXRuntime-LICENSE.txt
curl --fail --location --retry 3 --connect-timeout 15 --max-time 180 https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/ThirdPartyNotices.txt -o app/src/main/assets/licenses/ONNXRuntime-ThirdPartyNotices.txt
echo 'Maia-3 5M and 23M exported and checked against PyTorch and upstream board encoding.'
