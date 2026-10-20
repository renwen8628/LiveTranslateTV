#!/usr/bin/env bash
# One-time setup for building the Remote Play native code (chiaki-ng).
#  1. Fetches the chiaki-ng submodule and only the nested submodules the library needs.
#  2. Creates .venv-nanopb with the Python packages chiaki-ng's protobuf generator needs.
# Requires: git, python3 (3.9+), Android NDK 27.1.12297006 and CMake 3.22.1 from the SDK manager.
set -euo pipefail
cd "$(dirname "$0")/.."

git submodule update --init --depth 1 third_party/chiaki-ng
git -C third_party/chiaki-ng submodule update --init --depth 1 \
  third-party/nanopb third-party/jerasure third-party/gf-complete third-party/curl

if [ ! -x .venv-nanopb/bin/python ]; then
  python3 -m venv .venv-nanopb
fi
.venv-nanopb/bin/pip install --quiet protobuf grpcio-tools
echo "Native build setup done."
