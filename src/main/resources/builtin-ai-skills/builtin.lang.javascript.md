---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.javascript
kortty-builtin-version: 1
kortty-builtin-topics: [javascript, js]
name: "JavaScript"
description: "Conventions the assistant applies when writing JavaScript code: comment style, robustness, pitfalls, and secure patterns."
tags: [javascript, node.js, nodejs, npm, eslint]
enabled: true
target: BOTH
---
# JavaScript Best Practices

When generating or reviewing JavaScript code or configuration, apply the rules below.

## JavaScript Comments

- Comment the why — intent, trade-offs, workarounds, issue references — never restate what the code visibly does.
- Document every exported function, class, and module with JSDoc: `@param`, `@returns`, `@throws`, plus type annotations when the project has no TypeScript.
- Spell out contracts and invariants: nullable parameters, whether inputs are mutated, units, accepted ranges, and ordering or reentrancy assumptions.
- Flag deliberate oddities inline (`// intentional:`) — empty catch blocks, API-mandated loose checks, event-loop timing tricks — so they survive review.
- Keep TODO comments actionable with an owner or ticket reference; delete them when resolved.

## Robust JavaScript Code

- Use async/await wrapped in try/catch; handle or return every promise — never leave a floating promise. Where await is impossible, attach an explicit `.catch`.
- Validate all input at boundaries (HTTP handlers, CLI arguments, file and message parsers): check type, shape, and range before use; reject early with a clear error.
- Throw `Error` or subclasses with actionable messages, never strings; preserve the original error via the `cause` option when rethrowing.
- Fail safely: exit non-zero from Node CLIs on failure; in servers convert errors to controlled responses and log them; never continue with partially initialized state.
- Guard `null`/`undefined` with optional chaining `?.` and nullish coalescing `??` instead of truthiness checks that mistreat `0` and `""`.
- Use error-first callbacks only inside legacy code; wrap them with `util.promisify` at the boundary and stay in promises elsewhere.
- Lint with eslint; fix warnings instead of disabling rules.

## Avoid in JavaScript

- Never declare with `var`; use `const` by default and `let` only when reassignment is required.
- Never compare with `==`/`!=`; use `===`/`!==` so coercion cannot change semantics.
- Never swallow errors in an empty catch block; log, rethrow, or map to a typed failure.
- Never mutate function parameters or shared module state; return new objects via spread or `structuredClone`.
- Never call `parseInt` without a radix; pass `10` or use `Number()`.
- Never use blocking `*Sync` fs or crypto calls on a server request path; use the async APIs.

## JavaScript Security

- Never use `eval`, `new Function`, or string arguments to `setTimeout`/`setInterval`; find a data-driven alternative.
- Never assign untrusted data to `innerHTML`, `outerHTML`, or `document.write`; use `textContent` or sanitize with DOMPurify first.
- Prevent prototype pollution: reject `__proto__`, `constructor`, and `prototype` keys in deep merges; use `Object.create(null)` or `Map` for untrusted keys.
- In Node, never build shell commands by concatenating user input into `exec`; use `execFile` or `spawn` with an arguments array and `shell: false`.
- Keep secrets out of source: read them from the environment or a secret manager; commit a lockfile and run `npm audit` in CI.
- Encode dynamic URL parts with `encodeURIComponent` and validate redirect targets against an allowlist.
