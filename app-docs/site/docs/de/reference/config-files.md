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
├── ai-chats.xml                       # Saved AI conversations
├── snippets.xml                       # Code snippets and scripts
├── snippet-variables.xml              # Snippet variable storage
├── job-scheduler.xml                  # JobScheduler jobs, host-key pins, sudo secrets, journal
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

## Kernkonfigurationsdateien

### Verbindungen.xml
Enthält alle gespeicherten SSH-Verbindungen mit ihren Einstellungen.

**Beinhaltet:**
- Verbindungsname, Host, Port, Benutzername
- Authentifizierungsmethode (Passwort, SSH-Schlüssel, temporärer SSH-Schlüssel)
- Überschreibungen des Terminal-Erscheinungsbilds (Schriftart, Farben, Größe)
- SSH-Tunnel und Jump-Server-Konfiguration
- Terminaleffekt-Plugins und Animationsgeschwindigkeit
- Verbindungsspezifische Terminalprotokollierungseinstellungen
- Einstellungen für die Fenstergeometrie
- Gruppen-/Ordnerorganisation

**Sicherheit:** Verbindungspasswörter werden mit AES-256-GCM unter Verwendung des Master-Passworts verschlüsselt.

!!! Notiz
    Wenn ein Verbindungskennwort verschlüsselt ist, wird es in einem Hash-/verschlüsselten Format gespeichert und kann nicht als Klartext angezeigt werden. Wenn Sie eine gespeicherte Verbindung öffnen, wird das Passwort automatisch mit Ihrem Master-Passwort entschlüsselt.

### Anmeldeinformationen.xml
Zentralisierte Speicherung von Anmeldeinformationen für Benutzername/Passwort-Paare.

**Beinhaltet:**
- Anmeldename, Benutzername, Passwort
- Umgebung (Produktion, Entwicklung, Test, Staging)
- Servermuster (Globmuster wie `*.example.com` oder `10.0.0.*`)
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

!!! Tipp
    SSH-Schlüssel, auf die in dieser Datei verwiesen wird, können an ihrem ursprünglichen Speicherort aufbewahrt oder zur einfachen Sicherung und Migration über die Aktion *In Benutzerverzeichnis kopieren* in der SSH-Schlüsselverwaltung nach `~/.kortty/ssh-keys/` kopiert werden.

### gpg-keys.xml
Speichert GPG-Schlüsselinformationen für die Backup-Verschlüsselung.

**Beinhaltet:**
- GPG-Schlüssel-ID
- Schlüssel-E-Mail-Adresse
- Optionaler Fingerabdruck
- Quelle importieren (Systemschlüsselbund oder manuelle Eingabe)

### global-settings.xml
Globale Anwendungseinstellungen und Standardeinstellungen.

**Enthält:**
- Einstellungen für UI-Design und Erscheinungsbild
- Schriftfamilie und Standardgröße
- Konfiguration der Terminalfarbe
- Fenstergeometrie und -status (Position, Größe, maximierter Status)
- Sichtbarkeitsstatus des Dashboards
- Präferenz für die Sichtbarkeit der Menüleiste
- AI-Profilstandards und -konfiguration
- Übersetzungs-API-Einstellungen
- Video-/Aufnahmeeinstellungen
- Standardeinstellungen für die Terminalprotokollierung
- SSH-Keepalive-Einstellungen
- Voreinstellung für die JobScheduler-Statusanzeige
- Standardeinstellungen für Terminaleffekt-Plugins
- Backup-Verschlüsselungsmethode und Aufbewahrungseinstellungen
- Standardeinstellungen für Verbindungszeitlimit und Wiederholungsversuche

### job-scheduler.xml
Alle JobScheduler-Jobs und zugehörige Daten.

**Enthält:**
- Jobname, aktivierter Status, Aktionstyp (COMMAND, SNIPPET_SCRIPT, AI_AGENT, SFTP, RSYNC_SYNC)
- Zeitplankonfiguration (Wochentage, Zeiten, Intervalle, Datumsbereiche)
- Zielserver oder -gruppen
- Host-Key-Pins (OpenSSH-Public-Key-Material für unbeaufsichtigte Ausführung)
- Sudo-Passwörter für Server und Gruppen (verschlüsselt)
- Journaleinträge mit Zeitstempeln, Exit-Codes und redigierter Ausgabe
- Einstellungen zur Journalaufbewahrung (Einträge, die älter als 14 Tage sind, werden standardmäßig automatisch gelöscht)

