---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.dart
kortty-builtin-version: 1
kortty-builtin-topics: [dart, flutter]
name: "Dart (Flutter)"
description: "Conventions the assistant applies when writing Dart code: comment style, robustness, pitfalls, and secure patterns."
tags: [dart, flutter, pubspec]
enabled: true
target: BOTH
---
# Dart Best Practices

When generating or reviewing Dart or Flutter code and configuration, apply the rules below.

## Dart Comments

- Comment the why — intent, trade-offs, workarounds — never restate what the code visibly does.
- Write `///` dartdoc comments on every public API member: a standalone first sentence, identifiers referenced in square brackets like `[Widget]`.
- Document contracts and invariants: nullability expectations beyond the type, units, whether callbacks may fire synchronously, single- versus broadcast-subscription streams, and who owns disposal.
- Justify every deliberate exception inline — each `!` and each `// ignore:` needs a reason — so lints stay meaningful.

## Robust Dart Code

- Keep null safety sound: no `!` without a comment proving non-null; prefer `??`, pattern matching, and promoted local checks.
- Await every Future inside try/catch; wrap intentional fire-and-forget in `unawaited()` from `dart:async` with a comment — an unhandled async error must never be silent.
- Declare `final` by default and `const` wherever possible; mutate only with explicit reason.
- Use sealed classes and enums with exhaustive `switch` expressions so a new variant breaks compilation, not production.
- Validate input at boundaries: decode JSON through typed `fromJson` factories that throw `FormatException` on bad shape instead of casting blindly.
- Fail safely: catch specific exception types, never a bare `catch` that swallows; rethrow with context; set a non-zero exit code in command-line apps.
- Flutter: use `const` constructors wherever possible; dispose controllers, focus nodes, and stream subscriptions in `dispose()`; keep heavy work out of `build()` and split large widgets so rebuilds stay local.
- Enable the effective-dart lint sets (flutter_lints or lints) in `analysis_options.yaml` and keep the analyzer clean.

## Avoid in Dart

- Never use a BuildContext across an async gap; re-check `mounted` after every await or capture needed values before awaiting.
- Never call `setState` after `dispose` or during `build`; guard with `mounted` and schedule with post-frame callbacks when needed.
- Never carry `dynamic` past the parse boundary; map decoded JSON to typed models immediately.
- Never run long synchronous work on the UI isolate; use `compute()` or `Isolate.run`.
- Never catch `Error` subtypes such as `StateError` to keep running; errors signal bugs — fix the cause.
- Never build reorderable stateful list children without stable `Key`s; provide them explicitly.

## Dart Security

- Keep secrets out of source, `String` constants, and bundled assets — everything shipped in the app is readable; use platform secure storage (Keychain/Keystore via a secure-storage plugin) or fetch from a backend.
- Treat platform-channel messages, deep links, and network responses as untrusted: type-check and validate every payload before use.
- Use HTTPS only; never ship a `badCertificateCallback` that returns true; consider certificate pinning for sensitive traffic.
- Sanitize anything rendered into a WebView and leave JavaScript disabled unless required.
- Normalize file paths derived from user input and reject `..` traversal before touching the filesystem.
- Pin dependencies through the committed `pubspec.lock` for applications and review new packages and their transitive dependencies before adopting them.
