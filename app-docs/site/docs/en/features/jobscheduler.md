---
title: JobScheduler
---

# JobScheduler

The JobScheduler runs unattended background jobs while KorTTY is open. It does not require an operating system service or an active SSH terminal tab. Jobs execute automatically on a configured schedule using saved SSH connections from the Connection Manager.

Open it with **Tools > JobScheduler...**. The dialog remembers its window position and size.


![JobScheduler execution](../assets/diagrams/jobscheduler-execution.svg)

## Overview

JobScheduler supports five types of actions:

- **COMMAND** — Run a non-interactive remote shell command
- **SNIPPET_SCRIPT** — Execute a SnippetManager script on the target with optional parameters
- **AI_AGENT** — Run a headless AI agent with explicit auto-approval
- **SFTP** — Upload, download, sync, delete, rename, create directories, set permissions, change ownership, remote-copy, or create archives
- **RSYNC_SYNC** — Synchronize directories via external `rsync` over SSH

## Job Configuration

The JobScheduler dialog has three tabs: **Job**, **Action**, and **Journal**.

### Job Tab: Targets and Schedules

Use the **Job** tab to define where and when a job runs.

| Field | Description |
|-------|-------------|
| **Enabled** | Enables or disables the job. |
| **Name** | Display name shown in the job list, journal, and menu-bar status. |
| **Connection** | Opens the Connection Manager selection dialog. Select individual SSH TCP servers, whole groups, or both. Mosh connections are not supported. |
| **Working directory** | Optional remote directory. Use **Browse...** to connect to the target and pick a directory when the path is not known. |
| **Journal** | `LIMITED_REDACTED` stores bounded, redacted excerpts. `FULL` stores full output/transcripts (KorTTY-managed secrets are still redacted). |
| **Active from** / **Active until** | Optional date range for the job. The job will not run outside this range. |
| **Window start** / **Window end** | Time window for the job (e.g., 09:00 to 17:00). Values are selected from validated time lists. |
| **Interval minutes** | Optional repeated interval inside the active window. If set, the job runs every N minutes between window start and end. |
| **Fixed times** | Optional explicit start times (e.g., 09:30, 12:00, 15:30). Values are selected from validated time lists. |
| **Weekdays** | Optional weekday filter. Use the all-toggle to select or clear all weekdays at once. |

Schedule calculations use the local system time zone. If no fixed time and no interval are configured, the next run is the window start on an allowed date.

!!! note
    Scheduler jobs support saved SSH TCP connections only. Mosh targets are blocked as unsupported and the reason is written to the journal.

### Host Keys, Sudo, and Secrets

Host-key verification is secure by default. Before unattended SSH/SFTP/Rsync execution, select the target and click **Confirm host key** so KorTTY stores the pinned fingerprint and OpenSSH public-key material.

The checkbox **Disable host-key verification for this job** disables host-key verification only for the selected job. This is unsafe and should only be used when the risk is understood.

Sudo passwords can be stored for one server or for a server group:

- Server-specific sudo passwords are used first.
- Group sudo passwords are used as fallback.
- Stored sudo passwords are encrypted with the master password.
- If the master password is locked and a job needs SSH, sudo, API, or archive secrets, the job is blocked and journaled.

For Rsync jobs, **Use sudo** means passwordless remote sudo only through `sudo -n rsync`. Stored sudo passwords are not used by the current Rsync integration.

### Action Tab: Job Types and Configuration

Use the **Action** tab to choose what the job does. The tab shows only the fields that the selected action can use, so unrelated fields are hidden. The action selector includes:

| Action | Purpose |
|--------|---------|
| **COMMAND** | Run a non-interactive remote command. |
| **SNIPPET_SCRIPT** | Run a SnippetManager script on the selected target. The **Snippet search** field filters the script dropdown by snippet name, category, language, or ID; **Snippet parameters** passes additional arguments as one argv value per line. |
| **AI_AGENT** | Run the headless scheduler AI agent. Unattended command execution requires **Auto-approve AI commands** on the job. |
| **SFTP_UPLOAD** | Upload a local path to a remote path. |
| **SFTP_DOWNLOAD** | Download a remote path to a local path. |
| **SFTP_SYNC** | Synchronize local and remote paths in the selected upload/download direction. |
| **SFTP_DELETE** | Delete a remote path. |
| **SFTP_RENAME** | Rename a remote path. |
| **SFTP_MKDIR** | Create a remote directory. |
| **SFTP_CHMOD** | Change permissions. Numeric modes such as `755` and symbolic modes such as `u+rw,o-w` are accepted. |
| **SFTP_CHOWN** | Change owner and/or group. Owner and group buttons can query the target and show available values. |
| **SFTP_COPY_REMOTE** | Copy a remote path to another remote path on the same target. |
| **SFTP_ARCHIVE** | Create a remote archive. |
| **RSYNC_SYNC** | Synchronize one or more directories via external `rsync` over SSH. |

Path fields provide local Finder/Explorer selection where the path is local and remote directory browsing where the path is remote. Remote browsing requires a selected target and host-key verification unless the job explicitly disables host-key verification.

#### Snippet Script Jobs

