#!/usr/bin/env python3
"""
Translate all keys in messages_XX.properties that still have English values
into the target language. Uses messages.properties (full English default) as reference.
Placeholders {0}, {1}, ${var} are preserved.
"""
from pathlib import Path
import re
import sys
import time

# Add venv if present
venv_python = Path(__file__).resolve().parent.parent / ".venv_translate" / "bin" / "python"
if venv_python.exists():
    # Run in subprocess to use venv
    pass

try:
    from deep_translator import GoogleTranslator
    from deep_translator.exceptions import (
        BaseError,
        RequestError,
        TooManyRequests,
    )
except ImportError:
    print("Install: pip install deep-translator", file=sys.stderr)
    sys.exit(1)

BASE = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "i18n"
LANG_MAP = {
    "messages_de.properties": "de",
    "messages_es.properties": "es",
    "messages_fr.properties": "fr",
    "messages_hr.properties": "hr",
    "messages_it.properties": "it",
    "messages_nl.properties": "nl",
    "messages_pt.properties": "pt",
}
PLACEHOLDER_RE = re.compile(r"(\{\d+\}|\$\{[^}]+\})")


def parse(path):
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v
    return out


def mask_placeholders(text):
    placeholders = []
    def repl(m):
        placeholders.append(m.group(0))
        return f"__PH_{len(placeholders)-1}__"
    masked = PLACEHOLDER_RE.sub(repl, text)
    return masked, placeholders


def unmask_placeholders(text, placeholders):
    for i, p in enumerate(placeholders):
        text = text.replace(f"__PH_{i}__", p)
    return text


def main():
    import sys
    only = None
    if len(sys.argv) > 1:
        only = set(sys.argv[1:])
    en = parse(BASE / "messages.properties")
    for fname, target in LANG_MAP.items():
        if only and fname not in only:
            continue
        path = BASE / fname
        lines = path.read_text(encoding="utf-8").splitlines()
        to_translate = []  # (line_index, key, current_value, masked, placeholders)
        for idx, line in enumerate(lines):
            if not line or line.lstrip().startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            ev = en.get(k)
            if ev is None or v != ev or not v.strip():
                continue
            masked, ph = mask_placeholders(v)
            to_translate.append((idx, k, v, masked, ph))

        if not to_translate:
            print(f"{fname}: nothing to translate")
            continue

        tr = GoogleTranslator(source="en", target=target)
        batch_size = 25
        changed = 0
        for i in range(0, len(to_translate), batch_size):
            chunk = to_translate[i : i + batch_size]
            texts = [t[3] for t in chunk]
            try:
                results = tr.translate_batch(texts)
            except (BaseError, RequestError, TooManyRequests) as e:
                print(f"{fname}: batch error {e}", file=sys.stderr)
                raise
            if not isinstance(results, (list, tuple)) or len(results) != len(chunk):
                raise ValueError(
                    f"translate_batch returned {len(results) if isinstance(results, (list, tuple)) else 'non-iterable'} "
                    f"items for chunk size {len(chunk)} (translate_batch/results/chunk mismatch)"
                )
            for (idx, k, v, masked, ph), out in zip(chunk, results):
                translated = unmask_placeholders(out if out else masked, ph)
                if translated and translated != v:
                    lines[idx] = f"{k}={translated}"
                    changed += 1
            if i + batch_size < len(to_translate):
                time.sleep(0.5)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{fname}: translated {changed} keys")


if __name__ == "__main__":
    main()
