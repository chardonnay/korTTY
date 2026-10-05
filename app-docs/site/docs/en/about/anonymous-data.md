---
title: Anonymous data for application optimization
---

# Anonymous data for application optimization

korTTY can collect **anonymous usage statistics** to help decide which features are worth improving and to find crashes and frequent errors. This is entirely optional and can be turned on or off at any time. On a first launch the checkbox in the master-password setup comes **pre-selected**, so confirming that dialog as-is starts collection — clear it there, or switch it off later, and nothing is collected.

![Anonymous telemetry consent and data flow](../assets/diagrams/telemetry-consent-flow.svg)

## Overview

* **Your decision, once.** Nothing is collected before you confirm. You are asked once, together with the master-password setup on first launch, where the checkbox is pre-selected; existing installations are asked once after unlocking, with a dialog that counts every dismissal as "no".
* **Anonymous.** No account, no login, and no persistent device identifier is transmitted.
* **Revocable.** You can change your decision at any time under **Settings → Privacy**. Turning it off stops collection immediately and discards any data that has not yet been sent.

## What is collected

| Data | Example |
| --- | --- |
| Event names | app start, feature used (e.g. a tool opened, a backup created) |
| Aggregate counts and flags | number of open terminal tabs, whether AI is enabled |
| App version | 2.5.1 |
| Operating system and version | macOS 15, Windows 11, Linux |
| App language | de, en |
| An anonymous session ID | a random number regenerated on every launch and after one hour of inactivity |

The session ID is **not** a persistent identifier: it is newly generated each time and cannot be traced back to you across launches.

### Terminal features

A few terminal features report how they are used, with flags, coarse counts and fixed names only. Counts are rounded down to 0, 1, 2, 3, 5, 10 or 20, so seven panes are reported as 5.

| Event | Sent when | Data |
| --- | --- | --- |
| `command_palette_used` | a row of the command palette runs | the kind of row (`action`, `tab`, `connection` or `snippet`) and whether the search started with a scope prefix such as `>` or `@` |
| `terminal_highlight_applied` | a pane starts showing another highlighting rule set | a built-in set's id, or `custom` or `none`, and where the choice came from (menu, shortcut, connection or default) |
| `multi_exec_changed` | you add panes to multi-exec, remove them or stop it | whether multi-exec is still on, and the rounded number of panes, tabs and windows taking part |
| `session_restored` | the previous session is opened again | the Session Restore setting (`ask`, `auto` or `off`), whether the menu, the startup bar or the automatic restore opened it, and the rounded number of windows and tabs |
| `session_isolation` | an isolated or incognito terminal session connects | the isolation level asked for (`none`, `process`, `sandbox`), the state it got (`none`, `process`, `sandboxed`, `degraded`), the operating system family and whether the tab is incognito |

The search text, the names of commands, tabs, connections, snippets and rule sets, the patterns, and anything typed into the panes are never sent. Changes on the settings pages are reported as the name of the setting and, for switches and choices, the new value; for the quick-select letters and patterns and for rebound shortcuts only whether you changed them.

### AI chat code blocks

| Event | Sent when | Data |
| --- | --- | --- |
| `ai_code_block_action` | **Insert** or **Run** on a code block of an AI chat is used | `insert` or `run`, and whether it ended `sent`, `cancelled` (the Run confirmation was declined) or `refused` (the policy, the block or the pane did not allow it) |

The block, its language, the command, the pane, the tab, the host and the reason for a refusal are never sent.

### Control API and MCP clients

When a program uses the [Control API](../reference/control-api.md), korTTY reports which methods are used and what kind of client used them. An AI assistant connected through `kortty-cli mcp` is also reported by tool and by how the call ended. Each event is sent at most once per method, or per tool and outcome, while korTTY runs; the number of calls goes into the periodic usage summary.

