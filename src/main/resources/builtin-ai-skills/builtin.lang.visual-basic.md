---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.visual-basic
kortty-builtin-version: 1
kortty-builtin-topics: [visual-basic, visualbasic, vb.net, vba]
name: "Visual Basic (VB.NET)"
description: "Conventions the assistant applies when writing Visual Basic code: comment style, robustness, pitfalls, and secure patterns."
tags: [vb.net, vba, vbscript]
enabled: true
target: BOTH
---
# VB.NET Best Practices

When generating or reviewing Visual Basic code — VB.NET projects, Office VBA macros, or legacy VBScript — apply the rules below.

## VB.NET Comments

- Comment to explain why — intent, trade-offs, and non-obvious constraints — never to restate what the code does.
- Write XML doc comments (`'''`) on every public type and member: `<summary>`, plus `<param>`, `<returns>`, and `<exception>` where applicable.
- Document contracts and invariants: nullability of reference parameters, valid ranges, units, thread-safety, and who owns disposal of passed objects.
- Tag workarounds as `' TODO(name): reason` so they remain searchable and attributable.
- In VBA modules, keep a short header comment stating the module's purpose and any workbook or document state it depends on.

## Robust VB.NET Code

- Set `Option Strict On` and `Option Explicit On` in every file or project-wide; fix the resulting errors instead of relaxing the options.
- Handle errors with structured `Try/Catch/Finally`; catch specific exception types and never swallow one — log with context or rethrow with a bare `Throw` to preserve the stack.
- Validate inputs at public boundaries and throw `ArgumentException`/`ArgumentNullException` naming the offending parameter.
- Wrap every `IDisposable` (connections, streams, Office interop wrappers) in a `Using` block.
- In VBA, declare every variable under `Option Explicit`, and write error handlers that restore application state (`ScreenUpdating`, `EnableEvents`, `Calculation`) before exiting.
- On unrecoverable errors release resources and exit with a nonzero code (console) or a clear failure signal; never continue on corrupted state.

## Avoid in VB.NET

- Never use `On Error Resume Next` in new code; use `Try/Catch` in VB.NET, or in VBA a single structured `On Error GoTo` handler with cleanup.
- Never leave variables implicitly typed as `Variant`/`Object`; declare explicit types — `Dim total As Long`, not `Dim total`.
- Never rely on implicit narrowing conversions; convert explicitly with `CType`, `CInt`, or `TryParse` under `Option Strict On`.
- Never write new automation in VBScript; it is legacy-only — recommend PowerShell for new Windows scripting.
- Never use legacy `Microsoft.VisualBasic` compatibility functions where a framework equivalent exists; use `String.IsNullOrEmpty`, `System.IO`, and `DateTime`.
- Never block on async work with `.Result` or `.Wait()`; use `Await` end to end.

## VB.NET Security

- Query databases only with parameterized ADO.NET commands (`SqlCommand` with typed `SqlParameter` objects); never concatenate user input into SQL strings.
- Keep secrets out of source and committed config; read them from environment variables, the user-secrets store, or a vault.
- Never call `Shell`, `CreateObject("WScript.Shell")`, or `Process.Start` with a command string built from input; pass explicit argument lists and validate paths against traversal.
- Never deserialize untrusted data with `BinaryFormatter`; use `System.Text.Json` or XML readers with strict settings.
- In VBA, never execute strings from documents or downloads (`Application.Evaluate` on untrusted input, `ExecuteExcel4Macro`); require signed macros and treat external documents as hostile.
- Pin NuGet dependencies and scan for known CVEs (`dotnet list package --vulnerable`) before shipping.
