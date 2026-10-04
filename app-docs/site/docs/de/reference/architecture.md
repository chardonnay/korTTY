---
title: Architektur
---

# Architecture (Entwickler)

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
| **Kern** | SSH-Konnektivität, gemeinsames interaktives Host-Key-Vertrauen, Sitzungsverwaltung, KI-Integration, Terminalautomatisierung | `SshTtyConnector`, `SshHostKeyTrustManager`, `AiChatManager`, `TerminalAgentService`, `Mosh4jTtyConnector` |
| **ai** | Signierter Modell-/Eingabeaufforderungskatalog, Hugging Face-Metadaten/Downloads, eingebettete llama.cpp- und MLX-Laufzeiten und signierte Laufzeitpakete | `AiCatalogService`, `HuggingFaceClient`, `LlamaRuntimeManager`, `LlamaRuntimePackageInstaller`, `EmbeddedMlxAiService`, `MlxRuntimeLocator` |
| **rag** | Sicherer Quellscanning, Extraktion, Chunking, Embeddings, Vektor-Speicher, Synchronisation und begrenztes Abrufen | `RagSourceScanner`, `RagSourceSynchronizer`, `LocalHnswStore`, `RagRuntimeService` |
| **ui** | JavaFX-Benutzeroberfläche, Dialoge, Terminalansichten, SFTP-Manager | `TerminalView`, `TerminalTab`, `ConnectionEditDialog`, `SFTPManagerTab`, `SnippetEditDialog` |
| **Modell** | Domänenobjekte für Verbindungen, Anmeldeinformationen, Snippets, Jobs | `ServerConnection`, `StoredCredential`, `Snippet`, `JobSchedule` |
| **Jobscheduler** | Hintergrundjobplanung und -ausführung | `JobSchedulerService`, `JobSchedulerJobRunner`, `JobJournalEntry` |
| **Sicherheit** | Master-Passwort, Verschlüsselung/Entschlüsselung, Passwort-Tresor | `MasterPasswordManager`, `EncryptionService`, `PasswordVault` |
| **Persistenz** | XML-Serialisierung, Datei-E/A, Repository-Muster | `XMLConnectionRepository`, `HistoryStorage` |
| **Plugin** | Terminaleffekt-Plugins (ServiceLoader SPI) | `TerminalEffectPlugin`, `TerminalEffectSession` |
| **Leistung** | Aktivitätsbewusstes Schlaf- und App-Nap-Management pro Plattform | `PowerManagementCoordinator`, `MacPowerManagementBackend` |
| **Telemetrie** | Anonyme Nutzungsereignisse nach Zustimmung | `TelemetryService`, `Telemetry` |
| **control** | Standardmäßig ausgeschaltete lokale Steuerungs-API: Transport, Token, Verben, Ereignisbus (siehe [Steuerungs-API](control-api.md)) | `ControlApiServer`, `ControlVerbs`, `ControlSurface`, `ControlEventBus`, `ControlApiGate` |
| **cli** | Der Client `kortty-cli`, der die Steuerungs-API spricht (siehe [Steuerungs-CLI](cli.md)) | `KorttyCli` |
| **Teamarbeit** | Funktionen für Zusammenarbeit und Fernzugriff | Teambasierte Sitzungsfreigabe und -koordination |
| **jmx** | Überwachung der Java-Verwaltungserweiterungen | `SSHClientMonitor`, `SSHClientMonitorMBean` |
| **Update** | Versionsprüfung und Update-Benachrichtigungen | Update-Dienst und Versionsmetadaten |

## SithTermFX Terminal-Engine

KorTTY verwendet **SithTermFX 1.2.2** als primären Terminalemulator, der während des Erstellungsprozesses aus dem Quellcode erstellt wird. SithTermFX bietet:

