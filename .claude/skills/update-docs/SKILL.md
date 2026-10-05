---
name: update-docs
description: >-
  Reconcile the korTTY guide docs with code/i18n changes. Use after adding or
  changing a user-facing feature, setting, menu item, dialog or shortcut, before
  a release, or when the docs-validate CI fails. Diffs each doc page against the
  code it owns since its last-synced commit, patches the affected Markdown and
  reference tables from the i18n labels, refreshes diagrams/screenshots, syncs the
  version, and re-runs the validators.
---

# Update korTTY documentation

You keep `app-docs/site/docs/en/**` + `README.adoc` in lockstep with the code.
Follow `/AGENTS.md` ("Documentation system") exactly. **Never invent features** —
every claim must trace to code or an i18n label. Author English only; German is
generated. Do **not** commit unless asked.

## Inputs
- `app-docs/doc-manifest.yaml` — the page ↔ code ↔ i18n map (the contract).
- `build.gradle.kts:12` — the canonical version.
- `src/main/resources/i18n/messages.properties` — canonical labels/keys (English base bundle).

## Procedure

### 1. Find dirty pages
For each `pages[]` entry in the manifest with `owns_code` and `last_synced_ref`:
```bash
git diff --name-only <last_synced_ref> HEAD -- <each owns_code path>
git log --oneline <last_synced_ref>..HEAD -- <owns_code paths>
```
Also diff the i18n file and keep hunks whose key matches the page's `owns_i18n`:
```bash
git diff <last_synced_ref> HEAD -- src/main/resources/i18n/messages.properties
```
A page is **dirty** if either diff is non-empty. Build the worklist and report it
before editing.

### 2. Find undocumented keys
```bash
.venv-docs/bin/python scripts/doc-coverage.py
```
Every orphan key must get a home: add a row to the right reference page and ensure
that page's `owns_i18n` prefix in the manifest covers it. Author the missing
settings pages listed under "page(s) still to author".

### 3. Re-read the changed code/i18n
Read the full changed files (not just hunks) to understand new/changed dialogs,
settings and behavior. Note added/removed/renamed i18n keys.

### 4. Patch the affected pages
- Edit only the dirty page's body.
- Settings/menu/shortcut references are **tables**; rebuild rows from the i18n
  **label values** (English label in the left column). Keep table columns
  identical to the existing tables.
- Keep prose factual and concise; match the surrounding style.

### 5. Update the release notes
`app-docs/site/docs/en/about/release-notes.md` holds **exactly one** `## vX.Y.Z`
section — the current release. The page is translated into every guide language,
so a growing changelog there costs translation time on text nobody reads twice.

If `build.gradle.kts` version is newer than the top `## vX.Y.Z`, **move** that
outgoing section to the top of the version list in `app-docs/release-notes-archive.md`
(not built, not translated) and start a fresh section for the new version; else
append bullets under the current version. Source bullets from
`git log <ref>..HEAD` (user-facing prose). Keep the closing "Earlier releases"
admonition at the bottom of the page, and mirror the same structure in
`app-docs/site/docs/de/about/release-notes.md`.

### 6. Refresh diagrams (if a depicted flow changed)
Edit the SVG to the house style (see AGENTS.md), then:
```bash
./scripts/validate-doc-svg.sh <name>
```

### 7. Refresh screenshots (if the UI changed)
Re-capture per the screenshot catalog using the throwaway demo dataset and
computer-use (Normal design, no secrets). If capture tooling is unavailable,
**list** the stale screenshots and DO NOT delete the old PNGs.

After capturing, optimize the new PNGs in place (raw captures are ~2x larger
and ship inside the app jar):
```bash
./scripts/optimize-png.sh <new-or-changed>.png ...   # or no args for all roots
```

### 8. Sync version + validate (all must pass)
```bash
python3 scripts/sync-version.py
./scripts/validate-doc-svg.sh
.venv-docs/bin/python scripts/doc-links.py
.venv-docs/bin/python scripts/doc-coverage.py --strict
.venv-docs/bin/python scripts/build-docs-site.py --lang en   # must build clean (offline-checked)
```

### 9. Bump last-synced refs
For each page you reconciled, set its `last_synced_ref` in the manifest to the
**merge-base with `main`** — never the branch HEAD:
```bash
git rev-parse --short "$(git merge-base HEAD origin/main 2>/dev/null \
  || git merge-base HEAD main 2>/dev/null \
  || git rev-parse HEAD)"
```
On `main` this is HEAD, so nothing changes there. On a feature branch it is the last
commit the branch was based on — a commit that still exists after the PR is merged.

**Why not `git rev-parse --short HEAD`:** a branch-local hash does not survive a
squash merge. The PR is replaced by a single new commit and the recorded hash is
gone, so `git diff <last_synced_ref>..HEAD` in step 1 aborts with "fatal: bad
revision" — which reads exactly like "nothing changed", and the page silently stops
being checked. This is not hypothetical: 19 of 46 pages had dead refs from this, some
unchecked for three weeks. `scripts/doc-links.py` now fails on unresolvable refs.

The trade-off is deliberate: the merge-base predates your own branch, so after the PR
lands the page shows dirty for the code changes you just documented. A page flagged
for a second look costs one diff; a dead ref costs every future check.

### 10. Regenerate German (if EN changed)
```bash
.venv-docs/bin/python scripts/translate_docs.py
```

Always `git diff --numstat -- app-docs/site/docs/de` afterwards: only the pages whose English changed (plus anchor-only rewrites) may move.

