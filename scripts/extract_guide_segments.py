#!/usr/bin/env python3
"""Extract translatable segments from the BUILT English guide HTML.

Companion to scripts/translate_docs.py, but one layer lower: translate_docs.py
translates Markdown at build time with a cloud API, this script prepares the
*rendered* HTML so korTTY can translate the guide at RUNTIME with a local AI
profile — the offline case where no translation API is reachable.

The output is a per-page manifest holding only the translatable prose plus the
UTF-16 offsets it came from. The HTML itself is NOT duplicated: the English page
already ships in the jar, and the runtime splices translated segments back into
it by offset. That keeps the manifests at roughly a quarter of the page size.

Two rules earn their keep and are the reason this is not a text-node splitter:

  * The unit is a LEAF BLOCK (p, li, td, h2 …), not a text node. A text-node
    split shatters one <p> of features/connections.html into 12 fragments such
    as ") or" and "(falling back to" — untranslatable on their own, and German
    word order routinely has to move words across an inline tag anyway. Inline
    markup inside the block is masked to KTPH### tokens, exactly like
    translate_docs.py masks inline Markdown.
  * Heading IDs are NOT translated. Slugs stay English, so #anchors keep
    resolving. The Markdown-level pipeline gets this wrong today: translating
    heading text re-slugs the anchor, which is why 12 of the 13 cross-page
    anchor links in guide/de are broken (e.g. de/features/connections.html ->
    ai-assistant.html#ai-agent-and-ai-planning, an ID that only exists in EN).

Offsets are UTF-16 code units, not Python code points: 7 guide pages contain
astral emoji (👍 💾 📖 …), where the two disagree, and the consumer indexes a
Java String. Getting this wrong corrupts exactly those pages and nothing else.

Usage:
  scripts/extract_guide_segments.py                  # whole EN tree
  scripts/extract_guide_segments.py --pages index.html features/connections.html
  scripts/extract_guide_segments.py --verify         # re-check invariants only
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_GUIDE_ROOT = REPO / "src" / "main" / "resources" / "guide" / "en"
MANIFEST_DIRNAME = "translation"

# Bump when masking/segmentation rules change: the runtime refuses a manifest
# whose formatVersion it does not know, rather than splicing stale offsets.
FORMAT_VERSION = 1

# The translation unit. Chosen so a whole sentence reaches the model; anything
# larger would drag block structure into the prompt, anything smaller fragments
# sentences around inline markup.
BLOCK_TAGS = frozenset({
    "p", "li", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6",
    "dt", "dd", "caption", "figcaption", "summary", "label", "figure",
})

# Never emit text from inside these. <pre> is code, <svg> is path data, and the
# <script id="__config"> search-UI strings need JSON-aware handling they do not
# get here (they are counted and reported, not extracted).
SKIP_TAGS = frozenset({"script", "style", "svg", "pre", "textarea", "code", "kbd"})

VOID_TAGS = frozenset({
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
    "meta", "param", "source", "track", "wbr",
})

# Attribute values a reader actually sees (tooltips, alt text, a11y labels).
TRANSLATABLE_ATTRS = frozenset({"title", "alt", "aria-label", "placeholder"})

# Proper nouns and language names: translating these makes the page worse.
# "Deutsch"/"English" label the language switcher and must stay in their own
# language; "korTTY" is the product.
ATTR_VALUE_DENYLIST = frozenset({
    "korTTY", "korTTY Guide", "korTTY on GitHub", "logo", "Deutsch", "English",
})

# Masked whole (element AND content): the content is code, a key cap, a glyph or
# vector data — never prose.
ATOMIC_PATTERNS = [
    re.compile(r"<svg\b[^>]*>.*?</svg>", re.S | re.I),
    re.compile(r"<code\b[^>]*>.*?</code>", re.S | re.I),
    re.compile(r"<kbd\b[^>]*>.*?</kbd>", re.S | re.I),
    # The ¶ permalink that follows every heading.
    re.compile(r'<a\b[^>]*class="[^"]*headerlink[^"]*"[^>]*>.*?</a>', re.S | re.I),
]
# Everything else keeps its tags masked but its text translatable, so
# "<strong>Host key verification</strong>" still reaches the model as prose.
# Quote-aware (unrolled loop, no nested quantifier): a plain <[^>]+> stops at the
# first '>' even inside an attribute value, so <img alt="a > b" src="x"> masked
# only up to the alt text and leaked ' b" src="x">' into the prose sent to the
# model — which would then come back translated, as markup.
TAG_PATTERN = re.compile(r"""<[^>"']*(?:(?:"[^"]*"|'[^']*')[^>"']*)*>""", re.S)
# Entities are masked LAST, and that order is load-bearing. Masking them first
# put a token inside a tag that a later pattern then captured whole — the
# fragment for <img alt="Backup &amp; restore flow"> came back holding a literal
# "KTPH001", which unmasking could never resolve. Running last means every
# entity inside a tag or an atomic element is already part of some fragment, so
# no fragment ever contains a token and unmasking stays a flat substitution.
ENTITY_PATTERN = re.compile(r"&(?:[a-zA-Z][a-zA-Z0-9]*|#\d+|#[xX][0-9a-fA-F]+);")

TOKEN_RE = re.compile(r"KTPH(\d{3})")
LETTER_RE = re.compile(r"[^\W\d_]", re.UNICODE)


def has_letter(text: str) -> bool:
    return bool(LETTER_RE.search(text))


def mask_inline(raw: str) -> tuple[str, list[str]]:
    """Replace inline markup with KTPH### tokens. unmask() is its exact inverse."""
    frags: list[str] = []

    def repl(match: re.Match) -> str:
        frags.append(match.group(0))
        return "KTPH%03d" % (len(frags) - 1)

    masked = raw
    for pattern in ATOMIC_PATTERNS:
        masked = pattern.sub(repl, masked)
    masked = TAG_PATTERN.sub(repl, masked)
    masked = ENTITY_PATTERN.sub(repl, masked)
    return masked, frags


