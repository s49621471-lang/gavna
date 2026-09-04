#!/usr/bin/env bash
# Compiles and runs the host-side native tests. No device or NDK required.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${TMPDIR:-/tmp}/unique-native-test"
mkdir -p "$out"
${CXX:-g++} -std=c++20 -O1 -Wall -Wextra -o "$out/redirect_table_test" \
    "$here/redirect_table_test.cpp"
"$out/redirect_table_test"
