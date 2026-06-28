# Verbindungen

korTTY verwaltet SSH-, Mosh- und **lokale Shell**-Verbindungen über drei
Einstiegspunkte: **Quick Connect**, der **Verbindungsmanager** und gespeicherte
**Projekte**.

![Connection flow](../assets/diagrams/connection-flow.svg)

## Schnellverbindung

Öffnen Sie mit ++Strg+K++ (oder **Verbindungen → Schnellverbindung…**). Geben Sie Host, Port,
Benutzername und Authentifizierung eingeben und ohne Speichern eine Verbindung herstellen. Häufig verwendet
Verbindungen werden als Schnellschaltflächen angezeigt. Eine Live-Suche filtert sie.

## Verbindungsmanager

**Verbindungen → Verbindungen verwalten…** öffnet einen durchsuchbaren Baum gespeicherter Daten
Verbindungen (optional gruppiert). Von hier aus erstellen, bearbeiten, duplizieren, löschen,
Import- und Exportverbindungen.

## Eine Verbindung erstellen/bearbeiten

Der Verbindungseditor verfügt über folgende Registerkarten:

| Tab | Inhalt |
| --- | --- |
| Verbindung | Host, Port, Benutzername, Protokoll (SSH / Mosh / lokale Shell), Authentifizierung (Passwort/Schlüssel/Tastatur-interaktiv). Für **lokale Shell**-Verbindungen werden Host, Port, Benutzername und Authentifizierung nicht benötigt und sind deaktiviert. |
| Terminaleinstellungen | Farben pro Verbindung, Schriftart, ANSI/TrueColor-Behandlung, Terminaleffekt |
| SSH-Tunnel | Lokale / Remote- / dynamische Portweiterleitung |
| Jump-Server | Bastion-Host-Verkettung |
| Terminalprotokollierung | Protokollformat und Ziel pro Verbindung |
| Fenstergeometrie | Gespeicherte Größe/Position für diese Verbindung |

## Protokolle

=== „SSH“
Standard-SSH über Apache MINA SSHD. Unterstützt Passwort, öffentlichen Schlüssel und
Tastaturinteraktive Authentifizierung, Keep-Alive und OSC 8 anklickbar
Hyperlinks.

=== „Mosh“
Roaming, latenzfreundlicher Mosh-Transport (mosh4j). Das Mosh-Backend ist
gebündelt in nativen Builds; Bestehende Verbindungen benötigen keine Migration.

=== „Lokale Shell“
Öffnet die Shell des **lokalen Rechners** in einer Terminal-Registerkarte (ohne
Netzwerk) über ein pty4j-gestütztes Pseudo-Terminal. Host, Port, Benutzername und
Authentifizierung werden nicht benötigt. Siehe [Lokale Shell](#lokale-shell) unten.

## Lokale Shell

Eine **lokale Shell**-Verbindung startet ein lokales Pseudo-Terminal (PTY) auf
Ihrem eigenen Rechner, statt sich mit einem Remote-Host zu verbinden. Sie ist
sowohl in **Quick Connect** als auch im **Verbindungsmanager** auswählbar; für
diese Verbindungen werden Host, Port, Benutzername und Authentifizierung nicht
benötigt (und sind in den Dialogen deaktiviert), und es wird keine
Passwortabfrage angezeigt.

### Shell auswählen

| Plattform | Optionen |
| --- | --- |
| Windows | **PowerShell** (Standard) oder **cmd.exe**. **Git Bash**, **Cygwin** und **WSL** werden ebenfalls als Voreinstellungen angeboten – aber nur, wenn sie tatsächlich installiert sind (Git Bash/Cygwin werden über ihre üblichen Installationspfade / `PATH` erkannt; WSL erscheint nur, wenn `wsl.exe` vorhanden und mindestens eine Distribution installiert ist). |
| macOS / Linux | Standardmäßig Ihre `$SHELL` (Fallback auf `/bin/zsh` oder `/bin/bash`). |

Ein freies Feld **Benutzerdefinierter Befehl** akzeptiert jede ausführbare Datei
mit Argumenten (z. B. `pwsh.exe`, `wsl.exe -d Ubuntu`, einen Git-Bash-Pfad), und
ein optionales **Startverzeichnis** kann gesetzt werden. Der Befehls-Parser
beachtet Anführungszeichen, sodass Shell-Pfade mit Leerzeichen – wie
`"C:\Program Files\Git\bin\bash.exe"` – korrekt gestartet werden.

### Terminal-Funktionen in lokalen Shells

Terminalprotokollierung und -aufzeichnung sowie die KI-Eingabe-/Daten-Hooks
funktionieren über eine gemeinsame `ObservableTtyConnector`-Schnittstelle auch
für lokale Shells. Funktionen, die einen SSH-Kanal voraussetzen, bleiben
SSH-exklusiv.

!!! note „KI-Agent in lokalen Shells“
    Der **KI-Agent** und die **KI-Planung** laufen ebenfalls in lokalen Shells
    unter Windows, macOS und Linux – siehe
    [KI-Assistent](ai-assistant.md#ki-agent-und-ki-planung).

## Tunnel und Sprungserver

- **SSH-Tunnel** – Ports über die Verbindung weiterleiten: **lokal** (`-L`),
**remote** (`-R`) oder **dynamisch / SOCKS** (`-D`).
- **Jump-Server (Bastion)** – Leiten Sie die Verbindung über einen oder mehrere weiter
Zwischenwirte.

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

## Von anderen Clients importieren

**Verbindungen → Importieren…** liest Verbindungsdateien von **MTPuTTY**, **MobaXterm**
und **PuTTY Connection Manager** mit Gruppenfilterung und Anmeldeinformationsverarbeitung.

!!! Hinweis „Weitere folgen“
Diese Seite ist Teil des Scaffolded-Anleitungs. Die vollständige Funktionsbibliothek – SFTP,
Snippets, JobScheduler, KI-Assistent und Tools, Terminalaufzeichnung, Sicherheit,
und die kompletten Einstellungstabellen – wird als nächstes ausgefüllt.
