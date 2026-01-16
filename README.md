# KorTTY - SSH Client

Ein moderner SSH-Client mit JavaFX-Oberfläche, Tab-Unterstützung und JMX-Monitoring.

## Features

- **GUI-basiert**: Moderne JavaFX-Oberfläche mit dunklem Theme
- **Tab-Unterstützung**: Mehrere SSH-Verbindungen in einem Fenster
- **Multi-Window**: Öffnen Sie mehrere Fenster für verschiedene Projekte
- **Verschlüsselte Passwörter**: AES-256-GCM Verschlüsselung mit Master-Passwort
- **SSH-Key-Verwaltung**: Zentrale Verwaltung privater SSH-Keys mit verschlüsselten Passphrases
- **Einstellbare Darstellung**: Schriftgröße, Farben (global oder pro Verbindung)
- **Projekt-Management**: Speichern und laden Sie Verbindungs-Sets mit Historie
- **Import/Export**: Importieren Sie Verbindungen von MTPuTTY und MobaXterm
- **JMX-Monitoring**: Überwachen Sie aktive Verbindungen, Speicherverbrauch, etc.
- **Dashboard**: Übersicht aller geöffneten Verbindungen im Projekt
- **SFTP Manager**: Dateiübertragung zwischen lokalem System und entfernten Servern
- **Fenstergeometrie-Speicherung**: Automatische Wiederherstellung von Fensterposition und -größe
- **Dashboard-Status-Speicherung**: Automatische Wiederherstellung des Dashboard-Zustands

## Voraussetzungen

- Java 25 oder höher
- Gradle 9.x (wird automatisch über den Wrapper heruntergeladen)

## Build

```bash
./gradlew build
```

## Ausführen

```bash
./gradlew run
```

## macOS Release erstellen

KorTTY kann mit `jpackage` als native macOS App (.app) oder als DMG Installer gebaut werden.

### Voraussetzungen

- **Java 25 oder höher** (mit jpackage Tool)
- **macOS** (für native Builds)
- **Xcode Command Line Tools** (für Code-Signing, optional)

### macOS App (.app) erstellen

Erstellt eine eigenständige macOS App, die eine gebündelte JVM enthält:

```bash
./gradlew jpackage
```

Die erstellte App befindet sich unter:
```
build/jpackage/korTTY.app
```

**Installation:**
- Die App kann per Drag & Drop in `/Applications` kopiert werden
- Oder direkt aus dem `build/jpackage/` Verzeichnis gestartet werden

### DMG Installer erstellen

Erstellt einen DMG Installer für einfache Verteilung:

```bash
./gradlew jpackageDmg
```

Die erstellte DMG-Datei befindet sich unter:
```
build/jpackage/korTTY-1.1.0.dmg
```

**Verteilung:**
- Die DMG-Datei kann an Benutzer verteilt werden
- Benutzer öffnen die DMG und ziehen die App in den Applications-Ordner

### Technische Details

- **Launcher-Klasse**: Die App verwendet `de.kortty.Launcher` als Entry-Point, um JavaFX Runtime-Checks zu umgehen
- **Gebündelte JVM**: Die App enthält eine vollständige JVM (ca. 150-200 MB)
- **Icon**: Das App-Icon wird automatisch aus `src/main/resources/icon/kortty_icon.icns` geladen
- **Dependencies**: Alle Dependencies (JavaFX, Apache SSHD, etc.) werden automatisch eingebunden

### Troubleshooting

**App öffnet nicht:**
- Prüfe die System-Logs: `log show --predicate 'process == "korTTY"' --last 5m`
- Teste die App direkt: `build/jpackage/korTTY.app/Contents/MacOS/korTTY`

**Gatekeeper-Warnung:**
- Nicht signierte Apps können eine Warnung beim ersten Start zeigen
- Rechtsklick → "Öffnen" umgeht die Warnung
- Für Verteilung: App mit Developer-ID signieren (erfordert Apple Developer Account)

## JMX-Monitoring

