---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.swift
kortty-builtin-version: 1
kortty-builtin-topics: [swift]
name: "Swift"
description: "Conventions the assistant applies when writing Swift code: comment style, robustness, pitfalls, and secure patterns."
tags: [swift, swiftui, xcode]
enabled: true
target: BOTH
---
# Swift Best Practices

When generating or reviewing Swift code or configuration, apply the rules below.

## Swift Comments

- Comment the why — intent, trade-offs, workarounds — never what the code visibly does.
- Write `///` markup comments on every public declaration: a one-line summary, then `- Parameter`, `- Returns`, and `- Throws` sections.
- Document contracts and invariants the types cannot express: units, thread or actor affinity, escaping-closure lifetimes, and preconditions enforced with `precondition`.
- Justify every `!`, `try!`, and `unowned` with a comment stating why the failure case is impossible.

## Robust Swift Code

- Unwrap optionals with `guard let`/`if let`; never force-unwrap in production paths; reserve `precondition`/`assert` for programmer-error invariants.
- Model data as value types (`struct`, `enum`) by default; use `class` only for identity, shared mutable state, or framework requirements.
- Throw meaningful errors (an `enum` conforming to `Error` with context) and handle them with `do`/`catch`; use `defer` for cleanup that must run on every exit path.
- Break retain cycles: capture `self` weakly in escaping closures; use `unowned` only when the captured reference provably outlives the closure.
- Isolate UI state with `@MainActor` and protect shared mutable state with `actor`s instead of manual locks; keep `Sendable` conformances honest.
- Decode external data with `Codable` and handle `DecodingError` gracefully — surface or fall back; never `try!` a decode.
- Validate input at boundaries (user input, URLs, file data); fail safely: return early, surface an error state, keep the app usable.
- Switch exhaustively over your own enums without `default` so new cases force handling.

## Avoid in Swift

- Never force-unwrap (`!`) or `try!` on data you did not just construct; use `guard let`, a throwing path, or handled `try?`.
- Never use `unowned` where the reference can outlive the referent; prefer `weak` unless the lifetime relationship is proven.
- Never block the main thread or main actor; move heavy or blocking work to a background task and hop back for UI updates.
- Never mutate shared state from concurrent tasks without actor isolation; data races are undefined behavior.
- Never scatter stringly-typed identifiers as repeated literals; define constants or enums.
- Never discard a throwing call's error with `try?` when failures need distinct handling; catch and branch on the error.

## Swift Security

- Store secrets and tokens in the Keychain — never in `UserDefaults`, plists, or source constants.
- Honor App Transport Security: HTTPS everywhere; add no ATS exceptions unless narrowly scoped and justified in a comment.
- Validate all external input — deep links, universal links, pasteboard contents, files, network payloads — before acting on it.
- Avoid unsafe APIs (`unsafeBitCast`, `Unmanaged`, raw pointers) unless interop demands them; wrap and document the unsafe surface.
- Use `SecRandomCopyBytes` or `SystemRandomNumberGenerator` for security-relevant randomness, never a seeded or custom generator.
- Never concatenate user input into SQL or predicate strings; bind parameters (SQLite bindings, `NSPredicate` argument arrays).
- Never log sensitive values; use os_log with private privacy specifiers for anything user-identifying.