Snippet script jobs use the selected SnippetManager entry without requiring an open terminal tab. KorTTY resolves built-in snippet variables and stored SnippetManager variables before execution. Missing snippets, missing stored variable values, and unsupported snippet languages block the job and write the reason to the journal. Additional snippet parameters are entered one per line so values with spaces are passed as single script arguments.

#### SFTP Archive Jobs

SFTP archive jobs support ZIP, password-protected ZIP, TAR, and TAR.BZ2. Archive sources and exclude patterns accept one path or pattern per line. The archive can optionally be downloaded after creation.

When **Use sudo staging for SFTP paths** is enabled, KorTTY stages files in a temporary location and uses sudo-assisted remote commands such as `mv`, `cp`, `tar`, `chmod`, and `chown` where elevated rights are required. Cleanup failures are written to the journal.

## Rsync Jobs

`RSYNC_SYNC` supports upload and download between the local filesystem and saved SSH TCP connections.

- **Upload**: local source directories are synchronized under the remote target root.
- **Download**: remote source directories are synchronized under the local target root.
- **Multiple sources** are supported.
- **Delete missing files** adds `--delete`; it is off by default.
- For downloads from multiple group targets, KorTTY writes each target into its own subdirectory below the local target root to avoid overwriting files from another server.

KorTTY builds Rsync execution as a `ProcessBuilder` argument list instead of shell-concatenating the command. The command uses `-a --itemize-changes`; `--delete` is added only when the job checkbox is enabled.

### Rsync Prerequisites

- `rsync` is taken from `PATH`, unless an explicit binary path is configured in **Settings > SFTP > JobScheduler Rsync**.
- `ssh` must be available in `PATH`.
- Host-key pinning is required unless the job explicitly disables host-key verification.
- Password and private-key passphrase authentication use a temporary owner-only `SSH_ASKPASS` helper. Secrets, helper paths, and temporary secret-file paths are redacted before journaling.

## Journal Tab

The **Journal** tab lists job runs with local KorTTY timestamps, status, job name, and summary. The columns **Started**, **Status**, **Job**, and **Summary** are sortable; the default order shows the newest started entries first.

The search row can match all persisted journal fields or only selected columns such as status, job, summary, stdout, stderr, and detail. Enter multiple whitespace-separated terms when every term must occur somewhere in the selected search scope; use `*` inside a term as a wildcard, for example `backup*fail`.

Selecting a row shows stdout, stderr, and detail text. Use **Delete selected** to remove selected journal entries. By default, KorTTY automatically deletes scheduler journal entries older than 14 days; set the retention value to `0` to keep entries indefinitely.

The protocol/detail area below the table is separated by a vertical splitter. Resize it to give the output more or less height; KorTTY stores that divider position in the global settings. In the detail text area, mark text and right-click to copy the selected text to the clipboard.

Journal statuses include successful, failed, blocked, cancelled, and running/system entries. Reasons such as locked master password, missing host-key pin, missing `rsync`/`ssh`, unsupported Mosh target, or shutdown drain are written as journal details.

## Menu-Bar Status and Cancellation

When **Show Jobs status in menu bar** is enabled, KorTTY shows the scheduler status after **Help** only if an enabled scheduler entry exists or a job is currently running. The status shows the running job, cancellation state, or the next job with a live countdown.

Click the status menu to see:

- **Open JobScheduler...**
- running jobs and cancel entries
- up to five next queued jobs with start time and live countdown

Right-click the status label for a compact menu with cancel actions for running jobs and a shortcut to open JobScheduler. Cancellation requests are journaled and running SSH/Rsync work is interrupted cleanly where possible.

## Quitting KorTTY While Jobs Run

If KorTTY is about to exit while JobScheduler jobs are running, it shows a warning with the active job names. Choosing **Cancel** keeps KorTTY running. Choosing **Wait and quit** starts shutdown drain mode:

- new scheduled or manual job starts are blocked;
- the shutdown wait is written to the journal;
- KorTTY waits for running jobs to complete or cancel;
- KorTTY exits automatically after the drain finishes.

## Security and Secrets

- Host keys are pinned by default to prevent man-in-the-middle attacks on unattended execution.
- Sudo passwords are stored encrypted with the master password.
- SSH key passphrases and archive passwords are stored encrypted.
- KorTTY redacts managed secrets (passwords, passphrases, archive credentials) from journal output before persistence.
- If the master password is locked when a job needs SSH, sudo, API, or archive secrets, the job is blocked.

## Troubleshooting

!!! warning
    **JobScheduler job is blocked:** Open **Tools > JobScheduler... > Journal** and inspect the selected entry's detail text. Common causes are:
    - Locked master password
    - Missing host-key pin
    - Unsupported Mosh target
    - Missing `rsync` or `ssh` in PATH
    - Old host-key pin without OpenSSH public-key material for Rsync

    **JobScheduler Rsync cannot start:** Verify local `rsync --version` and `ssh -V`, or configure the Rsync binary path in **Settings > SFTP > JobScheduler Rsync**.

    **JobScheduler remote browser cannot open:** Select exactly one target and confirm the host key first, unless the job explicitly disables host-key verification.

---
