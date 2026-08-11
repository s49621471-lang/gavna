#!/usr/bin/env bash
# Cross-builds the Windows binary from Linux with mingw-w64.
# Produces a fully static exe importing nothing but system DLLs.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$root/dist"
obj="$(mktemp -d)"
trap 'rm -rf "$obj"' EXIT

CXX="${CXX:-x86_64-w64-mingw32-g++}"

flags=(
    -std=c++17 -O2 -municode
    -DUNICODE -D_UNICODE -DNOMINMAX -DWIN32_LEAN_AND_MEAN -D_WIN32_WINNT=0x0A00
    -Wall -Wextra
    -I"$root/src"
)

libs=(-ld3d11 -ldxgi -ld2d1 -ldwrite -ldcomp -lole32 -luuid -luser32 -lgdi32)

for src in "$root"/src/*.cpp; do
    "$CXX" "${flags[@]}" -c "$src" -o "$obj/$(basename "$src" .cpp).o"
done

mkdir -p "$out/config"
"$CXX" -municode -O2 "$obj"/*.o -o "$out/cs2-esp.exe" \
    -static -static-libgcc -static-libstdc++ "${libs[@]}"

cp "$root/config/offsets.ini" "$out/config/offsets.ini"

echo "built: $out/cs2-esp.exe"
