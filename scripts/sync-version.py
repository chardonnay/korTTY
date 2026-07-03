#!/usr/bin/env python3
"""Single-source the korTTY version.

The authoritative version lives in build.gradle.kts (``version = "X.Y.Z"``).
Several other files historically hard-copy that string and drift out of sync.
This script propagates the canonical version to those files, or — with
``--check`` — verifies they all agree (used as a hard CI gate).

Targets kept in sync:
  * src/main/java/de/kortty/KorTTYApplication.java  (APP_VERSION constant)
  * README.adoc                                     (leading ``*vX.Y.Z*`` badge on the intro line)
  * app-docs/site/docs/en/about/release-notes.md    (top ``# vX.Y.Z`` heading, if present)
  * app-docs/RELEASE_NOTES.adoc                      (legacy top ``== vX.Y.Z`` heading, if present)
  * app-docs/USER_GUIDE.adoc                         (leading ``*vX.Y.Z*`` badge and trailing footer)
  * app-docs/doc-manifest.yaml                       (top-level ``version: "X.Y.Z"`` field)

The MkDocs site does NOT hard-code the version: it is injected at build time via
the ``KORTTY_VERSION`` env var (``extra.kortty_version: !ENV [KORTTY_VERSION, 'dev']``),
so there is nothing to rewrite there — only build.gradle.kts feeds it.

Usage:
  scripts/sync-version.py            # rewrite all targets to match build.gradle.kts
  scripts/sync-version.py --check    # verify only; non-zero exit + diff on mismatch (CI)
  scripts/sync-version.py --quiet    # rewrite, print only changes
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SEMVER = r"\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.\-]+)?"

GRADLE_FILE = REPO_ROOT / "build.gradle.kts"
GRADLE_VERSION_RE = re.compile(r'^version\s*=\s*"(' + SEMVER + r')"', re.MULTILINE)


@dataclass
class Target:
    """A file whose version literal must match the canonical Gradle version.

    ``pattern`` must contain exactly one capture group around the version digits.
    Only the captured span is rewritten, so surrounding/contextual mentions of a
    version (e.g. example commands) are never touched.
    """

    path: Path
    pattern: re.Pattern[str]
    label: str
    required: bool = True


TARGETS: list[Target] = [
    Target(
        path=REPO_ROOT / "src/main/java/de/kortty/KorTTYApplication.java",
        pattern=re.compile(r'(?m)^(\s*private\s+static\s+final\s+String\s+APP_VERSION\s*=\s*")'
                           r'(' + SEMVER + r')(";)'),
        label="KorTTYApplication.APP_VERSION",
    ),
    Target(
        # Leading badge on README intro line: `*v2.2.2* — ...`. Anchored to the
        # start of a line + the `*v...*` shape so other `v2.2.2` mentions are safe.
        path=REPO_ROOT / "README.adoc",
        pattern=re.compile(r'(?m)^(\*v)(' + SEMVER + r')(\*)'),
        label="README.adoc version badge",
    ),
    Target(
        path=REPO_ROOT / "app-docs/site/docs/en/about/release-notes.md",
        pattern=re.compile(r'(?m)^(##\s+v)(' + SEMVER + r')(\b)'),
        label="release-notes.md top heading",
        required=False,
    ),
    Target(
        path=REPO_ROOT / "app-docs/RELEASE_NOTES.adoc",
        pattern=re.compile(r'(?m)^(==\s+v)(' + SEMVER + r')(\b)'),
        label="RELEASE_NOTES.adoc top heading",
        required=False,
    ),
    Target(
        # Leading badge, same shape as the README badge: `*v2.2.2* | ...`.
        path=REPO_ROOT / "app-docs/USER_GUIDE.adoc",
        pattern=re.compile(r'(?m)^(\*v)(' + SEMVER + r')(\*)'),
        label="USER_GUIDE.adoc version badge",
    ),
    Target(
        # Trailing footer line: `_KorTTY v2.2.2_`.
        path=REPO_ROOT / "app-docs/USER_GUIDE.adoc",
        pattern=re.compile(r'(?m)^(_KorTTY\s+v)(' + SEMVER + r')(_)'),
        label="USER_GUIDE.adoc footer",
    ),
    Target(
        # Top-level `version: "X.Y.Z"` field consumed by the doc validators.
        path=REPO_ROOT / "app-docs/doc-manifest.yaml",
        pattern=re.compile(r'(?m)^(version:\s*")(' + SEMVER + r')(")'),
        label="doc-manifest.yaml version",
    ),
]


def read_gradle_version() -> str:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    m = GRADLE_VERSION_RE.search(text)
    if not m:
        sys.exit(f"FATAL: could not find `version = \"...\"` in {GRADLE_FILE}")
    return m.group(1)


def current_literal(target: Target) -> str | None:
    """Return the version literal currently in the file (first match), or None."""
    if not target.path.exists():
        return None
    m = target.pattern.search(target.path.read_text(encoding="utf-8"))
    return m.group(2) if m else None


def check() -> int:
    canonical = read_gradle_version()
    print(f"Canonical version (build.gradle.kts): {canonical}")
    failures: list[str] = []
    for t in TARGETS:
        if not t.path.exists():
            if t.required:
                failures.append(f"  MISSING  {t.label}: {t.path} not found")
            else:
                print(f"  skip     {t.label}: not present yet")
            continue
        found = current_literal(t)
        if found is None:
            if t.required:
                failures.append(f"  NO-MATCH {t.label}: version pattern not found in {t.path}")
            else:
                print(f"  skip     {t.label}: no version literal yet")
            continue
        if found != canonical:
            failures.append(f"  DRIFT    {t.label}: {found!r} != {canonical!r}  ({t.path})")
        else:
            print(f"  ok       {t.label}: {found}")
    if failures:
        print("\nVersion drift detected:", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        print("\nRun `scripts/sync-version.py` to fix.", file=sys.stderr)
        return 1
    print("\nAll version references are in sync.")
    return 0


def apply(quiet: bool) -> int:
    canonical = read_gradle_version()
    changed = 0
    for t in TARGETS:
        if not t.path.exists():
            if t.required:
                print(f"WARN: {t.label}: {t.path} not found — skipped")
            continue
        text = t.path.read_text(encoding="utf-8")
        new_text, n = t.pattern.subn(lambda m: m.group(1) + canonical + m.group(3), text, count=1)
        if n == 0:
            if t.required:
                print(f"WARN: {t.label}: version pattern not found — skipped")
            continue
        if new_text != text:
            t.path.write_text(new_text, encoding="utf-8")
            changed += 1
            print(f"updated  {t.label} -> {canonical}")
        elif not quiet:
            print(f"ok       {t.label}: already {canonical}")
    print(f"\nDone. {changed} file(s) updated to v{canonical}.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Sync/check the korTTY version across files.")
    ap.add_argument("--check", action="store_true",
                    help="verify only; non-zero exit on drift (CI mode)")
    ap.add_argument("--quiet", action="store_true", help="print only files that change")
    args = ap.parse_args()
    return check() if args.check else apply(args.quiet)


if __name__ == "__main__":
    raise SystemExit(main())