**Sicherheit:**
– Für die unbeaufsichtigte SSH/SFTP/Rsync-Ausführung ist standardmäßig das Anheften des Hostschlüssels erforderlich
- Sudo-Passwörter werden mit dem Master-Passwort verschlüsselt
- Journaleinträge werden mit von KorTTY verwalteten Geheimnissen gespeichert, die vor der Persistenz geschwärzt wurden
- Archivkennwörter und Anmeldeinformationen für die Backup-Verschlüsselung werden verschlüsselt gespeichert

!!! Warnung
    Wenn das Hauptkennwort gesperrt ist und ein Job SSH-, Sudo-, API- oder Archivgeheimnisse benötigt, wird der Job blockiert und ein Journaleintrag mit einer Erläuterung des Problems erstellt.

### ai-chats.xml
Gespeicherte KI-Gespräche und Chat-Verlauf.

**Beinhaltet:**
- Chat-Titel und Erstellungszeitstempel
- Konversationsnachrichten und Antworten
- Zugehöriges KI-Profil, das für den Chat verwendet wird
- Verlauf der Folgeaufforderungen (für den Kontext)

**Hinweis:** AI-Ergebnisregisterkarten werden nicht automatisch gespeichert. Sie müssen sie explizit auf der Registerkarte „AI“ mit der Schaltfläche „Speichern“ speichern, um sie dieser Datei hinzuzufügen.

### snippets.xml
Codeausschnitte, Skripte und Vorlagen.

**Beinhaltet:**
- Snippet-Name, Beschreibung, Sprache
- Codeinhalte mit Metadaten zur Syntaxhervorhebung
- Kategorie-/Ordnerorganisation
- Tags und Metadaten
- Zielsystem (Spalte Betriebssystem: Linux, macOS, Windows, etc.)
- Import-/Exportverlauf

**Merkmale:**
- Unterstützung für JSON/XML/YAML-Import/Export
- Export von Nur-Text-Skripten
- ZIP-Archive mit optionaler Passwort- oder GPG-Verschlüsselung
- Hervorhebung der lokalen Syntax mit dem Monaco-Editor
- KI-gestützte Bearbeitung und Codegenerierung
- PlantUML-Diagrammunterstützung
- Einzeiliger Export mit optionalen Skriptargumenten

### snippet-variables.xml
Speichert Variablendefinitionen zur Verwendung in Snippets.

**Beinhaltet:**
- Variablenname, Wert, Typ
- Geltungsbereich (lokal oder gemeinsam genutzt)
- Standardwerte und Validierungsregeln

### Master-Passwort-Hash
Binärdatei, die das gehashte Master-Passwort enthält.

**Format:** PBKDF2-Hash mit 310.000 Iterationen

**Sicherheit:** Diese Datei enthält nicht das eigentliche Master-Passwort, sondern nur einen kryptografischen Hash, der zur Überprüfung des Passworts verwendet wird, das Sie beim Start eingeben. Wenn diese Datei verloren geht oder beschädigt ist, müssen Sie KorTTY neu starten und ein neues Master-Passwort festlegen (Sie verlieren jedoch den Zugriff auf zuvor gespeicherte verschlüsselte Anmeldeinformationen und SSH-Schlüssel-Passphrasen).

!!! Warnung
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

!!! Tipp
    Überprüfen Sie diese Datei, wenn Sie Verbindungsprobleme, Probleme beim Laden von Plugins oder unerwartetes Verhalten beheben. Zu den hier protokollierten häufigen Problemen gehören SSH-Fehler, Verschlüsselungsfehler und Import-/Exportprobleme.

## Verzeichnisse

### Geschichte/
Komprimierter Terminalsitzungsverlauf.

**Format:** GZIP-komprimierte Textdateien, eine pro Terminalsitzung

**Benennung:** `{session-id}_{timestamp}.history.gz` (für den Sitzungsverlauf aus der Terminalprotokollierung)

