#!/usr/bin/env python3
"""Generate the German guide (docs/de) from the English source (docs/en).

English (docs/en) is the source of truth; German is generated — never hand-edit
docs/de. Mirrors the approach of scripts/translate_i18n.py (deep_translator
GoogleTranslator + placeholder masking), extended for Markdown: fenced code
blocks, inline code, link/image targets, HTML tags, attribute lists, keyboard
keys and YAML front-matter keys are masked so only prose (and link text / alt
text / table cells / admonition titles / the front-matter title value) is
translated. Asset files (CSS, images, the logo video) are copied verbatim;
diagrams/screenshots are staged per-language by scripts/build-docs-site.py.

Incremental: a source-hash cache (app-docs/site/.docs-translate-cache) skips
unchanged pages. Run via the docs venv:  .venv-docs/bin/python scripts/translate_docs.py

Usage:
  scripts/translate_docs.py            # translate changed pages, copy assets
  scripts/translate_docs.py --force    # re-translate everything
"""
from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import sys
import time
from pathlib import Path

try:
    from deep_translator import GoogleTranslator
    from deep_translator.exceptions import BaseError, RequestError, TooManyRequests
except ImportError:
    sys.exit("Install: pip install deep-translator")

REPO = Path(__file__).resolve().parent.parent
SITE = REPO / "app-docs" / "site"
EN = SITE / "docs" / "en"
DE = SITE / "docs" / "de"
CACHE = SITE / ".docs-translate-cache"
TARGET = "de"

# Asset subtrees that are generated/staged elsewhere — never copy or translate.
SKIP_DIRS = {"diagrams", "screenshots"}

FENCE_RE = re.compile(r"^(\s*)(```|~~~)")
# Inline things to protect inside a prose line. Order matters (images before links).
INLINE_PATTERNS = [
    re.compile(r"!\[[^\]]*\]\([^)]*\)"),   # ![alt](path) — whole image (alt rarely needs DE)
    re.compile(r"\]\([^)]*\)"),            # the ](url) part of a link — keep the URL
    re.compile(r"`[^`]+`"),                # inline code
    re.compile(r"\+\+[^+]+\+\+"),          # ++key++ keyboard
    re.compile(r"<[^>]+>"),                # HTML tags
    re.compile(r"\{[^}]*\}"),              # {: attr } / { .class } attribute lists
    re.compile(r"\$\{[^}]+\}|\{\d+\}"),    # ${var} / {0} placeholders
]


def mask(text: str):
    store: list[str] = []

    def repl(m):
        store.append(m.group(0))
        return f"KTPH{len(store) - 1:03d}"

    for pat in INLINE_PATTERNS:
        text = pat.sub(repl, text)
    return text, store


def unmask(text: str, store: list[str]) -> str:
    for i, frag in enumerate(store):
        text = text.replace(f"KTPH{i:03d}", frag)
    return text


def translatable_lines(md: str) -> tuple[list[str], list[tuple[int, str, list[str]]]]:
    """Split markdown into lines; return (lines, jobs) where jobs are
    (line_index, masked_text, store) for lines whose prose should be translated."""
    lines = md.split("\n")
    jobs: list[tuple[int, str, list[str]]] = []
    in_fence = False
    in_frontmatter = False
    for i, line in enumerate(lines):
        if i == 0 and line.strip() == "---":
            in_frontmatter = True
            continue
        if in_frontmatter:
            if line.strip() == "---":
                in_frontmatter = False
            elif line.startswith("title:"):
                val = line[len("title:"):].strip().strip('"')
                if val:
                    masked, store = mask(val)
                    jobs.append((i, masked, store))
                    lines[i] = "title: "  # sentinel; filled back after translate
            continue
        if FENCE_RE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        stripped = line.strip()
        if not stripped:
            continue
        # Skip pure-structure lines (table separators, hr, list bullets only)
        if re.fullmatch(r"[|:\-\s]+", stripped) or stripped in {"---", "***"}:
            continue
        masked, store = mask(line)
        # Nothing translatable left (e.g. a line that was all code/links)
        if not masked.strip().strip("#>*-|+ "):
            continue
        jobs.append((i, masked, store))
    return lines, jobs


