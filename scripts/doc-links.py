#!/usr/bin/env python3
"""Validate the korTTY doc manifest and its asset references.

Complements `mkdocs build --strict`, which owns the link graph: it fails on broken
in-page links, missing images and — since `validation.links.anchors: warn` was added
to app-docs/site/mkdocs.yml — links to a heading anchor that does not exist. That
last one is deliberately NOT re-implemented here: MkDocs resolves links and collects
heading ids with the site's own extension set, and a second implementation of the
slug rules would only drift from it. Anchors broke silently in the German guide for
exactly that reason (see the anchor post-pass in scripts/translate_docs.py), and the
fix belongs in the build gate, not in a parallel checker.

This script guards the manifest itself:

  * every page `path` is unique;
  * every `diagrams:` entry exists under app-docs/diagrams/;
  * every `screenshots:` entry exists under app-docs/screenshots/;
  * every screenshot embedded in an English page is registered by some page;
  * no two pages own the same `owns_i18n` prefix (ambiguous home);
  * every `owns_code` path exists on disk;
  * every `last_synced_ref` resolves to a commit in the current history;
  * (warn) canonical diagrams that no page references (orphan asset);
  * (warn) screenshots on disk that no page registers (orphan asset).

The two drift-detection checks guard opposite failures, both silent. An `owns_code`
path that does not exist makes `git diff -- <path>` return nothing, so the page reads
as permanently CLEAN and is never reconciled — `first-launch.md` pointed at
`core/MasterPasswordManager.java`, which has only ever existed under `security/`.
A dead `last_synced_ref` makes the same command abort with "fatal: bad revision",
which is just as easily read as "nothing changed".

The `last_synced_ref` check exists because a dead ref silently disables the
`update-docs` skill: its dirty-page detection is `git diff <last_synced_ref>..HEAD`,
which aborts with "fatal: bad revision" and is easily read as "nothing changed".
Refs die when a docs sync records the branch HEAD and the PR is then squash-merged
under a new hash — see the skill's step 9, which records a merge-base for that reason.

Needs real history: the check is skipped with a warning on a shallow clone or
outside a git work tree, so CI must check out with `fetch-depth: 0` for it to bite.

Exit non-zero on any hard error (always, so it is safe as a CI gate).
Requires PyYAML (run via .venv-docs/bin/python).
"""
from __future__ import annotations

import re
import subprocess
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
DOCS_EN_DIR = REPO_ROOT / "app-docs" / "site" / "docs" / "en"
# Matches the catalog-relative part of any `assets/screenshots/<area>/<name>.png` reference,
# whatever the page's `../` depth. German pages are generated, so only English is scanned.
SCREENSHOT_REF = re.compile(r"assets/screenshots/([A-Za-z0-9][A-Za-z0-9._/-]*\.png)")


def _git(*args: str) -> tuple[int, str]:
    """Runs git in the repo; returns (returncode, stdout). Never raises."""
    try:
        done = subprocess.run(
            ["git", "-C", str(REPO_ROOT), *args],
            capture_output=True, text=True, timeout=30, check=False)
        return done.returncode, done.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return 1, ""


def _main_ref() -> str | None:
    """The mainline branch to measure ancestry against, or None when none is available."""
    for candidate in ("origin/main", "main", "origin/HEAD"):
        if _git("rev-parse", "--quiet", "--verify", f"{candidate}^{{commit}}")[0] == 0:
            return candidate
    return None


def _history_unavailable() -> str | None:
    """Why `last_synced_ref` cannot be verified here, or None when it can."""
    if _git("rev-parse", "--is-inside-work-tree")[1] != "true":
        return "not a git work tree"
    if _git("rev-parse", "--is-shallow-repository")[1] == "true":
        return "shallow clone (CI needs actions/checkout with fetch-depth: 0)"
    if _main_ref() is None:
        return "no main branch available to check ancestry against"
    return None


