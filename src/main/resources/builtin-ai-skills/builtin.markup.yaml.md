---
kortty-ai-skill: 1
kortty-builtin-id: builtin.markup.yaml
kortty-builtin-version: 1
kortty-builtin-topics: [yaml, yml]
name: "YAML (YML)"
description: "Conventions the assistant applies when writing YML documents: comment style, robustness, pitfalls, and secure patterns."
tags: [yml, .yaml, .yml, yamllint]
enabled: true
target: BOTH
---
# YML Best Practices

When generating or reviewing YAML documents, apply the rules below.

## YML Comments

- Comment with `#` to explain why — the reason for a value, its unit, its allowed range — not to repeat the key name.
- Document every non-obvious configuration key where it is set: what consumes it, what happens when it is absent.
- Explain every anchor (`&`) and alias (`*`) at the anchor definition; merged mappings are invisible at the point of use.
- Keep comments aligned with the values they describe and update them in the same change; a stale comment in a config file causes real outages.

## Robust YML Documents

- Indent with exactly two spaces per level and never with tabs; tabs are a syntax error in YAML.
- Quote every ambiguous scalar: `"no"`, `"yes"`, `"on"`, `"off"`, `"true"`-like strings (the Norway problem), version numbers like `"1.10"`, and values with leading zeros — unquoted they silently change type.
- Start documents with `---` and keep one concern per document; use multi-document streams only when the consumer explicitly reads them.
- Prefer block style over inline flow style for nested structures; flow style hides structure and breaks diffs.
- Keep keys unique within a mapping; duplicate keys are accepted by some parsers, dropped by others, and always a bug.
- Keep files `yamllint`-clean and validate against the consumer's schema where one exists (CI pipelines, Kubernetes, compose files).

## Avoid in YML

- Never mix indentation widths or align values with tabs; pick two spaces and enforce it with a linter.
- Never rely on implicit typing for anything that must stay a string; quote it instead of trusting the resolver.
- Never nest deeper than three or four levels; restructure or split the file — deep trees are unreadable and error-prone.
- Never overuse anchors, aliases and merge keys as a template engine; a little duplication beats an unreadable reference web.
- Never leave sexagesimal-looking values like `12:30` unquoted in legacy-parser contexts; they can parse as numbers.
- Never append to a shared file with string concatenation; load, modify and dump through a YAML library so structure stays intact.

## YML Security

- Never deserialize untrusted input with a full loader; use the safe loader of the language (`yaml.safe_load` and equivalents) — full loaders instantiate arbitrary objects.
- Reject custom tags (`!!python/...`, `!ruby/...`) in any document that crosses a trust boundary; tags are code-execution hooks.
- Cap alias expansion and document depth when parsing untrusted input; anchor bombs are the YAML variant of billion laughs.
- Reference secrets via environment variables or a secret store instead of writing plaintext credentials into config files.
- Set restrictive file permissions on any document that must carry sensitive values, and keep such files out of repositories.
- Validate structure and value ranges after parsing; a syntactically valid document is not a trustworthy one.
