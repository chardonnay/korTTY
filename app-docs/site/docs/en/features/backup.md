---
title: Backup & restore
---

# Backup & restore

KorTTY creates encrypted backups of all your settings, connections, credentials, and SSH keys. Use backup and restore to protect your configuration or move it between machines.

![Backup & restore flow](../assets/diagrams/backup-restore.svg)

## Features

* **Encrypted backups** — All backups are encrypted using either password-protected ZIP or GPG encryption
* **Configuration backup** — Includes connections, credentials, SSH/GPG keys, global settings, JobScheduler configuration, snippets, AI chat history, local-model registrations, and knowledge-store source metadata
* **Regenerable local AI data excluded** — GGUF weights, native llama.cpp runtimes, signed-catalog cache, temporary sidecar files, and HNSW snapshots are intentionally not copied into the archive
* **Projects directory** — All saved project workspaces are included in the backup
* **Automatic rotation** — Old backups are automatically moved to an `old-backups` subdirectory with timestamps
* **Configurable retention** — Set a maximum number of backups to keep (0 = unlimited; oldest backups are deleted automatically)
* **Import/restore** — Restore from previously created backups with optional overwrite control
* **Flexible decryption** — Both password-encrypted ZIP and GPG-encrypted formats are supported for import

## Creating a backup

1. Open **Edit → Create Backup...** or press ++ctrl+shift+b++ (Cmd+Shift+B on macOS)
2. Select a destination directory for the backup file
3. The backup is created using the encryption method configured in **Settings → Backup**

KorTTY names the current backup `kortty-backup.zip` in the target directory. If a backup already exists there, it is automatically rotated into an `old-backups` subdirectory with a timestamp appended (e.g., `kortty-backup_2025-06-24_14-30-45.zip`).

The backup includes:

| Item | Details |
|------|---------|
| Connections | All saved SSH connections and groups |
| Credentials | Stored usernames and passwords (encrypted) |
| SSH keys | Centrally managed SSH private keys with encrypted passphrases |
| GPG keys | GPG public keys for backup encryption |
| Settings | Global application settings, terminal configurations, themes, and AI profiles |
| JobScheduler jobs | All scheduled jobs, host-key pins, and encrypted sudo passwords |
| Snippets | Code snippets and script templates with metadata |
| Snippet variables | Custom variables for snippet substitution |
| AI chats | Saved AI conversation histories and profiles |
| Local AI configuration | Local GGUF registrations and typed launch settings, Text/Coding roles, preferred runtime backend/update policy, and encrypted Hugging Face token |
| Knowledge-store configuration | Store metadata and source paths, filters, sync modes, and embedding configuration; not the HNSW vectors |
| Projects | All `.kortty` project workspace files |

## Backup encryption

### Password-protected ZIP (default)

1. Open **Settings → Backup**
2. Select **Encryption Type: Password**
3. Choose or create a credential to use as the encryption password
4. Optionally set **Maximum Backups** (0 = unlimited)
5. Save

Password-protected backups use standard ZIP encryption (`EncryptionMethod.ZIP_STANDARD`) via the zip4j library. The credential's password is used to encrypt all files in the archive.

### GPG encryption

1. Open **Settings → Backup**
2. Select **Encryption Type: GPG**
3. Choose a GPG key from **Manage GPG Keys...**
4. Optionally set **Maximum Backups** (0 = unlimited)
5. Save

GPG backups encrypt the backup file using the public key of your selected GPG key. KorTTY creates a temporary ZIP first, then encrypts it with `gpg --encrypt` and stores the `.gpg` file. The temporary ZIP is securely deleted after encryption.

!!! tip
    If you don't have GPG keys set up yet, use **Management → Manage GPG Keys...** to import keys from your system keyring or add them manually.

## Importing a backup

1. Open **Edit → Import Backup...**
2. Select a backup file (`.zip` or `.gpg`)
3. If the backup is password-protected, enter the password when prompted
4. Choose whether to **Overwrite Existing Files**:
   * **Checked** — Backup files will replace any existing files in your configuration
   * **Unchecked** — Existing files are skipped; only missing files are imported
