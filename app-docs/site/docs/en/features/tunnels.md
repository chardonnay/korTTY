---
title: SSH Tunnels (Port Forwarding)
---

# SSH Tunnels (Port Forwarding)

SSH tunnels securely forward traffic between local and remote ports through an encrypted SSH connection. korTTY supports three tunnel types: local port forwarding, remote port forwarding, and dynamic port forwarding (SOCKS proxy).

## Configuring Tunnels

1. Open a connection for editing: **Connections > Manage Connections** → select the connection → **Edit**.
2. Navigate to the **SSH Tunnels** tab.
3. Click **Add Tunnel** and configure the tunnel.

### Tunnel Configuration Fields

| Field | Description |
|-------|-------------|
| **Type** | Local (`-L`), Remote (`-R`), or Dynamic (`-D`) |
| **Local Host** | Local bind address (typically `localhost`) |
| **Local Port** | Local port number |
| **Remote Host** | Target host from the SSH server's perspective |
| **Remote Port** | Target port on the remote host |
| **Description** | Optional label for the tunnel |
| **Enabled** | Toggle on/off without deleting the configuration |

Multiple tunnels can be configured per connection. Disabled tunnels remain in the configuration but are not activated when the connection opens.

## Tunnel Types

### Local Port Forwarding (`-L`)

Forward a local port to a remote service through the SSH tunnel.

```
Your machine:8080  -->  SSH Server  -->  database-server:5432
```

**Example use case:** Access a remote database server that is not directly reachable from your machine.

- **Local Host:** `localhost`
- **Local Port:** `8080`
- **Remote Host:** `database-server` (or IP)
- **Remote Port:** `5432`

Once configured, connect to the remote database using `localhost:8080` on your machine.

### Remote Port Forwarding (`-R`)

Make a local service accessible from the remote server's network.

```
Remote:9090  -->  SSH Server  -->  Your machine:3000
```

**Example use case:** Allow a remote team member to access a local development server on your machine.

- **Remote Port:** `9090` (the port the remote side uses to reach your service)
- **Local Host:** `localhost`
- **Local Port:** `3000` (your local service)

After connecting, the remote machine can access your service at `localhost:9090`.

### Dynamic Port Forwarding (`-D`)

Create a SOCKS5 proxy for all network traffic through the SSH tunnel.

```
Your machine:1080  -->  SSH Server  -->  (any destination)
```

**Example use case:** Encrypt all traffic from your browser or application by routing it through the SSH tunnel.

- **Local Port:** `1080` (or any available port)

Configure your browser or application to use `localhost:1080` as a SOCKS5 proxy. All traffic will be securely forwarded through the SSH server.

!!! note
    For Dynamic Port Forwarding, only the local port is required. The remote host and remote port fields are not used.

## Managing Tunnels

- **Enable/Disable:** Toggle the **Enabled** checkbox on a tunnel to activate or deactivate it without removing the configuration.
- **Edit:** Select a tunnel and modify its settings.
- **Delete:** Remove a tunnel from the connection.
- **Multiple Tunnels:** Any number of tunnels can be configured on a single connection and activated simultaneously.

Tunnels are established when the SSH connection is opened and remain active for the duration of the session.

!!! warning
    When using local or remote port forwarding on ports below 1024 (such as port 80 or 443), your system may require elevated privileges. Use ports 1024 and above to avoid permission issues.
