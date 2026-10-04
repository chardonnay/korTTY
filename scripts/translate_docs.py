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

Backends: --backend google (default, deep_translator), lmstudio (a local OpenAI-compatible
server; --model, --base-url, --concurrency, --batch-lines) or libretranslate. The LLM prompt
carries the formal register, the placeholder rules and the German UI labels / glossary terms
of the lines in each request. A line that cannot be translated keeps its English text, is
reported as FAILED and its page is NOT marked done, so a plain re-run retries it.
scripts/translate_benchmark.py compares backends and models on a fixed sample.

Changed-lines mode (default with --changed-since, --no-only-changed-lines to disable): the
English of the base ref is aligned with the current English (difflib) and every unchanged
English line keeps its existing German line verbatim; only new/edited lines are translated.
The line memory alone re-translated every line with an anchored link, because its German
anchor never matches the English one. Anchors are then repointed line by line over the whole
German tree (sync_anchors_linewise).

Usage:
  scripts/translate_docs.py            # translate changed pages, copy assets
  scripts/translate_docs.py --force    # re-translate everything
  scripts/translate_docs.py --dry-run --changed-since origin/main --memory-from-git
  scripts/translate_docs.py --backend lmstudio --model openai/gpt-oss-20b \\
      --concurrency 8 --batch-lines 4 --changed-since origin/main --memory-from-git
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
except ImportError:  # only the google backend needs it; checked in make_backend()
    GoogleTranslator = None
    BaseError = RequestError = TooManyRequests = Exception

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
    # Token numbers follow INLINE_PATTERNS order, not line position ("| ++ctrl+w++ |" masks
    # the key as KTPH000 and the pipes as KTPH001..), so the expected order is the order in
    # which the structural tokens appear in the masked source. Comparing against the token
    # numbers instead rejected every table row with a key or link after its first "|" — the
    # translation went to the fragment fallback, and the committed German row never
    # qualified as translation memory.
    if masked is not None:
        structural_indices.sort(key=lambda i: masked.find(tokens[i]))
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


def translate_preserving_token_order(masked: str, translator, errors: list | None = None) -> str:
    """Fallback for providers that drop/reorder placeholder tokens.

    Translate only the prose fragments between tokens and then reassemble the
    original token order. The grammar can be slightly less fluid than a full-line
    translation, but the generated Markdown stays valid and no content vanishes.
    A fragment whose translation raised keeps its English text; when `errors` is
    given, the fragment is appended to it so the caller can report the line.
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
            if errors is not None:
                errors.append(core)
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
        elif source[:1].isupper() and source[:1].isascii():
            # Never rewrite inside a CamelCase identifier (`LocalHnswStore`, `RagKnowledgeStorePane`):
            # a capitalised term that directly follows a lowercase letter, digit or underscore is
            # the middle of a word, not the term.
            text = re.sub(r"(?<![a-z0-9_])" + re.escape(source), lambda _m: target, text)
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


def _google_translate_lines(translator, texts: list[str]) -> list[str | None]:
    """Google path (deep_translator duck type: translate_batch + translate). Returns None for
    a line the provider could not translate — the caller decides what to do with it."""
    out: list[str | None] = []
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
            # per-item translation. Retries with a backoff before giving up: most
            # single-item failures here are transient (rate limiting).
            res = []
            for item in chunk:
                r = None
                for attempt in range(4):
                    try:
                        r = translator.translate(item)
                        if r:
                            break
                    except Exception:  # noqa: BLE001
                        r = None
                    if attempt < 3:
                        time.sleep(1.5 * (attempt + 1))
                res.append(r or None)
                time.sleep(0.2)
        out.extend(list(res))
        if k + B < len(texts):
            time.sleep(0.4)
    return out


def translate_texts(translator, texts: list[str]) -> list[str | None]:
    """One pass over masked lines through any backend. Backends that implement
    translate_lines (LM Studio, LibreTranslate) own batching and concurrency; anything
    else is treated as a deep_translator GoogleTranslator."""
    if not texts:
        return []
    if hasattr(translator, "translate_lines"):
        return list(translator.translate_lines(texts))
    return _google_translate_lines(translator, texts)


_IDENTIFIER_RE = re.compile(r"[\w.$~-]+(?:/[\w.$~-]*)*/?")


def is_identifier_line(masked: str) -> bool:
    """The prose of the line (tokens and list/heading markers removed) is one file name, path
    or identifier — "### llm/models.xml", "### coding-agents/", "### master.autounlock".
    Such a line is never sent to a translator (an LLM happily "translates" file names into
    "llm/Modelle.xml") and its identical German line is valid translation memory."""
    text = TOKEN_RE.sub(" ", masked).strip()
    return bool(text) and " " not in text and bool(_IDENTIFIER_RE.fullmatch(text)) \
        and bool(re.search(r"[./_]", text))


def looks_untranslated(masked: str, out: str) -> bool:
    """True when a translation is just the English source again (three or more English
    words of prose outside the tokens) — an LLM that echoed its input, or a provider
    fallback. The caller retries such a line once and then accepts it (a line of names
    and identifiers can legitimately stay the same)."""
    if out.strip() != masked.strip():
        return False
    return len(re.findall(r"[A-Za-z]{2,}", TOKEN_RE.sub(" ", masked))) >= 3


# English function words that never occur in German prose (German homographs such as
# "an", "in", "so", "will", "was", "die" are deliberately absent).
_ENGLISH_ONLY_WORDS = frozenset(
    "the with and of is are than its which that this from into your you when while below "
    "above been be by there their they these those".split())


def english_leftovers(text: str) -> int:
    """Number of English-only function words in a translated line's prose (tokens removed)."""
    words = re.findall(r"[A-Za-z]+", TOKEN_RE.sub(" ", text))
    return sum(1 for w in words if w.lower() in _ENGLISH_ONLY_WORDS)


_EMPHASIS_RE = re.compile(r"\*\*([^*\n]+?)\*\*|(?<![*\w])\*([^*\n]+?)\*(?![*\w])")
def untranslated_spans(masked: str, out: str) -> list[str]:
    """Bold/italic text and link text that came back as the identical English words.

    An LLM tends to treat "**Library**" or "[The library](...)" as a name and keep it. A span
    counts only when it holds a real English word (four or more lowercase-able letters) — so
    "**⚠ 3**", "**A+**" or a single product name are not flagged unless they reappear verbatim
    in a longer span. The caller retries such a line once and then accepts it: a UI label or
    product name can legitimately stay English."""
    def spans(text: str) -> set[str]:
        found = {a or b for a, b in _EMPHASIS_RE.findall(text)}
        # Every stretch of text between tokens (and before the first / after the last one):
        # link text, a table cell, a heading's words, the prose around inline code.
        found |= {part for part in TOKEN_RE.split(text) if part and not TOKEN_RE.fullmatch(part)}
        return {x.strip() for x in found if x.strip()}

    source = spans(masked)
    kept = []
    for span in spans(out) & source:
        words = re.findall(r"[A-Za-z]{4,}", span)
        if len(words) >= 2 or (words and span[:1].isupper() and span.lower() in _COMMON_ENGLISH_LABELS):
            kept.append(span)
        elif words and _english_ui_labels().get(span, span) != span:
            kept.append(span)  # an English UI label whose German label differs
    return sorted(kept)


# Single-word English labels an LLM is tempted to keep (multi-word spans are always checked).
_COMMON_ENGLISH_LABELS = frozenset(
    "library actions browse edit tabs closing deleting overview settings options search "
    "preview save close open delete apply export import history diagram analysis summary "
    "general advanced details status".split())


_TERMS: "TermContext | None" = None


def _english_ui_labels() -> dict[str, str]:
    """English UI label -> German UI label (shared, loaded once)."""
    global _TERMS
    if _TERMS is None:
        _TERMS = TermContext()
    return _TERMS.labels


_GERMAN_UI_LABELS: set[str] | None = None


def german_ui_labels() -> set[str]:
    """Every German UI label — a span that equals one is not an English leftover even when
    the app itself uses the English word (e.g. **Findings**)."""
    global _GERMAN_UI_LABELS
    if _GERMAN_UI_LABELS is None:
        path = I18N_DIR / f"messages_{TARGET}.properties"
        props = parse_properties(path.read_text(encoding="utf-8")) if path.is_file() else {}
        _GERMAN_UI_LABELS = {label for label in map(clean_ui_label, props.values()) if label}
    return _GERMAN_UI_LABELS


