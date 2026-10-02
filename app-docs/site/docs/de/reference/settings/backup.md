---
title: Sicherung
---

# Backup

Konfigurieren Sie die Sicherungsaufbewahrungsrichtlinie und die Verschlüsselungsmethode für korTTY-Sitzungssicherungen. Öffnen über **Konfiguration → Globale Einstellungen → Backup**; in `~/.kortty/global-settings.xml` gespeichert.

![Backup settings tab](../../assets/screenshots/settings/backup.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Maximale Anzahl an Backups | Anzahl | 0–100 (0 = unbegrenzt) | 10 | `maxBackupCount` |
| ZIP mit Passwort | umschalten | — | Ein | `backupEncryptionType` |
| Anmeldeinformationen | Dropdown-Liste | Verfügbare gespeicherte Anmeldeinformationen | – | `backupCredentialId` |
| GPG-Verschlüsselung | umschalten | – | Aus | `backupEncryptionType` |
| GPG-Schlüssel | Dropdown-Liste | Verfügbare GPG-Schlüssel | – | `backupGpgKeyId` |

!!! warning
    Backups werden IMMER verschlüsselt. Beide Verschlüsselungsmodi (ZIP mit Passwort und GPG) sind obligatorisch – mindestens einer muss konfiguriert werden, bevor Backups durchgeführt werden können.

!!! note
    **Maximale Backups:** Setzen Sie `0`, um Sicherungen unbegrenzt zu behalten; jeder andere Wert (1–100) löscht automatisch die ältesten Sicherungen in `old-backups/`, sobald das Limit erreicht ist, wobei Passwort-ZIP- und GPG-Sicherungen zusammen gezählt werden. Bei passwortbasierter Verschlüsselung wählen Sie ein Credential aus dem Verwaltungssystem. Bei GPG-Verschlüsselung wählen Sie einen verfügbaren GPG-Schlüssel aus. Der Verschlüsselungstyp wird in den globalen Einstellungen gespeichert und bestimmt, welches Credential oder welche Schlüssel-ID für zukünftige Sicherungen verwendet wird.

Sicherungen umfassen `ssh-host-keys.properties`, sodass normalisierte Host:Port-Vertrauensentscheidungen, die von interaktiven Terminal-, SFTP- und Mosh-Bootstrap-Verbindungen geteilt werden, die Wiederherstellung überleben; Die transiente prozessübergreifende Datei `.lock` ist nicht enthalten. JobScheduler-Hostschlüssel-Pins bleiben separat in `job-scheduler.xml` gespeichert.

Lokale KI-Backups enthalten `llm/models.xml`, `llm/mlx-models.json` und `rag/stores.json`, sodass GGUF- und MLX-Modellregistrierungen, Rollenzuweisungen und Wissensquellendefinitionen wiederhergestellt werden können. Sie schließen GGUF-Gewichte, llama.cpp-Laufzeitpakete, temporäre Sidecar-Daten, Quellunterlagen und regenerierbare HNSW-Snapshots aus; Siehe [Sicherung & Wiederherstellung](../../features/backup.md).
