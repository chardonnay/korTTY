#!/usr/bin/env bash
#
# Optimize documentation/app PNGs in place so regenerated screenshots don't bloat
# the bundled guide again (the guide ships inside the app jar).
#
#   - UI screenshots (*/screenshots/*): pngquant 8-bit quantization (visually
#     lossless for flat UI captures, typically -50%), skipped when it would grow.
#   - Everything else (logos, icons, previews): lossless only.
#   - A final lossless oxipng pass runs over all of them.
#
# Usage:
#   ./scripts/optimize-png.sh            # all default roots
#   ./scripts/optimize-png.sh <file...>  # specific PNGs (screenshot heuristic by path)
#
# Requires: oxipng, pngquant (brew install oxipng pngquant). Exits with a hint
# if missing rather than failing a larger workflow.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

for tool in oxipng pngquant; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "optimize-png: '$tool' not found (brew install oxipng pngquant) — skipping optimization." >&2
        exit 0
    fi
done

DEFAULT_ROOTS=(
    app-docs
    src/main/resources/guide
    src/main/resources/icon
    src/main/resources/previews
)

files=()
if [[ $# -gt 0 ]]; then
    files=("$@")
else
    while IFS= read -r -d '' f; do files+=("$f"); done \
        < <(find "${DEFAULT_ROOTS[@]}" -name '*.png' -print0 2>/dev/null)
fi

if [[ ${#files[@]} -eq 0 ]]; then
    echo "optimize-png: no PNGs found."
    exit 0
fi

before=$(du -ck "${files[@]}" | tail -1 | cut -f1)

screenshots=()
for f in "${files[@]}"; do
    [[ "$f" == *screenshots* ]] && screenshots+=("$f")
done
if [[ ${#screenshots[@]} -gt 0 ]]; then
    pngquant --quality 80-98 --speed 1 --skip-if-larger --strip --force --ext .png "${screenshots[@]}"
fi

oxipng -o 4 --strip safe -q "${files[@]}"

after=$(du -ck "${files[@]}" | tail -1 | cut -f1)
echo "optimize-png: ${#files[@]} files, ${before} KB -> ${after} KB"