def repair_spans(masked: str, out: str, translator) -> str:
    """Translates bold/italic/link text that an otherwise good translation kept in English,
    one span per request, and puts the German span in its place."""
    labels = german_ui_labels()
    for span in untranslated_spans(masked, out):
        if clean_ui_label(span) in labels:
            continue
        try:
            german = translator.translate(span)
        except Exception:  # noqa: BLE001
            continue
        german = (german or "").strip()
        if german and german != span and "KTPH" not in german and "\n" not in german \
                and "*" not in german:
            out = out.replace(span, german, 1)
    return out


def acceptable(out: str | None, store: list[str], masked: str) -> bool:
    """A translated line may ship: tokens intact, not an echo of the source, no run of
    English left in it (a table row whose last cells an LLM did not translate) and no bold
    or link text kept in English."""
    if not out or not placeholders_intact(out, store, masked) or looks_untranslated(masked, out):
        return False
    if english_leftovers(out) >= 3 and english_leftovers(masked) >= 3:
        return False
    return not untranslated_spans(masked, out)


def translate_masked(translator, items: list[tuple[str, list[str]]]) -> tuple[list[str | None], dict]:
    """Translates (masked, store) pairs and validates every result.

    A line whose placeholder tokens do not survive (or that came back empty) is sent again
    once, alone. If it still fails, the fragment-wise fallback translates only the prose
    between the tokens. A line that even that cannot produce is returned as None — the
    caller keeps the English text and reports it as FAILED instead of treating it as done.

    Returns (results, stats) where stats counts first-pass placeholder survival, retries,
    fragment fallbacks and failures (the benchmark reports these)."""
    texts = [masked for masked, _store in items]
    stats = {"lines": len(items), "first_pass_ok": 0, "retried": 0, "retry_ok": 0,
             "fragment_fallback": 0, "failed": 0}
    first = translate_texts(translator, texts)
    results: list[str | None] = [None] * len(items)
    retry: list[int] = []
    for i, ((masked, store), out) in enumerate(zip(items, first)):
        if acceptable(out, store, masked):
            results[i] = out
            stats["first_pass_ok"] += 1
        else:
            retry.append(i)
    if retry:
        stats["retried"] = len(retry)
        second = translate_texts(translator, [texts[i] for i in retry])
        for i, out in zip(retry, second):
            masked, store = items[i]
            if acceptable(out, store, masked):
                results[i] = out
                stats["retry_ok"] += 1
                continue
            if out and placeholders_intact(out, store, masked) and not looks_untranslated(masked, out) \
                    and (english_leftovers(out) < 3 or english_leftovers(masked) < 3):
                # Tokens fine, some English left: repair bold/link text that stayed English by
                # translating just those spans, and keep the rest — better than a fragment-wise
                # translation of the whole line.
                results[i] = repair_spans(masked, out, translator)
                stats["retry_ok"] += 1
                stats["span_repairs"] = stats.get("span_repairs", 0) + 1
                continue
            errors: list[str] = []
            fallback = translate_preserving_token_order(masked, translator, errors)
            stats["fragment_fallback"] += 1
            if not errors and placeholders_intact(fallback, store, masked):
                results[i] = fallback
            else:
                stats["failed"] += 1
    return results, stats


def finish_line(source_line: str, translated: str) -> str:
    """Restores the source line's indentation and a displaced heading marker."""
    indent = source_line[:len(source_line) - len(source_line.lstrip(" "))]
    line = indent + translated.lstrip(" ") if indent else translated
    return reanchor_leading_marker(line, source_line)


def translate_md(
    md: str, translator, memory: dict[str, str] | None = None,
    keep: dict[int, str] | None = None,
) -> tuple[str, int, int, list[str]]:
    """Translate a page, reusing memory (masked EN line -> masked DE line) for
    unchanged lines. Returns (german_markdown, reused_lines, translated_lines,
    still_english) — the last being the masked source text of every line that
    kept its English wording after translation genuinely failed (as opposed to
    a line that is legitimately identical, e.g. a bare product name).

    `keep` (line index -> German line, from changed_line_keep) switches to the
    changed-lines mode: those lines are copied verbatim — no translator call, no
    glossary pass — and only the remaining lines are translated. A kept line counts as
    reused."""
    memory = dict(memory) if memory else {}
    lines, jobs = translatable_lines(md)
    if keep is not None:
        kept_jobs = sum(1 for idx, _m, _s in jobs if idx in keep)
        jobs = [j for j in jobs if j[0] not in keep]
        fresh_idx = {idx for idx, _m, _s in jobs}
        for idx, german in keep.items():
            if idx < len(lines):
                lines[idx] = german
        german_md, reused, translated, failed = _translate_jobs(lines, jobs, translator, memory)
        out = german_md.split("\n")
        for idx in fresh_idx:
            out[idx] = apply_glossary(out[idx])
        return "\n".join(out), reused + kept_jobs, translated, failed
    if not jobs:
        return apply_glossary(md), 0, 0, []
    german_md, reused, translated, failed = _translate_jobs(lines, jobs, translator, memory)
    return apply_glossary(german_md), reused, translated, failed


def _translate_jobs(lines: list[str], jobs: list[tuple[int, str, list[str]]], translator,
                    memory: dict[str, str]) -> tuple[str, int, int, list[str]]:
    """Translates `jobs` into `lines` (in place) and returns the joined page WITHOUT the
    glossary pass, plus (reused, translated, still_english) as translate_md reports them."""
    if not jobs:
        return "\n".join(lines), 0, 0, []
    for _idx, masked, _store in jobs:
        if masked not in memory and is_identifier_line(masked):
            memory[masked] = masked
    misses = [j for j in jobs if j[1] not in memory]
    # Identical masked lines (a repeated table row, "See also") are translated once.
    unique: dict[str, list[str]] = {}
    for _idx, masked, store in misses:
        unique.setdefault(masked, store)
    items = list(unique.items())
    results, _stats = translate_masked(translator, items)
    failed: list[str] = []
    failed_set: set[str] = set()
    for (masked, _store), translated in zip(items, results):
        if translated is None:
            failed.append(masked)
            failed_set.add(masked)
        else:
            memory[masked] = translated
    for idx, masked, store in jobs:
        if masked in failed_set:
            translated = unmask(masked, store)
        else:
            translated = unmask(memory.get(masked, ""), store)
        if "KTPH" in translated:
            # Belt and braces: a mask token (or a deformed remnant of one) must never
            # ship in a generated page, no matter which upstream path produced it —
            # a translator deformation, a stale memory line, or a future masking bug.
            # The fragment-wise fallback cannot move or invent tokens; if a remnant
            # survives even that, keep the English line and report the failure.
            errors: list[str] = []
            translated = unmask(translate_preserving_token_order(masked, translator, errors), store)
            if "KTPH" in translated or errors:
                translated = unmask(masked, store)
                if masked not in failed_set:
                    failed.append(masked)
                    failed_set.add(masked)
        if lines[idx] == "title: \x03":
            lines[idx] = f'title: {translated}'
        else:
            # The translation API strips leading whitespace from its output, but a
            # list item's indented continuation paragraph (e.g. the "grid cards"
            # layout on index.md) depends on that indent to stay nested under its
            # item — restore whatever indentation the original line had.
            # Capture the English line before it is overwritten: `masked` has already had its
            # heading marker replaced by a placeholder, so it cannot tell us the line was a heading.
            lines[idx] = finish_line(lines[idx], translated)
    return "\n".join(lines), len(jobs) - len(misses), len(misses), failed


