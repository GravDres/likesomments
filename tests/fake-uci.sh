#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec java -cp artifacts/core-tests FakeUci
