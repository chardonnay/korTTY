#!/usr/bin/env bash
# =============================================================================
# DEV-ONLY MLX runtime builder. NEVER SIGNED, NEVER SHIPPED.
#
# Assembles the korTTY MLX runtime package layout under build/mlx-runtime-dev/
# from a plain Python venv so the sidecar stack can be exercised locally
# without the CI-built relocatable CPython. The resulting venv is bound to
# this machine's Python installation and must be used in place; release
# packages come exclusively from .github/workflows/mlx-runtime.yml plus the
# human-approved signing pipeline.
#
# Usage:
#   scripts/build-mlx-runtime-local.sh
#
# Then point the app at the dev runtime, e.g.:
#   mkdir -p ~/.kortty/llm/mlx
#   ln -sfn "$(pwd)/build/mlx-runtime-dev" ~/.kortty/llm/mlx/runtime
# =============================================================================
set -euo pipefail

MLX_LM_VERSION="0.31.3"
INSTALLATION_ID="mlx-${MLX_LM_VERSION}-kortty1-macos-aarch64-dev"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "build-mlx-runtime-local.sh: MLX only exists on Apple silicon; refusing to build on $(uname -s)/$(uname -m)." >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAUNCHER_SOURCE="${REPO_ROOT}/src/main/resources/mlx/kortty_mlx_server.py"
OUTPUT_ROOT="${REPO_ROOT}/build/mlx-runtime-dev"
PACKAGE_DIRECTORY="${OUTPUT_ROOT}/packages/${INSTALLATION_ID}"

if [[ ! -f "${LAUNCHER_SOURCE}" ]]; then
  echo "build-mlx-runtime-local.sh: launcher not found at ${LAUNCHER_SOURCE}." >&2
  exit 1
fi

PYTHON3="$(command -v python3 || true)"
if [[ -z "${PYTHON3}" ]]; then
  echo "build-mlx-runtime-local.sh: no python3 on PATH; install Python 3.10 or newer first." >&2
  exit 1
fi
if ! "${PYTHON3}" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'; then
  echo "build-mlx-runtime-local.sh: $(${PYTHON3} --version 2>&1) is too old; Python 3.10 or newer is required." >&2
  exit 1
fi

echo "==> Building DEV MLX runtime ${INSTALLATION_ID}"
echo "    interpreter : ${PYTHON3}"
echo "    output      : ${OUTPUT_ROOT}"

rm -rf "${PACKAGE_DIRECTORY}"
mkdir -p "${PACKAGE_DIRECTORY}"

# The package's python/ directory is a plain venv here; the release pipeline
# uses a relocatable python-build-standalone CPython in the same location.
"${PYTHON3}" -m venv "${PACKAGE_DIRECTORY}/python"
"${PACKAGE_DIRECTORY}/python/bin/python3" -m pip install --quiet --upgrade pip
"${PACKAGE_DIRECTORY}/python/bin/python3" -m pip install --quiet "mlx-lm==${MLX_LM_VERSION}"
"${PACKAGE_DIRECTORY}/python/bin/python3" - <<PY
import mlx_lm
expected = "${MLX_LM_VERSION}"
actual = getattr(mlx_lm, "__version__", "unknown")
if actual != expected:
    raise SystemExit(f"pinned mlx-lm {expected} expected but {actual} was installed")
print(f"    mlx-lm      : {actual}")
PY

cp "${LAUNCHER_SOURCE}" "${PACKAGE_DIRECTORY}/kortty_mlx_server.py"
"${PACKAGE_DIRECTORY}/python/bin/python3" -m py_compile "${PACKAGE_DIRECTORY}/kortty_mlx_server.py"

printf '%s\n' "${INSTALLATION_ID}" > "${OUTPUT_ROOT}/active"

echo "==> DEV runtime ready (unsigned, machine-bound; do not distribute)."
echo "    activate with: ln -sfn '${OUTPUT_ROOT}' ~/.kortty/llm/mlx/runtime"
