---
title: SFTP-Manager
---

# SFTP-Manager

Standardwerte für den Dual-Panel-Dateimanager [SFTP ](../../features/sftp.md) und für die Rsync-Jobs des JobScheduler. Öffnen über **Konfiguration → Globale Einstellungen → SFTP-Manager**; in `~/.kortty/global-settings.xml` gespeichert.

![SFTP Manager settings tab](../../assets/screenshots/settings/sftp-manager.png)

## SFTP-Manager-Einstellungen

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| SFTP-Tabs nach Inaktivität automatisch schließen | umschalten | – | Aus | `sftpAutoCloseMinutes` (nicht aktiviert oder `0`) |
| Timeout (Minuten) | Nummer | 1–120 | 10 | `sftpAutoCloseMinutes` |

!!! note "Auto-Close"
    Ein inaktiver SFTP-Tab schließt sich nach dem Timeout selbst, wodurch die serverseitige Verbindung freigegeben wird, wenn Sie vergessen, den Manager zu schließen. Das Timeout-Feld kann nur bearbeitet werden, während der Schalter aktiviert ist, und beide haben einen gemeinsamen gespeicherten Wert: Wenn Sie den Schalter ausschalten, wird überhaupt kein Timeout gespeichert.

## ZIP-Erstellungseinstellungen

Dies sind die Standardeinstellungen für die Erstellung eines ZIP-Archivs **auf dem Remote-Server** über den SFTP-Manager.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Standard-ZIP-Pfad | Text | Entfernter absoluter Pfad | `/tmp` | `sftpDefaultZipPath` |
| Standardkomprimierung (0–9) | Nummer | 0–9 | 6 | `sftpDefaultZipCompression` |

!!! note "Kompressionsstufen"
    `0` bedeutet keine Komprimierung (am schnellsten) und `9` die beste Komprimierung (am langsamsten).

## JobScheduler Rsync

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Rsync-Binärpfad | Pfad | – | leer (`rsync` aus `PATH` auflösen) | `jobSchedulerRsyncBinaryPath` |

!!! note "Anforderungen"
    Lassen Sie den Pfad leer, um `rsync` aus `PATH` zu verwenden. **Durchsuchen** wählt explizit eine Binärdatei aus. Für Rsync-Jobs muss außerdem `ssh` auf `PATH` verfügbar sein. siehe [JobScheduler](../../features/jobscheduler.md).
