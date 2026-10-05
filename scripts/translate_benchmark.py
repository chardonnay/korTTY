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

Short by design: without options the benchmark runs the --quick preset (24 fixed lines,
spread evenly over the fixture's line kinds, concurrency 8, batch 4 - about two minutes for
a mid-size model). --max-seconds N is a global time budget: when it is hit the run stops
cleanly and the report is flagged PARTIAL, extrapolating from the lines/min measured so far.
--sweep compares several concurrency/batch settings on a small sample, each limited by
--config-timeout. The full 110-line run needs an explicit --full (or --limit N).

Usage (docs venv):
  .venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio \
      --model qwen/qwen3-4b-2507                              # = --quick
  .venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio \
      --model openai/gpt-oss-20b --max-seconds 120 --request-timeout 60
  .venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio \
      --model openai/gpt-oss-20b --sweep concurrency=4,8,16 batch=2,4,8 --config-timeout 90
  .venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio --full \
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


QUICK_LINES = 24
QUICK_CONCURRENCY = 8
QUICK_BATCH_LINES = 4
DEFAULT_REQUEST_TIMEOUT = 120.0
DEFAULT_SWEEP_LINES = 24
DEFAULT_CONFIG_TIMEOUT = 90.0


def quick_subset(samples: list[dict], n: int = QUICK_LINES) -> list[dict]:
    """Deterministic n-line subset of the fixture, spread evenly over its line kinds.

    Each kind gets n // kinds lines (the remainder goes to the kinds in alphabetical order; a
    kind with fewer lines gives its unused share to the others). Within a kind the lines are
    evenly spaced through the fixture order, so different pages are covered. The result is
    interleaved round-robin by kind - a run that is cut short still saw every kind."""
    n = min(n, len(samples))
    by_kind: dict[str, list[dict]] = {}
    for sample in samples:
        by_kind.setdefault(sample["kind"], []).append(sample)
    kinds = sorted(by_kind)
    if not kinds or n <= 0:
        return []
    share = {k: 0 for k in kinds}
    remaining = n
    while remaining > 0:
        open_kinds = [k for k in kinds if share[k] < len(by_kind[k])]
        if not open_kinds:
            break
        per, extra = divmod(remaining, len(open_kinds))
        for i, k in enumerate(open_kinds):
            add = min(per + (1 if i < extra else 0), len(by_kind[k]) - share[k])
            share[k] += add
            remaining -= add
    picked: dict[str, list[dict]] = {}
    for k in kinds:
        pool, take = by_kind[k], share[k]
        picked[k] = [pool[(i * len(pool)) // take] for i in range(take)] if take else []
    out: list[dict] = []
    for i in range(max(share.values())):
        for k in kinds:
            if i < len(picked[k]):
                out.append(picked[k][i])
    return out


def parse_sweep(spec: list[str]) -> list[tuple[int, int]]:
    """['concurrency=4,8', 'batch=2,4'] -> [(4, 2), (4, 4), (8, 2), (8, 4)]. A missing axis
    uses the quick preset value."""
    axes: dict[str, list[int]] = {"concurrency": [QUICK_CONCURRENCY], "batch": [QUICK_BATCH_LINES]}
    for item in spec:
        key, sep, values = item.partition("=")
        key = {"batch-lines": "batch", "batch_lines": "batch"}.get(key.strip(), key.strip())
        if not sep or key not in axes:
            raise ValueError(f"bad --sweep item {item!r}: expected concurrency=N,N,... or batch=N,N,...")
        try:
            numbers = [int(v) for v in values.split(",") if v.strip()]
        except ValueError:
            raise ValueError(f"bad --sweep item {item!r}: values must be integers") from None
        if not numbers or any(v < 1 for v in numbers):
            raise ValueError(f"bad --sweep item {item!r}: values must be positive integers")
        axes[key] = numbers
    return [(c, b) for c in axes["concurrency"] for b in axes["batch"]]


def fmt_duration(seconds: float | None) -> str:
    if seconds is None:
        return "-"
    return f"{seconds:.0f} s" if seconds < 120 else f"{seconds / 60:.1f} min"


def extrapolate(lines: int, lines_per_min: float | None) -> float | None:
    """Minutes for `lines` at the measured rate, or None if nothing was measured."""
    return round(lines / lines_per_min, 1) if lines_per_min else None


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


def measure(backend, samples: list[dict], deadline: float | None) -> dict:
    """Translates `samples` through the pipeline, stopping at `deadline` (perf_counter time).

    Returns the raw outputs plus wall time, the indices of the lines that finished before
    the box was hit and whether the run is partial. Lines cut off by the deadline are not
    scored."""
    backend.deadline = deadline
    backend.deadline_hit = False
    items = [td.mask(s["en"]) for s in samples]
    started = time.perf_counter()
    results, stats = td.translate_masked(backend, items)
    wall = time.perf_counter() - started
    hit = bool(getattr(backend, "deadline_hit", False))
    if hit:
        # A None after the box was hit is "not done", not "failed": keep only answered lines.
        done_idx = [i for i, out in enumerate(results) if out is not None]
        stats = dict(stats, failed=0)
    else:
        done_idx = list(range(len(samples)))
    backend.deadline = None
    return {"items": items, "results": results, "stats": stats, "wall": wall,
            "done_idx": done_idx, "partial": hit}


def build_result(args, backend, model: str, samples: list[dict], m: dict, terms,
                 changed_lines: int, full_lines: int, load_s: float | None,
                 warmup_s: float | None) -> dict:
    done = m["done_idx"]
    stats, wall = m["stats"], m["wall"]
    hyps: list[str] = []
    refs: list[str] = []
    per_line: list[dict] = []
    expected_total = expected_hit = 0
    for i in done:
        sample, (masked, store), out = samples[i], m["items"][i], m["results"][i]
        raw = td.finish_line(sample["en"], td.unmask(out, store)) if out is not None else sample["en"]
        final = td.apply_glossary(raw)
        expected = terms.expected_terms(masked)
        hits = [t for t in expected if t in raw]
        expected_total += len(expected)
        expected_hit += len(hits)
        hyps.append(final)
        refs.append(sample["de"])
        per_line.append({"id": sample["id"], "kind": sample["kind"], "en": sample["en"],
                         "reference": sample["de"], "output": final, "failed": out is None,
                         "expected_terms": expected, "missing_terms": [t for t in expected if t not in hits],
                         "chrf": round(chrf_from_stats(chrf_stats(final, sample["de"])), 1)})

    reqs = list(backend.log.requests)
    latencies = [r["latency"] for r in reqs]
    completion = [r["completion_tokens"] for r in reqs if r.get("completion_tokens")]
    decode_rates = [r["completion_tokens"] / r["latency"] for r in reqs
                    if r.get("completion_tokens") and r["latency"] > 0]
    lines_per_s = len(done) / wall if wall > 0 and done else 0.0
    lpm = lines_per_s * 60 if lines_per_s else None

    by_kind = {}
    for kind in sorted({samples[i]["kind"] for i in done}):
        idx = [j for j, i in enumerate(done) if samples[i]["kind"] == kind]
        by_kind[kind] = round(corpus_chrf([hyps[j] for j in idx], [refs[j] for j in idx]), 1)

    return {
        "backend": args.backend, "model": model,
        "concurrency": getattr(backend, "concurrency", 1), "batch_lines": getattr(backend, "batch_lines", 20),
        "lines": len(samples), "lines_done": len(done), "partial": m["partial"],
        "load_s": round(load_s, 1) if load_s is not None else None,
        "warmup_s": round(warmup_s, 2) if warmup_s is not None else None,
        "wall_s": round(wall, 1),
        "lines_per_min": round(lpm, 1) if lpm else None,
        "requests": len(reqs),
        "latency_mean_s": round(statistics.mean(latencies), 2) if latencies else None,
        "latency_median_s": round(statistics.median(latencies), 2) if latencies else None,
        "latency_p95_s": round(percentile(latencies, 0.95), 2) if latencies else None,
        "completion_tokens": sum(completion) if completion else None,
        "reasoning_tokens": sum(r["reasoning_tokens"] or 0 for r in reqs) if completion else None,
        "tokens_per_s_aggregate": round(sum(completion) / wall, 1) if completion and wall else None,
        "tokens_per_s_per_request": round(statistics.mean(decode_rates), 1) if decode_rates else None,
        "answers_in_reasoning": sum(1 for r in reqs if r.get("answer_in_reasoning")),
        "placeholder_first_pass_rate": round(100 * stats["first_pass_ok"] / max(1, stats["lines"]), 1)
        if not m["partial"] else None,
        "retried": stats["retried"], "fragment_fallback": stats["fragment_fallback"],
        "failed": stats["failed"], "meta_replies": stats.get("meta_replies", 0),
        "term_adherence_rate": round(100 * expected_hit / expected_total, 1) if expected_total else None,
        "expected_terms": expected_total,
        "chrf": round(corpus_chrf(hyps, refs), 1) if hyps else None,
        "chrf_by_kind": by_kind,
        "changed_lines": changed_lines,
        "eta_changed_min": extrapolate(changed_lines, lpm),
        "full_lines": full_lines,
        "eta_full_min": extrapolate(full_lines, lpm),
        "per_line": per_line,
    }


def prepare_model(args, model: str | None) -> tuple[float | None, bool]:
    """Makes sure an LM Studio model is loaded. Returns (load seconds or None, loaded here)."""
    if args.backend != "lmstudio":
        return None, False
    base = args.base_url or td.DEFAULT_BASE_URLS["lmstudio"]
    state = lmstudio_model_state(base, model)
    if state == "loaded":
        print("   already loaded - load time not measured", flush=True)
        return None, False
    if args.no_load:
        sys.exit(f"{model} is not loaded (state: {state}); drop --no-load or load it in LM Studio")
    parallel = args.concurrency or QUICK_CONCURRENCY
    print(f"   loading {model} (context {args.context_length}, parallel {parallel}) ...", flush=True)
    load_s = lmstudio_load(model, args.context_length, parallel)
    print(f"   loaded in {load_s:.1f}s", flush=True)
    return load_s, True


def run_model(args, model: str | None, plans: list[dict], changed_lines: int, full_lines: int,
              deadline: float | None) -> list[dict]:
    """Runs one model through the planned configurations (each plan: concurrency, batch_lines,
    samples, timeout) and returns one result per configuration."""
    label = model or args.backend
    print(f"\n== {args.backend} / {label}", flush=True)
    load_s, loaded_here = prepare_model(args, model)
    out: list[dict] = []
    for n, plan in enumerate(plans):
        backend = td.make_backend(args.backend, model, args.base_url, plan["concurrency"],
                                  plan["batch_lines"], args.reasoning_effort, args.request_timeout)
        terms = getattr(backend, "terms", None) or td.TermContext()
        warmup_s = None
        if n == 0:
            # The first request pays for prompt-cache/graph setup; measured separately.
            warm_masked, _store = td.mask("Open the **Settings** dialog and press `Save`.")
            backend.deadline = deadline
            started = time.perf_counter()
            try:
                backend.translate(warm_masked)
            except Exception as exc:  # noqa: BLE001
                print(f"   ! warm-up failed: {exc}")
            warmup_s = time.perf_counter() - started
        backend.log.requests.clear()
        box = deadline
        if plan.get("timeout"):
            cfg_deadline = time.perf_counter() + plan["timeout"]
            box = cfg_deadline if box is None else min(box, cfg_deadline)
        print(f"   concurrency {plan['concurrency']}, batch {plan['batch_lines']}, "
              f"{len(plan['samples'])} line(s)"
              + (f", box {fmt_duration(max(0.0, box - time.perf_counter()))}" if box is not None else ""),
              flush=True)
        m = measure(backend, plan["samples"], box)
        result = build_result(args, backend, label, plan["samples"], m, terms, changed_lines,
                              full_lines, load_s if n == 0 else None, warmup_s)
        out.append(result)
        if m["partial"]:
            print(f"   PARTIAL: {result['lines_done']}/{result['lines']} line(s) before the time box",
                  flush=True)
        if deadline is not None and time.perf_counter() >= deadline and n + 1 < len(plans):
            print("   global time budget exhausted - remaining configurations skipped", flush=True)
            break
    if loaded_here and args.unload_after:
        lmstudio_unload(model)
    return out


COLUMNS = [
    ("model", "model"), ("concurrency", "conc"), ("batch_lines", "batch"), ("lines_done", "lines"),
    ("load_s", "load s"), ("warmup_s", "warm-up s"), ("wall_s", "wall s"),
    ("lines_per_min", "lines/min"), ("latency_mean_s", "lat mean"), ("latency_median_s", "lat med"),
    ("latency_p95_s", "lat p95"), ("tokens_per_s_aggregate", "tok/s"),
    ("placeholder_first_pass_rate", "placeholders %"), ("term_adherence_rate", "terms %"),
    ("chrf", "chrF++"), ("failed", "failed"), ("eta_changed_min", "changed min"),
    ("eta_full_min", "full min"),
]


def _cell(r: dict, key: str) -> str:
    value = r.get(key)
    if value is None:
        return "-"
    if key == "lines_done":
        return f"{value}/{r['lines']}" + (" (partial)" if r.get("partial") else "")
    return str(value)


def table(results: list[dict]) -> str:
    head = "| " + " | ".join(h for _k, h in COLUMNS) + " |"
    sep = "|" + "|".join("---" for _ in COLUMNS) + "|"
    rows = ["| " + " | ".join(_cell(r, k) for k, _h in COLUMNS) + " |" for r in results]
    return "\n".join([head, sep, *rows])


def extrapolation_lines(results: list[dict], meta: dict) -> list[str]:
    """Human-readable wall time / partial flag / extrapolation block."""
    out = [f"Wall time (measured translation): {fmt_duration(meta['measured_wall_s'])}"
           + (" - PARTIAL (time budget hit; unfinished lines are not scored)" if meta["partial"] else ""),
           f"Worklist now: {meta['changed_lines']} line(s) (same memory logic as "
           f"`translate_docs.py --dry-run --memory-from-git`, changes since `{meta['changed_since']}`); "
           f"full re-translation: {meta['full_lines']} line(s)."]
    for r in results:
        label = f"{r['model']} (conc {r['concurrency']}, batch {r['batch_lines']})"
        if r["lines_per_min"] is None:
            out.append(f"- {label}: no line finished - no extrapolation possible")
            continue
        basis = f"{r['lines_done']}/{r['lines']} lines at {r['lines_per_min']} lines/min"
        out.append(f"- {label}: current worklist ~{fmt_duration(r['eta_changed_min'] * 60)}, "
                   f"full re-translation ~{fmt_duration(r['eta_full_min'] * 60)} "
                   f"(extrapolated from {basis}{', lower bound of the real rate' if r['partial'] else ''})")
    return out


def markdown_report(results: list[dict], meta: dict) -> str:
    flag = " - PARTIAL" if meta["partial"] else ""
    out = [f"# Translation benchmark - {meta['timestamp']}{flag}", "",
           f"Mode `{meta['mode']}`: {meta['samples']} sample line(s) from `{meta['fixture']}` "
           f"(source {meta['fixture_commit']}); backend `{meta['backend']}`. Extrapolations use the "
           f"measured lines/min; real pages with longer lines take longer.", "",
           *extrapolation_lines(results, meta), "",
           table(results), ""]
    for r in results:
        out += [f"## {r['model']} (concurrency {r['concurrency']}, batch {r['batch_lines']})", "",
                f"- {r['lines_done']}/{r['lines']} line(s)" + (" - PARTIAL" if r["partial"] else "")
                + f", {r['requests']} request(s), wall {r['wall_s']} s",
                f"- completion tokens {r['completion_tokens']} (reasoning {r['reasoning_tokens']}), "
                f"per-request decode {r['tokens_per_s_per_request']} tok/s, "
                f"answers found only in the reasoning channel: {r['answers_in_reasoning']}",
                f"- retried {r['retried']}, fragment fallback {r['fragment_fallback']}, failed {r['failed']}, "
                f"meta replies {r.get('meta_replies', 0)}",
                f"- chrF++ by kind: {r['chrf_by_kind']}", ""]
        worst = sorted(r["per_line"], key=lambda x: x["chrf"])[:5]
        if worst and not meta.get("sweep"):
            out += ["Lowest-scoring lines:", ""]
            for w in worst:
                out += [f"- `{w['id']}` ({w['kind']}, chrF {w['chrf']})", f"  - EN: {w['en'].strip()}",
                        f"  - out: {w['output'].strip()}", f"  - ref: {w['reference'].strip()}"]
            out.append("")
    out.append(f"Benchmark took {meta['benchmark_took_s']} s.")
    return "\n".join(out)


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description="Benchmark docs-translation backends and models. Short by default "
                    "(--quick); the full 110-line run needs --full.")
    td.add_backend_arguments(ap)
    ap.add_argument("--model", action="append", default=[],
                    help="model id (lmstudio; repeatable to compare several models in one run)")
    ap.add_argument("--quick", action="store_true",
                    help=f"preset (also the default): {QUICK_LINES} fixed lines spread over the line "
                         f"kinds, concurrency {QUICK_CONCURRENCY}, batch {QUICK_BATCH_LINES}, "
                         f"request timeout {DEFAULT_REQUEST_TIMEOUT:.0f} s")
    ap.add_argument("--full", action="store_true", help="run all fixture lines (~110; slow)")
    ap.add_argument("--limit", type=int, help="use only the first N fixture samples (not the quick subset)")
    ap.add_argument("--max-seconds", type=float, metavar="N",
                    help="global time budget for the whole run; when hit, stop cleanly, flag the "
                         "report PARTIAL and extrapolate from the lines/min measured so far (exit 0)")
    ap.add_argument("--sweep", nargs="+", metavar="AXIS=N,N",
                    help="compare settings, e.g. --sweep concurrency=4,8,16 batch=2,4,8 "
                         "(cartesian product; each configuration runs --sweep-lines lines)")
    ap.add_argument("--sweep-lines", type=int, default=DEFAULT_SWEEP_LINES,
                    help=f"lines per sweep configuration (default {DEFAULT_SWEEP_LINES})")
    ap.add_argument("--config-timeout", type=float, default=DEFAULT_CONFIG_TIMEOUT, metavar="S",
                    help=f"time box per sweep configuration in seconds (default {DEFAULT_CONFIG_TIMEOUT:.0f})")
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
    return ap


def plan_runs(args, fixture_samples: list[dict]) -> tuple[list[dict], str]:
    """Turns the options into (configurations, mode name). Explicit --concurrency/--batch-lines
    override the quick preset. Raises ValueError on inconsistent options."""
    if args.request_timeout is None:
        args.request_timeout = DEFAULT_REQUEST_TIMEOUT
    if args.sweep:
        if args.full or args.limit:
            raise ValueError("--sweep cannot be combined with --full/--limit")
        lines = quick_subset(fixture_samples, args.sweep_lines)
        plans = [{"concurrency": c, "batch_lines": b, "samples": lines, "timeout": args.config_timeout}
                 for c, b in parse_sweep(args.sweep)]
        return plans, "sweep"
    if args.full:
        samples, mode = fixture_samples, "full"
    elif args.limit:
        samples, mode = fixture_samples[: args.limit], "limit"
    else:
        samples, mode = quick_subset(fixture_samples, QUICK_LINES), "quick"
    if mode == "quick":
        conc, batch = args.concurrency or QUICK_CONCURRENCY, args.batch_lines or QUICK_BATCH_LINES
    else:
        conc, batch = args.concurrency, args.batch_lines
    return [{"concurrency": conc, "batch_lines": batch, "samples": samples, "timeout": None}], mode


def main(argv: list[str] | None = None) -> int:
    began = time.perf_counter()
    args = build_parser().parse_args(argv)

    if args.make_fixture:
        make_fixture()
        return 0
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    try:
        plans, mode = plan_runs(args, fixture["samples"])
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    deadline = began + args.max_seconds if args.max_seconds else None

    ref = args.changed_since or subprocess.run(
        ["git", "-C", str(REPO), "merge-base", "HEAD", "origin/main"],
        capture_output=True, text=True).stdout.strip() or "HEAD"
    plan = td.plan_pages(td.pages_changed_since(ref), memory_from_git=True)
    changed_lines = sum(p["misses"] for p in plan)
    full_lines = td.all_translatable_lines()

    models = args.model or ([td.DEFAULT_LMSTUDIO_MODEL] if args.backend == "lmstudio" else [None])
    results: list[dict] = []
    for m in models:
        if deadline is not None and time.perf_counter() >= deadline and results:
            print("global time budget exhausted - remaining models skipped", flush=True)
            break
        results += run_model(args, m, plans, changed_lines, full_lines, deadline)

    took = time.perf_counter() - began
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    meta = {"timestamp": stamp, "backend": args.backend, "mode": mode, "sweep": mode == "sweep",
            "samples": max(len(p["samples"]) for p in plans),
            "fixture": FIXTURE.relative_to(REPO).as_posix(), "fixture_commit": fixture.get("source_commit"),
            "changed_since": ref[:12], "changed_lines": changed_lines, "full_lines": full_lines,
            "partial": any(r["partial"] for r in results), "max_seconds": args.max_seconds,
            "measured_wall_s": round(sum(r["wall_s"] for r in results), 1),
            "benchmark_took_s": round(took, 1)}
    slug = "+".join(dict.fromkeys(re.sub(r"[^A-Za-z0-9._-]+", "_", r["model"]) for r in results))
    args.out.mkdir(parents=True, exist_ok=True)
    base = args.out / f"{stamp}-{slug}"
    base.with_suffix(".json").write_text(
        json.dumps({"meta": meta, "results": results}, ensure_ascii=False, indent=1), encoding="utf-8")
    base.with_suffix(".md").write_text(markdown_report(results, meta), encoding="utf-8")
    print("\n" + table(results) + "\n")
    print("\n".join(extrapolation_lines(results, meta)))
    print(f"report: {base.with_suffix('.md')} (+ .json)")
    print(f"benchmark took {took:.1f} s" + (" (PARTIAL: time budget hit)" if meta["partial"] else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