def unmask(masked: str, frags: list[str]) -> str:
    """Inverse of mask_inline. Substitutes highest index first so that a nested
    token (always created earlier, hence lower-numbered) is still resolved if a
    future masking rule ever reintroduces nesting."""
    out = masked
    for i in range(len(frags) - 1, -1, -1):
        out = out.replace("KTPH%03d" % i, frags[i], 1)
    return out


class SegmentExtractor(HTMLParser):
    """Collects leaf-block spans, orphan text spans and translatable attributes.

    convert_charrefs=False is required: with entity conversion on, handle_data
    receives decoded text whose length no longer matches the source span, and
    every offset after the first &amp; would be wrong.
    """

    def __init__(self, html: str) -> None:
        super().__init__(convert_charrefs=False)
        self.html = html
        # Count ONLY "\n", matching HTMLParser.updatepos (`rawdata.count("\n", i, j)`).
        # str.splitlines() also breaks on \v \f \x1c \x1d \x1e \x85 U+2028 U+2029 and
        # lone \r; one such character anywhere in a page desynchronises this table from
        # getpos() and shifts every later offset. Nothing downstream would notice: the
        # manifest stays internally consistent (t is masked from whatever html[s:e] is),
        # so --verify and the runtime's round-trip guard both still pass while the
        # segments cover markup instead of prose.
        self.line_starts = [0]
        for index, char in enumerate(html):
            if char == "\n":
                self.line_starts.append(index + 1)
        self.stack: list[dict] = []
        self.skip_depth = 0
        self.leaf_blocks: list[tuple[int, int]] = []
        self.text_spans: list[tuple[int, int]] = []
        self.attr_spans: list[tuple[int, int]] = []
        self.title_span: tuple[int, int] | None = None
        self.html_lang_span: tuple[int, int] | None = None
        self.config_translations = 0

    # ------------------------------------------------------------- positions

    def src_offset(self) -> int:
        line, col = self.getpos()
        return self.line_starts[line - 1] + col

    def tag_end(self, start: int) -> int:
        """Index just past the '>' of the tag starting at `start`, quote-aware."""
        i = start + 1
        quote = None
        while i < len(self.html):
            char = self.html[i]
            if quote:
                if char == quote:
                    quote = None
            elif char in "\"'":
                quote = char
            elif char == ">":
                return i + 1
            i += 1
        return len(self.html)

    def attr_value_span(self, tag_start: int, tag_stop: int, name: str) -> tuple[int, int] | None:
        raw = self.html[tag_start:tag_stop]
        match = re.search(r'\b%s\s*=\s*"([^"]*)"' % re.escape(name), raw, re.I)
        if not match:
            return None
        return tag_start + match.start(1), tag_start + match.end(1)

    # --------------------------------------------------------------- parsing

    def handle_starttag(self, tag: str, attrs) -> None:
        start = self.src_offset()
        stop = self.tag_end(start)

        if tag == "html" and self.html_lang_span is None:
            self.html_lang_span = self.attr_value_span(start, stop, "lang")
        if tag == "meta":
            attr_map = {k: (v or "") for k, v in attrs}
            if attr_map.get("name") == "description":
                span = self.attr_value_span(start, stop, "content")
                if span:
                    self.attr_spans.append(span)
        if tag == "script":
            # The Material search UI strings live in a JSON blob here. Extracting
            # them needs JSON-aware escaping; report the count so the gap is visible.
            body_end = self.html.find("</script>", stop)
            if body_end > 0 and '"translations"' in self.html[stop:body_end]:
                self.config_translations += 1

        if self.skip_depth == 0:
            for name, value in attrs:
                if name in TRANSLATABLE_ATTRS and value and has_letter(value) \
                        and value.strip() not in ATTR_VALUE_DENYLIST:
                    span = self.attr_value_span(start, stop, name)
                    if span:
                        self.attr_spans.append(span)

        if tag in VOID_TAGS:
            return
        skip = tag in SKIP_TAGS
        if skip:
            self.skip_depth += 1
        self.stack.append({"tag": tag, "content_start": stop, "block_children": 0,
                           "skip": skip})

    def handle_startendtag(self, tag: str, attrs) -> None:
        # Self-closing form: attributes still matter, nesting does not.
        start = self.src_offset()
        stop = self.tag_end(start)
        if self.skip_depth == 0:
            for name, value in attrs:
                if name in TRANSLATABLE_ATTRS and value and has_letter(value) \
                        and value.strip() not in ATTR_VALUE_DENYLIST:
                    span = self.attr_value_span(start, stop, name)
                    if span:
                        self.attr_spans.append(span)

    def handle_endtag(self, tag: str) -> None:
        if tag in VOID_TAGS:
            return
        index = next((i for i in range(len(self.stack) - 1, -1, -1)
                      if self.stack[i]["tag"] == tag), None)
        if index is None:
            return  # stray close tag; mkdocs output is well formed, be defensive
        entry = self.stack[index]
        # Anything left above `index` was never closed — discard it with the pop, and
        # give back the skip_depth those entries claimed. Dropping them without that
        # correction leaves skip_depth permanently raised (an unclosed <code> is enough),
        # after which every remaining text node on the page is silently ignored and the
        # page translates only down to the defect.
        discarded = self.stack[index:]
        del self.stack[index:]
        for dropped in discarded:
            if dropped.get("skip"):
                self.skip_depth = max(0, self.skip_depth - 1)

        content_end = self.src_offset()
        if tag == "title" and self.skip_depth == 0:
            self.title_span = (entry["content_start"], content_end)
        if tag in BLOCK_TAGS and self.skip_depth == 0 and entry["block_children"] == 0:
            self.leaf_blocks.append((entry["content_start"], content_end))
        # Block-ness has to travel up through non-block ancestors. mkdocs wraps a
        # nav <label> in a plain <div> inside the <li>; propagating only one level
        # let the <div> absorb the signal, so the <li> still looked childless and
        # was emitted as a "leaf" spanning the entire nested navigation subtree.
        contributed = entry["block_children"] + (1 if tag in BLOCK_TAGS else 0)
        if contributed and self.stack:
            self.stack[-1]["block_children"] += contributed

    def handle_data(self, data: str) -> None:
        if self.skip_depth or not has_letter(data):
            return
        start = self.src_offset()
        self.text_spans.append((start, start + len(data)))


