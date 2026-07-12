---
title: Architektur
---

# Architektur (Entwickler)

korTTY ist ein modularer JavaFX-SSH-Client, der auf einer mehrschichtigen Architektur basiert. Dieser Anleitung beschreibt die Kernmodule, ihre Verantwortlichkeiten, Abhängigkeiten und wie sie interagieren, um ein nahtloses Terminalerlebnis mit erweiterten Funktionen wie Jobplanung, KI-Integration und Terminaleffekt-Plugins zu bieten.


![Startup & manager init](../assets/diagrams/app-startup-manager-init.svg)

## Architekturübersicht

Das folgende Diagramm zeigt die Hauptkomponenten von korTTY und ihre Beziehung:

![Architecture](../assets/diagrams/architecture.svg)

## Module und Abhängigkeiten

KorTTY ist in verschiedene Funktionsmodule unterteilt. Das folgende Diagramm gruppiert sie nach Funktion:

![Modules overview](../assets/diagrams/modules-overview.svg)

### Modulaufschlüsselung

| **Modul** | **Zweck** | **Schlüsselklassen** |
|---|---|---|
| **Kern** | SSH-Konnektivität, Sitzungsverwaltung, KI-Integration, Terminalautomatisierung | `SSHSession`, `AiChatManager`, `TerminalAgentService`, `Mosh4jTtyConnector` |
| **ui** | JavaFX-Benutzeroberfläche, Dialoge, Terminalansichten, SFTP-Manager | `TerminalPane`, `ConnectionDialog`, `SFTPManagerDialog`, `SnippetEditor` |
| **Modell** | Domänenobjekte für Verbindungen, Anmeldeinformationen, Snippets, Jobs | `Connection`, `Credential`, `Snippet`, `JobScheduleEntry` |
| **Jobscheduler** | Planung und Ausführung von Hintergrundjobs | `JobScheduler`, `JobExecutor`, `JobJournal` |
| **Sicherheit** | Master-Passwort, Verschlüsselung/Entschlüsselung, SSH-Schlüsselverwaltung | `MasterPasswordManager`, `AES256GCMEncryption`, `SSHKeyManager` |
| **Beharrlichkeit** | XML-Serialisierung, Datei-E/A, Repository-Muster | `XMLConnectionRepository`, `CredentialRepository`, `JobSchedulerPersistence` |
| **Plugin** | Terminaleffekt-Plugins (ServiceLoader SPI) | `TerminalEffectPlugin`, `TerminalEffectSession` |
| **Teamarbeit** | Funktionen für Zusammenarbeit und Fernzugriff | Teambasierte Sitzungsfreigabe und -koordination |
| **jmx** | Überwachung von Java Management Extensions | `SSHClientMonitor`, `SSHClientMonitorMBean` |
| **Update** | Versionsprüfung und Update-Benachrichtigungen | Dienst- und Versionsmetadaten aktualisieren |

## SithTermFX Terminal Engine

KorTTY verwendet **SithTermFX 1.2.1** als primären Terminalemulator, der während des Erstellungsprozesses aus dem Quellcode erstellt wird. SithTermFX bietet:

- **Terminal-Emulation**: VT100/xterm-kompatibles Terminal-Rendering, unterstützt durch ein benutzerdefiniertes JavaFX-Steuerelement
- **OSC 8-Hyperlinks**: Ab SithTermFX 1.2.0 Unterstützung für anklickbare explizite Hyperlinks (beschränkt auf sichere URI-Schemata: `http`, `https`, `mailto`, `ftp`, `ftps`, `news`; `file://` beschränkt auf lokalen Host)
- **Sitzungsintegration**: Direktes JAXB-Marshalling des Terminalstatus für Sitzungsaufzeichnung und -wiedergabe
- **Farbunterstützung**: Konfigurierbare ANSI- und TrueColor-Verarbeitung mit Überschreibungen pro Verbindung

### Build-Integration

Der Build-Prozess automatisch:

