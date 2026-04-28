#!/usr/bin/env bash
#
# Render PlantUML diagrams (.puml) to PNG and save them in docs/diagrams/.
#
# Usage:
#   ./scripts/render-plantuml.sh
#   ./scripts/render-plantuml.sh all
#   ./scripts/render-plantuml.sh architecture connection-flow
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DIAGRAM_DIR="$REPO_ROOT/docs/diagrams"
PLANTUML_VERSION="${PLANTUML_VERSION:-1.2026.2}"
PLANTUML_CACHE_DIR="${PLANTUML_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/kortty/plantuml}"
PLANTUML_JAR="$PLANTUML_CACHE_DIR/plantuml-$PLANTUML_VERSION.jar"
PLANTUML_BASE_URL="https://repo1.maven.org/maven2/net/sourceforge/plantuml/plantuml/$PLANTUML_VERSION"
PLANTUML_JAR_URL="$PLANTUML_BASE_URL/plantuml-$PLANTUML_VERSION.jar"
PLANTUML_SHA1_URL="$PLANTUML_JAR_URL.sha1"

cd "$REPO_ROOT"

if ! command -v java >/dev/null 2>&1; then
  echo "Error: java not found. Install Java and ensure it is on PATH." >&2
  exit 1
fi

if ! command -v dot >/dev/null 2>&1; then
  echo "Error: Graphviz dot not found. Install Graphviz and ensure dot is on PATH." >&2
  exit 1
fi

download_plantuml() {
  mkdir -p "$PLANTUML_CACHE_DIR"
  local sha_file="$PLANTUML_JAR.sha1"

  if [[ ! -f "$PLANTUML_JAR" ]]; then
    echo "Downloading PlantUML $PLANTUML_VERSION..."
    curl -fsSL "$PLANTUML_JAR_URL" -o "$PLANTUML_JAR"
  fi

  curl -fsSL "$PLANTUML_SHA1_URL" -o "$sha_file"
  local expected actual
  expected="$(awk '{print $1}' "$sha_file")"
  actual="$(shasum -a 1 "$PLANTUML_JAR" | awk '{print $1}')"
  if [[ "$expected" != "$actual" ]]; then
    rm -f "$PLANTUML_JAR"
    echo "Error: PlantUML checksum mismatch for $PLANTUML_JAR" >&2
    exit 1
  fi
}

render_one() {
  local name="$1"
  local src="$DIAGRAM_DIR/${name}.puml"
  if [[ ! -f "$src" ]]; then
    echo "Warning: $src not found, skipping." >&2
    return 1
  fi
  echo "Rendering $name..."
  java -jar "$PLANTUML_JAR" -tpng "$src"
  return 0
}

download_plantuml

if [[ $# -eq 0 ]] || [[ "${1:-}" == "all" ]]; then
  for puml in "$DIAGRAM_DIR"/*.puml; do
    [[ -f "$puml" ]] || continue
    name="$(basename "$puml" .puml)"
    render_one "$name"
  done
else
  for name in "$@"; do
    render_one "$name"
  done
fi

echo "Done. PNGs are in docs/diagrams/"
