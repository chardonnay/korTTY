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
| **Snippet editor → AI Code → Full code analysis** | You click *Apply selected*; each enabled guard rule becomes a separately tracked mandatory requirement, and an incomplete replacement is rejected before review |

Unlike the classic hardening options, input hardening is **strictly opt-in**: the master check box starts unticked and the panel starts collapsed, because the guard changes the script's runtime behaviour — a script that previously accepted any input will start rejecting input that violates the rules. Tick **Input hardening (validate script inputs)** to activate it for the current generation or rewrite.

Declarative YAML/YML/Ansible targets cannot receive this imperative argument-and-file guard. KorTTY disables the panel for those targets in the workflow, swarm, **Improve robustness**, **Custom improvement**, and **Full code analysis** dialogs, and an already-saved enabled default yields no effective guard configuration there. For snippet improvements in a supported language, enabling Input hardening changes the target to the **complete snippet** so the guard can be placed before all real work; the complete result is reviewed before it is applied.

## The five guard behaviours

With the master check box ticked, five sub-options control what the guard enforces. All five are pre-ticked; untick what you do not want.

#### Parameter allowlists & length limits

Every parameter the script is called with is validated: the exact expected parameter count, a per-parameter character allowlist derived from how the script actually uses the value (a number, a file path, a host name, a keyword, free text), and a maximum length per parameter. Control characters, NUL bytes and shell metacharacters (`;`, `|`, `&`, `` ` ``, `$`, `\`, `<`, `>`, embedded newlines) are rejected for every parameter that does not legitimately need them, so over-long or maliciously crafted values are refused instead of processed.

#### Input file format checks

Every parameter the script uses as an input file path is checked before first use: the file must exist and be readable, and its content format must match what the script can process. A text-processing script rejects binary files — the guard scans the first bytes for NUL bytes and additionally consults `file --mime-type` only where that command exists, falling back to the built-in check where it does not.

#### Max. input file size (script variable)

The guard defines a `MAX_FILE_SIZE` variable (in bytes) in its configuration section. When `MAX_FILE_SIZE` is greater than `0`, the guard obtains the file size from metadata before any operation reads file content — including the format check's initial content scan. A larger file is rejected with exit code `65` and its content is not read. If no metadata-only size query is available, the guard rejects the file without reading it. `0` means unlimited and skips the size check entirely. The size spinner in the panel accepts `0`–`1024` MB and sets the generated default to 10 MB unless you change it.

#### Security warnings to stderr & script log

Every violation is reported as a timestamped security warning line starting with `SECURITY:` on stderr. If **FORCE=1 override** is also selected, every forced bypass is reported the same way. If the script writes its own log file, the guard appends the same warning line there too, so the script's log carries a complete security trail.

#### FORCE=1 override

Blocking is the default, but a run can be forced: when the environment variable `FORCE` is set to `1`, the guard downgrades every violation to a warning and continues. Each individual violation is still reported, plus one extra warning that enforcement was bypassed — a forced run always leaves a complete trace.

## Exit codes

When the guard blocks a run (and `FORCE` is not set), it uses distinct, documented exit codes so callers can tell *why* the input was rejected. The prompt names only codes created by the selected checks: format-only and size-only selections each describe only their own `65` case, and `66` is included only with **Input file format checks**.

| Exit code | Meaning |
|-----------|---------|
| `64` | A parameter violated the rules (count, characters, length) |
| `65` | An input file failed the format or size checks |
| `66` | An input file is missing or unreadable |

## Language awareness

The guard uses each language's own built-ins and standard library wherever available, and it probes optional platform tools before using them. Bash guards use `$#`, pattern matching, GNU `stat -c %s` or BSD/macOS `stat -f %z` for a metadata-only size check, and a NUL-byte scan only after that size check passes; if neither `stat` form works, the guard rejects the file without reading it. Python guards use `sys.argv`, `re`, `os.path.getsize` and a binary read; Perl guards use `@ARGV`, untainting regex allowlists, taint mode (`-T`) where the script allows it and the metadata-only `-s` operator; Ruby guards use `ARGV`, `Regexp`, `File.size` and `File.binread`. Other script languages get generic guidance only for the native argument, file, logging or environment facilities required by the selected sub-options. Declarative YAML/YML/Ansible targets are disabled because they take no positional parameters this way.

## Managing the selection

Below the sub-options, **All** and **Clear** tick or untick every sub-option, and **Save** remembers the master toggle, the sub-option set and the size limit as your default — every Input hardening panel then opens with that selection. The panel title in the Full code analysis window shows a live **count** of the effectively active sub-options, which is `0` while the master toggle is off.