def utf16_offsets(html: str) -> list[int]:
    """Prefix table mapping a Python code-point index to a UTF-16 code-unit index.

    Java indexes Strings in UTF-16 units, so an astral emoji (👍, U+1F44D) counts
    as 2 there and 1 here. Seven guide pages contain such characters.
    """
    table = [0] * (len(html) + 1)
    total = 0
    for i, char in enumerate(html):
        table[i] = total
        total += 2 if ord(char) > 0xFFFF else 1
    table[len(html)] = total
    return table


def build_segments(html: str) -> tuple[list[dict], dict]:
    parser = SegmentExtractor(html)
    parser.feed(html)
    parser.close()

    spans: list[tuple[int, int, str]] = [(s, e, "block") for s, e in parser.leaf_blocks]
    if parser.title_span:
        spans.append((parser.title_span[0], parser.title_span[1], "title"))
    spans.extend((s, e, "attr") for s, e in parser.attr_spans)

    covered = sorted((s, e) for s, e, _ in spans)

    def inside_covered(start: int, end: int) -> bool:
        return any(cs <= start and end <= ce for cs, ce in covered)

    # Text the block pass did not reach — navigation labels, footer, headings
    # inlined into the sidebar. Chrome is ~84% duplicate across pages, so the
    # runtime's dedup makes these nearly free.
    for start, end in parser.text_spans:
        if not inside_covered(start, end):
            spans.append((start, end, "text"))

    spans.sort(key=lambda item: item[0])

    segments: list[dict] = []
    dropped_overlap = 0
    dropped_nested_attr = 0
    last_end = -1
    u16 = utf16_offsets(html)
    for start, end, kind in spans:
        if start < last_end:
            # An attribute inside a leaf block sits within a masked fragment, so
            # its value never reaches the model. Known gap: article image alt text
            # and the "Permanent link" heading tooltip stay English.
            if kind == "attr":
                dropped_nested_attr += 1
            else:
                dropped_overlap += 1
            continue
        raw = html[start:end]
        if not raw.strip() or not has_letter(raw):
            continue
        if "KTPH" in raw:
            dropped_overlap += 1
            continue  # a literal token in the source would break unmasking
        masked, frags = mask_inline(raw)
        if not has_letter(masked):
            continue  # e.g. a <td> holding only key caps
        if unmask(masked, frags) != raw:
            raise AssertionError("masking is not reversible at offset %d" % start)
        segments.append({
            "s": u16[start],
            "e": u16[end],
            "k": kind,
            "t": masked,
            "f": frags,
        })
        last_end = end

    stats = {
        "dropped_overlap": dropped_overlap,
        "dropped_nested_attr": dropped_nested_attr,
        "config_translation_blobs": parser.config_translations,
        "html_lang_span": parser.html_lang_span,
    }
    return segments, stats


