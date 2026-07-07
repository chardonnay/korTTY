---
title: Protokollierung
---

# Protokollierung

Konfigurieren Sie die Protokollierung von Terminalsitzungen, einschließlich des Speicherorts und der Aufbewahrungsdauer der Protokolle. Öffnen über **Konfiguration → Globale Einstellungen → Protokollierung**; in `~/.kortty/global-settings.xml` gespeichert.

![Logging settings tab](../../assets/screenshots/settings/logging.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Protokollverzeichnis | Pfad | — | `~/.kortty/logs` | `logDirectoryPath` |
| Protokolle führen | Nummer | 0–3650 Tage | 7 Tage | `logRetentionDays` |

!!! Notiz
    **Protokollaufbewahrung**: Für unbegrenzte Aufbewahrung auf `0` einstellen; Andernfalls werden Protokollarchive, die älter als die angegebene Anzahl von Tagen sind, automatisch gelöscht. Archive, die älter als 24 Stunden sind, werden automatisch komprimiert.