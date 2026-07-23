---
kortty-ai-skill: 1
kortty-builtin-id: builtin.devops.azure-pipelines
kortty-builtin-version: 1
kortty-builtin-topics: [azure-pipelines, azure-devops]
name: "Azure DevOps Pipelines"
description: "Conventions the assistant applies when writing Azure DevOps Pipelines configuration: comment style, robustness, pitfalls, and secure patterns."
tags: [azure-pipelines, azure-devops]
enabled: true
target: BOTH
---
# Azure-Pipelines Best Practices

When generating or reviewing Azure DevOps Pipelines YAML, apply the rules below.

## Azure-Pipelines Comments

- Comment the why, not the what: explain stage and job intent, trigger choices, and non-obvious conditions; never restate what a task invocation already says.
- Give stages, jobs, and steps meaningful `displayName` values plus a YAML comment wherever the purpose or ordering is not self-evident.
- Document template contracts at the top of each template: every parameter with meaning, type, default, and allowed values.
- State pipeline invariants explicitly: required variable groups, service connections, agent pool assumptions, and triggering branches.
- Mark intentional deviations (a pinned older task, a disabled check) with a comment giving the reason and the removal condition.

## Robust Azure-Pipelines Configuration

- Use YAML pipelines with templates for reuse; factor repeated stage/job/step blocks into parameterized templates instead of copy-pasting.
- Type every runtime parameter and constrain it with `values:` allowed lists so bad input fails at queue time.
- Make `dependsOn` and `condition` explicit on jobs and stages; never rely on implicit ordering, and decide deliberately how each stage behaves on upstream failure.
- Set `timeoutInMinutes` on every job so hung builds fail instead of blocking agents.
- Pin task versions with the `task@N` major-version syntax; review before adopting a new major.
- Extract any inline script beyond a few lines into a versioned script file in the repository so it can be reviewed, linted, and tested.
- Publish diagnostics (test results, logs) as artifacts on failure so broken runs are debuggable without rerunning.

## Avoid in Azure-Pipelines

- Never inline secret values in YAML or variable defaults; reference variable groups or Key Vault-backed groups with the variable marked secret.
- Never echo or log secret variables, and never pass them on command lines where process listings can capture them; pass them via `env:` mappings.
- Never assume secret variables reach scripts automatically; they are not mapped by default — map each one explicitly through `env:`.
- Never install tooling with curl-pipe-to-shell one-liners; use pinned tool-installer tasks or versioned packages with checksum verification.
- Never use floating task or image references when the result must be repeatable; pin explicitly.
- Never grant one giant service connection to every pipeline; scope connections narrowly per project and purpose.

## Azure-Pipelines Security

- Store secrets only in secret-marked variable-group entries or Azure Key Vault; rotate them outside the pipeline definition.
- Give service connections least privilege and restrict which pipelines may use them; require approvals and checks on protected environments and production stages.
- Treat pull-request builds from forks as untrusted: do not expose secrets to them, and keep secret-dependent stages behind branch conditions.
- Treat user-controllable values expanded into scripts as injection vectors; validate parameters with allowed values and quote expansions.
- Do not check out or execute code from unreviewed external repositories in privileged jobs; declare repository resources explicitly and pin refs.
- Deploy through named environments with approval gates and retained artifacts, never ad-hoc manual steps.
