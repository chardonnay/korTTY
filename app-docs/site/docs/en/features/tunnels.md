---
title: SSH Tunnels (Port Forwarding)
---

# SSH Tunnels (Port Forwarding)

SSH tunnels securely forward traffic between local and remote ports through an encrypted SSH connection. korTTY supports three tunnel types: local port forwarding, remote port forwarding, and dynamic port forwarding (SOCKS proxy). They work like the `-L`, `-R` and `-D` options of OpenSSH and run over the login session of the terminal tab, so they need no second login.

## Configuring Tunnels

1. Open a connection for editing: **Connections > Manage Connections** → select the connection → **Edit**.
2. Navigate to the **SSH Tunnels** tab.
3. Click **Add**, choose the **Type** and fill in the fields. A new tunnel starts with **Enable tunnel** ticked.
4. Click **Save** in the tunnel dialog, then **Save** in the connection editor. **Cancel** in the connection editor discards every tunnel you added, edited or removed since you opened it.

### Tunnel Configuration Fields

The tunnel dialog names its fields after the role they play for the chosen type and always lists the address that listens first, followed by the address the connections are forwarded to.

| Field | **Local (-L)** | **Remote (-R)** | **Dynamic SOCKS proxy (-D)** |
|-------|----------------|-----------------|------------------------------|
| Listens on | **Local bind address** and **Local port** on your computer | **Remote bind address** and **Remote port** on the SSH server | **Local bind address** and **Local port** on your computer |
| Forwards to | **Remote host** and **Remote port**, as seen from the SSH server | **Local host** and **Local port**, as seen from your computer | Whatever destination the SOCKS client asks for |
| Default bind address | `localhost` | `localhost` | `localhost` |
| **Description** | Optional label | Optional label | Optional label |
| **Enable tunnel** | Only enabled tunnels are opened | Only enabled tunnels are opened | Only enabled tunnels are opened |

A bind address of `localhost` (or leaving it empty) keeps the listener private to the computer it runs on. As soon as you enter another address, such as `0.0.0.0` or a network interface, the dialog warns that other computers can reach the tunnel; for a remote tunnel the warning adds that the SSH server only accepts such an address when its `GatewayPorts` setting allows it.

Multiple tunnels can be configured per connection. Disabled tunnels remain in the configuration but are not opened when the connection connects.

### The Tunnel List

The **SSH Tunnels** tab lists every tunnel the way `ssh` options read, listener first: `✓ L localhost:8080 -> db:5432`, `✓ R localhost:9090 -> localhost:3000` or `○ D localhost:1080 (SOCKS)`, followed by its description. `✓` marks an enabled tunnel and `○` a disabled one. A tunnel that other computers may reach is marked as such in the list, so a forgotten `0.0.0.0` stands out.

**Enable SSH tunnels** above the list switches every tunnel of the connection on or off at once. It is ticked when all tunnels are enabled and shows a dash when only some are; clicking the dash enables all of them. Switching a single tunnel is done with **Enable tunnel** in its own dialog.

## Tunnel Types

### Local Port Forwarding (`-L`)

Forward a local port to a remote service through the SSH tunnel.

```
Your machine:8080  -->  SSH Server  -->  database-server:5432
```

**Example use case:** Access a remote database server that is not directly reachable from your machine.

- **Local bind address:** `localhost`
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

- **Remote bind address:** `localhost` (the address the SSH server listens on)
- **Remote port:** `9090` (the port the SSH server listens on)
- **Local host:** `localhost` (where your computer forwards each connection)
- **Local port:** `3000` (your local service)

After connecting, programs on the SSH server reach your service at `localhost:9090`. Like OpenSSH, korTTY asks the server to listen on its loopback address only. Setting **Remote bind address** to `0.0.0.0` asks the server to accept connections from its network as well; an OpenSSH server only does that when its `GatewayPorts` setting is `clientspecified`, and korTTY marks such a tunnel in the tunnel dialog, the tunnel list and the status bar as reachable from other computers. A server with `GatewayPorts yes` listens on all its addresses for every remote tunnel, whatever bind address you enter.

### Dynamic Port Forwarding (`-D`)

Create a SOCKS proxy for network traffic through the SSH tunnel.

```
Your machine:1080  -->  SSH Server  -->  (any destination)
```

**Example use case:** Encrypt all traffic from your browser or application by routing it through the SSH tunnel.

