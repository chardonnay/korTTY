---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.rust
kortty-builtin-version: 1
kortty-builtin-topics: [rust]
name: "Rust (rustlang)"
description: "Conventions the assistant applies when writing Rust code: comment style, robustness, pitfalls, and secure patterns."
tags: [rust code, rustc, cargo, clippy, crates.io]
enabled: true
target: BOTH
---
# Rust Best Practices

When generating or reviewing Rust (rustlang) code or Cargo configuration, apply the rules below.

## Rust Comments

- Comment why, not what: explain invariants, algorithmic choices, and non-obvious borrow or lifetime decisions.
- Write `///` doc comments on every public item: a one-line summary, details, and an `# Examples` section that compiles as a doctest; add `# Errors` and `# Panics` sections where applicable.
- Use `//!` module-level docs to state each module's purpose and how its pieces fit together.
- Precede every `unsafe` block with a `// SAFETY:` comment explaining the invariant that makes it sound.
- Document contracts the type system cannot express: ordering requirements, unit conventions, protocol state machines.

## Robust Rust Code

- Return `Result`/`Option` and propagate with `?`; never call `unwrap()` or `expect()` in library or production paths — `expect("descriptive message")` is acceptable in binaries during startup for unrecoverable configuration errors.
- Split error handling deliberately: `thiserror` for typed library errors, `anyhow` for application-level flow; attach context with `.context(...)` at boundaries.
- Validate external input at the edges and parse it into strong types so illegal states become unrepresentable.
- Match enum variants you own exhaustively; avoid catch-all `_` arms so newly added variants become compile errors.
- Fail safely: bubble errors to `main` returning `Result`, exit nonzero on failure, and never `panic!` for expected failure modes.
- Use `checked_*`/`saturating_*` arithmetic on untrusted numbers; release builds wrap silently on overflow.

## Avoid in Rust

- Never reach for `unsafe` when a safe alternative exists; when unavoidable, minimize its scope, isolate it behind a safe API, and justify it with a SAFETY comment.
- Never `clone()` reflexively to appease the borrow checker; restructure lifetimes or borrow instead.
- Never derive traits blindly — derive `Debug` and `Clone` deliberately, and implement `Debug` manually on types holding secrets so they are redacted.
- Never narrow untrusted integers with `as` casts; use `try_from`/`try_into` and handle the error.
- Never hold a `Mutex` guard across an `.await` point; scope the lock tightly or use an async-aware mutex.
- Never let lints accumulate; keep code clean under `cargo clippy -- -D warnings` and formatted with `rustfmt`.

## Rust Security

- Audit dependencies with `cargo audit` or `cargo deny` and keep the dependency tree minimal; every crate is attack surface.
- Keep secrets out of source, logs, and `Debug` output; load them from the environment or a secret store, wrap them in redacting newtypes, and wipe buffers with the `zeroize` crate.
- Compare secrets in constant time (the `subtle` crate), never with `==` on byte slices.
- Never build shell commands by string interpolation; use `std::process::Command` with discrete arguments, and parameterized queries for SQL.
- Treat deserialization of untrusted data as a validation boundary: cap input sizes and use strict `serde` types, with `deny_unknown_fields` where appropriate.
- Add `#![forbid(unsafe_code)]` to crates that need no unsafe, making the guarantee toolchain-enforced.
