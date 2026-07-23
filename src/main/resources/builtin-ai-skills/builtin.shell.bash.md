---
kortty-ai-skill: 1
kortty-builtin-id: builtin.shell.bash
kortty-builtin-version: 1
kortty-builtin-topics: [bash]
name: "Bash"
description: "Conventions the assistant applies when writing Bash code: comment style, robustness, pitfalls, and secure patterns."
tags: [bash, .sh, shellcheck, pipefail, bashrc]
enabled: true
target: BOTH
---
# Bash Best Practices

When generating or reviewing Bash code or configuration, apply the rules below.

## Bash Comments

- Comment the why, not the what: explain intent, workarounds, and non-obvious constraints, not what a command visibly does.
- Start every script with `#!/usr/bin/env bash` and a header comment stating purpose, required external commands, and expected environment variables.
- Document each function above its definition: purpose, arguments, globals read or written, and exit-status meaning.
- State contracts and invariants: which variables must already be set, which steps must run first, and which files are assumed to exist.
- When a rule is deliberately broken, say why and add `# shellcheck disable=SCxxxx` so the exception is auditable.

## Robust Bash Code

- Start scripts with `set -u` and `set -o pipefail` so unset variables and mid-pipeline failures become errors.
- Use `set -e` knowing its caveats: it does not trigger inside `if`/`while` conditions, command substitutions, or `&&`/`||` chains — append `|| exit 1` to critical steps.
- Quote every expansion: `"$var"`, `"$@"`, `"${array[@]}"`; treat any unquoted expansion as a bug unless a comment justifies it.
- Validate inputs early: check argument counts, verify paths exist and are readable, and fail to stderr with a nonzero exit before doing any work.
- Install `trap cleanup EXIT INT TERM` and create temporary files and directories with `mktemp` so partial state is removed on every exit path.
- Check dependencies with `command -v tool >/dev/null 2>&1 || exit 1` before first use.
- Emit data with `printf '%s\n'` rather than `echo`, which mangles backslashes and leading dashes.

## Avoid in Bash

- Never parse `ls` output; iterate with globs (`for f in ./*`) or `find ... -print0` with `read -r -d ''`.
- Never assemble commands in strings for `eval`; keep arguments in an array and expand `"${args[@]}"`.
- Never let user-supplied paths be read as options; end option parsing with `--` and prefix relative globs with `./`.
- Never use backticks for command substitution; use `$(...)`, which nests cleanly.
- Never pipe downloads straight into the shell (`curl | bash`); save to a file, verify a checksum, then run it.
- Never redirect a file onto itself (`sed ... file > file`); write to a `mktemp` file and `mv` it into place.

## Bash Security

- Never place secrets in argv — it is visible to all users via `ps`; pass secrets via the environment, `chmod 600` files, or stdin.
- Never interpolate untrusted input into `eval`, `bash -c`, SSH remote commands, or SQL strings; validate against an allowlist and pass values as separate quoted arguments.
- Set `umask 077` before writing sensitive files and set `PATH` explicitly in privileged scripts.
- Prevent tempfile races: use `mktemp` instead of predictable names and `set -o noclobber` when creation must never overwrite.
- Never source configuration from paths an attacker can write; sourcing is code execution.
- Run `shellcheck` on every script and fix or explicitly justify every finding; shellcheck-clean is the quality bar.
