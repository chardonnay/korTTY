---
kortty-ai-skill: 1
kortty-builtin-id: builtin.observability.logstash
kortty-builtin-version: 1
kortty-builtin-topics: [logstash]
name: "Logstash"
description: "Conventions the assistant applies when writing Logstash configuration: comment style, robustness, pitfalls, and secure patterns."
tags: [logstash, logstash.conf, grok, elk]
enabled: true
target: BOTH
---
# Logstash Best Practices

When generating or reviewing Logstash pipeline configuration or commands, apply the rules below.

## Logstash Comments

- Comment the why, not the what: explain why a field is renamed (a downstream dashboard contract), not that `mutate` renames it.
- Start every pipeline file with a header comment describing the flow from input through filter to output, the expected event shape, and the owning team.
- Precede every grok or dissect pattern with a commented sample log line it must match; patterns without samples are unmaintainable.
- Document contracts and invariants: field names and types downstream consumers rely on, index naming conventions, and timezone assumptions baked into date parsing.

## Robust Logstash Configuration

- Guard filters with field-existence conditionals such as `if [user_agent]` so events missing a field are not mis-tagged or mangled.
- Handle `_grokparsefailure` and `_dateparsefailure` explicitly: route tagged events to a dead-letter index or file output for inspection; never let them vanish.
- Set an explicit `timezone` on every date filter; source hosts disagree, and silent UTC assumptions shift events by hours.
- Enable persistent queues for delivery-critical pipelines (`queue.type: persisted` with a sized `queue.max_bytes`) and the dead letter queue for outputs that reject events.
- Validate with `logstash --config.test_and_exit` before deploy, then test with representative events, including malformed ones.
- Prune fields nobody queries, via `prune` or `mutate` remove_field, to bound event size and index mapping growth.

## Avoid in Logstash

- Never use `.*` or mid-pattern `GREEDYDATA` in grok; catastrophic backtracking stalls whole pipelines. Anchor patterns with `^` and prefer dissect for fixed-delimiter formats.
- Never reach for the ruby filter when mutate, dissect, kv, or translate can do the job; ruby is a last resort and must rescue its own exceptions.
- Never drop parse failures silently, e.g. matching `_grokparsefailure` into a `drop` filter; dead-letter them so format drift gets detected.
- Never compare numeric values as strings; convert with `mutate` convert first, then compare typed values.
- Never run unbounded `kv` or json parsing over attacker-influenced text; allowlist keys to prevent field explosion and index mapping blowup.
- Never build one mega-pipeline for unrelated flows; split them via pipelines.yml so a stall in one flow cannot starve the rest.

## Logstash Security

- Keep credentials in the Logstash keystore (`logstash-keystore`) or environment variables; never plaintext in .conf files.
- Enable TLS with certificate verification on inputs and outputs; never disable verification to silence handshake errors — fix the CA chain instead.
- Restrict permissions on config and pipeline directories to 0640 or tighter; configs reveal infrastructure details and may embed sensitive patterns.
- Scrub or pseudonymize PII before indexing: `mutate` gsub to redact, or the fingerprint filter with keyed SHA-256 to correlate without storing raw emails, IPs, or tokens.
- Treat event content as untrusted input: never interpolate event fields into ruby filter code, exec outputs, or scripted queries; injection through crafted log lines is real.
- Give outputs least-privilege Elasticsearch credentials scoped to their target indices, never superuser.
