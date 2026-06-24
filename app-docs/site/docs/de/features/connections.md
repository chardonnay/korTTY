# Verbindungen

korTTY verwaltet SSH- und Mosh-Verbindungen über drei Einstiegspunkte: **Quick
Connect**, der **Verbindungsmanager** und gespeicherte **Projekte**.

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
| Verbindung | Host, Port, Benutzername, Protokoll (SSH/Mosh), Authentifizierung (Passwort/Taste/Tastatur-interaktiv) |
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