1. Klont SithTermFX am Tag `v1.2.1` in `vendor/sithtermfx` (kein GitHub-Token erforderlich)
2. Erstellt es lokal mit Maven über die `installSithtermfxLocal`-Aufgabe
3. Installiert Artefakte im lokalen Maven-Repo (`mavenLocal()`)
4. Verknüpft SithTermFX-Kern- und UI-Module mit der korTTY-JAR

Nach dem Klonen ist kein Netzwerkzugriff erforderlich. Alle Build-Schritte sind deterministisch und reproduzierbar.

## Persistenz und Konfiguration

KorTTY speichert alle Konfigurationen, Anmeldeinformationen und den Sitzungsstatus in XML-Dateien unter `~/.kortty/` unter Verwendung von JAXB (Jakarta XML Binding). Dieser Ansatz gewährleistet:

- **Portabilität**: Einfache manuelle Inspektion und Migration zwischen Systemen
- **Verschlüsselung**: Sensible Daten (Passwörter, SSH-Schlüsselpassphrasen, Master-Passwort-Hash) werden mit AES-256-GCM verschlüsselt
- **Atomere Schreibvorgänge**: Dateiaktualisierungen verwenden temporäre Dateien und eine atomare Umbenennung, um eine Beschädigung bei einem Absturz zu verhindern
- **Keine Datenbank**: Eigenständig; Kein separater Datenbankserver oder Migrationsaufwand

### Struktur der Konfigurationsdatei

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

### Serialisierung und Verschlüsselung

**JAXB Marshaling** konvertiert Domänenobjekte in/aus XML mit automatischer Schemavalidierung. Zu den wichtigsten Repositories gehören:

- `XMLConnectionRepository`: Verwaltet `Connection`-Objekte mit SFTP-, Jump-Server- und Tunneldetails
- `CredentialRepository`: Speichert umgebungsspezifische Anmeldeinformationsmuster
- `SSHKeyManager`: Umschließt importierte SSH-Schlüssel mit verschlüsselten Passphrasen
- `JobSchedulerPersistence`: Behält `JobScheduleEntry`-Objekte und Ausführungsjournal bei

**AES-256-GCM-Verschlüsselung** schützt vertrauliche Felder:

- Das Master-Passwort wird bei der ersten Anmeldung einmal gehasht und als `master-password-hash` gespeichert
- Alle verschlüsselten Felder verwenden AES-256-GCM mit zufälligen IVs, die vom Hauptkennwort abgeleitet werden
- SSH-Schlüsselpassphrasen, Verbindungskennwörter und Sudo-Geheimnisse werden vor der Persistenz verschlüsselt
- Backup-Archive können mit passwortgeschütztem ZIP oder GPG verschlüsselt werden

## Kernmodulorganisation

### SSH und Sitzungsverwaltung

| **Komponente** | **Verantwortung** |
|---|---|
| `SSHSession` | Umschließt den Apache SSHD-Client und verwaltet den Verbindungslebenszyklus |
| `SSHKeyManager` | Zentralisierte SSH-Schlüsselspeicherung mit verschlüsselter Passphrase-Unterstützung |
| `Mosh4jTtyConnector` | Mosh-Protokoll-Connector unter Verwendung der mosh4j-Bibliothek (dynamisch geladen) |
| `NativeMoshTtyConnector` | Native OS Mosh-Unterstützung (Fallback) |
| `TemporarySSHKeyManager` | Zeitlich begrenzte SSH-Schlüssel (z. B. von CyberArk) |

### KI- und Agentenintegration

| **Komponente** | **Verantwortung** |
|---|---|
| `AiChatManager` | Manages AI chat sessions and conversation history |
| `AiCliProviderRegistry` | Ordnet KI-Profilkonfigurationen Anbieterendpunkten zu (OpenAI, Anthropic, LM Studio) |
| `TerminalAgentService` | Führt Agenten-Workflows aus: SSH-Sitzung prüfen, Aufgabe an Modell senden, Befehle validieren/ausführen |
| `AiInternetAccessConfiguration` | Konfiguriert Web-Tools (Tavily, Brave Search, SearXNG usw.) pro Profil |
| `AiChatExportService` | Exportiert KI-Chats nach Markdown, PDF, YAML, JSON, XML, Asciidoctor |

