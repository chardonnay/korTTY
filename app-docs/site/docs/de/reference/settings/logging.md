---
title: Protokollierung
---

# Protokollierung

Konfigurieren Sie das eigene Anwendungslog von korTTY: wo es gespeichert wird und wie lange alte Logdateien aufbewahrt werden. Öffnen Sie über **Konfiguration → Globale Einstellungen → Logs**; gespeichert in `~/.kortty/global-settings.xml`.

!!! note "Anwendungsprotokoll, nicht Terminalausgabe"
    Dieser Tab behandelt `kortty.log`, die Datei, die korTTY über sich selbst schreibt (Start, Verbindungen, Fehler) zur Unterstützung und Fehlersuche. Er zeichnet nicht auf, was Ihre Terminal-Sitzungen ausgeben. Die Terminalausgabe wird pro Verbindung protokolliert, in **Connection-Manager → Terminal Logging** oder mit der Option **Terminalprotokoll** von Schnellverbindung; siehe [Terminalprotokollierung](../../features/terminal.md#terminal-logging). Der Tab gibt dies oben an und verlinkt mit **In der Anleitung öffnen** zu diesem Abschnitt.

![Logging settings tab](../../assets/screenshots/settings/logging.png)

## Anwendungsprotokoll { #application-log }

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Log-Verzeichnis | Pfad | — | `~/.kortty/logs` | `logDirectoryPath` |
| Logs aufbewahren | Anzahl | 0–3650 Tage | 7 Tage | `logRetentionDays` |

!!! note
    **Protokollaufbewahrung**: Für unbegrenzte Aufbewahrung auf `0` einstellen; Andernfalls werden Protokollarchive, die älter als die angegebene Anzahl von Tagen sind, automatisch gelöscht. Archive, die älter als 24 Stunden sind, werden automatisch komprimiert.

## Sitzungsjournal

Der gleiche Tab enthält auch die globalen Einstellungen für das [Sitzungsjournal](../../features/session-journal.md#ki-zusammenfassungen): den Speicherordner für das Journal, ob KI-Zusammenfassungen standardmäßig generiert werden, das Standardintervall für Zusammenfassungen und das verwendete KI-Profil für Zusammenfassungen. Weitere Informationen finden Sie auf der [Sitzungsjournal](../../features/session-journal.md)-Seite.