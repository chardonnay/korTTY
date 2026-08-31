#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 PACKAGE VERSION PKGREL ARCH LICENSE DESKTOP_FILE" >&2
  exit 2
}

[[ $# -eq 6 ]] || usage

package_file=$1
version=$2
pkgrel=$3
target_arch=$4
license_file=$5
desktop_file=$6
expected_name="kortty-${version}-${pkgrel}-${target_arch}.pkg.tar.zst"

[[ -f "$package_file" ]] || { echo "Pacman package not found: $package_file" >&2; exit 1; }
[[ -f "$license_file" ]] || { echo "License source not found: $license_file" >&2; exit 1; }
[[ -f "$desktop_file" ]] || { echo "Desktop source not found: $desktop_file" >&2; exit 1; }
[[ "$(basename "$package_file")" == "$expected_name" ]] || {
  echo "Unexpected package filename: $(basename "$package_file") (expected $expected_name)" >&2
  exit 1
}

case "$target_arch" in
  x86_64) expected_machine='Advanced Micro Devices X86-64' ;;
  aarch64) expected_machine='AArch64' ;;
  *) echo "Unsupported pacman architecture: $target_arch" >&2; exit 2 ;;
esac

for command in bsdtar readelf; do
  command -v "$command" >/dev/null || { echo "Required command is missing: $command" >&2; exit 1; }
done

verify_root=$(mktemp -d "${TMPDIR:-/tmp}/kortty-pacman-verify.XXXXXX")
trap 'rm -rf "$verify_root"' EXIT
bsdtar -xf "$package_file" -C "$verify_root"
bsdtar -xOf "$package_file" .PKGINFO > "$verify_root/PKGINFO"

require_pkginfo() {
  local expected=$1
  grep -Fx -- "$expected" "$verify_root/PKGINFO" >/dev/null || {
    echo "Missing expected .PKGINFO entry: $expected" >&2
    sed -n '1,80p' "$verify_root/PKGINFO" >&2
    exit 1
  }
}

require_pkginfo 'pkgname = kortty'
require_pkginfo "pkgver = ${version}-${pkgrel}"
require_pkginfo "arch = ${target_arch}"
require_pkginfo 'license = MIT'

installed_license="$verify_root/usr/share/licenses/kortty/LICENSE"
installed_desktop="$verify_root/usr/share/applications/kortty.desktop"
launcher_link="$verify_root/usr/bin/kortty"

[[ -f "$installed_license" ]] || { echo 'MIT license file is missing from the package.' >&2; exit 1; }
cmp "$license_file" "$installed_license"
[[ -f "$installed_desktop" ]] || { echo 'Desktop entry is missing from the package.' >&2; exit 1; }
cmp "$desktop_file" "$installed_desktop"
[[ -L "$launcher_link" ]] || { echo 'Launcher symlink /usr/bin/kortty is missing.' >&2; exit 1; }

launcher_target=$(readlink "$launcher_link")
case "$launcher_target" in
  /usr/lib/kortty/bin/korTTY|/usr/lib/kortty/bin/kortty) ;;
  *) echo "Unexpected launcher symlink target: $launcher_target" >&2; exit 1 ;;
esac

launcher="$verify_root$launcher_target"
runtime_java="$verify_root/usr/lib/kortty/lib/runtime/bin/java"
runtime_jvm="$verify_root/usr/lib/kortty/lib/runtime/lib/server/libjvm.so"

verify_elf_arch() {
  local file=$1
  local label=$2
  [[ -f "$file" ]] || { echo "$label is missing: ${file#"$verify_root"}" >&2; exit 1; }
  local machine
  machine=$(LC_ALL=C readelf -h "$file" | awk -F: '$1 ~ /^[[:space:]]*Machine[[:space:]]*$/ {sub(/^[[:space:]]+/, "", $2); print $2; exit}')
  [[ "$machine" == "$expected_machine" ]] || {
    echo "$label has ELF machine '$machine'; expected '$expected_machine'." >&2
    exit 1
  }
}

verify_elf_arch "$launcher" 'Native launcher'
verify_elf_arch "$runtime_java" 'Bundled Java launcher'
verify_elf_arch "$runtime_jvm" 'Bundled JVM'

echo "Verified $expected_name: metadata, installed files, and ELF architecture are correct."
