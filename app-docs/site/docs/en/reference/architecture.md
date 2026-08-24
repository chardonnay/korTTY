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
| **core** | SSH connectivity, shared interactive host-key trust, session management, AI integration, terminal automation | `SSHSession`, `SshHostKeyTrustManager`, `AiChatManager`, `TerminalAgentService`, `Mosh4jTtyConnector` |
| **ai** | Signed model/prompt catalog, Hugging Face metadata/downloads, embedded llama.cpp and MLX runtimes, and signed runtime packages | `AiCatalogService`, `HuggingFaceClient`, `LlamaRuntimeManager`, `LlamaRuntimePackageInstaller`, `EmbeddedMlxAiService`, `MlxRuntimeLocator` |
| **rag** | Safe source scanning, extraction, chunking, embeddings, vector stores, synchronization, and bounded retrieval | `RagSourceScanner`, `RagSourceSynchronizer`, `LocalHnswStore`, `RagRuntimeService` |
| **ui** | JavaFX user interface, dialogs, terminal views, SFTP manager | `TerminalView`, `TerminalTab`, `ConnectionEditDialog`, `SFTPManagerDialog`, `SnippetEditDialog` |
| **model** | Domain objects for connections, credentials, snippets, jobs | `ServerConnection`, `StoredCredential`, `Snippet`, `JobSchedule` |
| **jobscheduler** | Background job scheduling and execution | `JobSchedulerService`, `JobSchedulerJobRunner`, `JobJournalEntry` |
| **security** | Master password, encryption/decryption, password vault | `MasterPasswordManager`, `EncryptionService`, `PasswordVault` |
| **persistence** | XML serialization, file I/O, repository pattern | `XMLConnectionRepository`, `HistoryStorage` |
| **plugin** | Terminal effect plugins (ServiceLoader SPI) | `TerminalEffectPlugin`, `TerminalEffectSession` |
| **power** | Activity-aware sleep and App Nap management per platform | `PowerManagementCoordinator`, `MacPowerManagementBackend` |
| **telemetry** | Consent-gated anonymous usage events | `TelemetryService`, `Telemetry` |
| **teamwork** | Collaboration and remote access features | Team-based session sharing and coordination |
| **jmx** | Java Management Extensions monitoring | `SSHClientMonitor`, `SSHClientMonitorMBean` |
| **update** | Version checking and update notifications | Update service and version metadata |

## SithTermFX Terminal Engine

KorTTY uses **SithTermFX 1.2.1** as its primary terminal emulator, built from source during the build process. SithTermFX provides:

- **Terminal emulation**: VT100/xterm-compatible terminal rendering powered by a custom JavaFX control
- **OSC 8 hyperlinks**: Starting with SithTermFX 1.2.0, support for clickable explicit hyperlinks (restricted to safe URI schemes: `http`, `https`, `mailto`, `ftp`, `ftps`, `news`; `file://` limited to local host)
- **Session integration**: Direct JAXB marshaling of terminal state for session recording and replay
- **Color support**: Configurable ANSI and TrueColor handling with per-connection overrides
- **Reviewed boundary fix**: A pinned korTTY patch rejects the non-existent row at `line == height` during hyperlink hit-testing, preventing bottom-row `TerminalTextBuffer` range errors
- **Reviewed shortcut-chord fix**: A second pinned korTTY patch stops shortcut-chord `KEY_TYPED` characters (for example ++cmd+shift+d++) from reaching the pty or broadcast panes

### Build Integration

The build process automatically:

1. Clones SithTermFX at tag `v1.2.1` into `vendor/sithtermfx` (no GitHub token required)
2. Applies the reviewed patches in `patches/sithtermfx/` — `1.2.1-terminal-panel-bottom-row.patch` and `1.2.1-terminal-panel-meta-shortcut-key-typed.patch` — in order, failing if a patch neither applies nor already matches the source
3. Builds it locally using Maven via the `installSithtermfxLocal` task
4. Installs artifacts to the local Maven repo (`mavenLocal()`), including one marker resource per patch that lets Gradle reject an unpatched cached UI JAR
5. Links SithTermFX core and UI modules into the korTTY JAR

No network access is needed after cloning; all build steps are deterministic and reproducible.

## Persistence and Configuration

KorTTY stores its main configuration, credentials, and session state under `~/.kortty/`, primarily as JAXB XML. Local-model registration also uses JAXB XML, while knowledge-store configuration is strict JSON and the local vector graph is a regenerable binary snapshot. This approach ensures:

