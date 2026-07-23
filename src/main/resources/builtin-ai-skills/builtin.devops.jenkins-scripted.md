---
kortty-ai-skill: 1
kortty-builtin-id: builtin.devops.jenkins-scripted
kortty-builtin-version: 1
kortty-builtin-topics: [jenkins-scripted, jenkins]
name: "Jenkins Scripted Pipeline"
description: "Conventions the assistant applies when writing Jenkins Scripted Pipeline configuration: comment style, robustness, pitfalls, and secure patterns."
tags: [jenkins, jenkinsfile, scripted]
enabled: true
target: BOTH
---
# Jenkins Scripted Best Practices

When generating or reviewing Jenkins Scripted Pipeline code (Groovy `node {}` Jenkinsfiles and shared-library steps), apply the rules below.

## Jenkins Scripted Comments

- Comment the why, not the what: explain control-flow decisions, retry policies, and agent selection; never restate what a step call visibly does.
- Document the pipeline's contract at the top of the Jenkinsfile: required credential IDs, agent labels, tools, parameters, and the branches it serves.
- Annotate every `@NonCPS` method with why it must run outside CPS, confirming it is a pure transform returning serializable values.
- Give shared-library steps Groovydoc comments describing parameters, return values, side effects, and failure behavior.
- Mark plugin workarounds and serialization dodges with the reason and removal condition; delete stale comments when refactoring.

## Robust Jenkins Scripted Configuration

- Respect CPS serialization: never hold non-serializable objects (iterators, matchers, readers, streams) across a step call like `sh`; do pure transforms in `@NonCPS` methods that return serializable values.
- Wrap stages in try/catch/finally: set the result and rethrow in `catch`; put `cleanWs()` or `deleteDir()` and teardown in `finally` so failures cannot leak workspaces or locks.
- Wrap hang-prone and flaky work in `timeout(...)` and `retry(n)` blocks so builds terminate instead of pinning executors.
- Scope `node {}` blocks tightly: allocate agents only for work that needs one, release before `input` waits, and clean the workspace at block end.
- Validate parameters and environment first and `error(...)` out fast on bad input.
- Centralize repeated logic in a tested, version-pinned shared library instead of copy-pasting Groovy between Jenkinsfiles.

## Avoid in Jenkins Scripted

- Never expand secrets in double-quoted Groovy strings (`sh "cmd ${TOKEN}"`); interpolation leaks the value into process arguments and logs — use single-quoted `sh 'cmd "$TOKEN"'` so the shell resolves it from the environment.
- Never script against `Jenkins.instance` or other internal Jenkins APIs from a pipeline; use pipeline steps, and leave admin automation to configuration-as-code.
- Never advise approving dangerous sandbox script-approval signatures (`Jenkins.getInstance`, `execute`, reflection) to unblock a build; rewrite the code with whitelisted steps.
- Never swallow exceptions with empty catch blocks that leave the build green; set the result and rethrow.
- Never duplicate a Jenkinsfile across repositories with local edits; promote the shared parts into the library.

## Jenkins Scripted Security

- Bind every secret with `withCredentials` at the narrowest scope; never keep secrets in Groovy variables longer than needed or write them to the workspace.
- Never echo credentials or pass them as command-line arguments; deliver them via environment bindings or credential files.
- Treat build parameters and upstream data interpolated into `sh` steps as injection vectors; validate and quote them.
- Load shared libraries only from trusted, version-pinned repositories and review them as production code; they run with pipeline privileges, often outside the sandbox.
- Scope credentials per folder or job with least privilege instead of reusing global credentials.
- Do not disable the Groovy sandbox or CSRF protection to unblock a build; fix the underlying code.
