#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 VERSION PKGREL ARCH LINUX_TARBALL OUTPUT_DIR" >&2
  exit 2
}

[[ $# -eq 5 ]] || usage
[[ $EUID -ne 0 ]] || { echo 'makepkg must run as an unprivileged user.' >&2; exit 1; }

version=$1
pkgrel=$2
target_arch=$3
linux_tarball=$4
output_dir=$5

[[ "$version" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]] || { echo "Invalid version: $version" >&2; exit 2; }
[[ "$pkgrel" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid pkgrel: $pkgrel" >&2; exit 2; }
case "$target_arch" in x86_64|aarch64) ;; *) echo "Unsupported pacman architecture: $target_arch" >&2; exit 2 ;; esac
[[ -f "$linux_tarball" ]] || { echo "Linux tarball not found: $linux_tarball" >&2; exit 1; }

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
pkgbuild_template="$repo_root/package/arch/PKGBUILD"

# Cross-packaging is safe only while package() copies a natively built app image. If a future
# PKGBUILD compiles or executes target code, AArch64 must move to a native Arch Linux ARM runner.
if grep -Eq '^[[:space:]]*(function[[:space:]]+)?(prepare|build|check)([[:space:]]*\([[:space:]]*\))?[[:space:]]*(\{|$)' "$pkgbuild_template"; then
  echo 'Cross-packaging guard: prepare(), build(), or check() exists in PKGBUILD.' >&2
  echo 'Use a native Arch Linux ARM builder before packaging AArch64.' >&2
  exit 1
fi

for command in makepkg bsdtar readelf; do
  command -v "$command" >/dev/null || { echo "Required command is missing: $command" >&2; exit 1; }
done

build_root=$(mktemp -d "${TMPDIR:-/tmp}/kortty-pacman-build.XXXXXX")
trap 'rm -rf "$build_root"' EXIT
mkdir -p "$build_root/src" "$output_dir"

expected_source="kortty-Linux-${version}-${target_arch}.tar.gz"
cp "$linux_tarball" "$build_root/src/$expected_source"
cp "$repo_root/package/arch/kortty.desktop" "$build_root/src/kortty.desktop"
cp "$repo_root/LICENSE" "$build_root/src/LICENSE"
sed \
  -e "s/VERSION_PLACEHOLDER/${version}/g" \
  -e "s/^pkgrel=.*/pkgrel=${pkgrel}/" \
  "$pkgbuild_template" > "$build_root/src/PKGBUILD"

makepkg_args=(--noconfirm --clean --cleanbuild)
if [[ "$target_arch" == aarch64 ]]; then
  makepkg_config="$build_root/makepkg-aarch64.conf"
  cp /etc/makepkg.conf "$makepkg_config"
  sed -E -i \
    -e "s|^[[:space:]]*CARCH=.*$|CARCH='aarch64'|" \
    -e "s|^[[:space:]]*CHOST=.*$|CHOST='aarch64-unknown-linux-gnu'|" \
    "$makepkg_config"
  grep -Fx "CARCH='aarch64'" "$makepkg_config" >/dev/null
  grep -Fx "CHOST='aarch64-unknown-linux-gnu'" "$makepkg_config" >/dev/null
  makepkg_args+=(--config "$makepkg_config")
fi

(
  cd "$build_root/src"
  makepkg "${makepkg_args[@]}"
)

expected_package="kortty-${version}-${pkgrel}-${target_arch}.pkg.tar.zst"
mapfile -t packages < <(find "$build_root/src" -maxdepth 1 -type f -name '*.pkg.tar.zst' -printf '%f\n' | sort)
[[ ${#packages[@]} -eq 1 && "${packages[0]}" == "$expected_package" ]] || {
  echo "Expected only $expected_package, produced: ${packages[*]:-(none)}" >&2
  exit 1
}

"$script_dir/verify-pacman-package.sh" \
  "$build_root/src/$expected_package" "$version" "$pkgrel" "$target_arch" \
  "$repo_root/LICENSE" "$repo_root/package/arch/kortty.desktop"
cp "$build_root/src/$expected_package" "$output_dir/$expected_package"

echo "Pacman package ready: $output_dir/$expected_package"
