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

# markdown/yaml are only used by the anchor-sync pass, never by masking; stub them too
# so this test runs with a plain system python3 (e.g. from the Gradle check task).
try:
    import yaml  # noqa: F401
    import markdown  # noqa: F401
except ImportError:
    class _SafeLoaderStub:
        @classmethod
        def add_multi_constructor(cls, *_args, **_kwargs):
            pass

    yaml_stub = types.ModuleType("yaml")
    yaml_stub.SafeLoader = _SafeLoaderStub
    sys.modules.setdefault("yaml", yaml_stub)
    sys.modules.setdefault("markdown", types.ModuleType("markdown"))

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


class TokenDamageDetection(unittest.TestCase):
    """MT can merge, deform or invent placeholder tokens; none of that may leak."""

    def test_merged_adjacent_tokens_still_restore_without_a_leak(self):
        # The documented failure shape on the metacharacter list: the translator
        # swallows the ", " between two adjacent tokens.
        masked, store = td.mask(METACHARACTER_LIST)
        merged = masked.replace("KTPH006, KTPH007", "KTPH006KTPH007")
        restored = td.unmask(merged, store)
        self.assertNotIn("KTPH", restored)
        self.assertIn("`<``>`", restored)

    def test_deformed_token_fails_the_intact_check(self):
        masked, store = td.mask(METACHARACTER_LIST)
        deformed = masked.replace("KTPH007", "KTPH 007")
        self.assertFalse(td.placeholders_intact(deformed, store, masked))

    def test_invented_token_fails_the_intact_check(self):
        masked, store = td.mask(METACHARACTER_LIST)
        self.assertFalse(td.placeholders_intact(masked + " KTPH999", store, masked))


class _FakeTranslator:
    """Deterministic stand-in: full-line translation is scripted per masked input,
    fragment-level translate() prefixes 'DE ' so its use is observable."""

    def __init__(self, line_results=None, fragment_suffix=""):
        self.line_results = dict(line_results or {})
        self.fragment_suffix = fragment_suffix
        self.fragment_calls: list[str] = []

    def translate_batch(self, chunk):
        return [self.line_results.get(item, item) for item in chunk]

    def translate(self, text):
        self.fragment_calls.append(text)
        return f"DE {text}{self.fragment_suffix}"


class TranslateMdLeakGuard(unittest.TestCase):
    """End-to-end: no generated page may ever carry a KTPH remnant."""

    def test_deformed_full_line_translation_falls_back_to_fragments(self):
        masked, _store = td.mask(METACHARACTER_LIST)
        translator = _FakeTranslator({masked: masked.replace("KTPH007", "KTPH07")})
        out, _reused, _fresh, failed = td.translate_md(METACHARACTER_LIST, translator)
        self.assertNotIn("KTPH", out)
        self.assertIn("`<`", out)
        self.assertIn("`>`", out)
        self.assertTrue(translator.fragment_calls)
        self.assertEqual(failed, [])

    def test_poisoned_memory_line_is_repaired_before_it_ships(self):
        # Memory hits bypass placeholders_intact; the final guard must still catch
        # a stale line that kept a deformed token from an earlier defective run.
        masked, _store = td.mask(METACHARACTER_LIST)
        translator = _FakeTranslator()
        memory = {masked: masked.replace("KTPH007", "KTPH07")}
        out, _reused, _fresh, failed = td.translate_md(METACHARACTER_LIST, translator, memory)
        self.assertNotIn("KTPH", out)
        self.assertTrue(translator.fragment_calls)
        self.assertEqual(failed, [])

    def test_translator_that_keeps_inventing_tokens_ships_english_and_reports(self):
        masked, _store = td.mask(METACHARACTER_LIST)
        translator = _FakeTranslator(
            {masked: masked + " KTPH999"}, fragment_suffix=" KTPH998")
        out, _reused, _fresh, failed = td.translate_md(METACHARACTER_LIST, translator)
        self.assertNotIn("KTPH", out)
        self.assertEqual(out, td.apply_glossary(METACHARACTER_LIST))
        self.assertEqual(failed, [masked])


if __name__ == "__main__":
    unittest.main()
