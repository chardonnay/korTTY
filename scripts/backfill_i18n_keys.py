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

Every run is strictly additive: the target's own comments, section headers and
blank lines are carried through verbatim, so `git diff` after a backfill shows
insertions and nothing else.

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


def is_key_line(line: str) -> bool:
    """True for a `key=value` pair; False for comments, blanks and anything else."""
    return bool(line) and not line.lstrip().startswith("#") and "=" in line


def parse_values(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not is_key_line(line):
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v
    return out


def ordered_keys(path: Path) -> list[str]:
    keys = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not is_key_line(line):
            continue
        keys.append(line.split("=", 1)[0].strip())
    return keys


def backfill_one(en_values: dict[str, str], en_keys: list[str], target_path: Path) -> tuple[int, int]:
    """Returns (keys_added, duplicate_lines_dropped).

    Every existing line of the target is carried over verbatim — comments, blank
    lines and section headers included — and only the `key=value` lines for keys
    the target lacks are spliced in. A run of missing keys is anchored directly
    after the target line of the key that precedes it in English's order, so a
    key appended to the end of an English section lands inside that same section
    in the target rather than under the next section's comment.
    """
    lines = target_path.read_text(encoding="utf-8").splitlines()

    # Java's Properties loader keeps only the LAST occurrence of a repeated key,
    # so an earlier duplicate is dead text — drop those lines and keep the live
    # one where it already sits.
    key_to_line: dict[str, int] = {}
    dropped_lines: set[int] = set()
    for i, line in enumerate(lines):
        if not is_key_line(line):
            continue
        k = line.split("=", 1)[0].strip()
        if k in key_to_line:
            dropped_lines.add(key_to_line[k])
        key_to_line[k] = i

    # Keys in the order their surviving lines appear — the order the rewritten
    # file will have, so the alignment below anchors against real positions.
    target_keys = [lines[i].split("=", 1)[0].strip() for i in sorted(key_to_line.values())]

    sm = difflib.SequenceMatcher(None, en_keys, target_keys, autojunk=False)
    insert_after: dict[int, list[str]] = {}  # target line index -> lines to splice in after it
    insert_at_head: list[str] = []           # missing keys that sort before every target key
    pending: list[str] = []
    anchor: str | None = None
    added = 0
    seen: set[str] = set()  # guards against a key appearing in more than one opcode window

    def flush() -> None:
        nonlocal pending
        if not pending:
            return
        if anchor is None:
            insert_at_head.extend(pending)
        else:
            insert_after.setdefault(key_to_line[anchor], []).extend(pending)
        pending = []

    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("equal", "insert", "replace"):
            for k in target_keys[j1:j2]:
                flush()  # a pending run belongs after the PREVIOUS target key
                anchor = k
                seen.add(k)
        if tag in ("delete", "replace"):
            for k in en_keys[i1:i2]:
                if k not in key_to_line and k not in seen:
                    pending.append(f"{k}={en_values[k]}")
                    seen.add(k)
                    added += 1
    flush()

    if not (added or dropped_lines):
        return 0, 0

    first_key_line = key_to_line[target_keys[0]] if target_keys else None
    new_lines: list[str] = []
    for i, line in enumerate(lines):
        if i in dropped_lines:
            continue
        if i == first_key_line:
            # Head insertions go below the file's own header comment, not above it.
            new_lines.extend(insert_at_head)
        new_lines.append(line)
        new_lines.extend(insert_after.get(i, ()))
    if first_key_line is None:
        new_lines.extend(insert_at_head)  # target had no keys at all

    target_path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
    return added, len(dropped_lines)


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
