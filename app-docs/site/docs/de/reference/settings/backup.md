---
title: Sicherung
---

# Backup

Konfigurieren Sie die Sicherungsaufbewahrungsrichtlinie und die Verschlüsselungsmethode für korTTY-Sitzungssicherungen. Öffnen über **Konfiguration → Globale Einstellungen → Backup**; in `~/.kortty/global-settings.xml` gespeichert.

![Backup settings tab](../../assets/screenshots/settings/backup.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Maximale Anzahl an Backups | Anzahl | 0–100 (0 = unbegrenzt) | 10 | `maxBackupCount` |
| ZIP mit Passwort | radio (one of two) | — | On | `backupEncryptionType` (`PASSWORD`) |
| Anmeldeinformationen | Dropdown-Liste | Verfügbare gespeicherte Anmeldeinformationen | – | `backupCredentialId` |
| GPG-Verschlüsselung | radio (one of two) | — | Aus | `backupEncryptionType` (`GPG`) |
| GPG-Schlüssel | Dropdown-Liste | Verfügbare GPG-Schlüssel | – | `backupGpgKeyId` |

!!! warning
    Backups werden IMMER verschlüsselt. Wählen Sie einen Modus, ZIP mit Passwort oder GPG. Der gewählte Modus benötigt seine Anmeldeinformationen oder seinen GPG-Schlüssel; ohne einen davon stoppt das Backup mit einem Fehler, bevor etwas geschrieben wird.

!!! note
    **Maximale Backups:** Setzen Sie `0`, um Sicherungen unbegrenzt zu behalten; jeder andere Wert (1–100) löscht automatisch die ältesten Sicherungen in `old-backups/`, sobald das Limit erreicht ist, wobei Passwort-ZIP- und GPG-Sicherungen zusammen gezählt werden. Bei passwortbasierter Verschlüsselung wählen Sie ein Credential aus dem Verwaltungssystem. Bei GPG-Verschlüsselung wählen Sie einen verfügbaren GPG-Schlüssel aus. Der Verschlüsselungstyp wird in den globalen Einstellungen gespeichert und bestimmt, welches Credential oder welche Schlüssel-ID für zukünftige Sicherungen verwendet wird.

Sobald ein Backup erstellt wurde, zeigt der Tab **Letztes Backup:** mit Datum, Uhrzeit und dem Pfad, in den es geschrieben wurde.

Sicherungen umfassen `ssh-host-keys.properties`, sodass normalisierte Host:Port-Vertrauensentscheidungen, die von interaktiven Terminal-, SFTP- und Mosh-Bootstrap-Verbindungen geteilt werden, die Wiederherstellung überleben; Die transiente prozessübergreifende Datei `.lock` ist nicht enthalten. JobScheduler-Hostschlüssel-Pins bleiben separat in `job-scheduler.xml` gespeichert.

Neben diesen enthält ein Backup die Verbindungen, Anmeldedaten, GPG- und SSH-Schlüssel-Einträge mit den kopierten SSH-Schlüsseldateien, globale Einstellungen, JobScheduler-Jobs, Snippets und Snippet-Analysen, KI- und Schwarm-Chats, Themen, Umgebungen, Projektarbeitsbereiche und die Master-Passwort-Prüfdatei `master.key`, jedoch nicht die Auto-Entsperr-Kopie des Master-Passworts. Die vollständige Liste dessen, was ein Backup enthält und auslässt, finden Sie auf [Sicherung & Wiederherstellung](../../features/backup.md).

Lokale KI-Backups enthalten `llm/models.xml`, `llm/mlx-models.json` und `rag/stores.json`, sodass GGUF- und MLX-Modellregistrierungen, Rollenzuweisungen und Wissensquellendefinitionen wiederhergestellt werden können. Sie schließen GGUF-Gewichte, llama.cpp-Laufzeitpakete, temporäre Sidecar-Daten, Quellunterlagen und regenerierbare HNSW-Snapshots aus; Siehe [Sicherung & Wiederherstellung](../../features/backup.md).
