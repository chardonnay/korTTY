---
title: Policy configuration
---

# Policy configuration

In managed environments an administrator can restrict or preconfigure korTTY through a single TOML file, `kortty-policy.toml`, placed in the installation directory. Users cannot change or bypass it: the file is only read from the admin-writable installation folder, locked settings appear grayed out with a "Managed by your organization" hint, and hand-editing `global-settings.xml` is undone on the next load. This chapter explains where the file lives, how rules target users, groups and servers, and documents every available parameter with examples.

!!! note
    Without a `kortty-policy.toml` nothing changes — korTTY behaves exactly as before. The shipped `policy/kortty-policy.toml.example` is a fully commented template with everything disabled.

## File location and security model

korTTY loads the policy exclusively from the `policy/` folder of its installation directory — the folder that also holds the application jar — and never from `~/.kortty/` or any other user-writable location. A ready-to-copy template ships as `policy/kortty-policy.toml.example`; copy it to `kortty-policy.toml` in the same folder and restart korTTY (there is no hot reload).

| Platform | Policy file location |
| --- | --- |
| macOS | `/Applications/KorTTY.app/Contents/app/policy/kortty-policy.toml` |
| Windows | `C:\Program Files\KorTTY\app\policy\kortty-policy.toml` |
| Linux (deb/rpm) | `/opt/kortty/lib/app/policy/kortty-policy.toml` |

The enforcement model relies on the operating system's file permissions: the installation directory must be writable only by administrators, which is the default for the locations above. korTTY additionally logs a warning when the active policy file is writable by the current user. During development (never in a packaged installation) a policy can be tested with `-Dkortty.policy.file=/path/to/policy.toml`.

If the file exists but cannot be parsed or contains an invalid value, korTTY starts in a fail-safe lockdown: every policy-controllable feature is denied, no server connection is allowed, and a startup dialog names the file and the exact error position. A typo can therefore never silently disable enforcement. One invalid rule rejects the entire file; unknown keys only produce log warnings, so a policy written for a newer korTTY does not lock out users of an older version.

## Users, groups and rule precedence

Rules target the current OS login name (matched lowercase). Group membership comes from two sources at once: groups defined in the policy's `[groups]` table, and the user's OS-level group memberships. On domain-joined Windows machines the OS groups include Active Directory groups, so rules can target AD groups directly — both fully qualified (`ACME\Operations`) and as the bare name (`operations`) — without any directory server configuration.

Each `[[rule]]` block optionally names `users` and/or `groups`; a rule naming neither applies to every user. Per setting, the most specific tier that sets it wins: **user beats group beats everyone**. That is the familiar group-policy pattern — lock everything down in a baseline rule for everyone, then relax individual settings for a trusted group. When several rules of the same tier set the same key, the most restrictive value applies (`deny` over `read-only` over `confirm` over `allow`); for server rules, a connection must pass every applicable restriction of the winning tier. The order of rules in the file has no significance.

```toml
[meta]
schema-version = 1
organization = "ACME Corp"

[groups]
devs = ["alice", "bob"]

[[rule]]                              # applies to ALL users
name = "company-baseline"
  [rule.features]
  ai-agent = "deny"

[[rule]]                              # group tier: relaxes the baseline for ops
name = "ops-exception"
groups = ["ops", "ACME\\Operations"]  # policy group OR OS/AD group
  [rule.features]
  ai-agent = "allow"
  ai-agent-execution = "confirm"

[[rule]]                              # user tier: beats eve's group
users = ["eve"]
  [rule.features]
  ai-agent = "deny"
```

## Server access control

The `[rule.servers]` table restricts which servers a user may connect to — as an allow-list (`mode = "allow"`: only listed servers are reachable) or a deny-list (`mode = "deny"`: listed servers are blocked). The restriction is enforced centrally for every connection path: saved connections, QuickConnect, session restore, SFTP, teamwork-shared connections, AI swarm targets and scheduled jobs, including the jump host of a connection. Blocked connections stay visible in the connection manager but are grayed out with a lock marker, and any connect attempt shows a clear policy message.

