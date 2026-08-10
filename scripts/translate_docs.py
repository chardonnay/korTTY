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

Translating a heading changes the anchor MkDocs derives from it, so a final pass
repoints every `](page.md#anchor)` link at the translated heading (see "Anchor
synchronisation" below); `validation.links.anchors: warn` in mkdocs.yml fails the
build if one is ever missed.

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
import importlib
import json
import posixpath
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

try:
    import markdown
    import yaml
except ImportError:
    sys.exit("Install: pip install markdown pyyaml")

REPO = Path(__file__).resolve().parent.parent
SITE = REPO / "app-docs" / "site"
EN = SITE / "docs" / "en"
DE = SITE / "docs" / "de"
CACHE = SITE / ".docs-translate-cache"
TARGET = "de"
# Bump whenever masking/format-preservation rules change so cached pages are
# regenerated with the new rules instead of silently keeping stale Markdown.
TRANSLATION_FORMAT_VERSION = "13"

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
    # Inline code, including the CommonMark double-backtick form that shows a literal
    # backtick (`` ` ``). The old `[^`]+` mis-paired those doubled delimiters: on the
    # metacharacter list "(`$`, `\`, `<`, `>`)" it masked the ", " runs BETWEEN the code
    # spans, left < and > bare for the HTML-tag rule below, and that rule then stored
    # "<KTPH007>" as a fragment — a placeholder nested inside a store entry, which
    # unmasking re-emitted as literal token text on the input-hardening page.
    re.compile(r"(?<!`)(`+)(.+?)(?<!`)\1(?!`)"),
    # :material-rocket-launch: / :octicons-arrow-right-24: PyMdown emoji/icon shortcodes. Left
    # unmasked, the translator "helpfully" translated the identifier itself
    # (":material-rocket-launch:" -> ":material-raketenstart:"), which the icon font does not
    # recognize, so it fell back to rendering the raw colon-wrapped text instead of the icon.
    re.compile(r":[a-z0-9_+-]+:"),
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
    # Highest token first: a later pattern can match across an already-masked token (an
    # HTML-tag match spanning a code span), so its fragment embeds that token. Ascending
    # order restores the outer fragment after its embedded token's pass has already run,
    # leaving the token literal in the page; descending order restores outer fragments
    # first and their embedded tokens on a later step.
    for i in range(len(store) - 1, -1, -1):
        text = text.replace(f"KTPH{i:03d}", store[i])
    return text


TOKEN_RE = re.compile(r"(KTPH\d{3})")


def placeholders_intact(text: str, store: list[str], masked: str | None = None) -> bool:
    """Return whether protected tokens survived and Markdown grammar stayed ordered."""
    tokens = [f"KTPH{i:03d}" for i in range(len(store))]
    # Count ALL token-shaped strings, not just the expected ones: a translator that
    # invents or duplicates a KTPH token would pass the per-token check below, and the
    # invented token would survive unmask into the generated page.
    if len(TOKEN_RE.findall(text)) != len(tokens):
        return False
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
#
# The table itself lives in src/main/resources/i18n/glossary/<lang>.json because the
# runtime HTML translator (de.kortty.core.TranslationGlossary) needs exactly the same
# corrections — a second copy here would drift, and each pipeline would still look
# correct on its own while the app and the website disagreed on the product's own words.
GLOSSARY_PATH = REPO / "src" / "main" / "resources" / "i18n" / "glossary" / f"{TARGET}.json"


def load_glossary(scope: str = "markdown") -> list[tuple[str, str, bool]]:
    """Ordered (from, to, exact) rows for this scope. Order is load-bearing: a longer term
    must precede any shorter one it contains."""
    if not GLOSSARY_PATH.is_file():
        return []
    data = json.loads(GLOSSARY_PATH.read_text(encoding="utf-8"))
    rows: list[tuple[str, str, bool]] = []
    for row in data.get("replacements", []):
        if "from" not in row or "to" not in row:
            continue
        row_scope = row.get("scope", "any")
        if row_scope not in ("any", scope):
            continue
        rows.append((row["from"], row["to"], row.get("match") == "exact"))
    return rows


_GLOSSARY: list[tuple[str, str, bool]] | None = None


def apply_glossary(text: str) -> str:
    global _GLOSSARY
    if _GLOSSARY is None:
        _GLOSSARY = load_glossary("markdown")
    for source, target, exact in _GLOSSARY:
        if exact:
            if text.strip() == source:
                text = text.replace(source, target, 1)
        else:
            text = text.replace(source, target)
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


def translate_md(
    md: str, translator, memory: dict[str, str] | None = None
) -> tuple[str, int, int, list[str]]:
    """Translate a page, reusing memory (masked EN line -> masked DE line) for
    unchanged lines. Returns (german_markdown, reused_lines, translated_lines,
    still_english) — the last being the masked source text of every line that
    kept its English wording after translation genuinely failed (as opposed to
    a line that is legitimately identical, e.g. a bare product name)."""
    memory = dict(memory) if memory else {}
    lines, jobs = translatable_lines(md)
    if not jobs:
        return apply_glossary(md), 0, 0, []
    misses = [j for j in jobs if j[1] not in memory]
    texts = [j[1] for j in misses]
    out: list[str] = []
    failed: list[str] = []
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
            # (better an English phrase than a missing page). One retry after a
            # backoff before giving up: most single-item failures here are
            # transient (rate limiting), not a string Google truly cannot handle.
            res = []
            for item in chunk:
                r = None
                for attempt in range(2):
                    try:
                        r = translator.translate(item)
                        if r:
                            break
                    except Exception:  # noqa: BLE001
                        r = None
                    if attempt == 0:
                        time.sleep(1.0)
                if r:
                    res.append(r)
                else:
                    res.append(item)
                    failed.append(item)
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
        if "KTPH" in translated:
            # Belt and braces: a mask token (or a deformed remnant of one) must never
            # ship in a generated page, no matter which upstream path produced it —
            # a translator deformation, a stale memory line, or a future masking bug.
            # The fragment-wise fallback cannot move or invent tokens; if a remnant
            # survives even that, keep the English line and report the failure.
            translated = unmask(translate_preserving_token_order(masked, translator), store)
            if "KTPH" in translated:
                translated = unmask(masked, store)
                failed.append(masked)
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
    return apply_glossary("\n".join(lines)), len(jobs) - len(misses), len(misses), failed


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
    it was generated from; any mismatch disables reuse for safety.

    A line carrying an anchored link is the one case that never reuses: sync_anchors
    rewrote the German `](page.md#anchor)` to the translated slug, so the English
    fragment is no longer found and remask returns None. That costs one translation
    call per such line on a changed page (~30 in the whole corpus) and is harmless —
    the fresh translation re-emits the English anchor and sync_anchors repoints it
    again at the end of the run."""
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


# ---------------------------------------------------------------------------
# Anchor synchronisation
#
# Headings are translated, so MkDocs derives a GERMAN id from them
# ("## Exporting" -> #exporting becomes "## Exportieren" -> #exportieren). The
# `](page.md#anchor)` half of a link is masked as a placeholder and therefore
# survives translation with its ENGLISH slug, which then resolves to nothing and
# silently drops the reader at the top of the page. 17 links were broken this way.
#
# Fixed by a post-pass over the whole generated tree: pair each page's headings
# with their translations by document order, then rewrite every link anchor
# through that map. It runs over every page, not only the ones translated in this
# run, so cached pages are repaired too.
#
# The ids are not re-derived by hand — the slug rules (NFKD, ASCII folding,
# duplicate counters, and whatever the enabled extensions do to heading text) are
# python-markdown's, so the page is rendered with exactly the extension set from
# mkdocs.yml and the ids are read back out of the HTML. Verified byte-identical
# with the ids MkDocs emitted for all 113 built pages in both languages.
# ---------------------------------------------------------------------------

MKDOCS_YAML = SITE / "mkdocs.yml"
_HEADING_ID_RE = re.compile(r'<h[1-6][^>]*\sid="([^"]*)"')
_FRONTMATTER_RE = re.compile(r"^---\n.*?\n---\n", re.S)
_MD_LINK_RE = re.compile(r"\]\(([^)]+)\)")


class _MkDocsYamlLoader(yaml.SafeLoader):
    """SafeLoader that resolves the `!!python/name:` tags mkdocs.yml uses for the
    emoji extension, the same way MkDocs' own config loader does."""


def _resolve_python_name(loader, suffix, node):  # noqa: ANN001
    module_path, _, attribute = suffix.rpartition(".")
    return getattr(importlib.import_module(module_path), attribute)


_MkDocsYamlLoader.add_multi_constructor("tag:yaml.org,2002:python/name:", _resolve_python_name)
# Everything else MkDocs adds (!ENV in the `extra:` block) is not needed here; a
# blanket None keeps the parse from dying on a tag this script does not read.
_MkDocsYamlLoader.add_multi_constructor(None, lambda loader, suffix, node: None)


def load_markdown_extensions() -> tuple[list, dict]:
    """The `markdown_extensions:` block of mkdocs.yml as (names, configs).

    Read from the config rather than hardcoded so the slugs cannot drift apart
    from the site build when an extension is added there.
    """
    try:
        config = yaml.load(MKDOCS_YAML.read_text(encoding="utf-8"), Loader=_MkDocsYamlLoader)
    except Exception as exc:  # noqa: BLE001
        print(f"  ! cannot read {MKDOCS_YAML.name} ({exc}); anchors left untouched")
        return [], {}
    names: list = []
    configs: dict = {}
    for entry in config.get("markdown_extensions") or []:
        if isinstance(entry, str):
            names.append(entry)
        elif isinstance(entry, dict):
            for name, settings in entry.items():
                names.append(name)
                if isinstance(settings, dict):
                    configs[name] = settings
    return names, configs


def heading_ids(md_text: str, renderer) -> list[str]:
    """Every heading id of a page, in document order, exactly as MkDocs will emit it."""
    body = _FRONTMATTER_RE.sub("", md_text, count=1)
    renderer.reset()
    try:
        html = renderer.convert(body)
    except Exception:  # noqa: BLE001
        return []
    return _HEADING_ID_RE.findall(html)


def build_anchor_map(en_md: str, de_md: str, renderer, rel: str) -> dict[str, str]:
    """English id -> German id for one page.

    Pairing is by document order, which holds because translate_md preserves both
    the line count and the heading markers. A count mismatch means a heading was
    lost in translation; the page is then skipped rather than mapped by guesswork.
    """
    en_ids = heading_ids(en_md, renderer)
    de_ids = heading_ids(de_md, renderer)
    if not en_ids or len(en_ids) != len(de_ids):
        if en_ids:
            print(f"  ! {rel}: {len(en_ids)} heading(s) in EN vs {len(de_ids)} in DE "
                  f"— anchors for this page left untouched")
        return {}
    return {en_id: de_id for en_id, de_id in zip(en_ids, de_ids) if en_id != de_id}


def add_stale_generated_anchor_aliases(
    anchor_map: dict[str, str], old_de_md: str | None, de_md: str, renderer
) -> None:
    """Also map anchors emitted by the previously generated German page.

    A glossary correction can rename a translated heading while a cached German page still links
    to that heading's former German slug. The ordinary EN -> current-DE map cannot recognize this
    already-translated old slug, so retain it as an alias for this synchronization pass.
    """
    if not old_de_md:
        return
    old_ids = heading_ids(old_de_md, renderer)
    current_ids = heading_ids(de_md, renderer)
    if len(old_ids) != len(current_ids):
        return
    for old_id, current_id in zip(old_ids, current_ids):
        if old_id != current_id:
            anchor_map[old_id] = current_id


def rewrite_link_anchors(de_md: str, rel: str, maps: dict[str, dict[str, str]]) -> tuple[str, int]:
    """Point every in-repo link anchor on a German page at its translated heading."""
    hits = 0

    def rewrite_target(target: str) -> str:
        nonlocal hits
        core, space, title = target.partition(" ")  # ](url "title")
        path_part, hashed, anchor = core.partition("#")
        if not hashed or not anchor:
            return target
        # Absolute and off-site targets are not ours to slugify. An empty path_part
        # is a same-page link and resolves to this page below.
        if "://" in path_part or path_part.startswith(("mailto:", "/")):
            return target
        target_rel = (
            posixpath.normpath(posixpath.join(posixpath.dirname(rel), path_part))
            if path_part else rel)
        translated = maps.get(target_rel, {}).get(anchor)
        if not translated:
            return target
        hits += 1
        return f"{path_part}#{translated}{space}{title}"

    lines = de_md.split("\n")
    in_fence = False
    for i, line in enumerate(lines):
        if FENCE_RE.match(line):
            in_fence = not in_fence
            continue
        if in_fence or "](" not in line:
            continue
        lines[i] = _MD_LINK_RE.sub(lambda m: "](" + rewrite_target(m.group(1)) + ")", line)
    return "\n".join(lines), hits


def sync_anchors(pages: list[Path]) -> None:
    """Rewrites link anchors across the generated German tree.

    Runs over every page each time — a link may point into a page that this run
    skipped, and the first run after this feature landed has to repair the pages
    that were generated before it existed.
    """
    names, configs = load_markdown_extensions()
    if not names:
        return
    try:
        renderer = markdown.Markdown(extensions=names, extension_configs=configs)
    except Exception as exc:  # noqa: BLE001
        print(f"  ! cannot load the site's Markdown extensions ({exc}); anchors left untouched")
        return

    maps: dict[str, dict[str, str]] = {}
    for src in pages:
        rel = src.relative_to(EN).as_posix()
        dst = DE / src.relative_to(EN)
        if not dst.is_file():
            continue
        current_de = dst.read_text(encoding="utf-8")
        page_map = build_anchor_map(
            src.read_text(encoding="utf-8"), current_de, renderer, rel)
        add_stale_generated_anchor_aliases(page_map, git_head_version(dst), current_de, renderer)
        if page_map:
            maps[rel] = page_map
    if not maps:
        return

    changed_pages = total_hits = 0
    for src in pages:
        dst = DE / src.relative_to(EN)
        if not dst.is_file():
            continue
        original = dst.read_text(encoding="utf-8")
        rewritten, hits = rewrite_link_anchors(original, src.relative_to(EN).as_posix(), maps)
        if hits and rewritten != original:
            dst.write_text(rewritten, encoding="utf-8")
            changed_pages += 1
            total_hits += hits
    print(f"  anchors: {total_hits} link(s) repointed at translated headings "
          f"across {changed_pages} page(s)")


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
    md_pages: list[Path] = []
    all_failed: list[tuple[str, str]] = []  # (page, masked source text) that stayed English
    for src in sorted(EN.rglob("*")):
        if src.is_dir():
            continue
        rel = src.relative_to(EN)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        if src.suffix == ".md":
            md_pages.append(src)
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
        translated, reused, fresh, failed = translate_md(md, translator, memory)
        dst.write_text(translated, encoding="utf-8")
        new_cache[str(rel)] = digest
        md_done += 1
        md_lines_fresh += fresh
        md_lines_reused += reused
        if failed:
            all_failed.extend((str(rel), item) for item in failed)
        note = f", {len(failed)} FAILED — kept English" if failed else ""
        print(f"  translated {rel} ({fresh} line(s) translated, {reused} reused{note})")

    save_cache(new_cache)
    # After every page exists in its final German wording — a link can point into a
    # page that this run skipped, so the anchors are only knowable at the end.
    sync_anchors(md_pages)
    print(f"\nDone. translated {md_done} page(s) ({md_lines_fresh} line(s) translated, "
          f"{md_lines_reused} reused), {md_skip} unchanged. "
          f"(assets are staged into docs/de by build-docs-site.py)")
    if all_failed:
        # These pages were cached as "translated" above, so a plain re-run will not
        # retry them — the line-reuse memory in build_page_memory() would just read
        # the English text straight back out of the committed German page and treat
        # it as a valid prior translation. Re-running with --force does not help
        # either for the same reason. Delete the destination page (or the specific
        # line's German text) before re-running to force these back through the
        # translator.
        print(f"\n! {len(all_failed)} line(s) across {len({p for p, _ in all_failed})} "
              f"page(s) kept their English text after the translator failed twice:")
        for rel, item in all_failed:
            preview = item if len(item) <= 80 else item[:77] + "..."
            print(f"    {rel}: {preview!r}")
        print("  Delete the affected docs/de page(s) and re-run to force a full retranslation —")
        print("  a plain re-run will reuse this English text as if it were already translated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
