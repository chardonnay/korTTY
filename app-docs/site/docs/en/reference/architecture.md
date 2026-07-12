---
title: Architecture
---

# Architecture (Developer)

korTTY is a modular JavaFX SSH client built on a layered architecture. This guide describes the core modules, their responsibilities, dependencies, and how they interact to deliver a seamless terminal experience with advanced features like job scheduling, AI integration, and terminal effect plugins.


![Startup & manager init](../assets/diagrams/app-startup-manager-init.svg)

## Architecture Overview

The following diagram shows the main components of korTTY and how they relate:

![Architecture](../assets/diagrams/architecture.svg)

## Modules and Dependencies

KorTTY is organized into distinct functional modules. The diagram below groups them by function:

![Modules overview](../assets/diagrams/modules-overview.svg)

### Module Breakdown

| **Module** | **Purpose** | **Key Classes** |
|---|---|---|
| **core** | SSH connectivity, session management, AI integration, terminal automation | `SSHSession`, `AiChatManager`, `TerminalAgentService`, `Mosh4jTtyConnector` |
| **ui** | JavaFX user interface, dialogs, terminal views, SFTP manager | `TerminalPane`, `ConnectionDialog`, `SFTPManagerDialog`, `SnippetEditor` |
| **model** | Domain objects for connections, credentials, snippets, jobs | `Connection`, `Credential`, `Snippet`, `JobScheduleEntry` |
| **jobscheduler** | Background job scheduling and execution | `JobScheduler`, `JobExecutor`, `JobJournal` |
| **security** | Master password, encryption/decryption, SSH key management | `MasterPasswordManager`, `AES256GCMEncryption`, `SSHKeyManager` |
| **persistence** | XML serialization, file I/O, repository pattern | `XMLConnectionRepository`, `CredentialRepository`, `JobSchedulerPersistence` |
| **plugin** | Terminal effect plugins (ServiceLoader SPI) | `TerminalEffectPlugin`, `TerminalEffectSession` |
| **teamwork** | Collaboration and remote access features | Team-based session sharing and coordination |
| **jmx** | Java Management Extensions monitoring | `SSHClientMonitor`, `SSHClientMonitorMBean` |
| **update** | Version checking and update notifications | Update service and version metadata |

## SithTermFX Terminal Engine

KorTTY uses **SithTermFX 1.2.1** as its primary terminal emulator, built from source during the build process. SithTermFX provides:

- **Terminal emulation**: VT100/xterm-compatible terminal rendering powered by a custom JavaFX control
- **OSC 8 hyperlinks**: Starting with SithTermFX 1.2.0, support for clickable explicit hyperlinks (restricted to safe URI schemes: `http`, `https`, `mailto`, `ftp`, `ftps`, `news`; `file://` limited to local host)
- **Session integration**: Direct JAXB marshaling of terminal state for session recording and replay
- **Color support**: Configurable ANSI and TrueColor handling with per-connection overrides

### Build Integration

The build process automatically:

1. Clones SithTermFX at tag `v1.2.1` into `vendor/sithtermfx` (no GitHub token required)
2. Builds it locally using Maven via the `installSithtermfxLocal` task
3. Installs artifacts to the local Maven repo (`mavenLocal()`)
4. Links SithTermFX core and UI modules into the korTTY JAR

No network access is needed after cloning; all build steps are deterministic and reproducible.

## Persistence and Configuration

KorTTY stores all configuration, credentials, and session state in XML files under `~/.kortty/` using JAXB (Jakarta XML Binding). This approach ensures:

- **Portability**: Easy manual inspection and migration across systems
- **Encryption**: Sensitive data (passwords, SSH key passphrases, master password hash) is encrypted with AES-256-GCM
- **Atomic writes**: File updates use temporary files and atomic rename to prevent corruption on crash
- **No database**: Self-contained; no separate database server or migration overhead

### Configuration File Structure

```
~/.kortty/
├── connections.xml              # Saved SSH/Mosh connections
├── credentials.xml              # Stored usernames/passwords for env-specific targets
├── ssh-keys.xml                 # SSH key references and encrypted passphrases
├── gpg-keys.xml                 # GPG key management for backup encryption
├── global-settings.xml          # Application-wide settings (theme, language, AI profiles)
├── job-scheduler.xml            # JobScheduler jobs, host-key pins, sudo secrets, journal
├── terminal-effect-plugins.disabled # Disabled terminal-effect plugin IDs (one per line)
├── master-password-hash         # PBKDF2-hashed master password (310,000 iterations)
├── kortty.log                   # Application log file
├── history/                     # Compressed terminal session logs
├── plugins/                     # Imported external terminal-effect plugin JARs
├── bundled-plugins/             # Runtime copies of bundled exportable plugin JARs
├── projects/                    # Project files (connection sets with saved layout)
├── i18n/                        # Dynamically generated language property files
└── ssh-keys/                    # Optional: copied SSH keys for backup inclusion
```