Patterns match the host string exactly as configured in the connection — korTTY never resolves DNS for policy checks, so host names and IP addresses are separate namespaces: if a server is reachable both ways, list both.

| Pattern form | Example | Matches |
| --- | --- | --- |
| Exact host name | `db01.acme.com` | that host, case-insensitive, any port |
| Host-name glob | `*.prod.acme.com`, `web-??.acme.com` | `*` = any characters, `?` = one character |
| Host with port | `vault.acme.com:22` | only connections to that port |
| Single IP address | `192.168.10.42`, `2001:db8::1` | that address (IPv4 or IPv6) |
| IP with port | `192.168.10.42:22`, `[2001:db8::1]:22` | IPv6 ports require the bracket form |
| CIDR network | `10.99.0.0/16`, `2001:db8::/32` | every address in the network |
| IP range | `10.20.0.100-10.20.0.199` | inclusive from–to range |

```toml
[[rule]]
  [rule.servers]
  mode  = "deny"
  hosts = ["*.prod.acme.com", "vault.acme.com:22", "192.168.10.42", "10.99.0.0/16", "10.20.0.100-10.20.0.199"]
```

## Parameter reference

### `[meta]`

| Key | Type | Values | Required | Effect |
| --- | --- | --- | --- | --- |
| `schema-version` | integer | `1` | yes | Rejected (lockdown) when this korTTY does not understand the version |
| `organization` | string | free text | no | Shown in every "Managed by your organization" hint and dialog |

### `[groups]`

| Key | Type | Effect |
| --- | --- | --- |
| `<group-name>` | array of user names | Defines a policy group; rules referencing the name target its members. OS/AD groups match automatically and need no entry here |

### `[[rule]]` scope

| Key | Type | Effect |
| --- | --- | --- |
| `name` | string | Optional label used in log messages |
| `users` | array of user names | Targets the listed OS login names (user tier) |
| `groups` | array of group names | Targets members of policy groups and/or OS/AD groups (group tier); a rule with neither `users` nor `groups` applies to everyone |

### `[rule.servers]`

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `mode` | string | `allow`, `deny` | Allow-list or deny-list semantics for `hosts` |
| `hosts` | array of patterns | see table above | Non-empty list of server patterns |

### `[rule.features]`

| Key | Type | Values | Restricts |
| --- | --- | --- | --- |
| `ai` | string | `allow`, `deny` | Master switch: `deny` disables every AI capability at once |
| `ai-agent` | string | `allow`, `deny` | AI Agent (menu, terminal context menu, keyboard shortcut, headless job runs) |
| `ai-chat` | string | `allow`, `deny` | AI chat, Saved Chats and the terminal-selection AI actions |
| `ai-swarm` | string | `allow`, `deny` | AI Swarm, including scheduled swarm jobs |
| `ai-planning` | string | `allow`, `deny` | AI Planning |
| `teamwork` | string | `allow`, `deny` | Teamwork shared-connections sync (service is not started, menu locked) |
| `plugins` | string | `allow`, `deny` | Plugin loading and the Plugins menu (e.g. terminal effects) |
| `session-journal` | string | `allow`, `deny` | The [session journal](../features/session-journal.md): capture, journal bar, manager, viewer and exports. Not chained to `ai` — with AI denied the journal still records raw activity |
| `ai-agent-execution` | string | `allow`, `confirm`, `read-only` | `confirm` forces interactive approval of every mutating command set and defeats the auto-approve option; `read-only` lets the agent plan and chat but never execute commands |

