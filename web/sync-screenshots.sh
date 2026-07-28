#!/usr/bin/env bash
# Re-copies the screenshots the site shows from the repo's documentation set.
# Run from the repo root after updating app-docs/screenshots.
set -euo pipefail
cd "$(dirname "$0")"
for f in ai/local-models.png ai/knowledge-stores.png ai/ai-manager.png \
         settings/ai-skills.png settings/themes.png settings/snippet-editor.png \
         settings/translation.png sftp/sftp-manager.png \
         tools/full-code-analysis.png tools/ascii-art.png; do
  mkdir -p "screenshots/$(dirname "$f")"
  cp "../app-docs/screenshots/$f" "screenshots/$f"
  echo "updated screenshots/$f"
done
