#!/usr/bin/env bash
# Compiles and runs the host-side native tests. No device or NDK required.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${TMPDIR:-/tmp}/unique-native-test"
mkdir -p "$out"
for test in redirect_table proc_view round_trip; do
    ${CXX:-g++} -std=c++20 -O1 -Wall -Wextra -o "$out/${test}_test" "$here/${test}_test.cpp"
    "$out/${test}_test"
done
