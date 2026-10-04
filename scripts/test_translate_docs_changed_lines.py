#!/usr/bin/env python3
"""Tests for the changed-lines mode of scripts/translate_docs.py (--only-changed-lines, the
default with --changed-since): only English lines that are new or changed against the base
ref are translated, every other line keeps its existing German, en/de stay line-aligned and
link anchors follow re-translated German headings.

Each test builds a throwaway git repo with a two-page English/German guide, commits it as
the base, edits the English and runs translate_docs.main() with a fake backend (no network)
and a fake heading renderer (no markdown dependency).

Run:  python3 -m unittest scripts.test_translate_docs_changed_lines
(Gradle: ./gradlew translateDocsChangedLinesTest).
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import re
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

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
_spec = importlib.util.spec_from_file_location("translate_docs_changed_lines_td",
                                               _DIR / "translate_docs.py")
td = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(td)


def _german(text: str) -> str:
    """Deterministic fake 'translation': every word reversed, tokens untouched."""
    return "".join(
        part if td.TOKEN_RE.fullmatch(part) else re.sub(r"[A-Za-z]+", lambda m: m.group(0)[::-1], part)
        for part in td.TOKEN_RE.split(text))


class FakeBackend:
    def __init__(self, fail_on: str | None = None):
        self.sent: list[str] = []
        self.fail_on = fail_on

    def _one(self, text: str) -> str | None:
        if self.fail_on and self.fail_on in td.TOKEN_RE.sub("", text):
            return None
        return _german(text)

    def translate_lines(self, texts):
        self.sent.extend(texts)
        return [self._one(t) for t in texts]

    def translate(self, text):
        out = self._one(text)
        if out is None:
            raise RuntimeError("fake failure")
        return out


class FakeRenderer:
    """Renders only what heading_ids reads: <hN id="..."> per ATX heading, with
    python-markdown toc's slug rules (lowercase, non-word dropped, spaces to '-',
    duplicates get _1, _2 ...) and attr_list's explicit `{ #id }`."""

    def reset(self):
        pass

    def convert(self, body: str) -> str:
        seen: dict[str, int] = {}
        html = []
        for i in td.heading_line_indices(body):
            line = body.split("\n")[i]
            level = len(line.lstrip().split(" ")[0])
            text = line.lstrip().lstrip("#").strip()
            explicit = re.search(r"\{\s*#([\w-]+)\s*\}\s*$", text)
            if explicit:
                slug = explicit.group(1)
            else:
                slug = re.sub(r"[^\w\s-]", "", text).strip().lower()
                slug = re.sub(r"[\s-]+", "-", slug)
            if slug in seen:
                seen[slug] += 1
                slug = f"{slug}_{seen[slug]}"
            else:
                seen[slug] = 0
            html.append(f'<h{level} id="{slug}">{text}</h{level}>')
        return "\n".join(html)


BASE_EN_A = """# Title

Intro with a link to [the options](b.md#options).

## Exporting

Text one stays as it is.

Old line that will be dropped.

## Options { #opts }

Jump back to [exporting](#exporting) or to [these options](#opts).

Closing words of the page."""

BASE_DE_A = """# Titel

Einleitung mit einem Link zu [den Optionen](b.md#optionen).

## Exportieren

Text eins bleibt wie er ist.

Alte Zeile, die entfernt wird.

## Optionen { #opts }

Zurück zu [Exportieren](#exportieren) oder zu [diesen Optionen](#opts).

Schlussworte der Seite."""

BASE_EN_B = """# Bee page

## Options

Bee options text."""

BASE_DE_B = """# Bienenseite

## Optionen

Bienen-Optionstext."""

# English edits on the branch: one line changed, one inserted, one deleted, the explicit-id
# heading reworded, and a heading on b.md reworded without changing its English id (so its
# German heading is re-translated and the kept German link on a.md must follow it).
NEW_EN_A = """# Title

Intro with a link to [the options](b.md#options).

## Exporting

Text one was changed on the branch.

A brand new paragraph for the feature.

## More options { #opts }

Jump back to [exporting](#exporting) or to [these options](#opts).

Closing words of the page."""

