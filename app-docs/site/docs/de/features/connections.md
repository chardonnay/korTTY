# Verbindungen

korTTY verwaltet SSH-, Mosh- und **Local-Shell**-Verbindungen über drei Einstiegspunkte: **Quick Connect**, den **Verbindungsmanager** und gespeicherte **Projekte**.

![Connection flow](../assets/diagrams/connection-flow.svg)

## Schnellverbindung

Öffnen Sie mit ++Strg+K++ (oder **Verbindungen → Schnellverbindung…**). Geben Sie Host, Port, Benutzernamen und Authentifizierung ein und stellen Sie eine Verbindung her, ohne zu speichern. Häufig verwendete Verbindungen werden als Schnellschaltflächen angezeigt. Eine Live-Suche filtert sie.

## Verbindungsmanager

**Verbindungen → Verbindungen verwalten…** öffnet einen durchsuchbaren Baum gespeicherter Verbindungen (optional gruppiert). Von hier aus erstellen, bearbeiten, duplizieren, löschen, importieren und exportieren Sie Verbindungen.

## Eine Verbindung erstellen/bearbeiten

Der Verbindungseditor verfügt über folgende Registerkarten:

| Tab | Inhalt |
| --- | --- |
| Verbindung | Host, Port, Benutzername, Protokoll (SSH / Mosh / Local Shell), Authentifizierung (Passwort / Schlüssel / Tastatur-interaktiv). Für **Local Shell**-Verbindungen sind Host, Port, Benutzername und Authentifizierung nicht erforderlich und deaktiviert. |
| Terminaleinstellungen | Farben pro Verbindung, Schriftart, ANSI/TrueColor-Behandlung, Terminaleffekt |
| SSH-Tunnel | Lokale / Remote- / dynamische Portweiterleitung |
| Jump-Server | Bastion-Host-Verkettung |
| Terminalprotokollierung | Protokollformat und Ziel pro Verbindung |
| Fenstergeometrie | Gespeicherte Größe/Position für diese Verbindung |

## Protokolle

=== „SSH“
    Standard-SSH über Apache MINA SSHD. Unterstützt Passwort-, Public-Key- und Tastatur-interaktive Authentifizierung, Keep-Alive und anklickbare OSC 8-Hyperlinks.

=== „Mosh“
    Roaming, latenzfreundlicher Mosh-Transport (mosh4j). Das Mosh-Backend ist in nativen Builds gebündelt; Bestehende Verbindungen benötigen keine Migration.

=== „Lokale Shell“
    Öffnet die Shell des **lokalen Rechners** in einer Terminal-Registerkarte (kein Netzwerk) über ein pty4j-gestütztes Pseudo-Terminal. Host, Port, Benutzername und Authentifizierung sind nicht erforderlich. Siehe [Lokale Shell](#local-shell) unten].

## Lokale Shell

Eine **Lokale Shell**-Verbindung erzeugt ein lokales Pseudo-Terminal (PTY) auf Ihrem eigenen Computer, anstatt eine Verbindung zu einem Remote-Host herzustellen. Es ist sowohl im **Quick Connect** als auch im **Connection Manager** auswählbar; Für diese Verbindungen sind Host, Port, Benutzername und Authentifizierung nicht erforderlich (und in den Dialogen deaktiviert), und es wird keine Passwortabfrage angezeigt.

### Eine Muschel auswählen

| Plattform | Optionen |
| --- | --- |
| Windows | **PowerShell** (Standard) oder **cmd.exe**. **Git Bash**, **Cygwin** und **WSL** werden ebenfalls als Voreinstellungen angeboten – allerdings nur, wenn sie tatsächlich installiert sind (Git Bash/Cygwin werden über ihre üblichen Installationsorte / `PATH` erkannt; WSL erscheint nur, wenn `wsl.exe` vorhanden und mindestens eine Distribution installiert ist). |
| macOS / Linux | Der Standardwert ist Ihr `$SHELL` (Rückfall auf `/bin/zsh` oder `/bin/bash`). |

Ein Freiformfeld **Benutzerdefinierter Befehl** akzeptiert jede ausführbare Datei mit Argumenten (z. B. `pwsh.exe`, `wsl.exe -d Ubuntu`, ein Git-Bash-Pfad) und ein optionales **Startverzeichnis** kann festgelegt werden. Der Befehlsparser erkennt Anführungszeichen, sodass Shell-Pfade, die Leerzeichen enthalten – wie `"C:\Program Files\Git\bin\bash.exe"` – korrekt gestartet werden.

### Terminalfunktionen in lokalen Shells

Terminalprotokollierung und -aufzeichnung sowie die AI-Eingabe-/Daten-Hooks funktionieren für lokale Shells über eine gemeinsam genutzte `ObservableTtyConnector`-Schnittstelle. Funktionen, die von einem SSH-Kanal abhängen, bleiben nur SSH.

!!! Hinweis „KI-Agent in lokalen Shells“
    Der **AI Agent** und **AI Planning** laufen auch in lokalen Shells unter Windows, macOS und Linux – siehe [AI Assistant](ai-assistant.md#ai-agent-and-ai-planning).

## Tunnel und Sprungserver

- **SSH-Tunnel** – Ports über die Verbindung weiterleiten: **lokal** (`-L`), **remote** (`-R`) oder **dynamisch / SOCKS** (`-D`).
- **Jump-Server (Bastion)** – leitet die Verbindung über einen oder mehrere Zwischenhosts weiter.

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

## Von anderen Clients importieren

**Verbindungen → Importieren…** liest Verbindungsdateien von **MTPuTTY**, **MobaXterm** und **PuTTY Connection Manager**, mit Gruppenfilterung und Anmeldeinformationsverarbeitung.

!!! Hinweis „Weitere folgen“
    Diese Seite ist Teil des Scaffolded-Anleitungs. Als nächstes wird die vollständige Funktionsbibliothek – SFTP, Snippets, JobScheduler, KI-Assistent und -Tools, Terminalaufzeichnung, Sicherheit und die vollständigen Einstellungstabellen – ausgefüllt.
