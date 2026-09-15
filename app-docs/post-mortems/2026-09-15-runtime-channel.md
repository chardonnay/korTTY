# Post-mortem: the llama.cpp runtime channel answered HTTP 404

**Date:** 2026-09-15 · **Status:** resolved · **Severity:** high (a security mechanism was out of service)

## Summary

korTTY reads its signed llama.cpp runtime index from
`…/kortty-llama-runtimes/releases/latest/download/runtime-index-v1.json`. GitHub's
`releases/latest` is repository-wide, and the runtimes repository publishes two channels from
it. An MLX release took the pointer, and because MLX releases do not carry that index, every
runtime update check ran into a 404.

## Impact

Every shipped build from v2.6.0 (2026-07-23) on queries the channel — at **every application
start**.

- a FAILED status plus an ERROR with a stack trace in `kortty.log`
- no runtime updates
- **security-relevant:** the signed index is also the revocation channel, so a runtime found
  unsafe could not have been withdrawn from the field during the window. Nothing was pending:
  `revokedRuntimeIds` is empty.

## Timeline (UTC)

| Time | Event |
| --- | --- |
| 2026-07-16 21:07 | `llama-b10025-kortty2` — pointer correct, channel healthy |
| 2026-07-17 11:47 | `mlx-0.31.3-kortty1` published without `--latest=false` → **takes the pointer**, llama index 404s |
| 2026-07-17 11:56 | `4fb8a46` names the 404 verbatim and sets `--latest=false` for future MLX releases |
| 2026-07-17 12:18 | `mlx-0.31.3-kortty2` and `mlx-stable` — published with the fix, they do not take the pointer |
| 2026-07-23 | v2.6.0 ships the channel URL to users for the first time |
| 2026-09-14 18:07 | promoting `llama-b10952-kortty2` creates a newer llama release → the pointer moves back, the 404 ends |
| 2026-09-15 | cause investigated, guards built and merged |

## Cause

Two channels share a repository-wide pointer that GitHub awards by release date. Whoever
publishes last wins it, regardless of which assets that release carries.

## Why the July fix was not enough

`--latest=false` stops a *future* MLX release from taking the pointer. It does not give back a
pointer already taken. The fix closed the gate behind the horse.

## Why nobody noticed

Nothing in either repository ever fetched the URLs the app fetches. Every workflow stayed green
while the channel was dead. The end of the outage was chance as well — an unrelated promotion,
not a fix.

## Resolution and follow-up

| Action | Where |
| --- | --- |
| July MLX releases marked as pre-releases, so they can never win the pointer again | runtimes repository |
| Every promotion carries both signed indexes | `2a9ec97`, korTTY #336 |
| Post-promotion smoke test: `releases/latest` must resolve to the new release and serve every index it carries byte-identically | runtimes #2 |
| Weekly canary probes both channels including their Ed25519 signatures and opens an issue on failure | korTTY #338, `.github/workflows/runtime-channel-canary.yml` |

## Open question

How long the outage actually lasted cannot be reconstructed: GitHub does not expose which
release held the `latest` pointer historically, and correcting it by hand leaves no trace. If it
was never corrected, the outage ran from 2026-07-17 to 2026-09-14 and affected all thirteen
releases from v2.6.0 through v2.17.0. What is documented is that it was real on 2026-07-17. Only
`kortty.log` can settle it — search for `Runtime index request failed with HTTP 404`.

## Lesson

A channel nobody queries the way the client queries it is not a monitored channel. Both new
checks do exactly that and nothing else: they fetch the real URLs and verify the signature the
way the app does.
