#!/usr/bin/env python3
"""Regression tests for scripts/backfill_i18n_keys.py.

The defect these pin: the script used to rebuild each target file from its parsed
key list, so every line that is not a `key=value` pair was silently dropped on the
way out. One `--all` run over the real bundles deleted 225 lines from
messages_de.properties — the whole "# German (Deutsch) translations for KorTTY"
header plus every "# Menu - File" style section comment and the blank lines
between sections — and 3 lines from each of es/fr/hr/it/nl/pt.

A backfill run must be strictly additive: every pre-existing line survives
verbatim and in order, and the only new lines are the missing `key=value` pairs.

Run:  python3 scripts/test_backfill_i18n_keys.py
"""
from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest

_SCRIPT = pathlib.Path(__file__).resolve().parent / "backfill_i18n_keys.py"
_spec = importlib.util.spec_from_file_location("backfill_i18n_keys", _SCRIPT)
bf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bf)

I18N = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "i18n"

# A miniature of the real bundles: header comment, section comments, blank separators.
ENGLISH = """\
# English (default) translations for KorTTY
# This file contains all UI strings for the application

# Application
app.name=KorTTY
app.ready=Ready

# Menu - File
menu.file=File
menu.file.new=New Tab
menu.file.close=Close Tab

# Menu - Edit
menu.edit=Edit
menu.edit.copy=Copy
"""

GERMAN = """\
# German (Deutsch) translations for KorTTY

# Application
app.name=KorTTY
app.ready=Bereit

# Menu - File
menu.file=Datei
menu.file.new=Neuer Tab
menu.file.close=Tab schließen

# Menu - Edit
menu.edit=Bearbeiten
menu.edit.copy=Kopieren
"""


# The same bundle a key short, as a locale that missed the last translation run.
GERMAN_GAP = GERMAN.replace("menu.file.new=Neuer Tab\n", "")


def backfill(english: str, target: str) -> tuple[str, int, int]:
    """Runs backfill_one over two in-memory bundles; returns (new target, added, dupes)."""
    with tempfile.TemporaryDirectory() as tmp:
        en_path = pathlib.Path(tmp) / "messages.properties"
        de_path = pathlib.Path(tmp) / "messages_de.properties"
        en_path.write_text(english, encoding="utf-8")
        de_path.write_text(target, encoding="utf-8")
        en_values = bf.parse_values(en_path)
        added, dupes = bf.backfill_one(en_values, list(en_values.keys()), de_path)
        return de_path.read_text(encoding="utf-8"), added, dupes


def assert_additions_only(case: unittest.TestCase, before: str, after: str) -> list[str]:
    """Fails unless `after` is `before` with lines inserted; returns the inserted lines."""
    remaining = after.splitlines()
    inserted: list[str] = []
    for line in before.splitlines():
        while remaining and remaining[0] != line:
            inserted.append(remaining.pop(0))
        case.assertTrue(remaining, f"line vanished from the rewritten file: {line!r}")
        remaining.pop(0)
    inserted.extend(remaining)
    return inserted


