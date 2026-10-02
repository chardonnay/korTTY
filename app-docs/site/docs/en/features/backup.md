---
title: Backup & restore
---

# Backup & restore

KorTTY creates encrypted backups of all your settings, connections, credentials, and SSH keys. Use backup and restore to protect your configuration or move it between machines.

![Backup & restore flow](../assets/diagrams/backup-restore.svg)

## Features

* **Encrypted backups** — All backups are encrypted using either password-protected ZIP or GPG encryption
* **Configuration backup** — Includes connections, credentials, credential environments, SSH/GPG keys, trusted interactive SSH host keys, global settings, custom terminal themes, JobScheduler configuration, snippets and their stored code analyses, AI chat history, AI Swarm chats, local-model registrations (GGUF and MLX), and knowledge-store source metadata
* **Regenerable local AI data excluded** — GGUF weights, native llama.cpp runtimes, signed-catalog cache, temporary sidecar files, and HNSW snapshots are intentionally not copied into the archive
* **Projects directory** — All saved project workspaces are included in the backup
* **Automatic rotation** — Old backups are automatically moved to an `old-backups` subdirectory with timestamps
* **Configurable retention** — Set a maximum number of backups to keep (0 = unlimited; oldest backups are deleted automatically)
* **Import/restore** — Restore from previously created backups with optional overwrite control
* **Flexible decryption** — Both password-encrypted ZIP and GPG-encrypted formats are supported for import; korTTY recognises the format from the file content, not from its name

## Creating a backup

1. Open **Edit → Create Backup...** or press ++ctrl+shift+b++ (Cmd+Shift+B on macOS)
2. Select a destination directory for the backup file
3. The backup is created using the encryption method configured in **Settings → Backup**

KorTTY names the current backup `kortty-backup.zip` (password-protected ZIP) or `kortty-backup.zip.gpg` (GPG) in the target directory. The new backup is written completely first; only then is any current backup of either kind already there rotated into an `old-backups` subdirectory with a timestamp appended, keeping its `.zip` or `.zip.gpg` ending (e.g., `kortty-backup_2025-06-24_14-30-45.zip.gpg`). If creating the backup fails, the existing current backup stays untouched.

The backup includes:

