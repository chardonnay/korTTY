---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.kotlin
kortty-builtin-version: 1
kortty-builtin-topics: [kotlin]
name: "Kotlin"
description: "Conventions the assistant applies when writing Kotlin code: comment style, robustness, pitfalls, and secure patterns."
tags: [kotlin, .kt, ktlint]
enabled: true
target: BOTH
---
# Kotlin Best Practices

When generating or reviewing Kotlin source, Gradle Kotlin DSL, or coroutine-based code, apply the rules below.

## Kotlin Comments

- Comment to explain why — intent, trade-offs, and non-obvious constraints — never to narrate what idiomatic Kotlin already makes obvious.
- Write KDoc on every public declaration: one summary sentence, then `@param`, `@return`, and `@throws` where they add information.
- Document contracts and invariants in KDoc: nullability guarantees beyond the type system, thread-safety, cancellation behavior of suspend functions, units, and valid ranges.
- Tag workarounds as `// TODO(name): reason` so they remain searchable and attributable.
- When a suspend function has side effects on cancellation or a class deviates from value semantics, state that explicitly in its KDoc.

## Robust Kotlin Code

- Lean on null-safety: model absence in the type (`T?`), handle it with `?.` and `?:`, and enforce preconditions with `require`/`check`/`checkNotNull` carrying a descriptive message.
- Validate inputs at public boundaries with `require(...) { "..." }` so failures name the offending argument and value.
- Handle errors with specific exception types or a sealed result hierarchy; never swallow — log with context or rethrow with the cause attached, and let unrecoverable failures propagate rather than continuing in a corrupt state.
- Use structured concurrency: launch coroutines inside a scoped `CoroutineScope` or `coroutineScope` block, and let `CancellationException` propagate — never catch and drop it.
- Wrap every `Closeable` in `use { }` so resources are released on all paths.
- Prefer `val` over `var`, data classes for value carriers, and sealed classes/interfaces for closed hierarchies with exhaustive `when`; keep code ktlint- and detekt-clean so real warnings stay visible.

## Avoid in Kotlin

- Never use `!!` in production code; restructure with `?.`, `?:`, early returns, or `checkNotNull(x) { "why" }`.
- Never launch coroutines in `GlobalScope`; use an injected scope tied to a lifecycle, or `coroutineScope`/`supervisorScope`.
- Never catch broad `Exception` inside a coroutine without rethrowing `CancellationException` first.
- Never expose mutable state (`MutableList`, public `var`) from an API; return read-only views or copies.
- Never write Java-style getter/setter pairs or static utility classes; use properties, top-level functions, and extension functions.
- Never call blocking APIs directly inside suspend functions; wrap them in `withContext(Dispatchers.IO)`.

## Kotlin Security

- Build SQL only with parameterized queries (prepared statements, Exposed/Room bind parameters); never interpolate user input into query strings — string templates make this mistake easy.
- Keep secrets out of source, logs, and data-class `toString()` output; mask sensitive fields and load secrets from environment variables or a secrets manager.
- Never deserialize untrusted data with Java serialization; use kotlinx.serialization or another schema-validating format.
- Pin dependency versions (Gradle version catalogs) and scan for known CVEs (OWASP Dependency-Check, Dependabot) before shipping.
- Run external processes with `ProcessBuilder` argument lists, never interpolated shell strings; normalize and validate file paths against traversal.
- Use `SecureRandom` for tokens and salts — never `kotlin.random.Random` for anything security-relevant.