def read_page(path: Path) -> tuple[str, str]:
    """Returns (html, sha256-of-the-actual-file-bytes).

    Deliberately not Path.read_text(): that applies universal-newline translation, so a
    CRLF page decodes to LF. Every offset would then be computed against text the runtime
    never sees, and the digest would be taken over the translated bytes rather than the
    file's — the Java side re-hashes the real resource bytes and would reject all 54 pages.
    """
    raw = path.read_bytes()
    return raw.decode("utf-8"), hashlib.sha256(raw).hexdigest()


def extract_page(path: Path, guide_root: Path) -> dict:
    html, digest = read_page(path)
    segments, stats = build_segments(html)
    return {
        "formatVersion": FORMAT_VERSION,
        "sourceLang": "en",
        "page": path.relative_to(guide_root).as_posix(),
        "sourceSha256": digest,
        "sourceLengthUtf16": len(html.encode("utf-16-le")) // 2,
        "segments": segments,
        "_stats": stats,
    }


def verify_manifest(manifest: dict, html: str, digest: str) -> list[str]:
    """Re-derive every segment from the page and report any drift."""
    problems: list[str] = []
    if digest != manifest["sourceSha256"]:
        problems.append("sourceSha256 mismatch — page changed since extraction")
        return problems
    # Rebuild the code-point view from UTF-16 offsets to index the Python string.
    u16 = utf16_offsets(html)
    back = {}
    for cp_index, u16_index in enumerate(u16):
        back.setdefault(u16_index, cp_index)
    last = -1
    for i, seg in enumerate(manifest["segments"]):
        start, end = back.get(seg["s"]), back.get(seg["e"])
        if start is None or end is None:
            problems.append("segment %d: offset not on a character boundary" % i)
            continue
        if start < last:
            problems.append("segment %d: overlaps the previous segment" % i)
        last = end
        if unmask(seg["t"], seg["f"]) != html[start:end]:
            problems.append("segment %d: does not round-trip to the source" % i)
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description="Extract guide translation manifests.")
    ap.add_argument("--guide-root", type=Path, default=DEFAULT_GUIDE_ROOT,
                    help="built English guide tree (default: bundled resources)")
    ap.add_argument("--out", type=Path, default=None,
                    help="manifest output dir (default: <guide-root>/translation)")
    ap.add_argument("--pages", nargs="*", default=None,
                    help="only these pages, relative to guide-root")
    ap.add_argument("--verify", action="store_true",
                    help="check existing manifests against the pages, write nothing")
    args = ap.parse_args()

    guide_root: Path = args.guide_root
    if not guide_root.is_dir():
        sys.exit("missing guide root: %s" % guide_root)
    out_dir: Path = args.out or (guide_root / MANIFEST_DIRNAME)

    if args.pages:
        pages = [guide_root / p for p in args.pages]
        missing = [p for p in pages if not p.is_file()]
        if missing:
            sys.exit("no such page(s): %s" % ", ".join(str(m) for m in missing))
    else:
        pages = sorted(p for p in guide_root.rglob("*.html"))

    if args.verify:
        failures = 0
        for page in pages:
            manifest_path = out_dir / (page.relative_to(guide_root).as_posix() + ".json")
            if not manifest_path.is_file():
                print("  MISSING manifest for %s" % page.relative_to(guide_root))
                failures += 1
                continue
            page_html, page_digest = read_page(page)
            problems = verify_manifest(json.loads(manifest_path.read_text(encoding="utf-8")),
                                       page_html, page_digest)
            for problem in problems:
                print("  %s: %s" % (page.relative_to(guide_root), problem))
            failures += len(problems)
        print("\nverify: %d page(s), %d problem(s)" % (len(pages), failures))
        return 1 if failures else 0

    out_dir.mkdir(parents=True, exist_ok=True)
    index = []
    all_texts: dict[str, int] = {}
    total_segments = total_chars = 0
    config_blobs = nested_attrs = 0
    for page in pages:
        manifest = extract_page(page, guide_root)
        stats = manifest.pop("_stats")
        config_blobs += stats["config_translation_blobs"]
        nested_attrs += stats["dropped_nested_attr"]
        rel = manifest["page"]
        target = out_dir / (rel + ".json")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
                          encoding="utf-8")
        for seg in manifest["segments"]:
            all_texts[seg["t"]] = all_texts.get(seg["t"], 0) + 1
            total_chars += len(seg["t"])
        total_segments += len(manifest["segments"])
        index.append({"page": rel, "segments": len(manifest["segments"])})
        print("  %-52s %4d segment(s)%s" % (
            rel, len(manifest["segments"]),
            "  (%d overlap dropped)" % stats["dropped_overlap"] if stats["dropped_overlap"] else ""))

    # Asset inventory. A generated language tree has to be self-contained: its pages are
    # loaded from the config directory over file:, and a file: document cannot pull the
    # stylesheets and images back out of the jar. The runtime copies this list out of the
    # classpath; listing it here avoids having to walk the jar at runtime.
    assets = sorted(
        path.relative_to(guide_root).as_posix()
        for path in guide_root.rglob("*")
        if path.is_file() and path.suffix != ".html"
        and MANIFEST_DIRNAME not in path.relative_to(guide_root).parts)
    (out_dir / "index.json").write_text(
        json.dumps({"formatVersion": FORMAT_VERSION, "sourceLang": "en", "pages": index,
                    "assets": assets},
                   ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8")
    print("asset(s) recorded for staging: %d" % len(assets))

    distinct_chars = sum(len(t) for t in all_texts)
    print("\n%d page(s), %d segment(s), %d chars" % (len(pages), total_segments, total_chars))
    print("after dedup: %d distinct segment(s), %d chars (%.1f%% saved)" % (
        len(all_texts), distinct_chars,
        100.0 * (total_chars - distinct_chars) / total_chars if total_chars else 0.0))
    if nested_attrs:
        print("note: %d attribute value(s) sit inside a leaf block and stay English "
              "(image alt text, \"Permanent link\" tooltips)" % nested_attrs)
    if config_blobs:
        print("note: %d page(s) carry a <script> search-UI translations blob "
              "that is NOT extracted yet" % config_blobs)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
