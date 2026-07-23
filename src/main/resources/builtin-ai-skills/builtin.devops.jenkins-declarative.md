---
kortty-ai-skill: 1
kortty-builtin-id: builtin.devops.jenkins-declarative
kortty-builtin-version: 1
kortty-builtin-topics: [jenkins-declarative, jenkins]
name: "Jenkins Declarative Pipeline"
description: "Conventions the assistant applies when writing Jenkins Declarative Pipeline configuration: comment style, robustness, pitfalls, and secure patterns."
tags: [jenkins, jenkinsfile, declarative]
enabled: true
target: BOTH
---
# Jenkins Declarative Best Practices

When generating or reviewing Jenkins Declarative Pipeline code (a `pipeline {}` Jenkinsfile), apply the rules below.

## Jenkins Declarative Comments

- Comment the why, not the what: explain non-obvious stage purposes, agent choices, and `when` conditions; never restate what a step visibly does.
- Use clear stage names as first-line documentation; add a `//` comment above any stage whose intent, ordering, or side effect is not obvious from its name.
- Document the pipeline's contract at the top of the Jenkinsfile: required credential IDs, agent labels, tools, parameters, and target branches.
- Mark workarounds (plugin bugs, agent quirks) with the reason and the condition for removing them; delete stale comments during edits.

## Robust Jenkins Declarative Configuration

- Use declarative `pipeline {}` syntax throughout; reach for a `script {}` block only when no declarative construct expresses the logic, and keep such blocks minimal.
- Set `options { timeout(...) }` so hung builds die, `disableConcurrentBuilds()` where concurrent runs would collide, and wrap flaky external calls in `retry(n)`.
- Declare a typed `parameters` block (`string`, `booleanParam`, `choice`) and validate values in the first stage, failing fast on bad input.
- Give each stage the narrowest suitable `agent`, preferring per-stage containerized tool images over one fat static agent for reproducibility.
- Wrap `input` approval steps in a `timeout` so unanswered prompts abort instead of blocking an executor.
- Add a `post` section: `always { cleanWs() }` plus `failure`/`unstable` notifications so broken builds are seen, not ignored.
- Keep the Jenkinsfile thin: move nontrivial logic into a tested, versioned shared library.

## Avoid in Jenkins Declarative

- Never expand secrets in double-quoted Groovy strings (`sh "cmd ${PASSWORD}"`); Groovy interpolation writes the value into process arguments and logs — use single-quoted `sh 'cmd "$PASSWORD"'` so the shell reads it from the environment.
- Never scatter `script {}` escape hatches through the pipeline; restructure with declarative directives (`when`, `matrix`, `parallel`) and shared-library steps.
- Never hard-code credentials, tokens, or URLs with embedded passwords in the Jenkinsfile; bind them at use time with `withCredentials` or `environment { credentials(...) }`.
- Never swallow failures with try/catch inside `script {}` that leaves the build green; let stages fail or set the result explicitly.
- Never run heavyweight logic on the controller; pin work to build agents via `agent` labels.

## Jenkins Declarative Security

- Access every secret exclusively through `withCredentials` or the `credentials()` helper; never echo secret variables or pass them as command-line arguments.
- Treat build parameters interpolated into `sh`/`bat`/`powershell` steps as injection vectors; validate and quote them.
- Restrict who can approve `input` steps with `submitter`; do not leave production gates open to any authenticated user.
- Load shared libraries only from trusted, version-pinned sources and review them as production code, since they run with pipeline privileges.
- Scope credentials narrowly (per folder or job) with least privilege rather than global credentials reused everywhere.
- Do not disable script security or CSRF protection to make a pipeline pass; fix the pipeline instead.
