---
title: SFTP Manager
---

# SFTP Manager

Defaults for the dual-panel [SFTP file manager](../../features/sftp.md) and for the JobScheduler's Rsync jobs. Open via **Configuration → Global Settings → SFTP Manager**; stored in `~/.kortty/global-settings.xml`.

## SFTP Manager Settings

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Auto-close SFTP tabs after inactivity | toggle | — | Off | `sftpAutoCloseMinutes` (unset or `0`) |
| Timeout (minutes) | number | 1–120 | 10 | `sftpAutoCloseMinutes` |

!!! note "Auto-close"
    An idle SFTP tab closes itself after the timeout, which frees the server-side connection when you forget to close the manager. The timeout field is only editable while the toggle is on, and the two share one stored value: switching the toggle off stores no timeout at all.

## ZIP Creation Settings

These are the defaults for creating a ZIP archive **on the remote server** from the SFTP manager.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Default ZIP path | text | Remote absolute path | `/tmp` | `sftpDefaultZipPath` |
| Default compression (0-9) | number | 0–9 | 6 | `sftpDefaultZipCompression` |

!!! note "Compression levels"
    `0` means no compression (fastest) and `9` the best compression (slowest).

## JobScheduler Rsync

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Rsync binary path | path | — | empty (resolve `rsync` from `PATH`) | `jobSchedulerRsyncBinaryPath` |

!!! note "Requirements"
    Leave the path empty to use the `rsync` found on `PATH`. **Browse** picks a binary explicitly. Rsync jobs additionally require `ssh` to be available on `PATH`; see [JobScheduler](../../features/jobscheduler.md).
