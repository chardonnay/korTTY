---
kortty-ai-skill: 1
kortty-builtin-id: builtin.markup.html
kortty-builtin-version: 1
kortty-builtin-topics: [html]
name: "HTML"
description: "Conventions the assistant applies when writing HTML markup: comment style, robustness, pitfalls, and secure patterns."
tags: [html, html5, css, .html]
enabled: true
target: BOTH
---
# HTML Best Practices

When generating or reviewing HTML markup, apply the rules below.

## HTML Comments

- Comment to explain why — a browser workaround, an accessibility decision, a template constraint — never to restate what a tag obviously does.
- Use `<!-- section -->` landmarks sparingly in long documents to mark major regions; keep them short and current.
- Never ship commented-out markup blocks in production pages; delete dead markup — version control remembers it.
- Never put internal notes, credentials, environment details, or TODOs with sensitive context into comments — every visitor receives them in the page source.
- Document non-obvious attribute choices (`aria-*` roles, `data-*` contracts consumed by scripts) where they are declared.

## Robust HTML Markup

- Start every document with `<!doctype html>`, a `lang` attribute on `<html>`, `<meta charset="utf-8">` and a viewport meta tag.
- Prefer semantic elements (`header`, `nav`, `main`, `article`, `section`, `footer`, `button`) over generic `div`/`span` — they carry meaning for assistive technology and search engines.
- Give every `img` an `alt` attribute (empty `alt=""` for purely decorative images) and tie every form control to a `label` (`for`/`id` or wrapping).
- Keep a logical heading hierarchy (`h1` → `h2` → `h3`) without skipping levels; one `h1` per page.
- Use the correct input `type`, `name` and `autocomplete` attributes; treat `required`/`pattern` as first-line UX validation only — the server must revalidate everything.
- Keep markup valid — run it through the W3C validator or an HTML linter; invalid nesting parses differently across browsers.

## Avoid in HTML

- Never use inline event handlers (`onclick="..."`) or `style` attributes; keep behavior in scripts and presentation in CSS files.
- Never use presentational or obsolete tags (`font`, `center`, `marquee`, `big`); use `strong`/`em` instead of `b`/`i` for emphasis.
- Never build page layout with tables; tables are for tabular data with proper `th`/`caption`.
- Never use `target="_blank"` without `rel="noopener noreferrer"` — the opened page otherwise gains scripting access to the opener.
- Never reuse an `id` in one document or pile up div-soup where a semantic element exists.
- Never autoplay audio or video with sound; provide controls instead.

## HTML Security

- Escape every untrusted value interpolated into markup (`<`, `>`, `&`, quotes) — unescaped output is the classic XSS vector; escape for the exact context (element body, attribute, URL).
- Render user-supplied HTML only through an allowlist sanitizer; never "clean" HTML with regular expressions.
- Add Subresource Integrity (`integrity` + `crossorigin`) to third-party scripts and stylesheets so a compromised CDN cannot inject code.
- Design pages to work under a strict Content-Security-Policy: no inline scripts, no `javascript:` URLs.
- Never store sensitive data in hidden fields, comments, or `data-*` attributes — the page source is public to its viewer.
- Send state-changing forms via POST and include the application's CSRF token; never trigger mutations from plain links.