**Backends.** `--backend google` (default, deep_translator) can be rate-limited (`TooManyRequests`, google.com/sorry). `--backend lmstudio` uses a local OpenAI-compatible server (LM Studio on `http://localhost:1234/v1`, `--base-url` to change) with `--model` (default `openai/gpt-oss-20b`), `--concurrency N` parallel requests and `--batch-lines N` lines per request; `--concurrency 8 --batch-lines 4` was fastest for gpt-oss-20b with the model loaded as `lms load openai/gpt-oss-20b -c 16384 --parallel 8`. The prompt carries the formal "Sie" register, the placeholder rules and, per request, the German UI labels (`messages_de.properties`) and glossary terms (`i18n/glossary/de.json`) that occur in the lines. `--backend libretranslate` posts to a LibreTranslate server's `/translate` (`LIBRETRANSLATE_API_KEY` if required; untested in CI).

**Useful options.** `--changed-since <ref>` translates only pages whose English changed since `ref`; `--dry-run` prints how many lines each page would send; `--memory-from-git` rebuilds the line memory from the English version the committed German page was generated from — use it on a branch that edited English over several commits without regenerating German (HEAD's English is then no longer line-aligned and every line would be re-translated), e.g. `.venv-docs/bin/python scripts/translate_docs.py --backend lmstudio --concurrency 8 --batch-lines 4 --memory-from-git --changed-since $(git merge-base HEAD origin/main)`.

**Only changed lines (default with `--changed-since`).** The script aligns the English of `<ref>` with the current English (difflib) and, while the German page on disk is still the German of `<ref>`, copies the existing German line for every unchanged English line; only new or edited lines go to the translator, deleted lines drop out, and en/de stay line-aligned. This replaces the old `de_splice.py` post-processing: lines with `](page.md#anchor)` links are no longer re-translated just because the line memory cannot reuse them. Afterwards every link anchor on every German page is repointed — fresh lines through the English→German heading map, kept lines through the old→new German heading map (a re-translated heading or a shifted duplicate counter), explicit `{ #id }` ids never change — and an anchor that still names no heading is printed and fails the run (exit 1). `--dry-run` shows `N kept` per page. `--no-only-changed-lines` restores the old behaviour (every line the memory cannot reuse); a page without a line-aligned base (new page, German already changed on the branch) is translated in full as before. The script works on the checkout of the current directory (`--repo` to override), so another worktree's copy of the script can be run against this one.

**Failures are not cached.** A line whose placeholders do not survive is retried once, then translated fragment by fragment; a line nothing can translate keeps its English text, is listed as FAILED, the run exits 1, and the page is not marked done — re-run the same command to retry only those lines. A meta reply instead of a translation ("Bitte geben Sie die zu übersetzende Zeile an.", "Sure, please provide the line…", a question back to the reader, or a reply that keeps none of the source's placeholders, numbers and identifiers such as `korTTY` or `settings.json`) counts as such a failure — it is printed as `! meta reply instead of a translation`, never written into `docs/de`, and the line is retried on the next run (`is_meta_reply` in `scripts/translate_docs.py`). Recurring wrong wording belongs in `src/main/resources/i18n/glossary/de.json` (shared with the runtime translator; longer terms first), not in hand edits of `docs/de`.

**Choosing a model.** `scripts/translate_benchmark.py` runs a fixed sample of guide lines (`scripts/translate_benchmark_samples.json`, ~110 lines, references = the committed German) through the same pipeline and reports load/warm-up time, wall time, lines/min, latency (mean/median/p95), tokens/s, first-pass placeholder survival, UI-term adherence, chrF++ against the reference, failures, and the extrapolated time for (a) this branch's worklist (same memory logic as `--dry-run --memory-from-git`) and (b) a full re-translation. It always ends with `benchmark took X s`. Reports land in `build/translate-benchmark/<timestamp>-<model>.{md,json}`. **It is short by default**: with no options it runs `--quick` (24 fixed lines spread evenly over the line kinds, concurrency 8, batch 4, request timeout 120 s; about two minutes for a mid-size model). The full 110-line run needs an explicit `--full` (or `--limit N`).

```bash
.venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio --model qwen/qwen3-4b-2507           # quick
.venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio --model openai/gpt-oss-20b \
    --max-seconds 120 --request-timeout 60 --reasoning-effort low                                           # time-boxed
.venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio --model openai/gpt-oss-20b \
    --sweep concurrency=4,8,16 batch=2,4,8 --sweep-lines 24 --config-timeout 90                            # compare settings
.venv-docs/bin/python scripts/translate_benchmark.py --backend lmstudio --full \
    --model openai/gpt-oss-20b --model qwen/qwen3-4b-2507 --concurrency 8 --batch-lines 4                  # slow, explicit
```

`--max-seconds N` is a global budget: when it is hit the run stops cleanly (no further requests, the request in flight is cut), the report is flagged **PARTIAL**, only finished lines are scored, the extrapolation uses the lines/min measured so far (a lower bound), and the exit code stays 0. `--sweep` runs the cartesian product of the given `concurrency=`/`batch=` values, each configuration limited to `--sweep-lines` lines and `--config-timeout` seconds. `--reasoning-effort low|medium|high` is passed to reasoning (harmony/gpt-oss) models; `--request-timeout S` caps every request. A model that is not loaded is loaded with `lms load` (timed; `--context-length`, `--unload-after`, `--no-load`). chrF++ measures closeness to the current, machine-translated German, so a better translation of a weak reference can score lower — read the lowest-scoring lines in the report. Regenerate the sample file only deliberately (`--make-fixture`), or results stop being comparable.

## Output
Summarize: dirty pages touched, new/removed keys, diagrams/screenshots refreshed,
new release-notes bullets, and the new `last_synced_ref`.