def remask(text: str, store: list[str]) -> str | None:
    """Reverse of unmask: put the KTPH tokens back into a translated line. Longer
    fragments first so a fragment that contains another does not get corrupted.
    Returns None when any fragment is missing (line cannot be safely reused)."""
    for i, frag in sorted(enumerate(store), key=lambda pair: -len(pair[1])):
        token = f"KTPH{i:03d}"
        if re.match(r"^\s*(?:!!!|===|#{1,6}\s|>\s|[-*+]\s|\d+[.)]\s)", frag):
            # A list/admonition/tab/heading marker is valid only at the beginning.
            # Searching globally can mistake prose such as "API- or ..." for the
            # missing "- " list marker and incorrectly reuse broken Markdown.
            # The marker patterns always capture their trailing whitespace, which is what
            # tells a "+ " list marker apart from a "++ctrl+w++" key: without it every line
            # with a keyboard key after its start was refused as memory and re-translated.
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
        source_words = re.findall(r"[A-Za-z]{2,}", en_lines[idx])
        if de_line.strip() == en_lines[idx].strip() and len(source_words) >= 2 \
                and not is_identifier_line(masked):
            # A failed provider call writes the English source into the generated page. Never
            # promote that fallback into translation memory on the next run, or it becomes
            # indistinguishable from a deliberate translation and can never be retried.
            continue
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
# Changed-lines mode (--only-changed-lines, the default with --changed-since)
#
# The line memory above cannot reuse a line that carries an anchored link (its German
# anchor differs from the English one), so a branch that edited a few lines on a page
# used to re-translate every linked line of that page as well — often 5-10x the really
# changed lines, with new (and different) German for text nobody touched. This mode
# aligns the English the German page was generated from with the current English
# (difflib, line by line) and copies the existing German line for every unchanged
# English line; only inserted/replaced lines are translated. Deleted lines drop out with
# their English, so en/de stay line-aligned. Anchors of kept lines are fixed afterwards
# by sync_anchors_linewise.
# ---------------------------------------------------------------------------