### Serialization and Encryption

**JAXB Marshaling** converts domain objects to/from XML with automatic schema validation. Key repositories include:

- `XMLConnectionRepository`: Manages `Connection` objects with SFTP, jump server, and tunnel details
- `CredentialRepository`: Stores environment-specific credential patterns
- `SSHKeyManager`: Wraps imported SSH keys with encrypted passphrases
- `JobSchedulerPersistence`: Persists `JobScheduleEntry` objects and execution journal

**AES-256-GCM encryption** protects sensitive fields:

- Master password is hashed once on first login, stored as `master-password-hash`
- All encrypted fields use AES-256-GCM with random IVs derived from the master password
- SSH key passphrases, connection passwords, and sudo secrets are encrypted before persistence
- Backup archives can be encrypted with password-protected ZIP or GPG

## Core Module Organization

### SSH and Session Management

| **Component** | **Responsibility** |
|---|---|
| `SSHSession` | Wraps Apache SSHD client and manages connection lifecycle |
| `SSHKeyManager` | Centralized SSH key storage with encrypted passphrase support |
| `Mosh4jTtyConnector` | Mosh protocol connector using the mosh4j library (dynamically loaded) |
| `NativeMoshTtyConnector` | Native OS Mosh support (fallback) |
| `TemporarySSHKeyManager` | Time-limited SSH keys (e.g., from CyberArk) |

### AI and Agent Integration

| **Component** | **Responsibility** |
|---|---|
| `AiChatManager` | Manages AI chat sessions and conversation history |
| `AiCliProviderRegistry` | Maps AI profile configurations to provider endpoints (OpenAI, Anthropic, LM Studio) |
| `TerminalAgentService` | Executes agent workflows: probes SSH session, sends task to model, validates/executes commands |
| `AiInternetAccessConfiguration` | Configures web tools (Tavily, Brave Search, SearXNG, etc.) per profile |
| `AiChatExportService` | Exports AI chats to Markdown, PDF, YAML, JSON, XML, Asciidoctor |

### Job Scheduling

| **Component** | **Responsibility** |
|---|---|
| `JobScheduler` | Main scheduler service; manages scheduled jobs and execution queue |
| `JobExecutor` | Executes individual jobs (SSH command, script, AI agent, SFTP, Rsync) |
| `JobJournal` | Persistent audit log with redaction support and auto-cleanup (14 days default) |

## UI Module Organization

The UI layer is built on JavaFX and organized into logical components:

| **Component** | **Purpose** |
|---|---|
| `MainWindow` | Top-level application window containing menu bar, tab bar, terminal panes, dashboard, SFTP browser |
| `TerminalPane` | Single terminal tab with split-pane support and inline AI activity panel |
| `ConnectionDialog` | Multi-tab editor for connection details (SSH, tunnels, jump server, logging, etc.) |
| `SFTPManagerDialog` | Dual-panel file manager for local and remote file operations |
| `SnippetEditor` | Monaco-powered code editor with syntax highlighting, AI assistance, and Mermaid flowcharts |
| `JobSchedulerDialog` | Job creation, scheduling, and journal inspection |
| `QuickConnectDialog` | Fast connection search and frequently-used connection shortcuts |

## Security Architecture

### Master Password and Encryption

1. **First Launch**: User creates a master password (minimum 6 characters, strength-checked via zxcvbn)
2. **Storage**: Password is hashed using PBKDF2 with 310,000 iterations and stored in `~/.kortty/master-password-hash`
3. **Unlocking**: Master password unlocks all encrypted data (connection passwords, SSH key passphrases, credentials, backup archive passwords)
4. **Encryption**: AES-256-GCM with random IVs; encryption/decryption happens on-demand when accessing sensitive fields

### Host Key Verification

- **JobScheduler**: Requires pinned host-key fingerprints before unattended execution (SSH, SFTP, Rsync)
- **Storage**: Host keys are stored in `job-scheduler.xml` with OpenSSH public-key material (needed for Rsync integration)
- **Override**: Per-job override to disable verification when risk is explicitly accepted

### Credential Management

- **Environment-specific**: Credentials can be scoped to Production, Development, Test, or Staging
- **Glob patterns**: Server patterns like `*.example.com` or `10.0.0.*` automatically match connections
- **Stored encrypted**: All credential passwords use AES-256-GCM

## Plugin System

