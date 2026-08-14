---
title: Configuration files
---

# Configuration files

KorTTY stores all application data and configuration under the `~/.kortty/` directory in your home folder. This guide documents every file and subdirectory, their purpose, and how they are used.

## Directory structure

```
~/.kortty/
├── connections.xml                    # Saved SSH connections
├── credentials.xml                    # Stored credentials (encrypted)
├── ssh-keys.xml                       # SSH key management
├── gpg-keys.xml                       # GPG keys for backup encryption
├── global-settings.xml                # Global application settings
├── llm/
│   ├── models.xml                     # Local GGUF registrations/settings
│   ├── models/                        # Managed GGUF weights
│   ├── runtime/                       # Versioned llama.cpp packages, activation and revocation state
│   ├── catalog/                       # Verified model/prompt catalog cache
│   └── run/                           # Temporary sidecar state
├── rag/
│   ├── stores.json                    # Knowledge stores and source configuration
│   └── stores/                        # Local HNSW snapshots
├── ai-chats.xml                       # Saved AI conversations
├── snippets.xml                       # Code snippets and scripts
├── snippet-variables.xml              # Snippet variable storage
├── job-scheduler.xml                  # JobScheduler jobs, host-key pins, sudo secrets, journal
├── ssh-host-keys.properties           # Interactive Terminal/SFTP/Mosh host-key pins
├── ssh-host-keys.properties.lock      # Transient cross-process writer lock (not backed up)
├── master.key                         # Hashed master password (PBKDF2)
├── master.autounlock                  # Optional auto-login password (obfuscated; owner-only)
├── terminal-effect-plugins.disabled   # Disabled terminal-effect plugin IDs
├── kortty.log                         # Application log file
├── history/                           # Terminal session history (compressed)
├── journals/                          # Session journals (one directory per journal)
├── terminal-logs/                     # Default folder for per-connection terminal logs
├── plugins/                           # Imported terminal-effect plugin JARs
├── bundled-plugins/                   # Runtime copies of bundled exportable plugins
├── projects/                          # Project files (.kortty)
├── i18n/                              # Generated language files (messages_*.properties)
└── ssh-keys/                          # Optional copied SSH keys (included in backups)
```

## Core configuration files

### connections.xml
Contains all saved SSH connections with their settings.

