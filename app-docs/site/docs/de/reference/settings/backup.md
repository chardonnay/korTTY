---
title: Sicherung
---

# Sicherung

Konfigurieren Sie die Sicherungsaufbewahrungsrichtlinie und die Verschlüsselungsmethode für korTTY-Sitzungssicherungen. Öffnen über **Konfiguration → Globale Einstellungen → Backup**; in `~/.kortty/global-settings.xml` gespeichert.

![Backup settings tab](../../assets/screenshots/settings/backup.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Maximale Anzahl Backups | Nummer | 0–100 (0 = unbegrenzt) | 10 | `maxBackupCount` |
| ZIP mit Passwort | umschalten | — | Auf | `backupEncryptionType` |
| Anmeldeinformationen | Dropdown | Verfügbare gespeicherte Anmeldeinformationen | — | `backupCredentialId` |
| GPG-Verschlüsselung | umschalten | — | Aus | `backupEncryptionType` |
| GPG-Schlüssel | Dropdown | Verfügbare GPG-Schlüssel | — | `backupGpgKeyId` |

!!! warning
    Backups werden IMMER verschlüsselt. Beide Verschlüsselungsmodi (ZIP mit Passwort und GPG) sind obligatorisch – mindestens einer muss konfiguriert werden, bevor Backups durchgeführt werden können.

!!! note
    **Maximale Backups:** Auf `0` eingestellt, um Backups auf unbestimmte Zeit aufzubewahren; Jeder andere Wert (1–100) löscht automatisch die ältesten Backups, sobald das Limit erreicht ist. Wenn Sie eine passwortbasierte Verschlüsselung verwenden, wählen Sie eine Anmeldeinformation aus dem Verwaltungssystem aus. Wählen Sie bei Verwendung der GPG-Verschlüsselung einen verfügbaren GPG-Schlüssel aus. Der Verschlüsselungstyp wird in den globalen Einstellungen gespeichert und bestimmt, welche Anmeldeinformationen oder Schlüssel-ID für zukünftige Sicherungen verwendet werden.