# Normalize German renderings of "guide/manual" to the product's canonical term
# ("Anleitung", matching the app's menu.help.guide=Anleitung) so the DE site never
# drifts to Handbuch/Leitfaden/Manual. Applied after translation.
GLOSSARY_DE = [
    ("korTTY Guide", "korTTY Anleitung"),
    ("Bedienungsanleitung", "Anleitung"),
    ("Benutzerhandbuch", "Anleitung"),
    ("Handbuch", "Anleitung"),
    ("handbuch", "Anleitung"),
    ("Leitfaden", "Anleitung"),
    ("→ Manual", "→ Anleitung"),
    ("Help → Anleitung", "Hilfe → Anleitung"),
    ("Hilfe → Manual", "Hilfe → Anleitung"),
    ("das Anleitung", "die Anleitung"),
    ("Das Anleitung", "Die Anleitung"),
    ("dieses Anleitung", "diese Anleitung"),
    ("Dieses Anleitung", "Diese Anleitung"),
    ("ein Anleitung", "eine Anleitung"),
    ("des Anleitung", "der Anleitung"),
    ("dem Anleitung", "der Anleitung"),
]


def apply_glossary(text: str) -> str:
    for a, b in GLOSSARY_DE:
        text = text.replace(a, b)
    return text


def translate_md(md: str, translator) -> str:
    lines, jobs = translatable_lines(md)
    if not jobs:
        return apply_glossary(md)
    texts = [j[1] for j in jobs]
    out: list[str] = []
    B = 20
    for k in range(0, len(texts), B):
        chunk = texts[k:k + B]
        res = None
        try:
            res = translator.translate_batch(chunk)
            if not isinstance(res, (list, tuple)) or len(res) != len(chunk):
                res = None
        except (BaseError, RequestError, TooManyRequests, Exception):  # noqa: BLE001
            res = None
        if res is None:
            # A single untranslatable string aborts the whole batch — fall back to
            # per-item translation, keeping the English original where Google fails
            # (better an English phrase than a missing page).
            res = []
            for item in chunk:
                try:
                    r = translator.translate(item)
                    res.append(r if r else item)
                except Exception:  # noqa: BLE001
                    res.append(item)
                time.sleep(0.2)
        out.extend(res)
        if k + B < len(texts):
            time.sleep(0.4)
    for (idx, _masked, store), translated in zip(jobs, out):
        translated = unmask(translated or "", store)
        if lines[idx] == "title: ":
            lines[idx] = f'title: {translated}'
        else:
            lines[idx] = translated
    return apply_glossary("\n".join(lines))


def load_cache() -> dict[str, str]:
    if not CACHE.exists():
        return {}
    out = {}
    for line in CACHE.read_text(encoding="utf-8").splitlines():
        if "\t" in line:
            h, rel = line.split("\t", 1)
            out[rel] = h
    return out


def save_cache(cache: dict[str, str]) -> None:
    CACHE.write_text("".join(f"{h}\t{rel}\n" for rel, h in sorted(cache.items())), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate docs/de from docs/en.")
    ap.add_argument("--force", action="store_true", help="re-translate all pages")
    args = ap.parse_args()

    if not EN.is_dir():
        sys.exit(f"missing {EN}")
    cache = {} if args.force else load_cache()
    new_cache = dict(cache)
    translator = GoogleTranslator(source="en", target=TARGET)

    md_done = md_skip = copied = 0
    for src in sorted(EN.rglob("*")):
        if src.is_dir():
            continue
        rel = src.relative_to(EN)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        dst = DE / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        if src.suffix != ".md":
            # Assets (CSS, images, video) are staged into docs/de by
            # scripts/build-docs-site.py — don't copy/translate them here.
            continue
        digest = hashlib.sha256(src.read_bytes()).hexdigest()
        if not args.force and cache.get(str(rel)) == digest and dst.exists():
            md_skip += 1
            continue
        md = src.read_text(encoding="utf-8")
        dst.write_text(translate_md(md, translator), encoding="utf-8")
        new_cache[str(rel)] = digest
        md_done += 1
        print(f"  translated {rel}")

    save_cache(new_cache)
    print(f"\nDone. translated {md_done} page(s), {md_skip} unchanged. "
          f"(assets are staged into docs/de by build-docs-site.py)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