**Zweck:** Speichert den Verlauf und die Ausgabe von Terminalbefehlen, wenn die Terminalprotokollierung für eine Verbindung aktiviert ist.

**Zugriff:** Der Terminalverlauf wird automatisch geladen, wenn Sie eine gespeicherte Verbindung öffnen, und in der Suchfunktion für den Terminalverlauf angezeigt.

!!! Notiz
    In diesem Verzeichnis wird der Verlauf nur gespeichert, wenn Sie beim Erstellen oder Bearbeiten einer Verbindung explizit *Terminalprotokollierung* für eine Verbindung auf der Registerkarte *Terminalprotokollierung* aktivieren.

### Plugins/
Vom Benutzer importierte Terminal-Effekt-Plugin-JARs.

**Zweck:** Externe Terminal-Effekt-Plugins, die Sie über *Plugins > Terminal-Effekte > Importieren* importieren.

**Sicherheit:** Importierte Plugins sind vertrauenswürdiger Java-Code und werden nicht in einer Sandbox gespeichert. Importieren Sie JARs nur aus Quellen, denen Sie vertrauen.

**Bereinigung:** Wenn Sie ein Plugin aus diesem Verzeichnis löschen, ist es in KorTTY nicht mehr verfügbar. Deaktivierte Plugins bleiben in diesem Verzeichnis, werden aber in `terminal-effect-plugins.disabled` aufgeführt.

!!! Warnung
    Plugin-Abhängigkeiten müssen in der Plugin-JAR schattiert werden. Benachbarte Abhängigkeits-JARs werden nicht automatisch geladen. Verpackungsrichtlinien finden Sie unter [Terminal Effect Plugins](../features/terminal-effect-plugins.md)].

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
- Liste der offenen Verbindungen/Registerkarten beim Speichern des Projekts
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

!!! Notiz
    Integrierte Sprachen (Englisch, Deutsch, Italienisch, Spanisch, Portugiesisch, Französisch, Kroatisch, Niederländisch) werden mit der Anwendung geliefert und verwenden dieses Verzeichnis nicht. Dieses Verzeichnis wird nur für dynamisch generierte Übersetzungen verwendet.

### ssh-keys/
Optionales Verzeichnis für kopierte SSH-Schlüssel.

**Zweck:** Speichert Kopien von SSH-Schlüsseln zur einfachen Sicherung und Migration.

**Anwendung:**
1. Öffnen Sie *Verwaltung > SSH-Schlüssel verwalten...*
2. Wählen Sie einen SSH-Schlüssel aus und klicken Sie auf *In Benutzerverzeichnis kopieren*
3. Der Schlüssel wird nach `~/.kortty/ssh-keys/` kopiert

**Backup:** SSH-Schlüssel in diesem Verzeichnis werden einbezogen, wenn Sie über *Bearbeiten > Backup erstellen* ein Backup erstellen.

**Vorteile:**
- Zentraler Speicherort für alle SSH-Schlüssel
- Einfache Migration auf neue Maschinen
- In verschlüsselten Backups enthalten

## Sicherheitszusammenfassung

| Artikel | Sicherheitsmethode |
|------|-----------------|
| Master-Passwort | PBKDF2-Hashing mit 310.000 Iterationen |
| Verbindungspasswörter | AES-256-GCM-Verschlüsselung |
| SSH-Schlüsselpassphrasen | AES-256-GCM-Verschlüsselung |
| Anmeldeinformationen (Benutzername/Passwort) | AES-256-GCM-Verschlüsselung |
| JobScheduler Sudo-Passwörter | AES-256-GCM-Verschlüsselung |
| JobScheduler-Journaleinträge | Geschwärzte Geheimnisse vor der Persistenz |
| Sicherungsdateien | Passwortgeschützte ZIP- oder GPG-Verschlüsselung |
| API-Schlüssel (KI-Profile) | AES-256-GCM-Verschlüsselung mit Master-Passwort |
| Terminaleffekt-Plugins | Nicht verschlüsselt; Vertrauenswürdiger lokaler Code, nicht in einer Sandbox |

## Dateispeicherorte nach Plattform

