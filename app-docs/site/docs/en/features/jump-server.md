---
title: Jump Server (Bastion Host)
---

# Jump Server (Bastion Host)

A jump server (bastion host) acts as an intermediate gateway to reach servers on a private network. korTTY tunnels the connection through a jump server so you can reach an internal system from your local machine without direct network access to it.

## Connection flow

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

korTTY authenticates to the jump server with the jump server's own credentials, opens a tunnel through it, and then opens the real SSH session to the target host through that tunnel. The target's credentials are used only for the target, and the jump server's credentials only for the jump server — neither host is offered the other's password or key.

Both SSH terminal and SFTP connections to the target route through the jump server; there is nothing extra to configure for SFTP.

!!! warning
    Mosh connections cannot go through a jump server: a Mosh session runs over UDP, which the jump server's SSH tunnel (TCP) does not forward. korTTY refuses the combination up front — the Jump Server tab warns as soon as it is configured, and connecting fails immediately with a clear message instead of stalling after the SSH bootstrap. Use the SSH protocol for targets behind a bastion, or disable the jump server.

## Configuration

To configure a jump server for a connection:

1. Open the *Connection Manager* and edit (or create) a connection.
2. Go to the **Jump Server** tab.
3. Enable **Jump Server**.
4. Enter the jump server's details:
    - **Host** — hostname or IP address of the jump server
    - **Port** — SSH port (default: 22)
    - **Username** — login username for the jump server
5. Choose an **Authentication** method:
    - **Password** — the password is stored encrypted with your master password. Leave the field empty when editing to keep the previously stored password.
    - **SSH key file (no passphrase)** — the path to an unencrypted private key file. Passphrase-protected keys are not supported for the jump hop.
6. Click **Save**.

## Host key verification

The jump server's host key is verified on first use exactly like any other host: korTTY shows the key's SHA-256 fingerprint and asks you to confirm it, then pins it. On later connections a changed jump-server key is refused, the same trust-on-first-use protection the target host gets. The bastion is always checked strictly: [relaxing host-key verification](security.md#relaxing-host-key-verification) applies to target hosts only, never to the jump hop.

This holds even when host-key verification has been relaxed for the target: the per-connection, per-group and global opt-outs never cover the bastion, so its key is always verified strictly. See [Relaxing host-key verification](security.md#relaxing-host-key-verification).

The target host is verified under its own name even though the transport goes through the tunnel, so a compromised bastion cannot substitute a different target host key unnoticed.

## When to use it

- Target servers are behind a firewall and only reachable through a bastion host.
- Security policy requires routing through a gateway.
- Internal infrastructure uses private addresses and needs an intermediary for access.

!!! note
    Jump server passwords are stored encrypted with your master password, alongside the connection's own credentials. Because the stored password is never shown back, editing the connection with an empty jump-server password field keeps the stored one; type a new value only to change it. If the master password vault is locked when you save a new jump password, korTTY reports that the jump password could not be saved and leaves the other settings untouched.
