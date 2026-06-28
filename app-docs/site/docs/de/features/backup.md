---
title: Sichern und wiederherstellen
---

# Sichern und Wiederherstellen

KorTTY erstellt verschlüsselte Backups aller Ihrer Einstellungen, Verbindungen, Anmeldeinformationen und SSH-Schlüssel. Verwenden Sie Sicherung und Wiederherstellung, um Ihre Konfiguration zu schützen oder sie zwischen Computern zu verschieben.

![Backup & restore flow](../assets/diagrams/backup-restore.svg)

## Merkmale

* **Verschlüsselte Backups** – Alle Backups werden entweder mit passwortgeschützter ZIP- oder GPG-Verschlüsselung verschlüsselt
* **Vollständige Sicherung** – Beinhaltet Verbindungen, Anmeldeinformationen, SSH-Schlüssel, GPG-Schlüssel, globale Einstellungen, JobScheduler-Konfiguration, Snippets und AI-Chat-Verlauf
* **Projektverzeichnis** – Alle gespeicherten Projektarbeitsbereiche sind in der Sicherung enthalten
* **Automatische Rotation** – Alte Backups werden automatisch mit Zeitstempeln in ein `old-backups`-Unterverzeichnis verschoben
* **Konfigurierbare Aufbewahrung** – Legen Sie eine maximale Anzahl der aufzubewahrenden Backups fest (0 = unbegrenzt; älteste Backups werden automatisch gelöscht)
* **Importieren/Wiederherstellen** – Wiederherstellung von zuvor erstellten Backups mit optionaler Überschreibkontrolle
* **Flexible Entschlüsselung** – Sowohl passwortverschlüsselte ZIP- als auch GPG-verschlüsselte Formate werden für den Import unterstützt

## Erstellen eines Backups

1. Öffnen Sie **Bearbeiten → Backup erstellen...** oder drücken Sie ++Strg+Umschalt+B++ (Befehl+Umschalt+B unter macOS)
2. Wählen Sie ein Zielverzeichnis für die Sicherungsdatei
3. Das Backup wird mit der unter **Einstellungen → Backup** konfigurierten Verschlüsselungsmethode erstellt.

KorTTY nennt das aktuelle Backup `kortty-backup.zip` im Zielverzeichnis. Wenn dort bereits ein Backup vorhanden ist, wird es automatisch in ein `old-backups`-Unterverzeichnis mit angehängtem Zeitstempel rotiert (z. B. `kortty-backup_2025-06-24_14-30-45.zip`).

Das Backup umfasst:

| Artikel | Einzelheiten |
|------|---------|
| Verbindungen | Alle gespeicherten SSH-Verbindungen und Gruppen |
| Anmeldeinformationen | Gespeicherte Benutzernamen und Passwörter (verschlüsselt) |
| SSH-Schlüssel | Zentral verwaltete private SSH-Schlüssel mit verschlüsselten Passphrasen |
| GPG-Schlüssel | Öffentliche GPG-Schlüssel für die Backup-Verschlüsselung |
| Einstellungen | Globale Anwendungseinstellungen, Terminalkonfigurationen, Themen und KI-Profile |
| JobScheduler-Jobs | Alle geplanten Jobs, Hostschlüssel-Pins und verschlüsselten Sudo-Passwörter |
| Schnipsel | Codefragmente und Skriptvorlagen mit Metadaten |
| Snippet-Variablen | Benutzerdefinierte Variablen für die Snippet-Ersetzung |
| KI-Chats | Gespeicherte KI-Gesprächsverläufe und -Profile |
| Projekte | Alle `.kortty`-Projektarbeitsbereichsdateien |

## Backup-Verschlüsselung

### Passwortgeschützte ZIP-Datei (Standard)

1. Öffnen Sie **Einstellungen → Backup**
2. Wählen Sie **Verschlüsselungstyp: Passwort**
3. Wählen oder erstellen Sie Anmeldeinformationen, die als Verschlüsselungskennwort verwendet werden sollen
4. Legen Sie optional **Maximale Backups** fest (0 = unbegrenzt)
5. Speichern

