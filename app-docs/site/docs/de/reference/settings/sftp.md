---
title: SFTP-Manager
---

# SFTP-Manager

Standardwerte für den Dual-Panel-Dateimanager [SFTP ](../../features/sftp.md) und für die Rsync-Jobs des JobScheduler. Öffnen über **Konfiguration → Globale Einstellungen → SFTP-Manager**; in `~/.kortty/global-settings.xml` gespeichert.

![SFTP Manager settings tab](../../assets/screenshots/settings/sftp-manager.png)

## SFTP-Manager-Einstellungen

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| SFTP-Tabs nach Inaktivität automatisch schließen | Schalter | — | Aus | `sftpAutoCloseMinutes` (nicht festgelegt oder `0`) |
| Timeout (Minuten) | Nummer | 1–120 | 10 | `sftpAutoCloseMinutes` |

!!! note "Auto-Close"
    Ein inaktiver SFTP-Tab schließt sich nach dem Timeout selbst, wodurch die serverseitige Verbindung freigegeben wird, wenn Sie vergessen, den Manager zu schließen. Das Timeout-Feld kann nur bearbeitet werden, während der Schalter aktiviert ist, und beide haben einen gemeinsamen gespeicherten Wert: Wenn Sie den Schalter ausschalten, wird überhaupt kein Timeout gespeichert. Solange Uploads oder Downloads laufen, gilt der Tab als aktiv, sodass er sich nie mitten in einer Übertragung selbst schließt.

## Übertragungen

Wie Uploads und Downloads in der [Übertragungsliste](../../features/sftp.md#ubertragungsliste) funktionieren. Änderungen gelten für danach geöffnete SFTP-Tabs.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Parallele Übertragungen | Nummer | 1–8 | 3 | `sftpParallelTransfers` |
| Wenn das Ziel bereits existiert | Auswahl | Fragen (mit "für alle übernehmen"), Datei überspringen, Überschreiben | Fragen | `sftpConflictDefault` (`ask`, `skip`, `overwrite`) |
| Unterbrochene Übertragungen fortsetzen | Schalter | — | Ein | `sftpResumePartialTransfers` |
| Teildatei behalten, wenn eine Übertragung abgebrochen wird | Schalter | — | Aus | `sftpKeepPartialOnCancel` |

!!! note "Parallele Übertragungen"
    Jede parallele Übertragung nutzt einen eigenen SFTP-Kanal neben dem, mit dem der Tab den Server durchsucht. Drei Kanäle bringen bei langsamen Verbindungen den größten Teil der Geschwindigkeit und bleiben deutlich unter dem üblichen Serverlimit von zehn Sitzungen pro Verbindung; verweigert der Server einen Kanal, kopiert der Tab stillschweigend mit weniger Kanälen. Ein Tab, der die Sitzung eines Terminal-Bereichs mitbenutzt, verwendet höchstens 2, unabhängig von der Einstellung.

!!! note "Wenn das Ziel bereits existiert"
    **Fragen** zeigt den Dialog [Datei existiert bereits](../../features/sftp.md#wenn-eine-datei-bereits-existiert) einmal pro Upload oder Download an, mit **Für alle weiteren Konflikte dieser Art übernehmen**. **Datei überspringen** und **Überschreiben** beantworten ihn ohne Nachfrage; Ordner werden in beiden Fällen zusammengeführt. **Überschreiben** ersetzt nie einen symbolischen Link oder eine Datei, wo ein Ordner erwartet wird (und umgekehrt): Hier wird weiterhin nachgefragt.

!!! note "Teildateien"
    Jede Übertragung schreibt zuerst in eine Datei `.kortty-part` neben ihrem Ziel. Ist **Unterbrochene Übertragungen fortsetzen** eingeschaltet, behält eine Übertragung, die fehlgeschlagen ist oder ihre Verbindung verloren hat, diese Datei, und **Wiederholen** setzt dort fort, wo sie aufgehört hat, nachdem geprüft wurde, dass sich die Quelle nicht geändert hat. Ist die Option ausgeschaltet, wird die Teildatei entfernt, sobald eine Übertragung nicht abgeschlossen wird, und ein erneuter Versuch beginnt von vorn. **Teildatei behalten, wenn eine Übertragung abgebrochen wird** behält sie auch bei **Abbrechen**, sodass auch eine abgebrochene Übertragung fortgesetzt werden kann; die Option lässt sich nur einschalten, solange das Fortsetzen eingeschaltet ist.

!!! note "Von Ihrer Organisation verwaltet"
    Eine Organisation kann mit [`[rule.sftp]`](../enterprise-policy.md#rulesftp) **Parallele Übertragungen** begrenzen sowie **Wenn das Ziel bereits existiert** festlegen und sperren und mit `file-transfer = "deny"` die Dateiübertragung ganz abschalten; die Seite weist dann unterhalb der Übertragungseinstellungen darauf hin.

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
