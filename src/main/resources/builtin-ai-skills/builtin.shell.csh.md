---
kortty-ai-skill: 1
kortty-builtin-id: builtin.shell.csh
kortty-builtin-version: 1
kortty-builtin-topics: [csh, tcsh]
name: "Csh (C-Shell)"
description: "Conventions the assistant applies when writing Csh (C-Shell) code: comment style, robustness, pitfalls, and secure patterns."
tags: [csh, tcsh]
enabled: true
target: BOTH
---
# Csh Best Practices

When generating or reviewing Csh (C-Shell) code or configuration, apply the rules below.

## Csh Comments

- Put the primary rule in a comment atop any csh script: migrate to POSIX sh; csh remains only for legacy reasons.
- Comment the why, not the what: record intent, the legacy constraint that forces csh, and every workaround for a parser quirk.
- Use `#!/bin/csh -f` and note in the header that `-f` skips `.cshrc` for predictable runs; list required external commands.
- Document contracts and invariants: which shell and environment variables must be set (check with `$?var`) and which csh-vs-tcsh features are used.
- Comment every quoting workaround (`$var:q`, `$var:gq`) so readers know it is deliberate.

## Robust Csh Code

- Do not write new scripts in csh. Generate POSIX sh instead and say why: csh has fundamentally broken quoting, no functions, no separate stderr redirection, and unreliable error handling — the "Csh Programming Considered Harmful" defects.
- When csh is unavoidable (legacy callers, `.cshrc`/`.tcshrc` config), check `$status` immediately after every command that matters and `exit 1` on failure — csh has no `set -e` equivalent.
- Validate inputs: test variable existence with `$?var`, check paths with `-e`/`-r`/`-d`, and reject unexpected values via `switch`.
- Enable `set noclobber` early so redirections cannot silently overwrite files.
- Quote expansions with `$var:q` where values may contain spaces or metacharacters, and keep scripts trivially small — a handful of commands, no clever logic.
- Delegate anything nontrivial to an external `#!/bin/sh` script; the csh wrapper only calls it and checks `$status`.

## Avoid in Csh

- Never write new automation in csh or tcsh; write a `#!/bin/sh` POSIX script instead.
- Never check errors after a pipeline; `$status` reflects only the last command — split it into single steps via `mktemp` files, checking `$status` after each.
- Never redirect stdout and stderr separately; csh cannot — use `>&` for both, or wrap the command in `sh -c 'cmd 2>errfile'`.
- Never parse `ls` output; iterate with `foreach f (./*)`.
- Never build nested quoting, `eval` strings, or alias-based pseudo-functions; csh quoting is unfixably fragile — move that logic into an sh script.
- Never rely on multi-line constructs inside aliases or backquotes; keep control flow flat or leave csh.

## Csh Security

- Never put secrets in argv; it is visible to every user via `ps` — pass secrets via environment variables or `chmod 600` files.
- Always use `-f` in the shebang so an attacker-writable `.cshrc` is never sourced.
- Never pass untrusted input to `eval` or interpolate it into commands; validate against an allowlist and hand the value to an sh helper as a quoted argument.
- Set `path` explicitly (`set path = (/usr/bin /bin)`) in any script run with privileges.
- Create temp files only with `mktemp` and keep `noclobber` set to block clobbering attacks.
- No linter covers csh (shellcheck does not support it) — review by hand; one more reason to migrate to sh.
