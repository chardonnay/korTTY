---
kortty-ai-skill: 1
kortty-builtin-id: builtin.markup.json
kortty-builtin-version: 1
kortty-builtin-topics: [json]
name: "JSON (.json)"
description: "Conventions the assistant applies when writing JSON-Format documents: comment style, robustness, pitfalls, and secure patterns."
tags: [.json, jsonlint, json5]
enabled: true
target: BOTH
---
# JSON-Format Best Practices

When generating or reviewing JSON documents, apply the rules below.

## JSON-Format Comments

- JSON has no comment syntax: never emit `//` or `/* */` — strict parsers reject the document.
- Where an explanation is unavoidable inside the data, use a tolerated convention key such as `"$comment"` only when the consuming schema explicitly allows it.
- Use JSONC or JSON5 only when the consumer is documented to support it (e.g. specific editor configs); plain `.json` files stay strict.
- Put the real documentation into the JSON Schema (`description` fields) or the accompanying reference docs, not into the data.

## Robust JSON-Format Documents

- Use double quotes for every key and string; single quotes and unquoted keys are invalid.
- Emit no trailing commas and encode files as UTF-8 without a byte-order mark.
- Validate documents against a JSON Schema where one exists, and keep key casing consistent (one of camelCase or snake_case, never mixed).
- Represent integers beyond 2^53 and monetary amounts as strings; consumers parse numbers as doubles and silently lose precision.
- Represent dates and timestamps as ISO 8601 strings; raw epoch numbers hide their unit and timezone.
- Define and document the difference between `null` and an absent key; consumers must not have to guess.
- Generate documents through a serializer, never by concatenating strings — escaping and nesting must survive every input.

## Avoid in JSON-Format

- Never hand-build JSON with string concatenation or templates; one unescaped quote produces an invalid or injectable document.
- Never emit `NaN` or `Infinity`; they are invalid JSON — use `null` or a string sentinel the schema defines.
- Never rely on duplicate keys; behavior is parser-dependent and usually last-wins without warning.
- Never depend on key order; JSON objects are unordered, so any order-sensitive contract is a latent bug.
- Never embed large binary payloads inline; reference them by URL or use base64 only where the schema requires it.
- Never smuggle comments in by abusing throwaway keys in machine-consumed data files.

## JSON-Format Security

- Parse with the platform's strict parser (`JSON.parse` and equivalents); never evaluate JSON text as code.
- Guard object merges against prototype pollution: reject or strip `__proto__`, `constructor` and `prototype` keys from untrusted input.
- Enforce size and depth limits before parsing untrusted documents; deeply nested payloads are a denial-of-service vector.
- Treat every parsed value as untrusted: validate types, ranges and formats against the schema before use.
- Keep secrets out of JSON config files committed to repositories; inject them via the environment or a secret store.
- Avoid JSONP end-to-end; it is a legacy script-injection channel superseded by CORS.