- **Terminalemulation**: VT100/xterm-kompatibles Terminal-Rendering, unterstützt durch ein benutzerdefiniertes JavaFX-Steuerelement
- **OSC-8-Hyperlinks**: Klickbare explizite Hyperlinks, die Programme ausgeben. korTTY ersetzt in jedem Bereich den Standard-Link-Handler von SithTermFX durch eine eigene Zulassungsliste (`http`, `https`, `ftp`, `ftps`, `mailto`, streng geparst, höchstens 8 KB, keine Steuer- oder Bidi-Zeichen, `mailto` beschränkt auf Empfänger, Betreff, Text und in-reply-to) und öffnet Links über JavaFX `HostServices`. Links öffnen sich nur bei einem einzelnen, unbewegten Cmd/Strg+Klick ohne Alt: `TerminalLinkClickPolicy` ist eine reine Entscheidungstabelle, die als primärer `MOUSE_CLICKED`-Filter auf dem Canvas von `KorttyTerminalPanel` installiert ist, die `KorttyLinkInfo`, die SithTermFX bei einem einfachen Klick ansteuert, bewirkt nichts, einfache Klicks auf Link-Zellen werden konsumiert (mit einer Fokusanforderung, damit die Split-Ansicht weiterhin den fokussierten Bereich verfolgt), und einfache Doppel- und Dreifachklicks darauf werden als Wort- und Zeilenauswahl von SithTermFX nachgebildet. `news:` und andere Ziele bleiben Klartext, und ein `file:`-Ziel wird nur in einem Bereich zum Link, der Dateien als Text öffnet (siehe unten), sodass Terminalausgabe nie `java.awt.Desktop.open` erreicht und keine lokale Datei starten kann. Bereiche zeichnen Links im SithTermFX-Modus `HOVER_WITH_CUSTOM_COLOR`, sodass verlinkter Text seine eigenen Farben behält und beim Überfahren unterstrichen wird, und `TerminalScreenRenderer` entpackt für Aufzeichnungen den `HyperlinkStyle` einer Link-Zelle zum Stil des Textes. korTTY registriert keinen SithTermFX-`HyperlinkFilter`: In diesem Modus würde ein Filter passende Zellen, OSC-8-Links eingeschlossen, mit den Linkfarben des Herstellers überschreiben, deshalb werden Links im Klartext stattdessen bei Bedarf gefunden; `NoHyperlinkFilterGuardTest` lässt den Build fehlschlagen, wenn ein Filter hinzugefügt wird. `TerminalLinkResolver` ist der Trefferauflöser der Klick-Policy: Unter der Puffersperre liefert er den OSC-8-Link der angeklickten Zelle oder führt andernfalls `TerminalLinkDetector` für Web- und E-Mail-Adressen über die logische Zeile um die Zelle aus, begrenzt auf ±16 umgebrochene Zeilen und 4096 Zeichen, und verwirft ein Token, das über dieses Fenster hinauslaufen könnte, statt es abgeschnitten zu öffnen. Die Arten kommen von einem Lieferanten pro Bereich, der `GlobalSettings.terminalLinkDetectionEnabled` bei jedem Klick liest, sodass die Erkennung nichts kostet, während Ausgabe eintrifft. `TerminalLinkHoverController` löst auf dieselbe Weise auf, aber nur, wenn die Maus eine andere Zelle erreicht: Ein `MOUSE_MOVED`-Handler, der nach dem von SithTermFX hinzugefügt wird, setzt den Canvas-Cursor bei jedem Zellwechsel explizit (HAND nur für einen Link, der sich öffnen lässt), zeichnet die Unterstreichung eines Klartext-Links als `Line`s in die `LINKS`-Ebene des Bereichs (`TerminalSplitPane.paneOverlay`, ein nicht verwaltetes, für die Maus transparentes `Pane` im Wrapper des Bereichs mit negativer View-Order, positioniert über Szenenkoordinaten, weil der Wrapper auch die Zeitstempel-Spalte und das Agent-Panel enthält) und zeigt einen Tooltip mit `TerminalLinkOpener.displayTarget` (Punycode-Host, prozentkodierte Steuer- und Bidi-Zeichen, auf etwa 200 Zeichen gekürzt, ohne den Host abzuschneiden). Er baut alles wieder ab bei Verlassen, Ziehen, Scrollen, einer Nicht-Modifikatortaste, Fokusverlust von Bereich oder Fenster und Entfernen aus der Szene, und solange ein Link angezeigt wird, folgt er einem Listener auf das Puffermodell (auf den FX-Thread zusammengefasst) und Größenänderungen des Canvas, um neu aufzulösen. Ein OSC-8-Klick, dessen sichtbarer Text eine URL auf einem anderen Host als das Ziel ist (`TerminalLinkOpener.visibleHostMismatch`), öffnet erst nach `TerminalLinkMismatchDialog`, der nach der Verarbeitung des Klicks fragt, mit Abbrechen als Standardschaltfläche. Das Terminal-Kontextmenü erreicht dieselben Links ohne Modifikator: `TerminalLinkContextMenu` fügt einen `MOUSE_PRESSED`-Filter auf dem Canvas hinzu, der den Link unter jedem Druck der rechten Maustaste auflöst (jeder andere Druck vergisst ihn), und `TerminalSplitPane.createFullContextMenu` setzt für einen Link mit Ziel **Link öffnen** (der Öffnungsweg des Klicks, Abweichungs-Rückfrage eingeschlossen) und **Linkadresse kopieren** (über `KorttyClipboard`, sodass der interne Zwischenablagemodus gilt; bei einem OSC-8-Link das `toASCIIString()`-Ziel, bei einer Klartextadresse die Adresse wie ausgegeben) über die Bearbeitungseinträge. Dateien gehen dieselben Wege ohne Browser: `TerminalFileLink` ist ein Pfad, den der Detektor gefunden hat (`Kind.PATH`, zerlegt durch `TerminalPathToken`), oder ein OSC-8-`file:`-Ziel, das `fromFileUri` akzeptiert (absoluter Pfad, keine `//`-Netzwerkfreigabe, kein Backslash, nach dem Dekodieren keine Steuer-, Bidi- oder Formatzeichen, ein Host ohne Benutzername oder Port). `KorttyOsc8LinkInfoProvider` erstellt einen `file:`-Link nur, solange der `TerminalFileLinkHandler` des Bereichs aktiviert ist (auf dem Emulator-Thread abgefragt), was `TerminalView` nur für SSH- und lokale Shell-Bereiche wahr macht, solange ein `TerminalPathOpenHandler` gesetzt ist, und `MainWindow` setzt einen solchen nur, wenn `loadIntoSnippetEditor` der Richtlinie nicht `DENY` ist; dieselbe Bedingung fügt `PATH` zu den Klartext-Linkarten des Bereichs hinzu. Das gemeinsame Lookup des Bereichs verwirft die Datei eines Links, den der Handler nicht akzeptiert (ein OSC-8-Host, der nicht leer, `localhost`, der Verbindungshost, der Prompt-Host oder der lokale Hostname ist, verglichen am ersten DNS-Label), sodass Hover, Klick und Kontextmenü übereinstimmen. Das Öffnen löst den Pfad mit `RemoteTextFileSelectionSupport.resolveRemotePath` / `resolveLocalPath` auf (absolut, `~` und relativ zum Arbeitsverzeichnis zum Klickzeitpunkt, das nur für einen relativen Pfad ermittelt wird; UNC- und Gerätepfade werden vor und nach dem Auflösen abgelehnt) und liest ihn über denselben Leser wie **Im Snippet-Editor öffnen**, mit dem Schutz gegen fremde Sitzungen, den Prüfungen auf reguläre Datei und UTF-8 sowie einer 10-MB-Grenze für SFTP- und lokale Lesevorgänge, die auch eine Datei stoppt, die während des Lesens wächst. Eine Datei aus einem OSC-8-Link öffnet sich schreibgeschützt: `ExternalFileActionConfig` erhält keine Überschreiben- oder Speichern-unter-Aktion und einen `lockedReason`. `TerminalLinkHoverController` fügt seinen `MOUSE_MOVED`-Handler in `KorttyTerminalPanel.init()` hinzu, nachdem SithTermFX dort seinen eigenen hinzugefügt hat, sodass der Cursor, den korTTY setzt, auch bei einem OSC-8-Link gewinnt, den korTTY nicht öffnet.
- **Schnellauswahl**: *Bearbeiten → Schnellauswahl* (Cmd/Strg+Shift+Leertaste) versieht das, was der fokussierte Bereich anzeigt, mit Kürzeln. Die Tastenkombination ist eine `MainWindow`-Konstante, die über den Shortcut-Router der Szene geleitet wird (Geltungsbereich: ein Terminal-Tab, in dem der Tastaturfokus liegt; Rest: Leerzeichen oder NUL) und am Menüeintrag gesetzt ist, der außerhalb von Terminal-Tabs deaktiviert ist. `QuickSelectScreen` liest unter der Puffersperre die sichtbaren Zeilen am Scroll-Ursprung des Panels, fügt umgebrochene Zeilen innerhalb des Sichtbereichs zusammen (zugeschnitten auf die 16-KB-Eingabegrenze des Detektors) und führt `TerminalLinkDetector` für jede Art aus, wobei ein Token verworfen wird, das über den Sichtbereich hinausläuft; die eigenen Muster des Benutzers (`QuickSelectPatterns`, aus `GlobalSettings.terminalQuickSelectPatterns`: höchstens 16 mit je höchstens 512 Zeichen, einmal pro Änderung der Liste kompiliert) laufen über denselben gescannten Text und ergeben `Kind.CUSTOM`, jeweils über eine `DeadlineCharSequence` mit 10 ms pro Zeile und 100 ms für eine ganze Erfassung, wobei ein Muster, das seine Zeit überschreitet, für den Rest der Erfassung übersprungen wird, und ein Mustertreffer ersetzt die eingebauten Treffer, die er überlappt, wenn er mindestens so lang ist wie jeder von ihnen; `QuickSelectLabels` vergibt präfixfreie Kürzel aus dem Alphabet des Benutzers (`terminalQuickSelectAlphabet`, geprüft von `alphabetProblem`, bei Unbrauchbarkeit der Standard; ein Buchstabe pro Text bis zur Größe des Alphabets, sonst zwei), beginnend mit der untersten Zeile, eines pro verschiedenem Text; `QuickSelectSession` ist ein reiner Zustandsautomat über `KeyCode` und Shift (Modifikatortasten und die Start-Tastenkombination werden ignoriert, ein Kürzel kopiert, Shift auf seinem letzten Buchstaben öffnet, Backspace bearbeitet das Präfix, Escape und jede andere Taste brechen ab). `TerminalQuickSelectController` installiert die ersten `KEY_PRESSED`-, `KEY_TYPED`- und `INPUT_METHOD_TEXT_CHANGED`-Filter der Split-Ansicht (vor denen von `TerminalView`, also vor der Agent-Eingabesperre und dem Broadcast-Spiegel pro Bereich): Solange eine Sitzung läuft, wird jede Taste, jedes getippte Zeichen und jedes Eingabemethoden-Ereignis konsumiert, und `QuickSelectInputGuard` verwendet `KeyTypedResidueGuard`, um das `KEY_TYPED` der letzten Taste einmal sowie jeden Eingabemethoden-Text bis zum nächsten Tastendruck zu verschlucken. Er zeichnet `Rectangle`-Rahmen und inline gestylte `Label`-Chips (Farben des Bereichs invertiert, Terminalschrift) über Szenenkoordinaten (`TerminalCellGeometry.boxes`) in die `LINKS`-Ebene des Bereichs, kopiert über `KorttyClipboard`, öffnet eine URL, eine E-Mail-Adresse oder, wo der Bereich Dateien öffnet (`KorttyTermWidget.opens`), einen Pfad über `openLink` des Widgets (den Cmd/Strg+Klick-Weg) und kopiert alles, was er nicht öffnet, und endet bei einem Mausdruck, Fokusverlust des Canvas, einer Verschiebung oder Größenänderung des Canvas, einer Änderung der Scrollleiste, einer Änderung der Schriftgröße (`TerminalView.reinitPaneFont`) oder dem Entfernen aus der Szene; ein Listener auf das Puffermodell, auf den FX-Thread zusammengefasst, verwirft die Treffer, deren Zellen ihren erfassten Text nicht mehr enthalten, und beendet die Sitzung, wenn keiner mehr übrig ist.
- **Sitzungsintegration**: Direktes JAXB-Marshalling des Terminalstatus für Sitzungsaufzeichnung und -wiedergabe
- **Farbunterstützung**: Konfigurierbare ANSI- und TrueColor-Verarbeitung mit Überschreibungen pro Verbindung
- **Überprüfte Grenzkorrektur**: Ein angehefteter korTTY-Patch lehnt die nicht vorhandene Zeile bei ab `line == height` beim Hyperlink-Treffertest, um die unterste Zeile zu verhindern `TerminalTextBuffer` Bereichsfehler
- **Überprüfter Shortcut-Akkord-Fix**: Ein zweiter angehefteter korTTY-Patch verhindert, dass Shortcut-Akkord-`KEY_TYPED`-Zeichen (z. B. ++cmd+shift+d++) die PTY- oder Broadcast-Fenster erreichen
- **Korrektheit des Bildlaufbereichs**: Der Bildlaufbereich schränkt den Cursor nicht ein, während der Ursprungsmodus (DECOM) deaktiviert ist. Text, der oberhalb des oberen Rands oder unterhalb des unteren Rands adressiert wird, bleibt in dieser Zeile, anstatt in den Bereich gezogen zu werden, und nur ein Zeilenvorschub oder ein automatischer Zeilenumbruch, der tatsächlich den unteren Rand überschreitet, scrollt – so landen die erste Bereichszeile von tmux, seine Statuszeile und der Cursor alle dort, wo die Anwendung sie platziert hat. Wird als korTTY-Patch getragen, bis er in SithTermFX 1.2.2 ausgeliefert wird

