---
kortty-ai-skill: 1
kortty-builtin-id: builtin.lang.go
kortty-builtin-version: 1
kortty-builtin-topics: [go, golang]
name: "Go (Golang)"
description: "Conventions the assistant applies when writing Go (Golang) code: comment style, robustness, pitfalls, and secure patterns."
tags: [golang, goroutine, go.mod, gofmt]
enabled: true
target: BOTH
---
# Golang Best Practices

When generating or reviewing Go (Golang) code or module configuration, apply the rules below.

## Golang Comments

- Comment the why — invariants, concurrency assumptions, protocol quirks — never a restatement of the statement below it.
- Write a doc comment on every exported identifier, phrased as a full sentence that starts with the identifier name ("ParseConfig reads ...").
- Give every package a package comment (in `doc.go` for larger packages) stating its purpose and intended use.
- Document contracts the type system cannot express: whether nil receivers or arguments are valid, goroutine-safety, whether a returned slice aliases internal state, ordering guarantees.
- Explain every non-obvious mutex or channel arrangement: what the lock protects, who closes the channel and when.

## Robust Golang Code

- Check every error; wrap with `fmt.Errorf("doing X: %w", err)` to add context while preserving the chain for unwrapping.
- Inspect errors with `errors.Is`/`errors.As`, never by matching message strings.
- Reserve `panic` for programmer bugs; return errors for all expected failures, and `recover` only at top-level goroutine boundaries to fail safely with a logged, nonzero exit.
- Place `defer` immediately after acquiring a resource — `Close`, `Unlock`, transaction rollback — so cleanup runs on every return path.
- Accept `context.Context` as the first parameter of any blocking or long-running function and honor cancellation in loops and I/O.
- Know how every goroutine exits before starting it — a context, a closed channel, or a `WaitGroup`; a goroutine with no exit path is a leak.
- Validate external input at boundaries; cap sizes and counts received from the network before allocating from them.

## Avoid in Golang

- Never discard an error with `_` unless a comment proves it cannot matter; handle or return it.
- Never share memory across goroutines without synchronization; communicate over channels or guard with a mutex, and verify with `go test -race`.
- Never launch fire-and-forget goroutines from library code; accept a context or expose Start/Stop so the caller controls the lifetime.
- Never put complex logic or mutable global state in `init()`; use explicit constructor functions.
- Never store a `Context` inside a struct; pass it explicitly per call.
- Never hand-format or skip static checks; `gofmt` and `go vet` are mandatory, `staticcheck` strongly recommended.

## Golang Security

- Never build shell commands by string concatenation; use `exec.CommandContext` with discrete arguments and no shell.
- Use parameterized database queries with placeholders; never assemble SQL with `fmt.Sprintf`.
- Keep secrets out of source, logs, and error text; load them from the environment or a secret store, and redact them when logging structs.
- Compare secrets with `crypto/subtle.ConstantTimeCompare`, never `==`; use `crypto/rand` for anything security-relevant, never `math/rand`.
- Clean and containment-check user-supplied file paths against a base directory to block traversal.
- Set explicit timeouts on every `http.Server` and outbound client, and run `govulncheck` to keep dependencies patched.
