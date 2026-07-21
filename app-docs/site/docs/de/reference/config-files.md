---
title: Konfigurationsdateien
---

# Konfigurationsdateien

KorTTY speichert alle Anwendungsdaten und Konfigurationen im Verzeichnis `~/.kortty/` in Ihrem Home-Ordner. Diese Anleitung dokumentiert jede Datei und jedes Unterverzeichnis, ihren Zweck und ihre Verwendung.

## Verzeichnisstruktur

```
~/.kortty/
├── connections.xml                    # Saved SSH connections
├── credentials.xml                    # Wissensspeicherd credentials (encrypted)
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
├── master-password-hash               # Hashed master password (PBKDF2)
├── terminal-effect-plugins.disabled   # Disabled terminal-effect plugin IDs
├── kortty.log                         # Application log file
├── history/                           # Terminal session history (compressed)
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
- Einstellungen für Fenstergeometrie
- Gruppen-/Ordnerorganisation

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
    SSH-Schlüssel, auf die in dieser Datei verwiesen wird, können an ihrem ursprünglichen Speicherort aufbewahrt oder zur einfachen Sicherung und Migration über die Aktion *In Benutzerverzeichnis kopieren* in der SSH-Schlüsselverwaltung nach `~/.kortty/ssh-keys/` kopiert werden.

### ssh-host-keys.properties

Der versionierte Trust-on-First-Use-Speicher für interaktive Terminal- und SFTP-Verbindungen und der von Mosh verwendete SSH-Bootstrap. Einträge werden durch normalisierten Hostnamen und Port verschlüsselt und enthalten den Public-Key-Algorithmus, den OpenSSH-SHA-256-Fingerabdruck, die OpenSSH-Public-Key-Zeile und den Vertrauenszeitstempel. Ein passender Schlüssel wird nach der Bestätigung der ersten Verwendung stillschweigend akzeptiert; Ein geänderter Schlüssel ist fest gesperrt und wird nicht automatisch ersetzt. Wenn die Überprüfung des Hostschlüssels für eine Verbindung auf „Akzeptieren neuer“ gelockert wird, wird ein unbekannter Schlüssel ohne Bestätigungsaufforderung angeheftet – ein geänderter Schlüssel wird in beiden Modi weiterhin abgelehnt.

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

**Enthält:**
- UI-Design- und Darstellungseinstellungen
- Schriftfamilie und Standardgröße
- Terminal-Farbkonfiguration
- Fenstergeometrie und -status (Position, Größe, maximierter Status)
- Dashboard-Sichtbarkeitsstatus
- Präferenz für die Sichtbarkeit der Menüleiste
- Letzte Vorschau-Zoomstufe des ASCII-Art-Dialogfelds
- AI-Profilstandards, Text-/Coding-Rollenzuweisungen, eingebettete GGUF-Referenzen, Eingabeaufforderungsvoreinstellungen und Wissensspeicherzuordnungen
- RAG-Einbettungsmodell-ID und bevorzugte Laufzeit-Backend-/Update-Richtlinie für llama.cpp
- Optional verschlüsseltes Hugging Face-Token
- Übersetzungs-API-Einstellungen
- Video-/Aufnahmeeinstellungen
- Standardeinstellungen für die Terminalprotokollierung
- SSH Keep-Alive-Einstellungen
- SSH-Opt-out für die Überprüfung des Hostschlüssels: das globale Flag und die Liste der Verbindungsgruppen, deren Überprüfung auf „Akzeptieren von Neu“ gelockert wird
- JobScheduler-Statusanzeigeeinstellung
- Standardeinstellungen für das Terminaleffekt-Plugin
- Backup-Verschlüsselungsmethode und Aufbewahrungseinstellungen
- Verbindungszeitlimit und Standardwerte für Wiederholungsversuche

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
- Journaleinträge mit Zeitstempeln, Exit-Codes und redigierter Ausgabe
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
- Persistierte Mermaid-Flussdiagramme mit stabilen Code-Referenz-Knoten-IDs
- Einzeiliger Export mit optionalen Skriptargumenten

### snippet-variables.xml
Speichert Variablendefinitionen zur Verwendung in Snippets.

**Beinhaltet:**
- Variablenname, Wert, Typ
- Scope (lokal oder gemeinsam genutzt)
- Standardwerte und Validierungsregeln

### master-password-hash
Binärdatei, die das gehashte Master-Passwort enthält.

**Format:** PBKDF2-Hash mit 310.000 Iterationen

**Sicherheit:** Diese Datei enthält nicht das eigentliche Master-Passwort, sondern nur einen kryptografischen Hash, der zur Überprüfung des Passworts verwendet wird, das Sie beim Start eingeben. Wenn diese Datei verloren geht oder beschädigt ist, müssen Sie KorTTY neu starten und ein neues Master-Passwort festlegen (Sie verlieren jedoch den Zugriff auf zuvor gespeicherte verschlüsselte Anmeldeinformationen und SSH-Schlüssel-Passphrasen).

!!! warning
    Wenn Sie Ihr Master-Passwort vergessen, löschen Sie `master-password-hash` und `credentials.xml`, starten Sie KorTTY neu, legen Sie ein neues Master-Passwort fest und geben Sie Ihre Passwörter erneut ein. Es gibt keinen Wiederherstellungsmechanismus für das verlorene Passwort.

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

**Zweck:** Speichert den Verlauf und die Ausgabe von Terminalbefehlen, wenn die Terminalprotokollierung für eine Verbindung aktiviert ist.

**Zugriff:** Der Terminalverlauf wird automatisch geladen, wenn Sie eine gespeicherte Verbindung öffnen, und in der Suchfunktion für den Terminalverlauf angezeigt.

!!! note
    In diesem Verzeichnis wird der Verlauf nur gespeichert, wenn Sie beim Erstellen oder Bearbeiten einer Verbindung explizit *Terminalprotokollierung* für eine Verbindung auf der Registerkarte *Terminalprotokollierung* aktivieren.

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

**Zweck:** Speichert Kopien von SSH-Schlüsseln zur einfachen Sicherung und Migration.

**Anwendung:**
1. Öffnen Sie *Verwaltung > SSH-Schlüssel verwalten...*
2. Wählen Sie einen SSH-Schlüssel aus und klicken Sie auf *In Benutzerverzeichnis kopieren*
3. Der Schlüssel wird kopiert `~/.kortty/ssh-keys/`

**Backup:** SSH-Schlüssel in diesem Verzeichnis werden einbezogen, wenn Sie über *Bearbeiten > Backup erstellen* ein Backup erstellen.

**Vorteile:**
- Zentraler Speicherort für alle SSH-Schlüssel
- Einfache Migration auf neue Maschinen
- In verschlüsselten Backups enthalten

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

- Alle `.xml`-Konfigurationsdateien
- `master-password-hash`
- `history/` Verzeichnis
- `projects/` Verzeichnis
Verzeichnis - `i18n/` (generierte Sprachdateien)
- `ssh-keys/`-Verzeichnis (falls vorhanden)
- `ssh-host-keys.properties` interaktiver Hostschlüssel-Truststore (nicht seine transiente `.lock`-Datei)
- `snippets.xml` und zugehörige Snippet-Daten
- `llm/models.xml` lokale Modellregistrierungen
- `rag/stores.json` Wissensspeicher-/Quellenmetadaten

Verwaltete GGUF-Gewichte, native Laufzeitpakete, temporäre Sidecar-Daten, Originalquelldokumente und HNSW-Snapshots sind ausgeschlossen, da sie groß oder regenerierbar sind.

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
- Wenn Sie Ihr Master-Passwort vergessen, müssen Sie `master-password-hash` löschen und ein neues Passwort festlegen. Auf zuvor verschlüsselte Daten kann nicht mehr zugegriffen werden.

**Beschädigte XML-Dateien:**
- Wenn eine `.xml`-Datei beschädigt ist, stellen Sie sie aus einer Sicherung wieder her oder löschen Sie die Datei. KorTTY wird es beim nächsten Speichern mit den Standardeinstellungen neu erstellen.

**Wissensspeicher-Registrierung oder Snapshot kann nicht gelesen werden:**
- Stellen Sie `rag/stores.json` aus einem Backup wieder her oder erstellen Sie den Wissensspeicher im AI Manager neu. Löschen Sie nur die betroffene regenerierbare Datei `index.hnsw` und wählen Sie dann **Jetzt aktualisieren**, um sie aus den konfigurierten Quelldateien neu zu erstellen.

**Interaktiver SSH-Hostschlüssel geändert:**
- Stellen Sie keine erneute Verbindung her, bis Sie den neuen OpenSSH SHA-256-Fingerabdruck beim Serveradministrator überprüft und DNS-, Routing- oder Man-in-the-Middle-Probleme ausgeschlossen haben. KorTTY blockiert absichtlich die Nichtübereinstimmung, ohne es erneut zu versuchen oder die gespeicherte PIN zu ersetzen.

**Probleme beim Laden des Plugins:**
- Überprüfen Sie `kortty.log` auf Fehlermeldungen im Zusammenhang mit dem Laden von Plugins (z. B. doppelte IDs, fehlende Dienste, Fehler beim Laden von Klassen).
- Stellen Sie sicher, dass sich die Plugin-JAR in `~/.kortty/plugins/` befindet und `META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin` enthält.
- Verwenden Sie *Plugins > Terminaleffekte > Neu laden*, um die Plugin-Liste zu aktualisieren.

**Größe der Protokolldatei:**
- `kortty.log` wächst während der Anwendungssitzung. Es wird nicht automatisch gedreht. Sie können es sicher löschen, während KorTTY geschlossen ist.

**Wiederherstellung des Master-Passworts:**
- Es gibt keine Wiederherstellung für ein vergessenes Master-Passwort. Wenn Sie es verlieren, löschen Sie `master-password-hash` und `credentials.xml`, starten Sie KorTTY neu und legen Sie ein neues Passwort fest.