**Includes:**
- Connection name, host, port, username
- Authentication method (password, SSH key, temporary SSH key)
- Terminal appearance overrides (font, colors, size)
- SSH tunnels and jump server configuration
- Optional per-connection SSH host-key verification override (verify, don't verify, or inherit)
- Terminal effect plugins and animation speed
- Connection-specific terminal logging settings
- Per-connection session journal settings (enable, capture typed input, AI summaries, summary interval)
- Window geometry preferences
- Group/folder organization
- Optional free-text tag (used for search, bulk tagging and tag-based export)

**Security:** Connection passwords are encrypted with AES-256-GCM using the master password.

!!! note
    If a connection password is encrypted, it is stored in a hashed/encrypted format and cannot be viewed as plain text. When you open a saved connection, the password is automatically decrypted using your master password.

### credentials.xml
Centralized credential storage for username/password pairs.

**Includes:**
- Credential name, username, password
- Environment (Production, Development, Test, Staging)
- Server pattern (glob patterns like `*.example.com` or `10.0.0.*`)
- Auto-assignment to connections matching the pattern

**Security:** All passwords are encrypted with AES-256-GCM.

### ssh-keys.xml
Manages centralized SSH key storage.

**Includes:**
- SSH key path
- Key passphrase (if the key is protected)
- Optional description
- Key fingerprint

**Security:** Key passphrases are encrypted with AES-256-GCM using the master password.

!!! tip
    SSH keys referenced in this file can be kept in their original location or copied to `~/.kortty/ssh-keys/` via the *Copy to User Directory* action in SSH key management. Copied keys are included in encrypted backups; keys left in their original locations are only referenced and must be migrated separately.

### ssh-host-keys.properties

The versioned trust-on-first-use store for interactive Terminal and SFTP connections and the SSH bootstrap used by Mosh. Entries are keyed by normalized host name and port and contain the public-key algorithm, OpenSSH SHA-256 fingerprint, OpenSSH public-key line, and trust timestamp. A matching key is accepted silently after first-use confirmation; a changed key is hard-blocked and is not replaced automatically. When host-key verification is relaxed to accept-new for a connection, an unknown key is pinned without the confirmation prompt — a changed key is still refused in both modes.

Writes use a temporary file plus atomic replacement, while `ssh-host-keys.properties.lock` coordinates separate korTTY processes so their pins are merged safely. The properties file is included in encrypted backups; the transient lock file is not. This endpoint-based store is separate from the JobScheduler host-key pins in `job-scheduler.xml`, which are keyed by connection ID for unattended operations.

### gpg-keys.xml
Stores GPG key information for backup encryption.

**Includes:**
- GPG key ID
- Key email address
- Optional fingerprint
- Import source (system keyring or manual entry)

### global-settings.xml
Global application preferences and defaults.

**Contains:**
- UI theme and appearance settings
- Font family and default size
- UI font size (percent, 80–160%, or auto-derived from the display resolution) and the manual's separate text size (70–250%)
- Terminal color configuration
- Window geometry and state (position, size, maximized status)
- Dashboard visibility state
- Menu bar visibility preference
- Last ASCII Art dialog preview zoom level
- AI profile defaults, Text/Coding role assignments, embedded GGUF references, prompt presets, and knowledge-store associations
- RAG embedding model ID and llama.cpp preferred runtime backend/update policy
- Optional encrypted Hugging Face token
- Translation API settings
- Video/recording preferences
- Terminal logging defaults
- Session journal defaults (storage folder, capture-log format, AI summaries with interval and profile, AI screenshot analysis toggle, note translation language, line window and token budget, journal page appearance, live-log tail height, custom markers and marker rules) and the remembered journal window geometries
- Docked live session-journal panel: placement (hidden/left/right) and width
- PDF export watermark (off by default; custom text and colour) and document export footer (on by default; custom text)
- Master password auto-login flag (`skipMasterPasswordPrompt`)
- AI request timeout in minutes (0 = no limit)
- Snippet input-hardening defaults (enabled, options, max file size) and snippet translation target language
- "Open tool windows as tabs" flag
- SSH keep-alive settings
- SSH host-key verification opt-out: the global flag and the list of connection groups whose checking is relaxed to accept-new
- JobScheduler status display preference
- Terminal effect plugin defaults
- Backup encryption method and retention settings
- Connection timeout and retry defaults

### llm/models.xml

The atomically written JAXB registry for locally installed or referenced GGUF models.

**Contains:**

- Stable model ID and display name
- GGUF path and compatible `llama-server` executable path
- Backend (`AUTO`, `CPU`, `METAL`, or `VULKAN`)
- Context size, CPU threads, GPU layers, and idle-unload minutes

The registry contains paths and settings, not model weights or API keys. `llm/models/` holds managed GGUF copies, `llm/runtime/` holds independently updated native packages, and `llm/run/` holds temporary process directories, logs, and owner-only generated key files. Temporary keys are removed when the sidecar stops.

### llm/runtime/

The regenerable native-runtime area contains immutable package directories plus small atomic state files:

- `active-v1` points to the currently selected installation.
- `pending-first-launch-v1` records a candidate and its rollback base until a real GGUF-backed authenticated API start succeeds.
- `healthy-history-v1` retains at most the two newest confirmed, non-revoked installations.
- `revoked-v1` is the durable denylist learned from verified signed indexes; a revoked package also contains `.kortty-runtime-revoked`.
- `blocked-active-v1` remembers the runtime ID removed from active use by a withdrawal so the UI can explain why local AI remains blocked.
- `packages/` contains extracted verified installations, while `downloads/` is temporary staging protected by the updater lock.

Do not edit or delete the denylist/quarantine markers to re-enable a package. Runtime launches independently enforce them, and a compatible signed replacement must be installed instead. The entire directory is excluded from backup because packages and state can be recreated from the signed stable channel.

### llm/catalog/last-valid-catalog-v1.json

An atomic cache envelope containing the last model/prompt catalog payload and its detached signature. korTTY re-verifies the signature and strict schema before every cache use. If the application has no valid catalog public trust root, this file is ignored and the built-in bootstrap is used without a network refresh. The cache is regenerable and is not included in backups.

### rag/stores.json

The atomically written, owner-readable JSON registry for knowledge stores and their sources.

**Contains:**

- Store ID/name/type, local snapshot directory or Qdrant endpoint/collection, embedding model ID and vector dimensions
- Text, Coding, and autonomous-use assignments
- Per-source stable ID, canonical path, file/directory type, enabled flag, automatic/manual sync mode, size limit, include/exclude globs, `.gitignore` preference, content hashes, last status, file/chunk/problem counts, and last successful index time

The `rag/stores/` subdirectory holds regenerable `index.hnsw` snapshots. A v2 snapshot embeds its format version, vector dimensions, embedding model ID, hierarchical graph parameters, entry point, chunk metadata, vectors, node levels, and per-layer neighbors; a mismatch is rejected and requires a rebuild. A valid legacy single-layer v1 snapshot is rebuilt and atomically migrated when opened.

### job-scheduler.xml
All JobScheduler jobs and related data.

**Contains:**
- Job name, enabled status, action type (COMMAND, SNIPPET_SCRIPT, AI_AGENT, SFTP, RSYNC_SYNC)
- Schedule configuration (weekdays, times, intervals, date ranges)
- Target servers or groups
- Host-key pins (OpenSSH public-key material for unattended execution)
- Sudo passwords for servers and groups (encrypted)
- Journal entries with timestamps, exit codes, and redacted output
- Journal retention settings (auto-delete entries older than 14 days by default)

**Security:**
- Host-key pinning is required by default for unattended SSH/SFTP/Rsync execution
- Sudo passwords are encrypted with the master password
- Journal entries are stored with KorTTY-managed secrets redacted before persistence
- Archive passwords and backup encryption credentials are stored encrypted

!!! warning
    If the master password is locked and a job needs SSH, sudo, API, or archive secrets, the job is blocked and a journal entry is created explaining the issue.

### ai-chats.xml
Saved AI conversations and chat history.

**Includes:**
- Chat title and creation timestamp
- Conversation messages and responses
- Associated AI profile used for the chat
- Follow-up prompt history (for context)

**Note:** AI result tabs are not automatically saved. You must explicitly save them from the AI tab using the *Save* button to add them to this file.

### snippets.xml
Code snippets, scripts, and templates.

**Includes:**
- Snippet name, description, language
- Code content with syntax highlighting metadata
- Category/folder organization
- Tags and metadata
- Target system (Operating System column: Linux, macOS, Windows, etc.)
- Import/export history

**Features:**
- Support for JSON/XML/YAML import/export
- Plain-text script export
- ZIP archives with optional password or GPG encryption
- Local syntax highlighting with Monaco editor
- AI-assisted editing and code generation
- Persisted Mermaid flowcharts with stable code-reference node IDs
- One-liner export with optional script arguments

### snippet-variables.xml
Stores variable definitions for use within snippets.

**Includes:**
- Variable name, value, type
- Scope (local or shared)
- Default values and validation rules

### master.key
Binary file containing the hashed master password.

**Format:** PBKDF2 hash with 310,000 iterations

**Security:** This file does not contain the actual master password—only a cryptographic hash used to verify the password you enter on startup. If this file is lost or corrupted, you must restart KorTTY and set a new master password (though you will lose access to previously stored encrypted credentials and SSH key passphrases).

!!! warning
    If you forget your master password, delete `master.key` and `credentials.xml`, restart KorTTY, set a new master password, and re-enter your passwords. There is no recovery mechanism for the lost password.

### master.autounlock
Written only while [auto-login](settings/security.md) is enabled: a copy of the master password, **obfuscated only — not encrypted**, with owner-only file permissions. Deleting it (or disabling the option) restores the normal startup prompt.

### terminal-effect-plugins.disabled
Text file listing disabled terminal-effect plugin IDs (one per line).

**Purpose:** When you disable a terminal-effect plugin via *Plugins > Terminal Effects*, its ID is written to this file so it remains disabled after restart.

### kortty.log
Application log file.

**Contains:**
- Startup messages
- Connection attempts and results
- Terminal session events
- Configuration changes
- Errors and warnings
- Performance metrics (on request via JMX)

**Rotation:** The log file grows throughout the application session. Old logs are not automatically rotated (the log file persists until KorTTY exits or you manually delete it).

!!! tip
    Check this file when troubleshooting connection issues, plugin loading problems, or unexpected behavior. Common issues logged here include SSH errors, encryption failures, and import/export problems.

## Directories

### history/
Compressed terminal session history.

**Format:** GZIP-compressed text files, one per terminal session

**Naming:** `{session-id}_{timestamp}.history.gz` (for session history from terminal logging)

**Purpose:** Stores project/session scrollback history so reopened sessions can restore their terminal content.

**Access:** Terminal history is loaded automatically when you open a saved connection and displayed in the terminal history search feature.

!!! note
    Per-connection *Terminal Logging* does not write here: its generated log files go to the folder configured on the connection's Terminal Logging tab, or to `~/.kortty/terminal-logs/` when that folder is left empty.

### journals/
Session journals — one self-contained directory per journal (location configurable in **Settings > Logging > Session Journal**). Each journal directory holds `journal.xml` (the curated document: metadata, AI summaries, markers, notes, screenshot references), the append-only capture log `session-log.json` / `.xml` / `.yaml` (JSON Lines by default) with gzip-compressed rotated parts (default 25 MB per part), the generated `journal.html` timeline page, and `screenshots/*.png`. See [Session journal](../features/session-journal.md).

### terminal-logs/
Default target folder for [per-connection terminal logs](../features/terminal.md#terminal-logging) when a connection's log folder field is left empty. File naming, daily rotation, compression and retention follow the connection's logging configuration.

### plugins/
User-imported terminal-effect plugin JARs.

**Purpose:** External terminal-effect plugins that you import via *Plugins > Terminal Effects > Import*.

**Security:** Imported plugins are trusted Java code and are not sandboxed. Only import JARs from sources you trust.

**Cleanup:** If you delete a plugin from this directory, it will no longer be available in KorTTY. Disabled plugins remain in this directory but are listed in `terminal-effect-plugins.disabled`.

!!! warning
    Plugin dependencies must be shaded into the plugin JAR. Adjacent dependency JARs are not loaded automatically. See [Terminal effect plugins](../features/terminal-effect-plugins.md) for packaging guidelines.

### bundled-plugins/
Runtime copies of bundled exportable terminal-effect plugin JARs.

**Purpose:** Backup and working copies of built-in plugins that can be exported to external users.

**Contents:** The MU/TH/UR 6000 effect plugin and any other exportable bundled plugins.

**Auto-management:** KorTTY automatically manages this directory. Users should not edit it manually.

### projects/
Project files for saving and loading connection sets.

**Format:** KorTTY XML-based project format (`.kortty` files)

**Naming:** User-defined project names with `.kortty` extension (e.g., `production.kortty`, `development.kortty`)

**Contains:**
- List of open connections/tabs at project save time
- Window state (tabs, sizes, active tab)
- Dashboard state
- Project metadata and creation timestamp

**Purpose:** Quickly open a pre-configured set of connections for a specific project or workflow.

**Usage:** Save a project via *File > Save Project*, restore via *File > Open Project* or the project history menu.

### i18n/
Dynamically generated language translation files.

**Format:** Java properties files (`.properties`)

**Naming:** `messages_{language-code}.properties` (e.g., `messages_de.properties` for German, `messages_fr.properties` for French)

**Purpose:** Stores translations generated by translation APIs (Google Translate, DeepL, LibreTranslate, Microsoft Translator, or Yandex) when you choose to generate language files via *Settings > Translation*.

**Auto-refresh:** When you upgrade KorTTY to a new version, generated language files are marked as outdated. Use *Settings > Translation > Regenerate outdated* to refresh them with any new or changed UI keys.

!!! note
    Built-in languages (English, German, Italian, Spanish, Portuguese, French, Croatian, Dutch) are shipped with the application and do not use this directory. This directory is only used for dynamically generated translations.

### ssh-keys/
Optional directory for copied SSH keys.

**Purpose:** Stores copies of SSH keys in one place for backup and migration.

**How to use:**
1. Open *Management > Manage SSH Keys...*
2. Select an SSH key and click *Copy to User Directory*
3. The key is copied to `~/.kortty/ssh-keys/`

**Backup:** The key files in this directory are included when you create a backup via *Edit > Create Backup* (the archive is AES-256- or GPG-encrypted). On import they are restored with owner-only file permissions, and the import merges: local keys are never deleted, and existing files are only replaced with **Overwrite** enabled.

**Benefits:**
- Centralized location for all SSH keys
- Included in encrypted backups
- Easy migration to new machines

## Security summary

| Item | Security Method |
|------|-----------------|
| Master password | PBKDF2 hashing with 310,000 iterations |
| Connection passwords | AES-256-GCM encryption |
| SSH key passphrases | AES-256-GCM encryption |
| Interactive Terminal/SFTP/Mosh host keys | Normalized host:port TOFU with OpenSSH SHA-256 fingerprints and fail-closed change detection; the optional per-connection/group/global opt-out relaxes only unknown-key prompting to accept-new |
| Credentials (username/password) | AES-256-GCM encryption |
| JobScheduler sudo passwords | AES-256-GCM encryption |
| JobScheduler journal entries | Redacted secrets before persistence |
| Backup files | Password-protected ZIP or GPG encryption |
| API keys (AI profiles) | AES-256-GCM encryption with master password |
| Terminal effect plugins | Not encrypted; trusted local code, not sandboxed |

## File locations by platform

All files are stored in the same `~/.kortty/` directory across platforms:

- **macOS:** `/Users/{username}/.kortty/`
- **Windows:** `C:\Users\{username}\.kortty\` (or `%USERPROFILE%\.kortty\`)
- **Linux:** `/home/{username}/.kortty/` (or `$HOME/.kortty/`)

## Backup and recovery

When you create a backup via *Edit > Create Backup*, the following configuration is included:

- All `.xml` configuration files (connections, credentials, SSH key references and passphrases, GPG keys, global settings, JobScheduler, snippets, snippet variables, AI chats)
- `master.key`
- `projects/` directory
- `ssh-keys/` directory — copied SSH key files (restored with owner-only permissions; imports merge and never delete local keys)
- `ssh-host-keys.properties` interactive host-key trust store (not its transient `.lock` file)
- `llm/models.xml` local-model registrations
- `rag/stores.json` knowledge-store/source metadata

Managed GGUF weights, native runtime packages, temporary sidecar data, original source documents, and HNSW snapshots are excluded because they are large or regenerable. The `history/` and `i18n/` directories are also not part of a backup, and neither is the auto-login file `master.autounlock`.

The backup is encrypted (password-protected ZIP or GPG) and saved to a location you specify.

!!! tip
    Backups are the recommended way to migrate KorTTY to a new machine or to recover from data loss. Create a backup regularly and store it in a safe location.

## Accessing configuration files

You can edit KorTTY configuration directly by:

1. Closing KorTTY completely
2. Opening `~/.kortty/` in your file manager or terminal
3. Editing the XML or JSON file with a text editor
4. Restarting KorTTY to load the changes

!!! warning
    Editing XML/JSON files directly can corrupt your data if not done carefully. Never edit a binary `index.hnsw` snapshot. Always create a backup before manual editing. For most configuration tasks, use the KorTTY UI instead—it handles encryption, validation, and file format correctly.

## Troubleshooting

**Configuration file not found:**
- KorTTY creates the `~/.kortty/` directory and all necessary subdirectories on first launch.
- If a configuration file is missing, KorTTY uses sensible defaults and creates the file on next save.

**Encryption errors:**
- If you forget your master password, you must delete `master.key` and set a new password. Previously encrypted data will be inaccessible.

**Corrupted XML files:**
- If a `.xml` file becomes corrupted, restore from a backup or delete the file. KorTTY will recreate it with defaults on next save.

**Knowledge-store registry or snapshot cannot be read:**
- Restore `rag/stores.json` from a backup or recreate the store in the AI Manager. Delete only the affected regenerable `index.hnsw`, then choose **Update now** to rebuild it from the configured source files.

**Interactive SSH host key changed:**
- Do not reconnect until you have verified the new OpenSSH SHA-256 fingerprint with the server administrator and ruled out DNS, routing, or man-in-the-middle problems. KorTTY deliberately blocks the mismatch without retrying or replacing the stored pin.

**Plugin loading issues:**
- Check `kortty.log` for error messages related to plugin loading (e.g., duplicate IDs, missing services, class loading errors).
- Ensure the plugin JAR is in `~/.kortty/plugins/` and contains `META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin`.
- Use *Plugins > Terminal Effects > Reload* to refresh the plugin list.

**Log file size:**
- `kortty.log` grows during the application session. It is not automatically rotated. You can safely delete it while KorTTY is closed.

**Master password recovery:**
- There is no recovery for a forgotten master password. If you lose it, delete `master.key` and `credentials.xml`, restart KorTTY, and set a new password.
