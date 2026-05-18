#!/usr/bin/env bash
#
# Validate hand-maintained documentation SVG diagrams in app-docs/diagrams/.
#
# Usage:
#   ./scripts/validate-doc-svg.sh
#   ./scripts/validate-doc-svg.sh architecture connection-flow
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIAGRAM_DIR="$REPO_ROOT/app-docs/diagrams"

cd "$REPO_ROOT"

if ! command -v xmllint >/dev/null 2>&1; then
  echo "Error: xmllint not found. Install libxml2 tools and ensure xmllint is on PATH." >&2
  exit 1
fi

render_check_available=false
if command -v rsvg-convert >/dev/null 2>&1; then
  render_check_available=true
fi

validate_one() {
  local name="$1"
  local src="$DIAGRAM_DIR/${name}.svg"
  if [[ ! -f "$src" ]]; then
    echo "Error: $src not found." >&2
    return 1
  fi

  echo "Validating $name.svg..."
  xmllint --noout "$src"

  if [[ "$render_check_available" == "true" ]]; then
    rsvg-convert "$src" >/dev/null
  fi
}

if [[ $# -eq 0 ]] || [[ "${1:-}" == "all" ]]; then
  shopt -s nullglob
  svgs=("$DIAGRAM_DIR"/*.svg)
  if [[ ${#svgs[@]} -eq 0 ]]; then
    echo "Error: no SVG diagrams found in app-docs/diagrams/." >&2
    exit 1
  fi
  for svg in "${svgs[@]}"; do
    validate_one "$(basename "$svg" .svg)"
  done
else
  for name in "$@"; do
    validate_one "$name"
  done
fi

if [[ "$render_check_available" != "true" ]]; then
  echo "Warning: rsvg-convert not found; XML validation passed but render smoke test was skipped." >&2
fi

echo "Done. Documentation SVG diagrams are valid."
