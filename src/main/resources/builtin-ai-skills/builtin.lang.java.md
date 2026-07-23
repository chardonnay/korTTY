---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.java
kortty-builtin-version: 1
kortty-builtin-topics: [java]
name: "Java (JVM)"
description: "Conventions the assistant applies when writing Java code: comment style, robustness, pitfalls, and secure patterns."
tags: [java code, jvm, javadoc, maven, jdk]
enabled: true
target: BOTH
---
# Java Best Practices

When generating or reviewing Java source, JVM configuration, or Maven/Gradle build files, apply the rules below.

## Java Comments

- Comment to explain why — intent, trade-offs, and non-obvious constraints — never to restate what the code already says; delete comments that merely echo the next line.
- Write Javadoc on every public type and method: one summary sentence, then `@param`, `@return`, and `@throws` for each declared exception.
- Document contracts and invariants in Javadoc: nullability, thread-safety, mutability, units, and valid ranges — callers must not have to read the implementation.
- Tag workarounds as `// TODO(name): reason` so they remain searchable and attributable.
- When a class overrides `equals`, state the equality semantics (identity vs value) and keep the `equals`/`hashCode` contract documented.

## Robust Java Code

- Catch the most specific exception type possible and never swallow one — log it with context or rethrow wrapped with the original as the cause.
- Wrap every `AutoCloseable` (streams, connections, statements) in try-with-resources; never rely on manual `close()` calls in `finally`.
- Validate inputs at public boundaries: `Objects.requireNonNull`, range checks, and `IllegalArgumentException` messages that name the offending value.
- Return `Optional` instead of null from public APIs, and empty collections instead of null collections, so callers never need defensive null checks.
- Default to immutability: `final` fields, defensive copies via `List.copyOf`/`Map.copyOf`, records for value carriers.
- Fail safely: on unrecoverable errors release resources, log the state needed for diagnosis, and exit nonzero (CLI) or propagate (library) instead of continuing with corrupt state.

## Avoid in Java

- Never use raw types; always parameterize generics (`List<String>`, not `List`).
- Never override `equals` without `hashCode`; generate both together or use a record.
- Never catch `Exception` or `Throwable` just to keep a loop alive; catch the specific failures you can actually handle.
- Never use finalizers; manage lifecycle with try-with-resources or `java.lang.ref.Cleaner` — finalization is deprecated and its timing is unpredictable.
- Never use `java.util.Date` or `Calendar` in new code; use `java.time` types.
- Never synchronize on `this`, String literals, or boxed primitives; use a private final lock object.

## Java Security

- Build SQL only with `PreparedStatement` placeholders; never concatenate user input into query strings, and bind parameters in JPQL/HQL the same way.
- Never deserialize untrusted data with `ObjectInputStream`; prefer JSON or protobuf with validation, and configure `ObjectInputFilter` allowlists where native serialization is unavoidable.
- Keep secrets out of source, logs, and `toString()` output; load them from environment variables or a secrets manager.
- Pin dependency versions and scan them for known CVEs (OWASP Dependency-Check, Dependabot) before shipping.
- Invoke external processes with `ProcessBuilder` and argument lists, never a shell string; normalize file paths (`Path.normalize` plus prefix check) to block traversal.
- Use `SecureRandom` for tokens, salts, and anything security-relevant — never `java.util.Random` or `Math.random()`.
