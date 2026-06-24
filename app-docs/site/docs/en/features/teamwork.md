---
title: Teamwork (shared connections)
---

# Teamwork (shared connections)

Share SSH connections with your team by syncing them from a Git repository or shared file. Teamwork sources are loaded into the Connection Manager alongside your local connections, kept in sync automatically, and safely stripped of inline passwords so credentials come only from your local encrypted storage.


![Teamwork sync](../assets/diagrams/teamwork-sync-flow.svg)

## Overview

Teamwork lets teams maintain a centralized library of connection configurations:

- **Git repositories** — Clone and keep in sync with a Git repo containing a `kortty-teamwork-connections.xml` file (or legacy `connections.xml`).
- **Shared files** — Load connections from a local or network path (read-only or read-write).
- **Automatic sync** — Background syncing at a configurable interval checks for updates.
- **Credential security** — Shared connections do NOT carry inline passwords; only credential IDs and SSH key references.
- **Local overrides** — Your local credentials and SSH keys are merged with shared connection definitions.
- **Read-only mode** — Mark sources as read-only to prevent accidental writes back.

## Setting up teamwork sources

Open **Teamwork → Teamwork Settings…** (or **Configuration → Global Settings… → Teamwork**) to configure sources.

### Add a source

1. Click **Add** to create a new source.
2. Choose the source **Type**:
   - **Git** — Clone from an HTTPS, SSH, or git:// URL.
   - **Shared File** — Read from a local or network path (e.g., `file:///mnt/share/connections.xml` or `//host/share/connections.xml`).