- **Portability**: Easy manual inspection and migration across systems
- **Encryption**: Sensitive data (passwords, SSH key passphrases, master password hash) is encrypted with AES-256-GCM
- **Atomic writes**: File updates use temporary files and atomic rename to prevent corruption on crash
- **No required database**: Local HNSW is self-contained; Qdrant is an optional second vector backend

### Configuration File Structure

```
~/.kortty/
├── connections.xml              # Saved SSH/Mosh connections
├── credentials.xml              # Stored usernames/passwords for env-specific targets
├── ssh-keys.xml                 # SSH key references and encrypted passphrases
├── gpg-keys.xml                 # GPG key management for backup encryption
├── global-settings.xml          # Application-wide settings (theme, language, AI profiles)
├── llm/
│   ├── models.xml               # Local GGUF registrations and typed launch settings
│   ├── models/                  # Managed GGUF weights (regenerable; excluded from backup)
│   ├── runtime/                 # Versioned native llama.cpp packages
│   ├── catalog/                 # Regenerable signature-verified catalog cache
│   └── run/                     # Temporary sidecar key files and logs
├── rag/
│   ├── stores.json              # Knowledge-store and source configuration
│   └── stores/                  # Regenerable local HNSW snapshots
├── job-scheduler.xml            # JobScheduler jobs, host-key pins, sudo secrets, journal
├── ssh-host-keys.properties     # Shared interactive Terminal/SFTP/Mosh host-key pins
├── terminal-effect-plugins.disabled # Disabled terminal-effect plugin IDs (one per line)
├── master.key                   # PBKDF2-hashed master password (310,000 iterations)
├── kortty.log                   # Application log file
├── history/                     # Compressed terminal session logs
├── plugins/                     # Imported external terminal-effect plugin JARs
├── bundled-plugins/             # Runtime copies of bundled exportable plugin JARs
├── projects/                    # Project files (connection sets with saved layout)
├── i18n/                        # Dynamically generated language property files
└── ssh-keys/                    # Optional: copied SSH keys (included in backups)
```

### Serialization and Encryption

**JAXB Marshaling** converts domain objects to/from XML with automatic schema validation. Key repositories include:

- `XMLConnectionRepository`: Manages `Connection` objects with SFTP, jump server, and tunnel details
- `CredentialRepository`: Stores environment-specific credential patterns
- `SSHKeyManager`: Wraps imported SSH keys with encrypted passphrases
- `JobSchedulerPersistence`: Persists `JobScheduleEntry` objects and execution journal

**AES-256-GCM encryption** protects sensitive fields:

- Master password is hashed once on first login, stored as `master.key`
- All encrypted fields use AES-256-GCM with random IVs derived from the master password
- SSH key passphrases, connection passwords, and sudo secrets are encrypted before persistence
- Backup archives can be encrypted with password-protected ZIP or GPG

## Core Module Organization

### SSH and Session Management

| **Component** | **Responsibility** |
|---|---|
| `SSHSession` | Wraps Apache SSHD client and manages connection lifecycle |
| `SshHostKeyTrustManager` | Shares normalized host:port TOFU pins across interactive Terminal, SFTP, and Mosh bootstrap connections with atomic, cross-process persistence |
| `SSHKeyManager` | Centralized SSH key storage with encrypted passphrase support |
| `Mosh4jTtyConnector` | Mosh protocol connector using the mosh4j library (dynamically loaded) |
| `NativeMoshTtyConnector` | Native OS Mosh support (fallback) |
| `TemporarySSHKeyManager` | Time-limited SSH keys (e.g., from CyberArk) |

### AI and Agent Integration

| **Component** | **Responsibility** |
|---|---|
| `AiChatManager` | Manages AI chat sessions and conversation history |
| `AiServiceFactory` | Maps a profile to HTTP, Anthropic, local CLI, or embedded llama.cpp transport, then adds prompt presets and optional RAG |
| `AiCatalogService` | Returns a reverified cache/bootstrap immediately and schedules one signed stable-channel refresh for recommendations and preset-family mappings |
| `LlamaRuntimeManager` | Shares one isolated authenticated sidecar per compatible GGUF configuration and grants reference-counted request leases |
| `RagAugmentedAiService` | Retrieves bounded excerpts for ordinary AI actions and adds the untrusted context before the model preset |
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
| `LocalModelManagerPane` | Searches/downloads/imports GGUF files and controls concurrent llama.cpp sidecars |
| `RagKnowledgeStorePane` | Creates knowledge stores, previews sources, displays persisted indexing state, synchronizes them, and runs retrieval tests |
| `JobSchedulerDialog` | Job creation, scheduling, and journal inspection |
| `QuickConnectDialog` | Fast connection search and frequently-used connection shortcuts |

