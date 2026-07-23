---
kortty-ai-skill: 1
kortty-builtin-id: builtin.shell.ksh
kortty-builtin-version: 1
kortty-builtin-topics: [ksh, kornshell]
name: "KornShell (ksh)"
description: "Conventions the assistant applies when writing KornShell (ksh) code: comment style, robustness, pitfalls, and secure patterns."
tags: [ksh, ksh93, kornshell, mksh]
enabled: true
target: BOTH
---
# ksh Best Practices

When generating or reviewing KornShell (ksh) code or configuration, apply the rules below.

## ksh Comments

- Comment the why, not the what: capture intent, workarounds, and constraints; never narrate what a line obviously does.
- Begin scripts with an explicit shebang (`#!/bin/ksh93` or `#!/usr/bin/env ksh`) and a header stating purpose, target dialect (ksh93 or mksh), and required commands.
- Document each function above its definition: purpose, parameters, `typeset` locals, globals touched, and exit-status meaning.
- Record contracts and invariants: which variables callers must set, whether a function relies on `function name` scoping, and ordering dependencies.
- Flag every ksh93-only construct (floating-point arithmetic, compound variables, `printf '%T'`) with a comment so a port to mksh does not break silently.

## Robust ksh Code

- Start scripts with `set -u` and `set -o pipefail`; `set -e` is skipped in `if`/`while` conditions and command substitutions, so append `|| exit 1` on critical steps.
- Quote every expansion — `"$var"`, `"$@"`, `"${arr[@]}"` — and use `[[ ]]` for tests: it avoids word-splitting surprises and handles pattern matching safely.
- Declare locals with `typeset` and prefer the `function name { }` form — only it gives ksh93 local scoping; `name()` functions share the caller's variables.
- Parse options with `getopts` in a `while` loop; reject unknown flags and validate every operand (existence, readability, allowed values).
- Install `trap cleanup EXIT INT TERM`, create temporary files only with `mktemp`, and check dependencies with `command -v tool >/dev/null || exit 1`.
- Use coprocesses deliberately: start with `cmd |&`, talk via `print -p` and `read -p`, and close the coprocess before waiting to avoid hangs.

## Avoid in ksh

- Never parse `ls` output; iterate with globs (`for f in ./*`) or `find ... -exec`.
- Never use `echo` for data; use `print -r --` or `printf '%s\n'`, which do not interpret backslashes or leading dashes.
- Never build commands in strings for `eval`; keep arguments in an array (`typeset -a args`) and expand `"${args[@]}"`.
- Never assume ksh93 features work under mksh or vice versa; pin the dialect in the shebang and test with that interpreter.
- Never use backticks; use `$(...)`, which nests cleanly.
- Never let filenames be parsed as options; terminate option parsing with `--` (`rm -- "$file"`).

## ksh Security

- Never put secrets in argv — visible to every user via `ps`; pass them via environment variables, `chmod 600` files, or stdin.
- Never feed untrusted input to `eval`, dot-sourcing (`.`), or arithmetic expansion `$(( ))` — ksh arithmetic evaluates subscripts and can execute code; validate against an allowlist first.
- Set `PATH` explicitly in privileged scripts and apply `umask 077` before writing sensitive files.
- Create temp files exclusively with `mktemp` and enable `set -o noclobber` where overwriting must be impossible.
- Never source startup or configuration files from directories other users can write.
- Lint with `shellcheck -s ksh` and fix or justify every finding; coverage is partial, so review ksh93-specific constructs manually.