### `[rule.security]`

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `require-master-password` | boolean | `true` | Forces the master-password gate at startup; the setting is locked |
| `enforce-host-key-check` | boolean | `true` | SSH host key verification cannot be disabled anywhere — globally, per group or per connection |
| `allow-telemetry` | boolean | `false` | Forbids anonymous usage statistics |
| `allow-terminal-recording` | boolean | `false` | Forbids terminal session recording, including the session-level toggle |
| `clipboard-mode` | string | `system`, `internal` | `internal` confines korTTY to its own in-memory clipboard — see below |

### `[rule.teamwork]`, `[rule.snippets]`, `[rule.ai-profiles]`

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `allow-custom-sources` | boolean | `false` | Users cannot add teamwork sources; only `[[teamwork-source]]` entries remain |
| `allow-custom-script-headers` | boolean | `false` | Users cannot create script headers; only `[[script-header]]` entries remain |
| `allow-create` | boolean | `false` | Users cannot create AI profiles (buttons and wizard are locked) |
| `allow-edit` | boolean | `false` | Users cannot edit their existing AI profiles either |

### `[rule.ai-runtime]`

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `allow-runtime-downloads` | boolean | `false` | No llama.cpp/MLX runtime downloads or update checks |
| `allow-model-downloads` | boolean | `false` | The Hugging Face model browser and downloads are disabled |
| `allow-user-models` | boolean | `false` | Only admin-provisioned `[[ai-runtime.model]]` entries can be loaded by embedded AI profiles |

### `[rule.updates]`

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `enabled` | boolean | `false` | Disables the automatic update check and the manual check in the About dialog |
| `feed-url` | string | http(s) URL | Update checks query this endpoint instead of GitHub; it must return the same JSON shape as the GitHub `releases/latest` API (`tag_name`, `assets[]` with `name` and `browser_download_url`) |

### `[rule.terminal]`

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `load-into-snippet-editor` | string | `allow`, `read-only`, `deny` | `read-only` keeps loading remote files into the snippet editor but forbids writing back to the target system; `deny` removes the feature entirely |

### `[rule.logging]`

korTTY rotates its log daily (`kortty.YYYY-MM-DD.log`); this table controls where the files go, how long they live, how they are formatted and how rotation is capped. Directory and retention are forced into the corresponding (then locked) user settings; format, compression and the rotation caps have no user setting and act directly. The configuration is applied before the very first log line of a start, so even startup logging follows the admin's scheme.

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `directory` | string | absolute or `~/`-relative path | Log directory (default `~/.kortty/logs`); the setting is locked |
| `retention-days` | integer | `0` = keep forever | Rotated logs older than N days are deleted; the setting is locked |
| `compress` | boolean | default `true` | Gzip rotated logs after one day |
| `format` | string | `text`, `json` | `json` writes one structured JSON object per event (logback JSON encoder — convenient for SIEM/central log collection) |
| `rotation-max-files` | integer | `0` = unlimited | Keep at most N rotated daily files |
| `rotation-total-size-mb` | integer | `0` = uncapped | Cap the total size of all rotated files (oldest deleted first) |

```toml
[[rule]]
  [rule.logging]
  directory = "/var/log/kortty"
  retention-days = 30
  compress = true
  format = "json"
  rotation-max-files = 14
  rotation-total-size-mb = 512
```

!!! note
    When several same-tier rules configure logging, each key resolves separately: shorter retention and tighter caps win, compression on wins, `json` wins over `text`. In practice, put the logging configuration into a single all-users rule. The chosen directory must be writable by the user running korTTY.

### `[rule.session-journal]`

Mandates for the [session journal](../features/session-journal.md). Forced values lock the corresponding controls in the journal options, the settings dialog and the connection editor with a "managed by your organization" hint.

