#!/usr/bin/env python3
"""Build the korTTY documentation site (MkDocs Material), fully self-contained.

Pipeline per language:
  1. Stage canonical visuals (app-docs/diagrams, app-docs/screenshots) into the
     MkDocs asset tree (docs/<lang>/assets/...). These copies are gitignored.
  2. Run `mkdocs build --strict` with KORTTY_VERSION injected from build.gradle.kts.
  3. Normalize generated text to LF so the committed offline guide is byte-for-byte
     reproducible on Windows, macOS, and Linux.
  4. Post-process: inline the vendored iframe-worker shim so the built site has
     ZERO runtime network dependencies and its offline search works from a
     file://  /  jar:  origin inside korTTY's WebView.
  5. Assert no external resource is actually fetched.

Output: build/guide/<lang>/ (consumed by Gradle staging into resources + GitHub Pages).

Usage:
  scripts/build-docs-site.py                # build every language that has content
  scripts/build-docs-site.py --lang en      # build only English
  scripts/build-docs-site.py --no-strict    # don't pass --strict (debugging)
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SITE_DIR = REPO_ROOT / "app-docs" / "site"
DIAGRAMS_SRC = REPO_ROOT / "app-docs" / "diagrams"
SCREENSHOTS_SRC = REPO_ROOT / "app-docs" / "screenshots"
SHIM = SITE_DIR / "vendor" / "iframe-worker-shim.js"
BUILD_OUT = REPO_ROOT / "build" / "guide"

# The exact tag the Material `offline` plugin injects; we replace it with an
# inline copy of the vendored shim so nothing is fetched from unpkg.com.
UNPKG_SHIM_TAG = '<script src="https://unpkg.com/iframe-worker/shim"></script>'

# Hosts allowed to appear as canonical/social LINKS (metadata, not fetched).
LANGS = ["en", "de"]

# Strip ANSI color codes from captured mkdocs output before filtering/printing.
_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def gradle_version() -> str:
    text = (REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'(?m)^version\s*=\s*"([^"]+)"', text)
    if not m:
        sys.exit("FATAL: version not found in build.gradle.kts")
    return m.group(1)


def stage_assets(lang: str) -> None:
    docs_assets = SITE_DIR / "docs" / lang / "assets"
    if DIAGRAMS_SRC.is_dir():
        dst = docs_assets / "diagrams"
        dst.mkdir(parents=True, exist_ok=True)
        for svg in DIAGRAMS_SRC.glob("*.svg"):
            shutil.copy2(svg, dst / svg.name)
    if SCREENSHOTS_SRC.is_dir():
        dst = docs_assets / "screenshots"
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(SCREENSHOTS_SRC, dst)
    # Generated languages mirror EN's committed assets (CSS, JS, logo image +
    # video, favicon) so they aren't duplicated in git — stage them from docs/en.
    if lang != "en":
        en_assets = SITE_DIR / "docs" / "en" / "assets"
        for sub in ("stylesheets", "javascripts", "images"):
            src = en_assets / sub
            if src.is_dir():
                dst = docs_assets / sub
                if dst.exists():
                    shutil.rmtree(dst)
                # The hero video is half a megabyte of incompressible bytes; translated
                # trees reference EN's copy relatively (../en/assets/…) instead of
                # shipping their own.
                shutil.copytree(src, dst,
                                ignore=shutil.ignore_patterns("kortty-logo.mp4"))


def lang_has_content(lang: str) -> bool:
    return (SITE_DIR / "docs" / lang / "index.md").is_file()


def build_lang(lang: str, strict: bool, version: str) -> Path:
    cfg = SITE_DIR / f"mkdocs.{lang}.yml"
    if not cfg.is_file():
        sys.exit(f"FATAL: {cfg} not found")
    stage_assets(lang)
    env = dict(os.environ, KORTTY_VERSION=version)
    cmd = [sys.executable, "-m", "mkdocs", "build", "-f", str(cfg)]
    if strict:
        cmd.append("--strict")
    print(f"\n=== mkdocs build [{lang}] (version {version}) ===")
    # Capture mkdocs output so we can drop the Material-for-MkDocs "MkDocs 2.0"
    # advocacy banner (a box-drawn block printed on every build) — it is not a
    # problem with this project and only clutters the Gradle/app build log. Real
    # INFO/WARNING/ERROR lines are kept.
    proc = subprocess.run(cmd, cwd=SITE_DIR, env=env, capture_output=True, text=True)
    for raw in (proc.stdout + proc.stderr).splitlines():
        clean = _ANSI_RE.sub("", raw)
        if "│" in clean or "Material for MkDocs" in clean or "MkDocs 2.0" in clean \
                or "squidfunk.github.io" in clean:
            continue
        print(clean)
    if proc.returncode != 0:
        raise subprocess.CalledProcessError(proc.returncode, cmd)
    out = BUILD_OUT / lang
    normalize_text_line_endings(out)
    inline_shim(out)
    assert_offline(out)
    extract_translation_manifests(out, lang)
    return out


def normalize_text_line_endings(out: Path) -> None:
    """Make generated text reproducible before byte-offset manifests are extracted.

    MkDocs and Python's text writers use the host newline convention.  Without this
    pass, a Windows build embeds CRLF in translation-manifest strings while CI on
    Linux embeds LF, leaving the committed guide permanently stale on one platform.
    """
    text_suffixes = {".css", ".html", ".js", ".json", ".svg", ".txt", ".xml"}
    normalized = 0
    for path in out.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in text_suffixes:
            continue
        raw = path.read_bytes()
        canonical = raw.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        if canonical != raw:
            path.write_bytes(canonical)
            normalized += 1
    print(f"  normalized LF line endings in {normalized} generated text file(s)")


def extract_translation_manifests(out: Path, lang: str) -> None:
    """Emit the runtime translation manifests for the English tree.

    Must run here, as the last step over the built HTML: the manifests record byte
    offsets into these exact pages, and stageGuideIntoResources deletes and re-copies
    src/main/resources/guide wholesale, so manifests generated anywhere else would be
    wiped by the next docs build. Only English is extracted — it is the translation
    source, and the German tree is itself generated.
    """
    if lang != "en":
        return
    script = REPO_ROOT / "scripts" / "extract_guide_segments.py"
    proc = subprocess.run(
        [sys.executable, str(script), "--guide-root", str(out)],
        capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stdout.write(proc.stdout)
        sys.stderr.write(proc.stderr)
        sys.exit(f"FATAL: {script.name} failed with exit code {proc.returncode}")
    summary = [line for line in proc.stdout.splitlines() if line.startswith(("54 ", "after dedup", "note:"))
               or " page(s), " in line]
    for line in summary:
        print(f"  {line}")


def inline_shim(out: Path) -> int:
    if not SHIM.is_file():
        sys.exit(f"FATAL: vendored shim missing at {SHIM} — run "
                 f"`curl -fsSL https://unpkg.com/iframe-worker/shim -o {SHIM}`")
    shim_js = SHIM.read_text(encoding="utf-8")
    inline_tag = f"<script>/* iframe-worker shim (vendored, offline) */\n{shim_js}</script>"
    patched = 0
    for html in out.rglob("*.html"):
        text = html.read_bytes().decode("utf-8")
        if UNPKG_SHIM_TAG in text:
            html.write_bytes(text.replace(UNPKG_SHIM_TAG, inline_tag).encode("utf-8"))
            patched += 1
    print(f"  inlined offline search shim into {patched} page(s)")
    return patched


def assert_offline(out: Path) -> None:
    """Fail if any HTML still fetches an external resource (<script>/<link>/@import)."""
    offenders: list[str] = []
    fetch_re = re.compile(r'<(?:script|link)[^>]*(?:src|href)="https?://[^"]+"')
    for html in out.rglob("*.html"):
        for m in fetch_re.finditer(html.read_text(encoding="utf-8")):
            tag = m.group(0)
            # canonical SEO links and social links are metadata, not fetches.
            if 'rel="canonical"' in tag or 'rel="alternate"' in tag:
                continue
            offenders.append(f"{html.relative_to(out)}: {tag[:90]}")
    if offenders:
        print("\nFATAL: built site still fetches external resources (not offline):",
              file=sys.stderr)
        print("\n".join(offenders[:20]), file=sys.stderr)
        sys.exit(1)
    print("  offline check passed: no external runtime fetches")


def main() -> int:
    ap = argparse.ArgumentParser(description="Build the korTTY docs site (offline).")
    ap.add_argument("--lang", choices=LANGS, help="build only this language")
    ap.add_argument("--no-strict", action="store_true", help="don't pass --strict")
    args = ap.parse_args()

    version = gradle_version()
    langs = [args.lang] if args.lang else [l for l in LANGS if lang_has_content(l)]
    if not langs:
        sys.exit("FATAL: no language has content (missing docs/<lang>/index.md)")

    built = [build_lang(l, not args.no_strict, version) for l in langs]
    print(f"\nDone. Built {', '.join(p.parent.name + '/' + p.name for p in built)} "
          f"under {BUILD_OUT}/.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
