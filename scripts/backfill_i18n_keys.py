#!/usr/bin/env python3
"""Insert keys missing from messages_XX.properties that exist in messages.properties.

translate_i18n.py only retranslates a key that is already PRESENT in a target
file but textually identical to English — it iterates over the target file's
own lines, so a key simply ABSENT from messages_es.properties etc. is never
considered at all. New keys added to messages.properties since the last full
translation run for a locale just silently pile up as gaps (Java's
ResourceBundle falls back to English for a missing key, so nothing crashes,
but that locale's UI is partly untranslated with no way to detect it short of
diffing key sets).

This script closes that gap mechanically: for each target file it aligns the
target's existing key order against messages.properties' canonical order via
an LCS diff (difflib.SequenceMatcher), so keys the target already has keep
their exact existing line and position untouched, and every key the target
lacks is inserted at the position implied by the English ordering — anchored
between whichever existing target keys come immediately before/after it in
English's order — holding the English value verbatim as a placeholder.

It does not translate anything. Run scripts/translate_i18n.py afterward (or
pass --translate here) to send every English-identical value — the ones this
script just inserted, plus any that were already sitting untranslated — through
Google Translate, unchanged from its existing batch/retry logic.

Usage:
  scripts/backfill_i18n_keys.py messages_es.properties messages_fr.properties
  scripts/backfill_i18n_keys.py --all
  scripts/backfill_i18n_keys.py --all --translate   # backfill, then translate each file
"""
from __future__ import annotations

import argparse
import difflib
import subprocess
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "i18n"
ALL_TARGETS = [
    "messages_de.properties",
    "messages_es.properties",
    "messages_fr.properties",
    "messages_hr.properties",
    "messages_it.properties",
    "messages_nl.properties",
    "messages_pt.properties",
]


def parse_values(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v
    return out


def ordered_keys(path: Path) -> list[str]:
    keys = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        keys.append(line.split("=", 1)[0].strip())
    return keys


def backfill_one(en_values: dict[str, str], en_keys: list[str], target_path: Path) -> tuple[int, int]:
    """Returns (keys_added, duplicate_lines_dropped)."""
    lines = target_path.read_text(encoding="utf-8").splitlines()
    key_to_line: dict[str, int] = {}
    dupes_dropped = 0
    for i, line in enumerate(lines):
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        k = line.split("=", 1)[0].strip()
        if k in key_to_line:
            # A key repeated in the file: Java's Properties loader keeps only the
            # last occurrence at runtime, so an earlier duplicate is dead text —
            # drop it rather than re-duplicating it into the merged output.
            dupes_dropped += 1
        key_to_line[k] = i  # last occurrence wins, matching Properties semantics

    # Deduplicated, first-seen-position order — matches how en_keys is derived
    # from a dict below, so the two sequences align on genuinely unique keys.
    target_keys: list[str] = []
    seen_target: set[str] = set()
    for line in lines:
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        k = line.split("=", 1)[0].strip()
        if k not in seen_target:
            target_keys.append(k)
            seen_target.add(k)

    sm = difflib.SequenceMatcher(None, en_keys, target_keys, autojunk=False)
    new_lines: list[str] = []
    added = 0
    seen: set[str] = set()  # guards against a key appearing in more than one opcode window
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("equal", "insert"):
            for k in target_keys[j1:j2]:
                new_lines.append(lines[key_to_line[k]])
                seen.add(k)
        if tag == "replace":
            for k in target_keys[j1:j2]:
                new_lines.append(lines[key_to_line[k]])
                seen.add(k)
        if tag in ("delete", "replace"):
            for k in en_keys[i1:i2]:
                if k not in key_to_line and k not in seen:
                    new_lines.append(f"{k}={en_values[k]}")
                    seen.add(k)
                    added += 1

    if added or dupes_dropped:
        target_path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
    return added, dupes_dropped


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", help="messages_XX.properties filenames to backfill")
    ap.add_argument("--all", action="store_true", help="backfill every target in LANG_MAP")
    ap.add_argument("--translate", action="store_true",
                     help="run scripts/translate_i18n.py on the backfilled files afterward")
    args = ap.parse_args()

    targets = ALL_TARGETS if args.all else args.files
    if not targets:
        ap.error("give filenames or --all")

    en_values = parse_values(BASE / "messages.properties")
    en_keys = list(en_values.keys())

    touched = []
    for fname in targets:
        path = BASE / fname
        if not path.is_file():
            print(f"{fname}: not found, skipping", file=sys.stderr)
            continue
        added, dupes = backfill_one(en_values, en_keys, path)
        dupe_note = f", dropped {dupes} duplicate-key line(s)" if dupes else ""
        print(f"{fname}: inserted {added} missing key(s){dupe_note}")
        if added:
            touched.append(fname)

    if args.translate and touched:
        print(f"\nTranslating {len(touched)} file(s)...")
        subprocess.run([sys.executable, str(Path(__file__).parent / "translate_i18n.py"), *touched],
                        check=True)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
