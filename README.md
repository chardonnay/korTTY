# KorTTY - SSH Client

Ein moderner SSH-Client mit JavaFX-Oberfläche, Tab-Unterstützung und JMX-Monitoring.

## Features

- **GUI-basiert**: Moderne JavaFX-Oberfläche mit dunklem Theme
- **Tab-Unterstützung**: Mehrere SSH-Verbindungen in einem Fenster
- **Multi-Window**: Öffnen Sie mehrere Fenster für verschiedene Projekte
- **Verschlüsselte Passwörter**: AES-256-GCM Verschlüsselung mit Master-Passwort
- **Einstellbare Darstellung**: Schriftgröße, Farben (global oder pro Verbindung)
- **Projekt-Management**: Speichern und laden Sie Verbindungs-Sets mit Historie
- **Import/Export**: Importieren Sie Verbindungen von MTPuTTY und MobaXterm
- **JMX-Monitoring**: Überwachen Sie aktive Verbindungen, Speicherverbrauch, etc.
- **Dashboard**: Übersicht aller geöffneten Verbindungen im Projekt

## Voraussetzungen

- Java 21 oder höher
- Gradle 8.x (wird automatisch über den Wrapper heruntergeladen)

## Build

```bash
./gradlew build
```

## Ausführen

```bash
./gradlew run
```

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
├── master.key           # Master-Passwort-Hash
├── kortty.log           # Log-Datei
├── history/             # Terminal-Historie (komprimiert)
└── projects/            # Projektdateien (.kortty)
```

## Import von anderen Programmen

### MTPuTTY

Exportieren Sie Ihre Verbindungen aus MTPuTTY als `servers.xml` und importieren Sie diese über:
**Verbindungen → Importieren → MTPuTTY Server-Dateien (*.xml)**

### MobaXterm

Kopieren Sie die `MobaXterm.ini` Datei und importieren Sie diese über:
**Verbindungen → Importieren → MobaXterm Session-Dateien (*.ini)**

**Hinweis:** Passwörter werden aus Sicherheitsgründen nicht importiert. Sie müssen diese nach dem Import erneut eingeben.

## Sicherheit

- Master-Passwort wird mit PBKDF2 (310.000 Iterationen) gehasht
- Verbindungspasswörter werden mit AES-256-GCM verschlüsselt
- Private Schlüssel-Passphrases werden ebenfalls verschlüsselt gespeichert
- Passwörter werden niemals im Klartext gespeichert

## Lizenz

MIT License