### Build-Integration

Der Build-Prozess automatisch:

1. Klont SithTermFX am Tag `v1.2.2` in `vendor/sithtermfx` (kein GitHub-Token erforderlich)
2. Wendet die überprüften Patches in `patches/sithtermfx/` – `1.2.2-terminal-panel-bottom-row.patch` und `1.2.2-terminal-panel-meta-shortcut-key-typed.patch` – der Reihe nach an. Dies schlägt fehl, wenn ein Patch weder zutrifft noch bereits mit der Quelle übereinstimmt
3. Erstellt es lokal mit Maven über die `installSithtermfxLocal`-Aufgabe
4. Installiert Artefakte im lokalen Maven-Repository (`mavenLocal()`), einschließlich einer Markierungsressource pro Patch, die es Gradle ermöglicht, ein ungepatchtes zwischengespeichertes UI-JAR abzulehnen
5. Verknüpft SithTermFX-Kern- und UI-Module mit der korTTY-JAR

Nach dem Klonen ist kein Netzwerkzugriff erforderlich; Alle Build-Schritte sind deterministisch und reproduzierbar.

## Persistenz und Konfiguration

KorTTY speichert seine Hauptkonfiguration, Anmeldeinformationen und Sitzungsstatus unter `~/.kortty/`, hauptsächlich als JAXB-XML. Die lokale Modellregistrierung verwendet ebenfalls JAXB-XML, während die Konfiguration des Wissensspeichers strikt JSON ist und das lokale Vektordiagramm ein regenerierbarer binärer Snapshot ist. Dieser Ansatz gewährleistet:

