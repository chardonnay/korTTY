---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.typescript
kortty-builtin-version: 1
kortty-builtin-topics: [typescript]
name: "TypeScript"
description: "Conventions the assistant applies when writing TypeScript code: comment style, robustness, pitfalls, and secure patterns."
tags: [typescript, tsconfig, tsc]
enabled: true
target: BOTH
---
# TypeScript Best Practices

When generating or reviewing TypeScript code or configuration, apply the rules below.

## TypeScript Comments

- Comment the why — intent, trade-offs, workarounds — never what the types and code already state.
- Use TSDoc on every exported symbol: a one-sentence summary, then `@param`, `@returns`, `@throws`, and `@remarks` for behavior the types cannot express.
- Document contracts and invariants the type system cannot encode: units, sortedness, mutation and ownership, side effects, allowed call order.
- Document every type predicate and assertion function with the exact runtime check it performs; a wrong guard silently breaks soundness.
- Justify every `@ts-expect-error` (never bare `@ts-ignore`) with a reason and a removal condition.

## Robust TypeScript Code

- Enable `strict: true` (including `strictNullChecks`) plus `noUncheckedIndexedAccess`; treat compiler errors as design feedback.
- Type external input as `unknown` and narrow with guards; validate data crossing runtime boundaries (HTTP, files, env, IPC) with a schema library in the zod/io-ts style — static types vanish at runtime.
- Model state with discriminated unions (a `kind` or `status` tag) instead of optional-field blobs; make illegal states unrepresentable.
- Make every switch over a union exhaustive with a `never`-typed default check so new variants fail compilation, not production.
- Prefer `readonly` properties, `Readonly<T>`, and `as const` for data that must not change.
- Handle every promise with async/await in try/catch; enable `@typescript-eslint/no-floating-promises` and the other type-aware ESLint rules.
- Fail safely: throw typed errors or return result unions; exit non-zero in CLIs; never proceed past failed validation.

## Avoid in TypeScript

- Never use `any`; use `unknown` plus narrowing, or a generic parameter.
- Never silence type errors with `as` casts; fix the type or write a validated guard, and comment the rare provably-safe cast.
- Never use the non-null assertion `!` to appease the compiler; narrow with a real check or restructure so the value cannot be null.
- Never hand-duplicate types from other declarations; derive them with `typeof`, `keyof`, `ReturnType`, `Pick`, or `Omit`.
- Never mix `null` and `undefined` semantics ad hoc; pick one absent-value convention per API and encode it in the types.
- Never widen a function's parameter types to dodge an error; adjust the caller or model the union honestly.

## TypeScript Security

- Runtime-validate all external data before trusting it — a compile-time interface is not sanitization; parse with a schema and reject on failure.
- Never use `eval`, `new Function`, or string timer arguments; the JavaScript injection canon applies unchanged after compilation.
- Never pass untrusted data to HTML sinks such as `innerHTML`; use `textContent` or a sanitizer, and use branded types so APIs accept only sanitized strings.
- Guard merges of untrusted objects against prototype pollution; reject `__proto__`, `constructor`, and `prototype` keys.
- Keep secrets out of source; load configuration from the environment through a validated schema; commit a lockfile and run `npm audit` in CI.
- On Node, never concatenate user input into `exec`; use `execFile` or `spawn` with an arguments array.
