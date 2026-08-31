---
title: Konfigurationsdateien
---

# Konfigurationsdateien

KorTTY speichert alle Anwendungsdaten und Konfigurationen im Verzeichnis `~/.kortty/` in Ihrem Home-Ordner. Diese Anleitung dokumentiert jede Datei und jedes Unterverzeichnis, ihren Zweck und ihre Verwendung.

## Verzeichnisstruktur

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

## Core-Konfigurationsdateien

### connections.xml
Enthält alle gespeicherten SSH-Verbindungen mit ihren Einstellungen.

**Beinhaltet:**
- Verbindungsname, Host, Port, Benutzername
- Authentifizierungsmethode (Passwort, SSH-Schlüssel, temporärer SSH-Schlüssel)
- Überschreibungen des Terminal-Erscheinungsbilds (Schriftart, Farben, Größe)
- SSH-Tunnel und Jump-Server-Konfiguration
- Optionale Überschreibung der SSH-Hostschlüsselüberprüfung pro Verbindung (überprüfen, nicht überprüfen oder erben)
- Terminaleffekt-Plugins und Animationsgeschwindigkeit
- Verbindungsspezifische Terminalprotokollierungseinstellungen
- Einstellungen für das Sitzungsjournal pro Verbindung (aktivieren, typisierte Eingaben erfassen, KI-Zusammenfassungen, Zusammenfassungsintervall)
- Einstellungen für Fenstergeometrie
- Gruppen-/Ordnerorganisation
- Optionales Freitext-Tag (wird für Suche, Massen-Tagging und Tag-basierten Export verwendet)

**Sicherheit:** Verbindungspasswörter werden mit AES-256-GCM unter Verwendung des Master-Passworts verschlüsselt.

!!! note
    Wenn ein Verbindungskennwort verschlüsselt ist, wird es in einem Hash-/verschlüsselten Format gespeichert und kann nicht als Klartext angezeigt werden. Wenn Sie eine gespeicherte Verbindung öffnen, wird das Passwort automatisch mit Ihrem Master-Passwort entschlüsselt.

### credentials.xml
Zentralisierte Speicherung von Anmeldeinformationen für Benutzername/Passwort-Paare.

**Beinhaltet:**
- Anmeldename, Benutzername, Passwort
- Environment (Produktion, Entwicklung, Test, Staging)
- Server-Muster (Glob-Muster wie `*.example.com` oder `10.0.0.*`)
- Automatische Zuweisung zu Verbindungen, die dem Muster entsprechen

**Sicherheit:** Alle Passwörter werden mit AES-256-GCM verschlüsselt.

### ssh-keys.xml
Verwaltet die zentrale SSH-Schlüsselspeicherung.

**Beinhaltet:**
- SSH-Schlüsselpfad
- Schlüsselpassphrase (wenn der Schlüssel geschützt ist)
- Optionale Beschreibung
- Schlüssel-Fingerabdruck

**Sicherheit:** Schlüsselpassphrasen werden mit AES-256-GCM unter Verwendung des Master-Passworts verschlüsselt.

!!! tip
    SSH-Schlüssel, auf die in dieser Datei verwiesen wird, können an ihrem ursprünglichen Speicherort aufbewahrt oder über die Aktion *In Benutzerverzeichnis kopieren* in der SSH-Schlüsselverwaltung nach `~/.kortty/ssh-keys/` kopiert werden. Kopierte Schlüssel sind in verschlüsselten Backups enthalten; Schlüssel, die an ihren ursprünglichen Speicherorten verbleiben, werden nur referenziert und müssen separat migriert werden.

### ssh-host-keys.properties

Der versionierte Trust-on-First-Use-Speicher für interaktive Terminal- und SFTP-Verbindungen und der von Mosh verwendete SSH-Bootstrap. Einträge werden durch normalisierten Hostnamen und Port verschlüsselt und enthalten den Public-Key-Algorithmus, den OpenSSH-SHA-256-Fingerabdruck, die OpenSSH-Public-Key-Zeile und den Vertrauenszeitstempel. Ein passender Schlüssel wird nach der Bestätigung der ersten Verwendung stillschweigend akzeptiert. Ein geänderter Schlüssel ist fest gesperrt und wird nicht automatisch ersetzt. Wenn die Überprüfung des Hostschlüssels für eine Verbindung auf „Akzeptieren neuer“ gelockert wird, wird ein unbekannter Schlüssel ohne Bestätigungsaufforderung angeheftet – ein geänderter Schlüssel wird in beiden Modi weiterhin abgelehnt.

