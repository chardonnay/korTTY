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

Incremental on two levels: a source-hash cache (app-docs/site/.docs-translate-cache)
skips unchanged pages entirely, and within a changed page a per-line translation
memory reuses the existing German lines. The memory needs no extra state: the old
English source (git HEAD) is line-aligned with the committed German page —
translate_md preserves line counts — so only added/edited lines are sent to the
translator. Run via the docs venv:  .venv-docs/bin/python scripts/translate_docs.py

Usage:
  scripts/translate_docs.py            # translate changed pages, copy assets
  scripts/translate_docs.py --force    # re-translate everything
"""
from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
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
# Bump whenever masking/format-preservation rules change so cached pages are
# regenerated with the new rules instead of silently keeping stale Markdown.
TRANSLATION_FORMAT_VERSION = "11"

# Asset subtrees that are generated/staged elsewhere — never copy or translate.
SKIP_DIRS = {"diagrams", "screenshots"}

FENCE_RE = re.compile(r"^(\s*)(```|~~~)")
# Inline things to protect inside a prose line. Order matters (images before links).
INLINE_PATTERNS = [
    # Preserve line-level Markdown grammar while still translating its title/text.
    re.compile(r'^\s*!!!\s+[A-Za-z][\w-]*(?:\s+")?'),  # admonition type + opening quote
    re.compile(r'^\s*===\s+"'),                          # tab marker + opening quote
    re.compile(r'^\s*#{1,6}\s+'),                        # heading marker
    re.compile(r'^\s*(?:[-*+]|\d+[.)])\s+'),             # list marker
    re.compile(r'^\s*>\s+'),                              # blockquote marker
    re.compile(r"!\[[^\]]*\]\([^)]*\)"),   # ![alt](path) — whole image (alt rarely needs DE)
    re.compile(r"(?<!!)\[(?=[^\]\n]+\]\([^)]*\))"),  # opening [ of a normal Markdown link
    re.compile(r"\]\([^)]*\)"),            # the ](url) part of a link — keep the URL
    re.compile(r"`[^`]+`"),                # inline code
    # ++key++ and ++ctrl+shift+a++ keyboard keys. The inner alternation is required: a plain
    # [^+]+ cannot span the separating "+" of a chord, so every multi-key shortcut went to the
    # translator unmasked and came back localized ("++Strg+Umschalt+D++"), which the keys
    # extension does not render — 90 such chords across 15 pages before this was fixed.
    re.compile(r"\+\+[^+\s]+(?:\+[^+\s]+)*\+\+"),
    re.compile(r"<[^>]+>"),                # HTML tags
    re.compile(r"\{[^}]*\}"),              # {: attr } / { .class } attribute lists
    re.compile(r"\$\{[^}]+\}|\{\d+\}"),    # ${var} / {0} placeholders
    re.compile(r"\|"),                     # table cell boundaries
    re.compile(r'"$'),                      # closing admonition/tab title quote
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


TOKEN_RE = re.compile(r"(KTPH\d{3})")


def placeholders_intact(text: str, store: list[str], masked: str | None = None) -> bool:
    """Return whether protected tokens survived and Markdown grammar stayed ordered."""
    tokens = [f"KTPH{i:03d}" for i in range(len(store))]
    if any(text.count(token) != 1 for token in tokens):
        return False
    structural_indices = [
        i for i, fragment in enumerate(store)
        if fragment == "|"
        or fragment == "["
        or fragment == '"'
        or fragment.startswith("](")
        or fragment.startswith("{")
        or fragment.startswith("<")
        or re.match(r"^\s*(?:!!!|===|#{1,6}|>|[-*+]|\d+[.)])", fragment)
    ]
    positions = [text.index(tokens[i]) for i in structural_indices]
    if positions != sorted(positions):
        return False
    # Relative order is not enough: German word order routinely moves text ACROSS the final
    # token without disturbing any pair's order. Two ways that corrupts a line, so the check
    # is symmetric — whether the line ends in a token must be preserved, and so must which
    # token that is:
    #   * a table row's closing "|" swallowed into the cell ("... über `ffmpeg` | nach WebM
    #     exportieren") turns the trailing text into a phantom column;
    #   * inline code dragged to the end ("ob die Datei unter noch vorhanden ist `~/...`")
    #     leaves grammatically broken prose.
    # Failing here routes the line to translate_preserving_token_order, which translates only
    # the fragments between tokens and so cannot move one.
    if masked is not None:
        source_tail = masked.rstrip()
        found = TOKEN_RE.findall(source_tail)
        source_ends_with_token = bool(found) and source_tail.endswith(found[-1])
        target_tail = text.rstrip()
        target_found = TOKEN_RE.findall(target_tail)
        target_ends_with_token = bool(target_found) and target_tail.endswith(target_found[-1])
        if source_ends_with_token != target_ends_with_token:
            return False
        if source_ends_with_token and found[-1] != target_found[-1]:
            return False
    return True


def translate_preserving_token_order(masked: str, translator) -> str:
    """Fallback for providers that drop/reorder placeholder tokens.

    Translate only the prose fragments between tokens and then reassemble the
    original token order. The grammar can be slightly less fluid than a full-line
    translation, but the generated Markdown stays valid and no content vanishes.
    """
    translated_parts: list[str] = []
    for part in TOKEN_RE.split(masked):
        if not part or TOKEN_RE.fullmatch(part):
            translated_parts.append(part)
            continue
        leading = part[:len(part) - len(part.lstrip())]
        trailing = part[len(part.rstrip()):]
        core = part.strip()
        if not core or not re.search(r"[A-Za-z]", core):
            translated_parts.append(part)
            continue
        try:
            translated = translator.translate(core) or core
        except Exception:  # noqa: BLE001
            translated = core
        translated_parts.append(f"{leading}{translated}{trailing}")
    return "".join(translated_parts)


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
                    lines[i] = "title: \x03"  # sentinel; filled back after translate
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
    # A bare "Enabled" column label reads as a verb to MT ("Ermöglicht" = "enables"); as a
    # settings-table label it is the adjective. Scoped to the cell so prose is untouched.
    ("| Ermöglicht |", "| Aktiviert |"),
    # Product/technology names that MT tends to translate literally.
    ("Meerjungfrau", "Mermaid"),
    ("meerjungfrau", "Mermaid"),
    ("Knowledge Stores", "Wissensspeicher"),
    ("Knowledge Store", "Wissensspeicher"),
    ("Knowledge-Store", "Wissensspeicher"),
    ("eines Shops", "eines Wissensspeichers"),
    ("des Shops", "des Wissensspeichers"),
    ("Shops", "Wissensspeicher"),
    ("Shop", "Wissensspeicher"),
    ("Geschäfts", "Wissensspeichers"),
    ("Geschäfte", "Wissensspeicher"),
    ("Geschäft", "Wissensspeicher"),
    ("Stores", "Wissensspeicher"),
    ("Store", "Wissensspeicher"),
    ("HNSW-Diagramm", "HNSW-Graph"),
    ("Mehr laden", "Weitere laden"),
    ("**Laden…**", "**Wird ermittelt…**"),
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
    # "GitHub issue" is a proper term — MT renders it as Problem/Ausgabe.
    ("GitHub-Probleme", "GitHub-Issues"),
    ("GitHub-Problem", "GitHub-Issue"),
    ("Öffnen Sie eine neue Ausgabe", "Öffnen Sie ein neues Issue"),
    ("Öffnen Sie das Problem", "Öffnen Sie das Issue"),
    ("ein Problem zu eröffnen", "ein Issue zu eröffnen"),
    # "ASCII art" is a product term. MT renders it as "ASCII-Kunst" and, for the bare heading,
    # as "ASCII Art.-Nr" — reading "Artikelnummer", an article number.
    ("ASCII Art.-Nr", "ASCII-Art"),
    ("ASCII-Kunstbanner", "ASCII-Art-Banner"),
    ("ASCII-Kunst", "ASCII-Art"),
    ("ASCII Kunst", "ASCII-Art"),
]


def apply_glossary(text: str) -> str:
    for a, b in GLOSSARY_DE:
        text = text.replace(a, b)
    return text


# A heading marker is masked as a placeholder, and the translator is free to move a placeholder
# to wherever the target language wants that word. German fronts the noun phrase, so
# "### AI result tab features" came back as "Funktionen der ### AI-Ergebnisregisterkarte" — no
# longer a heading at all, just a line with hashes in the middle of it.
_LEADING_HEADING = re.compile(r"^(\s*)(#{1,6})\s")


def reanchor_leading_marker(translated: str, masked_source: str) -> str:
    """Pulls a heading marker back to the start of the line when translation displaced it."""
    source_match = _LEADING_HEADING.match(masked_source)
    if not source_match or _LEADING_HEADING.match(translated):
        return translated
    indent, marker = source_match.group(1), source_match.group(2)
    stripped = re.sub(rf"\s*{re.escape(marker)}\s*", " ", translated, count=1).strip()
    return f"{indent}{marker} {stripped}" if stripped else translated


def translate_md(md: str, translator, memory: dict[str, str] | None = None) -> tuple[str, int, int]:
    """Translate a page, reusing memory (masked EN line -> masked DE line) for
    unchanged lines. Returns (german_markdown, reused_lines, translated_lines)."""
    memory = dict(memory) if memory else {}
    lines, jobs = translatable_lines(md)
    if not jobs:
        return apply_glossary(md), 0, 0
    misses = [j for j in jobs if j[1] not in memory]
    texts = [j[1] for j in misses]
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
    for (_idx, masked, store), translated in zip(misses, out):
        translated = translated or ""
        if not placeholders_intact(translated, store, masked):
            translated = translate_preserving_token_order(masked, translator)
        memory[masked] = translated
    for idx, masked, store in jobs:
        translated = unmask(memory.get(masked, ""), store)
        if lines[idx] == "title: \x03":
            lines[idx] = f'title: {translated}'
        else:
            # The translation API strips leading whitespace from its output, but a
            # list item's indented continuation paragraph (e.g. the "grid cards"
            # layout on index.md) depends on that indent to stay nested under its
            # item — restore whatever indentation the original line had.
            # Capture the English line before it is overwritten: `masked` has already had its
            # heading marker replaced by a placeholder, so it cannot tell us the line was a heading.
            source_line = lines[idx]
            indent = source_line[:len(source_line) - len(source_line.lstrip(" "))]
            lines[idx] = indent + translated.lstrip(" ") if indent else translated
            lines[idx] = reanchor_leading_marker(lines[idx], source_line)
    return apply_glossary("\n".join(lines)), len(jobs) - len(misses), len(misses)


def remask(text: str, store: list[str]) -> str | None:
    """Reverse of unmask: put the KTPH tokens back into a translated line. Longer
    fragments first so a fragment that contains another does not get corrupted.
    Returns None when any fragment is missing (line cannot be safely reused)."""
    for i, frag in sorted(enumerate(store), key=lambda pair: -len(pair[1])):
        token = f"KTPH{i:03d}"
        if re.match(r"^\s*(?:!!!|===|#{1,6}|>|[-*+]|\d+[.)])", frag):
            # A list/admonition/tab/heading marker is valid only at the beginning.
            # Searching globally can mistake prose such as "API- or ..." for the
            # missing "- " list marker and incorrectly reuse broken Markdown.
            if not text.startswith(frag):
                return None
            text = token + text[len(frag):]
        elif frag == '"':
            if not text.endswith(frag):
                return None
            text = text[:-1] + token
        else:
            if frag not in text:
                return None
            text = text.replace(frag, token, 1)
    return text


def build_page_memory(old_en_md: str | None, de_md: str | None) -> dict[str, str]:
    """Line-aligns a previous English source with its generated German page into a
    translation memory (masked EN -> masked DE). translate_md preserves line counts,
    so index i of the German page is the translation of index i of the English page
    it was generated from; any mismatch disables reuse for safety."""
    if old_en_md is None or de_md is None:
        return {}
    en_lines = old_en_md.split("\n")
    de_lines = de_md.split("\n")
    if len(en_lines) != len(de_lines):
        return {}
    _lines, jobs = translatable_lines(old_en_md)
    memory: dict[str, str] = {}
    for idx, masked, store in jobs:
        de_line = de_lines[idx]
        if en_lines[idx].startswith("title:"):
            if not de_line.startswith("title:"):
                continue
            de_line = de_line[len("title:"):].strip()
        remasked = remask(de_line, store)
        # Validate reuse with the same predicate that gates a fresh translation. Without
        # this, a row damaged by an earlier run is keyed by its (unchanged) English line
        # and gets reused verbatim forever — the repaired rule would never reach it.
        if remasked is not None and placeholders_intact(remasked, store, masked):
            memory[masked] = remasked
    return memory


def git_head_version(path: Path) -> str | None:
    """The committed (HEAD) content of a repo file, or None if unavailable."""
    try:
        rel = path.relative_to(REPO).as_posix()
        result = subprocess.run(
            ["git", "-C", str(REPO), "show", f"HEAD:{rel}"],
            capture_output=True, text=True, timeout=10)
        return result.stdout if result.returncode == 0 else None
    except Exception:  # noqa: BLE001
        return None


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
    md_lines_fresh = md_lines_reused = 0
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
        digest = hashlib.sha256(
            TRANSLATION_FORMAT_VERSION.encode("ascii") + b"\0" + src.read_bytes()
        ).hexdigest()
        if not args.force and cache.get(str(rel)) == digest and dst.exists():
            md_skip += 1
            continue
        md = src.read_text(encoding="utf-8")
        # Line-level reuse: align the committed (pre-edit) English source with the
        # existing German page so only added/edited lines hit the translator.
        memory: dict[str, str] = {}
        if dst.exists():
            old_en = git_head_version(src)
            if old_en is None and cache.get(str(rel)) == digest:
                old_en = md  # unchanged page (e.g. --force run): current EN matches DE
            memory = build_page_memory(old_en, dst.read_text(encoding="utf-8"))
        translated, reused, fresh = translate_md(md, translator, memory)
        dst.write_text(translated, encoding="utf-8")
        new_cache[str(rel)] = digest
        md_done += 1
        md_lines_fresh += fresh
        md_lines_reused += reused
        print(f"  translated {rel} ({fresh} line(s) translated, {reused} reused)")

    save_cache(new_cache)
    print(f"\nDone. translated {md_done} page(s) ({md_lines_fresh} line(s) translated, "
          f"{md_lines_reused} reused), {md_skip} unchanged. "
          f"(assets are staged into docs/de by build-docs-site.py)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
