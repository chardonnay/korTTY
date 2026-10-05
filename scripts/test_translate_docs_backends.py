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


class MetaReplies(unittest.TestCase):
    """A chat model's reply ABOUT the request ("Bitte geben Sie die zu übersetzende Zeile an.")
    must never be written as the German line."""

    SEEN = ["Bitte geben Sie die zu übersetzende Zeile an.",
            "Sure, please provide the line you would like me to translate.",
            "Please provide the text to translate.",
            "Welche Zeile möchten Sie übersetzen?",
            "Here is the translation:",
            "Es tut mir leid, aber ich kann diese Anfrage nicht bearbeiten.",
            "Es gibt keinen Text, den ich übersetzen könnte.",
            "Gerne! Bitte senden Sie mir den Text."]

    def test_known_meta_phrases_are_detected(self):
        for reply in self.SEEN:
            with self.subTest(reply=reply):
                self.assertTrue(td.is_meta_reply("Open the file with the editor.", reply))
                self.assertTrue(td.is_meta_reply("and", reply))

    def test_ordinary_translations_are_not_meta(self):
        pairs = [
            ("Open the file with the editor.", "Öffnen Sie die Datei mit dem Editor."),
            ("Please enter the text to search for.", "Bitte geben Sie den Text ein, nach dem gesucht werden soll."),
            ("## What is encrypted", "## Was ist verschlüsselt?"),
            ("Is the host reachable?", "Ist der Host erreichbar? Prüfen Sie das."),
            ("KorTTY stores KTPH000 in 3 places, e.g. on disk.",
             "korTTY speichert KTPH000 an 3 Orten, z. B. auf der Festplatte."),
            ("Profiles store foreground/background colors.", "Profile speichern Vordergrund-/Hintergrundfarben."),
            ("Edit settings.json and JavaFX options.", "Bearbeiten Sie settings.json und die JavaFX-Optionen."),
        ]
        for source, out in pairs:
            with self.subTest(source=source):
                self.assertFalse(td.is_meta_reply(source, out), td.meta_reply_reason(source, out))

    def test_a_phrase_the_source_itself_contains_is_not_meta(self):
        self.assertFalse(td.is_meta_reply("Here is the translation of the menu labels.",
                                          "Hier ist die Übersetzung der Menübeschriftungen."))

    def test_question_addressing_the_reader_is_a_request_for_input(self):
        self.assertTrue(td.is_meta_reply("Save the snippet.", "Was genau möchten Sie speichern?"))
        self.assertEqual("question", td.meta_reply_reason("Save the snippet.", "Could you clarify what you mean?"))

    def test_reply_without_any_carry_over_token_is_meta(self):
        self.assertEqual("no-carry-over", td.meta_reply_reason(
            "Set KTPH000 to 30 seconds in korTTY.", "Das kann ich so nicht beantworten."))
        self.assertFalse(td.is_meta_reply("Set the timeout to 30 seconds.", "Setzen Sie das Zeitlimit auf 30 Sekunden."))
        self.assertFalse(td.is_meta_reply("Set the timeout.", "Setzen Sie das Zeitlimit."))

    def test_meta_line_is_retried_then_failed(self):
        masked, store = td.mask("Open the file with the editor.")
        tr = _Translator(lambda t, n: MetaReplies.SEEN[0], fragment=lambda t: MetaReplies.SEEN[0])
        results, stats = td.translate_masked(tr, [(masked, store)])
        self.assertEqual(results, [None])
        self.assertEqual((stats["retried"], stats["failed"]), (1, 1))
        self.assertGreaterEqual(stats["meta_replies"], 2)

    def test_meta_retry_answer_is_not_span_repaired(self):
        masked, store = td.mask("Open the file with the editor.")
        tr = _Translator(lambda t, n: MetaReplies.SEEN[1],
                         fragment=lambda t: "Öffnen Sie die Datei mit dem Editor.")
        results, _stats = td.translate_masked(tr, [(masked, store)])
        self.assertEqual(results, ["Öffnen Sie die Datei mit dem Editor."])  # fragment fallback

    def test_meta_reply_for_a_fragment_fails_the_line(self):
        masked, store = td.mask("Press ++ctrl+s++ and then `save`.")
        tr = _Translator(lambda t, n: None,
                         fragment=lambda t: MetaReplies.SEEN[0] if t == "and then" else "Drücken Sie")
        results, stats = td.translate_masked(tr, [(masked, store)])
        self.assertEqual(results, [None])
        self.assertEqual(stats["failed"], 1)
        page = "Press ++ctrl+s++ and then `save`.\n"
        out, _r, _f, failed = td.translate_md(page, tr)
        self.assertEqual(out, page)
        self.assertEqual(len(failed), 1)

    def test_good_answer_after_a_meta_answer_is_accepted(self):
        masked, store = td.mask("Open the file with the editor.")
        tr = _Translator(lambda t, n: MetaReplies.SEEN[0] if n == 1 else "Öffnen Sie die Datei mit dem Editor.")
        results, stats = td.translate_masked(tr, [(masked, store)])
        self.assertEqual(results, ["Öffnen Sie die Datei mit dem Editor."])
        self.assertEqual((stats["retry_ok"], stats["meta_replies"]), (1, 1))

    def test_llm_backend_redoes_a_meta_batch_line_and_fails_a_meta_single(self):
        def reply(_system, user):
            if '{"lines"' in user:
                return json.dumps({"translations": [
                    {"id": 1, "text": "Öffnen KTPH000"},
                    {"id": 2, "text": "Sure, please provide the line you want translated."}]})
            return MetaReplies.SEEN[0]
        backend = ScriptedBackend(reply, batch_lines=2)
        self.assertEqual(backend.translate_lines(["Open KTPH000", "Open the file"]), ["Öffnen KTPH000", None])
        self.assertEqual(len(backend.calls), 2)  # the batch + one single-line retry
        with self.assertRaises(RuntimeError):
            backend.translate("and")


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


