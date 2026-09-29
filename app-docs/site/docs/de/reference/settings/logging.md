---
title: Protokollierung
---

# Protokollierung

Konfigurieren Sie das Protokollieren von Terminal-Sitzungen, einschließlich des Speicherorts der Logs und der Dauer der Aufbewahrung. Öffnen Sie sie über **Konfiguration → Globale Einstellungen → Logs**; gespeichert im `~/.kortty/global-settings.xml`.

![Logging settings tab](../../assets/screenshots/settings/logging.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Log-Verzeichnis | Pfad | — | `~/.kortty/logs` | `logDirectoryPath` |
| Logs aufbewahren | Anzahl | 0–3650 Tage | 7 Tage | `logRetentionDays` |

!!! note
    **Protokollaufbewahrung**: Für unbegrenzte Aufbewahrung auf `0` einstellen; Andernfalls werden Protokollarchive, die älter als die angegebene Anzahl von Tagen sind, automatisch gelöscht. Archive, die älter als 24 Stunden sind, werden automatisch komprimiert.

## Sitzungsjournal

Der gleiche Tab enthält auch die globalen Einstellungen für das [Sitzungsjournal](../../features/session-journal.md#ki-zusammenfassungen): den Speicherordner für das Journal, ob KI-Zusammenfassungen standardmäßig generiert werden, das Standardintervall für Zusammenfassungen und das verwendete KI-Profil für Zusammenfassungen. Weitere Informationen finden Sie auf der [Sitzungsjournal](../../features/session-journal.md)-Seite.