| Item | Details |
|------|---------|
| Connections | All saved SSH connections and groups |
| Credentials | Stored usernames and passwords (encrypted) |
| Credential environments | Your custom credential environments (`environments.xml`); the built-in ones need no backup |
| SSH keys | Key references with encrypted passphrases, plus the copied key files in `~/.kortty/ssh-keys/` |
| Trusted interactive hosts | `ssh-host-keys.properties`, shared by Terminal, SFTP, and the Mosh SSH bootstrap; the transient `.lock` companion is not included |
| GPG keys | GPG public keys for backup encryption |
| Settings | Global application settings, terminal configurations, and AI profiles |
| Themes | Terminal color themes, including the ones you created (`themes.xml`) |
| JobScheduler jobs | All scheduled jobs, host-key pins, and encrypted sudo passwords |
| Snippets | Code snippets and script templates with metadata, their folder structure, file names and executable flags |
| Snippet variables | Custom variables for snippet substitution |
| Snippet analyses | The stored [Full code analyses](snippets.md#full-code-analysis) of every snippet and every [analysed folder](snippets.md#analysing-a-folder-as-one-project), with their apply runs, diagrams and modularization proposals |
| AI chats | Saved AI conversation histories and profiles |
| AI Swarm chats | Saved swarm chats, including the ones scheduled AI Swarm jobs store (`swarm-chats.xml`) |
| Local AI configuration | Local GGUF registrations and typed launch settings, MLX model registrations, Text/Coding roles, preferred runtime backend/update policy, and encrypted Hugging Face token |
| Knowledge-store configuration | Store metadata and source paths, filters, sync modes, and embedding configuration; not the HNSW vectors |
| Projects | All `.kortty` project workspace files |

## Backup encryption

### Password-protected ZIP (default)

1. Open **Settings → Backup**
2. Select **Encryption Type: Password**
3. Choose or create a credential to use as the encryption password
4. Optionally set **Maximum Backups** (0 = unlimited)
5. Save

Password-protected backups are encrypted with AES-256 (via the zip4j library); the credential's password encrypts all files in the archive. Backups created by older korTTY versions used legacy ZIP encryption and can still be imported — the decryption method is read from the archive itself. Note that AES-encrypted ZIPs need an AES-capable tool (7-Zip, WinZip, `unzip` 6+) if you ever extract one outside korTTY.

### GPG encryption

1. Open **Settings → Backup**
2. Select **Encryption Type: GPG**
3. Choose a GPG key from **Manage GPG Keys...**
4. Optionally set **Maximum Backups** (0 = unlimited)
5. Save

GPG backups are encrypted for the public key of your selected GPG key. KorTTY builds an unencrypted ZIP in a private temporary folder that only your user can read, encrypts it with `gpg`, stores the result as `kortty-backup.zip.gpg` and then deletes the temporary ZIP with its folder. Creating a GPG backup needs the installed `gpg` and the recipient's public key; restoring it needs `gpg` and the matching **private** key, so keep that key (and its passphrase) somewhere other than the backup.

!!! tip
    If you don't have GPG keys set up yet, use **Management → Manage GPG Keys...** to import keys from your system keyring or add them manually.

## Importing a backup

1. Open **Edit → Import Backup...**
2. Select a backup file (`.zip` or `.zip.gpg`)
3. If the backup is a password-protected ZIP, enter the password when prompted; GPG backups ask for no ZIP password — `gpg` may ask for the passphrase of your private key instead
4. Choose whether to **Overwrite Existing Files**:
   * **OK** — Backup files will replace any existing files in your configuration
   * **Cancel** — Existing files are skipped; only missing files are imported
5. **Restart the application** for all changes to take effect

KorTTY recognises the backup format from the file content, not from its name, so GPG backups that older versions saved as `kortty-backup.zip` import as GPG backups too. A file that is neither a ZIP archive nor GPG-encrypted is rejected before anything is restored.

After the import, korTTY reloads the restored connections, credentials, environments, SSH and GPG keys, settings, themes, snippets, snippet variables and saved AI and swarm chats, so a later save does not overwrite them with what was loaded before.

Each restored file replaces the local one atomically, so an interrupted import never leaves a half-written file behind. On macOS and Linux, `connections.xml`, `credentials.xml`, `ssh-keys.xml`, `job-scheduler.xml` and `master.key` are restored owner-only (`rw-------`); the other files keep the permissions of the file they replace, and a file that did not exist locally is created owner-only. If a restored connections, credentials, SSH key, GPG key or environments file cannot be parsed, the reload moves it aside as `<name>.corrupt-<timestamp>` and korTTY keeps what it had loaded before, which its next save writes to a fresh file; a themes file that cannot be parsed is moved aside the same way and replaced by the built-in themes.

!!! warning
    Importing a backup with **Overwrite** enabled will replace your current settings, connections, and credentials. If you are unsure, choose **Cancel** to merge the backup without overwriting.

!!! important "A backup with another master password"
    If an overwriting import brings the `master.key` of another master password, everything korTTY has loaded still belongs to the old one. korTTY tells you to restart and quits without saving its current data, so the restored files stay as they are. Start korTTY again and unlock it with the master password of the backup.

## Backup file contents

Both `.zip` and `.zip.gpg` backups contain the same files:

* `connections.xml` — All SSH connections and groups
* `credentials.xml` — Stored credentials (still encrypted with your master password)
* `environments.xml` — Custom credential environments
* `ssh-keys.xml` — SSH key references and encrypted passphrases
* `ssh-keys/` — Copied SSH key files (only keys you placed there via **Copy to User Directory**; keys referenced in their original locations are not collected). Restored key files get owner-only permissions, and an import merges — it never deletes or, without **Overwrite**, replaces keys already present
* `ssh-host-keys.properties` — Trusted public host keys for interactive Terminal, SFTP, and Mosh bootstrap connections (`ssh-host-keys.properties.lock` is intentionally excluded)
* `gpg-keys.xml` — GPG public keys
* `global-settings.xml` — Application settings, AI profiles, terminal defaults
* `themes.xml` — Terminal color themes, including your own
* `job-scheduler.xml` — JobScheduler jobs, host-key pins, encrypted sudo passwords
* `snippets.xml` — Code snippets and templates
* `snippet-variables.xml` — Custom snippet variables
* `snippet-analyses/` — Stored Full code analyses, one file per snippet. An import merges them: analyses that exist only locally are kept, missing ones are added, and with **Overwrite** a local file is replaced only when the backup's copy is newer. Unsaved snippet drafts (`snippet-drafts/`) are not included
* `ai-chats.xml` — Saved AI conversations
* `swarm-chats.xml` — Saved AI Swarm chats
* `master.key` — Salt and verification hash of your master password, so the restored secrets can be unlocked with it
* `llm/models.xml` — Local GGUF registrations and runtime settings (model weights are not included)
* `llm/mlx-models.json` — MLX model registrations (model weights are not included)
* `rag/stores.json` — Knowledge-store and source configuration (vector snapshots are not included)
* `projects/` — All saved project workspace files (`.kortty`)

!!! note
    All passwords and credentials inside the backup remain encrypted with your master password. When you import a backup, you must unlock the master password for KorTTY to decrypt the credentials.

!!! important "Rebuild local AI assets after a restore"
    The backup excludes `llm/models/`, `llm/runtime/`, `llm/catalog/`, `llm/run/`, and local `index.hnsw` snapshots. After moving to another computer, restore or download the GGUF files and a compatible runtime, reconnect any external model/source paths, then run **Update now** in each knowledge store to regenerate its index. The signed catalog cache refreshes automatically or falls back to the bootstrap. Original source documents and external Qdrant data are not part of a korTTY configuration backup.

## Backup retention and cleanup

When you create a new backup in a directory that already contains one, KorTTY:

1. Creates the new backup — `kortty-backup.zip` for a password-protected ZIP, `kortty-backup.zip.gpg` for GPG — and stops here, leaving the existing backup untouched, if that fails
2. Moves every existing current backup, of either kind, to `old-backups/kortty-backup_<timestamp>.zip` or `old-backups/kortty-backup_<timestamp>.zip.gpg`; a backup rotated in the same second gets `-1`, `-2` … appended instead of replacing the earlier one
3. If the number of old backups exceeds **Maximum Backups**, deletes the oldest ones

**Maximum Backups** counts ZIP and GPG backups together. After you switch the encryption type, the first new backup also rotates the current backup of the other type, so the oldest old backup can be deleted one backup earlier than you might expect.

To keep unlimited old backups, set **Maximum Backups** to `0` in **Settings → Backup**. To keep only one old backup next to the current one, set **Maximum Backups** to `1`.

## Using backups across machines

1. **Export your current configuration:**
   * On machine A, open **Edit → Create Backup...** and save to a USB drive or cloud storage

2. **Move the backup file:**
   * Copy `kortty-backup.zip` (or `kortty-backup.zip.gpg`) to machine B; for a GPG backup, machine B also needs `gpg` and the private key of the backup's GPG key

3. **Import on the new machine:**
   * On machine B, open **Edit → Import Backup...**
   * Select the backup file from step 2
   * Enter the backup password if prompted
   * Choose **Cancel** in the overwrite question unless you want to replace existing connections
   * Restart KorTTY

All backed-up connections, settings, snippets, saved chats, interactive host-key trust decisions, model registrations, and knowledge-source definitions will be available on machine B. Restored host keys are still matched by normalized host name and port, so a changed key remains blocked after migration. Local model weights, runtime packages, source documents, and HNSW vectors must be restored or regenerated separately.

## Troubleshooting

**"Backup file not found"**
: Verify the file path is correct and the file exists. Check the directory permissions.

**"Password required for password-encrypted backup"**
: Password-protected backups need the correct password. Verify you are entering the credential password (from **Settings → Backup**), not your master password.

**"… is not a korTTY backup: it is neither a ZIP archive nor a GPG-encrypted file"**
: The selected file is neither a ZIP archive nor GPG-encrypted data, for example a different file with a `.zip` name or a backup that was cut off while copying. Select the `kortty-backup.zip` or `kortty-backup.zip.gpg` file itself.

**"GPG encryption failed"**
: `gpg` could not encrypt the new backup: it is not installed, or the selected key's public key is neither in your GPG keyring nor available as the key file stored with the key in **Manage GPG Keys...**. The previous backup in the target directory is left as it was.

**"GPG decryption failed"**
: `gpg` could not decrypt the backup: the private key that matches the backup's GPG key is not in your keyring, `gpg` is not installed, or you cancelled the passphrase prompt. Import the private key into your GPG keyring (for example with `gpg --import`) and try again.

**"GPG key not found"**
: The GPG key used for encryption is missing. Use **Management → Manage GPG Keys...** to import or add the key, then try again.

**"No password selected for backup encryption" or "No GPG key selected"**
: Configure a password credential or GPG key in **Settings → Backup** before creating a backup.

**A `*.corrupt-<timestamp>` file appeared in `~/.kortty`**
: korTTY could not parse that data file at startup or after an import, so it moved the original aside under this name without changing it and continued without its content. Repair the file and, with korTTY closed, move it back under its original name, or restore the file from a backup.

**Import succeeded but changes did not take effect**
: Restart KorTTY for imported settings to become active. If you imported credentials, you may also need to unlock the master password after restart.

**Backup file is larger than expected**
: Large backups can occur if you have many saved AI chats or a large projects directory. GGUF weights, llama.cpp runtime packages, and HNSW snapshots are excluded and cannot be the cause.
