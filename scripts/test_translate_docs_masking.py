#!/usr/bin/env python3
"""Regression tests for the inline masking in scripts/translate_docs.py.

Every case here is the defect that shipped as a literal "<KTPH007>" on the German
input-hardening page: the inline-code pattern could not parse CommonMark's
double-backtick form (`` ` ``), masked the ", " runs BETWEEN the code spans of the
shell-metacharacter list instead of the spans themselves, and the HTML-tag pattern
then stored "<KTPH007>" as a fragment — a placeholder nested inside a store entry,
which ascending-order unmasking re-emitted as literal token text.

Run:  .venv-docs/bin/python scripts/test_translate_docs_masking.py
(markdown/pyyaml must be importable; the translator dependency is stubbed when absent).
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import types
import unittest

# translate_docs.py imports its translator at module level; masking needs none of it,
# so satisfy the import with a stub when the docs venv is not active.
try:
    import deep_translator  # noqa: F401
except ImportError:
    exceptions = types.ModuleType("deep_translator.exceptions")
    exceptions.BaseError = exceptions.RequestError = exceptions.TooManyRequests = Exception
    stub = types.ModuleType("deep_translator")
    stub.GoogleTranslator = object
    stub.exceptions = exceptions
    sys.modules["deep_translator"] = stub
    sys.modules["deep_translator.exceptions"] = exceptions

_SCRIPT = pathlib.Path(__file__).resolve().parent / "translate_docs.py"
_spec = importlib.util.spec_from_file_location("translate_docs", _SCRIPT)
td = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(td)


# The real input-hardening sentence that leaked the token.
METACHARACTER_LIST = (
    "Control characters, NUL bytes and shell metacharacters "
    "(`;`, `|`, `&`, `` ` ``, `$`, `\\`, `<`, `>`, embedded newlines) are rejected."
)


class MaskRoundTrip(unittest.TestCase):

    def test_double_backtick_escape_masks_as_one_span(self):
        _masked, store = td.mask(METACHARACTER_LIST)
        self.assertIn("`` ` ``", store)
        self.assertIn("`<`", store)
        self.assertIn("`>`", store)

    def test_no_fragment_nests_a_placeholder(self):
        _masked, store = td.mask(METACHARACTER_LIST)
        self.assertEqual([f for f in store if "KTPH" in f], [])

    def test_round_trip_restores_the_source(self):
        masked, store = td.mask(METACHARACTER_LIST)
        self.assertEqual(td.unmask(masked, store), METACHARACTER_LIST)
        self.assertTrue(td.placeholders_intact(masked, store, masked))

    def test_code_span_containing_a_backtick_run_is_one_fragment(self):
        # The ai-assistant rendering table writes fence markers as code spans.
        line = "| ` ```svg ` / ` ```xml ` code block containing an `<svg>` document |"
        masked, store = td.mask(line)
        self.assertIn("` ```svg `", store)
        self.assertIn("` ```xml `", store)
        self.assertIn("`<svg>`", store)
        self.assertEqual(td.unmask(masked, store), line)

    def test_plain_code_spans_still_mask(self):
        masked, store = td.mask("run `ssh` and `mosh` now")
        self.assertEqual(store, ["`ssh`", "`mosh`"])
        self.assertEqual(masked, "run KTPH000 and KTPH001 now")

    def test_unmask_restores_outer_fragments_before_embedded_tokens(self):
        # Defensive property: should a fragment ever nest a token again, descending
        # restore order re-expands it instead of leaking the token literally.
        self.assertEqual(td.unmask("aKTPH001b", ["X", "<KTPH000>"]), "a<X>b")


if __name__ == "__main__":
    unittest.main()