NEW_EN_B = """# Bee page

## Options!

Bee options text."""

NEW_EN_C = """# Sea page

Read about [exporting](a.md#exporting) first."""


def _git(repo: pathlib.Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@t",
                           "-c", "commit.gpgsign=false", *args],
                          check=True, capture_output=True, text=True).stdout


class ChangedLinesModeTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = pathlib.Path(self._tmp.name)
        self.en = self.repo / "app-docs" / "site" / "docs" / "en"
        self.de = self.repo / "app-docs" / "site" / "docs" / "de"
        self.en.mkdir(parents=True)
        self.de.mkdir(parents=True)
        for path, text in ((self.en / "a.md", BASE_EN_A), (self.de / "a.md", BASE_DE_A),
                           (self.en / "b.md", BASE_EN_B), (self.de / "b.md", BASE_DE_B)):
            path.write_text(text, encoding="utf-8")
        _git(self.repo, "init", "-q")
        _git(self.repo, "add", "-A")
        _git(self.repo, "commit", "-q", "-m", "base")
        _git(self.repo, "tag", "base")
        (self.en / "a.md").write_text(NEW_EN_A, encoding="utf-8")
        (self.en / "b.md").write_text(NEW_EN_B, encoding="utf-8")
        (self.en / "c.md").write_text(NEW_EN_C, encoding="utf-8")
        _git(self.repo, "add", "-A")
        _git(self.repo, "commit", "-q", "-m", "branch: English edits")
        self._original_repo = td.REPO

    def tearDown(self):
        td.set_repo(self._original_repo)
        td._GLOSSARY = None
        self._tmp.cleanup()

    def run_main(self, backend, *extra):
        argv = ["translate_docs.py", "--repo", str(self.repo), "--changed-since", "base", *extra]
        out = io.StringIO()
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(td, "make_backend", lambda *a, **k: backend), \
                mock.patch.object(td, "make_heading_renderer", lambda: FakeRenderer()), \
                contextlib.redirect_stdout(out):
            code = td.main()
        return code, out.getvalue()

    def de_lines(self, name):
        return (self.de / name).read_text(encoding="utf-8").split("\n")

    def test_only_new_and_changed_lines_are_sent(self):
        backend = FakeBackend()
        code, log = self.run_main(backend)
        self.assertEqual(0, code, log)
        sent = sorted(td.TOKEN_RE.sub("", s).strip() for s in backend.sent)
        self.assertEqual(sorted([
            "Text one was changed on the branch.",
            "A brand new paragraph for the feature.",
            "More options",               # heading marker and { #opts } are masked
            "Options!",
            "Sea page", "Read about exporting first.",  # new page: translated in full
        ]), sent)

    def test_unchanged_lines_keep_base_german_and_alignment_holds(self):
        self.run_main(FakeBackend())
        de_a = self.de_lines("a.md")
        self.assertEqual(len(NEW_EN_A.split("\n")), len(de_a))
        self.assertEqual("# Titel", de_a[0])
        self.assertEqual("## Exportieren", de_a[4])
        self.assertEqual("txeT eno saw degnahc no eht hcnarb.", de_a[6])
        self.assertEqual("A dnarb wen hpargarap rof eht erutaef.", de_a[8])
        self.assertEqual("Schlussworte der Seite.", de_a[-1])
        self.assertNotIn("Alte Zeile, die entfernt wird.", de_a)  # deleted with its English
        for en_line, de_line in zip(NEW_EN_A.split("\n"), de_a):
            self.assertEqual(en_line == "", de_line == "", (en_line, de_line))

    def test_explicit_heading_id_is_kept(self):
        self.run_main(FakeBackend())
        de_a = self.de_lines("a.md")
        self.assertEqual("## eroM snoitpo { #opts }", de_a[10])
        self.assertIn("[diesen Optionen](#opts)", de_a[12])
        self.assertIn("[Exportieren](#exportieren)", de_a[12])

    def test_kept_link_follows_a_retranslated_heading(self):
        self.run_main(FakeBackend())
        self.assertEqual("## snoitpO!", self.de_lines("b.md")[2])
        self.assertEqual("Einleitung mit einem Link zu [den Optionen](b.md#snoitpo).",
                         self.de_lines("a.md")[2])

    def test_new_page_gets_german_anchors(self):
        self.run_main(FakeBackend())
        self.assertEqual("daeR tuoba [gnitropxe](a.md#exportieren) tsrif.", self.de_lines("c.md")[2])

    def test_old_behaviour_retranslates_linked_lines(self):
        td.set_repo(self.repo)
        changed = td.pages_changed_since("base")
        new_mode = {str(p["rel"]): p["misses"] for p in td.plan_pages(
            changed, memory_from_git=True, only_changed_lines=True, base_ref="base")}
        old_mode = {str(p["rel"]): p["misses"] for p in td.plan_pages(
            changed, memory_from_git=True, only_changed_lines=False)}
        self.assertEqual(3, new_mode["a.md"])
        self.assertGreater(old_mode["a.md"], new_mode["a.md"])  # + the two linked lines

    def test_failed_line_keeps_english_and_a_rerun_retries_only_it(self):
        code, log = self.run_main(FakeBackend(fail_on="brand new paragraph"))
        self.assertEqual(1, code)
        self.assertIn("FAILED", log)
        self.assertEqual("A brand new paragraph for the feature.", self.de_lines("a.md")[8])
        retry = FakeBackend()
        code, log = self.run_main(retry)
        self.assertEqual(0, code, log)
        self.assertEqual(["A brand new paragraph for the feature."], retry.sent)
        self.assertEqual("A dnarb wen hpargarap rof eht erutaef.", self.de_lines("a.md")[8])
        self.assertEqual("txeT eno saw degnahc no eht hcnarb.", self.de_lines("a.md")[6])


