---
title: Language unification
---

# Language unification

**Language unification** rewrites a snippet so that all of it is written in *one* programming language. Real-world admin scripts often are not: a Bash frame that pipes a heredoc into Perl, an inline `awk` program, a `python3 -c` one-liner. Such a script has no single formatter, no single linter, and no analysis that can see across the language boundary.

KorTTY detects the mix locally — no AI request is needed to decide whether the option is offered — and only then asks a model to perform the rewrite.

!!! warning "A rewrite is not a redesign"
    The migration preserves observable behaviour and nothing else. Anything that cannot be carried over is reported back as a **note** instead of being dropped silently or replaced with an invented equivalent. Always read the notes and the before/after preview before you apply.

## Where it appears

| Where | What it does |
|-------|--------------|
| **Snippet editor → AI Code → Migrate into one language…** | Opens the migration dialog directly and shows the result as a before/after preview |
| **Snippet editor → AI Code → Full code analysis** | A collapsed **Language unification** panel; the migration then runs as the **first** stage of *Apply selected*, so every improvement and hardening stage afterwards works on the migrated script |
| **Snippet editor → AI Code → Security Check** | The same panel; the migration runs before the security fixes, so the fixes are written in the target language |
| **Terminal → Generate Workflow Script**, **AI Swarm** | The **Target language only** check box, which forbids embedded foreign-language parts in the generated script from the start |

## Target languages

Bash, Python, Perl, Ruby, PowerShell, Windows-CMD, AppleScript, JavaScript (Node) and Groovy.

Each target brings its own shebang, file extension and comment prefix, and the same per-language idioms the workflow-script generator uses. After a whole-script migration KorTTY also updates the snippet's **Language**, its file extension and its auto-detected AI skills, so the snippet is consistently the new kind of file.

Ansible is deliberately **not** a target: turning an imperative script into a declarative playbook is a re-modelling, not a language migration.

## Orchestration formats

An Azure DevOps pipeline, a GitHub Actions workflow, a GitLab CI file, a Jenkinsfile, an Ansible playbook, a Puppet manifest and a Dockerfile invoke Bash or PowerShell **by construction**. Embedded shell is their design, not a defect, so KorTTY never reports such a document as "mixed" and never offers to migrate the document itself. Two narrower things are offered instead:

#### Unify the script steps

Offered only when the document's own script steps disagree — a pipeline with both `- bash:` and `- pwsh:` steps, a Jenkinsfile mixing `sh` and `bat`. Only the **bodies** of those steps are rewritten. Every other line — structure, keys, indentation, display names, conditions, task invocations, comments — must come back character for character, and the step type is adjusted where the format requires it (`- pwsh:` instead of `- bash:`). A result that changed anything outside the script steps is **discarded**, not offered. The snippet's language, file name and skills stay as they are: it is still the same pipeline.

#### Convert to another platform

Choosing a **Target platform** converts the host document into another platform's schema — a Jenkinsfile into an Azure DevOps pipeline, for instance. This is **never suggested and never preselected**: the selection starts on *Unchanged*, and only an explicit choice activates it.

The conversion is deliberately lossy. Platform semantics are not equivalent, so KorTTY carries over what has a real counterpart (triggers, agent or pool selection, variables, secrets, dependencies, artifacts, conditions) and reports everything else — approvals, environments, platform-specific tasks, matrix semantics, plugin calls — as notes you have to redo by hand. A result that is not recognisable as the requested target platform is discarded.

Dockerfiles are excluded from platform conversion in both directions: an image build recipe is not a CI pipeline.

## What is detected

For a plain script KorTTY looks for heredocs fed to another interpreter (`perl <<'EOF'`, `python3 <<PY`, `node <<'JS'`, …), inline one-liners (`perl -e`, `python3 -c`, `node -e`, `ruby -e`, `awk '…'`), and shell *programs* handed to another language's process API. Matches inside comments are ignored.

`sed -e` expressions are deliberately not flagged: they are ubiquitous in shell, and treating them as a foreign language would make almost every script look mixed. A single external command call is likewise not an embedded language — it stays a plain process call after the migration too.

## When a result is refused

| Message | Cause |
|---------|-------|
| *The AI returned no usable script.* | The reply carried no script at all |
| *The AI returned an incomplete script.* | The result lost most of the program, or contains an omission marker such as “rest unchanged” |
| *The AI changed the document outside its script steps.* | A step unification rewrote part of the pipeline scaffold |
| *The result is not a valid … document.* | A platform conversion did not reach the requested target format |

In every case the snippet is left untouched. Re-running with a stronger model, or with the profile picker in the preview window, is usually enough.