Passwortgeschützte Backups verwenden die Standard-ZIP-Verschlüsselung (`EncryptionMethod.ZIP_STANDARD`) über die zip4j-Bibliothek. Das Passwort der Anmeldeinformationen wird zum Verschlüsseln aller Dateien im Archiv verwendet.

### GPG-Verschlüsselung

1. Öffnen Sie **Einstellungen → Backup**
2. Wählen Sie **Verschlüsselungstyp: GPG**
3. Wählen Sie einen GPG-Schlüssel aus **GPG-Schlüssel verwalten...**
4. Legen Sie optional **Maximale Backups** fest (0 = unbegrenzt)
5. Speichern

GPG-Backups verschlüsseln die Backup-Datei mit dem öffentlichen Schlüssel Ihres ausgewählten GPG-Schlüssels. KorTTY erstellt zunächst eine temporäre ZIP-Datei, verschlüsselt sie dann mit `gpg --encrypt` und speichert die Datei `.gpg`. Die temporäre ZIP-Datei wird nach der Verschlüsselung sicher gelöscht.

!!! Tipp
Wenn Sie noch keine GPG-Schlüssel eingerichtet haben, verwenden Sie **Verwaltung → GPG-Schlüssel verwalten...**, um Schlüssel aus Ihrem Systemschlüsselbund zu importieren oder sie manuell hinzuzufügen.

## Ein Backup importieren

1. Öffnen Sie **Bearbeiten → Backup importieren...**
2. Wählen Sie eine Sicherungsdatei (`.zip` oder `.gpg`)
3. Wenn die Sicherung passwortgeschützt ist, geben Sie das Passwort ein, wenn Sie dazu aufgefordert werden
4. Wählen Sie, ob Sie **Vorhandene Dateien überschreiben** möchten:
* **Aktiviert** – Sicherungsdateien ersetzen alle vorhandenen Dateien in Ihrer Konfiguration
* **Deaktiviert** – Vorhandene Dateien werden übersprungen; Es werden nur fehlende Dateien importiert
5. Klicken Sie auf **Importieren**
6. **Starten Sie die Anwendung neu**, damit alle Änderungen wirksam werden

!!! Warnung
Durch das Importieren eines Backups mit aktiviertem **Überschreiben** werden Ihre aktuellen Einstellungen, Verbindungen und Anmeldeinformationen ersetzt. Wenn Sie sich nicht sicher sind, deaktivieren Sie diese Option, um das Backup zusammenzuführen, ohne es zu überschreiben.

## Inhalt der Sicherungsdatei

Sowohl `.zip`- als auch `.gpg`-Backups enthalten dieselben Dateien:

* `connections.xml` – Alle SSH-Verbindungen und -Gruppen
* `credentials.xml` – Gespeicherte Anmeldeinformationen (immer noch mit Ihrem Master-Passwort verschlüsselt)
* `ssh-keys.xml` – SSH-Schlüsselreferenzen und verschlüsselte Passphrasen
* `gpg-keys.xml` – öffentliche GPG-Schlüssel
* `global-settings.xml` – Anwendungseinstellungen, Themen, AI-Profile, Terminal-Standardeinstellungen
* `job-scheduler.xml` – JobScheduler-Jobs, Host-Key-Pins, verschlüsselte Sudo-Passwörter
* `snippets.xml` – Codeausschnitte und Vorlagen
* `snippet-variables.xml` – Benutzerdefinierte Snippet-Variablen
* `ai-chats.xml` – Gespeicherte KI-Gespräche
* `master-password-hash` – Hash Ihres Master-Passworts (zur Überprüfung beim Import)
* `projects/` – Alle gespeicherten Projektarbeitsbereichsdateien (`.kortty`)

