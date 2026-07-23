---
kortty-ai-skill: 1
kortty-builtin-id: builtin.shell.sh
kortty-builtin-version: 1
kortty-builtin-topics: [sh, bourne, posix-sh]
name: "Bourne-Shell (sh, POSIX)"
description: "Conventions the assistant applies when writing portable POSIX sh code: comment style, robustness, pitfalls, and secure patterns."
tags: [bourne, posix, bin/sh]
enabled: true
target: BOTH
---
# POSIX sh Best Practices

When generating or reviewing Bourne-Shell (sh, POSIX) code or configuration, apply the rules below.

## POSIX sh Comments

- Comment the why, not the what: capture intent, workarounds, and portability constraints; never narrate what a line obviously does.
- Start every script with `#!/bin/sh` and a header stating purpose and required utilities; `#!/bin/sh` means strict POSIX — target dash or BusyBox ash, never bash.
- Document each function (`name() { }`) above its definition: purpose, parameters, globals touched, and exit-status meaning.
- Record contracts and invariants: variables callers must set, files assumed to exist, and ordering between steps.
- Mark any deliberate deviation from POSIX (such as `local`) with a comment naming the shells verified to support it.

## Robust POSIX sh Code

- Start scripts with `set -u`; `set -e` does not fire in `if`/`while` conditions, command substitutions, or `&&`/`||` chains — append `|| exit 1` on critical steps; `set -o pipefail` is not universal in `/bin/sh`, so check pipeline stages individually or via `mktemp` intermediates.
- Quote every expansion: `"$var"`, `"$@"`, `"$(cmd)"`; treat any unquoted expansion as a bug unless a comment justifies it.
- Validate inputs early with `case` patterns (`case $1 in -*) ... esac`) and file tests (`[ -r "$f" ]`); fail to stderr with a nonzero exit before side effects.
- Install `trap cleanup EXIT INT TERM`, create temporary files with `mktemp`, and remove them in the cleanup handler.
- Check dependencies with `command -v tool >/dev/null 2>&1 || exit 1`.
- Emit data with `printf '%s\n'`, never `echo` — flag and escape handling varies between sh implementations.
- Treat `local` as an extension: dash and BusyBox ash support it but POSIX does not guarantee it — comment the assumption or use unique names.

## Avoid in POSIX sh

- Never use arrays — POSIX sh has none; reuse positional parameters (`set -- a b c`; `"$@"`) or newline-delimited data.
- Never use `[[ ]]`; use `[ ]` with quoted operands or `case` patterns.
- Never use bash expansions like `${var/pat/rep}` or `${var,,}`; use POSIX forms (`${var%suffix}`, `${var#prefix}`, `${var:-default}`) or `sed`/`tr`.
- Never use process substitution `<(...)`; use `mktemp` files or plain pipes.
- Never use `source`; use `.` with an explicit path.
- Never parse `ls` output; iterate with globs (`for f in ./*`) or `find`.

## POSIX sh Security

- Never place secrets in argv — it is world-readable via `ps`; pass them via environment variables, `chmod 600` files, or stdin.
- Never feed untrusted input to `eval`, `.`-sourcing, or interpolated command strings; validate against an allowlist and pass values as quoted arguments.
- Set `PATH` explicitly in privileged scripts and apply `umask 077` before writing sensitive files.
- Create temporary files only with `mktemp`; enable `set -C` (noclobber) where redirection must never overwrite an existing file.
- Never source files from directories other users can write; sourcing is code execution.
- Lint with `shellcheck -s sh`, flag bashisms with `checkbashisms`, and fix or justify every finding.