Schreibvorgänge verwenden eine temporäre Datei plus atomare Ersetzung, während `ssh-host-keys.properties.lock` separate korTTY-Prozesse koordiniert, sodass ihre Pins sicher zusammengeführt werden. Die Eigenschaftendatei ist in verschlüsselten Backups enthalten; die vorübergehende Sperrdatei ist es nicht. Dieser endpunktbasierte Speicher ist von den JobScheduler-Hostschlüssel-Pins in `job-scheduler.xml` getrennt, die für unbeaufsichtigte Vorgänge nach Verbindungs-ID kodiert sind.

### gpg-keys.xml
Speichert GPG-Schlüsselinformationen für die Backup-Verschlüsselung.

**Beinhaltet:**
- GPG-Schlüssel-ID
- Schlüssel-E-Mail-Adresse
- Optionaler Fingerabdruck
- Importquelle (Systemschlüsselbund oder manuelle Eingabe)

### global-settings.xml
Globale Anwendungseinstellungen und Standardeinstellungen.

**Enthält:** jede Einstellung des Dialogs [Globale Einstellungen](settings/index.md), im Folgenden nach Bereichen gruppiert.

#### Aussehen und Schriftarten

- UI-Design- und Darstellungseinstellungen
- Schriftfamilie und Standardgröße
- UI-Schriftgröße (Prozent, 80–160 % oder automatisch von der Bildschirmauflösung abgeleitet) und die separate Textgröße der Anleitungs (70–250 %)
- Terminal-Farbkonfiguration

#### Fenster, Menüs und Panels

- Fenstergeometrie und -status (Position, Größe, maximierter Status)
- Präferenz für die Sichtbarkeit der Menüleiste
- Dashboard-Sichtbarkeitsstatus
- Flag „Toolfenster als Registerkarten öffnen“
- Angedocktes Live-Sitzungsjournal-Panel: Platzierung (versteckt/links/rechts) und Breite
- JobScheduler-Statusanzeigeeinstellung
- Letzte Vorschau-Zoomstufe des ASCII-Art-Dialogfelds

#### Terminal und Verbindungen

- Standardeinstellungen für die Terminalprotokollierung
- Standardeinstellungen für Terminaleffekt-Plugins
- SSH-Keep-Alive-Einstellungen
- Verbindungszeitlimit und Standardwerte für Wiederholungsversuche

#### KI, Modelle und Wissensspeicher

- KI-Profil-Standards, Text-/Coding-Rollenzuweisungen, eingebettete GGUF-Referenzen, Prompt-Voreinstellungen und Wissensspeicher-Zuordnungen
- RAG-Einbettungsmodell-ID und bevorzugtes Laufzeit-Backend sowie Update-Richtlinie für llama.cpp
- Optional verschlüsseltes Hugging-Face-Token
- Zeitlimit für KI-Anfragen in Minuten (0 = keine Begrenzung)

#### Sitzungsjournal

- Speicherordner und Capture-Log-Format
- KI-Zusammenfassungen (Intervall und Profil) und der Umschalter für die KI-Screenshot-Analyse
- Übersetzungssprache für Notizen
- KI-Zeilenfenster und Token-Budget
- Erscheinungsbild der Journalseite und Endhöhe des Live-Protokolls
- Benutzerdefinierte Markierungen und Markierungsregeln
- Die gespeicherten Geometrien der Journalfenster

#### Snippets und Übersetzung

- Standardeinstellungen der Snippet-Eingabe-Härtung (aktiviert, Optionen, maximale Dateigröße)
- Zielsprache der Snippet-Übersetzung
- Übersetzungs-API-Einstellungen

#### Export und Aufzeichnung

- PDF-Export-Wasserzeichen (standardmäßig deaktiviert; benutzerdefinierter Text und benutzerdefinierte Farbe)
- Dokumentexport-Fußzeile (standardmäßig aktiviert; benutzerdefinierter Text)
- Video-/Aufnahmeeinstellungen

#### Sicherheit und Backup

