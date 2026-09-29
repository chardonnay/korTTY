#!/usr/bin/env python3
"""Tests for the translation backends of scripts/translate_docs.py and for
scripts/translate_benchmark.py — the pure parts only (no server, no network).

Run:  python3 -m unittest scripts.test_translate_docs_backends
(plain python3 is enough: deep_translator/markdown/yaml are stubbed when absent;
Gradle: ./gradlew translateDocsBackendTest).
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import random
import sys
import threading
import time
import types
import unittest

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

_DIR = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(_DIR))


def _load(name: str):
    spec = importlib.util.spec_from_file_location(name, _DIR / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


td = _load("translate_docs")
tb = _load("translate_benchmark")

# Small, fixed terminology so the tests do not depend on the real bundles.
TERMS = td.TermContext(
    en_props={"snippets.title": "Snippet Manager", "snippets.save": "_Save",
              "snippets.saveAll": "Save all", "menu.tools": "Tools",
              "snippets.discard": "Discard"},
    de_props={"snippets.title": "Snippet-Verwaltung", "snippets.save": "_Speichern",
              "snippets.saveAll": "Alle speichern", "menu.tools": "Werkzeuge",
              "snippets.discard": "Verwerfen"},
    glossary_rows=[
        {"from": "Snippet Manager", "to": "Snippet-Manager"},
        {"from": "**Verwerfen lassen**", "to": "**Verwerfen**", "note": "UI label snippets.discard."},
        {"from": "Store", "to": "Wissensspeicher"},
    ])


class ScriptedBackend(td.OpenAICompatBackend):
    """OpenAICompatBackend whose server is a function of (system, user) -> reply."""

    def __init__(self, reply, batch_lines=1, concurrency=1):
        super().__init__("http://unused", "stub", concurrency=concurrency,
                         batch_lines=batch_lines, terms=TERMS)
        self.reply = reply
        self.calls: list[str] = []
        self._lock = threading.Lock()

    def _chat(self, system, user, max_tokens, lines):
        with self._lock:
            self.calls.append(user)
        return self.reply(system, user)


def _batch_payload(user: str) -> list[dict]:
    return json.loads(user[user.index('{"lines"'):])["lines"]


def _german(text: str) -> str:
    return text.replace("Open", "Öffnen").replace("the", "die").replace("file", "Datei")


class BatchReplyParsing(unittest.TestCase):
    def test_object_shape_maps_by_id(self):
        reply = '{"translations": [{"id": 2, "text": "b"}, {"id": 1, "text": "a"}]}'
        self.assertEqual(td.parse_batch_reply(reply, 2), ["a", "b"])

    def test_fenced_json_and_surrounding_prose(self):
        reply = 'Here you go:\n```json\n{"translations": [{"id": 1, "text": "x"}]}\n```'
        self.assertEqual(td.parse_batch_reply(reply, 1), ["x"])

    def test_bare_string_list(self):
        self.assertEqual(td.parse_batch_reply('["a", "b"]', 2), ["a", "b"])

    def test_count_mismatch_is_rejected(self):
        self.assertIsNone(td.parse_batch_reply('{"translations": [{"id": 1, "text": "a"}]}', 2))
        self.assertIsNone(td.parse_batch_reply('["a"]', 2))

    def test_duplicate_or_missing_ids_are_rejected(self):
        dup = '{"translations": [{"id": 1, "text": "a"}, {"id": 1, "text": "b"}]}'
        gap = '{"translations": [{"id": 1, "text": "a"}, {"id": 3, "text": "b"}]}'
        self.assertIsNone(td.parse_batch_reply(dup, 2))
        self.assertIsNone(td.parse_batch_reply(gap, 2))

    def test_garbage_is_rejected(self):
        self.assertIsNone(td.parse_batch_reply("not json", 1))
        self.assertIsNone(td.parse_batch_reply("", 1))


class BatchMapping(unittest.TestCase):
    def test_batch_is_mapped_back_in_order(self):
        def reply(_system, user):
            lines = _batch_payload(user)
            return json.dumps({"translations": [{"id": x["id"], "text": _german(x["text"])}
                                                for x in reversed(lines)]})
        backend = ScriptedBackend(reply, batch_lines=3)
        out = backend.translate_lines(["Open KTPH000", "Open the file KTPH000", "Open"])
        self.assertEqual(out, ["Öffnen KTPH000", "Öffnen die Datei KTPH000", "Öffnen"])
        self.assertEqual(len(backend.calls), 1)

    def test_line_that_lost_a_token_falls_back_to_a_single_request(self):
        def reply(_system, user):
            if '{"lines"' in user:
                lines = _batch_payload(user)
                return json.dumps({"translations": [
                    {"id": x["id"], "text": "Öffnen" if x["id"] == 2 else _german(x["text"])}
                    for x in lines]})
            return _german(user.split("\n\n", 1)[1])
        backend = ScriptedBackend(reply, batch_lines=2)
        out = backend.translate_lines(["Open KTPH000", "Open KTPH000 KTPH001"])
        self.assertEqual(out, ["Öffnen KTPH000", "Öffnen KTPH000 KTPH001"])
        self.assertEqual(len(backend.calls), 2)  # the batch + one single-line retry

    def test_count_mismatch_redoes_every_line_alone(self):
        def reply(_system, user):
            if '{"lines"' in user:
                return '{"translations": [{"id": 1, "text": "nur eine"}]}'
            return _german(user.split("\n\n", 1)[1])
        backend = ScriptedBackend(reply, batch_lines=3)
        out = backend.translate_lines(["Open a", "Open b", "Open c"])
        self.assertEqual(out, ["Öffnen a", "Öffnen b", "Öffnen c"])
        self.assertEqual(len(backend.calls), 4)

    def test_echoed_english_line_is_redone_alone(self):
        def reply(_system, user):
            if '{"lines"' in user:
                lines = _batch_payload(user)
                return json.dumps({"translations": [{"id": x["id"], "text": x["text"]} for x in lines]})
            return "Öffnen Sie die Datei mit dem Editor."
        backend = ScriptedBackend(reply, batch_lines=2)
        out = backend.translate_lines(["Open the file with the editor.", "KTPH000"])
        self.assertEqual(out[0], "Öffnen Sie die Datei mit dem Editor.")

    def test_answer_only_in_reasoning_channel_is_recovered_for_batches(self):
        answer = '{"translations": [{"id": 1, "text": "A"}, {"id": 2, "text": "B"}]}'
        backend = ScriptedBackend(lambda s, u: "\x00REASONING\x00thinking... " + answer, batch_lines=2)
        self.assertEqual(backend.translate_lines(["a", "b"]), ["A", "B"])

    def test_single_line_answer_only_in_reasoning_is_a_failure(self):
        backend = ScriptedBackend(lambda s, u: "\x00REASONING\x00Öffnen", batch_lines=1)
        self.assertEqual(backend.translate_lines(["Open"]), [None])

    def test_concurrency_keeps_the_input_order(self):
        def reply(_system, user):
            time.sleep(random.uniform(0, 0.02))
            return "DE " + user.split("\n\n", 1)[1]
        backend = ScriptedBackend(reply, batch_lines=1, concurrency=6)
        texts = [f"line {i}" for i in range(30)]
        self.assertEqual(backend.translate_lines(texts), [f"DE {t}" for t in texts])

    def test_prompt_carries_terminology_and_register(self):
        seen = {}

        def reply(system, user):
            seen["system"] = system
            return "Klicken Sie auf **Alle speichern** im Snippet-Manager"
        ScriptedBackend(reply).translate_lines(["Click **Save all** in the Snippet Manager"])
        self.assertIn('"Save all" -> "Alle speichern"', seen["system"])
        # The guide's own term (glossary) wins over the app label for the same English words.
        self.assertIn('"Snippet Manager" -> "Snippet-Manager"', seen["system"])
        self.assertNotIn("Snippet-Verwaltung", seen["system"])
        self.assertIn('"Sie"', seen["system"])


class Validation(unittest.TestCase):
    def test_table_row_with_a_key_after_the_first_pipe_is_intact(self):
        masked, store = td.mask("| ++ctrl+w++ | Close Tab |")
        self.assertTrue(td.placeholders_intact(masked.replace("Close Tab", "Tab schließen"), store, masked))

    def test_table_row_with_a_link_is_intact(self):
        masked, store = td.mask("| [Colors](colors.md) | Color profile |")
        self.assertTrue(td.placeholders_intact(masked.replace("Colors", "Farben"), store, masked))

    def test_key_moved_into_another_cell_still_fails(self):
        masked, store = td.mask("| a | ++k++ |")
        self.assertEqual(masked, "KTPH001 a KTPH002 KTPH000 KTPH003")
        self.assertFalse(td.placeholders_intact("KTPH001 a KTPH000 KTPH002 KTPH003", store, masked))

    def test_remask_reuses_a_row_with_a_key(self):
        masked, store = td.mask("| ++ctrl+w++ | Close Tab |")
        self.assertIsNotNone(td.remask("| ++ctrl+w++ | Tab schließen |", store))

    def test_remask_still_requires_list_markers_at_the_start(self):
        _masked, store = td.mask("- API or CLI")
        self.assertIsNone(td.remask("API- oder CLI", store))

    def test_echo_and_english_leftovers_are_not_acceptable(self):
        masked, store = td.mask("Open the file with the editor and save it.")
        self.assertFalse(td.acceptable(masked, store, masked))
        leftover = "Öffnen Sie the file with the editor and save it."
        self.assertFalse(td.acceptable(leftover, store, masked))
        self.assertTrue(td.acceptable("Öffnen Sie die Datei im Editor und speichern Sie sie.", store, masked))

    def test_link_text_kept_in_english_is_flagged(self):
        masked, _ = td.mask("See [Reviewing an AI change](#reviewing) below.")
        out = masked.replace("See", "Siehe").replace("below", "unten")
        self.assertEqual(td.untranslated_spans(masked, out), ["Reviewing an AI change"])

    def test_identifier_lines(self):
        for line in ("### llm/models.xml", "### coding-agents/", "### master.autounlock"):
            self.assertTrue(td.is_identifier_line(td.mask(line)[0]), line)
        for line in ("### Terminal logs", "- Make the command case-insensitive", "## Overview"):
            self.assertFalse(td.is_identifier_line(td.mask(line)[0]), line)


class _Translator:
    """Google duck type: scripted translate_batch, fragment translate()."""

    def __init__(self, line, fragment=lambda t: "DE " + t):
        self.line, self.fragment = line, fragment
        self.batches = 0

    def translate_batch(self, chunk):
        self.batches += 1
        return [self.line(t, self.batches) for t in chunk]

    def translate(self, text):
        return self.fragment(text)


class TranslateMasked(unittest.TestCase):
    def test_bad_first_answer_is_retried_once(self):
        masked, store = td.mask("Press ++ctrl+s++ to save the file.")
        tr = _Translator(lambda t, n: t.replace("KTPH000", "") if n == 1
                         else t.replace("Press", "Drücken Sie").replace("to save the file", "zum Speichern"))
        results, stats = td.translate_masked(tr, [(masked, store)])
        self.assertIn("Drücken Sie", results[0])
        self.assertEqual((stats["retried"], stats["retry_ok"], stats["failed"]), (1, 1, 0))

    def test_line_that_nothing_can_translate_is_failed_not_english(self):
        masked, store = td.mask("Press ++ctrl+s++ to save the file.")

        def boom(_t):
            raise RuntimeError("down")
        tr = _Translator(lambda t, n: None, fragment=boom)
        results, stats = td.translate_masked(tr, [(masked, store)])
        self.assertEqual(results, [None])
        self.assertEqual(stats["failed"], 1)

    def test_translate_md_reports_the_failed_line(self):
        def boom(_t):
            raise RuntimeError("down")
        page = "# Title\n\nPress ++ctrl+s++ to save the file.\n"
        out, _reused, _fresh, failed = td.translate_md(page, _Translator(lambda t, n: None, fragment=boom))
        self.assertEqual(len(failed), 2)
        self.assertIn("Press ++ctrl+s++ to save the file.", out)

    def test_identifier_heading_is_never_sent(self):
        tr = _Translator(lambda t, n: "SHOULD NOT BE USED")
        out, _r, fresh, failed = td.translate_md("### llm/models.xml\n", tr)
        self.assertEqual(out, "### llm/models.xml\n")
        self.assertEqual((tr.batches, failed), (0, []))


class TermContextTests(unittest.TestCase):
    def test_bold_and_menu_path_labels(self):
        labels = TERMS.matched_labels("Click **Save** under Tools → Snippet Manager")
        self.assertEqual(labels.get("Save"), "Speichern")
        self.assertEqual(labels.get("Tools"), "Werkzeuge")

    def test_multiword_label_needs_word_boundaries(self):
        self.assertIn("Save all", TERMS.matched_labels("use Save all here"))
        self.assertNotIn("Save all", TERMS.matched_labels("use Save allowed here"))

    def test_single_word_glossary_rows_are_not_hints(self):
        self.assertNotIn("Store", dict(TERMS.hints("Store it in the folder")))

    def test_note_key_rows_become_avoid_hints(self):
        hints = dict(TERMS.hints("Click **Discard**"))
        self.assertEqual(hints.get("not: Verwerfen lassen"), "Verwerfen")

    def test_expected_terms_skip_avoid_hints(self):
        self.assertEqual(TERMS.expected_terms("Click **Discard**"), ["Verwerfen"])


class Helpers(unittest.TestCase):
    def test_clean_single_reply(self):
        self.assertEqual(td.clean_single_reply('```\n"Hallo Welt"\n```', "Hello world"), "Hallo Welt")
        self.assertEqual(td.clean_single_reply("German: Hallo", "Hello"), "Hallo")
        self.assertEqual(td.clean_single_reply("Host­schlüssel‑Liste", "x"), "Hostschlüssel-Liste")
        self.assertEqual(td.clean_single_reply("Eins\nZwei", "One"), "Eins")

    def test_parse_properties(self):
        props = td.parse_properties("# c\na=Stra\\u00dfe\nb = one \\\n  two\nc:x\\ty\n")
        self.assertEqual(props, {"a": "Straße", "b": "one two", "c": "x\ty"})

    def test_clean_ui_label(self):
        self.assertEqual(td.clean_ui_label("_Open Project..."), "Open Project")
        self.assertIsNone(td.clean_ui_label("Deleted {0} files"))
        self.assertIsNone(td.clean_ui_label("This is a sentence."))


class ChrF(unittest.TestCase):
    def test_identical_is_100_and_disjoint_is_0(self):
        self.assertAlmostEqual(tb.corpus_chrf(["Das ist gut."], ["Das ist gut."]), 100.0)
        self.assertEqual(tb.corpus_chrf(["xyz"], ["abc"]), 0.0)

    def test_closer_hypothesis_scores_higher(self):
        ref = ["Öffnen Sie die Einstellungen und speichern Sie die Datei."]
        close = tb.corpus_chrf(["Öffnen Sie die Einstellungen und sichern Sie die Datei."], ref)
        far = tb.corpus_chrf(["Die Datei ist geöffnet."], ref)
        self.assertTrue(0 < far < close < 100)

    def test_corpus_statistics_are_summed_not_averaged(self):
        stats = [a + b for a, b in zip(tb.chrf_stats("aaaa", "aaaa"), tb.chrf_stats("bbbb", "cccc"))]
        self.assertAlmostEqual(tb.corpus_chrf(["aaaa", "bbbb"], ["aaaa", "cccc"]), tb.chrf_from_stats(stats))


class SampleSelection(unittest.TestCase):
    CANDIDATES = [{"page": f"p{i % 5}.md", "kind": kind, "en": f"{kind} line {i}", "de": f"DE {i}"}
                  for i, kind in enumerate(["prose", "table", "heading", "list"] * 12)]

    def test_selection_is_deterministic_and_order_independent(self):
        quotas = {"prose": 3, "table": 2, "heading": 1, "list": 4}
        a = tb.select_samples(self.CANDIDATES, quotas)
        shuffled = list(self.CANDIDATES)
        random.Random(7).shuffle(shuffled)
        self.assertEqual(a, tb.select_samples(shuffled, quotas))
        self.assertEqual([s["id"] for s in a], [f"s{i:03d}" for i in range(1, 11)])
        self.assertEqual(sum(1 for s in a if s["kind"] == "list"), 4)

    def test_duplicates_are_dropped(self):
        dup = self.CANDIDATES + [dict(self.CANDIDATES[0])]
        self.assertEqual(tb.select_samples(dup, {"prose": 100}), tb.select_samples(self.CANDIDATES, {"prose": 100}))

    def test_line_kind(self):
        self.assertEqual(tb.line_kind("## Heading"), "heading")
        self.assertEqual(tb.line_kind('!!! note "x"'), "admonition")
        self.assertEqual(tb.line_kind("    body of a note"), "admonition")
        self.assertEqual(tb.line_kind("| a | b |"), "table")
        self.assertEqual(tb.line_kind("Run `ls` now"), "inline")
        self.assertEqual(tb.line_kind("- item"), "list")
        self.assertEqual(tb.line_kind("Plain prose."), "prose")

    def test_committed_fixture_is_well_formed(self):
        data = json.loads(tb.FIXTURE.read_text(encoding="utf-8"))
        ids = [s["id"] for s in data["samples"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(len(ids), 80)
        for sample in data["samples"]:
            self.assertTrue(sample["en"].strip() and sample["de"].strip())


if __name__ == "__main__":
    unittest.main()