### Jobplanung

| **Komponente** | **Verantwortung** |
|---|---|
| `JobScheduler` | Hauptplanerdienst; verwaltet geplante Jobs und Ausführungswarteschlange |
| `JobExecutor` | Führt einzelne Jobs aus (SSH-Befehl, Skript, AI-Agent, SFTP, Rsync) |
| `JobJournal` | Persistentes Prüfprotokoll mit Schwärzungsunterstützung und automatischer Bereinigung (Standardeinstellung: 14 Tage) |

## Organisation des UI-Moduls

Die UI-Ebene basiert auf JavaFX und ist in logische Komponenten unterteilt:

| **Komponente** | **Zweck** |
|---|---|
| `MainWindow` | Anwendungsfenster der obersten Ebene mit Menüleiste, Registerkartenleiste, Terminalbereichen, Dashboard und SFTP-Browser |
| `TerminalPane` | Einzelne Terminal-Registerkarte mit Split-Pane-Unterstützung und Inline-KI-Aktivitätsfenster |
| `ConnectionDialog` | Multi-Tab-Editor für Verbindungsdetails (SSH, Tunnel, Jump-Server, Protokollierung usw.) |
| `SFTPManagerDialog` | Dual-Panel-Dateimanager für lokale und Remote-Dateioperationen |
| `SnippetEditor` | Monaco-basierter Code-Editor mit Syntaxhervorhebung, KI-Unterstützung und Mermaid-Flussdiagrammen |
| `JobSchedulerDialog` | Auftragserstellung, Terminplanung und Journalprüfung |
| `QuickConnectDialog` | Schnelle Verbindungssuche und häufig verwendete Verbindungsverknüpfungen |

## Sicherheitsarchitektur

### Master-Passwort und Verschlüsselung

1. **Erster Start**: Der Benutzer erstellt ein Master-Passwort (mindestens 6 Zeichen, Stärke überprüft über zxcvbn)
2. **Speicherung**: Das Passwort wird mit PBKDF2 mit 310.000 Iterationen gehasht und in `~/.kortty/master-password-hash` gespeichert
3. **Entsperren**: Das Master-Passwort entsperrt alle verschlüsselten Daten (Verbindungspasswörter, SSH-Schlüsselpassphrasen, Anmeldeinformationen, Backup-Archiv-Passwörter)
4. **Verschlüsselung**: AES-256-GCM mit zufälligen IVs; Die Verschlüsselung/Entschlüsselung erfolgt bei Bedarf beim Zugriff auf vertrauliche Felder

### Überprüfung des Hostschlüssels

- **JobScheduler**: Erfordert angeheftete Hostschlüssel-Fingerabdrücke vor der unbeaufsichtigten Ausführung (SSH, SFTP, Rsync)
- **Speicher**: Host-Schlüssel werden in `job-scheduler.xml` mit OpenSSH-Public-Key-Material gespeichert (erforderlich für die Rsync-Integration)
- **Override**: Override pro Job, um die Überprüfung zu deaktivieren, wenn das Risiko explizit akzeptiert wird

### Anmeldeinformationsverwaltung

- **Umgebungsspezifisch**: Anmeldeinformationen können auf Produktion, Entwicklung, Test oder Staging beschränkt werden
- **Glob-Muster**: Servermuster wie `*.example.com` oder `10.0.0.*` stimmen automatisch mit Verbindungen überein
- **Verschlüsselt gespeichert**: Alle Anmeldedaten-Passwörter verwenden AES-256-GCM

## Plugin-System

KorTTY unterstützt **Terminaleffekt-Plugins** über Java `ServiceLoader` zum Anpassen des Erscheinungsbilds und Verhaltens des Terminals.

### Plugin-Architektur