KorTTY supports **terminal effect plugins** via Java `ServiceLoader` for customizing terminal appearance and behavior.

### Plugin Architecture

| **SPI Interface** | **Responsibility** |
|---|---|
| `TerminalEffectPlugin` | Plugin metadata (ID, name, description) and session factory |
| `TerminalEffectSession` | Lifecycle hooks (init, render, cleanup) and optional TtyConnector wrapping |
| `TerminalEffectContext` | Access to overlay API, current SithTermFX widgets, animation speed, appearance |
| `TerminalEffectAppearance` | Optional font/color/cursor overrides |
| `TerminalEffectConnectorWrapper` | Transparent command stream wrapper; KorTTY can safely unwrap |

### Plugin Loading

- **Bundled plugins**: Loaded from the application classpath (e.g., MOTHER effect)
- **External plugins**: Imported as `.jar` files into `~/.kortty/plugins/`
- **Management**: Enable/disable individual plugins via `Plugins → Terminal Effects` without uninstalling
- **Exportable plugins**: Some plugins can be exported as standalone JARs for distribution

### Security Note

External plugins are **trusted local code** and are not sandboxed. Only import JARs from sources you trust. Dependencies not already bundled with korTTY must be shaded into the plugin JAR.

## External Dependencies

KorTTY relies on carefully curated, production-tested dependencies:

| **Category** | **Library** | **Version** | **Purpose** |
|---|---|---|---|
| **SSH** | Apache SSHD (core, common, sftp) | 2.12.0 | SSH protocol implementation |
| | Ed25519 (net.i2p.crypto:eddsa) | 0.3.0 | EdDSA key support |
| **Terminal** | SithTermFX (core, ui) | 1.2.0 | Terminal emulator engine |
| | Lanterna | 3.1.2 | Text-based UI components |
| | pty4j (JetBrains) | 0.12.25 | PTY allocation for Mosh |
| **Data** | Jakarta XML Bind | 4.0 | JAXB serialization |
| | Gson | 2.13.2 | JSON parsing |
| | zip4j | 2.11.5 | ZIP encryption |
| **Archive** | Apache Commons Compress | 1.25.0 | TAR, BZ2, XZ support |
| | Tukaani xz | 1.9 | XZ compression |
| **UI** | JavaFX | 21 | Application framework |
| | Monaco Editor | 0.55.1 | Code editor component |
| | Mermaid | 11.16.0 | Local diagram parsing, SVG rendering, and PNG rasterization |
| | MathJax | 3.2.2 | Local AI-chat formula rendering |
| | google-java-format | 1.35.0 | Java code formatting |
| **Utilities** | jfiglet | 0.0.9 | ASCII art banners |
| | zxcvbn | 1.9.0 | Password strength (offline) |
| **Logging** | SLF4J / Logback | 2.0.9 / 1.4.14 | Structured logging |
| **Optional** | mosh4j | 2.0.2 | Mosh protocol (dynamically loaded) |

### Dynamic Dependencies

- **mosh4j**: Its five SHA-256-pinned, architecture-specific release JARs and protobuf are bundled in native builds and dynamically loaded only when Mosh is needed. The child loader reuses Bouncy Castle from the application parent instead of shipping a second copy.
- **rsync / ssh**: External commands used by JobScheduler Rsync jobs
- **ffmpeg**: Optional; used for terminal recording video export

## Build Process

### Compilation and Packaging

1. **SithTermFX build**: Cloned and built locally (Maven); its accidentally published JUnit dependency is excluded from the runtime.
2. **Browser assets**: An isolated, pinned Node.js is used only at build time for Monaco and for JavaFX-WebKit compatibility processing of the SHA-256-pinned Mermaid bundle. Monaco's editor and diff pages share one mode-aware IIFE/CSS pair while retaining all five workers and language services. Mermaid and MathJax remain separate lazy resources; Prettier Standalone with five selected plugins and the sql-formatter UMD build run without Node at runtime.
3. **External formatter payload**: Only shfmt, Perl::Tidy and their manifest are staged beside the app; the logo video is stored once per source surface as H.264/yuv420p at 640×360 without audio.
4. **Clean native staging**: `prepareJpackage` uses a final Gradle `Sync`, so obsolete dependencies, formatter trees and Mosh architectures are deleted. Bouncy Castle is deduplicated, and JNA/pty4j are repacked with only the current target's native paths and binary architecture.
5. **Native packaging and gates**: The selected Gradle JDK 25 toolchain supplies `jpackage` for .app/.dmg, .exe/.msi, .deb and .rpm output. `scripts/package-size-report.py` emits JSON/Markdown component reports and CI enforces the committed release comparison, at least 15% installer reduction, absolute app/DMG limits and frozen verified-size budgets with 2% tolerance.