- **Portabilität**: Einfache manuelle Inspektion und Migration zwischen Systemen
- **Verschlüsselung**: Sensible Daten (Passwörter, SSH-Schlüsselpassphrasen, Master-Passwort-Hash) werden mit AES-256-GCM verschlüsselt
- **Atomere Schreibvorgänge**: Dateiaktualisierungen verwenden temporäre Dateien und eine atomare Umbenennung, um eine Beschädigung bei einem Absturz zu verhindern
- **Keine erforderliche Datenbank**: Lokales HNSW ist eigenständig; Qdrant ist ein optionales zweites Vektor-Backend

### Konfigurationsdateistruktur

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
├── history/                     # Project screen snapshots (gzip, one per terminal tab)
├── plugins/                     # Imported external terminal-effect plugin JARs
├── bundled-plugins/             # Runtime copies of bundled exportable plugin JARs
├── projects/                    # Project files (connection sets with saved layout)
├── i18n/                        # Dynamically generated language property files
└── ssh-keys/                    # Optional: copied SSH keys (included in backups)
```

### Serialisierung und Verschlüsselung

**JAXB Marshaling** konvertiert Domänenobjekte in/aus XML mit automatischer Schemavalidierung. Zu den wichtigsten Repositories gehören:

- `XMLConnectionRepository`: Verwaltet `Connection`-Objekte mit SFTP-, Jump-Server- und Tunneldetails
- `CredentialRepository`: Speichert umgebungsspezifische Anmeldeinformationsmuster
- `SSHKeyManager`: Umschließt importierte SSH-Schlüssel mit verschlüsselten Passphrasen
- `JobSchedulerPersistence`: Behält `JobScheduleEntry`-Objekte und Ausführungsjournal bei

**AES-256-GCM-Verschlüsselung** schützt vertrauliche Felder:

- Das Master-Passwort wird bei der ersten Anmeldung einmal gehasht und gespeichert als `master.key`
- Alle verschlüsselten Felder verwenden AES-256-GCM mit zufälligen IVs, die vom Hauptkennwort abgeleitet werden
- SSH-Schlüsselpassphrasen, Verbindungskennwörter und Sudo-Geheimnisse werden vor der Persistenz verschlüsselt
- Backup-Archive können mit passwortgeschütztem ZIP oder GPG verschlüsselt werden

## Kernmodulorganisation

### SSH und Sitzungsverwaltung

| **Komponente** | **Verantwortung** |
|---|---|
| `SshTtyConnector` | Apache SSHD Terminalverbindung eines Panels: Sprunghost, Authentifizierung, Shell-Kanal und Trennungserkennung |
| `ActiveConnectionRegistry` | Live-Liste der offenen SSH- und Mosh-Terminalverbindungen (eine pro Panel), gelesen vom JMX-MBean |
| `SshTunnelManager` | Die SSH-Tunnel (`-L`, `-R`, `-D`) eines Terminal-Tabs: werden nach dem Login in der ersten SSH-Sitzung des Tabs geöffnet, vor einer Wiederverbindung gestoppt und danach wieder geöffnet, in ein anderes Paneel desselben Servers verschoben, wenn dieses Paneel geschlossen wird; geteilte Paneele öffnen nie ihre eigenen |
| `SshTunnelApprovals` | Einmalige Bestätigung pro Verbindung und Tunnel-Set vor dem Öffnen der Tunnel, gespeichert als Fingerabdruck in `ssh-tunnel-approvals.properties` |
| `SshHostKeyTrustManager` | Gibt normalisierte Host:Port-TOFU-Pins über interaktive Terminal-, SFTP- und Mosh-Bootstrap-Verbindungen mit atomarer, prozessübergreifender Persistenz frei |
| `SSHKeyManager` | Zentralisierte SSH-Schlüsselspeicherung mit verschlüsselter Passphrase-Unterstützung |
| `Mosh4jTtyConnector` | Mosh-Protokoll-Connector unter Verwendung der mosh4j-Bibliothek (dynamisch geladen) |
| `NativeMoshTtyConnector` | Native OS Mosh-Unterstützung (Fallback) |
| `TemporarySSHKeyManager` | Zeitlich begrenzte SSH-Schlüssel (z. B. von CyberArk) |

### AI und Agentenintegration

| **Komponente** | **Verantwortung** |
|---|---|
| `AiChatManager` | Verwaltet KI-Chatsitzungen und den Gesprächsverlauf |
| `AiServiceFactory` | Ordnet ein Profil dem HTTP-, Anthropic-, lokalen CLI- oder eingebetteten llama.cpp-Transport zu und fügt dann Eingabeaufforderungsvoreinstellungen und optionales RAG hinzu |
| `AiCatalogService` | Gibt sofort einen erneut verifizierten Cache/Bootstrap zurück und plant eine signierte Stable-Channel-Aktualisierung für Empfehlungen und voreingestellte Familienzuordnungen |
| `LlamaRuntimeManager` | Gibt einen isolierten authentifizierten Sidecar pro kompatibler GGUF-Konfiguration gemeinsam und gewährt referenzgezählte Anforderungsleasings |
| `RagAugmentedAiService` | Ruft begrenzte Auszüge für gewöhnliche KI-Aktionen ab und fügt den nicht vertrauenswürdigen Kontext vor der Modellvoreinstellung hinzu |
| `TerminalAgentService` | Führt Agenten-Workflows aus: SSH-Sitzung prüfen, Aufgabe an Modell senden, Befehle validieren/ausführen |
| `AiInternetAccessConfiguration` | Konfiguriert Web-Tools (Tavily, Brave Search, SearXNG usw.) pro Profil |
| `AiChatExportService` | Exportiert KI-Chats nach Markdown, PDF, YAML, JSON, XML, Asciidoctor |

### Jobplanung

| **Komponente** | **Verantwortung** |
|---|---|
| `JobScheduler` | Hauptplanerdienst; verwaltet geplante Jobs und die Ausführungswarteschlange |
| `JobExecutor` | Führt einzelne Jobs aus (SSH-Befehl, Skript, KI-Agent, SFTP, Rsync) |
| `JobJournal` | Persistentes Prüfprotokoll mit Schwärzungsunterstützung und automatischer Bereinigung (Standard 14 Tage) |

## UI-Modulorganisation

Die UI-Ebene basiert auf JavaFX und ist in logische Komponenten unterteilt:

| **Komponente** | **Zweck** |
|---|---|
| `MainWindow` | Anwendungsfenster der obersten Ebene mit Menüleiste, Registerkartenleiste, Terminalbereichen, Dashboard, SFTP-Browser |
| `TerminalPane` | Einzelne Terminal-Registerkarte mit Split-Panee-Unterstützung und Inline-KI-Aktivitätspanel |
| `ConnectionDialog` | Multi-Tab-Editor für Verbindungsdetails (SSH, Tunnel, Jump-Server, Protokollierung usw.) |
| `SFTPManagerTab` | Zweispaltiger Dateimanager-Tab für lokale und entfernte Dateioperationen |
| `SnippetEditor` | Monaco-basierter Code-Editor mit Syntaxhervorhebung, KI-Unterstützung und Mermaid-Flussdiagrammen |
| `LocalModelManagerPane` | Sucht/lädt/importiert GGUF-Dateien und steuert gleichzeitige llama.cpp-Sidecars |
| `RagKnowledgeStorePane` | Erstellt Wissensspeicher, zeigt Quellenvorschau an, zeigt den persistierten Indexzustand an, synchronisiert sie und führt Abfragen zur Wiederfindung durch |
| `JobSchedulerDialog` | Joberstellung, Planung und Journalprüfung |
| `QuickConnectDialog` | Schnelle Verbindungssuche und häufig verwendete Verbindungsverknüpfungen |

## Sicherheitsarchitektur

### Master-Passwort und Verschlüsselung

1. **Erster Start**: Der Benutzer erstellt ein Master-Passwort (mindestens 6 Zeichen, Stärke überprüft über zxcvbn)
2. **Speicherung**: Das Passwort wird mit PBKDF2 mit 310.000 Iterationen gehasht und gespeichert `~/.kortty/master.key`
3. **Entsperren**: Das Master-Passwort entsperrt alle verschlüsselten Daten (Verbindungspasswörter, SSH-Schlüsselpassphrasen, Anmeldeinformationen, Backup-Archiv-Passwörter)
4. **Verschlüsselung**: AES-256-GCM mit zufälligen IVs; Die Verschlüsselung/Entschlüsselung erfolgt bei Bedarf beim Zugriff auf vertrauliche Felder

### Host-Schlüsselüberprüfung

- **Interaktives Terminal/SFTP/Mosh Bootstrap**: Ein gemeinsamer TOFU-Verifizierer wird durch den normalisierten Hostnamen und Port gekennzeichnet. Die erste Verwendung zeigt den OpenSSH-SHA-256-Fingerabdruck mit **Nein** als Vorgabe; eine exakte Übereinstimmung bleibt still, während ein geänderter Schlüssel ohne erneuten Versuch hart blockiert wird. Nur eine Verbindung, die der Benutzer in einem Terminal-Tab oder im SFTP-Manager geöffnet hat, kann einen geänderten Schlüssel ersetzen, nachdem eine explizite Fingerabdruckbestätigung erfolgt ist und als Compare-and-Swap gegen den überprüften Schlüssel dient; Hintergrundaufrufe bauen ihren Verifizierer mit der Nie-Ersetzen-Politik auf.
- **Interaktiver Speicher**: `ssh-host-keys.properties` speichert Public-Key-Material durch einen atomaren Ersatz, der sowohl durch prozessinterne als auch prozessübergreifende Sperren geschützt ist. Sein vorübergehender Begleiter `.lock` wird nicht gesichert.
- **JobScheduler**: Unbeaufsichtigtes SSH, SFTP und Rsync verwenden separate verbindungs-ID-basierte Pins in `job-scheduler.xml`, einschließlich OpenSSH-Public-Key-Material, das von Rsync benötigt wird. Durch eine Außerkraftsetzung pro Auftrag kann diese Überprüfung nur dann deaktiviert werden, wenn das Risiko ausdrücklich akzeptiert wird.

### Anmeldeinformationsverwaltung

- **Umgebungsspezifisch**: Anmeldeinformationen können auf Produktion, Entwicklung, Test oder Staging beschränkt werden
- **Glob-Muster**: Servermuster wie `*.example.com` oder `10.0.0.*` stimmen automatisch mit Verbindungen überein
- **Verschlüsselt gespeichert**: Alle Anmeldedaten-Passwörter verwenden AES-256-GCM

### Eingebettete KI- und RAG-Isolation

- Jeder eingebettete Modellprozess bindet nur an `127.0.0.1` an einem zufälligen Port und erfordert einen generierten API-Schlüssel, der in einer temporären Datei nur für Besitzer gespeichert ist.
- Der feste Startbefehl llama.cpp aktiviert den Offline-Modus und deaktiviert die Web-Benutzeroberfläche, den Agenten, den UI-MCP-Proxy und den Slot-Endpunkt. Die geerbten Token-Variablen `LLAMA_ARG_*` und Hugging Face wurden entfernt.
- Hugging Face-Tokens und KI-Profil-API-Schlüssel werden mit dem Master-Passwort verschlüsselt. Modelldownloads werden an eine unveränderliche Revision angeheftet und mit SHA-256 überprüft.
- RAG akzeptiert nur zentral zugelassenen, inhaltsvalidierten Text. Abgerufene Auszüge werden als explizit nicht vertrauenswürdige Daten begrenzt und verpackt, sodass indizierte Anweisungen den System-/Aktionsvertrag von korTTY nicht ersetzen können.
- Runtime-Update-Indizes werden mit Ed25519 als exakte Bytes überprüft, bevor Paket-URLs analysiert werden; Pakete verfügen außerdem über signierte Größen-/SHA-256-Metadaten und sichere ZIP-Extraktionsbeschränkungen.
- Runtime-Entnahmen werden vor dem Herunterfahren des Prozesses durch eine Runtime-Root-Denylist plus Paketmarkierung beibehalten. Aktive Zeiger und unsichere Rollback-Verlaufseinträge werden entfernt, registrierte Modelle werden an eine nicht ausführbare Quarantänemarkierung zurückgebunden und der Startpfad überprüft die Markierung erneut, sodass ein veralteter Registrierungsstatus eine zurückgezogene Binärdatei nicht wiederbeleben kann.
- Ein Paket, das die Integritätsprüfung der Lightweight-Version besteht, bleibt bis zum ersten echten GGUF-gestützten authentifizierten API-Start in der Warteschleife. `LlamaRuntimeFirstLaunchRecovery` bestätigt die Bereitschaft oder stellt nach einem Startfehler die neueste fehlerfreie, nicht widerrufene Installation und Modellbindungen wieder her.
- Der unabhängige Modell-/Prompt-Katalog verfügt über einen eigenen Ed25519 Trust Root und ein eigenes striktes Schema. Sein Cache wird vor der Verwendung erneut überprüft; Ein fehlender Schlüssel deaktiviert sowohl die Cache-Vertrauensstellung als auch die Netzwerkaktualisierung und wählt den kompilierten Bootstrap aus.
- Remote Qdrant verwendet HTTPS, wobei einfaches HTTP auf einen Loopback-Test/lokalen Dienst beschränkt ist.

## Plugin-System

KorTTY unterstützt **Terminaleffekt-Plugins** über Java `ServiceLoader` zum Anpassen des Erscheinungsbilds und Verhaltens des Terminals.

### Plugin-Architektur

| **SPI-Schnittstelle** | **Verantwortung** |
|---|---|
| `TerminalEffectPlugin` | Plugin-Metadaten (ID, Name, Beschreibung) und Sitzungsfabrik |
| `TerminalEffectSession` | Lebenszyklus-Hooks (Init, Render, Cleanup) und optionaler TtyConnector-Wrapping |
| `TerminalEffectContext` | Zugriff auf Overlay-API, aktuelle SithTermFX-Widgets, Animationsgeschwindigkeit, Erscheinungsbild |
| `TerminalEffectAppearance` | Optionale Schriftart/Farbe/Cursor überschreibt |
| `TerminalEffectConnectorWrapper` | Transparenter Befehlsstream-Wrapper; KorTTY kann sicher ausgepackt werden |

### Plugin wird geladen

- **Gebündelte Plugins**: Aus dem Klassenpfad der Anwendung geladen (z. B. MOTHER-Effekt)
- **Externe Plugins**: Importiert als `.jar` Dateien in `~/.kortty/plugins/`
- **Verwaltung**: Einzelne Plugins über `Plugins → Terminal Effects` ohne Deinstallation aktivieren/deaktivieren
- **Exportierbare Plugins**: Einige Plugins können als eigenständige JARs zur Verteilung exportiert werden

### Sicherheitshinweis

Externe Plugins sind **vertrauenswürdiger lokaler Code** und unterliegen keiner Sandbox. Importieren Sie JARs nur aus Quellen, denen Sie vertrauen. Abhängigkeiten, die nicht bereits mit korTTY gebündelt sind, müssen in der Plugin-JAR schattiert werden.

## Externe Abhängigkeiten

KorTTY basiert auf sorgfältig kuratierten, produktionsgetesteten Abhängigkeiten:

| **Kategorie** | **Bibliothek** | **Version** | **Zweck** |
|---|---|---|---|
| **SSH** | Apache SSHD (Core, Common, SFTP) | 2.19.0 | SSH-Protokollimplementierung |
| | BouncyCastle (bcprov, bcpkix) | 1.86 | Kryptografieanbieter, SSH-Schlüsselanalyse und Ed25519/EdDSA-Schlüsselunterstützung |
| **Terminal** | SithTermFX (Kern, UI) | 1.2.2 plus angeheftete KorTTY-Grenz- und Shortcut-Akkord-Patches | Terminal-Emulator-Engine |
| | Lanterna | 3.1.5 | Textbasierte UI-Komponenten |
| | pty4j (JetBrains) | 0,12,25 | PTY-Zuweisung für Mosh |
| **Plattform** | JNA (JNA, JNA-Plattform) | 5.19.1 | Native Desktop-Energieverwaltungsintegration |
| **Daten** | Jakarta XML Bind | 4.0.5 (jaxb-runtime 4.0.9) | JAXB-Serialisierung |
| | Gson | 2.14.0 | JSON-Analyse |
| | zip4j | 2.11.6 | ZIP-Verschlüsselung |
| | jtokkit | 1.1.0 | Tokenzählung für KI-Anfragen |
| | PDFBox | 3.0.8 | PDF-Export und RAG-Textextraktion |
| **Archiv** | Apache Commons Compress | 1.28.0 | TAR, BZ2, XZ-Unterstützung |
| | Tukaani xz | 1.12 | XZ-Komprimierung |
| | zstd-jni | 1.5.7-16 | zstd-Komprimierung für gedrehte Sitzungsjournalteile |
| **UI** | JavaFX | 21.0.12 | Anwendungsframework |
| | AtlantaFX Base | 2.1.0 | Sieben wählbare Primer-, Nord-, Cupertino- und Dracula-JavaFX-Benutzeragententhemen; seine transitive OpenJFX-Abhängigkeit ist ausgeschlossen |
| | Monaco-Editor | 0.56.0 | Code-Editor-Komponente |
| | Mermaid | 12.0.0 | Lokale Diagrammanalyse, SVG-Rendering und PNG-Rasterisierung |
| | MathJax | 3.2.2 | Lokales KI-Chat-Formel-Rendering |
| | google-java-format | 1.36.1 | Java-Codeformatierung |
| **Dienstprogramme** | jfiglet | 0.0.9 | ASCII-Art-Banner |
| | zxcvbn | 1.9.0 | Passwortstärke (offline) |
| **Protokollierung** | SLF4J / Logback | 2.0.19 / 1.6.3 | Strukturierte Protokollierung |
| **Optional** | mosh4j | 2.0.2 | Mosh-Protokoll (dynamisch geladen) |
| **Lokale KI** | llama.cpp `llama-server` | Quellfixiertes Laufzeitpaket | Lokaler GGUF-Chat-Vervollständigungs- und Einbettungs-Sidecar |

### Dynamische Abhängigkeiten

- **mosh4j**: Seine fünf SHA-256-fixierten, architekturspezifischen Release-JARs und Protobuf werden in nativen Builds gebündelt und nur dann dynamisch geladen, wenn Mosh benötigt wird. Der untergeordnete Lader verwendet Bouncy Castle aus der übergeordneten Anwendung wieder, anstatt eine zweite Kopie zu versenden.
- **rsync / ssh**: Externe Befehle, die von JobScheduler Rsync-Jobs verwendet werden
- **ffmpeg**: Optional; Wird für die Terminalaufzeichnung und den Videoexport verwendet
- **llama.cpp-Laufzeit**: Unabhängig heruntergeladen unter `~/.kortty/llm/runtime/`; CPU-Pakete decken alle unterstützten Plattformen ab, Metal ist auf macOS verfügbar und Vulkan ist für unterstützte Windows/Linux-Ziele verfügbar. Es wird niemals in den Basis-Installer eingeklappt.
- **Qdrant**: Optionaler externer Vektordienst. Remote-Endpunkte erfordern HTTPS; HTTP ist nur Loopback. Lokales HNSW ist abhängigkeitsfrei und bleibt das Standard-Wissensspeicher-Backend.

## Build-Prozess

### Zusammenstellung und Verpackung

1. **SithTermFX-Build**: Lokal geklont und erstellt (Maven); seine versehentlich veröffentlichte JUnit-Abhängigkeit wird von der Laufzeit ausgeschlossen.
2. **Browser-Assets**: Ein isoliertes, angeheftetes Node.js wird nur zur Erstellungszeit für Monaco und für die JavaFX-WebKit-Kompatibilitätsverarbeitung des SHA-256-angehefteten Mermaid-Bundles verwendet. Die Editor- und Diff-Seiten von Monaco teilen sich ein modusbewusstes IIFE/CSS-Paar, während alle fünf Worker und Sprachdienste beibehalten werden. Mermaid und MathJax bleiben getrennte Lazy-Ressourcen; Prettier Standalone mit fünf ausgewählten Plugins und dem SQL-Formatter UMD Build läuft ohne Node zur Laufzeit.
3. **Nutzlast des externen Formatierers**: Nur shfmt, Perl::Tidy und deren Manifest werden neben der App bereitgestellt; Das Logo-Video wird einmal pro Quelloberfläche als H.264/yuv420p bei 640×360 ohne Audio gespeichert.
4. **Sauberes natives Staging**: `prepareJpackage` verwendet einen endgültigen Gradle `Sync`, sodass veraltete Abhängigkeiten, Formatierungsbäume und Mosh-Architekturen gelöscht werden. Bouncy Castle wird dedupliziert und JNA/pty4j werden nur mit den nativen Pfaden und der binären Architektur des aktuellen Ziels neu gepackt.
5. **Native Verpackung und Gates**: Die ausgewählte Gradle JDK 25-Toolchain stellt `jpackage` für die Ausgabe von .app/.dmg, .exe/.msi, .deb und .rpm bereit. `scripts/package-size-report.py` gibt JSON-/Markdown-Komponentenberichte aus und CI erzwingt den Commit-Release-Vergleich, eine Reduzierung des Installationsprogramms um mindestens 15 %, absolute App-/DMG-Grenzwerte und eingefrorene verifizierte Größenbudgets mit einer Toleranz von 2 %.
6. **JavaFX-Abhängigkeitsausrichtung**: `verifyJavaFxDependencyAlignment` überprüft jeden aufgelösten Kompilierungs-, Laufzeit- und Testklassenpfad vor der Kompilierung und Verpackung. Standard-OpenJFX-Module müssen bei 21.0.12 bleiben; Nur die `jdk-jsobject`-Brücke zur Kompilierungszeit verwendet ihre separat angeheftete Version und gibt möglicherweise keinen Laufzeitklassenpfad ein.
7. **llama.cpp-Laufzeitpaketierung**: Separate Gradle-Aufgaben überprüfen das angeheftete Upstream-Tag/Commit/Archiv SHA-256, erstellen nur `llama-server` plus erforderliche gemeinsam genutzte Bibliotheken, stellen einen Backend-spezifischen Baum bereit und erzeugen eine reproduzierbare unveränderliche ZIP- und signierte Index-Deskriptoreingabe. Der wöchentliche Laufzeitworkflow öffnet Kandidaten-PRs; Ein Scope-Job führt die vollständige Plattform-/Backend-Matrix nur aus, wenn sich die Pin-Datei, der Workflow oder die Lama-Java-Quellen geändert haben (eine reine `build.gradle.kts`-Änderung führt zu einem einzelnen Smoke-Leg); Jedes erstellte Bein führt einen nativen Link-Smoke aus, das Referenzpaket führt den vollständig authentifizierten Chat-/Einbettungs-/JSON-/Sleep-/Parallel-Sidecar-Vertrag und eine geschützte `llama-runtime-signing`-Umgebung mit der erforderlichen manuellen Stable-Promotion für Prüfer-Gates von `main` aus.
8. **Modell-/Prompt-Katalog-Werbung**: Ein separater manueller Workflow, der nur für `main` gilt, validiert das kanonische JSON mit strengem Schema, erfordert eine größere Sequenz als die neueste unveränderliche Version, führt Schema-/Vertrauenskettentests aus, gleicht den Signaturschlüssel mit dem Vertrauensstammverzeichnis der Anwendung ab, signiert die genauen Bytes und veröffentlicht über die durch Prüfer geschützte `ai-catalog-signing`-Umgebung ohne Vorschaukanal.

### Classpath und Modulpfad

- **Kompilierungszeit**: Auf JDK 25 wird das JavaFX `jdk-jsobject`-Artefakt im Upgrade-Modulpfad bereitgestellt, sodass WebView-Brücken konsistent kompiliert werden. Das Modul fehlt in JDK 26.
- **Packaging-time**: Gradle löst die gleiche Temurin JDK 25-Toolchain auf und ruft sie auf, die für die Kompilierung verwendet wurde, sodass die gekürzte Laufzeit `jdk.jsobject` enthält, auch wenn das System `java`/`jpackage` neuer ist.
- **Modulpfad**: JavaFX, SithTermFX und Apache SSHD sind während der Kompilierung verfügbar; Ungenutzte `javafx.fxml` und `java.scripting` sind nicht in der Paketlaufzeit enthalten.

## Datenfluss und Integrationspunkte

### SSH-Verbindungsablauf

```
User Input (Schnellverbindung)
    ↓