| **SPI-Schnittstelle** | **Verantwortung** |
|---|---|
| `TerminalEffectPlugin` | Plugin-Metadaten (ID, Name, Beschreibung) und Sitzungsfabrik |
| `TerminalEffectSession` | Lebenszyklus-Hooks (Init, Render, Cleanup) und optionales TtyConnector-Wrapping |
| `TerminalEffectContext` | Zugriff auf Overlay-API, aktuelle SithTermFX-Widgets, Animationsgeschwindigkeit, Erscheinungsbild |
| `TerminalEffectAppearance` | Optionale Schriftart-/Farb-/Cursorüberschreibungen |
| `TerminalEffectConnectorWrapper` | Transparenter Befehlsstream-Wrapper; KorTTY kann | sicher auspacken

### Plugin wird geladen

- **Gebündelte Plugins**: Wird aus dem Klassenpfad der Anwendung geladen (z. B. MOTHER-Effekt)
- **Externe Plugins**: Als `.jar`-Dateien in `~/.kortty/plugins/` importiert
- **Verwaltung**: Einzelne Plugins über `Plugins → Terminal Effects` ohne Deinstallation aktivieren/deaktivieren
- **Exportierbare Plugins**: Einige Plugins können als eigenständige JARs zur Verteilung exportiert werden

### Sicherheitshinweis

Externe Plugins sind **vertrauenswürdiger lokaler Code** und unterliegen keiner Sandbox. Importieren Sie JARs nur aus Quellen, denen Sie vertrauen. Abhängigkeiten, die nicht bereits mit korTTY gebündelt sind, müssen in der Plugin-JAR schattiert werden.

## Externe Abhängigkeiten

KorTTY basiert auf sorgfältig kuratierten, produktionsgetesteten Abhängigkeiten:

| **Kategorie** | **Bibliothek** | **Version** | **Zweck** |
|---|---|---|---|
| **SSH** | Apache SSHD (Core, Common, SFTP) | 2.12.0 | Implementierung des SSH-Protokolls |
| | Ed25519 (net.i2p.crypto:eddsa) | 0,3,0 | EdDSA-Schlüsselunterstützung |
| **Terminal** | SithTermFX (Kern, Benutzeroberfläche) | 1.2.0 | Terminal-Emulator-Engine |
| | Lanterna | 3.1.2 | Textbasierte UI-Komponenten |
| | pty4j (JetBrains) | 0,12,25 | PTY-Zuteilung für Mosh |
| **Daten** | Jakarta XML Bind | 4,0 | JAXB-Serialisierung |
| | Gson | 2.13.2 | JSON-Analyse |
| | zip4j | 2.11.5 | ZIP-Verschlüsselung |
| **Archiv** | Apache Commons Compress | 1,25,0 | TAR-, BZ2-, XZ-Unterstützung |
| | Tukaani xz | 1,9 | XZ-Komprimierung |
| **Benutzeroberfläche** | JavaFX | 21 | Anwendungsframework |
| | Monaco-Herausgeber | 0,55,1 | Code-Editor-Komponente |
| | Mermaid | 11.16.0 | Lokale Diagrammanalyse, SVG-Rendering und PNG-Rasterisierung |
| | MathJax | 3.2.2 | Lokales AI-Chat-Formel-Rendering |
| | Google-Java-Format | 1,35,0 | Java-Codeformatierung |
| **Utilities** | jfiglet | 0.0.9 | ASCII art banners |
| | zxcvbn | 1.9.0 | Passwortstärke (offline) |
| **Protokollierung** | SLF4J / Logback | 2.0.9 / 1.4.14 | Strukturierte Protokollierung |
| **Optional** | mosh4j | 2.0.2 | Mosh-Protokoll (dynamisch geladen) |

### Dynamische Abhängigkeiten

- **mosh4j**: Seine fünf SHA-256-fixierten, architekturspezifischen Release-JARs und Protobuf werden in nativen Builds gebündelt und nur dann dynamisch geladen, wenn Mosh benötigt wird. Der untergeordnete Lader verwendet Bouncy Castle aus der übergeordneten Anwendung wieder, anstatt eine zweite Kopie zu versenden.
- **rsync / ssh**: Externe Befehle, die von JobScheduler Rsync-Jobs verwendet werden
- **ffmpeg**: Optional; Wird für die Terminalaufzeichnung und den Videoexport verwendet

