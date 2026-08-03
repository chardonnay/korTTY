# AGENTS.md — korTTY contributor & AI agent guide

korTTY is a cross-platform JavaFX SSH client. This file is loaded into every AI
coding session; keep it short and authoritative.

## Versioning

The version lives in **one** place: `build.gradle.kts:12` (`version = "X.Y.Z"`).
Never hand-edit version strings anywhere else. To bump or reconcile, run:

```bash
python3 scripts/sync-version.py          # propagate to all mirrors
python3 scripts/sync-version.py --check  # CI gate: fail on drift
```

It syncs `KorTTYApplication.APP_VERSION`, the README badge, and the release-notes
heading. The docs site reads the version from the `KORTTY_VERSION` env var at
build time — no version literals appear in any Markdown page.

## Documentation system

The user-facing guide is a **MkDocs Material** site under `app-docs/site/`,
authored in **English Markdown** (`docs/en/**`, the source of truth). German
(`docs/de/**`) is **generated** — never hand-edit it.

| Path | Purpose |
| --- | --- |
| `app-docs/site/docs/en/**` | Authored Markdown (one page per subsystem) |
| `app-docs/site/mkdocs*.yml` | Shared + per-language build configs |
| `app-docs/diagrams/*.svg` | Canonical hand-authored SVG diagrams |
| `app-docs/screenshots/<area>/*.png` | Live UI screenshots |
| `app-docs/doc-manifest.yaml` | **The contract**: page ↔ code ↔ i18n ↔ visuals |
| `scripts/build-docs-site.py` | Builds the offline bilingual site into `build/guide` |
| `scripts/sync-version.py` · `doc-coverage.py` · `doc-links.py` | Validators |
| `README.adoc` | Stays AsciiDoc — the GitHub repo landing page |

The same built site is **bundled into the app** (`/guide/**`, opened via
**Help → Manual**, `GuideViewer`) and **published to GitHub Pages**.

### Build & preview

```bash
./gradlew setupDocsVenv            # once: create .venv-docs + install MkDocs
.venv-docs/bin/python scripts/build-docs-site.py --lang en   # build (offline-checked)
./gradlew buildDocsSite            # the same, via Gradle (bundled into the app)
```

### House style

- **Markdown**, Material flavor. Use admonitions (`!!! note`), tables for
  references, `++ctrl+c++` for keys, fenced ` ```bash ` for shell. No raw HTML
  except the hero on `index.md`.
- **Don't hard-wrap prose onto multiple physical lines.** `translate_docs.py`
  translates line-by-line; a bold span or sentence split across a line break
  garbles German word order (and an indented list-item/admonition-body line
  loses its content association if the wrap lands mid-paragraph). Write each
  paragraph/bullet as one long line, matching `features/terminal.md`.
- **No version literals** in prose — the footer shows it from `KORTTY_VERSION`.
- **SVG diagrams** must match the house style: `viewBox="0 0 1280 720"`,
  `role="img"` + `<title>`/`<desc>`, a `<defs>` arrow marker + a `<style>` block
  of CSS classes; palette `.bg #07111d`, `.box` stroke `#38bdf8`, `#67e8f9`
  connectors, category-colored strokes. Validate with
  `./scripts/validate-doc-svg.sh`. Reference as `![alt](../assets/diagrams/NAME.svg)`.
- **Screenshots** live in `app-docs/screenshots/<area>/<name>.png`, captured from
  the app in the **Normal** design with a throwaway demo dataset (no real hosts,
  keys, or passwords). Register each in `doc-manifest.yaml`.
- Every user-facing `settings.*` / `menu.*` key is documented on exactly one page,
  declared via that page's `owns_i18n` prefix in `doc-manifest.yaml`.

## Keeping docs in sync — required after user-facing changes

Any change that adds/alters a user-facing feature, setting, menu item, dialog or
shortcut (code or `messages_*.properties`) **must** be followed by reconciling the
docs. Run the maintenance skill:

```
/update-docs
```

(see `.claude/skills/update-docs/SKILL.md`). It diffs each manifest page's
`owns_code` since its `last_synced_ref`, patches the affected pages and tables
from the i18n labels, refreshes diagrams/screenshots, and re-runs the validators.

## CI gates (`.github/workflows/docs-validate.yml`)

```bash
./scripts/validate-doc-svg.sh                          # SVG validity
python3 scripts/sync-version.py --check                # version drift
.venv-docs/bin/python scripts/doc-links.py             # manifest + asset integrity
.venv-docs/bin/python scripts/doc-coverage.py --strict # every settings/menu key documented
```

`.coderabbit.yaml` excludes `app-docs/**` and `README.adoc` from automated review,
so these checks are the docs' only automated gate — keep `scripts/doc-*.py`
outside `app-docs/` so they stay reviewed as code.

## Building & running the app

```bash
./gradlew run            # launch (first run builds Monaco + the guide site)
./gradlew compileJava    # quick compile check
./gradlew test           # unit tests
```

Dependencies and bundled artifacts (Monaco, mosh4j, BouncyCastle, Node) are
version-pinned with SHA-256 in `build.gradle.kts` — when changing a version,
update its pin too.

For llama.cpp the pin lives in `gradle/llama-cpp-pins.properties`, not in
`build.gradle.kts` — the `llama-runtime` workflow keys its build matrix off that file so
an unrelated build change does not rebuild all ten runtime packages. It holds the active
`tag` / `commit` / `sourceSha256` plus a `pin.<tag> = <commit>:<sha256>` row for every
tag korTTY has shipped, and the pin is enforced rather than trusted: `verifyLlamaCppPin`
fails when the active tag has no row or the row disagrees. **Bumping the tag means
adding a row.** The `llama-runtime` workflow does this automatically; a bump that only
rewrites the three active values fails its own PR. Keep those three as plain
`key = value` lines — that workflow rewrites them by regex. Changing korTTY's own llama
build logic in `build.gradle.kts` still gets checked, but as a single-leg smoke
(linux/x86_64/CPU) rather than all ten packages; a manual `workflow_dispatch` run
(`action=build`) forces the full matrix.