class AdditionsOnly(unittest.TestCase):

    def test_comments_and_blank_lines_survive_a_backfill(self):
        after, added, dupes = backfill(ENGLISH, GERMAN_GAP)
        self.assertEqual((added, dupes), (1, 0))
        inserted = assert_additions_only(self, GERMAN_GAP, after)
        self.assertEqual(inserted, ["menu.file.new=New Tab"])

    def test_the_header_comment_is_still_the_first_line(self):
        after, _added, _dupes = backfill(ENGLISH, GERMAN_GAP)
        self.assertEqual(after.splitlines()[0], "# German (Deutsch) translations for KorTTY")

    def test_a_new_key_lands_inside_its_own_section(self):
        after, _added, _dupes = backfill(ENGLISH, GERMAN_GAP)
        lines = after.splitlines()
        # menu.file.new follows menu.file in English, so it belongs under "# Menu - File",
        # not below the "# Menu - Edit" header that happens to come next in German.
        self.assertLess(lines.index("# Menu - File"), lines.index("menu.file.new=New Tab"))
        self.assertLess(lines.index("menu.file.new=New Tab"), lines.index("# Menu - Edit"))

    def test_a_key_appended_to_a_section_stays_above_the_next_section_comment(self):
        english = ENGLISH.replace("menu.file.close=Close Tab\n",
                                  "menu.file.close=Close Tab\nmenu.file.quit=Quit\n")
        after, added, _dupes = backfill(english, GERMAN)
        self.assertEqual(added, 1)
        lines = after.splitlines()
        self.assertEqual(lines.index("menu.file.quit=Quit"), lines.index("menu.file.close=Tab schließen") + 1)
        self.assertLess(lines.index("menu.file.quit=Quit"), lines.index("# Menu - Edit"))

    def test_a_key_ahead_of_every_target_key_lands_below_the_header_comment(self):
        english = ENGLISH.replace("app.name=KorTTY\n", "app.brand=Kor\napp.name=KorTTY\n")
        after, added, _dupes = backfill(english, GERMAN)
        self.assertEqual(added, 1)
        lines = after.splitlines()
        self.assertLess(lines.index("# Application"), lines.index("app.brand=Kor"))
        self.assertEqual(lines.index("app.brand=Kor"), lines.index("app.name=KorTTY") - 1)

    def test_a_key_after_every_target_key_lands_at_the_end(self):
        english = ENGLISH + "menu.edit.paste=Paste\n"
        after, added, _dupes = backfill(english, GERMAN)
        self.assertEqual(added, 1)
        assert_additions_only(self, GERMAN, after)
        self.assertEqual(after.splitlines()[-1], "menu.edit.paste=Paste")

    def test_a_target_with_nothing_missing_is_left_byte_for_byte_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            en_path = pathlib.Path(tmp) / "messages.properties"
            de_path = pathlib.Path(tmp) / "messages_de.properties"
            en_path.write_text(ENGLISH, encoding="utf-8")
            de_path.write_text(GERMAN, encoding="utf-8")
            en_values = bf.parse_values(en_path)
            self.assertEqual(bf.backfill_one(en_values, list(en_values.keys()), de_path), (0, 0))
            self.assertEqual(de_path.read_text(encoding="utf-8"), GERMAN)

    def test_a_duplicate_key_is_dropped_without_taking_comments_with_it(self):
        stale = GERMAN_GAP.replace("# Menu - Edit\n", "app.ready=Fertig\n\n# Menu - Edit\n")
        after, added, dupes = backfill(ENGLISH, stale)
        self.assertEqual((added, dupes), (1, 1))
        lines = after.splitlines()
        # The live (last) occurrence wins, the dead earlier one goes, comments stay.
        self.assertEqual([l for l in lines if l.startswith("app.ready=")], ["app.ready=Fertig"])
        for comment in ("# German (Deutsch) translations for KorTTY", "# Application",
                        "# Menu - File", "# Menu - Edit"):
            self.assertIn(comment, lines)


class RealBundles(unittest.TestCase):
    """The same guarantee against the shipped bundles, which is where the damage happened."""

    def test_every_shipped_locale_takes_a_new_key_without_losing_a_line(self):
        english = (I18N / "messages.properties").read_text(encoding="utf-8")
        # One key mid-file and one at the very end, as a feature branch would add them.
        english = english.replace("app.ready=Ready\n", "app.ready=Ready\napp.test.injected=Injected\n", 1)
        english += "zzz.test.trailing=Trailing\n"
        self.assertIn("app.test.injected", english)
        for name in bf.ALL_TARGETS:
            with self.subTest(bundle=name):
                before = (I18N / name).read_text(encoding="utf-8")
                after, added, dupes = backfill(english, before)
                self.assertEqual((added, dupes), (2, 0))
                inserted = assert_additions_only(self, before, after)
                self.assertEqual(sorted(inserted),
                                 ["app.test.injected=Injected", "zzz.test.trailing=Trailing"])


if __name__ == "__main__":
    unittest.main()