| Key | Type | Values | Effect |
| --- | --- | --- | --- |
| `enforced` | boolean | `true` | A journal is written for **every** connection, regardless of the per-connection setting; users cannot stop it and the enable switch is locked on |
| `log-format` | string | `json` (default), `xml`, `yaml` | Forces the capture-log format for new journals |
| `ai-max-lines` | integer | `0` = context fill | Forces the AI evaluation window (max terminal lines per summary) |
| `storage-path` | string | absolute path | Forces the journal storage directory; the setting is locked |
| `allow-rename` | boolean | `false` | Journals cannot be renamed in the manager |
| `allow-delete` | boolean | `false` | Journals cannot be deleted in the manager |
| `name-template` | string | template | Initial journal title, with `{connection}`, `{host}`, `{user}`, `{date}` and `{time}` placeholders |
| `ai-title` | boolean | `true` | The closing AI title is generated regardless of the user setting |
| `ai-screenshot-analysis` | boolean | `true` / `false` | `true` forces the AI screenshot analysis on, `false` forbids it — including the manual per-screenshot run; the journal option is locked either way |
| `max-log-parts` | integer | ≥ 1 | Caps the number of rotated capture-log parts per journal; the effective limit is the minimum of this cap and the per-connection setting, and the connection editor's spinner is clamped to it |

```toml
[[rule]]
  [rule.features]
  session-journal = "allow"
  [rule.session-journal]
  enforced = true
  log-format = "json"
  storage-path = "/srv/audit/kortty-journals"
  allow-delete = false
  name-template = "{connection} {date} {time} ({user})"
```

!!! note
    `enforced` mandates capture, not AI: with AI denied or unavailable the enforced journal records raw activity entries. When several same-tier rules configure the journal, `enforced` and `ai-title` resolve to true if any rule sets them, `allow-rename`/`allow-delete` to false if any rule forbids them, `ai-screenshot-analysis` to off if any rule switches it off, the line cap resolves to the tighter value (`0` counts as unlimited), and `max-log-parts` resolves to the lower cap.

### `[[rule.session-journal.replace]]`

Automatic search-and-replace in every journal — the way to keep a whole category of secret out of the transcript, instead of relying on the user to notice it. Each entry is one rule, and a rule may use a regular expression.

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `pattern` | string | *required* | The text to find. With `regex` enabled it is a regular expression |
| `replacement` | string | `***` | The text that replaces each match. In a regex rule `$1` inserts a captured group |
| `regex` | boolean | `false` | Treats `pattern` as a regular expression |
| `ignore-case` | boolean | `false` | Matches every casing |
| `label` | string | – | Description for the administrator; korTTY only counts the rules in its UI hint |

```toml
[[rule]]
  [rule.session-journal]
  enforced = true

    [[rule.session-journal.replace]]
    pattern = "AKIA[0-9A-Z]{16}"
    replacement = "***AWS-ACCESS-KEY***"
    regex = true
    label = "AWS access keys"

    [[rule.session-journal.replace]]
    pattern = "(?i)bearer\\s+[A-Za-z0-9._-]{20,}"
    replacement = "Bearer ***"
    regex = true
    label = "Bearer tokens"

    [[rule.session-journal.replace]]
    pattern = "vpn.internal.acme.corp"
    replacement = "<internal-host>"
    ignore-case = true
```

The rules run **on the capture thread, before a line is written**, so a matching text never reaches the log file at all, and they are applied to AI summaries and user notes as well. Unlike the other keys, replacement rules are **merged across every matching rule and every tier** rather than the highest tier winning: more redaction is the more restrictive outcome, so a user- or group-specific rule can only add patterns, never switch the organization-wide ones off. Duplicate entries are collapsed.

!!! warning
    A `pattern` that is not a valid regular expression is a **policy error**, not a warning — the file is rejected. A rule that silently matches nothing is worse than one the administrator has to fix, because the redaction would appear to be in place.

!!! note
    Only journals written while the rule is in force are covered; existing journals are not rewritten retroactively. Users can clean those up with the viewer's Search & replace.

### Admin-provided objects