- Master-Passwort-Auto-Login-Flag (`skipMasterPasswordPrompt`)
- SSH-Opt-out für die Überprüfung des Hostschlüssels: das globale Flag und die Liste der Verbindungsgruppen, deren Überprüfung auf „Neue akzeptieren“ gelockert wird
- Backup-Verschlüsselungsmethode und Aufbewahrungseinstellungen

### llm/models.xml

Die atomar geschriebene JAXB-Registrierung für lokal installierte oder referenzierte GGUF-Modelle.

**Enthält:**

- Stabile Modell-ID und Anzeigename
- GGUF-Pfad und kompatibler `llama-server`-Ausführungspfad
- Backend (`AUTO`, `CPU`, `METAL` oder `VULKAN`)
- Kontextgröße, CPU-Threads, GPU-Ebenen und Leerlauf-Entlademinuten

Die Registrierung enthält Pfade und Einstellungen, keine Modellgewichtungen oder API-Schlüssel. `llm/models/` enthält verwaltete GGUF-Kopien, `llm/runtime/` enthält unabhängig aktualisierte native Pakete und `llm/run/` enthält temporäre Prozessverzeichnisse, Protokolle und nur vom Eigentümer generierte Schlüsseldateien. Temporäre Schlüssel werden entfernt, wenn der Beiwagen anhält.

### llm/runtime/

Der regenerierbare native Laufzeitbereich enthält unveränderliche Paketverzeichnisse sowie kleine atomare Statusdateien:

- `active-v1` zeigt auf die aktuell ausgewählte Installation.
- `pending-first-launch-v1` zeichnet einen Kandidaten und seine Rollback-Basis auf, bis ein echter GGUF-gestützter authentifizierter API-Start erfolgreich ist.
- `healthy-history-v1` behält höchstens die beiden neuesten bestätigten, nicht widerrufenen Installationen.
- `revoked-v1` ist die dauerhafte Denylist, die aus verifizierten signierten Indizes gelernt wurde. Ein widerrufenes Paket enthält auch `.kortty-runtime-revoked`.
- `blocked-active-v1` merkt sich die Laufzeit-ID, die durch eine Auszahlung aus der aktiven Nutzung entfernt wurde, sodass die Benutzeroberfläche erklären kann, warum die lokale KI blockiert bleibt.
- `packages/` enthält extrahierte verifizierte Installationen, während `downloads/` ein temporäres Staging ist, das durch die Updater-Sperre geschützt ist.

Bearbeiten oder löschen Sie die Sperrlisten-/Quarantänemarkierungen nicht, um ein Paket erneut zu aktivieren. Laufzeitstarts erzwingen sie unabhängig voneinander, und stattdessen muss ein kompatibler signierter Ersatz installiert werden. Das gesamte Verzeichnis ist von der Sicherung ausgeschlossen, da Pakete und Status aus dem signierten stabilen Kanal neu erstellt werden können.

### llm/catalog/last-valid-catalog-v1.json

Ein atomarer Cache-Umschlag, der die letzte Nutzlast des Modell-/Prompt-Katalogs und die zugehörige abgetrennte Signatur enthält. korTTY überprüft die Signatur und das strikte Schema vor jeder Cache-Nutzung erneut. Wenn die Anwendung über kein gültiges öffentliches Vertrauensstammverzeichnis für den Katalog verfügt, wird diese Datei ignoriert und der integrierte Bootstrap wird ohne Netzwerkaktualisierung verwendet. Der Cache ist regenerierbar und wird nicht in Backups einbezogen.

### rag/stores.json

Die atomar geschriebene, vom Eigentümer lesbare JSON-Registrierung für Wissensspeicher und deren Quellen.

**Enthält:**

- Wissensspeicher-ID/Name/Typ, lokales Snapshot-Verzeichnis oder Qdrant-Endpunkt/Sammlung, Einbettungsmodell-ID und Vektordimensionen
- Text-, Codierungs- und autonome Nutzungszuweisungen
- Stabile ID pro Quelle, kanonischer Pfad, Datei-/Verzeichnistyp, aktiviertes Flag, automatischer/manueller Synchronisierungsmodus, Größenbeschränkung, Einschluss-/Ausschluss-Globs, `.gitignore`-Präferenz, Inhalts-Hashes, letzter Status, Anzahl der Dateien/Chunks/Probleme und Zeitpunkt der letzten erfolgreichen Indexierung