def main() -> int:
    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    pages = manifest.get("pages", [])
    errors: list[str] = []
    warnings: list[str] = []

    seen_paths: set[str] = set()
    prefix_owner: dict[str, str] = {}
    referenced_diagrams: set[str] = set()
    registered_screenshots: set[str] = set()

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
            registered_screenshots.add(shot)
            if not (SCREENSHOTS_DIR / shot).exists():
                errors.append(f"{path}: screenshot not found: app-docs/screenshots/{shot}")

        # A non-existent owns_code path makes the page's drift check permanently pass.
        for code_path in page.get("owns_code") or []:
            if not (REPO_ROOT / code_path).exists():
                errors.append(
                    f"{path}: owns_code path does not exist: {code_path} "
                    f"— the page's drift check would silently always pass")

    # Every last_synced_ref must name a commit that still exists. Distinct refs are
    # checked once and mapped back to their pages, since many pages share a ref.
    refs_to_pages: dict[str, list[str]] = {}
    for page in pages:
        ref = page.get("last_synced_ref")
        if ref and page.get("path"):
            refs_to_pages.setdefault(str(ref), []).append(page["path"])

    skip_reason = _history_unavailable()
    if skip_reason:
        warnings.append(
            f"last_synced_ref not verified for {len(refs_to_pages)} ref(s): {skip_reason}")
    else:
        main_ref = _main_ref()
        for ref in sorted(refs_to_pages):
            owners = refs_to_pages[ref]
            shown = ", ".join(owners[:3]) + (f" (+{len(owners) - 3} more)" if len(owners) > 3 else "")
            if _git("rev-parse", "--quiet", "--verify", f"{ref}^{{commit}}")[0] != 0:
                errors.append(
                    f"last_synced_ref {ref!r} does not resolve to a commit "
                    f"— used by {len(owners)} page(s): {shown}")
            elif _git("merge-base", "--is-ancestor", ref, main_ref)[0] != 0:
                # Resolving is not enough: a commit that lives only on an unmerged branch is in the
                # local object store but not in the project's history. It passes `rev-parse --verify`
                # on the machine that has that branch and fails on a fresh CI clone — and it dies for
                # good once the branch is squash-merged or deleted, which is the failure this whole
                # check exists to prevent.
                errors.append(
                    f"last_synced_ref {ref!r} resolves but is not an ancestor of {main_ref} "
                    f"— it exists only on an unmerged branch and will not survive there "
                    f"— used by {len(owners)} page(s): {shown}")

    # A screenshot embedded in a page but registered by none is invisible to every staleness
    # check: `update-docs` looks at the registering page's `screenshots:` list, so an
    # unregistered image is never reviewed and quietly keeps showing an older UI. That is how
    # ai/ai-profiles.png aged unnoticed while being embedded in reference/settings/ai.md.
    if DOCS_EN_DIR.is_dir():
        for md in sorted(DOCS_EN_DIR.rglob("*.md")):
            page_rel = md.relative_to(REPO_ROOT)
            for ref in sorted(set(SCREENSHOT_REF.findall(md.read_text(encoding="utf-8")))):
                if not (SCREENSHOTS_DIR / ref).exists():
                    errors.append(f"{page_rel}: embeds a missing screenshot: {ref}")
                elif ref not in registered_screenshots:
                    errors.append(
                        f"{page_rel}: embeds {ref}, which no manifest page registers "
                        f"— add it to a page's `screenshots:` list or it is never checked "
                        f"for staleness")

    # Orphan diagrams (present on disk, referenced by no page) — a soft warning.
    if DIAGRAMS_DIR.is_dir():
        for svg in sorted(DIAGRAMS_DIR.glob("*.svg")):
            if svg.name not in referenced_diagrams:
                warnings.append(f"diagram not referenced by any manifest page: {svg.name}")

    # Orphan screenshots (on disk, registered by no page) — a soft warning: an unused capture
    # still ships inside the guide bundle, and a used-but-unregistered one is the error above.
    if SCREENSHOTS_DIR.is_dir():
        for png in sorted(SCREENSHOTS_DIR.rglob("*.png")):
            rel = png.relative_to(SCREENSHOTS_DIR).as_posix()
            if rel not in registered_screenshots:
                warnings.append(f"screenshot not registered by any manifest page: {rel}")

    if warnings:
        print(f"{len(warnings)} warning(s):")
        for w in warnings:
            print(f"  - {w}")
    if errors:
        print(f"\n{len(errors)} ERROR(s):", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    refs_note = "unverified" if skip_reason else "verified"
    print(f"\nManifest OK: {len(seen_paths)} pages, {len(prefix_owner)} owned prefixes, "
          f"{len(referenced_diagrams)} diagrams referenced, "
          f"{len(refs_to_pages)} sync ref(s) {refs_note}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
