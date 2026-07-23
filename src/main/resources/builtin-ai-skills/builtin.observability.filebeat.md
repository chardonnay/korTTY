---
kortty-ai-skill: 1
kortty-builtin-id: builtin.observability.filebeat
kortty-builtin-version: 1
kortty-builtin-topics: [filebeat]
name: "Filebeat"
description: "Conventions the assistant applies when writing Filebeat configuration: comment style, robustness, pitfalls, and secure patterns."
tags: [filebeat, filebeat.yml, beats]
enabled: true
target: BOTH
---
# Filebeat Best Practices

When generating or reviewing Filebeat configuration or commands, apply the rules below.

## Filebeat Comments

- Comment the why, not the what: why `close.on_state_change.inactive` is 10m (logs rotate every 5m), not that it closes files.
- Start every filebeat.yml or module file with a header comment: purpose, expected log sources, target output, and owning team.
- Comment each input and each processor block with its purpose and the log source/format it expects, e.g. `# nginx access logs, JSON lines, hourly rotation`.
- Document contracts and invariants: field names downstream pipelines depend on, index or data-stream naming assumptions, and that filestream `id` values are registry state and must never be renamed casually.

## Robust Filebeat Configuration

- Give every filestream input a unique, stable `id`; missing or duplicate ids corrupt registry state and cause duplicated or lost events.
- Anchor multiline patterns with `^` and test them against real stack traces; cap with `multiline.max_lines` and a timeout so a broken pattern cannot glue unrelated lines forever.
- Tune close and clean options (`close.on_state_change.inactive`, `clean_inactive`, `clean_removed`) to the actual rotation scheme; defaults on high-churn directories leak open file handles and grow the registry without bound. Keep `clean_inactive` larger than `ignore_older`.
- Validate before deploy with `filebeat test config` and `filebeat test output`; treat any failure as blocking.
- Handle backpressure with queue sizing (`queue.mem.events`, or the disk queue for spiky sources) plus output retry/backoff so events wait instead of dropping.
- When decoding JSON in an input or `decode_json_fields`, keep the raw message and tag decode failures so malformed lines stay inspectable.

## Avoid in Filebeat

- Never use the deprecated `log` input for new work; use `filestream` with an explicit `id` instead.
- Never write globs that also match rotated files (`*.log*` catching `app.log.1`), which re-ships old data; match only live files and let rotation options handle the rest.
- Never use unanchored or greedy multiline regexes; anchor at line start and test against captured samples.
- Never absorb backpressure by shrinking queues or ignoring output errors; size queues and fix the slow output.
- Never install an arbitrary agent version; pin a Filebeat version compatible with the target Elasticsearch or Logstash cluster and upgrade deliberately.
- Never debug by pointing a second Filebeat at the same paths with the same data directory; give it a separate `path.data`.

## Filebeat Security

- Store credentials in the Filebeat keystore (`filebeat keystore add`) and reference them as `${VAR}`; never put passwords, tokens, or API keys in plaintext filebeat.yml.
- Enable TLS to every output with `ssl.verification_mode: full`; never ship with verification set to `none`.
- Remove PII and secrets before shipping, using `drop_fields`, `rename`, or script processors for tokens, cookies, emails, and internal hostnames; filtering after indexing is too late.
- Use least-privilege Elasticsearch API keys scoped to the publishing role for outputs, never a superuser account.
- Restrict config file permissions to 0600 and run Filebeat with only the privileges needed to read its sources, not blanket root.
