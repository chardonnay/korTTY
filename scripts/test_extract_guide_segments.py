#!/usr/bin/env python3
"""Regression tests for scripts/extract_guide_segments.py.

Every case here is a defect that shipped in the first draft and that no existing check
caught: the manifests stayed internally consistent, so both --verify and the runtime's
per-segment round-trip guard passed while the segments covered markup instead of prose.

Run:  python3 scripts/test_extract_guide_segments.py
"""
from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import tempfile
import unittest

_SCRIPT = pathlib.Path(__file__).resolve().parent / "extract_guide_segments.py"
_spec = importlib.util.spec_from_file_location("extract_guide_segments", _SCRIPT)
egs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(egs)


def texts(html: str) -> list[str]:
    segments, _stats = egs.build_segments(html)
    return [segment["t"] for segment in segments]


def spans(html: str) -> list[tuple[int, int]]:
    segments, _stats = egs.build_segments(html)
    return [(segment["s"], segment["e"]) for segment in segments]


class MaskingTest(unittest.TestCase):

    def test_unmask_is_the_exact_inverse_of_mask(self):
        raw = 'Set <strong>Host key</strong> on the <em>Connection</em> tab &amp; save.'
        masked, frags = egs.mask_inline(raw)
        self.assertNotIn("<", masked)
        self.assertEqual(egs.unmask(masked, frags), raw)

    def test_entities_are_masked_after_tags_so_no_fragment_holds_a_token(self):
        # Masking entities first put KTPH001 inside the <img> fragment, which unmasking
        # could never resolve.
        raw = '<img alt="Backup &amp; restore" src="x.svg" /> caption'
        masked, frags = egs.mask_inline(raw)
        self.assertFalse(any("KTPH" in fragment for fragment in frags))
        self.assertEqual(egs.unmask(masked, frags), raw)

    def test_a_gt_inside_an_attribute_value_does_not_leak_markup_into_the_prose(self):
        self.assertEqual(texts('<p><img alt="a > b" src="x"> caption</p>'), ["KTPH000 caption"])
        self.assertEqual(texts("<p><img alt='a > b' src='x'> caption</p>"), ["KTPH000 caption"])


class OffsetTest(unittest.TestCase):

    DOC = ('<!doctype html>\n<html lang="en">\n<body>\n'
           '<p>Alpha{sep}beta</p>\n<p>Gamma delta</p>\n</body>\n</html>\n')

    def test_line_separators_python_splits_on_but_htmlparser_does_not(self):
        # str.splitlines() breaks on these; HTMLParser.updatepos counts only "\n". Building
        # the line table with splitlines shifted every offset after the first occurrence.
        for name, sep in [("LS", "\u2028"), ("PS", "\u2029"), ("NEL", "\x85"),
                          ("VT", "\x0b"), ("FF", "\x0c"), ("RS", "\x1e")]:
            with self.subTest(separator=name):
                html = self.DOC.format(sep=f" {sep} ")
                self.assertIn("Gamma delta", texts(html))
                for start, end in spans(html):
                    self.assertTrue(html[start:end])

    def test_offsets_are_utf16_code_units_not_code_points(self):
        # Java indexes Strings in UTF-16; an astral emoji counts 2 there and 1 in Python.
        html = '<html lang="en"><body><p>\U0001F44D ok</p><p>Second block</p></body></html>'
        utf16 = html.encode("utf-16-le")
        for start, end in spans(html):
            fragment = utf16[start * 2:end * 2].decode("utf-16-le")
            self.assertIn(fragment, html)

    def test_crlf_pages_hash_their_real_bytes(self):
        # Path.read_text() applies universal-newline translation, so the digest was taken
        # over LF text the runtime never sees and every page would be rejected.
        directory = pathlib.Path(tempfile.mkdtemp(prefix="kortty-guide-extract-test"))
        page = directory / "x.html"
        page.write_bytes(b'<html lang="en">\r\n<body>\r\n<p>Hello world</p>\r\n</body></html>\r\n')
        html, digest = egs.read_page(page)
        self.assertEqual(digest, hashlib.sha256(page.read_bytes()).hexdigest())
        self.assertIn("\r\n", html)


class StructureTest(unittest.TestCase):

    def test_an_unclosed_skip_tag_does_not_swallow_the_rest_of_the_page(self):
        # skip_depth stayed raised when unclosed entries were discarded, after which every
        # later text node was silently dropped.
        html = "<p>before</p><div><code>x</div><p>after the unclosed code</p>"
        self.assertIn("after the unclosed code", texts(html))

    def test_block_ness_propagates_through_non_block_ancestors(self):
        # mkdocs wraps a nav <label> in a plain <div> inside the <li>. Propagating only one
        # level let the <div> absorb it, so the <li> looked childless and was emitted as a
        # "leaf" spanning the whole nested navigation.
        html = ("<ul><li class='nested'><div class='wrap'><label>Settings</label></div>"
                "<nav><label>Settings</label></nav></li></ul>")
        self.assertEqual(texts(html), ["Settings", "Settings"])

    def test_leaf_blocks_keep_a_sentence_whole_instead_of_splitting_on_inline_markup(self):
        html = ("<p>Set <strong>Host key verification</strong> on the "
                "<em>Connection</em> tab.</p>")
        self.assertEqual(len(texts(html)), 1)
        self.assertIn("Host key verification", texts(html)[0])

    def test_segments_never_overlap(self):
        html = ('<html lang="en"><body><ul><li><p>One</p></li><li>Two</li></ul>'
                '<table><tr><td>Cell</td></tr></table></body></html>')
        ordered = spans(html)
        self.assertEqual(ordered, sorted(ordered))
        for (_, end), (start, _) in zip(ordered, ordered[1:]):
            self.assertLessEqual(end, start)


class BundledCorpusTest(unittest.TestCase):
    """The committed manifests must still describe the committed pages."""

    def test_bundled_manifests_verify_against_the_bundled_pages(self):
        import json

        guide = _SCRIPT.parent.parent / "src" / "main" / "resources" / "guide" / "en"
        manifests = guide / egs.MANIFEST_DIRNAME
        if not manifests.is_dir():
            self.skipTest("no manifests committed yet")
        problems: list[str] = []
        pages = sorted(guide.rglob("*.html"))
        self.assertTrue(pages, "no bundled pages found")
        for page in pages:
            manifest_path = manifests / (page.relative_to(guide).as_posix() + ".json")
            self.assertTrue(manifest_path.is_file(), f"missing manifest for {page.name}")
            html, digest = egs.read_page(page)
            problems += [f"{page.name}: {problem}" for problem in egs.verify_manifest(
                json.loads(manifest_path.read_text(encoding="utf-8")), html, digest)]
        self.assertEqual(problems, [])


if __name__ == "__main__":
    unittest.main()
