#!/usr/bin/env bash
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo 'This integration test must run as root in the disposable Arch container.' >&2; exit 1; }

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
test_root=$(mktemp -d /tmp/kortty-pacman-test.XXXXXX)
trap 'rm -rf -- "$test_root"' EXIT

id builder >/dev/null 2>&1 || useradd -m -s /bin/bash builder
chown builder:builder "$test_root"

write_elf() {
  local file=$1
  local machine=$2
  python3 - "$file" "$machine" <<'PY'
from pathlib import Path
import struct
import sys

path = Path(sys.argv[1])
machine = int(sys.argv[2])
ident = b"\x7fELF" + bytes((2, 1, 1, 0, 0)) + bytes(7)
header = struct.pack("<16sHHIQQQIHHHHHH", ident, 3, machine, 1, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0)
path.write_bytes(header)
PY
  chmod 755 "$file"
}

create_fixture() {
  local arch=$1
  local tarball=$2
  local jvm_arch=${3:-$arch}
  local machine jvm_machine
  case "$arch" in x86_64) machine=62 ;; aarch64) machine=183 ;; esac
  case "$jvm_arch" in x86_64) jvm_machine=62 ;; aarch64) jvm_machine=183 ;; esac
  local stage
  stage=$(mktemp -d "$test_root/fixture.XXXXXX")
  mkdir -p "$stage/korTTY/bin" "$stage/korTTY/lib/runtime/lib/server"
  write_elf "$stage/korTTY/bin/korTTY" "$machine"
  write_elf "$stage/korTTY/lib/runtime/lib/server/libjvm.so" "$jvm_machine"
  tar -C "$stage" -czf "$tarball" korTTY
}

run_packager() {
  local script=$1
  local arch=$2
  local tarball=$3
  local output=$4
  local command
  printf -v command '%q ' "$script" 0.0.0 1 "$arch" "$tarball" "$output"
  su builder -c "$command"
}

for arch in x86_64 aarch64; do
  fixture="$test_root/kortty-Linux-0.0.0-${arch}.tar.gz"
  output="$test_root/output-$arch"
  mkdir -p "$output"
  create_fixture "$arch" "$fixture"
  chown -R builder:builder "$fixture" "$output"
  run_packager "$repo_root/scripts/package-pacman.sh" "$arch" "$fixture" "$output"
  expected="kortty-0.0.0-1-${arch}.pkg.tar.zst"
  test -f "$output/$expected"
  test "$(find "$output" -maxdepth 1 -type f -name '*.pkg.tar.zst' | wc -l)" -eq 1
  if find "$output" -maxdepth 1 -type f -name '*-debug-*.pkg.tar.zst' -print -quit | grep -q .; then
    echo "Debug package unexpectedly produced for $arch." >&2
    exit 1
  fi
  "$repo_root/scripts/verify-pacman-package.sh" \
    "$output/$expected" 0.0.0 1 "$arch" "$repo_root/LICENSE" "$repo_root/package/arch/kortty.desktop"
done

guard_variants=(
  'prepare () { :; }'
  $'build()\n{ :; }'
  'check ( ) { :; }'
  'function build { :; }'
  'function prepare() { :; }'
)
for index in "${!guard_variants[@]}"; do
  isolated="$test_root/guard-repo-$index"
  mkdir -p "$isolated"
  cp -a "$repo_root/package" "$repo_root/scripts" "$repo_root/LICENSE" "$isolated/"
  printf '\n%s\n' "${guard_variants[$index]}" >> "$isolated/package/arch/PKGBUILD"
  output="$test_root/guard-output-$index"
  mkdir -p "$output"
  chown -R builder:builder "$isolated" "$output"
  log="$test_root/guard-$index.log"
  if run_packager "$isolated/scripts/package-pacman.sh" aarch64 "$test_root/kortty-Linux-0.0.0-aarch64.tar.gz" "$output" >"$log" 2>&1; then
    echo "Cross-packaging guard accepted forbidden function variant $index." >&2
    exit 1
  fi
  grep -F 'Cross-packaging guard' "$log" >/dev/null
  test -z "$(find "$output" -maxdepth 1 -type f -name '*.pkg.tar.zst' -print -quit)"
done

mixed_fixture="$test_root/kortty-Linux-0.0.0-aarch64-mixed.tar.gz"
mixed_output="$test_root/mixed-output"
mkdir -p "$mixed_output"
create_fixture aarch64 "$mixed_fixture" x86_64
chown -R builder:builder "$mixed_fixture" "$mixed_output"
mixed_log="$test_root/mixed.log"
if run_packager "$repo_root/scripts/package-pacman.sh" aarch64 "$mixed_fixture" "$mixed_output" >"$mixed_log" 2>&1; then
  echo 'AArch64 package with an x86_64 bundled JVM was accepted.' >&2
  exit 1
fi
grep -F "Bundled JVM has ELF machine 'Advanced Micro Devices X86-64'; expected 'AArch64'." "$mixed_log" >/dev/null
test -z "$(find "$mixed_output" -maxdepth 1 -type f -name '*.pkg.tar.zst' -print -quit)"

echo 'Pacman packaging integration tests passed for x86_64 and aarch64.'
