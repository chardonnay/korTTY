---
kortty-ai-skill: 1
kortty-builtin-id: builtin.shell.zsh
kortty-builtin-version: 1
kortty-builtin-topics: [zsh]
name: "Zsh (Z-Shell)"
description: "Conventions the assistant applies when writing Zsh (Z-Shell) code: comment style, robustness, pitfalls, and secure patterns."
tags: [zsh, zshrc, oh-my-zsh]
enabled: true
target: BOTH
---
# Zsh Best Practices

When generating or reviewing Zsh (Z-Shell) code or configuration, apply the rules below.

## Zsh Comments

- Comment the why, not the what: record intent, workarounds, and constraints; never restate what a line visibly does.
- Start scripts with `#!/usr/bin/env zsh` and a header stating purpose, required commands, and every `setopt` the script depends on.
- Document each function above its definition: purpose, arguments, `local` variables, and exit-status meaning; in autoloadable functions, at the top of the file.
- State contracts and invariants: which option state (`ERR_EXIT`, `NULL_GLOB`, `EXTENDED_GLOB`) a function assumes and which variables must already be set.
- Mark every deliberate zsh-ism — `${=var}`, glob qualifiers, 1-based indexing tricks — with a comment so readers do not "fix" it into bash semantics.

## Robust Zsh Code

- Start scripts with `setopt ERR_EXIT NO_UNSET PIPE_FAIL`; `ERR_EXIT` does not fire inside `if`/`while` conditions, command substitutions, or `&&`/`||` chains — append `|| exit 1` on critical steps.
- Quote every expansion anyway: zsh does not word-split unquoted parameters, but quoting preserves empty values and survives copy-paste into other shells; when splitting is wanted, use `${=var}` deliberately or an array.
- Unmatched globs are errors by default; handle expected empty matches with the `(N)` qualifier or a scoped `setopt NULL_GLOB`.
- Begin functions with `emulate -L zsh` (plus needed `setopt`s) so caller options cannot change their behavior; use `emulate sh` for portable snippets.
- Validate inputs early — argument count, path existence, allowlisted values — and fail to stderr with a nonzero exit before any side effect.
- Install `trap cleanup EXIT INT TERM`, create temporary files with `mktemp`, and check dependencies with `command -v tool >/dev/null || exit 1`.

## Avoid in Zsh

- Never parse `ls` output; use glob qualifiers — `*(.)` for plain files, `**/*(/)` for directories, `*(om[1])` for the newest.
- Never rely on bash-style implicit word-splitting; split explicitly with `${=var}` or store lists in arrays.
- Never assume 0-based arrays; zsh arrays and `$argv` start at 1 — index accordingly.
- Never use `echo` for data; use `print -r --` or `printf '%s\n'`.
- Never build commands in strings for `eval`; hold arguments in an array and expand `"${args[@]}"`.
- Never present native-zsh code as portable; wrap it in `emulate sh` or write it for `#!/bin/sh` from the start.

## Zsh Security

- Never put secrets in argv — visible via `ps`; pass them via environment variables, `chmod 600` files, or stdin.
- Never expand untrusted input through `eval`, `${~var}` globbing, `GLOB_SUBST`, or arithmetic `$(( ))`; validate against an allowlist first.
- Set `PATH` explicitly in privileged scripts and apply `umask 077` before writing sensitive files.
- Use `mktemp` for every temporary file and `setopt NO_CLOBBER` where overwriting must be impossible.
- Never source untrusted files — `.zshrc` plugins, themes, and `oh-my-zsh` snippets are arbitrary code execution; review before sourcing.
- shellcheck does not support zsh: check syntax with `zsh -n`, and keep shared logic POSIX so it can be linted with shellcheck.
