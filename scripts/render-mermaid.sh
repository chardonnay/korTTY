#!/usr/bin/env bash
#
# Render Mermaid diagrams (.mmd) to PNG and save them in docs/diagrams/.
# Requires Node.js and npx (mermaid-cli is used via npx, no global install needed).
#
# Usage:
#   ./scripts/render-mermaid.sh           # Render all .mmd files in docs/diagrams/
#   ./scripts/render-mermaid.sh all      # Same as above
#   ./scripts/render-mermaid.sh architecture connection-flow   # Render only these diagrams
#
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIAGRAM_DIR="$REPO_ROOT/docs/diagrams"
# Higher scale = sharper PNG (e.g. 3 or 4 for module/detail diagrams)
SCALE="${MERMAID_SCALE:-3}"

cd "$REPO_ROOT"

if ! command -v npx &>/dev/null; then
  echo "Error: npx not found. Install Node.js (https://nodejs.org) and ensure npx is on PATH." >&2
  exit 1
fi

render_one() {
  local name="$1"
  local src="$DIAGRAM_DIR/${name}.mmd"
  local dst="$DIAGRAM_DIR/${name}.png"
  if [[ ! -f "$src" ]]; then
    echo "Warning: $src not found, skipping." >&2
    return 1
  fi
  echo "Rendering $name..."
  npx -p @mermaid-js/mermaid-cli mmdc -i "$src" -o "$dst" -s "$SCALE"
  return 0
}

if [[ $# -eq 0 ]] || [[ "$1" == "all" ]]; then
  for mmd in "$DIAGRAM_DIR"/*.mmd; do
    [[ -f "$mmd" ]] || continue
    name="$(basename "$mmd" .mmd)"
    render_one "$name" || true
  done
else
  for name in "$@"; do
    render_one "$name" || true
  done
fi

echo "Done. PNGs are in docs/diagrams/"
