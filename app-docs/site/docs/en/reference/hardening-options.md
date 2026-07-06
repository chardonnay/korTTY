---
title: Hardening options
---

# Hardening options

**Hardening options** are a set of production-quality techniques you can ask the
AI to bake into a script it generates or rewrites. Instead of writing the same
instruction ("add error handling", "make it re-runnable", "give it a `--help`")
by hand every time, you tick the techniques you want and KorTTY turns each one
into a precise rule that is appended to the AI prompt. The result is a script
that behaves like something a careful engineer would ship: it fails loudly
instead of silently, cleans up after itself, logs what it does, and can be run
again without surprises.

The same eleven options are used everywhere KorTTY generates or improves a
script, so they behave identically no matter where you start from.

## Where they appear

The **Hardening options** panel shows up in these places:

| Where | How it looks | Applied when |
|-------|--------------|--------------|
| **Terminal → Generate Workflow Script** (the *Workflow* button after an agent run) | Collapsible *Hardening options* panel (collapsed by default) | You click *Generate* |
| **AI Swarm → Generate multi-server workflow** | Same collapsible panel | You generate the multi-server script |
| **Snippet editor → AI Code → Improve robustness** | Options panel with all boxes ticked | You confirm the dialog |
| **Snippet editor → AI Code → Custom improvement…** | Options panel plus a free-text instruction field | You confirm the dialog |
| **Snippet editor → AI Code → Full code analysis** | Collapsible *Hardening options* panel at the bottom of the window | You click *Apply selected* |

!!! note "Not shown for every action"
    *Improve readability* and *Improve performance* deliberately do **not** show
    hardening options — those actions are meant to stay close to the original
    code. Hardening options appear only where adding robustness is the point:
    *Improve robustness*, *Custom improvement*, *Full code analysis*, and the two
    workflow-script generators.

Every option is **ticked by default**. Untick the ones you do not want. An
unticked option contributes nothing to the prompt.

## How they are applied

Each ticked option becomes exactly one instruction line that KorTTY appends to
the request sent to the AI (under *Apply these hardening techniques:* in the
snippet editor, or *ADDITIONAL REQUIREMENTS:* in the workflow generator). The AI
is then asked to honour those rules while producing the script.

The wording of each rule **adapts to the target language**:

- **Imperative scripts** — Bash, Python, Perl, Ruby, PowerShell, Windows-CMD and
  AppleScript get the imperative phrasing (flags, traps, exit codes, …).
- **Declarative artefacts** — Ansible playbooks, and snippets whose language is
  `YAML`/`YML` or contains `ansible`, get Ansible-idiomatic phrasing instead
  (`block`/`rescue`/`always`, `assert`, `vars:`, check mode, …).

So the *idea* of each option is the same everywhere, but a Bash script gets a
`set -euo pipefail` style rule while an Ansible playbook gets an `assert` /
`failed_when` style rule for the very same tick-box.

## The two groups

The eleven options fall into two groups by their effect on the script:

- **Behaviour-preserving hardening** (the first seven) only add documentation,
  logging, structure, and safety nets. They make the script sturdier and easier
  to read **without changing what it actually does**. These are safe to leave on
  for almost any script.
- **Behavioural / interactive changes** (the last four) can change control flow
  or add a command-line interface — precondition gates, re-run detection, a
  dry-run mode, argument parsing. Leave them off if you want the rewrite to stay
  as close as possible to the original behaviour.

## Option reference

Each option below lists what it is for and the exact rule KorTTY sends to the
AI — for imperative scripts and for Ansible playbooks.

### Behaviour-preserving hardening

#### Strict mode (abort on error)

- **What it is for** — Stop the script the moment something goes wrong instead of
  blindly continuing on a half-failed state.
- **Imperative scripts** — Enable the language's strict / abort-on-error mode (for
  example `set -euo pipefail` in Bash, `Set-StrictMode -Version Latest` with
  `$ErrorActionPreference = 'Stop'` in PowerShell, `use strict; use warnings;` in
  Perl).
- **Ansible playbooks** — Validate prerequisites with `assert`/`failed_when` so
  bad state fails the play immediately.