| Event | Sent when | Data |
| --- | --- | --- |
| `control_api_used` | a Control API method is used for the first time in this run | the method name, such as `pane.read`, and the kind of client: `cli` for `kortty-cli`, `mcp` for an MCP client, `other` for any other program |
| `mcp_tool_called` | an MCP client's call ends in a way not yet reported in this run | the tool (`pane_list`, `pane_read`, `pane_wait_output`, `tab_list`, `agent_list`, `pane_send_text`, `pane_run`, `pane_send_keys`, or `other`) and the outcome: `ok`, `refused` (switched off, not allowed or not possible in this pane), `denied` (you said no, or korTTY could not ask), `timeout` (nobody answered in time, or a wait ran out) or `failed` (any other error) |

The client's name, pane ids, tool arguments, the text read or typed and the answers you gave are never sent.

### SFTP transfers

When an SFTP Manager tab opens, one event says whether it shares a terminal's session. When an upload or download in the SFTP Manager finishes, one event says how it went. File counts are rounded down to 0, 1, 2, 5, 10, 50, 100 or 1000. When korTTY stops watching a file you edited in an external editor (also as root), one event says how the edit ended; upload counts are rounded down to 0, 1, 2, 5, 10 or 50.

| Event | Sent when | Data |
| --- | --- | --- |
| `sftp_opened` | an SFTP Manager tab opens | whether it uses a terminal pane's SSH session or a login of its own |
| `sftp_transfer_batch` | an upload or download batch (one button press or one drop) is finished | `upload` or `download`, the rounded number of files, whether it ended `done`, `partial`, `failed` or `cancelled`, whether a file continued a partial file, and how many files were copied at once |
| `sftp_remote_edit` | korTTY stops watching a file opened with **Edit in External Editor** or **Edit as Root (sudo)...** | `external` or `sudo`, whether it ended `stopped`, `conflict`, `disconnected`, `closed` or `failed`, and the rounded number of uploads |

File and folder names, paths, server names, editor commands, sudo passwords and file sizes are never sent.

### JobScheduler notifications

When the JobScheduler shows a desktop notification for a job run or sends one to a webhook target, one event says how it went.

| Event | Sent when | Data |
| --- | --- | --- |
| `job_notification_sent` | a run notification is shown on the desktop, or delivered, refused or blocked for one webhook target | `desktop` or `webhook`, the payload format (`slack`, `teams`, `generic` or `none`), whether it ended `ok`, `failed` or `blocked`, and the number of delivery attempts (0 to 3) |

Job and target names, webhook URLs and hosts, run status texts and anything of the run's output are never sent. A test sent with **Send test** is not counted.

## What is never collected

korTTY never transmits any of the following:

* Hostnames, IP addresses, usernames, or connection names and addresses
* File names, paths, or directory contents
* Snippet content, terminal output, or AI prompt and chat text
* Passwords, SSH keys, GPG keys, or API keys
* Error messages (only the type of error and the korTTY class where it occurred)

## Where the data goes

Usage statistics are processed by **[Aptabase](https://aptabase.com)**, an open-source, privacy-first analytics service. korTTY uses Aptabase's **EU region** (`eu.aptabase.com`), so the data is processed on servers located in the European Union in compliance with the **GDPR**. See the [Aptabase privacy policy](https://aptabase.com/legal/privacy) for details.

If no connection is available, events are cached locally in `~/.kortty` and sent later — including after a restart — so a temporary lack of connectivity does not lose or block anything. This offline cache holds only the same anonymous events; it is discarded if you opt out, and events older than three days are dropped.

## Why korTTY collects it

The goal is to make korTTY better with real, anonymous evidence instead of guesswork:

* **Prioritize features** that people actually use, and retire ones that nobody does.
* **Find crashes and frequent errors** so they can be fixed in the next release.
* **Measure whether releases improve stability** over time.

## Your choices

* **First launch:** the setup dialog for the master password includes the pre-selected checkbox and this information; clearing it before you click **Setup** declines.
* **Any time:** open **Settings → Privacy** to enable or disable collection. The same page links back to this chapter.
* **Turning it off** stops all collection immediately and discards data that has not yet been sent.

![Privacy settings tab](../assets/screenshots/settings/telemetry.png)

## Your consent record

Your decision and the date it was made are stored locally in `~/.kortty/global-settings.xml` (see [Configuration files](../reference/config-files.md)) as a record of consent. If a future korTTY version changes what is collected, you will be asked again so your choice always reflects the current scope.