## Build-Prozess

### Zusammenstellung und Verpackung

1. **SithTermFX-Build**: Lokal geklont und erstellt (Maven); seine versehentlich veröffentlichte JUnit-Abhängigkeit wird von der Laufzeit ausgeschlossen.
2. **Browser-Assets**: Ein isoliertes, angeheftetes Node.js wird nur zur Erstellungszeit für Monaco und für die JavaFX-WebKit-Kompatibilitätsverarbeitung des SHA-256-angehefteten Mermaid-Bundles verwendet. Die Editor- und Diff-Seiten von Monaco teilen sich ein modusbewusstes IIFE/CSS-Paar, während alle fünf Worker und Sprachdienste beibehalten werden. Mermaid und MathJax bleiben getrennte Lazy-Ressourcen; Prettier Standalone mit fünf ausgewählten Plugins und dem SQL-Formatter UMD Build läuft ohne Node zur Laufzeit.
3. **Nutzlast des externen Formatierers**: Nur shfmt, Perl::Tidy und deren Manifest werden neben der App bereitgestellt; Das Logo-Video wird einmal pro Quelloberfläche als H.264/yuv420p bei 640×360 ohne Audio gespeichert.
4. **Sauberes natives Staging**: `prepareJpackage` verwendet einen endgültigen Gradle `Sync`, sodass veraltete Abhängigkeiten, Formatierungsbäume und Mosh-Architekturen gelöscht werden. Bouncy Castle wird dedupliziert und JNA/pty4j werden nur mit den nativen Pfaden und der binären Architektur des aktuellen Ziels neu gepackt.
5. **Native Verpackung und Gates**: Die ausgewählte Gradle JDK 25-Toolchain stellt `jpackage` für die Ausgabe von .app/.dmg, .exe/.msi, .deb und .rpm bereit. `scripts/package-size-report.py` gibt JSON-/Markdown-Komponentenberichte aus und CI erzwingt den Commit-Release-Vergleich, eine Installationsprogrammreduzierung von mindestens 15 %, absolute App-/DMG-Grenzwerte und eingefrorene verifizierte Größenbudgets mit einer Toleranz von 2 %.

### Klassenpfad und Modulpfad

- **Kompilierungszeit**: Auf JDK 25 wird das JavaFX `jdk-jsobject`-Artefakt im Upgrade-Modulpfad bereitgestellt, sodass WebView-Brücken konsistent kompiliert werden. Das Modul fehlt in JDK 26.
- **Packaging-time**: Gradle löst die gleiche Temurin JDK 25-Toolchain auf und ruft sie auf, die für die Kompilierung verwendet wurde, sodass die gekürzte Laufzeit `jdk.jsobject` enthält, auch wenn das System `java`/`jpackage` neuer ist.
- **Modulpfad**: JavaFX, SithTermFX und Apache SSHD sind während der Kompilierung verfügbar; Ungenutzte `javafx.fxml` und `java.scripting` sind nicht in der Paketlaufzeit enthalten.

## Datenfluss und Integrationspunkte

### SSH-Verbindungsablauf

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

### KI-Integrationsfluss

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

### Jobplanungsablauf

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

## Protokollierung und Diagnose

- **Protokolldatei**: `~/.kortty/kortty.log` (SLF4J mit Logback-Backend)
- **JMX-Überwachung**: MBean `de.kortty:type=SSHClient` macht aktive Verbindungen, Speicher und gepufferte Textgröße verfügbar
- **JobScheduler-Journal**: Detaillierte Ausführungsprotokolle mit konfigurierbarer Aufbewahrung (standardmäßig 14 Tage, unbegrenzt, wenn auf 0 gesetzt)
- **Terminalverlauf**: Komprimierte Sitzungsprotokolle in `~/.kortty/history/`
- **Terminalaufzeichnung**: Optionale Wiedergabedateien in `~/.kortty/recordings/`

## Thread-Modell