3. Enter the **Location**:
   - For Git: the clone URL.
   - For Shared File: a local/network file path (can be a file:// URI or a UNC path).
4. Set the **Check Interval** (1–1440 minutes; default: 15).
5. Optionally enable **Read-Only** to prevent writes back (Git sources only).
6. Click **OK** to save.

### Manage sources

The Teamwork Settings dialog lists all sources with their type, location, and sync interval:

| Column | Meaning |
| --- | --- |
| Type | **Git** or **Shared File** |
| Location | Repository URL or file path |
| Interval | Minutes between sync checks |
| Enabled | Toggle to enable/disable without deleting |

Use the buttons to:
- **Add** — Create a new source.
- **Edit** — Modify the selected source.
- **Remove** — Delete the selected source.
- **Enable/Disable** — Toggle the enabled state for the selected sources.

At the bottom, set the **Default Check Interval** (applies to new sources that don't specify one).

## How synchronization works

### Background sync

Once you save the Teamwork Settings:

1. KorTTY starts a background sync thread.
2. Every N minutes (based on the minimum interval among enabled sources), it:
   - Pulls/clones each source (Git) or reads the file (Shared File).
   - Loads the connections XML.
   - Merges the results into the cache and Connection Manager.
3. If a source update fails, the previous cached version is kept.

### Manual sync

Use **Teamwork → Teamwork Settings…** and click **OK** to trigger a sync immediately.

### Conflict detection

Each sync records a version token:
- **Git** — The current commit hash.
- **Shared File** — The file's last-modified timestamp.

If a shared connection's version token changes between syncs, a new version was fetched. If you have made local edits to a teamwork connection and the source updates with a conflicting change, the local edits are preserved (no automatic overwrite).

## Shared connection file format

Create a `kortty-teamwork-connections.xml` file (or `connections.xml` for backward compatibility) in the root of your Git repo or shared file:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<connections>
  <connection id="prod-web-1">
    <name>Prod Web Server 1</name>
    <host>web1.example.com</host>
    <port>22</port>
    <username>deploy</username>
    <group>Production/Web</group>
    <authMethod>SSH_KEY</authMethod>
    <sshKeyId>key-prod-deploy</sshKeyId>
    <credentialId>cred-prod-user</credentialId>
  </connection>
</connections>
```

!!! warning "Do not include inline secrets"
    Teamwork connections **must not** include `encryptedPassword`, `privateKeyPath`, or `privateKeyPassphrase`. Instead:
    - Use `credentialId` to reference a stored credential in **Security → Credentials…**.
    - Use `sshKeyId` to reference a stored SSH key in **Security → SSH-Keys…**.

If inline secrets are found in the shared file, KorTTY strips them automatically (they are not loaded).

## Using teamwork connections

Once a source is synced:

1. Open **Manage Connections…** (or press ++ctrl+m++).
2. Teamwork connections appear in the tree with a **[Teamwork]** label and their source ID.
3. Click a teamwork connection to view or use it.
4. **Cannot edit directly** — Teamwork connections are read-only unless their source is marked as writable and you own edit rights (determined by `teamworkRole`).

### Local overrides

- The merged credential and SSH key references are resolved from your local storage.
- If a credential or key is not found locally, you are prompted to provide it when connecting.
- Your local copy of a teamwork connection can override authentication by assigning a different credential or key.

### Distinguish sources

In the Connection Manager, teamwork connections are marked by their source ID. Hover over or inspect the connection properties to see which teamwork source it came from.

## Git repository setup

To share connections via Git:

1. Create a repository (e.g., `ssh-connections`).
2. Add a `kortty-teamwork-connections.xml` file to the root with your connection definitions.
3. Commit and push.
4. Share the repository URL (HTTPS or SSH) with team members.
5. Team members add the URL in **Teamwork → Teamwork Settings… → Add**.

### SSH vs. HTTPS

- **HTTPS** — Works without SSH key setup; may require a GitHub Personal Access Token or username/password (store the token securely).
- **SSH** — Requires `git` and a local SSH key in `~/.ssh/id_rsa` (or configured in `ssh-add`).

### Example repository layout

```
ssh-connections/
├── kortty-teamwork-connections.xml
├── .gitignore
└── README.md
```

### Optional: store versions in Git

Use the commit hash as the version token so KorTTY can detect updates:

```bash
git log -1 --pretty=%H
```

## Shared file setup

To share connections via a file:

1. Export your connections to a file: **Connections → Export… → select connections → save as `.xml`**.
2. Place the file on a shared network path (e.g., `//server/share/connections.xml`).
3. Set read/write permissions as needed.
4. Team members add the file path in **Teamwork → Teamwork Settings… → Add**.

### Example paths

| Platform | Path format |
| --- | --- |
| Windows (network share) | `//server/share/connections.xml` or `file:////server/share/connections.xml` |
| Linux/macOS (NFS mount) | `/mnt/teamshare/connections.xml` or `file:///mnt/teamshare/connections.xml` |
| SMB/CIFS (mounted) | `/Volumes/teamshare/connections.xml` (macOS) |

## Security considerations

!!! warning "Credentials are local-only"
    Shared connections do not carry passwords or key passphrases. Your local encrypted storage (master-password protected) holds the actual secrets. Team members must have their own credentials set up locally.

!!! warning "Git repositories should not store secrets"
    Never commit passwords, SSH key content, or API tokens to the teamwork repository. Use only credential IDs and key references.

!!! warning "File permissions"
    For shared files on network paths, restrict read/write access to team members only. Ensure the path is not world-readable.

!!! tip "Audit trail"
    For Git-based teamwork, the commit history provides an audit trail. Review changes before pulling by checking the remote branch.

## Troubleshooting

### Source is not syncing

1. Open **Teamwork → Teamwork Settings…**.
2. Verify the source is **Enabled**.
3. Check that the **Location** is correct and accessible:
   - **Git** — Run `git clone <url>` manually to test.
   - **Shared File** — Verify the file exists and is readable from your machine.
4. Click **OK** to trigger a manual sync.
5. Check the application log (`~/.kortty/kortty.log`) for errors.

### Connections appear but credentials are missing

1. Open **Security → Credentials…** and **Security → SSH-Keys…**.
2. Verify that the credential IDs or SSH key IDs in the shared connections exist locally.
3. If missing, add them manually or ask your team administrator to provide the IDs.

### Cannot push changes to Git repo

1. Verify the Git URL uses SSH or an HTTPS token (not username/password).
2. Ensure your SSH key is registered with the remote (GitHub, GitLab, etc.).
3. In **Teamwork Settings**, mark the source as **Read-Only** if you don't need to write back.

### File path is not recognized (Windows/UNC)

Use forward slashes or the file:// URI format:

- `//server/share/connections.xml` ✓
- `\\server\share\connections.xml` ✗ (backslashes may not parse correctly)
- `file:////server/share/connections.xml` ✓ (UNC notation)