5. Click **Import**
6. **Restart the application** for all changes to take effect

!!! warning
    Importing a backup with **Overwrite** enabled will replace your current settings, connections, and credentials. If you are unsure, uncheck this option to merge the backup without overwriting.

## Backup file contents

Both `.zip` and `.gpg` backups contain the same files:

* `connections.xml` — All SSH connections and groups
* `credentials.xml` — Stored credentials (still encrypted with your master password)
* `ssh-keys.xml` — SSH key references and encrypted passphrases
* `gpg-keys.xml` — GPG public keys
* `global-settings.xml` — Application settings, themes, AI profiles, terminal defaults
* `job-scheduler.xml` — JobScheduler jobs, host-key pins, encrypted sudo passwords
* `snippets.xml` — Code snippets and templates
* `snippet-variables.xml` — Custom snippet variables
* `ai-chats.xml` — Saved AI conversations
* `master-password-hash` — Hash of your master password (for verification on import)
* `llm/models.xml` — Local GGUF registrations and runtime settings (model weights are not included)
* `rag/stores.json` — Knowledge-store and source configuration (vector snapshots are not included)
* `projects/` — All saved project workspace files (`.kortty`)

!!! note
    All passwords and credentials inside the backup remain encrypted with your master password. When you import a backup, you must unlock the master password for KorTTY to decrypt the credentials.

!!! important "Rebuild local AI assets after a restore"
    The backup excludes `llm/models/`, `llm/runtime/`, `llm/catalog/`, `llm/run/`, and local `index.hnsw` snapshots. After moving to another computer, restore or download the GGUF files and a compatible runtime, reconnect any external model/source paths, then run **Update now** in each knowledge store to regenerate its index. The signed catalog cache refreshes automatically or falls back to the bootstrap. Original source documents and external Qdrant data are not part of a korTTY configuration backup.

## Backup retention and cleanup

When you create a new backup in a directory that already contains one, KorTTY:

1. Creates the new backup as `kortty-backup.zip`
2. Moves the existing backup to `old-backups/kortty-backup_<timestamp>.zip`
3. If the number of old backups exceeds **Maximum Backups**, deletes the oldest ones

To keep unlimited old backups, set **Maximum Backups** to `0` in **Settings → Backup**. To keep only the current backup, set **Maximum Backups** to `1` (old backups are still rotated but then immediately deleted).

## Using backups across machines

1. **Export your current configuration:**
   * On machine A, open **Edit → Create Backup...** and save to a USB drive or cloud storage

2. **Move the backup file:**
   * Copy `kortty-backup.zip` (or `.gpg`) to machine B

3. **Import on the new machine:**
   * On machine B, open **Edit → Import Backup...**
   * Select the backup file from step 2
   * Enter the backup password if prompted
   * Leave **Overwrite** unchecked unless you want to replace existing connections
   * Restart KorTTY

All backed-up connections, settings, snippets, saved chats, model registrations, and knowledge-source definitions will be available on machine B. Local model weights, runtime packages, source documents, and HNSW vectors must be restored or regenerated separately.

## Troubleshooting

**"Backup file not found"**
: Verify the file path is correct and the file exists. Check the directory permissions.

**"Password required for password-encrypted backup"**
: Password-protected backups need the correct password. Verify you are entering the credential password (from **Settings → Backup**), not your master password.

**"GPG key not found"**
: The GPG key used for encryption is missing. Use **Management → Manage GPG Keys...** to import or add the key, then try again.

**"No password selected for backup encryption" or "No GPG key selected"**
: Configure a password credential or GPG key in **Settings → Backup** before creating a backup.

**Import succeeded but changes did not take effect**
: Restart KorTTY for imported settings to become active. If you imported credentials, you may also need to unlock the master password after restart.

**Backup file is larger than expected**
: Large backups can occur if you have many saved AI chats or a large projects directory. GGUF weights, llama.cpp runtime packages, and HNSW snapshots are excluded and cannot be the cause.