These top-level tables define objects that appear read-only for every user, marked "Provided by your organization". They are rebuilt from the policy on every start and are never written into the user's configuration files — removing them from the policy removes them from korTTY.

| Table | Keys | Notes |
| --- | --- | --- |
| `[[script-header]]` | `name`, `content` | Immutable script header in the snippet system's Script-Header category |
| `[[ai-profile]]` | `id`, `name`, `provider`, `endpoint`, `model`, `api-key-encrypted` | `id` must start with `policy-`; `provider` is one of `anthropic`, `openai-compatible`, `lm-studio`, `embedded-llama`, `embedded-mlx` (embedded providers read `model` as the local model id) |
| `[[ai-runtime.model]]` | `name`, `runtime`, `source` | `runtime` is `llama` or `mlx`; `source` is an absolute local/UNC path, or for GGUF models an http(s) URL that korTTY downloads once at startup |
| `[[teamwork-source]]` | `name`, `type`, `url` | `type` is `git` or `shared-file`; injected as a read-only teamwork source |

## Internal clipboard mode

With `clipboard-mode = "internal"` korTTY detaches from the operating system clipboard entirely and uses its own in-memory clipboard instead: text copied in another application cannot be pasted anywhere in korTTY, and text copied in korTTY never reaches the OS clipboard — while copy, cut and paste *within* korTTY keep working everywhere, because terminals, SFTP views, the snippet editor and all dialogs share the same internal buffer. Because this is pure application logic and no OS clipboard isolation API is involved, it behaves identically on macOS, Windows and Linux, including the X11 primary selection (middle-click paste): pasting middle-click content from other applications is blocked, middle-click paste of korTTY-internal copies keeps working.

The mode covers the terminal (shortcuts, context menu, middle-click), the code editor, all copy buttons, the `${clipboard}` snippet variable, and the copy/cut/paste shortcuts of plain input fields. Copying images (AI-generated pictures, diagram exports) is unavailable in internal mode, since an image can only be shared via the OS clipboard.

!!! note "Scope"
    The internal clipboard is a policy tool against casual data transfer through the clipboard, not a hard air gap: a user can still read text on screen. The right-click *Paste* entry of plain text fields is provided by the UI toolkit and may still access the OS clipboard — the keyboard shortcut and every korTTY-provided menu are covered.

## Encrypted API keys

An AI profile's API key never appears in plain text in the policy. The administrator encrypts it once from a terminal — the command prints a `kortty-enc:v1:` value for the `api-key-encrypted` key:

```bash
korTTY --encrypt-policy-value
```

Users see only "API key provided by your organization" in the profile; the key is decrypted in memory at the moment a request is made.

!!! warning "Security scope"
    The envelope uses AES-256-GCM with an application-wide key, so it protects against casual disclosure (shoulder surfing, config diffs, backups) and detects tampering — it is not hard secrecy, since anyone with the korTTY binary could recover the application key. The installation directory's OS permissions remain the actual security boundary; prefer per-user keys via the normal profile flow when that boundary is not enough.

## Troubleshooting

| Symptom | Cause and remedy |
| --- | --- |
| Startup dialog "Organization policy could not be loaded" | The policy file has a syntax error or invalid value; the dialog and the log name the exact position. korTTY stays in fail-safe lockdown until the file is fixed |
| Policy seems ignored | The file is not named `kortty-policy.toml`, not in the installation's `policy/` folder, or korTTY was not restarted. The log's startup lines state which policy file (if any) was loaded |
| A rule does not apply to a user | Rule scoping is lowercase OS login names; check `[groups]` membership and remember that a more specific tier (user > group > everyone) overrides less specific rules |
| Warning "policy file is writable by the current user" | The installation directory permissions are too open — the enforcement model relies on admin-only write access |
| Admin model does not appear | See the log: GGUF URL downloads happen in the background at startup, and registration requires an installed llama.cpp runtime; MLX sources must be local safetensors directories |