Der SSH-Client registriert ein JMX MBean unter `de.kortty:type=SSHClient`.

Verfügbare Attribute:
- `ActiveConnectionCount`: Anzahl aktiver Verbindungen
- `UsedMemoryBytes`: Verwendeter Speicher
- `BufferedTextSize`: Größe des gepufferten Terminal-Texts
- `ActiveConnectionNames`: Liste der aktiven Verbindungsnamen
- `UptimeSeconds`: Laufzeit der Anwendung

Um das JMX-Monitoring zu nutzen, starten Sie die Anwendung mit:
```bash
./gradlew run --args="-Dcom.sun.management.jmxremote"
```

Oder verbinden Sie sich mit JConsole:
```bash
jconsole
```

## Tastenkürzel

| Tastenkürzel | Aktion |
|--------------|--------|
| Ctrl+T | Neuer Tab |
| Ctrl+Shift+N | Neues Fenster |
| Ctrl+W | Tab schließen |
| Ctrl+Tab | Nächster Tab |
| Ctrl+Shift+Tab | Vorheriger Tab |
| Ctrl+O | Projekt öffnen |
| Ctrl+S | Projekt speichern |
| Ctrl+Shift+D | Dashboard ein/aus |
| Ctrl+Plus | Vergrößern |
| Ctrl+Minus | Verkleinern |
| Ctrl+0 | Zoom zurücksetzen |
| Ctrl+Q | Beenden |

## Konfigurationsdateien

Alle Konfigurationsdateien werden unter `~/.kortty/` gespeichert:

```
~/.kortty/
├── config.xml           # Globale Einstellungen
├── connections.xml      # Gespeicherte Verbindungen
├── credentials.xml      # Gespeicherte Zugangsdaten
├── ssh-keys.xml         # SSH-Key-Verwaltung
├── gpg-keys.xml         # GPG-Schlüssel für Backup-Verschlüsselung
├── global-settings.xml  # Globale Anwendungseinstellungen
├── master.key           # Master-Passwort-Hash
├── kortty.log           # Log-Datei
├── history/             # Terminal-Historie (komprimiert)
├── projects/            # Projektdateien (.kortty)
└── ssh-keys/            # Kopierte SSH-Keys (optional)
```

## Import von anderen Programmen

### MTPuTTY

Exportieren Sie Ihre Verbindungen aus MTPuTTY als `servers.xml` und importieren Sie diese über:
**Verbindungen → Importieren → MTPuTTY Server-Dateien (*.xml)**

### MobaXterm

Kopieren Sie die `MobaXterm.ini` Datei und importieren Sie diese über:
**Verbindungen → Importieren → MobaXterm Session-Dateien (*.ini)**

**Hinweis:** Passwörter werden aus Sicherheitsgründen nicht importiert. Sie müssen diese nach dem Import erneut eingeben.

## Zugangsdaten-Verwaltung

KorTTY bietet eine zentrale Verwaltung für Zugangsdaten (Benutzername/Passwort):

### Features

- **Umgebungsspezifisch**: Zugangsdaten können für Produktion, Entwicklung, Test oder Staging gespeichert werden
- **Server-Pattern**: Automatische Zuordnung zu Servern über Glob-Pattern (z.B. `*.example.com` oder `10.0.0.*`)
- **Verschlüsselte Speicherung**: Passwörter werden mit AES-256-GCM verschlüsselt
- **Automatische Verwendung**: Zugangsdaten können direkt in Verbindungseinstellungen ausgewählt werden

### Verwendung

1. **Zugangsdaten hinzufügen**:
   - **Verwaltung → Zugangsdaten verwalten...**
   - Klicken Sie auf "Hinzufügen"
   - Geben Sie Name, Benutzername, Umgebung und optional ein Server-Pattern ein
   - Geben Sie das Passwort ein (wird verschlüsselt gespeichert)

2. **Zugangsdaten in Verbindung verwenden**:
   - Beim Erstellen/Bearbeiten einer Verbindung
   - Wählen Sie die passenden Zugangsdaten aus der Dropdown-Liste
   - Benutzername und Passwort werden automatisch eingetragen