class GlossaryIdentifiers(unittest.TestCase):
    def test_glossary_does_not_rewrite_inside_camel_case_identifiers(self):
        td._GLOSSARY = [("Store", "Wissensspeicher", False), ("Snippet Manager", "Snippet-Manager", False)]
        try:
            self.assertEqual(td.apply_glossary("Der Store und `LocalHnswStore` sowie `RagKnowledgeStorePane`"),
                             "Der Wissensspeicher und `LocalHnswStore` sowie `RagKnowledgeStorePane`")
            self.assertEqual(td.apply_glossary("Snippet Manager"), "Snippet-Manager")
        finally:
            td._GLOSSARY = None


class QuickAndTimeBoxedBenchmark(unittest.TestCase):
    FIXTURE = json.loads(tb.FIXTURE.read_text(encoding="utf-8"))["samples"]

    class Args:
        backend = "lmstudio"
        concurrency = None
        batch_lines = None
        reasoning_effort = None
        request_timeout = None
        sweep = None
        sweep_lines = 24
        config_timeout = 90.0
        full = False
        limit = None

    def _args(self, **kw):
        a = self.Args()
        for k, v in kw.items():
            setattr(a, k, v)
        return a

    def test_quick_subset_is_deterministic_and_covers_all_kinds(self):
        a = tb.quick_subset(self.FIXTURE)
        self.assertEqual(a, tb.quick_subset(list(self.FIXTURE)))
        self.assertEqual(len(a), 24)
        self.assertEqual(len({s["id"] for s in a}), 24)
        kinds = {s["kind"] for s in self.FIXTURE}
        self.assertEqual({s["kind"] for s in a}, kinds)
        counts = {k: sum(1 for s in a if s["kind"] == k) for k in kinds}
        self.assertLessEqual(max(counts.values()) - min(counts.values()), 1)
        # round-robin: a cut-short run still sees every kind in its first lines
        self.assertEqual({s["kind"] for s in a[:len(kinds)]}, kinds)

    def test_quick_subset_redistributes_small_kinds_and_handles_tiny_input(self):
        pool = [{"id": f"a{i}", "kind": "a", "en": "x", "de": "y"} for i in range(30)] + \
               [{"id": "b0", "kind": "b", "en": "x", "de": "y"}]
        out = tb.quick_subset(pool, 10)
        self.assertEqual(len(out), 10)
        self.assertEqual(sum(1 for s in out if s["kind"] == "b"), 1)
        self.assertEqual(len(tb.quick_subset(pool[:3], 24)), 3)
        self.assertEqual(tb.quick_subset([], 24), [])

    def test_fixture_is_untouched_by_quick_mode(self):
        before = tb.FIXTURE.read_bytes()
        tb.quick_subset(self.FIXTURE)
        self.assertEqual(before, tb.FIXTURE.read_bytes())

    def test_parse_sweep(self):
        self.assertEqual(tb.parse_sweep(["concurrency=4,8", "batch=2,4"]), [(4, 2), (4, 4), (8, 2), (8, 4)])
        self.assertEqual(tb.parse_sweep(["batch-lines=2"]), [(8, 2)])
        for bad in (["foo=1"], ["concurrency"], ["batch=x"], ["batch=0"], ["batch="]):
            with self.assertRaises(ValueError):
                tb.parse_sweep(bad)

    def test_argument_parsing_defaults_and_overrides(self):
        ap = tb.build_parser()
        a = ap.parse_args([])
        plans, mode = tb.plan_runs(a, self.FIXTURE)
        self.assertEqual(mode, "quick")
        self.assertEqual((plans[0]["concurrency"], plans[0]["batch_lines"], len(plans[0]["samples"])), (8, 4, 24))
        self.assertEqual(a.request_timeout, tb.DEFAULT_REQUEST_TIMEOUT)
        a = ap.parse_args(["--quick", "--concurrency", "2", "--request-timeout", "30", "--reasoning-effort", "high"])
        plans, mode = tb.plan_runs(a, self.FIXTURE)
        self.assertEqual((mode, plans[0]["concurrency"], plans[0]["batch_lines"]), ("quick", 2, 4))
        self.assertEqual((a.request_timeout, a.reasoning_effort), (30, "high"))
        plans, mode = tb.plan_runs(ap.parse_args(["--full"]), self.FIXTURE)
        self.assertEqual((mode, len(plans[0]["samples"])), ("full", len(self.FIXTURE)))
        plans, mode = tb.plan_runs(ap.parse_args(["--limit", "5", "--concurrency", "3"]), self.FIXTURE)
        self.assertEqual((mode, len(plans[0]["samples"]), plans[0]["concurrency"]), ("limit", 5, 3))
        plans, mode = tb.plan_runs(ap.parse_args(["--sweep", "concurrency=4,8", "batch=2",
                                                  "--sweep-lines", "12", "--config-timeout", "5"]), self.FIXTURE)
        self.assertEqual((mode, [(p["concurrency"], p["batch_lines"]) for p in plans]), ("sweep", [(4, 2), (8, 2)]))
        self.assertTrue(all(len(p["samples"]) == 12 and p["timeout"] == 5 for p in plans))
        with self.assertRaises(ValueError):
            tb.plan_runs(ap.parse_args(["--sweep", "batch=2", "--full"]), self.FIXTURE)
        self.assertEqual(ap.parse_args(["--max-seconds", "90"]).max_seconds, 90)

    def test_make_backend_passes_effort_and_timeout(self):
        b = td.make_backend("lmstudio", "m", None, 2, 3, "high", 45.0)
        self.assertEqual((b.reasoning_effort, b.timeout, b.concurrency, b.batch_lines), ("high", 45.0, 2, 3))
        b = td.make_backend("lmstudio", "m")
        self.assertEqual((b.reasoning_effort, b.timeout), ("low", 600.0))

    def _samples(self, n):
        return [{"id": f"s{i}", "kind": "prose", "en": f"Open the file number {i} now", "de": f"DE {i}"}
                for i in range(n)]

    def _run(self, backend, samples, deadline):
        m = tb.measure(backend, samples, deadline)
        args = self._args()
        return tb.build_result(args, backend, "stub", samples, m, TERMS, 100, 1000, None, None)

    def test_complete_run_is_not_partial(self):
        backend = ScriptedBackend(lambda s, u: _german(u.split("\n\n", 1)[1]))
        result = self._run(backend, self._samples(4), None)
        self.assertFalse(result["partial"])
        self.assertEqual((result["lines_done"], result["lines"], result["failed"]), (4, 4, 0))
        self.assertIsNotNone(result["eta_changed_min"])
        self.assertIsNotNone(result["eta_full_min"])

    def test_max_seconds_gives_a_partial_report_with_extrapolation(self):
        def slow(_system, user):
            time.sleep(0.05)
            return _german(user.split("\n\n", 1)[1])
        backend = ScriptedBackend(slow)
        samples = self._samples(40)
        started = time.perf_counter()
        result = self._run(backend, samples, time.perf_counter() + 0.3)
        self.assertLess(time.perf_counter() - started, 2.0)  # stopped, did not grind through 40 x 50 ms x retries
        self.assertTrue(result["partial"])
        self.assertTrue(0 < result["lines_done"] < 40)
        self.assertEqual(result["failed"], 0)  # cut-off lines are not counted as failures
        self.assertEqual(len(result["per_line"]), result["lines_done"])
        self.assertGreater(result["lines_per_min"], 0)
        self.assertAlmostEqual(result["eta_full_min"], tb.extrapolate(1000, result["lines_per_min"]))
        meta = {"changed_lines": 100, "changed_since": "abc", "full_lines": 1000, "partial": True,
                "measured_wall_s": result["wall_s"], "timestamp": "t", "mode": "quick", "samples": 40,
                "fixture": "f", "fixture_commit": "c", "backend": "lmstudio", "benchmark_took_s": 1.0}
        report = tb.markdown_report([result], meta)
        self.assertIn("PARTIAL", report)
        self.assertIn("Benchmark took", report)
        self.assertIn("current worklist", "\n".join(tb.extrapolation_lines([result], meta)))

    def test_expired_budget_yields_zero_lines_without_crashing(self):
        backend = ScriptedBackend(lambda s, u: u)
        result = self._run(backend, self._samples(3), time.perf_counter() - 1)
        self.assertTrue(result["partial"])
        self.assertEqual(result["lines_done"], 0)
        self.assertIsNone(result["lines_per_min"])
        self.assertIsNone(result["eta_full_min"])
        self.assertIsNone(result["chrf"])
        self.assertEqual(backend.calls, [])

    def test_sweep_config_timeout_limits_each_configuration(self):
        calls = {"n": 0}

        def reply(_system, user):
            calls["n"] += 1
            time.sleep(0.03)
            return _german(user.split("\n\n", 1)[1])
        results = []
        for conc, batch in [(1, 1), (2, 1)]:
            backend = ScriptedBackend(reply, batch_lines=batch, concurrency=conc)
            results.append(self._run(backend, self._samples(60), time.perf_counter() + 0.2))
        self.assertTrue(all(r["partial"] for r in results))
        self.assertTrue(all(r["lines_done"] < 60 for r in results))
        # the box is per configuration: the second run still made progress after the first was cut
        self.assertGreater(results[1]["lines_done"], 0)

    def test_deadline_caps_the_http_timeout(self):
        b = td.OpenAICompatBackend("http://unused", "m", terms=TERMS, timeout=600.0)
        b.deadline = time.perf_counter() + 5
        self.assertLessEqual(b._remaining_timeout(600.0), 5)
        b.deadline = time.perf_counter() - 1
        with self.assertRaises(TimeoutError):
            b._remaining_timeout(600.0)
        self.assertTrue(b.deadline_hit)


if __name__ == "__main__":
    unittest.main()
