#!/usr/bin/env python3
"""Validate the korTTY doc manifest and its asset references.

Complements `mkdocs build --strict` (which already fails on broken in-page links
and images). This guards the manifest itself:

  * every page `path` is unique;
  * every `diagrams:` entry exists under app-docs/diagrams/;
  * every `screenshots:` entry exists under app-docs/screenshots/;
  * no two pages own the same `owns_i18n` prefix (ambiguous home);
  * (warn) canonical diagrams that no page references (orphan asset).

Exit non-zero on any hard error (always, so it is safe as a CI gate).
Requires PyYAML (run via .venv-docs/bin/python).
"""
from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:
    sys.exit("PyYAML is required. Run via .venv-docs/bin/python or `pip install pyyaml`.")

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "app-docs" / "doc-manifest.yaml"
DIAGRAMS_DIR = REPO_ROOT / "app-docs" / "diagrams"
SCREENSHOTS_DIR = REPO_ROOT / "app-docs" / "screenshots"


def main() -> int:
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    pages = manifest.get("pages", [])
    errors: list[str] = []
    warnings: list[str] = []

    seen_paths: set[str] = set()
    prefix_owner: dict[str, str] = {}
    referenced_diagrams: set[str] = set()

    for page in pages:
        path = page.get("path")
        if not path:
            errors.append("page with no `path`")
            continue
        if path in seen_paths:
            errors.append(f"duplicate page path: {path}")
        seen_paths.add(path)

        for prefix in page.get("owns_i18n") or []:
            if prefix in prefix_owner and prefix_owner[prefix] != path:
                errors.append(f"prefix {prefix!r} owned by both {prefix_owner[prefix]} and {path}")
            prefix_owner[prefix] = path

        for diagram in page.get("diagrams") or []:
            referenced_diagrams.add(diagram)
            if not (DIAGRAMS_DIR / diagram).exists():
                errors.append(f"{path}: diagram not found: app-docs/diagrams/{diagram}")

        for shot in page.get("screenshots") or []:
            if not (SCREENSHOTS_DIR / shot).exists():
                errors.append(f"{path}: screenshot not found: app-docs/screenshots/{shot}")

    # Orphan diagrams (present on disk, referenced by no page) — a soft warning.
    if DIAGRAMS_DIR.is_dir():
        for svg in sorted(DIAGRAMS_DIR.glob("*.svg")):
            if svg.name not in referenced_diagrams:
                warnings.append(f"diagram not referenced by any manifest page: {svg.name}")

    if warnings:
        print(f"{len(warnings)} warning(s):")
        for w in warnings:
            print(f"  - {w}")
    if errors:
        print(f"\n{len(errors)} ERROR(s):", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    print(f"\nManifest OK: {len(seen_paths)} pages, {len(prefix_owner)} owned prefixes, "
          f"{len(referenced_diagrams)} diagrams referenced.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
