---
title: Backup
---

# Backup

Configure backup retention policy and encryption method for korTTY session backups. Open via **Configuration → Global Settings → Backup**; stored in `~/.kortty/global-settings.xml`.

![Backup settings tab](../../assets/screenshots/settings/backup.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Maximum number of backups | number | 0–100 (0 = unlimited) | 10 | `maxBackupCount` |
| ZIP with Password | radio (one of two) | — | On | `backupEncryptionType` (`PASSWORD`) |
| Credential | dropdown | Available stored credentials | — | `backupCredentialId` |
| GPG Encryption | radio (one of two) | — | Off | `backupEncryptionType` (`GPG`) |
| GPG Key | dropdown | Available GPG keys | — | `backupGpgKeyId` |

!!! warning
    Backups are ALWAYS encrypted. Choose one mode, ZIP with password or GPG. The chosen mode needs its credential or its GPG key; without one, the backup stops with an error before anything is written.

!!! note
    **Maximum Backups:** Set to `0` to keep backups indefinitely; any other value (1–100) will automatically delete the oldest backups in `old-backups/` once the limit is reached, counting password ZIP and GPG backups together. When using password-based encryption, select a credential from the management system. When using GPG encryption, select an available GPG key. The encryption type is stored in the global settings and determines which credential or key ID is used for future backups.

Once a backup has been made, the tab shows **Last Backup:** with its date, time and the path it was written to.

Backups include `ssh-host-keys.properties`, so normalized host:port trust decisions shared by interactive Terminal, SFTP, and Mosh bootstrap connections survive restore; the transient cross-process `.lock` file is not included. JobScheduler host-key pins remain separately stored in `job-scheduler.xml`.

Besides these, a backup carries the connections, credentials, GPG and SSH key entries with the copied SSH key files, global settings, JobScheduler jobs, snippets and snippet analyses, AI and swarm chats, themes, environments, project workspaces and the master-password check file `master.key`, but not the auto-unlock copy of the master password. The complete list of what a backup contains and leaves out is on [Backup & restore](../../features/backup.md).

Local-AI backups include `llm/models.xml`, `llm/mlx-models.json` and `rag/stores.json` so GGUF and MLX model registrations, role assignments, and knowledge-source definitions can be restored. They exclude GGUF weights, llama.cpp runtime packages, temporary sidecar data, source documents, and regenerable HNSW snapshots; see [Backup & restore](../../features/backup.md).
