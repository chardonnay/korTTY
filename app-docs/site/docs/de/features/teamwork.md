---
title: Teamarbeit (gemeinsame Verbindungen)
---

# Teamarbeit (gemeinsame Verbindungen)

Teilen Sie SSH-Verbindungen mit Ihrem Team, indem Sie sie aus einem Git-Repository oder einer freigegebenen Datei synchronisieren. Teamwork-Quellen werden zusammen mit Ihren lokalen Verbindungen in den Connection Manager geladen, automatisch synchronisiert und sicher von Inline-Passwörtern befreit, sodass Anmeldeinformationen nur aus Ihrem lokalen verschlüsselten Speicher stammen.


![Teamwork sync](../assets/diagrams/teamwork-sync-flow.svg)

## Übersicht

Teamwork ermöglicht es Teams, eine zentrale Bibliothek von Verbindungskonfigurationen zu verwalten:

- **Git-Repositorys** – Klonen und synchronisieren Sie es mit einem Git-Repository, das eine `kortty-teamwork-connections.xml`-Datei (oder eine ältere `connections.xml`) enthält.
- **Freigegebene Dateien** – Laden Sie Verbindungen von einem lokalen oder Netzwerkpfad (schreibgeschützt oder Lese-/Schreibzugriff).
- **Automatische Synchronisierung** – Die Hintergrundsynchronisierung prüft in einem konfigurierbaren Intervall, ob Aktualisierungen vorliegen.
- **Anmeldeinformationssicherheit** – Gemeinsame Verbindungen übertragen KEINE Inline-Passwörter; nur Anmeldeinformations-IDs und SSH-Schlüsselreferenzen.
- **Lokale Überschreibungen** – Ihre lokalen Anmeldeinformationen und SSH-Schlüssel werden mit gemeinsam genutzten Verbindungsdefinitionen zusammengeführt.
- **Schreibgeschützter Modus** – Markieren Sie Quellen als schreibgeschützt, um versehentliches Zurückschreiben zu verhindern.

## Einrichten von Teamwork-Quellen

Öffnen Sie **Teamwork → Teamwork-Einstellungen…** (oder **Konfiguration → Globale Einstellungen… → Teamwork**), um Quellen zu konfigurieren.

### Fügen Sie eine Quelle hinzu

1. Klicken Sie auf **Hinzufügen**, um eine neue Quelle zu erstellen.
2. Wählen Sie die Quelle **Typ**:
   - **Git** – Von einer HTTPS-, SSH- oder git://-URL klonen.
   - **Freigegebene Datei** – Von einem lokalen oder Netzwerkpfad lesen (z. B. `file:///mnt/share/connections.xml` oder `//host/share/connections.xml`).