Das Unterverzeichnis `rag/stores/` enthält regenerierbare `index.hnsw`-Snapshots. Ein v2-Snapshot bettet seine Formatversion, Vektordimensionen, Einbettungsmodell-ID, hierarchische Diagrammparameter, Einstiegspunkt, Chunk-Metadaten, Vektoren, Knotenebenen und Nachbarn pro Ebene ein; Eine Nichtübereinstimmung wird abgelehnt und erfordert einen Neuaufbau. Ein gültiger Single-Layer-V1-Snapshot der Legacy-Version wird beim Öffnen neu erstellt und atomar migriert.

### job-scheduler.xml
Alle JobScheduler-Jobs und zugehörige Daten.

**Enthält:**
- Jobname, aktivierter Status, Aktionstyp (COMMAND, SNIPPET_SCRIPT, AI_AGENT, SFTP, RSYNC_SYNC)
- Zeitplankonfiguration (Wochentage, Zeiten, Intervalle, Datumsbereiche)
- Zielserver oder -gruppen
- Host-Key-Pins (OpenSSH-Public-Key-Material für unbeaufsichtigte Ausführung)
- Sudo-Passwörter für Server und Gruppen (verschlüsselt)
- Journaleinträge mit Zeitstempeln, Exit-Codes und geschwärzter Ausgabe
- Journal-Aufbewahrungseinstellungen (Einträge, die älter als 14 Tage sind, standardmäßig automatisch löschen)

**Sicherheit:**
- Host-Key-Pinning ist standardmäßig für die unbeaufsichtigte SSH/SFTP/Rsync-Ausführung erforderlich
- Sudo-Passwörter werden mit dem Master-Passwort verschlüsselt
- Journaleinträge werden mit von KorTTY verwalteten Geheimnissen gespeichert, die vor der Persistenz geschwärzt wurden
- Archivkennwörter und Anmeldeinformationen für die Backup-Verschlüsselung werden verschlüsselt gespeichert

!!! warning
    Wenn das Hauptkennwort gesperrt ist und ein Job SSH-, Sudo-, API- oder Archivgeheimnisse benötigt, wird der Job blockiert und ein Journaleintrag mit einer Erläuterung des Problems erstellt.

### ai-chats.xml
Gespeicherte KI-Gespräche und Chat-Verlauf.

**Beinhaltet:**
- Chat-Titel und Erstellungszeitstempel
- Konversationsnachrichten und Antworten
- Zugehöriges AI-Profil, das für den Chat verwendet wird
- Follow-up-Eingabeaufforderungsverlauf (für Kontext)

**Hinweis:** AI-Ergebnisregisterkarten werden nicht automatisch gespeichert. Sie müssen sie explizit auf der Registerkarte „AI“ mit der Schaltfläche „Speichern“ speichern, um sie dieser Datei hinzuzufügen.

### snippets.xml
Codeausschnitte, Skripte und Vorlagen.

**Beinhaltet:**
- Snippet-Name, Beschreibung, Sprache
- Codeinhalt mit Metadaten zur Syntaxhervorhebung
- Kategorie-/Ordnerorganisation
- Tags und Metadaten
- Zielsystem (Spalte Betriebssystem: Linux, macOS, Windows usw.)
- Import-/Exportverlauf

**Merkmale:**
- Unterstützung für JSON/XML/YAML-Import/Export
- Export von Nur-Text-Skripten
- ZIP-Archive mit optionaler Passwort- oder GPG-Verschlüsselung
- Lokale Syntaxhervorhebung mit dem Monaco-Editor
- AI-unterstützte Bearbeitung und Codegenerierung
- Persistierte Mermaid-Diagramme (Flussdiagramm mit logischer Struktur, Sequenz, Zustand, Klasse, ER) mit stabilen Code-Referenz-Knoten-IDs und, für Diagramme mit Auswahlbereich, dem abgedeckten Linienbereich
- Einzeiliger Export mit optionalen Skriptargumenten

### snippet-variables.xml
Speichert Variablendefinitionen zur Verwendung in Snippets.

**Beinhaltet:**
- Variablenname, Wert, Typ
- Scope (lokal oder gemeinsam genutzt)
- Standardwerte und Validierungsregeln

### master.key
Binärdatei, die das gehashte Master-Passwort enthält.

**Format:** PBKDF2-Hash mit 310.000 Iterationen