def git_show(ref: str, path: Path) -> str | None:
    """`git show <ref>:<path>` of a repo file, or None if it does not exist there."""
    try:
        rel = path.relative_to(REPO).as_posix()
        result = subprocess.run(
            ["git", "-C", str(REPO), "show", f"{ref}:{rel}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
        return result.stdout if result.returncode == 0 else None
    except Exception:  # noqa: BLE001
        return None


def en_alignment(base_en: str, md: str) -> list[tuple[str, int, int, int, int]]:
    """difflib opcodes from the base English lines to the current English lines."""
    import difflib
    return difflib.SequenceMatcher(None, base_en.split("\n"), md.split("\n"),
                                   autojunk=False).get_opcodes()


def changed_line_keep(base_en: str, base_de: str, md: str) -> dict[int, str] | None:
    """Current line index -> German line to keep, for every English line that is unchanged
    against `base_en` (the English `base_de` was generated from). None when the base pair is
    not line-aligned (no safe reuse).

    A kept German line that is still the English text (a line a previous run failed on and
    shipped in English) is NOT kept, so it is translated again."""
    en_old = base_en.split("\n")
    de_old = base_de.split("\n")
    if len(en_old) != len(de_old):
        return None
    jobs = {idx: masked for idx, masked, _s in translatable_lines(md)[1]}
    keep: dict[int, str] = {}
    for tag, i1, i2, j1, _j2 in en_alignment(base_en, md):
        if tag != "equal":
            continue
        for k in range(i2 - i1):
            german = de_old[i1 + k]
            masked = jobs.get(j1 + k)
            if masked is not None and german.strip() == en_old[i1 + k].strip() \
                    and looks_untranslated(masked, masked) and not is_identifier_line(masked):
                continue  # shipped in English by an earlier failed run: translate it now
            keep[j1 + k] = german
    return keep


def changed_lines_base(src: Path, dst: Path, md: str, cached: str | None, digest: str,
                       base_ref: str | None, memory_from_git: bool) -> tuple[str, str] | None:
    """The (English, German) pair the German page on disk was generated from, or None.

    In order: the page this tool wrote in an earlier run (cache entry for the current
    English, also a partial one); the German of `base_ref` when the page on disk is still
    that German (the usual feature branch: English edited, German not yet regenerated) —
    paired with the English of `base_ref`; with --memory-from-git, the English version
    git_aligned_english picks for the committed German page. Never the English at HEAD on
    its own: on a branch that committed English edits, that pairs every edited line with
    the German of the line it replaced."""
    if not dst.is_file():
        return None
    de_md = dst.read_text(encoding="utf-8")
    if cached in (digest, PARTIAL + digest):
        return md, de_md
    if base_ref:
        if git_show(base_ref, dst) == de_md:
            base_en = git_show(base_ref, src)
            if base_en is not None and len(base_en.split("\n")) == len(de_md.split("\n")):
                return base_en, de_md
    if memory_from_git and git_head_version(dst) == de_md:
        base_en = git_aligned_english(src)
        if base_en is not None and len(base_en.split("\n")) == len(de_md.split("\n")):
            return base_en, de_md
    return None


_ATX_HEADING_RE = re.compile(r"^\s{0,3}#{1,6}(?:\s|$)")


def heading_line_indices(md: str) -> list[int]:
    """Line index of every ATX heading outside fences and front matter, in order."""
    out: list[int] = []
    in_fence = False
    lines = md.split("\n")
    start = 0
    if lines and lines[0].strip() == "---":
        for i in range(1, len(lines)):
            if lines[i].strip() == "---":
                start = i + 1
                break
    for i in range(start, len(lines)):
        line = lines[i]
        if FENCE_RE.match(line):
            in_fence = not in_fence
            continue
        if not in_fence and _ATX_HEADING_RE.match(line):
            out.append(i)
    return out


def heading_alias_map(old_de: str, new_de: str, opcodes, renderer) -> dict[str, str]:
    """Old German heading id -> new German heading id for one page, by heading LINE.

    `opcodes` align the English the old German page was generated from with the current
    English; old and new German are line-aligned with those, so a heading keeps its
    identity across a re-translation (its own text changed, a heading above it was added or
    removed and shifted a duplicate counter, ...). Headings inside a replaced block are
    paired in order when the block has as many headings before as after. When the
    heading scan does not agree with the rendered ids (or there are no opcodes), falls
    back to pairing by order if both pages have the same number of headings."""
    old_ids, new_ids = heading_ids(old_de, renderer), heading_ids(new_de, renderer)
    old_pos, new_pos = heading_line_indices(old_de), heading_line_indices(new_de)
    if opcodes is None or len(old_ids) != len(old_pos) or len(new_ids) != len(new_pos):
        if old_ids and len(old_ids) == len(new_ids):
            return {o: n for o, n in zip(old_ids, new_ids) if o != n}
        return {}
    old_at = dict(zip(old_pos, old_ids))
    new_at = dict(zip(new_pos, new_ids))
    alias: dict[str, str] = {}
    for tag, i1, i2, j1, j2 in opcodes:
        if tag == "equal":
            pairs = [(i1 + k, j1 + k) for k in range(i2 - i1)]
        elif tag == "replace":
            olds = [p for p in range(i1, i2) if p in old_at]
            news = [p for p in range(j1, j2) if p in new_at]
            pairs = list(zip(olds, news)) if len(olds) == len(news) else []
        else:
            pairs = []
        for o, n in pairs:
            if o in old_at and n in new_at and old_at[o] != new_at[n]:
                alias[old_at[o]] = new_at[n]
    return alias


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


def make_heading_renderer():
    """A python-markdown renderer with the site's extension set (see heading_ids), or None."""
    names, configs = load_markdown_extensions()
    if not names:
        return None
    try:
        return markdown.Markdown(extensions=names, extension_configs=configs)
    except Exception as exc:  # noqa: BLE001
        print(f"  ! cannot load the site's Markdown extensions ({exc}); anchors left untouched")
        return None


def sync_anchors_linewise(pages: list[Path], translated: dict[str, dict], renderer=None) -> int:
    """Anchor pass of the changed-lines mode, over every line of every German page.

    `translated` maps each EN-relative page written in this run to
    {"old_de": German before the run, "fresh": line indices that are new translations,
    "opcodes": en_alignment of the base English with the current English (None when the
    page was translated without a base)}.

    A fresh line carries the ENGLISH anchors the translator copied, so it goes through the
    English-id -> German-id map of the target page (build_anchor_map). Every other line —
    kept lines of translated pages and all lines of untouched pages — already carries a
    German anchor, which goes stale when the heading it names was re-translated or a
    duplicate counter shifted; it goes through the old-German -> new-German map of the
    target page (heading_alias_map). A kept anchor that matches no heading of the target
    page is tried against the English map as well (a leftover English anchor). Explicit
    `{ #id }` heading ids are identical in every map side and therefore never rewritten.

    Prints every anchor that still names no heading and returns their number."""
    if renderer is None:
        renderer = make_heading_renderer()
        if renderer is None:
            return 0
    en_maps: dict[str, dict[str, str]] = {}
    aliases: dict[str, dict[str, str]] = {}
    ids: dict[str, set[str]] = {}
    for src in pages:
        rel = src.relative_to(EN).as_posix()
        dst = DE / src.relative_to(EN)
        if not dst.is_file():
            continue
        current_de = dst.read_text(encoding="utf-8")
        ids[rel] = set(heading_ids(current_de, renderer))
        en_maps[rel] = build_anchor_map(src.read_text(encoding="utf-8"), current_de, renderer, rel)
        info = translated.get(rel)
        if info and info.get("old_de"):
            aliases[rel] = heading_alias_map(info["old_de"], current_de, info.get("opcodes"),
                                             renderer)

    total_hits = changed_pages = 0
    dangling: list[tuple[str, str]] = []
    for src in pages:
        rel = src.relative_to(EN).as_posix()
        dst = DE / src.relative_to(EN)
        if not dst.is_file():
            continue
        fresh = translated.get(rel, {}).get("fresh") or set()
        original = dst.read_text(encoding="utf-8")
        lines = original.split("\n")
        hits = 0
        in_fence = False
        for i, line in enumerate(lines):
            if FENCE_RE.match(line):
                in_fence = not in_fence
                continue
            if in_fence or "](" not in line:
                continue
            is_fresh = i in fresh

            def rewrite_target(target: str, is_fresh=is_fresh) -> str:
                nonlocal hits
                core, space, title = target.partition(" ")
                path_part, hashed, anchor = core.partition("#")
                if not hashed or not anchor:
                    return target
                if "://" in path_part or path_part.startswith(("mailto:", "/")):
                    return target
                target_rel = (
                    posixpath.normpath(posixpath.join(posixpath.dirname(rel), path_part))
                    if path_part else rel)
                if target_rel not in ids:
                    return target
                if is_fresh:
                    new = en_maps.get(target_rel, {}).get(anchor)
                else:
                    new = aliases.get(target_rel, {}).get(anchor)
                    if not new and anchor not in ids[target_rel]:
                        new = en_maps.get(target_rel, {}).get(anchor)
                if new and new != anchor:
                    hits += 1
                    anchor = new
                if anchor not in ids[target_rel]:
                    dangling.append((rel, f"{path_part}#{anchor}"))
                return f"{path_part}#{anchor}{space}{title}"

            lines[i] = _MD_LINK_RE.sub(lambda m: "](" + rewrite_target(m.group(1)) + ")", line)
        rewritten = "\n".join(lines)
        if rewritten != original:
            dst.write_text(rewritten, encoding="utf-8")
            changed_pages += 1
            total_hits += hits
    print(f"  anchors: {total_hits} link(s) repointed at translated headings "
          f"across {changed_pages} page(s)")
    for rel, link in dangling:
        print(f"  ! {rel}: link anchor {link} names no heading of its target page")
    return len(dangling)


# ---------------------------------------------------------------------------
# Translation backends
#
# google (default) — deep_translator's GoogleTranslator, unchanged behaviour.
# lmstudio         — any OpenAI-compatible /v1/chat/completions server (LM Studio,
#                    llama.cpp server, vLLM ...). Stdlib urllib only, no new dependency.
# libretranslate   — a LibreTranslate server's POST /translate (optional; set
#                    LIBRETRANSLATE_API_KEY if the server requires a key).
#
# Every backend answers translate(text) -> str (raises on failure; used by the
# fragment-wise fallback) and, except google, translate_lines(texts) -> [str | None]
# which owns batching and concurrency and keeps the input order.
# ---------------------------------------------------------------------------

I18N_DIR = REPO / "src" / "main" / "resources" / "i18n"
DEFAULT_BASE_URLS = {
    "lmstudio": "http://localhost:1234/v1",
    "libretranslate": "http://localhost:5000",
}
DEFAULT_LMSTUDIO_MODEL = "openai/gpt-oss-20b"


def parse_properties(text: str) -> dict[str, str]:
    """Minimal java.util.Properties reader: comments, continuation lines, \\uXXXX and the
    usual backslash escapes. Enough for the i18n bundles; no external dependency."""
    out: dict[str, str] = {}
    logical: list[str] = []
    buf = ""
    for raw in text.splitlines():
        line = raw.lstrip()
        if not buf and (not line or line[0] in "#!"):
            continue
        trailing = len(line) - len(line.rstrip("\\"))
        if trailing % 2 == 1:
            buf += line[:-1]
            continue
        logical.append(buf + line)
        buf = ""
    if buf:
        logical.append(buf)
    for line in logical:
        m = re.match(r"((?:\\.|[^=:\s\\])+)\s*[=:\s]\s*(.*)$", line)
        if not m:
            continue
        key, value = m.group(1), m.group(2)

        def unescape(v: str) -> str:
            v = re.sub(r"\\u([0-9a-fA-F]{4})", lambda mm: chr(int(mm.group(1), 16)), v)
            return re.sub(r"\\(.)", lambda mm: {"n": "\n", "t": "\t", "r": "\r"}.get(mm.group(1), mm.group(1)), v)

        out[unescape(key)] = unescape(value)
    return out


def clean_ui_label(label: str) -> str | None:
    """A UI string as it appears in prose: no mnemonic underscore, ellipsis or colon.
    None for strings that are not labels (sentences, placeholders, multi-line)."""
    label = label.strip()
    if not label or "\n" in label or "{" in label or "<" in label or len(label) > 48:
        return None
    label = re.sub(r"(^|(?<=\s))_(?=\w)", "", label)
    label = re.sub(r"(\.\.\.|…|:)$", "", label).strip()
    if len(label) < 3 or not re.search(r"[A-Za-z]", label) or label.endswith("."):
        return None
    return label


class TermContext:
    """Per-request terminology hints for LLM backends.

    * German UI labels from messages_de.properties for every English label that occurs in
      the lines being translated — bold (**Save all**), in a menu path (Tools → X) or,
      for multi-word labels, anywhere — so button and menu names match the app.
    * glossary/de.json rows: a row whose `from` text occurs in the English (an English
      leftover the post-pass would fix, e.g. "AI Manager") is given as a required term,
      and a row whose note names an i18n key whose English label matched is given as
      "use X, not Y". The same rows still run as post-corrections afterwards.
    Glossary rows win over plain UI labels for the same English term: the guide's own
    established wording (e.g. "Snippet-Manager") beats a label the app words differently.
    """

    MAX_HINTS = 40

    def __init__(self, en_props: dict[str, str] | None = None, de_props: dict[str, str] | None = None,
                 glossary_rows: list[dict] | None = None):
        if en_props is None:
            en_props = self._load(I18N_DIR / "messages.properties")
        if de_props is None:
            de_props = self._load(I18N_DIR / f"messages_{TARGET}.properties")
        if glossary_rows is None:
            glossary_rows = []
            if GLOSSARY_PATH.is_file():
                data = json.loads(GLOSSARY_PATH.read_text(encoding="utf-8"))
                glossary_rows = [r for r in data.get("replacements", [])
                                 if "from" in r and "to" in r and r.get("scope", "any") != "html"]
        from collections import Counter
        votes: dict[str, Counter] = {}
        self.key_labels: dict[str, str] = {}
        for key, en_value in en_props.items():
            en_label = clean_ui_label(en_value)
            de_value = de_props.get(key)
            if not en_label or de_value is None:
                continue
            de_label = clean_ui_label(de_value)
            if not de_label:
                continue
            self.key_labels[key] = en_label
            votes.setdefault(en_label, Counter())[de_label] += 1
        # Most frequent German rendering wins; ties go to the alphabetically first so the
        # prompt (and therefore a benchmark) is deterministic.
        self.labels: dict[str, str] = {
            en: sorted(c.items(), key=lambda kv: (-kv[1], kv[0]))[0][0] for en, c in votes.items()}
        self.multiword = sorted((en for en in self.labels if " " in en and len(en) >= 8),
                                key=len, reverse=True)
        self.glossary_rows = glossary_rows
        self._key_re = re.compile(r"\b([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9]+)+)\b")

    @staticmethod
    def _load(path: Path) -> dict[str, str]:
        return parse_properties(path.read_text(encoding="utf-8")) if path.is_file() else {}

    def matched_labels(self, text: str) -> dict[str, str]:
        """English UI label -> German UI label for every label found in text."""
        found: dict[str, str] = {}
        candidates: list[str] = []
        candidates += re.findall(r"\*\*([^*\n]+?)\*\*", text)
        candidates += re.findall(r"(?<![*\w])\*([^*\n]+?)\*(?![*\w])", text)
        # A short stretch between tokens is often a label on its own: a heading, a table
        # cell, link text.
        candidates += [part.strip() for part in TOKEN_RE.split(text)
                       if part.strip() and len(part.split()) <= 4 and not TOKEN_RE.fullmatch(part)]
        for path in re.findall(r"[^\n.;()]*(?:→|>)[^\n.;()]*", text):
            # "under Tools → Snippet Manager for": the label is the words next to the arrow.
            for part in re.split(r"\s*(?:→|>)\s*", path):
                words = part.strip(" *").split()
                for n in range(1, min(4, len(words)) + 1):
                    candidates += [" ".join(words[:n]), " ".join(words[-n:])]
        for cand in candidates:
            label = clean_ui_label(cand.strip(" *_"))
            if label and label in self.labels:
                found[label] = self.labels[label]
        text = TOKEN_RE.sub(" ", text)
        for en in self.multiword:
            if en in found:
                continue
            idx = text.find(en)
            while idx != -1:
                before = text[idx - 1] if idx > 0 else " "
                after = text[idx + len(en)] if idx + len(en) < len(text) else " "
                if not before.isalnum() and not after.isalnum():
                    found[en] = self.labels[en]
                    break
                idx = text.find(en, idx + 1)
        return found

    def hints(self, text: str) -> list[tuple[str, str]]:
        """(English or avoided term, required German term) pairs for this text."""
        labels = self.matched_labels(text)
        # Tokens sit directly against words ("KTPH000Snippet Manager"); a word-boundary match
        # must see a space there, not the token's digits.
        text = TOKEN_RE.sub(" ", text)
        matched_keys = {k for k, en in self.key_labels.items() if en in labels}
        pairs: dict[str, str] = {}
        for row in self.glossary_rows:
            # Markdown around a row's term ("[AI chats]", "**Discard**") is masked or bold in
            # the text being translated; match and hint the bare term.
            src, dst = row["from"].strip("[]*# "), row["to"].strip("[]*# ")
            if not src or not dst or "|" in src:
                continue
            # Only multi-word English leftovers ("AI Manager") make useful hints; a single
            # word such as "Store" is a verb as often as the product term, and the post-pass
            # applies those rows anyway.
            if (" " in src.strip() or "-" in src) and re.search(
                    rf"(?<![\w-]){re.escape(src)}(?![\w-])", text):
                pairs[src] = dst
                continue
            keys = set(self._key_re.findall(row.get("note", "")))
            if keys & matched_keys:
                pairs[f"not: {src}"] = dst
        for en, de in labels.items():
            pairs.setdefault(en, de)
        return list(pairs.items())[: self.MAX_HINTS]

    def expected_terms(self, text: str) -> list[str]:
        """German terms a good translation of `text` must contain (benchmark adherence)."""
        return sorted({de.replace("**", "").strip() for en, de in self.hints(text)
                       if not en.startswith("not: ") and de.replace("**", "").strip()})


SYSTEM_PROMPT = """You are a professional translator of technical software documentation from English into German.
You translate the user guide of korTTY, a desktop SSH client. The input is Markdown (MkDocs Material), one source line at a time.

Rules:
- Tokens of the form KTPH followed by three digits (KTPH000, KTPH001, ...) are placeholders for code, links, keyboard keys, Markdown markers and table separators. Copy every token exactly once, unchanged, and keep all tokens in the same order as in the source. Never translate, split, add or drop a token. A token at the start or end of the source line stays at the start or end.
- Keep Markdown syntax intact: **bold**, *italic*, and spacing around tokens. Translate the text inside **bold** and *italic* too (it is usually a UI label or a term; use the Terminology list for UI labels).
- Translate every sentence completely; never return the English text unchanged.
- Address the reader formally with "Sie" (never "du"), as the existing German guide does.
- Use the exact German UI terms listed under "Terminology" whenever the English term occurs; keep product and technology names (korTTY, SSH, SFTP, Mermaid, Monaco, LM Studio) unchanged.
- Canonical German terms: AI -> KI (KI-Manager, KI-Skills), guide/manual -> Anleitung, snippet -> Snippet, tab -> Tab, terminal -> Terminal, prompt -> Prompt.
- Never translate file names, paths, identifiers, setting keys or command names (snippets.xml, llm/models.xml, coding-agents/, snippetAnalysisHistoryMaxSize).
- Link text (text between two tokens, e.g. "See KTPH003The libraryKTPH004") is prose: translate it. "See ..." becomes "Siehe ...".
- Grammar: das Snippet (neuter), der Tab, die KI; product name korTTY.
- Form German compound nouns correctly: one word or joined with hyphens (KI-Profil, Setup-Assistent, Snippet-Editor), never as separate words.
- Write natural, concise German technical prose. Do not add explanations, notes or quotes."""


class _RequestLog:
    """Thread-safe per-request statistics (latency, token usage) for the benchmark."""

    def __init__(self):
        import threading
        self._lock = threading.Lock()
        self.requests: list[dict] = []

    def add(self, **entry):
        with self._lock:
            self.requests.append(entry)


def _chunks(seq: list, size: int) -> list[list]:
    size = max(1, size)
    return [seq[k:k + size] for k in range(0, len(seq), size)]


def _token_multiset(text: str) -> list[str]:
    return sorted(TOKEN_RE.findall(text or ""))


class _ConcurrentBackend:
    """Batching + ordered concurrency shared by the HTTP backends."""

    def __init__(self, concurrency: int = 1, batch_lines: int = 1):
        self.concurrency = max(1, concurrency)
        self.batch_lines = max(1, batch_lines)
        self.log = _RequestLog()
        # Optional time box (time.perf_counter() value) set by the benchmark: once it has
        # passed, no further request is sent and a request in flight is cut at that moment.
        self.deadline: float | None = None
        self.deadline_hit = False

    def _remaining_timeout(self, timeout: float) -> float:
        """The request timeout, capped to the time left until `deadline`; raises
        TimeoutError (and records that the box was hit) when there is none left."""
        if self.deadline is None:
            return timeout
        left = self.deadline - time.perf_counter()
        if left <= 0:
            self.deadline_hit = True
            raise TimeoutError("time budget exhausted")
        return min(timeout, left)

    def _translate_batch(self, batch: list[str]) -> list[str | None]:  # pragma: no cover
        raise NotImplementedError

    def translate_lines(self, texts: list[str]) -> list[str | None]:
        batches = _chunks(list(texts), self.batch_lines)
        if self.concurrency == 1 or len(batches) == 1:
            results = [self._safe_batch(b) for b in batches]
        else:
            from concurrent.futures import ThreadPoolExecutor
            with ThreadPoolExecutor(max_workers=self.concurrency) as pool:
                results = list(pool.map(self._safe_batch, batches))  # map keeps the order
        return [line for batch in results for line in batch]

    def _safe_batch(self, batch: list[str]) -> list[str | None]:
        if self.deadline is not None and time.perf_counter() >= self.deadline:
            self.deadline_hit = True
            return [None] * len(batch)
        try:
            out = self._translate_batch(batch)
        except Exception as exc:  # noqa: BLE001
            if self.deadline is not None and time.perf_counter() >= self.deadline:
                self.deadline_hit = True
                return [None] * len(batch)
            print(f"  ! {type(self).__name__}: {exc}", file=sys.stderr)
            return [None] * len(batch)
        return out if len(out) == len(batch) else [None] * len(batch)


def _http_post_json(url: str, payload: dict, timeout: float, headers: dict | None = None) -> dict:
    import urllib.request
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 (user-supplied local URL)
        return json.loads(resp.read().decode("utf-8"))


def parse_batch_reply(reply: str, count: int) -> list[str] | None:
    """Maps a numbered JSON batch reply back to its lines.

    Accepts {"translations": [{"id": 1, "text": "..."}, ...]}, a bare list of such
    objects, or a bare list of strings; tolerates a Markdown code fence and prose
    around the JSON. Returns None unless there is exactly one text per id 1..count."""
    if not reply:
        return None
    text = reply.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    if fence:
        text = fence.group(1).strip()
    candidates = []
    for opener, closer in (("{", "}"), ("[", "]")):
        start, end = text.find(opener), text.rfind(closer)
        if start != -1 and end > start:
            candidates.append(text[start:end + 1])
    for cand in candidates:
        try:
            data = json.loads(cand)
        except ValueError:
            continue
        if isinstance(data, dict):
            data = data.get("translations", data.get("lines"))
        if not isinstance(data, list):
            continue
        if all(isinstance(x, str) for x in data):
            return list(data) if len(data) == count else None
        by_id: dict[int, str] = {}
        for item in data:
            if not isinstance(item, dict):
                return None
            try:
                ident = int(item.get("id"))
            except (TypeError, ValueError):
                return None
            value = item.get("text", item.get("de", item.get("translation")))
            if not isinstance(value, str) or ident in by_id:
                return None
            by_id[ident] = value
        if sorted(by_id) != list(range(1, count + 1)):
            return None
        return [by_id[i] for i in range(1, count + 1)]
    return None


# Characters LLMs like to emit that the guide never uses: soft hyphens inside compounds,
# non-breaking hyphens/spaces, zero-width spaces. Invisible in review, but they break search
# and make identical words differ.
_LLM_CHAR_FIXES = {"\u00ad": "", "\u200b": "", "\u2011": "-", "\u2010": "-",
                   "\u00a0": " ", "\u202f": " "}


def normalize_llm_text(text: str) -> str:
    for bad, good in _LLM_CHAR_FIXES.items():
        text = text.replace(bad, good)
    return text


def clean_single_reply(reply: str, source: str) -> str | None:
    """Strips what chat models wrap around a one-line answer (fences, quotes, labels)."""
    if reply is None:
        return None
    text = reply.strip()
    fence = re.fullmatch(r"```[a-z]*\n?(.*?)\n?```", text, re.S)
    if fence:
        text = fence.group(1).strip()
    text = re.sub(r"^(?:German|Deutsch|Translation|Übersetzung)\s*:\s*", "", text)
    if "\n" in text and "\n" not in source:
        # One line in, one line out: take the first non-empty line, never glue lines.
        text = next((ln for ln in text.splitlines() if ln.strip()), "")
    for q in ('"', "„", "“"):
        if len(text) > 1 and text.startswith(q) and text.endswith(("\"", "“", "”")) \
                and not source.strip().startswith('"'):
            text = text[1:-1]
            break
    return normalize_llm_text(text) or None


class OpenAICompatBackend(_ConcurrentBackend):
    """LM Studio (or any OpenAI-compatible chat-completions server)."""

    name = "lmstudio"

    def __init__(self, base_url: str, model: str, concurrency: int = 4, batch_lines: int = 1,
                 terms: TermContext | None = None, timeout: float = 600.0,
                 reasoning_effort: str = "low"):
        super().__init__(concurrency, batch_lines)
        self.base_url = base_url.rstrip("/")
        self.model = model
        if terms is None:
            _english_ui_labels()
            terms = _TERMS
        self.terms = terms
        self.timeout = timeout
        self.reasoning_effort = reasoning_effort

    def _system(self, text: str) -> str:
        hints = self.terms.hints(text)
        if not hints:
            return SYSTEM_PROMPT
        rows = []
        for en, de in hints:
            if en.startswith("not: "):
                rows.append(f'- write "{de}", not "{en[5:]}"')
            else:
                rows.append(f'- "{en}" -> "{de}"')
        return SYSTEM_PROMPT + "\n\nTerminology (required):\n" + "\n".join(rows)

    def _chat(self, system: str, user: str, max_tokens: int, lines: int) -> str:
        payload = {
            "model": self.model,
            "temperature": 0,
            "max_tokens": max_tokens,
            "reasoning_effort": self.reasoning_effort,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        }
        started = time.perf_counter()
        data = _http_post_json(f"{self.base_url}/chat/completions", payload,
                               self._remaining_timeout(self.timeout))
        elapsed = time.perf_counter() - started
        usage = data.get("usage") or {}
        message = ((data.get("choices") or [{}])[0].get("message") or {})
        content = message.get("content") or ""
        reasoning = message.get("reasoning") or message.get("reasoning_content") or ""
        self.log.add(latency=elapsed, lines=lines,
                     prompt_tokens=usage.get("prompt_tokens"),
                     completion_tokens=usage.get("completion_tokens"),
                     reasoning_tokens=(usage.get("completion_tokens_details") or {}).get("reasoning_tokens"),
                     answer_in_reasoning=bool(not content.strip() and reasoning.strip()))
        if not content.strip() and reasoning.strip():
            # Harmony/thinking models sometimes leave content empty and put the answer at the
            # end of the reasoning channel. Only a structured (JSON batch) answer can be
            # recovered from there with confidence; the caller decides.
            return "\x00REASONING\x00" + reasoning
        return content

    def translate(self, text: str) -> str:
        self._remaining_timeout(self.timeout)  # raises once the benchmark's time box is spent
        out = self._single(text)
        if out is None:
            raise RuntimeError("no usable translation")
        return out

    def _single(self, text: str) -> str | None:
        reply = self._chat(self._system(text),
                           "Translate this line into German. Reply with the translated line only.\n\n" + text,
                           max_tokens=4096, lines=1)
        if reply.startswith("\x00REASONING\x00"):
            return None
        return clean_single_reply(reply, text)

    def _translate_batch(self, batch: list[str]) -> list[str | None]:
        if len(batch) == 1:
            return [self._single_or_none(batch[0])]
        joined = "\n".join(batch)
        request = json.dumps({"lines": [{"id": i + 1, "text": t} for i, t in enumerate(batch)]},
                             ensure_ascii=False)
        reply = self._chat(
            self._system(joined),
            "Translate the \"text\" of every line into German. Each line is independent Markdown. "
            "Reply with JSON only, exactly this shape and the same ids: "
            "{\"translations\": [{\"id\": 1, \"text\": \"...\"}, ...]}\n\n" + request,
            max_tokens=2048 + 400 * len(batch), lines=len(batch))
        if reply.startswith("\x00REASONING\x00"):
            reply = reply[len("\x00REASONING\x00"):]
            last = reply.rfind('{"translations"')
            reply = reply[last:] if last != -1 else ""
        mapped = parse_batch_reply(reply, len(batch))
        if mapped is None:
            # Count or ids do not match: redo every line of the batch on its own.
            return [self._single_or_none(t) for t in batch]
        out: list[str | None] = []
        for source, target in zip(batch, mapped):
            if _token_multiset(source) != _token_multiset(target) or not target.strip() \
                    or looks_untranslated(source, target) \
                    or (english_leftovers(target) >= 3 and english_leftovers(source) >= 3) \
                    or untranslated_spans(source, target):
                out.append(self._single_or_none(source))
            else:
                out.append(normalize_llm_text(target))
        return out

    def _single_or_none(self, text: str) -> str | None:
        try:
            return self._single(text)
        except Exception as exc:  # noqa: BLE001
            print(f"  ! lmstudio: {exc}", file=sys.stderr)
            return None


class LibreTranslateBackend(_ConcurrentBackend):
    """LibreTranslate POST /translate — optional; untested unless you run a server
    (docker run -p 5000:5000 libretranslate/libretranslate). Set LIBRETRANSLATE_API_KEY
    if the server requires a key. It has no glossary support: terminology comes only
    from the glossary post-pass."""

    name = "libretranslate"

    def __init__(self, base_url: str, concurrency: int = 2, batch_lines: int = 20, timeout: float = 120.0):
        super().__init__(concurrency, batch_lines)
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _post(self, q):
        import os
        payload = {"q": q, "source": "en", "target": TARGET, "format": "text"}
        key = os.environ.get("LIBRETRANSLATE_API_KEY")
        if key:
            payload["api_key"] = key
        started = time.perf_counter()
        data = _http_post_json(f"{self.base_url}/translate", payload,
                               self._remaining_timeout(self.timeout))
        self.log.add(latency=time.perf_counter() - started,
                     lines=len(q) if isinstance(q, list) else 1,
                     prompt_tokens=None, completion_tokens=None, reasoning_tokens=None,
                     answer_in_reasoning=False)
        return data.get("translatedText")

    def translate(self, text: str) -> str:
        self._remaining_timeout(self.timeout)
        out = self._post(text)
        if not isinstance(out, str) or not out:
            raise RuntimeError("no usable translation")
        return out

    def _translate_batch(self, batch: list[str]) -> list[str | None]:
        out = self._post(batch if len(batch) > 1 else batch[0])
        if isinstance(out, str):
            out = [out]
        if not isinstance(out, list) or len(out) != len(batch):
            return [None] * len(batch)
        return [x or None for x in out]


class GoogleBackend:
    """deep_translator GoogleTranslator with the request log the benchmark reads."""

    name = "google"

    def __init__(self):
        if GoogleTranslator is None:
            sys.exit("Install: pip install deep-translator (or use --backend lmstudio)")
        self._t = GoogleTranslator(source="en", target=TARGET)
        self.log = _RequestLog()

    def translate(self, text: str) -> str:
        started = time.perf_counter()
        try:
            return self._t.translate(text)
        finally:
            self.log.add(latency=time.perf_counter() - started, lines=1, prompt_tokens=None,
                         completion_tokens=None, reasoning_tokens=None, answer_in_reasoning=False)

    def translate_batch(self, chunk: list[str]):
        started = time.perf_counter()
        try:
            return self._t.translate_batch(chunk)
        finally:
            self.log.add(latency=time.perf_counter() - started, lines=len(chunk), prompt_tokens=None,
                         completion_tokens=None, reasoning_tokens=None, answer_in_reasoning=False)


def make_backend(backend: str, model: str | None = None, base_url: str | None = None,
                 concurrency: int | None = None, batch_lines: int | None = None,
                 reasoning_effort: str | None = None, timeout: float | None = None):
    if backend == "google":
        return GoogleBackend()
    if backend == "lmstudio":
        return OpenAICompatBackend(base_url or DEFAULT_BASE_URLS["lmstudio"],
                                   model or DEFAULT_LMSTUDIO_MODEL,
                                   concurrency=concurrency or 4, batch_lines=batch_lines or 1,
                                   reasoning_effort=reasoning_effort or "low",
                                   timeout=timeout or 600.0)
    if backend == "libretranslate":
        return LibreTranslateBackend(base_url or DEFAULT_BASE_URLS["libretranslate"],
                                     concurrency=concurrency or 2, batch_lines=batch_lines or 20,
                                     timeout=timeout or 120.0)
    raise ValueError(f"unknown backend {backend!r}")


def add_backend_arguments(ap: argparse.ArgumentParser) -> None:
    """CLI options shared by translate_docs.py and translate_benchmark.py."""
    ap.add_argument("--backend", choices=["google", "lmstudio", "libretranslate"], default="google",
                    help="translation backend (default: google)")
    ap.add_argument("--base-url", help="server URL (lmstudio: http://localhost:1234/v1, "
                                       "libretranslate: http://localhost:5000)")
    ap.add_argument("--concurrency", type=int, help="parallel requests (lmstudio default 4, "
                                                     "libretranslate default 2; order is kept)")
    ap.add_argument("--batch-lines", type=int, help="masked lines per request as a numbered JSON "
                                                     "array (lmstudio default 1; mismatches fall "
                                                     "back to one line per request)")
    ap.add_argument("--reasoning-effort", choices=["low", "medium", "high"],
                    help="reasoning_effort sent to the lmstudio backend (default low)")
    ap.add_argument("--request-timeout", type=float, metavar="S",
                    help="per-request timeout in seconds (lmstudio default 600, libretranslate 120)")


def git_head_version(path: Path) -> str | None:
    """The committed (HEAD) content of a repo file, or None if unavailable."""
    try:
        rel = path.relative_to(REPO).as_posix()
        result = subprocess.run(
            ["git", "-C", str(REPO), "show", f"HEAD:{rel}"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
        return result.stdout if result.returncode == 0 else None
    except Exception:  # noqa: BLE001
        return None


def git_aligned_english(src: Path, max_versions: int = 200) -> str | None:
    """The English source of `src` that the committed German page was generated from.

    The per-line memory needs an English text that is line-aligned with the German page.
    Normally that is the English at HEAD (German is regenerated in the same commit as its
    English edit), but a branch that edited English over several commits without
    regenerating German has drifted, and on long-lived pages even the commit that last
    wrote the German page may hold newer English than the German was generated from.

    So walk the English history from the commit that last wrote the German page backwards
    (newest first) and take the version that has the German
    page's line count and whose validated memory covers the most lines of the CURRENT
    English (a stale version can pair more lines overall and still reuse fewer); ties go
    to the newest. Falls
    back to HEAD when no version is line-aligned (a hand-edited German page, or none yet)."""
    try:
        en_rel = src.relative_to(REPO).as_posix()
        de_path = DE / src.relative_to(EN)
        if not de_path.is_file():
            return git_head_version(src)
        de_md = de_path.read_text(encoding="utf-8")
        de_count = len(de_md.split("\n"))
        current = {masked for _i, masked, _s in translatable_lines(src.read_text(encoding="utf-8"))[1]}
        de_rel = de_path.relative_to(REPO).as_posix()
        de_commit = subprocess.run(
            ["git", "-C", str(REPO), "log", "-1", "--format=%H", "--", de_rel],
            capture_output=True, text=True, timeout=10).stdout.strip()
        if not de_commit:
            return git_head_version(src)
        # Only English that existed when the German page was committed can be its source. A
        # later English edit with an unchanged line count would otherwise "align" perfectly and
        # pair every edited line with the German of the line it replaced.
        commits = subprocess.run(
            ["git", "-C", str(REPO), "log", f"-{max_versions}", "--format=%H", de_commit, "--", en_rel],
            capture_output=True, text=True, timeout=30).stdout.split()
        best: tuple[int, str] | None = None
        for commit in commits:
            shown = subprocess.run(
                ["git", "-C", str(REPO), "show", f"{commit}:{en_rel}"],
                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
            if shown.returncode != 0 or len(shown.stdout.split("\n")) != de_count:
                continue
            size = len(current.intersection(build_page_memory(shown.stdout, de_md)))
            if best is None or size > best[0]:
                best = (size, shown.stdout)
        if best is None or best[0] == 0:
            print(f"  (memory: no English version of {en_rel} is line-aligned with its German "
                  f"page; using HEAD)")
            return git_head_version(src)
        return best[1]
    except Exception:  # noqa: BLE001
        return git_head_version(src)


PARTIAL = "partial:"


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


def plan_pages(pages: list[str] | None = None, force: bool = False,
               memory_from_git: bool = False, only_changed_lines: bool = False,
               base_ref: str | None = None) -> list[dict]:
    """The Markdown pages a run would translate, with their line memory and the
    number of (unique) lines that would go to the translator. Shared by the real run,
    --dry-run and scripts/translate_benchmark.py.

    With `only_changed_lines` (ignored with `force`) every page that has a line-aligned
    base (changed_lines_base) also gets "keep" (line index -> German line kept verbatim),
    "opcodes" and "old_de"; a page without one is translated as before."""
    stored_cache = load_cache()
    selected = {item.replace("\\", "/") for item in (pages or [])}
    plan: list[dict] = []
    for src in sorted(EN.rglob("*.md")):
        rel = src.relative_to(EN)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        if selected and rel.as_posix() not in selected:
            continue
        dst = DE / rel
        digest = hashlib.sha256(
            TRANSLATION_FORMAT_VERSION.encode("ascii") + b"\0" + src.read_bytes()
        ).hexdigest()
        cached = stored_cache.get(str(rel))
        if not force and cached == digest and dst.exists():
            continue
        md = src.read_text(encoding="utf-8")
        # Line-level reuse: align the English the German page was generated from with the
        # existing German page so only added/edited lines hit the translator. A page this
        # tool wrote in an earlier run (cached, or cached as partial after a failure) is
        # aligned with the current English.
        memory: dict[str, str] = {}
        if dst.exists():
            if cached in (digest, PARTIAL + digest):
                old_en = md
            elif memory_from_git:
                old_en = git_aligned_english(src)
            else:
                old_en = git_head_version(src)
            de_md = dst.read_text(encoding="utf-8")
            memory = build_page_memory(old_en, de_md)
            if not memory and old_en is not None and not memory_from_git \
                    and len(old_en.split("\n")) != len(de_md.split("\n")):
                print(f"  (memory: {rel} — HEAD English is not line-aligned with the German page; "
                      f"--memory-from-git reuses the lines that did not change)")
        keep = opcodes = old_de = None
        if only_changed_lines and not force:
            base = changed_lines_base(src, dst, md, cached, digest, base_ref, memory_from_git)
            if base is not None:
                keep = changed_line_keep(base[0], base[1], md)
                if keep is not None:
                    opcodes = en_alignment(base[0], md)
                    old_de = base[1]
                    # The memory must pair the same base: HEAD English (which may already hold
                    # the branch's committed edits) with the base German would hand an edited
                    # line the German of the line it replaced.
                    memory = build_page_memory(base[0], base[1])
            if keep is None and dst.exists():
                print(f"  (changed lines: no line-aligned base for {rel}; translating the page "
                      f"with the line memory)")
        _lines, jobs = translatable_lines(md)
        misses = {masked for i, masked, _s in jobs
                  if (keep is None or i not in keep)
                  and masked not in memory and not is_identifier_line(masked)}
        plan.append({"rel": rel, "src": src, "dst": dst, "md": md, "digest": digest,
                     "memory": memory, "jobs": len(jobs), "misses": len(misses),
                     "keep": keep, "opcodes": opcodes,
                     "old_de": old_de if old_de is not None
                     else (dst.read_text(encoding="utf-8") if dst.exists() else None)})
    return plan


def pages_changed_since(ref: str) -> list[str]:
    """EN-relative Markdown paths whose English changed since `ref` (commits and working tree)."""
    out = subprocess.run(
        ["git", "-C", str(REPO), "diff", "--name-only", ref, "--", EN.relative_to(REPO).as_posix()],
        capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        sys.exit(f"git diff {ref} failed: {out.stderr.strip()}")
    prefix = EN.relative_to(REPO).as_posix() + "/"
    return sorted(line[len(prefix):] for line in out.stdout.splitlines()
                  if line.startswith(prefix) and line.endswith(".md") and (REPO / line).is_file())


def all_translatable_lines() -> int:
    """Unique translatable (masked) lines across the whole English guide — the size of
    a full re-translation (--force)."""
    unique: set[str] = set()
    for src in sorted(EN.rglob("*.md")):
        if any(part in SKIP_DIRS for part in src.relative_to(EN).parts):
            continue
        unique.update(masked for _i, masked, _s in translatable_lines(src.read_text(encoding="utf-8"))[1]
                      if not is_identifier_line(masked))
    return len(unique)


def set_repo(repo: Path) -> None:
    """Point every repo-derived path at another checkout (see --repo)."""
    global REPO, SITE, EN, DE, CACHE, MKDOCS_YAML, I18N_DIR, GLOSSARY_PATH
    REPO = repo.resolve()
    SITE = REPO / "app-docs" / "site"
    EN = SITE / "docs" / "en"
    DE = SITE / "docs" / "de"
    CACHE = SITE / ".docs-translate-cache"
    MKDOCS_YAML = SITE / "mkdocs.yml"
    I18N_DIR = REPO / "src" / "main" / "resources" / "i18n"
    GLOSSARY_PATH = I18N_DIR / "glossary" / f"{TARGET}.json"


def default_repo() -> Path:
    """The korTTY checkout the current directory is in, else the one holding this script —
    so `<other-worktree>/scripts/translate_docs.py` run from a checkout works on that
    checkout."""
    try:
        top = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True,
                             text=True, timeout=10)
        if top.returncode == 0:
            path = Path(top.stdout.strip())
            if (path / "app-docs" / "site" / "docs" / "en").is_dir():
                return path
    except Exception:  # noqa: BLE001
        pass
    return REPO


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate docs/de from docs/en.")
    ap.add_argument("--repo", type=Path,
                    help="checkout to work on (default: the git checkout of the current "
                         "directory, else the one holding this script)")
    ap.add_argument("--force", action="store_true", help="re-translate all pages")
    ap.add_argument("--page", action="append", default=[],
                    help="translate only this EN-relative Markdown path (repeatable)")
    ap.add_argument("--model", help=f"model id for --backend lmstudio (default {DEFAULT_LMSTUDIO_MODEL})")
    ap.add_argument("--changed-since", metavar="REF",
                    help="translate only the pages whose English changed since this git ref "
                         "(adds to --page)")
    ap.add_argument("--memory-from-git", action="store_true",
                    help="build the line memory from the English of the commit that last wrote "
                         "each German page (for a branch that edited English without regenerating)")
    ap.add_argument("--only-changed-lines", action=argparse.BooleanOptionalAction, default=None,
                    help="translate only the English lines that are new or changed against the "
                         "English the German page was generated from and keep the existing German "
                         "of every other line (default: on with --changed-since, whose ref is the "
                         "base; --no-only-changed-lines translates every line the memory cannot "
                         "reuse, as before)")
    ap.add_argument("--dry-run", action="store_true",
                    help="only report how many lines each page would send to the translator")
    add_backend_arguments(ap)
    args = ap.parse_args()
    repo = args.repo or default_repo()
    if repo.resolve() != REPO:
        set_repo(repo)
        print(f"  (working on {REPO})")
    only_changed = bool(args.changed_since) if args.only_changed_lines is None \
        else args.only_changed_lines

    if not EN.is_dir():
        sys.exit(f"missing {EN}")
    pages = list(args.page)
    if args.changed_since:
        pages += pages_changed_since(args.changed_since)
        if not pages:
            print(f"No English page changed since {args.changed_since}.")
            return 0
    plan = plan_pages(pages, args.force, args.memory_from_git, only_changed, args.changed_since)
    if args.dry_run:
        total = 0
        for item in plan:
            total += item["misses"]
            kept = f", {len(item['keep'])} kept" if item.get("keep") is not None else ""
            print(f"  {item['rel']}: {item['misses']} of {item['jobs']} line(s) to translate{kept}")
        print(f"\nDry run: {len(plan)} page(s), {total} line(s) would be translated.")
        return 0

    translator = make_backend(args.backend, args.model, args.base_url, args.concurrency,
                              args.batch_lines, args.reasoning_effort, args.request_timeout)
    new_cache = load_cache()
    md_pages = [src for src in sorted(EN.rglob("*.md"))
                if not any(part in SKIP_DIRS for part in src.relative_to(EN).parts)]
    md_done = md_lines_fresh = md_lines_reused = 0
    written: dict[str, dict] = {}  # EN-relative page -> what sync_anchors_linewise needs
    all_failed: list[tuple[str, str]] = []  # (page, masked source text) that stayed English
    started = time.perf_counter()
    for item in plan:
        rel, dst = item["rel"], item["dst"]
        dst.parent.mkdir(parents=True, exist_ok=True)
        keep = item.get("keep")
        translated, reused, fresh, failed = translate_md(item["md"], translator, item["memory"],
                                                         keep=keep)
        dst.write_text(translated, encoding="utf-8")
        line_count = len(item["md"].split("\n"))
        written[rel.as_posix()] = {
            "old_de": item.get("old_de"), "opcodes": item.get("opcodes"),
            "fresh": set(range(line_count)) - set(keep or {})}
        # A page with a failed line is NOT recorded as done: the next run translates it
        # again, reusing every good line (the memory is aligned with the current English)
        # and retrying only the lines that kept their English text.
        new_cache[str(rel)] = (PARTIAL if failed else "") + item["digest"]
        md_done += 1
        md_lines_fresh += fresh
        md_lines_reused += reused
        if failed:
            all_failed.extend((str(rel), line) for line in failed)
        note = f", {len(failed)} FAILED — kept English" if failed else ""
        print(f"  translated {rel} ({fresh} line(s) translated, {reused} reused{note})", flush=True)

    save_cache(new_cache)
    # After every page exists in its final German wording — a link can point into a
    # page that this run skipped, so the anchors are only knowable at the end.
    dangling = 0
    if only_changed:
        dangling = sync_anchors_linewise(md_pages, written)
    else:
        sync_anchors(md_pages)
    elapsed = time.perf_counter() - started
    print(f"\nDone in {elapsed:.0f}s. translated {md_done} page(s) ({md_lines_fresh} line(s) "
          f"translated, {md_lines_reused} reused) with {args.backend}"
          f"{' / ' + translator.model if hasattr(translator, 'model') else ''}. "
          f"(assets are staged into docs/de by build-docs-site.py)")
    if all_failed:
        print(f"\n! {len(all_failed)} line(s) across {len({p for p, _ in all_failed})} "
              f"page(s) FAILED and kept their English text:")
        for rel, line in all_failed:
            preview = line if len(line) <= 80 else line[:77] + "..."
            print(f"    {rel}: {preview!r}")
        print("  These pages are not marked as translated — re-run the same command to retry "
              "only the failed lines.")
        return 1
    if dangling:
        print(f"\n! {dangling} link anchor(s) name no heading of their target page (see above).")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