### Import/Export

Beim Import von Verbindungen können Sie wählen:
- Ob Zugangsdaten importiert werden sollen
- Ob importierte Zugangsdaten durch gespeicherte Zugangsdaten ersetzt werden sollen

## SSH-Key-Verwaltung

KorTTY bietet eine umfassende Verwaltung für private SSH-Keys:

### Features

- **Zentrale Verwaltung**: Alle SSH-Keys an einem Ort verwalten
- **Verschlüsselte Passphrases**: Key-Passphrases werden verschlüsselt gespeichert
- **Key-Kopierung**: Keys können ins KorTTY-Verzeichnis kopiert werden für einfachen Umzug
- **Glob-Suche**: Schnelle Suche nach Keys mit Wildcard-Pattern (`*`)
- **Automatische Verwendung**: Keys können direkt in Verbindungseinstellungen ausgewählt werden

### Verwendung

1. **SSH-Key hinzufügen**: 
   - **Verwaltung → SSH-Keys verwalten...**
   - Klicken Sie auf "Hinzufügen"
   - Wählen Sie den Pfad zu Ihrem privaten SSH-Key
   - Optional: Geben Sie die Passphrase ein (wird verschlüsselt gespeichert)

2. **Key in Verbindung verwenden**:
   - Beim Erstellen/Bearbeiten einer Verbindung
   - Wählen Sie "Privater Schlüssel" als Authentifizierungsmethode
   - Wählen Sie den gewünschten Key aus der Dropdown-Liste
   - Der Key-Pfad und die Passphrase werden automatisch eingetragen

3. **Key ins User-Verzeichnis kopieren**:
   - In der SSH-Key-Verwaltung einen Key auswählen
   - Klicken Sie auf "Ins User-Verzeichnis kopieren"
   - Der Key wird nach `~/.kortty/ssh-keys/` kopiert und bei Backups mitgespeichert

### Import/Export

Beim Import von Verbindungen können Sie wählen:
- Ob SSH-Keys importiert werden sollen
- Ob importierte Keys durch gespeicherte Keys aus der Verwaltung ersetzt werden sollen

## SFTP Manager

Der integrierte SFTP Manager ermöglicht direkte Dateiübertragungen:

### Features

- **Zwei-Panel-Ansicht**: Lokale und entfernte Dateien nebeneinander
- **Drag & Drop**: Einfaches Verschieben von Dateien
- **Dateioperationen**: Löschen, Umbenennen, Kopieren von Dateien und Verzeichnissen
- **Berechtigungen**: Anpassung von Dateiberechtigungen (chmod) mit Checkbox-Interface
- **ZIP-Archivierung**: Erstellen von ZIP-Archiven aus mehreren Dateien/Verzeichnissen
- **Suche**: Glob-Pattern-Suche (`*`) in beiden Panels
- **Sortierung**: Sortierbare Tabellenspalten (Name, Größe, Datum)

### Zugriff

- **Dashboard**: Rechtsklick auf einen Server → "SFTP Manager öffnen"
- **Menü**: **Tools → SFTP Manager öffnen...**

## Fenster- und Dashboard-Verwaltung

KorTTY merkt sich automatisch:

- **Fenstergeometrie**: Position, Größe und Maximized-Status des Hauptfensters
- **Dashboard-Status**: Ob das Dashboard beim Schließen geöffnet war

Diese Funktionen können in den Einstellungen (**Einstellungen → Fenster**) deaktiviert werden.

## Sicherheit

- Master-Passwort wird mit PBKDF2 (310.000 Iterationen) gehasht
- Verbindungspasswörter werden mit AES-256-GCM verschlüsselt
- Private SSH-Key-Passphrases werden ebenfalls verschlüsselt gespeichert
- Passwörter werden niemals im Klartext gespeichert
- SSH-Keys können optional ins User-Verzeichnis kopiert werden (bei Backups inkludiert)

## Lizenz

MIT License