## Security Architecture

### Master Password and Encryption

1. **First Launch**: User creates a master password (minimum 6 characters, strength-checked via zxcvbn)
2. **Storage**: Password is hashed using PBKDF2 with 310,000 iterations and stored in `~/.kortty/master.key`
3. **Unlocking**: Master password unlocks all encrypted data (connection passwords, SSH key passphrases, credentials, backup archive passwords)
4. **Encryption**: AES-256-GCM with random IVs; encryption/decryption happens on-demand when accessing sensitive fields

### Host Key Verification

- **Interactive Terminal/SFTP/Mosh bootstrap**: One shared TOFU verifier is keyed by normalized host name and port. First use shows the OpenSSH SHA-256 fingerprint with **No** as the default; an exact match is silent, while a changed key is hard-blocked without retry.
- **Interactive storage**: `ssh-host-keys.properties` stores public-key material through an atomic replacement guarded by both in-process and cross-process locks. Its transient `.lock` companion is not backed up.
- **JobScheduler**: Unattended SSH, SFTP, and Rsync use separate connection-ID-based pins in `job-scheduler.xml`, including OpenSSH public-key material needed by Rsync. A per-job override can disable that verification only when the risk is explicitly accepted.

### Credential Management

- **Environment-specific**: Credentials can be scoped to Production, Development, Test, or Staging
- **Glob patterns**: Server patterns like `*.example.com` or `10.0.0.*` automatically match connections
- **Stored encrypted**: All credential passwords use AES-256-GCM

### Embedded AI and RAG isolation

- Each embedded model process binds only to `127.0.0.1` on a random port and requires a generated API key stored in an owner-only temporary file.
- The fixed llama.cpp launch command enables offline mode and disables the web UI, agent, UI MCP proxy, and slots endpoint; inherited `LLAMA_ARG_*` and Hugging Face token variables are removed.
- Hugging Face tokens and AI profile API keys are encrypted with the master password. Model downloads are pinned to an immutable revision and verified with SHA-256.
- RAG accepts only centrally allowlisted, content-validated text. Retrieved excerpts are bounded and wrapped as explicitly untrusted data so indexed instructions cannot replace korTTY's system/action contract.
- Runtime update indexes are verified as exact bytes with Ed25519 before package URLs are parsed; packages also have signed size/SHA-256 metadata and safe ZIP extraction limits.
- Runtime withdrawals are persisted before process shutdown through a runtime-root denylist plus package marker. Active pointers and unsafe rollback-history entries are removed, registered models are rebound to a non-executable quarantine marker, and the launch path rechecks the marker so stale registry state cannot revive a withdrawn binary.
- A package that passes the lightweight version health check remains pending until its first real GGUF-backed authenticated API start. `LlamaRuntimeFirstLaunchRecovery` confirms it on readiness or restores the newest healthy non-revoked installation and model bindings after a start failure.
- The independent model/prompt catalog has its own Ed25519 trust root and strict schema. Its cache is reverified before use; a missing key disables both cache trust and network refresh and selects the compiled bootstrap.
- Remote Qdrant uses HTTPS, with plain HTTP restricted to a loopback test/local service.

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
| **SSH** | Apache SSHD (core, common, sftp) | 2.19.0 | SSH protocol implementation |
| | BouncyCastle (bcprov, bcpkix) | 1.85 | Cryptographic provider, SSH key parsing and Ed25519/EdDSA key support |
| **Terminal** | SithTermFX (core, ui) | 1.2.1 plus pinned korTTY boundary and shortcut-chord patches | Terminal emulator engine |
| | Lanterna | 3.1.5 | Text-based UI components |
| | pty4j (JetBrains) | 0.12.25 | PTY allocation for Mosh |
| **Platform** | JNA (jna, jna-platform) | 5.19.1 | Native desktop power-management integration |
| **Data** | Jakarta XML Bind | 4.0.5 (jaxb-runtime 4.0.9) | JAXB serialization |
| | Gson | 2.14.0 | JSON parsing |
| | zip4j | 2.11.6 | ZIP encryption |
| | jtokkit | 1.1.0 | Token counting for AI requests |
| | PDFBox | 3.0.8 | PDF export and RAG text extraction |
| **Archive** | Apache Commons Compress | 1.28.0 | TAR, BZ2, XZ support |
| | Tukaani xz | 1.12 | XZ compression |
| | zstd-jni | 1.5.7-15 | zstd compression for rotated session-journal parts |
| **UI** | JavaFX | 21 | Application framework |
| | Monaco Editor | 0.56.0 | Code editor component |
| | Mermaid | 11.16.1 | Local diagram parsing, SVG rendering, and PNG rasterization |
| | MathJax | 3.2.2 | Local AI-chat formula rendering |
| | google-java-format | 1.36.1 | Java code formatting |
| **Utilities** | jfiglet | 0.0.9 | ASCII art banners |
| | zxcvbn | 1.9.0 | Password strength (offline) |
| **Logging** | SLF4J / Logback | 2.0.18 / 1.6.1 | Structured logging |
| **Optional** | mosh4j | 2.0.2 | Mosh protocol (dynamically loaded) |
| **Local AI** | llama.cpp `llama-server` | Source-pinned runtime package | Local GGUF chat-completions and embeddings sidecar |

