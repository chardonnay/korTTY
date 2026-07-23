---
kortty-ai-skill: 1
kortty-builtin-id: builtin.shell.powershell
kortty-builtin-version: 1
kortty-builtin-topics: [powershell, pwsh]
name: "PowerShell"
description: "Conventions the assistant applies when writing PowerShell code: comment style, robustness, pitfalls, and secure patterns."
tags: [powershell, pwsh, .ps1, cmdlet]
enabled: true
target: BOTH
---
# PowerShell Best Practices

When generating or reviewing PowerShell scripts, functions, or modules, apply the rules below.

## PowerShell Comments

- Comment the why, not the what: explain intent, trade-offs, and non-obvious Windows/pwsh behavior; never restate what a cmdlet call visibly does.
- Give every exported function comment-based help with at least `.SYNOPSIS`, `.DESCRIPTION`, `.PARAMETER` for each parameter, and one `.EXAMPLE`.
- Document contracts and invariants near the code that owns them: accepted pipeline input types, emitted object types, side effects, required privileges, and idempotency guarantees.
- Mark deliberate workarounds (encoding quirks, legacy cmdlet bugs, remoting constraints) with a comment stating the reason and the condition under which the workaround can be removed.
- Keep comments current when editing code; delete stale ones rather than leaving contradictions.

## Robust PowerShell Code

- Start every script with `Set-StrictMode -Version Latest` and `$ErrorActionPreference = 'Stop'`.
- Wrap failure-sensitive operations in try/catch and pass `-ErrorAction Stop` to cmdlets inside the try, because non-terminating errors bypass catch blocks.
- Decorate functions with `[CmdletBinding()]`, use approved verbs (`Get-`, `Set-`, `New-`, `Remove-`; check with `Get-Verb`), and type every parameter.
- Validate input declaratively with parameter attributes: `[ValidateNotNullOrEmpty()]`, `[ValidateSet()]`, `[ValidateRange()]`, `[ValidatePattern()]`.
- Implement `SupportsShouldProcess` and honor `-WhatIf`/`-Confirm` for any destructive action.
- Fail safely: throw terminating errors for unrecoverable states, return distinct non-zero exit codes from scripts, and check `$LASTEXITCODE` after every native command.
- Keep code PSScriptAnalyzer-clean; treat its warnings as errors in CI.

## Avoid in PowerShell

- Never call `Invoke-Expression` on external or user-supplied input; invoke commands directly with the call operator `&` and an argument array.
- Never parse the text output of native tools when a cmdlet exists; work with objects on the pipeline (`Get-ChildItem`, `Get-Process`) instead of string matching.
- Never use aliases (`ls`, `%`, `?`, `gci`) in saved scripts; write full cmdlet and parameter names.
- Never write `$x -eq $null`; put `$null` on the left (`$null -eq $x`) so collections compare correctly.
- Never suppress errors globally with `-ErrorAction SilentlyContinue`; scope suppression to a single expected failure and verify state afterward.
- Never concatenate path strings; build paths with `Join-Path` and resolve them with `Resolve-Path`.

## PowerShell Security

- Never handle passwords as plaintext strings; use `[SecureString]`, `PSCredential`, and the SecretManagement module backed by a vault.
- Never hard-code secrets, tokens, or connection strings in scripts, parameter defaults, or transcripts; fetch them at runtime from a secret store.
- Sign production scripts or enforce a centrally managed execution policy; do not advise weakening the policy to run unsigned code.
- Treat any value interpolated into a scriptblock, SQL text, or remote `Invoke-Command` as an injection vector; pass data via `-ArgumentList` and parameter blocks, not string building.
- Reject download-and-execute patterns such as `iex (irm ...)`; download to disk, verify integrity, then run explicitly.
- Run with least privilege; request elevation only for the specific operations that need it.