Alle Dateien werden plattformübergreifend im selben `~/.kortty/`-Verzeichnis gespeichert:

- **macOS:** `/Users/{username}/.kortty/`
- **Windows:** `C:\Users\{username}\.kortty\` (oder `%USERPROFILE%\.kortty\`)
- **Linux:** `/home/{username}/.kortty/` (oder `$HOME/.kortty/`)

## Sicherung und Wiederherstellung

Wenn Sie über *Bearbeiten > Backup erstellen* ein Backup erstellen, sind die folgenden Dateien und Verzeichnisse enthalten:

- Alle `.xml`-Konfigurationsdateien
- `master-password-hash`
- `history/`-Verzeichnis
- `projects/`-Verzeichnis
- `i18n/`-Verzeichnis (generierte Sprachdateien)
- `ssh-keys/`-Verzeichnis (falls vorhanden)
- `snippets.xml` und zugehörige Snippet-Daten

Das Backup wird verschlüsselt (passwortgeschütztes ZIP oder GPG) und an einem von Ihnen angegebenen Ort gespeichert.

!!! Tipp
    Backups sind die empfohlene Methode, um KorTTY auf einen neuen Computer zu migrieren oder nach einem Datenverlust wiederherzustellen. Erstellen Sie regelmäßig ein Backup und bewahren Sie es an einem sicheren Ort auf.

## Zugriff auf Konfigurationsdateien

Sie können die KorTTY-Konfiguration direkt bearbeiten, indem Sie:

1. KorTTY vollständig schließen
2. Öffnen Sie `~/.kortty/` in Ihrem Dateimanager oder Terminal
3. Bearbeiten der XML-Dateien mit einem Texteditor
4. Starten Sie KorTTY neu, um die Änderungen zu laden

!!! Warnung
    Das direkte Bearbeiten von XML-Dateien kann Ihre Daten beschädigen, wenn es nicht sorgfältig durchgeführt wird. Erstellen Sie vor der manuellen Bearbeitung immer ein Backup. Verwenden Sie für die meisten Konfigurationsaufgaben stattdessen die KorTTY-Benutzeroberfläche – sie verarbeitet Verschlüsselung, Validierung und Dateiformat korrekt.

## Fehlerbehebung

**Konfigurationsdatei nicht gefunden:**
- KorTTY erstellt beim ersten Start das Verzeichnis `~/.kortty/` und alle erforderlichen Unterverzeichnisse.
- Wenn eine Konfigurationsdatei fehlt, verwendet KorTTY sinnvolle Standardeinstellungen und erstellt die Datei beim nächsten Speichern.

**Verschlüsselungsfehler:**
- Wenn Sie Ihr Master-Passwort vergessen, müssen Sie `master-password-hash` löschen und ein neues Passwort festlegen. Auf zuvor verschlüsselte Daten kann nicht mehr zugegriffen werden.

**Beschädigte XML-Dateien:**
- Wenn eine `.xml`-Datei beschädigt ist, stellen Sie sie aus einer Sicherung wieder her oder löschen Sie die Datei. KorTTY wird es beim nächsten Speichern mit den Standardeinstellungen neu erstellen.

**Probleme beim Laden des Plugins:**
- Überprüfen Sie `kortty.log` auf Fehlermeldungen im Zusammenhang mit dem Laden des Plugins (z. B. doppelte IDs, fehlende Dienste, Fehler beim Laden von Klassen).
– Stellen Sie sicher, dass sich die Plugin-JAR in `~/.kortty/plugins/` befindet und `META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin` enthält.
- Verwenden Sie *Plugins > Terminaleffekte > Neu laden*, um die Plugin-Liste zu aktualisieren.

**Größe der Protokolldatei:**
- `kortty.log` wächst während der Anwendungssitzung. Es wird nicht automatisch gedreht. Sie können es sicher löschen, während KorTTY geschlossen ist.

**Wiederherstellung des Master-Passworts:**
- Es gibt keine Wiederherstellung für ein vergessenes Master-Passwort. Wenn Sie es verlieren, löschen Sie `master-password-hash` und `credentials.xml`, starten Sie KorTTY neu und legen Sie ein neues Passwort fest.
