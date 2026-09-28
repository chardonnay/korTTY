#!/usr/bin/env python3
"""Benchmark a docs-translation backend/model against the committed German guide.

Runs a fixed sample of English guide lines (scripts/translate_benchmark_samples.json)
through the same pipeline as scripts/translate_docs.py — masking, batching, placeholder
validation with one retry and the fragment fallback, glossary post-pass — and compares
the result with the German line the guide already ships.

Measures per model: load/warm-up time, wall time, lines/min, request latency
(mean/median/p95), tokens/s where the server reports usage, placeholder survival on the
first pass, UI-term/glossary adherence, chrF++ against the reference, failures — and
extrapolates the time for (a) the lines the current branch needs translated (same
memory logic as translate_docs.py --dry-run --memory-from-git) and (b) a full
re-translation of the guide.

Usage (docs venv):
  .venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio \\
      --model openai/gpt-oss-20b --model qwen/qwen3-4b-2507 --concurrency 4 --batch-lines 8
  .venv-docs/bin/python scripts/translate_benchmark.py --backend google --limit 40
  .venv-docs/bin/python scripts/translate_benchmark.py --make-fixture   # re-select samples

Reports: build/translate-benchmark/<timestamp>-<model>.{json,md}. The sample file is
committed so results stay comparable over time — regenerate it only deliberately.
The references are the committed (machine-translated, glossary-corrected) German guide,
so chrF measures closeness to the current guide, not absolute quality.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import re
import statistics
import subprocess
import sys
import time
import urllib.request
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import translate_docs as td  # noqa: E402

REPO = td.REPO
FIXTURE = Path(__file__).resolve().parent / "translate_benchmark_samples.json"
REPORT_DIR = REPO / "build" / "translate-benchmark"
LMS = Path.home() / ".lmstudio" / "bin" / "lms"

# Sample quotas per line kind — ~110 lines, all kinds of Markdown the guide uses.
QUOTAS = {"prose": 30, "heading": 12, "list": 18, "table": 18, "admonition": 10, "inline": 22}


# ---------------------------------------------------------------------------
# chrF++ (pure Python; sacrebleu's definition: character n-grams 1..6 without
# whitespace, word n-grams 1..2, beta = 2, F averaged over the n-gram orders,
# statistics summed over the corpus).
# ---------------------------------------------------------------------------

def _char_ngrams(text: str, n: int) -> Counter:
    s = re.sub(r"\s+", "", text)
    return Counter(s[i:i + n] for i in range(len(s) - n + 1))


def _words(text: str) -> list[str]:
    out: list[str] = []
    for token in text.split():
        m = re.match(r"^(\W*)(.*?)(\W*)$", token)
        lead, core, trail = m.groups()
        out += [c for c in lead] + ([core] if core else []) + [c for c in trail]
    return out


def _word_ngrams(text: str, n: int) -> Counter:
    w = _words(text)
    return Counter(tuple(w[i:i + n]) for i in range(len(w) - n + 1))


def chrf_stats(hyp: str, ref: str, char_order: int = 6, word_order: int = 2) -> list[int]:
    stats: list[int] = []
    for n in range(1, char_order + 1):
        h, r = _char_ngrams(hyp, n), _char_ngrams(ref, n)
        stats += [sum(h.values()), sum(r.values()), sum((h & r).values())]
    for n in range(1, word_order + 1):
        h, r = _word_ngrams(hyp, n), _word_ngrams(ref, n)
        stats += [sum(h.values()), sum(r.values()), sum((h & r).values())]
    return stats


def chrf_from_stats(stats: list[int], beta: float = 2.0) -> float:
    factor = beta ** 2
    score, orders = 0.0, 0
    for i in range(0, len(stats), 3):
        n_hyp, n_ref, n_match = stats[i:i + 3]
        if n_hyp == 0 or n_ref == 0:
            continue
        orders += 1
        prec, rec = n_match / n_hyp, n_match / n_ref
        denom = factor * prec + rec
        score += (1 + factor) * prec * rec / denom if denom > 0 else 0.0
    return 100.0 * score / orders if orders else 0.0


def corpus_chrf(hyps: list[str], refs: list[str]) -> float:
    total: list[int] | None = None
    for h, r in zip(hyps, refs):
        s = chrf_stats(h, r)
        total = s if total is None else [a + b for a, b in zip(total, s)]
    return chrf_from_stats(total or [])


# ---------------------------------------------------------------------------
# Sample selection
# ---------------------------------------------------------------------------

def line_kind(line: str) -> str:
    stripped = line.strip()
    if re.match(r"#{1,6}\s", stripped):
        return "heading"
    if stripped.startswith(("!!!", "???")) or (line.startswith("    ") and not re.match(r"\s*(?:[-*+]|\d+[.)])\s", line)):
        return "admonition"
    if stripped.startswith("|"):
        return "table"
    if re.search(r"`|\+\+[^+\s]|\]\(", stripped):
        return "inline"
    if re.match(r"(?:[-*+]|\d+[.)])\s", stripped):
        return "list"
    return "prose"


def select_samples(candidates: list[dict], quotas: dict[str, int] = QUOTAS) -> list[dict]:
    """Deterministic: within each kind, candidates are ordered by a hash of their English
    text (spreads the pick over pages without randomness) and the first `quota` are taken.
    Output order is kind, then hash — stable for any input order."""
    by_kind: dict[str, list[dict]] = {}
    seen: set[str] = set()
    for cand in candidates:
        if cand["en"] in seen:
            continue
        seen.add(cand["en"])
        by_kind.setdefault(cand["kind"], []).append(cand)
    picked: list[dict] = []
    for kind in sorted(quotas):
        pool = sorted(by_kind.get(kind, []),
                      key=lambda c: hashlib.sha256(c["en"].encode("utf-8")).hexdigest())
        picked += pool[:quotas[kind]]
    return [{"id": f"s{i + 1:03d}", **c} for i, c in enumerate(picked)]


def collect_candidates() -> list[dict]:
    """(English, trusted German) line pairs: lines of the current English guide whose
    committed German line is a validated memory match (same logic as translate_docs.py)."""
    candidates: list[dict] = []
    for src in sorted(td.EN.rglob("*.md")):
        rel = src.relative_to(td.EN)
        if any(part in td.SKIP_DIRS for part in rel.parts) or rel.parts[0] == "about":
            continue  # release notes are lists of feature names, not representative prose
        dst = td.DE / rel
        if not dst.is_file():
            continue
        old_en = td.git_aligned_english(src)
        de_md = dst.read_text(encoding="utf-8")
        memory = td.build_page_memory(old_en, de_md)
        if not memory:
            continue
        en_lines = src.read_text(encoding="utf-8").split("\n")
        _lines, jobs = td.translatable_lines("\n".join(en_lines))
        for idx, masked, store in jobs:
            if masked not in memory or en_lines[idx].startswith("title:"):
                continue
            words = re.findall(r"[A-Za-z]{2,}", re.sub(r"KTPH\d{3}", " ", masked))
            if len(words) < 4 or len(en_lines[idx]) > 400:
                continue
            de_line = td.unmask(memory[masked], store)
            if "KTPH" in de_line:
                continue
            empty_cell = re.compile(r"\|\s*(?=\|)")
            if len(empty_cell.findall(de_line)) != len(empty_cell.findall(en_lines[idx])):
                continue  # a reference row whose cells shifted is not a trustworthy reference
            indent = en_lines[idx][:len(en_lines[idx]) - len(en_lines[idx].lstrip(" "))]
            candidates.append({"page": rel.as_posix(), "kind": line_kind(en_lines[idx]),
                               "en": en_lines[idx], "de": indent + de_line.lstrip(" ")})
    return candidates


def make_fixture() -> None:
    samples = select_samples(collect_candidates())
    head = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"],
                          capture_output=True, text=True).stdout.strip()
    data = {
        "version": 1,
        "source_commit": head,
        "note": "English guide lines with their committed German line as reference. Selected by "
                "scripts/translate_benchmark.py --make-fixture (hash-ordered per line kind, quotas "
                f"{QUOTAS}). Keep fixed so benchmark runs stay comparable.",
        "kinds": dict(Counter(s["kind"] for s in samples)),
        "samples": samples,
    }
    FIXTURE.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {len(samples)} samples to {FIXTURE.relative_to(REPO)} ({data['kinds']})")


# ---------------------------------------------------------------------------
# LM Studio model handling
# ---------------------------------------------------------------------------

def _server_root(base_url: str) -> str:
    return re.sub(r"/v1/?$", "", base_url.rstrip("/"))


def lmstudio_model_state(base_url: str, model: str) -> str | None:
    try:
        with urllib.request.urlopen(f"{_server_root(base_url)}/api/v0/models", timeout=10) as r:
            for m in json.loads(r.read().decode("utf-8")).get("data", []):
                if m.get("id") == model:
                    return m.get("state")
    except Exception:  # noqa: BLE001
        return None
    return None


def lmstudio_load(model: str, context_length: int, parallel: int) -> float:
    cmd = [str(LMS), "load", model, "-y", "--context-length", str(context_length),
           "--parallel", str(parallel)]
    started = time.perf_counter()
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=900, check=True)
    return time.perf_counter() - started


def lmstudio_unload(model: str) -> None:
    subprocess.run([str(LMS), "unload", model], stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, timeout=120)


# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------

def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    k = (len(ordered) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def run_one(args, model: str | None, samples: list[dict], changed_lines: int, full_lines: int) -> dict:
    label = model or args.backend
    print(f"\n== {args.backend} / {label}", flush=True)
    load_s = None
    loaded_here = False
    if args.backend == "lmstudio":
        base = args.base_url or td.DEFAULT_BASE_URLS["lmstudio"]
        state = lmstudio_model_state(base, model)
        if state != "loaded":
            if args.no_load:
                sys.exit(f"{model} is not loaded (state: {state}); drop --no-load or load it in LM Studio")
            print(f"   loading {model} (context {args.context_length}, parallel {args.concurrency or 4}) ...",
                  flush=True)
            load_s = lmstudio_load(model, args.context_length, args.concurrency or 4)
            loaded_here = True
            print(f"   loaded in {load_s:.1f}s", flush=True)
        else:
            print("   already loaded — load time not measured", flush=True)
    backend = td.make_backend(args.backend, model, args.base_url, args.concurrency, args.batch_lines)
    terms = getattr(backend, "terms", None) or td.TermContext()

    # Warm-up: the first request pays for prompt-cache/graph setup; measured separately.
    warm_masked, _warm_store = td.mask("Open the **Settings** dialog and press `Save`.")
    started = time.perf_counter()
    try:
        backend.translate(warm_masked)
    except Exception as exc:  # noqa: BLE001
        print(f"   ! warm-up failed: {exc}")
    warmup_s = time.perf_counter() - started
    backend.log.requests.clear()

    items = [td.mask(s["en"]) for s in samples]
    started = time.perf_counter()
    results, stats = td.translate_masked(backend, items)
    wall = time.perf_counter() - started

    hyps: list[str] = []
    per_line: list[dict] = []
    expected_total = expected_hit = 0
    for sample, (masked, store), out in zip(samples, items, results):
        raw = td.finish_line(sample["en"], td.unmask(out, store)) if out is not None else sample["en"]
        final = td.apply_glossary(raw)
        expected = terms.expected_terms(masked)
        hits = [t for t in expected if t in raw]
        expected_total += len(expected)
        expected_hit += len(hits)
        hyps.append(final)
        per_line.append({"id": sample["id"], "kind": sample["kind"], "en": sample["en"],
                         "reference": sample["de"], "output": final, "failed": out is None,
                         "expected_terms": expected, "missing_terms": [t for t in expected if t not in hits],
                         "chrf": round(chrf_from_stats(chrf_stats(final, sample["de"])), 1)})
    refs = [s["de"] for s in samples]

    reqs = list(backend.log.requests)
    latencies = [r["latency"] for r in reqs]
    completion = [r["completion_tokens"] for r in reqs if r.get("completion_tokens")]
    decode_rates = [r["completion_tokens"] / r["latency"] for r in reqs
                    if r.get("completion_tokens") and r["latency"] > 0]
    lines_per_s = len(samples) / wall if wall > 0 else 0.0

    def eta(lines: int) -> float | None:
        return lines / lines_per_s if lines_per_s else None

    by_kind = {}
    for kind in sorted({s["kind"] for s in samples}):
        idx = [i for i, s in enumerate(samples) if s["kind"] == kind]
        by_kind[kind] = round(corpus_chrf([hyps[i] for i in idx], [refs[i] for i in idx]), 1)

    result = {
        "backend": args.backend, "model": label,
        "concurrency": getattr(backend, "concurrency", 1), "batch_lines": getattr(backend, "batch_lines", 20),
        "lines": len(samples),
        "load_s": round(load_s, 1) if load_s is not None else None,
        "warmup_s": round(warmup_s, 2),
        "wall_s": round(wall, 1),
        "lines_per_min": round(lines_per_s * 60, 1),
        "requests": len(reqs),
        "latency_mean_s": round(statistics.mean(latencies), 2) if latencies else None,
        "latency_median_s": round(statistics.median(latencies), 2) if latencies else None,
        "latency_p95_s": round(percentile(latencies, 0.95), 2) if latencies else None,
        "completion_tokens": sum(completion) if completion else None,
        "reasoning_tokens": sum(r["reasoning_tokens"] or 0 for r in reqs) if completion else None,
        "tokens_per_s_aggregate": round(sum(completion) / wall, 1) if completion and wall else None,
        "tokens_per_s_per_request": round(statistics.mean(decode_rates), 1) if decode_rates else None,
        "answers_in_reasoning": sum(1 for r in reqs if r.get("answer_in_reasoning")),
        "placeholder_first_pass_rate": round(100 * stats["first_pass_ok"] / max(1, stats["lines"]), 1),
        "retried": stats["retried"], "fragment_fallback": stats["fragment_fallback"],
        "failed": stats["failed"],
        "term_adherence_rate": round(100 * expected_hit / expected_total, 1) if expected_total else None,
        "expected_terms": expected_total,
        "chrf": round(corpus_chrf(hyps, refs), 1),
        "chrf_by_kind": by_kind,
        "changed_lines": changed_lines,
        "eta_changed_min": round(eta(changed_lines) / 60, 1) if eta(changed_lines) is not None else None,
        "full_lines": full_lines,
        "eta_full_min": round(eta(full_lines) / 60, 1) if eta(full_lines) is not None else None,
        "per_line": per_line,
    }
    if loaded_here and args.unload_after:
        lmstudio_unload(model)
    return result


COLUMNS = [
    ("model", "model"), ("load_s", "load s"), ("warmup_s", "warm-up s"), ("wall_s", "wall s"),
    ("lines_per_min", "lines/min"), ("latency_mean_s", "lat mean"), ("latency_median_s", "lat med"),
    ("latency_p95_s", "lat p95"), ("tokens_per_s_aggregate", "tok/s"),
    ("placeholder_first_pass_rate", "placeholders %"), ("term_adherence_rate", "terms %"),
    ("chrf", "chrF++"), ("failed", "failed"), ("eta_changed_min", "changed min"),
    ("eta_full_min", "full min"),
]


def table(results: list[dict]) -> str:
    head = "| " + " | ".join(h for _k, h in COLUMNS) + " |"
    sep = "|" + "|".join("---" for _ in COLUMNS) + "|"
    rows = ["| " + " | ".join("—" if r.get(k) is None else str(r.get(k)) for k, _h in COLUMNS) + " |"
            for r in results]
    return "\n".join([head, sep, *rows])


def markdown_report(results: list[dict], meta: dict) -> str:
    out = [f"# Translation benchmark — {meta['timestamp']}", "",
           f"Samples: {meta['samples']} lines from `{meta['fixture']}` (source {meta['fixture_commit']}); "
           f"backend `{meta['backend']}`; changed lines since `{meta['changed_since']}`: "
           f"{meta['changed_lines']} (same memory logic as `translate_docs.py --dry-run --memory-from-git`; "
           f"0 once the German pages are regenerated in this working tree); full guide: "
           f"{meta['full_lines']} lines. Extrapolations use the measured lines/min; real pages with "
           f"longer lines take longer.", "",
           table(results), ""]
    for r in results:
        out += [f"## {r['model']}", "",
                f"- concurrency {r['concurrency']}, batch {r['batch_lines']} line(s), {r['requests']} request(s)",
                f"- completion tokens {r['completion_tokens']} (reasoning {r['reasoning_tokens']}), "
                f"per-request decode {r['tokens_per_s_per_request']} tok/s, "
                f"answers found only in the reasoning channel: {r['answers_in_reasoning']}",
                f"- retried {r['retried']}, fragment fallback {r['fragment_fallback']}, failed {r['failed']}",
                f"- chrF++ by kind: {r['chrf_by_kind']}", ""]
        worst = sorted(r["per_line"], key=lambda x: x["chrf"])[:5]
        out += ["Lowest-scoring lines:", ""]
        for w in worst:
            out += [f"- `{w['id']}` ({w['kind']}, chrF {w['chrf']})", f"  - EN: {w['en'].strip()}",
                    f"  - out: {w['output'].strip()}", f"  - ref: {w['reference'].strip()}"]
        out.append("")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description="Benchmark docs-translation backends and models.")
    td.add_backend_arguments(ap)
    ap.add_argument("--model", action="append", default=[],
                    help="model id (lmstudio; repeatable to compare several models in one run)")
    ap.add_argument("--limit", type=int, help="use only the first N samples")
    ap.add_argument("--changed-since", help="git ref for the 'changed lines' extrapolation "
                                            "(default: merge-base with origin/main)")
    ap.add_argument("--context-length", type=int, default=16384,
                    help="context length when this script loads an LM Studio model")
    ap.add_argument("--no-load", action="store_true", help="never load a model (fail if not loaded)")
    ap.add_argument("--unload-after", action="store_true",
                    help="unload a model this script loaded once its run is done")
    ap.add_argument("--make-fixture", action="store_true",
                    help="re-select the sample lines and rewrite the fixture, then exit")
    ap.add_argument("--out", type=Path, default=REPORT_DIR, help="report directory")
    args = ap.parse_args()

    if args.make_fixture:
        make_fixture()
        return 0
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    samples = fixture["samples"][: args.limit] if args.limit else fixture["samples"]

    ref = args.changed_since or subprocess.run(
        ["git", "-C", str(REPO), "merge-base", "HEAD", "origin/main"],
        capture_output=True, text=True).stdout.strip() or "HEAD"
    plan = td.plan_pages(td.pages_changed_since(ref), memory_from_git=True)
    changed_lines = sum(p["misses"] for p in plan)
    full_lines = td.all_translatable_lines()

    models = args.model or ([td.DEFAULT_LMSTUDIO_MODEL] if args.backend == "lmstudio" else [None])
    results = [run_one(args, m, samples, changed_lines, full_lines) for m in models]

    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    meta = {"timestamp": stamp, "backend": args.backend, "samples": len(samples),
            "fixture": FIXTURE.relative_to(REPO).as_posix(), "fixture_commit": fixture.get("source_commit"),
            "changed_since": ref[:12], "changed_lines": changed_lines, "full_lines": full_lines}
    slug = "+".join(re.sub(r"[^A-Za-z0-9._-]+", "_", r["model"]) for r in results)
    args.out.mkdir(parents=True, exist_ok=True)
    base = args.out / f"{stamp}-{slug}"
    base.with_suffix(".json").write_text(
        json.dumps({"meta": meta, "results": results}, ensure_ascii=False, indent=1), encoding="utf-8")
    base.with_suffix(".md").write_text(markdown_report(results, meta), encoding="utf-8")
    print("\n" + table(results))
    print(f"\nchanged lines ({ref[:12]}..): {changed_lines}; full guide: {full_lines} lines")
    print(f"report: {base.with_suffix('.md')} (+ .json)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
