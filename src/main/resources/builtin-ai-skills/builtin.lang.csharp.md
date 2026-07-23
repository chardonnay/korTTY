---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.csharp
kortty-builtin-version: 1
kortty-builtin-topics: [c#, csharp]
name: "C# (.NET)"
description: "Conventions the assistant applies when writing C# code: comment style, robustness, pitfalls, and secure patterns."
tags: [c#, csharp, dotnet, .net, nuget, asp.net]
enabled: true
target: BOTH
---
# C#/.NET Best Practices

When generating or reviewing C# source, .NET project files, or ASP.NET configuration, apply the rules below.

## C#/.NET Comments

- Comment to explain why — intent, trade-offs, and non-obvious constraints — never to restate what the code already expresses.
- Write XML doc comments (`///`) on every public type and member: `<summary>`, plus `<param>`, `<returns>`, and `<exception>` where applicable; enable `GenerateDocumentationFile` so gaps surface as warnings.
- Document contracts and invariants: nullability expectations beyond annotations, thread-safety, units, valid ranges, and disposal ownership (who calls `Dispose`).
- Tag workarounds as `// TODO(name): reason` so they remain searchable and attributable.
- Note cancellation and exception behavior of async APIs in `<remarks>` when it is not obvious from the signature.

## Robust C#/.NET Code

- Enable nullable reference types (`<Nullable>enable</Nullable>`) and honor the annotations; resolve warnings instead of suppressing them with `!`.
- Use async/await all the way up the call chain; suffix async methods with `Async` and accept a `CancellationToken`, forwarding it to every awaited call.
- Throw specific exception types with messages naming the offending argument (`ArgumentNullException.ThrowIfNull`, `ArgumentOutOfRangeException`); never swallow an exception — log with context or rethrow with bare `throw;` to preserve the stack.
- Validate inputs at public boundaries before doing any work, and fail fast rather than propagating half-initialized state.
- Wrap every `IDisposable`/`IAsyncDisposable` in a `using` declaration or `await using`; never rely on finalizers for cleanup.
- On unrecoverable errors release resources, log diagnostic state, and return a nonzero exit code (console apps) or let the host's error pipeline handle it (ASP.NET).

## Avoid in C#/.NET

- Never block on async code with `.Result`, `.Wait()`, or `GetAwaiter().GetResult()`; await it — blocking deadlocks UI and legacy ASP.NET synchronization contexts.
- Never use `async void` outside event handlers; return `Task` so exceptions remain observable.
- Never catch `Exception` broadly just to continue; catch the specific failures you can handle.
- Never reach for mutable public static state; inject dependencies and pass state explicitly.
- Never concatenate strings in loops; use `StringBuilder` or `string.Join`.
- Never compare strings with `==` when culture or case matters; use `string.Equals` with an explicit `StringComparison`.

## C#/.NET Security

- Query databases only with parameterized commands (`SqlParameter`) or LINQ through EF Core; never interpolate user input into raw SQL strings.
- Never use `BinaryFormatter`, `SoapFormatter`, or `NetDataContractSerializer` on untrusted data; use `System.Text.Json` with strict options.
- Keep secrets out of `appsettings.json` in source control; use the user-secrets store in development and environment variables or a vault in production.
- Start external processes with `ProcessStartInfo.ArgumentList` and `UseShellExecute = false`; never build a shell command string from input.
- Pin NuGet package versions and scan for known CVEs (`dotnet list package --vulnerable`, Dependabot) before shipping.
- Use `RandomNumberGenerator` for tokens and salts, never `System.Random`; canonicalize file paths (`Path.GetFullPath` plus prefix check) to block traversal.
