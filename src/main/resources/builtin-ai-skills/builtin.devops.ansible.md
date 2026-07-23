---
kortty-ai-skill: 1
kortty-builtin-id: builtin.devops.ansible
kortty-builtin-version: 1
kortty-builtin-topics: [ansible]
name: "Ansible"
description: "Conventions the assistant applies when writing Ansible configuration: comment style, robustness, pitfalls, and secure patterns."
tags: [ansible, playbook, jinja2, ansible-lint]
enabled: true
target: BOTH
---
# Ansible Best Practices

When generating or reviewing Ansible playbooks, roles, inventories, or Jinja2 templates, apply the rules below.

## Ansible Comments

- Give every play and task a descriptive `name:`; it is the primary self-documentation shown in run logs.
- Comment the why, not the what: explain host targeting choices, ordering constraints, and vendor quirks; never restate what the module invocation already says.
- Document role contracts: every variable in `defaults/main.yml` gets a comment stating meaning, type, and valid values, so consumers know the interface.
- State invariants where they matter: which facts a role requires, which tags it supports, whether it is safe to re-run mid-failure, and what it deliberately does not manage.
- Justify every `shell`/`command` task in a comment: why no module fits, and what makes the task idempotent.

## Robust Ansible Configuration

- Prefer purpose-built modules over `shell`/`command`; when raw commands are unavoidable, make them idempotent with `creates`/`removes` and define `changed_when` and `failed_when` explicitly.
- Guarantee idempotency: a second run of the same playbook against unchanged hosts must report zero changed tasks.
- Validate inputs up front with `ansible.builtin.assert` so missing or malformed variables fail fast before any host is touched.
- Structure error paths with `block`/`rescue`/`always`; put cleanup and service-restore logic in `rescue`/`always` so partial failures leave hosts consistent.
- Keep tasks check-mode compatible (`--check`); mark unavoidable exceptions with `check_mode: false` and a comment.
- Use handlers with `notify` for restarts so services bounce only when configuration actually changed.
- Keep everything `ansible-lint` clean and syntax-checked in CI before it reaches an inventory.

## Avoid in Ansible

- Never use blanket `ignore_errors: true`; handle expected failures with `failed_when` conditions or `rescue` blocks instead.
- Never install with unpinned `state: latest` in production plays; pin explicit versions so runs are reproducible.
- Never edit files with stacked regex `lineinfile`/`replace` tasks; own the whole file with `template` and make `lineinfile` the documented last resort.
- Never parse command output with fragile string slicing; register results and read structured module return values.
- Never hard-code hostnames, IPs, or environment names in tasks; drive them from inventory and `group_vars`/`host_vars`.
- Never share mutable state through global `set_fact` sprawl; scope variables to roles and pass them explicitly.

## Ansible Security

- Encrypt every secret at rest with `ansible-vault` (or an external lookup to a secret manager); plaintext credentials never belong in playbooks, inventories, or repositories.
- Set `no_log: true` on every task that touches a secret so values cannot leak into logs, callbacks, or registered-variable dumps.
- Escalate with least privilege: apply `become: true` per task or block that needs it, never blanket-root an entire play.
- Keep `validate_certs: true` on `uri`, `get_url`, and API modules; fix the certificate chain instead of disabling verification.
- Treat templated user input reaching `shell`, `command`, or SQL as an injection vector; quote with the `| quote` filter and prefer module arguments over interpolated command strings.
- Pin collection and role versions in `requirements.yml` and install only from trusted sources.