3. Geben Sie den **Standort** ein:
   - Für Git: die Klon-URL.
   - Für freigegebene Dateien: ein lokaler/Netzwerk-Dateipfad (kann ein file://-URI oder ein UNC-Pfad sein).
4. Stellen Sie das **Prüfintervall** ein (1–1440 Minuten; Standard: 15).
5. Aktivieren Sie optional **Schreibgeschützt**, um Rückschreibvorgänge zu verhindern (nur Git-Quellen).
6. Klicken Sie zum Speichern auf **OK**.

### Quellen verwalten

Im Dialogfeld „Teamwork-Einstellungen“ werden alle Quellen mit Typ, Standort und Synchronisierungsintervall aufgelistet:

| Spalte | Bedeutung |
| --- | --- |
| Geben Sie | ein **Git** oder **Shared File** |
| Standort | Repository-URL oder Dateipfad |
| Intervall | Minuten zwischen Synchronisierungsprüfungen |
| Aktiviert | Umschalten zum Aktivieren/Deaktivieren, ohne | zu löschen

Verwenden Sie die Schaltflächen, um:
- **Hinzufügen** – Erstellen Sie eine neue Quelle.
- **Bearbeiten** – Ändern Sie die ausgewählte Quelle.
- **Entfernen** – Löschen Sie die ausgewählte Quelle.
- **Aktivieren/Deaktivieren** – Schaltet den aktivierten Status für die ausgewählten Quellen um.

Legen Sie unten das **Standardprüfintervall** fest (gilt für neue Quellen, die keins angeben).

## So funktioniert die Synchronisierung

### Hintergrundsynchronisierung

Sobald Sie die Teamwork-Einstellungen gespeichert haben:

1. KorTTY startet einen Hintergrundsynchronisierungsthread.
2. Alle N Minuten (basierend auf dem Mindestintervall zwischen aktivierten Quellen) geschieht Folgendes:
   - Zieht/klont jede Quelle (Git) oder liest die Datei (Shared File).
   - Lädt die XML-Verbindungen.
   - Führt die Ergebnisse im Cache und im Verbindungsmanager zusammen.
3. Wenn eine Quellaktualisierung fehlschlägt, wird die vorherige zwischengespeicherte Version beibehalten.

### Manuelle Synchronisierung

Verwenden Sie **Teamwork → Teamwork-Einstellungen…** und klicken Sie auf **OK**, um sofort eine Synchronisierung auszulösen.

### Konflikterkennung

Bei jeder Synchronisierung wird ein Versionstoken aufgezeichnet:
- **Git** – Der aktuelle Commit-Hash.
- **Freigegebene Datei** – Der zuletzt geänderte Zeitstempel der Datei.

Wenn sich das Versionstoken einer gemeinsam genutzten Verbindung zwischen Synchronisierungen ändert, wurde eine neue Version abgerufen. Wenn Sie lokale Änderungen an einer Teamwork-Verbindung vorgenommen haben und die Quelle mit einer widersprüchlichen Änderung aktualisiert wird, bleiben die lokalen Änderungen erhalten (kein automatisches Überschreiben).

## Gemeinsam genutztes Verbindungsdateiformat

Erstellen Sie eine `kortty-teamwork-connections.xml`-Datei (oder `connections.xml` für Abwärtskompatibilität) im Stammverzeichnis Ihres Git-Repositorys oder Ihrer freigegebenen Datei:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<connections>
  <connection id="prod-web-1">
    <name>Prod Web Server 1</name>
    <host>web1.example.com</host>
    <port>22</port>
    <username>deploy</username>
    <group>Production/Web</group>
    <authMethod>SSH_KEY</authMethod>
    <sshKeyId>key-prod-deploy</sshKeyId>
    <credentialId>cred-prod-user</credentialId>
  </connection>
</connections>
```

!!! warning "Inline-Geheimnisse nicht einbeziehen"
    Teamwork-Verbindungen dürfen **nicht** `encryptedPassword`, `privateKeyPath` oder `privateKeyPassphrase` enthalten. Stattdessen:
    - Verwenden Sie `credentialId`, um auf gespeicherte Anmeldeinformationen in **Sicherheit → Anmeldeinformationen…** zu verweisen.
    - Verwenden Sie `sshKeyId`, um auf einen gespeicherten SSH-Schlüssel in **Sicherheit → SSH-Schlüssel…** zu verweisen.

Wenn in der freigegebenen Datei Inline-Geheimnisse gefunden werden, entfernt KorTTY diese automatisch (sie werden nicht geladen).

## Teamwork-Verbindungen nutzen

Sobald eine Quelle synchronisiert ist:

1. Öffnen Sie **Verbindungen verwalten…** (oder drücken Sie ++Strg+M++).
2. Teamwork-Verbindungen werden in der Baumstruktur mit der Bezeichnung **[Teamwork]** und ihrer Quell-ID angezeigt.
3. Klicken Sie auf eine Teamwork-Verbindung, um sie anzuzeigen oder zu verwenden.
4. **Kann nicht direkt bearbeitet werden** – Teamwork-Verbindungen sind schreibgeschützt, es sei denn, ihre Quelle ist als beschreibbar markiert und Sie besitzen Bearbeitungsrechte (bestimmt durch `teamworkRole`).

### Lokale Überschreibungen

- Die zusammengeführten Anmeldeinformationen und SSH-Schlüsselreferenzen werden aus Ihrem lokalen Speicher aufgelöst.
- Wenn lokal keine Anmeldeinformationen oder Schlüssel gefunden werden, werden Sie beim Herstellen der Verbindung aufgefordert, diese anzugeben.
- Ihre lokale Kopie einer Teamwork-Verbindung kann die Authentifizierung außer Kraft setzen, indem sie andere Anmeldeinformationen oder Schlüssel zuweist.

### Quellen unterscheiden

Im Connection Manager werden Teamwork-Verbindungen anhand ihrer Quell-ID gekennzeichnet. Bewegen Sie den Mauszeiger über die Verbindungseigenschaften oder überprüfen Sie sie, um zu sehen, von welcher Teamwork-Quelle sie stammt.

## Git-Repository-Setup

So teilen Sie Verbindungen über Git:

1. Erstellen Sie ein Repository (z. B. `ssh-connections`).
2. Fügen Sie eine `kortty-teamwork-connections.xml`-Datei mit Ihren Verbindungsdefinitionen zum Stammverzeichnis hinzu.
3. Commit und Push.
4. Teilen Sie die Repository-URL (HTTPS oder SSH) mit den Teammitgliedern.
5. Teammitglieder fügen die URL unter **Teamwork → Teamwork-Einstellungen… → Hinzufügen** hinzu.

### SSH vs. HTTPS

- **HTTPS** – Funktioniert ohne SSH-Schlüsseleinrichtung; Möglicherweise ist ein GitHub Personal Access Token oder ein Benutzername/Passwort erforderlich (bewahren Sie das Token sicher auf).
- **SSH** – Erfordert `git` und einen lokalen SSH-Schlüssel in `~/.ssh/id_rsa` (oder konfiguriert in `ssh-add`).

### Beispiel-Repository-Layout

```
ssh-connections/
├── kortty-teamwork-connections.xml
├── .gitignore
└── README.md
```

### Optional: Versionen in Git speichern

Verwenden Sie den Commit-Hash als Versionstoken, damit KorTTY Updates erkennen kann:

```bash
git log -1 --pretty=%H
```

## Einrichtung einer freigegebenen Datei

So teilen Sie Verbindungen über eine Datei:

1. Exportieren Sie Ihre Verbindungen in eine Datei: **Verbindungen → Exportieren… → Verbindungen auswählen → Als `.xml` speichern**.
2. Platzieren Sie die Datei auf einem freigegebenen Netzwerkpfad (z. B. `//server/share/connections.xml`).
3. Legen Sie die Lese-/Schreibberechtigungen nach Bedarf fest.
4. Teammitglieder fügen den Dateipfad unter **Teamwork → Teamwork-Einstellungen… → Hinzufügen** hinzu.

### Beispielpfade

| Plattform | Pfadformat |
| --- | --- |
| Windows (Netzwerkfreigabe) | `//server/share/connections.xml` oder `file:////server/share/connections.xml` |
| Linux/macOS (NFS-Mount) | `/mnt/teamshare/connections.xml` oder `file:///mnt/teamshare/connections.xml` |
| SMB/CIFS (gemountet) | `/Volumes/teamshare/connections.xml` (macOS) |

## Sicherheitsüberlegungen

!!! warning "Anmeldeinformationen sind nur lokal"
    Gemeinsame Verbindungen übertragen keine Passwörter oder Schlüsselpassphrasen. Ihr lokaler verschlüsselter Speicher (Master-Passwort geschützt) enthält die tatsächlichen Geheimnisse. Teammitglieder müssen ihre eigenen Anmeldeinformationen lokal eingerichtet haben.

!!! warning "Git-Repositorys sollten keine Geheimnisse speichern"
    Übergeben Sie niemals Passwörter, SSH-Schlüsselinhalte oder API-Tokens an das Teamwork-Repository. Verwenden Sie nur Anmeldeinformations-IDs und Schlüsselreferenzen.

!!! warning "Dateiberechtigungen"
    Beschränken Sie den Lese-/Schreibzugriff für freigegebene Dateien auf Netzwerkpfaden nur auf Teammitglieder. Stellen Sie sicher, dass der Pfad nicht allgemein lesbar ist.

!!! tip "Prüfpfad"
    Für Git-basierte Teamarbeit bietet der Commit-Verlauf einen Prüfpfad. Überprüfen Sie die Änderungen, bevor Sie sie abrufen, indem Sie den Remote-Zweig überprüfen.

## Fehlerbehebung

### Quelle wird nicht synchronisiert

1. Öffnen Sie **Teamwork → Teamwork-Einstellungen…**.
2. Stellen Sie sicher, dass die Quelle **Aktiviert** ist.
3. Überprüfen Sie, ob der **Standort** korrekt und zugänglich ist:
   - **Git** – Führen Sie `git clone <url>` zum Testen manuell aus.
   - **Freigegebene Datei** – Stellen Sie sicher, dass die Datei vorhanden und auf Ihrem Computer lesbar ist.
4. Klicken Sie auf **OK**, um eine manuelle Synchronisierung auszulösen.
5. Überprüfen Sie das Anwendungsprotokoll (`~/.kortty/kortty.log`) auf Fehler.

### Verbindungen werden angezeigt, aber Anmeldeinformationen fehlen

1. Öffnen Sie **Sicherheit → Anmeldeinformationen…** und **Sicherheit → SSH-Schlüssel…**.
2. Stellen Sie sicher, dass die Anmeldeinformations-IDs oder SSH-Schlüssel-IDs in den gemeinsam genutzten Verbindungen lokal vorhanden sind.
3. Wenn sie fehlen, fügen Sie sie manuell hinzu oder bitten Sie Ihren Teamadministrator, die IDs bereitzustellen.

### Änderungen können nicht an das Git-Repository übertragen werden

1. Stellen Sie sicher, dass die Git-URL SSH oder ein HTTPS-Token verwendet (nicht Benutzername/Passwort).
2. Stellen Sie sicher, dass Ihr SSH-Schlüssel bei der Fernbedienung registriert ist (GitHub, GitLab usw.).
3. Markieren Sie in den **Teamwork-Einstellungen** die Quelle als **Schreibgeschützt**, wenn Sie nicht zurückschreiben müssen.

### Dateipfad wird nicht erkannt (Windows/UNC)

Verwenden Sie Schrägstriche oder das URI-Format „file://“:

- `//server/share/connections.xml` ✓
- `\\server\share\connections.xml` ✗ (Backslashes werden möglicherweise nicht richtig analysiert)
- `file:////server/share/connections.xml` ✓ (UNC-Notation)

