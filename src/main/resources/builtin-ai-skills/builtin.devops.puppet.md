---
kortty-ai-skill: 1
kortty-builtin-id: builtin.devops.puppet
kortty-builtin-version: 1
kortty-builtin-topics: [puppet]
name: "Puppet"
description: "Conventions the assistant applies when writing Puppet configuration: comment style, robustness, pitfalls, and secure patterns."
tags: [puppet, hiera]
enabled: true
target: BOTH
---
# Puppet Best Practices

When generating or reviewing Puppet manifests, modules, or Hiera data, apply the rules below.

## Puppet Comments

- Comment the why, not the what: explain policy decisions, ordering constraints, and platform quirks; never restate what a resource declaration already says.
- Document every class and defined type with puppet-strings comments: `@summary`, a `@param` tag per parameter, and `@example` usage where the interface is non-obvious.
- State contracts and invariants explicitly: which resources a class assumes exist, which facts it depends on, and what it refuses to manage.
- Justify every `exec` resource in a comment: why no native resource type or module fits, and what makes it idempotent.
- Remove stale comments when refactoring; a comment that contradicts the manifest is worse than none.

## Robust Puppet Configuration

- Prefer declarative resource types and well-maintained Forge modules; use `exec` only as a last resort, and always guard it with `creates`, `onlyif`, or `unless` so it is idempotent.
- Guarantee idempotency overall: a second agent run against an unchanged system must report zero changes.
- Type every class parameter with Puppet data types (`String[1]`, `Enum`, `Stdlib::Absolutepath`, `Optional[...]`) so bad input fails at compile time.
- Fail early with explicit `fail()` for unsupported OS families or invalid input, rather than letting a partial catalog apply.
- Declare relationships explicitly with `require`, `before`, `notify`, and `subscribe` (or chaining arrows); never rely on evaluation-order luck.
- Keep code clean under `puppet-lint` and `pdk validate`, and compile-test catalogs with rspec-puppet before shipping.

## Avoid in Puppet

- Never embed site data (hostnames, ports, package versions, file paths) in manifests; look it up through Hiera with class parameters as the interface.
- Never hard-code node names or environment names inside shared modules; keep modules generic and drive differences from Hiera hierarchy levels.
- Never use `exec` with `curl | sh` style installers; package software through `package` resources or a proper repository resource.
- Never manage the same resource from two classes; extract a single owning class and depend on it.
- Never use global variables or inherit node scope implicitly; pass everything as typed parameters.
- Never mask failures with `noop` or ignored `exec` return codes in production code; fix the resource so success is verifiable.

## Puppet Security

- Never place plaintext secrets in manifests, templates, or Hiera YAML; encrypt them with hiera-eyaml or fetch them from an external secret backend.
- Wrap secret values in the `Sensitive` data type so they are redacted from logs, reports, and diffs.
- Set explicit, least-privilege `owner`, `group`, and `mode` on every `file` resource that touches credentials or keys; never default to world-readable.
- Validate and constrain any value that reaches `exec` command strings or templates; treat interpolated input as an injection vector.
- Pin module versions in the Puppetfile and source modules only from trusted namespaces; review third-party code before first use.
- Do not weaken agent security to make runs pass: keep certificate verification on and never enable blanket certificate autosigning.