- **UI-Thread**: Der JavaFX-Anwendungsthread übernimmt das gesamte Rendering und die Benutzerinteraktion
- **SSH-Sitzungsthreads**: Ein Thread pro aktiver SSH-Verbindung (Apache SSHD-Pool)
- **Job Executor Threads**: Hintergrund-Thread-Pool für die JobScheduler-Ausführung
- **AI-Chat-Threads**: Hintergrund-Threads für API-Anfragen (nicht blockierende Benutzeroberfläche)
- **Datei-E/A-Threads**: Asynchrone Schreibvorgänge für Verlauf, Journal und Aufzeichnungen
- **Webformatter-Anfragen**: Hintergrundaufrufer werden mit einer Gesamtzeitüberschreitung serialisiert; Erstellung, Laden und Aufrufen des Lazy Prettier/SQL WebView bleiben auf den JavaFX-Anwendungsthread beschränkt, und bei Fehlern wird die Engine-Generierung verworfen.
- **Mermaid-Rendering-Anfragen**: Hintergrundaufrufer erhalten `CompletableFuture`-Ergebnisse, während der gesamte Zugriff auf den einzelnen Lazy-Renderer WebView im JavaFX-Anwendungsthread verbleibt. Anfragen werden serialisiert; Bei einem Abbruch, einem 30-Sekunden-Timeout oder einem WebEngine-Fehler wird die Engine-Generierung verworfen, und die Leerlaufbereinigung gibt die ausgeblendete WebView frei.

## Erweiterbarkeitspunkte

### Für Plugin-Entwickler

1. **Terminale Auswirkungen**: `TerminalEffectPlugin` implementieren und über `ServiceLoader` registrieren (siehe `TERMINAL_EFFECT_PLUGINS.adoc`)
2. **Benutzerdefinierte Formatierer**: Fügen Sie im Snippet-Editor Unterstützung für neue Sprachen hinzu
3. **KI-Fähigkeiten**: Importieren Sie benutzerdefinierte KI-Befehlssätze über `Settings → AI Skills`

### Für Integratoren

1. **JAXB-Repositorys**: Erweitern Sie `XMLConnectionRepository` oder `CredentialRepository`, um benutzerdefinierte Datenquellen hinzuzufügen
2. **SSH-Sitzungs-Hooks**: Unterklasse `SSHSession` zum Einfügen benutzerdefinierter Verbindungslogik
3. **JobScheduler-Aktionen**: Neue Aktionstypen zu `JobExecutor` hinzufügen (z. B. benutzerdefinierte SFTP-Vorgänge)

## Leistungsüberlegungen

- **Sitzungscaching**: Aktive SSH-Verbindungen werden zwischengespeichert, um den Aufwand für die erneute Verbindung zu vermeiden
- **Lazy Loading**: Verbindungsdetails werden bei Bedarf geladen, nicht alle auf einmal
- **Komprimierung**: Terminalverlauf und Aufzeichnungen verwenden die GZIP-Komprimierung
- **Drosselung**: Terminal-Rendering-Updates werden stapelweise durchgeführt, um die Belastung des UI-Threads zu reduzieren
- **Speicherpooling**: Große Puffer für Terminaltext und SFTP-Dateiliste werden wiederverwendet

## Best Practices für die Sicherheit

1. **Master-Passwort**: Legen Sie ein sicheres, eindeutiges Passwort fest; es wird niemals übertragen oder protokolliert
2. **SSH-Schlüssel**: Zur Einbindung von Backups in `~/.kortty/ssh-keys/` speichern; Passphrasen werden verschlüsselt
3. **Host-Schlüssel-Pinning**: Vor der unbeaufsichtigten JobScheduler-Ausführung aktivieren
4. **Backup-Verschlüsselung**: Verwenden Sie passwortgeschützte ZIP- oder GPG-Verschlüsselung
5. **KI-Profile**: Bevorzugen Sie lokale LM Studio-Endpunkte für sensible Daten; Überprüfen Sie die Vertrauenswürdigkeit von Remote-Endpunkten
6. **Protokollierungsgeheimnisse vermeiden**: Das JobScheduler-Journal schwärzt gespeicherte Geheimnisse vor der Persistenz
