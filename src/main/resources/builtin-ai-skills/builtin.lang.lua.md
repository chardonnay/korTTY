---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.lua
kortty-builtin-version: 1
kortty-builtin-topics: [lua]
name: "Lua (Lua 5.x)"
description: "Conventions the assistant applies when writing Lua code: comment style, robustness, pitfalls, and secure patterns."
tags: [lua code, luarocks, luajit]
enabled: true
target: BOTH
---
# Lua Best Practices

When generating or reviewing Lua (Lua 5.x) code or configuration, apply the rules below.

## Lua Comments

- Comment to explain why — a workaround, an embedding-host constraint, a non-obvious idiom — never to restate what the code already says.
- Document public functions with LDoc: a `---` summary line plus `@param` and `@return` tags that include types and `nil` cases.
- Document contracts and invariants: whether a function returns `nil, err` or raises via `error`, which table fields are required, and who owns passed-in tables.
- State interpreter-version assumptions (5.1 versus 5.4 semantics such as integer division or `goto`) only where the code actually depends on them.
- Mark deferred work as `-- TODO(owner): reason` so it stays findable.

## Robust Lua Code

- Declare everything `local` by default; enforce it with luacheck or a strict-mode metatable on the globals table so accidental globals fail loudly.
- Wrap failure-prone calls in `pcall`/`xpcall`, propagate meaningful error objects, and raise with `error(msg, 2)` so blame lands on the caller.
- Follow the `nil, err` convention: check both return values of `io.open` and similar calls before using the handle.
- Guard table access chains against `nil` intermediates (`cfg and cfg.net and cfg.net.host`) instead of indexing blindly.
- Validate arguments at function entry with `type()` checks and clear error messages naming the parameter and the received value.
- Exit CLI scripts with `os.exit(1)` on fatal failure after reporting the reason to `io.stderr`, so pipelines and schedulers detect the failure.

## Avoid in Lua

- Never trust the `#` operator on tables with holes; keep arrays dense or track the length explicitly.
- Never carry 0-based indexing habits over; Lua tables and strings are 1-based, so derive every index and loop bound accordingly.
- Never concatenate strings in a loop with `..`; collect parts in a table and use `table.concat` — each `..` allocates a fresh string, so loops go quadratic.
- Never shadow standard library names (`table`, `string`, `os`) with locals; pick distinct names so the libraries stay reachable.
- Never leave missing-key behavior implicit; decide explicitly between a default value, a `nil` check, or raising an error.
- Never ship code with luacheck warnings; fix them or annotate a justified exception.

## Lua Security

- Never pass external data to `load`, `loadstring`, or `dofile`; parse configuration with a data-only decoder instead of executing it.
- Sandbox untrusted code with a restricted environment: a minimal `_ENV` (or `setfenv` on 5.1) exposing only vetted functions.
- Never interpolate untrusted input into `os.execute` or `io.popen`; avoid shelling out, or validate against a strict allowlist first.
- Keep secrets out of source; read them from the environment or the embedding host's secure store.
- Pin dependencies in a LuaRocks rockspec or manifest so builds stay reproducible and auditable.
