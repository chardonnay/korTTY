---
title: Sicherung
---

# Backup

Konfigurieren Sie die Sicherungsaufbewahrung, die Verschlüsselungsmethode und die automatische Sicherung. Öffnen Sie über **Konfiguration → Globale Einstellungen → Sicherung**; gespeichert in `~/.kortty/global-settings.xml`.

![Backup settings tab](../../assets/screenshots/settings/backup.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Maximale Anzahl an Backups | Anzahl | 0–100 (0 = unbegrenzt) | 10 | `maxBackupCount` |
| ZIP mit Passwort | radio (one of two) | — | On | `backupEncryptionType` (`PASSWORD`) |
| Anmeldeinformationen | Dropdown-Liste | Verfügbare gespeicherte Anmeldeinformationen | – | `backupCredentialId` |
| GPG-Verschlüsselung | radio (one of two) | — | Aus | `backupEncryptionType` (`GPG`) |
| GPG-Schlüssel | Dropdown-Liste | Verfügbare GPG-Schlüssel | – | `backupGpgKeyId` |
| Rhythmus | dropdown | Aus, Täglich, Wöchentlich, Monatlich | Wöchentlich | `autoBackupInterval` |
| Ordner | path | eines bestehenden Ordners | — | `autoBackupDirectory` |

!!! warning
    Backups werden IMMER verschlüsselt. Wählen Sie einen Modus, ZIP mit Passwort oder GPG. Der gewählte Modus benötigt seine Anmeldeinformationen oder seinen GPG-Schlüssel; ohne einen davon stoppt das Backup mit einem Fehler, bevor etwas geschrieben wird.

!!! note
    **Maximale Backups:** Setzen Sie `0`, um Sicherungen unbegrenzt zu behalten; jeder andere Wert (1–100) löscht automatisch die ältesten Sicherungen in `old-backups/`, sobald das Limit erreicht ist, wobei Passwort-ZIP- und GPG-Sicherungen zusammen gezählt werden. Bei passwortbasierter Verschlüsselung wählen Sie ein Credential aus dem Verwaltungssystem. Bei GPG-Verschlüsselung wählen Sie einen verfügbaren GPG-Schlüssel aus. Der Verschlüsselungstyp wird in den globalen Einstellungen gespeichert und bestimmt, welches Credential oder welche Schlüssel-ID für zukünftige Sicherungen verwendet wird.

Sobald ein Backup erstellt wurde, zeigt der Tab **Letztes Backup:** mit Datum, Uhrzeit und dem Pfad, in den es geschrieben wurde.

## Automatische Sicherung { #automatic-backup }

korTTY kann selbstständig ein Backup erstellen, in einen Ordner Ihrer Wahl, so oft Sie es wünschen. Das automatische Backup verwendet denselben Inhalt und dieselbe Verschlüsselung wie **Konfiguration → Erstellen Backup**: es ist immer mit dem oben ausgewählten Modus verschlüsselt und rotiert ältere Kopien in `old-backups/` unter der gleichen **Maximale Anzahl von Backups** Regel. Der Abschnittsheader verlinkt zu dieser Seite mit **In der Anleitung öffnen**.

| Einstellung | Auswirkung |
| --- | --- |
| **Rhythmus** | **Wöchentlich** ist die Vorgabe; **Täglich** und **Monatlich** sind die Alternativen, **Aus** deaktiviert das automatische Backup. Ein Monat ist ein Kalendermonat, daher ist ein am 31. geschriebenes Backup im nächsten Monat zum Ende des folgenden Monats fällig. |
| **Ordner** | Wo die Sicherung geschrieben wird, zum Beispiel ein Ordner auf einem Netzlaufwerk oder in einem synchronisierten Cloud-Ordner. Geben Sie einen Pfad ein oder verwenden Sie **Durchsuchen…**. Der Ordner muss bereits existieren: korTTY erstellt ihn nicht, weil ein fehlender Pfad jetzt eventuell ein Laufwerk sein könnte, das nicht gemountet ist, und ein Ordner, der an seiner Stelle erstellt wird, würde stillschweigend ab dann jede Sicherung erhalten. |

Nichts wird ausgeführt, bis ein Ordner festgelegt ist, sodass ein Update auf eine Version mit dieser Funktion nicht von selbst Dateien irgendwo schreibt. korTTY prüft, ob ein Backup zwei Minuten nach dem Start fällig ist und danach einmal pro Stunde; die Prüfung vergleicht die gespeicherte Zeit des letzten automatischen Backups (`lastAutoBackupAt`) mit dem Zeitplan, sodass ein Backup, das verpasst wurde, weil der Computer im Schlafmodus war oder korTTY geschlossen wurde, beim nächsten Check geschrieben wird anstatt ein weiteres Intervall zu warten. Ein manuelles Backup setzt den Zeitplan nicht zurück.

Die Statuszeile unter den beiden Feldern zeigt an, wann die letzte automatische Sicherung ausgeführt wurde und wann die nächste fällig ist, oder warum keine Sicherung gerade durchgeführt werden kann.

- **Noch kein Ordner ausgewählt**, oder **der Ordner existiert momentan nicht**: wählen Sie einen Ordner aus oder mounten Sie ihn; korTTY versucht jede Stunde erneut.
- **Keine Anmeldeinformationen ausgewählt** / **Kein GPG-Schlüssel ausgewählt**: Der oben angegebene Verschlüsselungsmodus hat nichts, womit er verschlüsseln kann; wählen Sie einen. Ein Backup wird niemals unverschlüsselt geschrieben.
- **Warten auf das Entsperren des Tresors**: *ZIP mit Passwort* entschlüsselt das Backup-Passwort mit Ihrem Master-Passwort, sodass dieser Modus nur läuft, solange der Tresor entsperrt ist; *GPG-Verschlüsselung* läuft auch bei einem gesperrten Tresor.
- **Die letzte automatische Sicherung ist fehlgeschlagen**: die Fehlermeldung aus dem Backup, die ebenfalls im Anwendungslog geschrieben wurde.

Wenn ein Lauf aus einem dieser Gründe übersprungen wird oder fehlschlägt, zeigt korTTY pro Grund eine Desktop-Benachrichtigung an (nicht je Stunde), sofern Desktop-Benachrichtigungen verfügbar und erlaubt sind.

!!! note "Verwaltet von Ihrer Organisation"
    Eine Unternehmensrichtlinie kann den Zeitplan (einschließlich *Aus*) und den Ordner mit `[rule.backup]` festlegen; die gesperrten Felder zeigen dann den Hinweis „managed by your organization“. Siehe [Unternehmensrichtlinie](../enterprise-policy.md#rulebackup).

Sicherungen umfassen `ssh-host-keys.properties`, sodass normalisierte Host:Port-Vertrauensentscheidungen, die von interaktiven Terminal-, SFTP- und Mosh-Bootstrap-Verbindungen geteilt werden, die Wiederherstellung überleben; Die transiente prozessübergreifende Datei `.lock` ist nicht enthalten. JobScheduler-Hostschlüssel-Pins bleiben separat in `job-scheduler.xml` gespeichert.

Neben diesen enthält ein Backup die Verbindungen, Anmeldedaten, GPG- und SSH-Schlüssel-Einträge mit den kopierten SSH-Schlüsseldateien, globale Einstellungen, JobScheduler-Jobs, Snippets und Snippet-Analysen, KI- und Schwarm-Chats, Themen, Umgebungen, Projektarbeitsbereiche und die Master-Passwort-Prüfdatei `master.key`, jedoch nicht die Auto-Entsperr-Kopie des Master-Passworts. Die vollständige Liste dessen, was ein Backup enthält und auslässt, finden Sie auf [Sicherung & Wiederherstellung](../../features/backup.md).

Lokale KI-Backups enthalten `llm/models.xml`, `llm/mlx-models.json` und `rag/stores.json`, sodass GGUF- und MLX-Modellregistrierungen, Rollenzuweisungen und Wissensquellendefinitionen wiederhergestellt werden können. Sie schließen GGUF-Gewichte, llama.cpp-Laufzeitpakete, temporäre Sidecar-Daten, Quellunterlagen und regenerierbare HNSW-Snapshots aus; Siehe [Sicherung & Wiederherstellung](../../features/backup.md).