### Dynamic Dependencies

- **mosh4j**: Its five SHA-256-pinned, architecture-specific release JARs and protobuf are bundled in native builds and dynamically loaded only when Mosh is needed. The child loader reuses Bouncy Castle from the application parent instead of shipping a second copy.
- **rsync / ssh**: External commands used by JobScheduler Rsync jobs
- **ffmpeg**: Optional; used for terminal recording video export
- **llama.cpp runtime**: Downloaded independently under `~/.kortty/llm/runtime/`; CPU packages cover every supported platform, Metal is available on macOS, and Vulkan is available for supported Windows/Linux targets. It is never folded into the base installer.
- **Qdrant**: Optional external vector service. Remote endpoints require HTTPS; HTTP is loopback-only. Local HNSW is dependency-free and remains the default knowledge-store backend.

## Build Process

### Compilation and Packaging

1. **SithTermFX build**: Cloned and built locally (Maven); its accidentally published JUnit dependency is excluded from the runtime.
2. **Browser assets**: An isolated, pinned Node.js is used only at build time for Monaco and for JavaFX-WebKit compatibility processing of the SHA-256-pinned Mermaid bundle. Monaco's editor and diff pages share one mode-aware IIFE/CSS pair while retaining all five workers and language services. Mermaid and MathJax remain separate lazy resources; Prettier Standalone with five selected plugins and the sql-formatter UMD build run without Node at runtime.
3. **External formatter payload**: Only shfmt, Perl::Tidy and their manifest are staged beside the app; the logo video is stored once per source surface as H.264/yuv420p at 640×360 without audio.
4. **Clean native staging**: `prepareJpackage` uses a final Gradle `Sync`, so obsolete dependencies, formatter trees and Mosh architectures are deleted. Bouncy Castle is deduplicated, and JNA/pty4j are repacked with only the current target's native paths and binary architecture.
5. **Native packaging and gates**: The selected Gradle JDK 25 toolchain supplies `jpackage` for .app/.dmg, .exe/.msi, .deb and .rpm output. `scripts/package-size-report.py` emits JSON/Markdown component reports and CI enforces the committed release comparison, at least 15% installer reduction, absolute app/DMG limits and frozen verified-size budgets with 2% tolerance.
6. **llama.cpp runtime packaging**: Separate Gradle tasks verify the pinned upstream tag/commit/archive SHA-256, build only `llama-server` plus required shared libraries, stage a backend-specific tree, and produce a reproducible immutable ZIP and signed-index descriptor input. The weekly runtime workflow opens candidate PRs; a scope job runs the full platform/backend matrix only when the pin file, the workflow, or the llama Java sources changed (a `build.gradle.kts`-only change gets a single smoke leg); every built leg runs a native link smoke, the reference package runs the full authenticated chat/embedding/JSON/sleep/parallel-sidecar contract, and a protected `llama-runtime-signing` environment with required reviewers gates manual stable promotion from `main`.
7. **Model/prompt catalog promotion**: A separate manual `main`-only workflow validates the canonical strict-schema JSON, requires a sequence greater than the latest immutable release, runs schema/trust-chain tests, matches the signing key to the application trust root, signs the exact bytes, and publishes through the reviewer-protected `ai-catalog-signing` environment without a preview channel.

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
Worker-thread SSH handshake + progress UI
    ↓
Shared host:port TOFU verification (Terminal/SFTP/Mosh bootstrap)
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
AI Action + deterministic Text/Coding role
    ↓