#### Error trap & cleanup

- **What it is for** — Guarantee that a failure is reported clearly and that any
  temporary state (temp files, mounts, locks) is cleaned up even when the script
  aborts.
- **Imperative scripts** — Add an error trap / `finally` / `ensure` block that
  reports failures and cleans up temporary state.
- **Ansible playbooks** — Use `block`/`rescue`/`always` so failures are caught and
  cleanup always runs.

#### Meaningful exit codes

- **What it is for** — Let whoever calls the script (a scheduler, a CI job,
  another script) tell *why* it failed, not just *that* it failed.
- **Imperative scripts** — Use distinct, documented non-zero exit codes for
  distinct failure classes.
- **Ansible playbooks** — Make failing tasks stop the play with a clear message
  (`any_errors_fatal` where sensible).

#### Logging (`--verbose`)

- **What it is for** — Make the script's progress visible and debuggable without
  cluttering normal output.
- **Imperative scripts** — Emit timestamped log messages to stderr and support a
  `--verbose`/`-v` flag.
- **Ansible playbooks** — Use the `debug` module for progress output (visible with
  `-v`).

#### Configuration block for literals

- **What it is for** — Collect the values you are most likely to change (paths,
  hostnames, package names) in one obvious place instead of scattering them
  through the script.
- **Imperative scripts** — Hoist all literals (paths, hosts, packages) into a
  clearly commented configuration block near the top.
- **Ansible playbooks** — Hoist all literals into a `vars:` block at the top.

#### Final summary

- **What it is for** — End with a short report so the operator can see at a glance
  what happened.
- **Imperative scripts** — Print a final summary of what was done (with
  success/failure counts).
- **Ansible playbooks** — End with a `debug` summary of what changed.

#### Style-guide / linter clean

- **What it is for** — Produce code that passes the language's standard linter, so
  it reads consistently and avoids common footguns.
- **Imperative scripts** — Follow the language style guide and keep it
  linter-clean (for example ShellCheck-clean for Bash).
- **Ansible playbooks** — Follow `ansible-lint` conventions and use
  fully-qualified module names.

### Behavioural / interactive changes

#### Precondition checks

- **What it is for** — Fail fast, before touching anything, if the environment
  isn't ready — a missing command, insufficient privileges, or no network.
- **Imperative scripts** — Before doing work, verify required commands, privileges
  (root/sudo) and connectivity.
- **Ansible playbooks** — Add `pre_tasks`/`assert` checks for required privileges,
  packages and connectivity before any change.

#### Idempotency (skip completed steps)

- **What it is for** — Make the script safe to run a second time: steps that are
  already done are detected and skipped instead of repeated or erroring out.
- **Imperative scripts** — Detect already-completed steps and skip them so the
  script is safe to re-run.
- **Ansible playbooks** — Ensure the playbook is fully idempotent (safe to re-run;
  rely on module idempotency and `creates`/`removes`).

#### Safe mode (`--dry-run` + confirm)

- **What it is for** — Let the operator preview what the script *would* do without
  making any changes, and ask for confirmation before anything destructive.
- **Imperative scripts** — Support a `--dry-run` flag that prints intended actions
  without executing, and confirm before destructive operations (suppressible with
  `--yes`).
- **Ansible playbooks** — Support check mode (`--check`) and guard destructive
  tasks so a dry run makes no changes.

#### `--help` & argument parsing

- **What it is for** — Turn the script into a proper command-line tool with
  documented, overridable inputs instead of hard-coded values.
- **Imperative scripts** — Provide a `--help`/usage message and parse command-line
  arguments for the configurable values.
- **Ansible playbooks** — Document all variables and how to override them via
  `--extra-vars` at the top of the file.

## Tips

- Start with the defaults (all on) for a throwaway or personal script — the
  behaviour-preserving group costs you nothing and the interactive group makes
  the script friendlier.
- For a rewrite where you want the smallest possible diff, untick the four
  behavioural options and keep only the behaviour-preserving group.
- The options are independent — you can tick any combination. KorTTY only sends
  rules for the boxes that are ticked.