Connection Manager (lookup saved or new connection)
    ↓
Worker-thread SSH handshake + progress UI
    ↓
Shared host:port TOFU verification (Terminal/SFTP/Mosh bootstrap)
    ↓
SshTtyConnector (Apache SSHD) or Mosh connector
    ↓
Terminal Pane (SithTermFX rendering)
    ↓
Dashboard (status display, job monitor)
```

### KI-Integrationsablauf

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
- **JMX-Überwachung**: MBean `de.kortty:type=SSHClient` gibt die Live SSH- und Mosh-Terminalverbindungen (jedes geteilte Panel zählt, lokale Shells nicht), Speicher und Betriebszeit aus; `BufferedTextSize` wird nicht verfolgt und bleibt 0
- **JobScheduler-Journal**: Detaillierte Ausführungsprotokolle mit konfigurierbarer Aufbewahrung (standardmäßig 14 Tage, unbegrenzt, wenn auf 0 gesetzt)
- **Projekt-Bildschirm-Snapshots**: Der zuletzt sichtbare Bildschirm jedes Terminal-Tabs eines gespeicherten Projekts, gzip-komprimiert in `~/.kortty/history/` und lokal angezeigt (nie an den Server gesendet), wenn das Projekt mit Auto-Reconnect wieder geöffnet wird
- **Terminalaufzeichnung**: Optionale Wiedergabedateien in `~/.kortty/recordings/`
- **Testisolation**: Die Test-Logback-Konfiguration schreibt nur auf die eigene Konsole und erstellt niemals die Konsole des echten Benutzers oder hängt an diese an `~/.kortty/logs`

## Thread-Modell

- **UI-Thread**: Der JavaFX-Anwendungsthread übernimmt das gesamte Rendering und die Benutzerinteraktion
- **SSH-Sitzungsthreads**: Ein Thread pro aktiver SSH-Verbindung (Apache SSHD-Pool)
- **Split-Connection-Handshake**: Gleiche Server- und neu ausgewählte Split-Verbindungen führen die Netzwerkeinrichtung auf einem Worker durch, während ein JavaFX-Fortschrittsdialog dafür sorgt, dass Host-Tasten und interaktive Tastatureingabeaufforderungen reagieren.
- **Job Executor Threads**: Hintergrund-Thread-Pool für die JobScheduler-Ausführung
- **KI-Chat-Threads**: Hintergrundthreads für API-Anfragen (nicht blockierende Benutzeroberfläche)
- **KI-Katalogaktualisierung**: Der erste Katalogkonsument erhält sofort verifizierte Cache-/Bootstrap-Daten und startet höchstens eine Hintergrundaktualisierung über den stabilen Kanal.
- **Bereitstellung lokaler Modelle**: Metadatenprüfung durch Setup-Assistent, signierte Laufzeitinstallation, fortsetzbarer GGUF-Download/-Überprüfung, Registrierung und echte Chat-/Einbettungstests, die auf Hintergrund-Futures ausgeführt werden; JavaFX erhält nur Fortschritts- und Endzustandsaktualisierungen. Text-/Codierungs-/RAG-Zuweisungen bleiben bestehen, nachdem jedes ausgewählte Modell bestanden wurde.
- **llama.cpp-Sidecars**: Ein nativer Prozess pro kompatibler geladener GGUF-Konfiguration; Verschiedene Modelle können gleichzeitig geladen und generiert werden, während Leases verhindern, dass aktive Anforderungen gestoppt werden. Die Laufzeitkonfiguration speichert die erste Anforderung eines Leerlaufstopps und wird abgelehnt, während eine Lease beschäftigt ist.
- **RAG-Worker**: Quellvorschauen, Extraktion, Einbettungsbatches und HNSW-Kandidaten-Builds werden außerhalb des JavaFX-Threads ausgeführt; Ein Daemon-WatchService-Thread entprellt automatische Quelländerungen. Der Bereich lässt nur einen aktiven Scan-/Indexvorgang zu und gibt die überprüfte Vorschau vor Konfigurations- oder Indexierungsänderungen an JavaFX zurück.
- **RAG-Koordinator**: Ein serialisierter Worker und Watcher pro Speicher verhindert überlappende Schreibvorgänge, gleicht automatische Quellen während des Anwendungsstarts unabhängig vom Start von Anmeldeinformationen/JobScheduler ab, behält Quell-Hashes/Zählungen/Status bei und lädt den UI-Status nach Abschluss neu.
- **Datei-E/A-Threads**: Asynchrone Schreibvorgänge für Verlauf, Journal und Aufzeichnungen
- **Webformatter-Anfragen**: Hintergrundaufrufer werden mit einer Gesamtzeitüberschreitung serialisiert; Erstellung, Laden und Aufruf des Lazy Prettier/SQL WebView bleiben auf den JavaFX-Anwendungsthread beschränkt, und bei Fehlern wird die Engine-Generierung verworfen.
- **Mermaid-Rendering-Anfragen**: Hintergrundaufrufer erhalten `CompletableFuture`-Ergebnisse, während der gesamte Zugriff auf den einzelnen Lazy-Renderer WebView im JavaFX-Anwendungsthread verbleibt. Anfragen werden serialisiert; Bei einem Abbruch, einem 30-Sekunden-Timeout oder einem WebEngine-Fehler wird die Engine-Generierung verworfen, und die Leerlaufbereinigung gibt die ausgeblendete WebView frei.

## Erweiterungspunkte

### Für Plugin-Entwickler

1. **Terminale Auswirkungen**: `TerminalEffectPlugin` implementieren und über `ServiceLoader` registrieren (siehe `TERMINAL_EFFECT_PLUGINS.adoc`)
2. **Benutzerdefinierte Formatierer**: Unterstützung für neue Sprachen im Snippet-Editor hinzugefügt
3. **KI-Skills**: Importieren Sie benutzerdefinierte KI-Auflistungen über `AI → KI-Manager → AI Skills`

### Für Integratoren

1. **JAXB-Repositorys**: Erweitern Sie `XMLConnectionRepository` oder `CredentialRepository`, um benutzerdefinierte Datenquellen hinzuzufügen
2. **JobScheduler-Aktionen**: Neue Aktionstypen zu `JobExecutor` hinzufügen (z. B. benutzerdefinierte SFTP-Vorgänge)

## Leistungsüberlegungen

- **Lazy Loading**: Verbindungsdetails werden bei Bedarf geladen, nicht alle auf einmal
- **Kompression**: Projekt-Bildschirm-Snapshots und Terminal-Logs verwenden gzip; rotierte Session-Journal-Teile nutzen zstd (veraltete `.gz`-Teile bleiben lesbar)
- **Drosselung**: Terminal-Rendering-Updates werden stapelweise durchgeführt, um die Belastung des UI-Threads zu reduzieren
- **Speicherpooling**: Große Puffer für Terminaltext und SFTP-Dateiauflistung werden wiederverwendet

## Best Practices für die Sicherheit

1. **Master-Passwort**: Legen Sie ein sicheres, eindeutiges Passwort fest; es wird niemals übertragen oder protokolliert
2. **SSH-Schlüssel**: Zur Einbindung von Backups in `~/.kortty/ssh-keys/` speichern; Passphrasen werden in `ssh-keys.xml` verschlüsselt und das AES-256/GPG-verschlüsselte Backup enthält die Schlüsseldateien
3. **Hostschlüsselüberprüfung**: Überprüfen Sie den OpenSSH SHA-256-Fingerabdruck, bevor Sie eine interaktive Aufforderung zur ersten Verwendung akzeptieren. Halten Sie die Hostschlüssel-Anheftung für die unbeaufsichtigte JobScheduler-Ausführung aktiviert
4. **Backup-Verschlüsselung**: Verwenden Sie passwortgeschützte ZIP- oder GPG-Verschlüsselung
5. **KI-Profile**: Bevorzugen Sie ein integriertes lokales GGUF-Modell für sensible Daten; Überprüfen Sie die Vertrauens- und Datenrichtlinie jedes Remote-Endpunkts
6. **Protokollierungsgeheimnisse vermeiden**: Das JobScheduler-Journal schwärzt gespeicherte Geheimnisse vor der Persistenz
