---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.groovy
kortty-builtin-version: 1
kortty-builtin-topics: [groovy]
name: "Groovy"
description: "Conventions the assistant applies when writing Groovy code: comment style, robustness, pitfalls, and secure patterns."
tags: [groovy, gradle]
enabled: true
target: BOTH
---
# Groovy Best Practices

When generating or reviewing Groovy source, Gradle build scripts, or Jenkins pipeline code, apply the rules below.

## Groovy Comments

- Comment to explain why — intent, trade-offs, and non-obvious constraints — never to restate what the code does; dynamic code especially needs intent made explicit.
- Write groovydoc on every public class and method: one summary sentence plus `@param`, `@return`, and `@throws` tags.
- Document contracts and invariants: expected argument types on dynamic signatures, nullability, units, and valid ranges — with dynamic dispatch the compiler will not tell callers.
- Tag workarounds as `// TODO(name): reason` so they remain searchable and attributable.
- In Gradle scripts, comment why a configuration deviates from convention, not what the DSL block obviously configures.

## Robust Groovy Code

- Annotate non-DSL classes with `@CompileStatic` (or at minimum `@TypeChecked`); dynamic dispatch hides typos and type errors until runtime.
- Declare explicit types on public method signatures and return values; reserve `def` for short-lived locals.
- Validate inputs at public boundaries and fail fast with `IllegalArgumentException` messages naming the bad value; use `Objects.requireNonNull` for mandatory parameters.
- Catch specific exception types and never swallow one — log with context or rethrow with the original as the cause.
- Handle expected nulls with `?.` and the Elvis operator `?:`; wrap every closeable resource in `withCloseable` or try-with-resources.
- On unrecoverable errors release resources and exit nonzero (scripts) or propagate the exception (libraries) rather than continuing in a corrupt state.

## Avoid in Groovy

- Never use a `GString` as a map key or set element expecting it to match the equivalent `String`; their `hashCode` values differ and `String#equals(GString)` is false — call `toString()` at the boundary.
- Never rely on dynamic typing in library or application code paths; add `@CompileStatic` and explicit types.
- Never put nontrivial logic in top-level Gradle script code; move it into plugins, task classes, or `buildSrc` so it is testable and cacheable.
- Never mutate shared script or global state from closures; pass parameters and return values explicitly.
- Never use `def` on public signatures; spell out the type so callers and tools see the contract.
- Never leave `@Grab` coordinates floating; pin exact dependency versions and prefer a real build tool for anything beyond a one-off script.

## Groovy Security

- Never pass external input to `Eval.me`, `GroovyShell.evaluate`, or `GroovyScriptEngine` — these execute arbitrary code; parse data with a proper parser (for example `JsonSlurper`) and validate it instead.
- Build SQL only with parameterized `groovy.sql.Sql` calls (`sql.rows('... where id = ?', [id])`); never interpolate user input into GString queries.
- Execute external commands with argument lists (`['cmd', arg].execute()`), never interpolated shell strings.
- Keep secrets out of scripts, logs, and committed Gradle properties; read them from environment variables or a credentials provider.
- Pin and scan dependencies for known CVEs (OWASP Dependency-Check, Dependabot); treat `@Grab` artifacts from unverified repositories as untrusted code.
- Never deserialize untrusted data with Java serialization; use JSON with validation.