### Classpath and Module Path

- **Compile-time**: On JDK 25 the JavaFX `jdk-jsobject` artifact is supplied on the upgrade module path so WebView bridges compile consistently; the module is absent from JDK 26.
- **Packaging-time**: Gradle resolves and invokes the same Temurin JDK 25 toolchain used for compilation, so the trimmed runtime contains `jdk.jsobject` even when the system `java`/`jpackage` is newer.
- **Module path**: JavaFX, SithTermFX and Apache SSHD are available during compilation; unused `javafx.fxml` and `java.scripting` are not included in the packaged runtime.

## Data Flow and Integration Points

### SSH Connection Flow

```
User Input (Quick Connect)
    ↓
Connection Manager (lookup saved or new connection)
    ↓
SSHSession (Apache SSHD or Mosh4j connector)
    ↓
Terminal Pane (SithTermFX rendering)
    ↓
Dashboard (status display, job monitor)
```

### AI Integration Flow

```
Selected Terminal Text
    ↓
AI Action (Summarize / Solve Problem / Ask)
    ↓
AiChatManager (prepare request with profile/model)
    ↓
AiCliProviderRegistry (map to endpoint: OpenAI, Claude, LM Studio, etc.)
    ↓
AI Response Tab (render with follow-up composer)
    ↓
Persistence (saved chats stored in global-settings.xml)
```

### Job Scheduling Flow

```
User creates scheduled job
    ↓
JobScheduler stores in job-scheduler.xml
    ↓
JobExecutor runs on schedule (background thread)
    ↓
JobJournal records output with redaction
    ↓
Menu-bar status displays next runs / live countdown
```

## Logging and Diagnostics

- **Log file**: `~/.kortty/kortty.log` (SLF4J with Logback backend)
- **JMX monitoring**: MBean `de.kortty:type=SSHClient` exposes active connections, memory, buffered text size
- **JobScheduler journal**: Detailed execution logs with configurable retention (14 days default, unlimited if set to 0)
- **Terminal history**: Compressed session logs in `~/.kortty/history/`
- **Terminal recording**: Optional replay files in `~/.kortty/recordings/`

## Thread Model

- **UI Thread**: JavaFX application thread handles all rendering and user interaction
- **SSH Session Threads**: One thread per active SSH connection (Apache SSHD pool)
- **Job Executor Threads**: Background thread pool for JobScheduler execution
- **AI Chat Threads**: Background threads for API requests (non-blocking UI)
- **File I/O Threads**: Async writes for history, journal, and recordings
- **Web formatter requests**: Background callers are serialized with one total timeout; creation, loading and invocation of the lazy Prettier/SQL WebView remain confined to the JavaFX application thread, and failures discard the engine generation.
- **Mermaid render requests**: Background callers receive `CompletableFuture` results while all access to the single lazy renderer WebView stays on the JavaFX application thread. Requests are serialized; cancellation, a 30-second timeout, or a WebEngine error discards the engine generation, and idle cleanup releases the hidden WebView.

## Extensibility Points

### For Plugin Developers

1. **Terminal Effects**: Implement `TerminalEffectPlugin` and register via `ServiceLoader` (see `TERMINAL_EFFECT_PLUGINS.adoc`)
2. **Custom Formatters**: Add support in the snippet editor for new languages
3. **AI Skills**: Import custom AI instruction sets via `Settings → AI Skills`

### For Integrators

1. **JAXB Repositories**: Extend `XMLConnectionRepository` or `CredentialRepository` to add custom data sources
2. **SSH Session Hooks**: Subclass `SSHSession` to inject custom connection logic
3. **JobScheduler Actions**: Add new action types to `JobExecutor` (e.g., custom SFTP operations)

## Performance Considerations

- **Session caching**: Active SSH connections are cached to avoid reconnection overhead
- **Lazy loading**: Connection details loaded on demand, not all at once
- **Compression**: Terminal history and recordings use gzip compression
- **Throttling**: Terminal rendering updates are batched to reduce UI thread load
- **Memory pooling**: Large buffers for terminal text and SFTP file listing are reused

## Security Best Practices

1. **Master password**: Set a strong, unique password; it is never transmitted or logged
2. **SSH keys**: Store in `~/.kortty/ssh-keys/` for backup inclusion; passphrases are encrypted
3. **Host key pinning**: Enable before unattended JobScheduler execution
4. **Backup encryption**: Use password-protected ZIP or GPG encryption
5. **AI profiles**: Prefer local LM Studio endpoints for sensitive data; verify trust of remote endpoints
6. **Avoid logging secrets**: JobScheduler journal redacts stored secrets before persistence
