# korTTY MLX runtime package

korTTY runs Apple-MLX models through the official Python `mlx_lm.server`, wrapped by
`src/main/resources/mlx/kortty_mlx_server.py` because upstream ships **no authentication** and no
idle shutdown. The launcher enforces a per-session Bearer token (constant-time compare), keeps
`GET /health` open for readiness probing, refuses to start against anything but the pinned
`mlx-lm` version, and self-exits after the idle window configured per model.

The runtime package that korTTY installs and activates on user machines consists of:

```
packages/<installation-id>/
├── python/                 # pinned relocatable CPython (python-build-standalone)
│   └── bin/python3         # plus the hash-locked wheelhouse in its site-packages
└── kortty_mlx_server.py    # korTTY's authenticated launcher
```

`de.kortty.ai.mlx.MlxRuntimeLocator` resolves the active installation through the
`<llmDir>/mlx/runtime/active` pointer file. macOS arm64 (macOS 14 or newer) is the only supported
platform; MLX does not exist anywhere else.

## Dependency pinning and lock flow

- `requirements.in` pins exactly one direct dependency: `mlx-lm==0.31.3`. The same version is
  enforced at start time by `kortty_mlx_server.py` (`EXPECTED_MLX_LM_VERSION`).
- `requirements.lock` is generated **in CI** (`.github/workflows/mlx-runtime.yml`) on the
  macOS 14 arm64 builder via:

  ```
  uv pip compile --generate-hashes --python-platform aarch64-apple-darwin \
      --python-version 3.12 mlx-runtime/requirements.in -o requirements.lock
  ```

  The committed `requirements.lock` is authoritative: when it exists, CI installs strictly from it
  with `uv pip install --require-hashes --no-build` (wheels only, no sdist builds, no unpinned
  resolution). The generate step only bootstraps a first lock or produces a review artifact for a
  dependency-update PR; a human must review and commit that artifact before any stable promotion.

## Build and publication

- CI (`mlx-runtime.yml`) downloads a pinned python-build-standalone CPython (URL and SHA-256 are
  pinned together at the top of the workflow), installs the hash-locked wheelhouse into it, stages
  the launcher, runs an authenticated API smoke against a small real MLX model, and uploads the
  candidate `tar.zst` plus its SHA-256 as workflow artifacts.
- Candidate packages are never signed in this repository. Human-approved signing and immutable
  stable publication run in `chardonnay/kortty-llama-runtimes`, which publishes the MLX channel as
  a separate `mlx-stable` index signed with the same Ed25519 release key as the llama.cpp channel.
- For local development only, `scripts/build-mlx-runtime-local.sh` assembles an unsigned package
  layout from a plain venv under `build/mlx-runtime-dev/`. Dev packages must never be shipped.