AI profile (HTTP / CLI / embedded GGUF)
    ↓
Action contract → AI Skills → optional bounded RAG → model preset
    ↓
AiServiceFactory (remote provider, local CLI, or runtime lease)
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
- **Test isolation**: The test Logback configuration writes to its own console only and never creates or appends to the real user's `~/.kortty/logs`

## Thread Model

- **UI Thread**: JavaFX application thread handles all rendering and user interaction
- **SSH Session Threads**: One thread per active SSH connection (Apache SSHD pool)
- **Split-connection handshake**: Same-server and newly selected split connections perform network setup on a worker while a JavaFX progress dialog keeps host-key and keyboard-interactive prompts responsive.
- **Job Executor Threads**: Background thread pool for JobScheduler execution
- **AI Chat Threads**: Background threads for API requests (non-blocking UI)
- **AI catalog refresh**: The first catalog consumer gets verified cache/bootstrap data immediately and starts at most one background stable-channel refresh.
- **Local-model provisioning**: Setup-assistant metadata inspection, signed runtime installation, resumable GGUF download/verification, registration, and real chat/embedding tests run on background futures; JavaFX receives only progress and final state updates. Text/Coding/RAG assignments are persisted after every selected model passes.
- **llama.cpp sidecars**: One native process per compatible loaded GGUF configuration; different models can load and generate concurrently, while leases keep active requests from being stopped. Runtime-configuration saves first request an idle stop and are rejected while a lease is busy.
- **RAG workers**: Source previews, extraction, embedding batches, and HNSW candidate builds run outside the JavaFX thread; a daemon WatchService thread debounces automatic source changes. The pane permits only one active scan/index operation and returns the reviewed preview to JavaFX before configuration or indexing changes.
- **RAG coordinator**: One serialized worker and watcher per store prevents overlapping writes, reconciles Automatic sources during application startup independently of credential/JobScheduler startup, persists source hashes/counts/status, and reloads UI state after completion.
- **File I/O Threads**: Async writes for history, journal, and recordings
- **Web formatter requests**: Background callers are serialized with one total timeout; creation, loading and invocation of the lazy Prettier/SQL WebView remain confined to the JavaFX application thread, and failures discard the engine generation.
- **Mermaid render requests**: Background callers receive `CompletableFuture` results while all access to the single lazy renderer WebView stays on the JavaFX application thread. Requests are serialized; cancellation, a 30-second timeout, or a WebEngine error discards the engine generation, and idle cleanup releases the hidden WebView.

## Extensibility Points

### For Plugin Developers

1. **Terminal Effects**: Implement `TerminalEffectPlugin` and register via `ServiceLoader` (see `TERMINAL_EFFECT_PLUGINS.adoc`)
2. **Custom Formatters**: Add support in the snippet editor for new languages
3. **AI Skills**: Import custom AI instruction sets via `AI → AI Manager → AI Skills`

### For Integrators

1. **JAXB Repositories**: Extend `XMLConnectionRepository` or `CredentialRepository` to add custom data sources
2. **SSH Session Hooks**: Subclass `SSHSession` to inject custom connection logic
3. **JobScheduler Actions**: Add new action types to `JobExecutor` (e.g., custom SFTP operations)

## Performance Considerations

- **Session caching**: Active SSH connections are cached to avoid reconnection overhead
- **Lazy loading**: Connection details loaded on demand, not all at once
- **Compression**: Terminal history and terminal logs use gzip; rotated session-journal parts use zstd (legacy `.gz` parts stay readable)
- **Throttling**: Terminal rendering updates are batched to reduce UI thread load
- **Memory pooling**: Large buffers for terminal text and SFTP file listing are reused

## Security Best Practices

1. **Master password**: Set a strong, unique password; it is never transmitted or logged
2. **SSH keys**: Store in `~/.kortty/ssh-keys/` for backup inclusion; passphrases are encrypted in `ssh-keys.xml`, and the AES-256/GPG-encrypted backup carries the key files
3. **Host key verification**: Verify the OpenSSH SHA-256 fingerprint before accepting an interactive first-use prompt; keep host-key pinning enabled for unattended JobScheduler execution
4. **Backup encryption**: Use password-protected ZIP or GPG encryption
5. **AI profiles**: Prefer an integrated local GGUF model for sensitive data; verify the trust and data policy of every remote endpoint
6. **Avoid logging secrets**: JobScheduler journal redacts stored secrets before persistence
