---
title: Video
---

# Video

Konfigurieren Sie Terminal-Aufzeichnungs- und Wiedergabeoptionen. Diese Einstellungen steuern, wie korTTY Terminalsitzungen erfasst und wiedergibt, einschließlich der Farbkonservierung und der automatischen Aufnahmeverfügbarkeit. Öffnen über **Konfiguration → Globale Einstellungen → Video**; in `~/.kortty/global-settings.xml` gespeichert.

![Video settings tab](../../assets/screenshots/settings/video.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Enable terminal recording after app restart | toggle | — | Off | `terminalRecordingEnabled` |
| Capture terminal colors in recordings | toggle | — | Off | `terminalRecordingCaptureColorsEnabled` |

!!! note
    Wenn Sie **Terminalfarben in Aufzeichnungen erfassen** aktivieren, werden Terminalfarbinformationen pro Zelle in neuen Wiedergabedateien gespeichert. Dadurch wird die Größe der Wiedergabedatei erhöht, aber das ursprüngliche Erscheinungsbild des Terminals, einschließlich ANSI-Farben und Hintergrundfarben, bleibt für eine genaue Wiedergabe erhalten.