class HeadingAliasTest(unittest.TestCase):
    def test_duplicate_counter_shift_follows_the_heading_line(self):
        old_de = "# T\n\n## Optionen\n\nx"
        new_de = "# T\n\n## Optionen\n\nneu\n\n## Optionen\n\nx"
        opcodes = td.en_alignment("# T\n\n## Options\n\nx",
                                  "# T\n\n## Options\n\nnew\n\n## Options\n\nx")
        # difflib pairs the old heading with the first of the two equal lines, which keeps
        # the id "optionen" — no alias needed — and the inserted one is new.
        self.assertEqual({}, td.heading_alias_map(old_de, new_de, opcodes, FakeRenderer()))

    def test_replaced_heading_is_paired_in_its_block(self):
        old_de = "# T\n\n## Alt\n\nx"
        new_de = "# T\n\n## Neu\n\nx"
        opcodes = td.en_alignment("# T\n\n## Old\n\nx", "# T\n\n## New\n\nx")
        self.assertEqual({"alt": "neu"},
                         td.heading_alias_map(old_de, new_de, opcodes, FakeRenderer()))

    def test_keep_skips_lines_that_shipped_in_english(self):
        base_en = "# T\n\nThis line failed before and stayed English.\n\nGood line here."
        base_de = "# T\n\nThis line failed before and stayed English.\n\nGute Zeile hier."
        keep = td.changed_line_keep(base_en, base_de, base_en)
        self.assertNotIn(2, keep)
        self.assertEqual("Gute Zeile hier.", keep[4])

    def test_misaligned_base_gives_no_keep(self):
        self.assertIsNone(td.changed_line_keep("a\nb", "a", "a\nb"))


if __name__ == "__main__":
    unittest.main()
