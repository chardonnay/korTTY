#!/usr/bin/env python3
"""Verify the korTTY guide documents every user-facing setting and menu item.

Reads app-docs/doc-manifest.yaml and src/main/resources/i18n/messages_en.properties
and asserts every feature `settings.*` / `menu.*` key (after dropping chrome
suffixes and explicit ignores) is "owned" by exactly one EXISTING doc page via an
`owns_i18n` prefix.

  * ORPHAN key  → a setting/menu item with no documentation home (undocumented).
  * STALE prefix → an `owns_i18n` prefix that matches no current key (dead doc).
  * PENDING page → a manifest page whose file does not exist yet (work to do).

Exit codes:
  --strict : non-zero if any orphan key exists (the CI completeness gate).
  default  : always 0; prints the worklist (orphans/pending) for authors.

Requires PyYAML (ships with the MkDocs toolchain in .venv-docs). Run via that
interpreter, e.g. `.venv-docs/bin/python scripts/doc-coverage.py`.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:
    sys.exit("PyYAML is required. Run via .venv-docs/bin/python or `pip install pyyaml`.")

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "app-docs" / "doc-manifest.yaml"


def load_feature_keys(cfg: dict) -> list[str]:
    """The settings./menu. keys a page must document.

    Reads the union of `i18n_source` and the base bundle, for the reason spelled out in
    `load_all_keys`: `messages.properties` is the English source of truth and
    `messages_en.properties` only overlays part of it. Reading the overlay alone hid 53
    settings/menu keys (the File Browser view menu, the SFTP/Editor/Snippet Editor tabs,
    the master-password dialog, ...) from this gate while it still reported 100%.
    """
    prefixes = tuple(cfg.get("required_prefixes", []))
    ignore_suffixes = tuple(cfg.get("ignore_suffixes", []))
    ignore_keys = set(cfg.get("ignore_keys", []))
    keys: list[str] = []
    for src in _key_sources(cfg):
        for line in src.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key = line.split("=", 1)[0].strip()
            if not key.startswith(prefixes):
                continue
            if key.endswith(ignore_suffixes) or key in ignore_keys:
                continue
            keys.append(key)
    return sorted(set(keys))


def _key_sources(cfg: dict) -> list[Path]:
    """`i18n_source` plus the base bundle, in a stable order, skipping what does not exist."""
    candidates = [REPO_ROOT / cfg["i18n_source"],
                  REPO_ROOT / "src/main/resources/i18n/messages.properties"]
    seen: list[Path] = []
    for src in candidates:
        if src.exists() and src not in seen:
            seen.append(src)
    return seen


def load_all_keys(cfg: dict) -> set[str]:
    """Every declared i18n key — used only to check that an owned prefix matches something.

    Deliberately not `load_feature_keys`: that one is filtered to `required_prefixes`
    (settings./menu.), so validating owned prefixes against it reported every legitimate
    feature prefix (recording., tunnel., theme., project., ...) as stale by construction.

    Also reads the base bundle, not just `i18n_source`. `messages.properties` is the English
    source of truth — `ResourceBundle` falls back to it — while `messages_en.properties` is a
    partial overlay, so a few hundred keys exist solely in the base bundle.
    """
    keys: set[str] = set()
    for src in _key_sources(cfg):
        for line in src.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                keys.add(line.split("=", 1)[0].strip())
    return keys


def main() -> int:
    ap = argparse.ArgumentParser(description="korTTY docs settings/menu coverage gate.")
    ap.add_argument("--strict", action="store_true", help="non-zero exit on undocumented keys (CI)")
    args = ap.parse_args()

    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    feature_keys = load_feature_keys(manifest["coverage"])

    owned_prefixes: list[str] = []
    pending_pages: list[str] = []
    for page in manifest.get("pages", []):
        page_path = REPO_ROOT / page["path"]
        prefixes = page.get("owns_i18n") or []
        if not page_path.exists():
            if prefixes:
                pending_pages.append(f"{page['path']}  (owns {', '.join(prefixes)})")
            continue
        owned_prefixes.extend(prefixes)

    orphans = [k for k in feature_keys if not any(k.startswith(p) for p in owned_prefixes)]
    all_keys = load_all_keys(manifest["coverage"])
    stale = [p for p in sorted(set(owned_prefixes))
             if not any(k.startswith(p) for k in all_keys)]

    documented = len(feature_keys) - len(orphans)
    pct = (documented / len(feature_keys) * 100) if feature_keys else 100.0
    print(f"Coverage: {documented}/{len(feature_keys)} feature keys documented "
          f"({pct:.0f}%) across {len(owned_prefixes)} owned prefixes.")

    if pending_pages:
        print(f"\n{len(pending_pages)} page(s) still to author:")
        for p in pending_pages:
            print(f"  - {p}")
    if stale:
        print(f"\n{len(stale)} stale owns_i18n prefix(es) (match no current key):")
        for p in stale:
            print(f"  - {p}")
    if orphans:
        print(f"\n{len(orphans)} UNDOCUMENTED feature key(s) (no owning page):")
        for k in orphans:
            print(f"  - {k}")

    if args.strict and orphans:
        print(f"\nFAIL: {len(orphans)} settings/menu key(s) are undocumented.", file=sys.stderr)
        return 1
    if not orphans and not stale:
        print("\nAll settings/menu keys are documented.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
