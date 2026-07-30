#!/usr/bin/env bash
# Re-copies the screenshots the site shows from the repo's documentation set.
# Run from the repo root after updating app-docs/screenshots.
#
# app-docs/screenshots is the single source of truth: full-window captures that
# include the native title bar, matching every other screenshot in the guide.
# The product site draws its OWN title bar (three dots + label) above each
# image, so a copied capture would show two — the per-file row counts below
# strip the native one off the top on the way over.
#
# Those crops used to be applied by hand to web/screenshots/ only, which made
# them silently reversible: this script overwrites its targets from app-docs, so
# the next sync restored the duplicated title bar. Cropping here instead keeps
# the result reproducible, and re-running is idempotent because every file is
# re-copied from the uncropped source first.
#
# The row count is per image because the captures were taken at different window
# scales, so the title bar is not the same height in each. To add an entry,
# measure it: the crop is correct when the site's own title bar sits directly on
# the app's menu bar / tab strip with no leftover native chrome between them.
set -euo pipefail
cd "$(dirname "$0")"

# "<path> <rows to remove from the top>" — 0 keeps the capture as-is.
SCREENSHOTS=(
  "ai/local-models.png 0"
  "ai/knowledge-stores.png 0"
  "ai/ai-manager.png 0"
  "settings/ai-skills.png 66"
  "settings/themes.png 35"
  "settings/snippet-editor.png 66"
  "settings/translation.png 40"
  "sftp/sftp-manager.png 66"
  "tools/full-code-analysis.png 66"
  "tools/ascii-art.png 52"
)

if ! command -v python3 >/dev/null 2>&1; then
  echo "sync-screenshots: python3 not found; it is required to crop the title bar." >&2
  exit 1
fi

copied=()
for entry in "${SCREENSHOTS[@]}"; do
  read -r f rows <<<"$entry"
  mkdir -p "screenshots/$(dirname "$f")"
  cp "../app-docs/screenshots/$f" "screenshots/$f"
  if [[ "$rows" -gt 0 ]]; then
    python3 ../scripts/crop-png-top.py "$rows" "screenshots/$f" >/dev/null
    echo "updated screenshots/$f (cropped ${rows}px)"
  else
    echo "updated screenshots/$f"
  fi
  # optimize-png.sh cd's to the repo root, so hand it repo-root-relative paths.
  copied+=("web/screenshots/$f")
done

# Always optimize the copies, for two reasons: the crop rewrites the image with
# flat filters and no quantization, and a source that was added without going
# through an optimization pass would otherwise be shipped at full size. The web
# copies are therefore not expected to be byte-identical to their sources.
../scripts/optimize-png.sh "${copied[@]}"