**Sicherheit:** Diese Datei enthält nicht das eigentliche Master-Passwort, sondern nur einen kryptografischen Hash, der zur Überprüfung des Passworts verwendet wird, das Sie beim Start eingeben. Wenn diese Datei verloren geht oder beschädigt ist, müssen Sie KorTTY neu starten und ein neues Master-Passwort festlegen (Sie verlieren jedoch den Zugriff auf zuvor gespeicherte verschlüsselte Anmeldeinformationen und SSH-Schlüssel-Passphrasen).

!!! warning
    Wenn Sie Ihr Master-Passwort vergessen, löschen Sie `master.key` und `credentials.xml`, starten Sie KorTTY neu, legen Sie ein neues Master-Passwort fest und geben Sie Ihre Passwörter erneut ein. Es gibt keinen Wiederherstellungsmechanismus für das verlorene Passwort.

### master.autounlock
Wird nur geschrieben, während [auto-login](settings/security.md) aktiviert ist: eine Kopie des Master-Passworts, **nur verschleiert – nicht verschlüsselt**, mit Dateiberechtigungen nur für Besitzer. Wenn Sie es löschen (oder die Option deaktivieren), wird die normale Startaufforderung wiederhergestellt.

### terminal-effect-plugins.disabled
Textdatei mit den IDs der deaktivierten Terminaleffekt-Plugins (eine pro Zeile).

**Zweck:** Wenn Sie ein Terminaleffekt-Plugin über *Plugins > Terminaleffekte* deaktivieren, wird seine ID in diese Datei geschrieben, sodass es nach dem Neustart deaktiviert bleibt.

### kortty.log
Anwendungsprotokolldatei.

**Enthält:**
- Startmeldungen
- Verbindungsversuche und Ergebnisse
- Terminalsitzungsereignisse
- Konfigurationsänderungen
- Fehler und Warnungen
- Leistungsmetriken (auf Anfrage über JMX)

**Rotation:** Die Protokolldatei wächst während der Anwendungssitzung. Alte Protokolle werden nicht automatisch rotiert (die Protokolldatei bleibt bestehen, bis KorTTY beendet wird oder Sie sie manuell löschen).

!!! tip
    Überprüfen Sie diese Datei, wenn Sie Verbindungsprobleme, Probleme beim Laden von Plugins oder unerwartetes Verhalten beheben. Zu den hier protokollierten häufigen Problemen gehören SSH-Fehler, Verschlüsselungsfehler und Import-/Exportprobleme.

## Verzeichnisse

### history/
Komprimierter Terminalsitzungsverlauf.

**Format:** GZIP-komprimierte Textdateien, eine pro Terminalsitzung

**Benennung:** `{session-id}_{timestamp}.history.gz` (für den Sitzungsverlauf aus der Terminalprotokollierung)

**Zweck:** Speichert den Projekt-/Sitzungs-Scrollback-Verlauf, damit wieder geöffnete Sitzungen ihren Terminalinhalt wiederherstellen können.

**Zugriff:** Der Terminalverlauf wird automatisch geladen, wenn Sie eine gespeicherte Verbindung öffnen, und in der Suchfunktion für den Terminalverlauf angezeigt.

!!! note
    Pro Verbindung schreibt *Terminal Logging* hier nicht: Die generierten Protokolldateien werden in den Ordner verschoben, der auf der Registerkarte „Terminal Logging“ der Verbindung konfiguriert ist, oder in `~/.kortty/terminal-logs/`, wenn dieser Ordner leer bleibt.

### journals/
Sitzungsjournale – ein eigenständiges Verzeichnis pro Journal (Speicherort konfigurierbar unter **Einstellungen > Protokollierung > Sitzungsjournal**). Jedes Journalverzeichnis enthält `journal.xml` (das kuratierte Dokument: Metadaten, KI-Zusammenfassungen, Markierungen, Notizen, Screenshot-Referenzen), das Nur-Anhängen-Capture-Log `session-log.json` / `.xml` / `.yaml` (standardmäßig JSON Lines) mit zstd-komprimierten gedrehten Teilen (Teilgröße und Teileanzahl sind pro Verbindung auf der Registerkarte „Journal“ konfigurierbar, standardmäßig 25 MB und 20 Teile; Journale aus älteren Versionen behalten ihre gzip-komprimierte `.gz`-Teile), die generierte `journal.html`-Timeline-Seite und `screenshots/*.png`. Siehe [Sitzungsjournal](../features/session-journal.md).