- **Local bind address:** `localhost`
- **Local port:** `1080` (or any available port)

Configure your browser or application to use `localhost:1080` as a SOCKS5 (or SOCKS4) proxy. Host names are resolved by the SSH server.

!!! note
    A dynamic tunnel only uses the local bind address and the local port; the remote fields are disabled for this type.

## When Tunnels Open

- **Once per terminal tab:** the tunnels open right after login on the tab's first SSH session and stay open while the tab is connected. Split panes neither open them again nor conflict with them.
- **Asked once:** the first time a connection's tunnels are about to open, korTTY lists them and asks **Open Tunnels** or **Not Now**. **Not Now** is the default button, so a key you are still typing into the terminal when the question appears never opens them. The answer is remembered for that connection; korTTY asks again when its tunnels or its server change. **Not Now** applies to the tab, including its reconnects — open the connection in a new tab to be asked again.
- **Reconnect:** a manual or automatic reconnect closes the tunnels first and opens them again on the new session.
- **Closing panes:** if you close the pane the tunnels run on (or type `exit` there) while a pane opened with **Split Right (same server)** or **Split Down (same server)** from a pane on the tab's server stays open, the tunnels move to that pane. Panes opened with **Split Right (new connection)** or **Split Down (new connection)**, and the same-server splits made from them, never take them over, even when they connect to the same server.
- **Closing the tab** closes its tunnels.
- **Two tabs, one connection:** each tab opens its own tunnels, so in the second tab a local or dynamic tunnel reports that the address is already in use, and a remote tunnel that the SSH server refused it. The first tab keeps working.
- **Not opened** by SFTP tabs, by Mosh connections (the status bar says that tunnels need the SSH protocol), or for splits to a different server.
- **Server side:** the SSH server must allow forwarding (`AllowTcpForwarding` in OpenSSH). A remote tunnel the server refuses (forwarding disabled, or its port already in use on the server) is reported in the status bar; the other tunnels still open.

### Status Bar

While a tab has tunnels, its status bar shows `Tunnels: 2/3 active`, followed by the first tunnel that failed and why (for example `Address already in use`) and by the first tunnel that other computers may reach. Hover over the status bar to see every tunnel with its state.

### Shared (Teamwork) Connections

Tunnels of a connection that comes from a [Teamwork](teamwork.md) source were written by whoever edits the shared file, so korTTY limits them:

- Remote tunnels are never opened, whatever address they name: loopback on a shared SSH server is reachable by every account on it.
- Local and dynamic tunnels may only listen on `localhost`.
- The rest still needs your one-time confirmation, which points out that the tunnels come from the shared file.

To use a different tunnel, create your own connection to the same server and add the tunnel there; shared connections cannot be duplicated in the connection manager.

### Organization Policy

An administrator can forbid all tunnels with `allow-port-forwarding = false` in the [enterprise policy](../reference/enterprise-policy.md). The status bar then says that tunnels are disabled by your organization.

## Managing Tunnels

- **Enable/Disable:** toggle **Enable tunnel** on a tunnel, or **Enable SSH tunnels** for all of them, to activate or deactivate tunnels without removing the configuration.
- **Edit:** select a tunnel and click **Edit** to change its settings.
- **Remove:** select a tunnel and click **Remove** to delete it from the connection.
- **Multiple tunnels:** any number of tunnels can be configured on a single connection and opened together.

Saving the connection applies the change to every open tab of that connection right away: switched-off or removed tunnels close, and a changed set of tunnels replaces the running one on the same session — after the one-time question unless you already allowed exactly that set. A tab whose tunnels did not change keeps them open, so editing only a description or another setting of the connection does not interrupt them. A tab that is disconnected or still logging in at that moment opens the saved tunnels once it is connected.

## Imported Tunnels

Tunnels imported from PuTTY Connection Manager keep their type, ports and target host. The CSV format names no bind address, so korTTY binds every imported listener to `localhost`, which is also PuTTY's default for remote tunnels. Remote tunnels that earlier versions imported were stored with the remote bind address `0.0.0.0`; the tunnel list, the tunnel dialog and the status bar mark them as reachable from other computers, so set their **Remote bind address** to `localhost` if the forwarded port should stay private to the server.

!!! warning
    Ports below 1024 (such as 80 or 443) need elevated privileges: on your computer for local and dynamic tunnels, and a root login on the SSH server for remote tunnels. Use ports 1024 and above to avoid permission issues.
