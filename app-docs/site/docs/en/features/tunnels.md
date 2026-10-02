---
title: SSH Tunnels (Port Forwarding)
---

# SSH Tunnels (Port Forwarding)

SSH tunnels securely forward traffic between local and remote ports through an encrypted SSH connection. korTTY supports three tunnel types: local port forwarding, remote port forwarding, and dynamic port forwarding (SOCKS proxy). They work like the `-L`, `-R` and `-D` options of OpenSSH and run over the login session of the terminal tab, so they need no second login.

## Configuring Tunnels

1. Open a connection for editing: **Connections > Manage Connections** → select the connection → **Edit**.
2. Navigate to the **SSH Tunnels** tab.
3. Click **Add** and configure the tunnel.

### Tunnel Configuration Fields

| Field | Local (`-L`) | Remote (`-R`) | Dynamic (`-D`) |
|-------|--------------|---------------|----------------|
| **Type** | `LOCAL` | `REMOTE` | `DYNAMIC` |
| **Local host** | Address the listener binds to on your computer (`localhost` keeps it private) | Host your computer forwards the connections to | Address the SOCKS proxy binds to on your computer |
| **Local port** | Port that listens on your computer | Port of the service on your computer | Port of the SOCKS proxy |
| **Remote host** | Target host, as seen from the SSH server | Address the SSH server listens on (default `localhost`) | Not used |
| **Remote port** | Target port | Port the SSH server listens on | Not used |
| **Description** | Optional label for the tunnel | Optional label | Optional label |
| **Enable tunnel** | Only enabled tunnels are opened | Only enabled tunnels are opened | Only enabled tunnels are opened |

Multiple tunnels can be configured per connection. Disabled tunnels remain in the configuration but are not opened when the connection connects.

## Tunnel Types

### Local Port Forwarding (`-L`)

Forward a local port to a remote service through the SSH tunnel.

```
Your machine:8080  -->  SSH Server  -->  database-server:5432
```

**Example use case:** Access a remote database server that is not directly reachable from your machine.

- **Local host:** `localhost`
- **Local port:** `8080`
- **Remote host:** `database-server` (or IP)
- **Remote port:** `5432`

Once connected, connect to the remote database using `localhost:8080` on your machine.

### Remote Port Forwarding (`-R`)

Make a local service accessible on the SSH server.

```
SSH Server localhost:9090  -->  Your machine:3000
```

**Example use case:** Let a program on the SSH server reach a development server on your machine.

- **Remote host:** `localhost` (the address the SSH server listens on)
- **Remote port:** `9090` (the port the SSH server listens on)
- **Local host:** `localhost`
- **Local port:** `3000` (your local service)

After connecting, programs on the SSH server reach your service at `localhost:9090`. Like OpenSSH, korTTY asks the server to listen on its loopback address only. Setting **Remote host** to `0.0.0.0` asks the server to accept connections from its network as well; the server only does that when its `GatewayPorts` setting allows it, and korTTY marks such a tunnel in the status bar as reachable from other computers.

### Dynamic Port Forwarding (`-D`)

Create a SOCKS proxy for network traffic through the SSH tunnel.

```
Your machine:1080  -->  SSH Server  -->  (any destination)
```

**Example use case:** Encrypt all traffic from your browser or application by routing it through the SSH tunnel.

- **Local port:** `1080` (or any available port)

Configure your browser or application to use `localhost:1080` as a SOCKS5 (or SOCKS4) proxy. Host names are resolved by the SSH server.

!!! note
    For Dynamic Port Forwarding, only the local host and local port are used. The remote host and remote port fields are ignored.

## When Tunnels Open

- **Once per terminal tab:** the tunnels open right after login on the tab's first SSH session and stay open while the tab is connected. Split panes neither open them again nor conflict with them.
- **Asked once:** the first time a connection's tunnels are about to open, korTTY lists them and asks **Open Tunnels** or **Not Now**. The answer is remembered for that connection; korTTY asks again when its tunnels or its server change. **Not Now** applies to the tab, including its reconnects — open the connection in a new tab to be asked again.
- **Reconnect:** a manual or automatic reconnect closes the tunnels first and opens them again on the new session.
- **Closing panes:** if you close the pane the tunnels run on (or type `exit` there) while another pane of the same server stays open, the tunnels move to that pane. Panes connected to a different server never take them over.
- **Closing the tab** closes its tunnels.
- **Two tabs, one connection:** each tab opens its own tunnels, so in the second tab a local or dynamic tunnel reports that the address is already in use. The first tab keeps working.
- **Not opened** by SFTP tabs, by Mosh connections (the status bar says that tunnels need the SSH protocol), or for splits to a different server.
- **Server side:** the SSH server must allow forwarding (`AllowTcpForwarding` in OpenSSH). A remote tunnel the server refuses is reported in the status bar; the other tunnels still open.

### Status Bar

While a tab has tunnels, its status bar shows `Tunnels: 2/3 active`, followed by the first tunnel that failed and why (for example `Address already in use`) and by the first tunnel that other computers may reach. Hover over the status bar to see every tunnel with its state.

### Shared (Teamwork) Connections

Tunnels of a connection that comes from a [Teamwork](teamwork.md) source were written by whoever edits the shared file, so korTTY limits them:

- Remote tunnels are never opened, whatever address they name: loopback on a shared SSH server is reachable by every account on it.
- Local and dynamic tunnels may only listen on `localhost`.
- The rest still needs your one-time confirmation, which points out that the tunnels come from the shared file.

To use a different tunnel, duplicate the connection and add the tunnel to your own copy.

### Organization Policy

An administrator can forbid all tunnels with `allow-port-forwarding = false` in the [enterprise policy](../reference/enterprise-policy.md). The status bar then says that tunnels are disabled by your organization.

## Managing Tunnels

- **Enable/Disable:** Toggle **Enable tunnel** on a tunnel to activate or deactivate it without removing the configuration.
- **Edit:** Select a tunnel and modify its settings.
- **Remove:** Remove a tunnel from the connection.
- **Multiple Tunnels:** Any number of tunnels can be configured on a single connection and opened together.

Changes take effect the next time the tab connects or reconnects.

!!! warning
    Ports below 1024 (such as 80 or 443) need elevated privileges: on your computer for local and dynamic tunnels, and a root login on the SSH server for remote tunnels. Use ports 1024 and above to avoid permission issues.
