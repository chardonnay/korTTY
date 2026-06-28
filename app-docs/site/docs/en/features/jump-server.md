---
title: Jump Server (Bastion Host)
---

# Jump Server (Bastion Host)

A jump server (bastion host) acts as an intermediate gateway to reach servers on a private network. KorTTY can tunnel connections through a jump server so you can access internal systems from your local machine without direct network access.

## Connection Flow

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

Your local machine connects first to the jump server (bastion host), which then connects to the target server. All traffic is routed through the jump server.

## Configuration

To configure a jump server for a connection:

1. Open the *Connection Manager* or create a new connection.
2. Edit the connection and go to the **Jump Server** tab (or **Advanced** tab).
3. Enable **Jump Server**.
4. Enter the jump server's details:
   - **Host** — Hostname or IP address of the jump server
   - **Port** — SSH port (default: 22)
   - **Username** — Login username for the jump server
5. Select an **Authentication** method:
   - **Password** — Enter the password directly (stored encrypted with your master password)
   - **SSH Key** — Select a stored SSH key from your key management
6. Optionally set an **Auto-Command** to execute after connecting to the jump server (e.g., `ssh internal-server`).
7. Click **Save**.

## How It Works

When you connect to a target server with a jump server configured, KorTTY:

1. Establishes an SSH connection to the jump server
2. Uses that connection to reach the target server
3. Routes all traffic through the jump server
4. Executes the optional auto-command on the jump server

This is particularly useful when:

- Target servers are behind a firewall and only reachable via a bastion host
- You need to comply with security policies that require routing through a gateway
- Internal infrastructure uses private IPs and requires an intermediary for access

!!! note
    The jump server credentials are stored encrypted in KorTTY, just like regular connection credentials. Your master password protects all stored jump server passwords and SSH key passphrases.