!!! Notiz
Alle Passwörter und Anmeldeinformationen im Backup bleiben mit Ihrem Master-Passwort verschlüsselt. Wenn Sie ein Backup importieren, müssen Sie das Hauptkennwort für KorTTY entsperren, um die Anmeldeinformationen zu entschlüsseln.

## Backup-Aufbewahrung und -Bereinigung

Wenn Sie ein neues Backup in einem Verzeichnis erstellen, das bereits eines enthält, führt KorTTY Folgendes aus:

1. Erstellt das neue Backup als `kortty-backup.zip`
2. Verschiebt das vorhandene Backup nach `old-backups/kortty-backup_<timestamp>.zip`
3. Wenn die Anzahl der alten Backups die **Maximale Anzahl an Backups** überschreitet, werden die ältesten gelöscht

Um unbegrenzt alte Backups aufzubewahren, stellen Sie **Maximale Backups** unter **Einstellungen → Backup** auf `0` ein. Um nur das aktuelle Backup zu behalten, legen Sie **Maximale Backups** auf `1` fest (alte Backups werden weiterhin rotiert, dann aber sofort gelöscht).

## Maschinenübergreifende Sicherungen verwenden

1. **Exportieren Sie Ihre aktuelle Konfiguration:**
* Öffnen Sie auf Computer A **Bearbeiten → Backup erstellen...** und speichern Sie es auf einem USB-Laufwerk oder einem Cloud-Speicher

2. **Sicherungsdatei verschieben:**
* Kopieren Sie `kortty-backup.zip` (oder `.gpg`) auf Maschine B

3. **Import auf der neuen Maschine:**
* Öffnen Sie auf Computer B **Bearbeiten → Backup importieren...**
* Wählen Sie die Sicherungsdatei aus Schritt 2 aus
* Geben Sie das Backup-Passwort ein, wenn Sie dazu aufgefordert werden
* Lassen Sie **Überschreiben** deaktiviert, es sei denn, Sie möchten vorhandene Verbindungen ersetzen
* Starten Sie KorTTY neu

Alle Ihre Verbindungen, Einstellungen, Snippets und gespeicherten Chats sind auf Computer B verfügbar.

## Fehlerbehebung

**"Sicherungsdatei nicht gefunden"**
: Überprüfen Sie, ob der Dateipfad korrekt ist und die Datei vorhanden ist. Überprüfen Sie die Verzeichnisberechtigungen.

**„Passwort für passwortverschlüsseltes Backup erforderlich“**
: Passwortgeschützte Backups benötigen das richtige Passwort. Stellen Sie sicher, dass Sie das Anmeldepasswort (aus **Einstellungen → Sicherung**) und nicht Ihr Master-Passwort eingeben.

**„GPG-Schlüssel nicht gefunden“**
: Der zur Verschlüsselung verwendete GPG-Schlüssel fehlt. Verwenden Sie **Verwaltung → GPG-Schlüssel verwalten...**, um den Schlüssel zu importieren oder hinzuzufügen, und versuchen Sie es dann erneut.

**„Kein Passwort für Backup-Verschlüsselung ausgewählt“ oder „Kein GPG-Schlüssel ausgewählt“**
: Konfigurieren Sie unter **Einstellungen → Backup** ein Passwort oder einen GPG-Schlüssel, bevor Sie ein Backup erstellen.

**Der Import war erfolgreich, aber die Änderungen wurden nicht wirksam**
: Starten Sie KorTTY neu, damit importierte Einstellungen aktiv werden. Wenn Sie Anmeldeinformationen importiert haben, müssen Sie nach dem Neustart möglicherweise auch das Master-Passwort entsperren.

**Sicherungsdatei ist größer als erwartet**
: Große Backups können auftreten, wenn Sie viele gespeicherte AI-Chats, große Terminal-Verlaufsdateien oder ein großes Projektverzeichnis haben. Ältere Projekte oder Chats können vor der Verwendung aus dem Import gelöscht werden.