### terminal-logs/
Standardzielordner für [Terminalprotokolle pro Verbindung](../features/terminal.md#terminalprotokollierung), wenn das Protokollordnerfeld einer Verbindung leer bleibt. Dateibenennung, tägliche Rotation, Komprimierung und Aufbewahrung richten sich nach der Protokollierungskonfiguration der Verbindung.

### plugins/
Vom Benutzer importierte Terminal-Effekt-Plugin-JARs.

**Zweck:** Externe Terminal-Effekt-Plugins, die Sie über *Plugins > Terminal-Effekte > Importieren* importieren.

**Sicherheit:** Importierte Plugins sind vertrauenswürdiger Java-Code und werden nicht in einer Sandbox gespeichert. Importieren Sie JARs nur aus Quellen, denen Sie vertrauen.

**Bereinigung:** Wenn Sie ein Plugin aus diesem Verzeichnis löschen, ist es in KorTTY nicht mehr verfügbar. Deaktivierte Plugins bleiben in diesem Verzeichnis, werden aber in `terminal-effect-plugins.disabled` aufgeführt.

!!! warning
    Plugin-Abhängigkeiten müssen in der Plugin-JAR schattiert werden. Benachbarte Abhängigkeits-JARs werden nicht automatisch geladen. Verpackungsrichtlinien finden Sie unter [Terminaleffekt-Plugins](../features/terminal-effect-plugins.md).

### bundled-plugins/
Laufzeitkopien der gebündelten exportierbaren Terminal-Effekt-Plugin-JARs.

**Zweck:** Backup und Arbeitskopien integrierter Plugins, die an externe Benutzer exportiert werden können.

**Inhalt:** Das MU/TH/UR 6000-Effekt-Plugin und alle anderen exportierbaren gebündelten Plugins.

**Automatische Verwaltung:** KorTTY verwaltet dieses Verzeichnis automatisch. Benutzer sollten es nicht manuell bearbeiten.

### Projekte/
Projektdateien zum Speichern und Laden von Verbindungssätzen.

**Format:** KorTTY XML-basiertes Projektformat (`.kortty`-Dateien)

**Benennung:** Benutzerdefinierte Projektnamen mit der Erweiterung `.kortty` (z. B. `production.kortty`, `development.kortty`)

**Enthält:**
- Liste der offenen Verbindungen/Registerkarten zum Projektspeicherzeitpunkt
- Fensterstatus (Registerkarten, Größen, aktive Registerkarte)
- Dashboard-Status
- Projektmetadaten und Erstellungszeitstempel

**Zweck:** Öffnen Sie schnell einen vorkonfigurierten Satz von Verbindungen für ein bestimmtes Projekt oder einen bestimmten Workflow.

**Verwendung:** Speichern Sie ein Projekt über *Datei > Projekt speichern*, stellen Sie es über *Datei > Projekt öffnen* oder das Projektverlaufsmenü wieder her.

### i18n/
Dynamisch generierte Sprachübersetzungsdateien.

**Format:** Java-Eigenschaftendateien (`.properties`)

**Benennung:** `messages_{language-code}.properties` (z. B. `messages_de.properties` für Deutsch, `messages_fr.properties` für Französisch)

**Zweck:** Speichert Übersetzungen, die von Übersetzungs-APIs (Google Translate, DeepL, LibreTranslate, Microsoft Translator oder Yandex) generiert wurden, wenn Sie über *Einstellungen > Übersetzung* die Generierung von Sprachdateien auswählen.

**Automatische Aktualisierung:** Wenn Sie KorTTY auf eine neue Version aktualisieren, werden generierte Sprachdateien als veraltet markiert. Verwenden Sie *Einstellungen > Übersetzung > Veraltete neu generieren*, um sie mit allen neuen oder geänderten UI-Schlüsseln zu aktualisieren.

!!! note
    Integrierte Sprachen (Englisch, Deutsch, Italienisch, Spanisch, Portugiesisch, Französisch, Kroatisch, Niederländisch) werden mit der Anwendung geliefert und verwenden dieses Verzeichnis nicht. Dieses Verzeichnis wird nur für dynamisch generierte Übersetzungen verwendet.

### ssh-keys/
Optionales Verzeichnis für kopierte SSH-Schlüssel.

**Zweck:** Speichert Kopien von SSH-Schlüsseln an einem Ort für Backup und Migration.

**Anwendung:**
1. Öffnen Sie *Verwaltung > SSH-Schlüssel verwalten...*
2. Wählen Sie einen SSH-Schlüssel aus und klicken Sie auf *In Benutzerverzeichnis kopieren*
3. Der Schlüssel wird kopiert `~/.kortty/ssh-keys/`

**Backup:** Die Schlüsseldateien in diesem Verzeichnis werden einbezogen, wenn Sie über *Bearbeiten > Backup erstellen* ein Backup erstellen (das Archiv ist AES-256- oder GPG-verschlüsselt). Beim Import werden sie mit Dateiberechtigungen, die nur dem Besitzer vorbehalten sind, wiederhergestellt und der Import wird zusammengeführt: Lokale Schlüssel werden niemals gelöscht und vorhandene Dateien werden nur ersetzt, wenn **Überschreiben** aktiviert ist.

**Vorteile:**
- Zentraler Speicherort für alle SSH-Schlüssel
- In verschlüsselten Backups enthalten
- Einfache Migration auf neue Maschinen

## Sicherheitsübersicht

| Artikel | Sicherheitsmethode |
|------|-----------------|
| Master-Passwort | PBKDF2-Hashing mit 310.000 Iterationen |
| Verbindungspasswörter | AES-256-GCM-Verschlüsselung |
| SSH-Schlüsselpassphrasen | AES-256-GCM-Verschlüsselung |
| Hostschlüssel für interaktives Terminal/SFTP/Mosh | Normalisiertes Host:Port-TOFU mit OpenSSH SHA-256-Fingerabdrücken und Fail-Closed-Änderungserkennung; Das optionale Opt-Out pro Verbindung/Gruppe/Global lockert nur die Aufforderung zum Akzeptieren neuer Schlüssel mit unbekannten Schlüsseln |
| Anmeldeinformationen (Benutzername/Passwort) | AES-256-GCM-Verschlüsselung |
| JobScheduler Sudo-Passwörter | AES-256-GCM-Verschlüsselung |
| JobScheduler-Journaleinträge | Geschwärzte Geheimnisse vor Persistenz |
| Sicherungsdateien | Passwortgeschützte ZIP- oder GPG-Verschlüsselung |
| API-Schlüssel (AI-Profile) | AES-256-GCM-Verschlüsselung mit Master-Passwort |
| Terminaleffekt-Plugins | Nicht verschlüsselt; Vertrauenswürdiger lokaler Code, kein Sandbox-Code |

## Dateispeicherorte nach Plattform

Alle Dateien werden plattformübergreifend im selben `~/.kortty/`-Verzeichnis gespeichert:

- **macOS:** `/Users/{username}/.kortty/`
- **Windows:** `C:\Users\{username}\.kortty\` (oder `%USERPROFILE%\.kortty\`)
- **Linux:** `/home/{username}/.kortty/` (oder `$HOME/.kortty/`)

## Sicherung und Wiederherstellung

Wenn Sie über *Bearbeiten > Backup erstellen* ein Backup erstellen, ist die folgende Konfiguration enthalten:

- Alle `.xml`-Konfigurationsdateien (Verbindungen, Anmeldeinformationen, SSH-Schlüsselreferenzen und Passphrasen, GPG-Schlüssel, globale Einstellungen, JobScheduler, Snippets, Snippet-Variablen, AI-Chats)
- `master.key`
- `projects/` Verzeichnis
- `ssh-keys/`-Verzeichnis – kopierte SSH-Schlüsseldateien (wiederhergestellt mit Nur-Eigentümer-Berechtigungen; Importe führen lokale Schlüssel zusammen und löschen sie niemals)
- `ssh-host-keys.properties` interaktiver Hostschlüssel-Truststore (nicht seine transiente `.lock`-Datei)
- `llm/models.xml` lokale Modellregistrierungen
- `rag/stores.json` Wissensspeicher-/Quellenmetadaten

Verwaltete GGUF-Gewichte, native Laufzeitpakete, temporäre Sidecar-Daten, Originalquelldokumente und HNSW-Snapshots sind ausgeschlossen, da sie groß oder regenerierbar sind. Die Verzeichnisse `history/` und `i18n/` sind ebenfalls nicht Teil eines Backups, ebenso wenig wie die Auto-Login-Datei `master.autounlock`.

Das Backup wird verschlüsselt (passwortgeschütztes ZIP oder GPG) und an einem von Ihnen angegebenen Ort gespeichert.

!!! tip
    Backups sind die empfohlene Methode, um KorTTY auf einen neuen Computer zu migrieren oder nach einem Datenverlust wiederherzustellen. Erstellen Sie regelmäßig ein Backup und bewahren Sie es an einem sicheren Ort auf.

## Zugriff auf Konfigurationsdateien

Sie können die KorTTY-Konfiguration direkt bearbeiten, indem Sie:

1. KorTTY vollständig schließen
2. Öffnen Sie `~/.kortty/` in Ihrem Dateimanager oder Terminal
3. Bearbeiten der XML- oder JSON-Datei mit einem Texteditor
4. KorTTY wird neu gestartet, um die Änderungen zu laden

!!! warning
    Das direkte Bearbeiten von XML/JSON-Dateien kann Ihre Daten beschädigen, wenn es nicht sorgfältig durchgeführt wird. Bearbeiten Sie niemals einen binären `index.hnsw`-Snapshot. Erstellen Sie vor der manuellen Bearbeitung immer ein Backup. Verwenden Sie für die meisten Konfigurationsaufgaben stattdessen die KorTTY-Benutzeroberfläche – sie verarbeitet Verschlüsselung, Validierung und Dateiformat korrekt.

## Fehlerbehebung

**Konfigurationsdatei nicht gefunden:**
- KorTTY erstellt beim ersten Start das Verzeichnis `~/.kortty/` und alle erforderlichen Unterverzeichnisse.
- Wenn eine Konfigurationsdatei fehlt, verwendet KorTTY sinnvolle Standardeinstellungen und erstellt die Datei beim nächsten Speichern.

**Verschlüsselungsfehler:**
- Wenn Sie Ihr Master-Passwort vergessen, müssen Sie `master.key` löschen und ein neues Passwort festlegen. Auf zuvor verschlüsselte Daten kann nicht mehr zugegriffen werden.

**Beschädigte XML-Dateien:**
- Wenn eine `.xml`-Datei beschädigt ist, stellen Sie sie aus einer Sicherung wieder her oder löschen Sie die Datei. KorTTY wird es beim nächsten Speichern mit den Standardeinstellungen neu erstellen.

**Wissensspeicher-Registrierung oder Snapshot kann nicht gelesen werden:**
- Stellen Sie `rag/stores.json` aus einem Backup wieder her oder erstellen Sie den Wissensspeicher im KI-Manager neu. Löschen Sie nur die betroffene regenerierbare Datei `index.hnsw` und wählen Sie dann **Jetzt aktualisieren**, um sie aus den konfigurierten Quelldateien neu zu erstellen.

**Interaktiver SSH-Hostschlüssel geändert:**
- Stellen Sie keine erneute Verbindung her, bis Sie den neuen OpenSSH SHA-256-Fingerabdruck beim Serveradministrator überprüft und DNS-, Routing- oder Man-in-the-Middle-Probleme ausgeschlossen haben. KorTTY blockiert absichtlich die Nichtübereinstimmung, ohne es erneut zu versuchen oder die gespeicherte PIN zu ersetzen.

**Probleme beim Laden des Plugins:**
- Überprüfen Sie `kortty.log` auf Fehlermeldungen im Zusammenhang mit dem Laden von Plugins (z. B. doppelte IDs, fehlende Dienste, Fehler beim Laden von Klassen).
- Stellen Sie sicher, dass sich die Plugin-JAR in `~/.kortty/plugins/` befindet und `META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin` enthält.
- Verwenden Sie *Plugins > Terminaleffekte > Neu laden*, um die Plugin-Liste zu aktualisieren.

**Größe der Protokolldatei:**
- `kortty.log` wächst während der Anwendungssitzung. Es wird nicht automatisch gedreht. Sie können es sicher löschen, während KorTTY geschlossen ist.

**Wiederherstellung des Master-Passworts:**
- Es gibt keine Wiederherstellung für ein vergessenes Master-Passwort. Wenn Sie es verlieren, löschen Sie `master.key` und `credentials.xml`, starten Sie KorTTY neu und legen Sie ein neues Passwort fest.
