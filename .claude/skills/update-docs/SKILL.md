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
- `src/main/resources/i18n/messages_en.properties` — canonical labels/keys.

## Procedure

### 1. Find dirty pages
For each `pages[]` entry in the manifest with `owns_code` and `last_synced_ref`:
```bash
git diff --name-only <last_synced_ref> HEAD -- <each owns_code path>
git log --oneline <last_synced_ref>..HEAD -- <owns_code paths>
```
Also diff the i18n file and keep hunks whose key matches the page's `owns_i18n`:
```bash
git diff <last_synced_ref> HEAD -- src/main/resources/i18n/messages_en.properties
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
`app-docs/site/docs/en/about/release-notes.md`. If `build.gradle.kts` version is
newer than the top `## vX.Y.Z`, prepend a new section; else append bullets under
the current version. Source bullets from `git log <ref>..HEAD` (user-facing prose).

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
For each page you reconciled, set its `last_synced_ref` in the manifest to:
```bash
git rev-parse --short HEAD
```

### 10. Regenerate German (if EN changed)
```bash
.venv-docs/bin/python scripts/translate_docs.py
```

## Output
Summarize: dirty pages touched, new/removed keys, diagrams/screenshots refreshed,
new release-notes bullets, and the new `last_synced_ref`.
