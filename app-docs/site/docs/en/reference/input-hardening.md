---
title: Input hardening
---

# Input hardening

**Input hardening** asks the AI to build an *input-validation guard block* directly into a script it generates or rewrites. The guard runs at the very top of the script, before any real work, and rejects bad input on the host that executes the script: wrong parameter counts, forbidden characters, oversized values, missing input files, files in the wrong format, and files above a configurable size limit. Violations stop the script with a clear message and a documented exit code — unless the run is deliberately forced.

!!! note "The guard lives in the script, not in korTTY"
    korTTY performs **no validation of its own** when a snippet or workflow script runs. Input hardening changes the *script*: the AI writes the checks into the code, so they protect every future run of that script — also when it is started outside korTTY, by a scheduler, or on a remote host. If you edit the script later, the guard is ordinary script code you can read, adjust, or remove.

## Input hardening vs. Hardening options

Despite the similar name, this is a different feature than [Hardening options](hardening-options.md). *Hardening options* are general production-quality techniques (strict mode, error traps, logging, `--help`, …) that shape *how the script is written*. *Input hardening* is about *what the script accepts*: it adds a concrete guard block that validates parameters and input files at run time. The two panels appear side by side and can be combined freely.

## Where it appears

The **Input hardening** panel shows up in the same places as the classic hardening panel, plus the AI-Swarm generator:

| Where | Applied when |
|-------|--------------|
| **Terminal → Generate Workflow Script** (the *Workflow* button after an agent run) | You click *Generate* |
| **AI Swarm → Generate multi-server workflow** | You generate the multi-server script |
| **Snippet editor → AI Code → Improve robustness** | You confirm the dialog |
| **Snippet editor → AI Code → Custom improvement…** | You confirm the dialog |
| **Snippet editor → AI Code → Full code analysis** | You click *Apply selected* |

Unlike the classic hardening options, input hardening is **strictly opt-in**: the master check box starts unticked and the panel starts collapsed, because the guard changes the script's runtime behaviour — a script that previously accepted any input will start rejecting input that violates the rules. Tick **Input hardening (validate script inputs)** to activate it for the current generation or rewrite.

## The five guard behaviours

With the master check box ticked, five sub-options control what the guard enforces. All five are pre-ticked; untick what you do not want.

#### Parameter allowlists & length limits

Every parameter the script is called with is validated: the exact expected parameter count, a per-parameter character allowlist derived from how the script actually uses the value (a number, a file path, a host name, a keyword, free text), and a maximum length per parameter. Control characters, NUL bytes and shell metacharacters (`;`, `|`, `&`, `` ` ``, `$`, `\`, `<`, `>`, embedded newlines) are rejected for every parameter that does not legitimately need them, so over-long or maliciously crafted values are refused instead of processed.

#### Input file format checks

Every parameter the script uses as an input file path is checked before first use: the file must exist and be readable, and its content format must match what the script can process. A text-processing script rejects binary files — the guard scans the first bytes for NUL bytes and additionally consults `file --mime-type` only where that command exists, falling back to the built-in check where it does not.

#### Max. input file size (script variable)

The guard defines a `KORTTY_MAX_FILE_SIZE` variable (in bytes) in its configuration section and rejects every input file above that limit. The panel's **Max. file size** spinner sets the generated default — 10 MB unless you change it — and because the limit is an ordinary script variable, the script author can raise or lower it later by simply editing that line in the script.

#### Security warnings to stderr & script log

Every violation, and every forced bypass, is reported as a timestamped security warning line starting with `SECURITY:` on stderr. If the script writes its own log file, the guard appends the same warning line there too, so the script's log carries a complete security trail.

#### KORTTY_FORCE=1 override

Blocking is the default, but a run can be forced: when the environment variable `KORTTY_FORCE` is set to `1`, the guard downgrades every violation to a warning and continues. Each individual violation is still reported, plus one extra warning that enforcement was bypassed — a forced run always leaves a complete trace.

## Exit codes

When the guard blocks a run (and `KORTTY_FORCE` is not set), it uses distinct, documented exit codes so callers can tell *why* the input was rejected:

| Exit code | Meaning |
|-----------|---------|
| `64` | A parameter violated the rules (count, characters, length) |
| `65` | An input file failed the format or size checks |
| `66` | An input file is missing or unreadable |

## Language awareness

The guard is implemented with each language's own built-ins and standard library only — it never depends on tools that may be missing on the target host, and optional helpers (like the `file` command) degrade gracefully to built-in checks. Bash guards use `$#`, pattern matching, `wc -c` and a NUL-byte scan; Python guards use `sys.argv`, `re`, `os.path.getsize` and a binary read; Perl guards use `@ARGV`, untainting regex allowlists and taint mode (`-T`) where the script allows it; Ruby guards use `ARGV`, `Regexp` and `File.binread`. Other script languages get a generic guard built from their native argument, string and file facilities. Declarative Ansible playbooks take no positional parameters this way, so input hardening does not apply to them.

## Managing the selection

Below the sub-options, **All** and **Clear** tick or untick every sub-option, and **Save** remembers the master toggle, the sub-option set and the size limit as your default — every Input hardening panel then opens with that selection. The panel title in the Full code analysis window shows a live **count** of the effectively active sub-options, which is `0` while the master toggle is off.
