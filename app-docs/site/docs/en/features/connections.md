# Connections

korTTY manages SSH and Mosh connections through three entry points: **Quick
Connect**, the **Connection Manager**, and saved **Projects**.

![Connection flow](../assets/diagrams/connection-flow.svg)

## Quick Connect

Open with ++ctrl+k++ (or **Connections → Quick Connect…**). Enter host, port,
username and authentication, and connect without saving. Frequently used
connections appear as quick buttons; a live search filters them.

## Connection Manager

**Connections → Manage Connections…** opens a searchable tree of saved
connections (optionally grouped). From here you create, edit, duplicate, delete,
import and export connections.

## Creating / editing a connection

The connection editor has these tabs:

| Tab | Contents |
| --- | --- |
| Connection | Host, port, username, protocol (SSH/Mosh), authentication (password / key / keyboard-interactive) |
| Terminal Settings | Per-connection colors, font, ANSI/TrueColor handling, terminal effect |
| SSH Tunnels | Local / remote / dynamic port forwarding |
| Jump Server | Bastion-host chaining |
| Terminal Logging | Per-connection log format and destination |
| Window Geometry | Saved size/position for this connection |

## Protocols

=== "SSH"
    Standard SSH via Apache MINA SSHD. Supports password, public-key and
    keyboard-interactive authentication, keep-alive, and OSC 8 clickable
    hyperlinks.

=== "Mosh"
    Roaming, latency-friendly Mosh transport (mosh4j). The Mosh backend is
    bundled in native builds; existing connections need no migration.

## Tunnels & jump servers

- **SSH tunnels** — forward ports through the connection: **local** (`-L`),
  **remote** (`-R`) or **dynamic / SOCKS** (`-D`).
- **Jump server (bastion)** — route the connection through one or more
  intermediate hosts.

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

## Import from other clients

**Connections → Import…** reads connection files from **MTPuTTY**, **MobaXterm**
and **PuTTY Connection Manager**, with group filtering and credential handling.

!!! note "More to come"
    This page is part of the scaffolded guide. The full feature library — SFTP,
    snippets, JobScheduler, AI assistant & tools, terminal recording, security,
    and the complete settings tables — is being filled in next.
