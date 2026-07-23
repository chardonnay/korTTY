---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.ruby
kortty-builtin-version: 1
kortty-builtin-topics: [ruby]
name: "Ruby"
description: "Conventions the assistant applies when writing Ruby code: comment style, robustness, pitfalls, and secure patterns."
tags: [ruby, .rb, rubygems, bundler, rails]
enabled: true
target: BOTH
---
# Ruby Best Practices

When generating or reviewing Ruby code or configuration, apply the rules below.

## Ruby Comments

- Comment to explain why — a workaround, a non-obvious design decision, a performance trade-off — never to restate what the code already says.
- Document every public class, module, and method with YARD or RDoc: a one-line summary plus `@param`, `@return`, and `@raise` tags where behavior is non-trivial.
- Document contracts and invariants: whether a method mutates its receiver or arguments, whether it can return `nil`, and any thread-safety guarantees.
- Start every file with `# frozen_string_literal: true` and treat it as part of the standard file header.
- Mark deferred work as `# TODO(owner): reason` so it stays findable.

## Robust Ruby Code

- Raise specific `StandardError` subclasses — define a per-library error class hierarchy — with actionable messages.
- Rescue narrowly by naming the exact error classes you can handle; let everything else propagate — a broad rescue hides bugs it was never meant to catch.
- Use `ensure` (or block forms such as `File.open(path) { |f| ... }`) so files, sockets, and locks are released on every exit path.
- Validate input at the boundary and fail fast with `ArgumentError` rather than propagating bad state.
- Exit CLI tools with `abort("message")` or a non-zero `exit` status on failure; send diagnostics to a logger or `$stderr`, not `puts`, so stdout stays reserved for real output.
- Keep code rubocop-clean; treat its warnings as defects, not noise.

## Avoid in Ruby

- Never `rescue Exception`; rescue `StandardError` or narrower so signals and fatal errors still propagate.
- Never silence errors with inline `rescue nil`; handle the specific failure or let it raise — it swallows the exception and returns `nil`, masking real defects.
- Never monkey-patch core classes in application code; use helper modules or refinements, which keep the change lexically scoped.
- Never use class variables (`@@var`); use class instance variables with accessors — `@@var` is shared across the entire inheritance hierarchy and mutates surprisingly.
- Never implement `method_missing` without a matching `respond_to_missing?`; prefer `define_method` where possible.
- Never mutate string literals; rely on `frozen_string_literal` and call `dup` explicitly when mutation is required.

## Ruby Security

- Never call `Marshal.load` or `YAML.load` on untrusted data; use `YAML.safe_load` with an explicit permitted-classes list, or JSON.
- Never interpolate untrusted input into backticks, `%x`, or a single-string `system` call; use `system` or `Open3.capture3` with array arguments.
- Never build SQL by string interpolation; use bound parameters (`?` placeholders or ActiveRecord hash conditions).
- Never pass user input to `eval`, `instance_eval`, `send`, or `constantize`; dispatch through an explicit allowlist.
- Escape all user-supplied content in HTML output; never mark it `html_safe`.
- Keep secrets in the environment, commit `Gemfile.lock` via Bundler, and run `bundler-audit` when dependencies change.
