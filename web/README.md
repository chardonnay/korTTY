# korTTY website

The product site for korTTY. Static, dependency-free: plain HTML, one stylesheet,
two scripts, and JSON translation files. No build step, no npm, no framework.

## Run it locally

Any static server works — the page fetches JSON, so `file://` will not do:

```bash
cd web
python3 -m http.server 8000   # then open http://localhost:8000
```

## Layout

| Path | What it is |
| --- | --- |
| `index.html` | The whole page. All copy lives here as the English source text. |
| `styles.css` | Design tokens and base components (dark theme, type scale, cards, buttons). |
| `page-behavior.js` | Release-version sync, matrix rain, scroll progress, scrollspy, reveals, mockup animations. |
| `i18n/i18n.js` | Language detection, manual switching, translation application. |
| `i18n/<lang>.json` | One file per language: `{ "key": "translated HTML" }`. |
| `assets/` | Logo and icon. |
| `screenshots/` | Copies of the app screenshots the page frames. |
| `sync-screenshots.sh` | Re-copies those screenshots from `../app-docs/screenshots`. |

## Editing copy

English text lives directly in `index.html`. Every translatable element carries a
`data-i18n="key"` attribute; the same key in each `i18n/<lang>.json` holds that
language's version (HTML allowed — use single quotes for attributes inside JSON).

Changing English text: edit `index.html`, then update the same key in the seven
JSON files. A key missing from a JSON file falls back to the English source
automatically, so a partial translation never breaks the page.

## Adding a language

1. Copy an existing `i18n/<lang>.json` and translate the values.
2. Add the two-letter code to `LANGS` in `i18n/i18n.js`.
3. Add an `<option>` to `#lang-sel` in `index.html`.

Browser language is detected on load; anything unsupported falls back to English.
A manual pick is remembered in `localStorage` under `kortty-lang`.

## Release version and download links

Version labels carry `data-ver`; download links carry
`data-asset="korTTY-macOS-{v}-aarch64.dmg"` style templates. On load,
`page-behavior.js` queries `api.github.com/repos/chardonnay/korTTY/releases/latest`
and rewrites both from the newest tag — `{v}` becomes the version without its
leading `v`. The hardcoded values in the HTML are the fallback when the API is
unreachable or rate-limited, so keep them roughly current.

When an asset naming scheme changes in `.github/workflows/build-release.yml`,
update the matching `data-asset` templates in the download section.

## Notes

- The manual release-notes section (`#release`) is deliberately pinned to its
  version — its content describes that release specifically.
- Animations respect `prefers-reduced-motion`; the matrix-rain background is the
  one deliberate exception and self-disables on slow hardware.
