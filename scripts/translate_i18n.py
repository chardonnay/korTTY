#!/usr/bin/env python3
"""
Translate all keys in messages_XX.properties that still have English values
into the target language. Uses messages.properties (full English default) as reference.
Placeholders {0}, {1}, ${var} are preserved. Pass --prefix=KEY_PREFIX to limit
translation to a section of each selected bundle.
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

# Values that legitimately stay identical to English in every target language — proper
# nouns, product/format/protocol names, and acronyms — so being identical to the EN
# value is not evidence of an untranslated gap. Without this, a plain re-run treats
# these as "still needs translation" and Google Translate mistranslates them as common
# words on the next pass (confirmed: "Markdown" as the retail discount sense in 6
# languages, "Mermaid" as the sea creature, "Hugging Face" translated literally as the
# emoji gesture, "Llama"/"Bash"/"Python"/"Ruby" as animal/gemstone/verb false friends —
# see the fix in this same commit). Keep this list scoped to values seen going wrong in
# practice rather than every conceivable proper noun; a new one is one line to add here
# after the same investigation.
PROTECTED_VALUES = {
    "Markdown", "Markdown (.md)", "Mermaid", "Metal", "Llama", "Vulkan", "CPU",
    "GGUF", "GGUF + MLX", "MLX", "PDF", "JSON", "XML", "YAML", "Shell:", "Host:",
    "Host", "Port:", "Port", "Type", "Type:", "Brave Search MCP", "Gemma",
    "DeepSeek", "Phi", "Mistral", "OpenAI", "Bash", "Git Bash", "Python (.py)",
    "Ruby (.rb)", "Info", "Options", "Options:", "Copy Mermaid",
}


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
    only = None
    prefix = None
    for argument in sys.argv[1:]:
        if argument.startswith("--prefix="):
            prefix = argument.removeprefix("--prefix=")
        else:
            if only is None:
                only = set()
            only.add(argument)
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
            if (ev is None or v != ev or not v.strip() or v in PROTECTED_VALUES
                    or (prefix is not None and not k.startswith(prefix))):
                continue
            masked, ph = mask_placeholders(v)
            to_translate.append((idx, k, v, masked, ph))

        if not to_translate:
            print(f"{fname}: nothing to translate")
            continue

        tr = GoogleTranslator(source="en", target=target)
        batch_size = 25
        changed = 0
        failed = []
        for i in range(0, len(to_translate), batch_size):
            chunk = to_translate[i : i + batch_size]
            texts = [t[3] for t in chunk]
            results = None
            try:
                results = tr.translate_batch(texts)
                if not isinstance(results, (list, tuple)) or len(results) != len(chunk):
                    results = None
            except (BaseError, RequestError, TooManyRequests, Exception):  # noqa: BLE001
                results = None
            if results is None:
                # A single untranslatable string aborts the whole batch call (deep_translator
                # raises rather than skipping it) — fall back to per-item translation, with one
                # retry after a backoff for transient failures, keeping English where a string
                # genuinely cannot be translated (better an English phrase than a crashed run
                # that silently drops every language after the one that hit this).
                results = []
                for text in texts:
                    r = None
                    for attempt in range(2):
                        try:
                            r = tr.translate(text)
                            if r:
                                break
                        except Exception:  # noqa: BLE001
                            r = None
                        if attempt == 0:
                            time.sleep(1.0)
                    results.append(r if r else text)
                    if not r:
                        failed.append(text)
                    time.sleep(0.2)
            for (idx, k, v, masked, ph), out in zip(chunk, results):
                translated = unmask_placeholders(out if out else masked, ph)
                if translated and translated != v:
                    lines[idx] = f"{k}={translated}"
                    changed += 1
            if i + batch_size < len(to_translate):
                time.sleep(0.5)
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        if failed:
            print(f"{fname}: {len(failed)} key(s) kept English text after translation failed twice:",
                  file=sys.stderr)
            for text in failed:
                preview = text if len(text) <= 80 else text[:77] + "..."
                print(f"    {preview!r}", file=sys.stderr)
        print(f"{fname}: translated {changed} keys")


if __name__ == "__main__":
    main()
