---
title: Teamarbeit (gemeinsame Verbindungen)
---

# Teamarbeit (gemeinsame Verbindungen)

Teilen Sie SSH-Verbindungen mit Ihrem Team, indem Sie sie aus einem Git-Repository oder einer gemeinsamen Datei synchronisieren. Teamwork-Quellen werden zusammen mit Ihren lokalen Verbindungen in den Connection-Manager geladen, automatisch synchron gehalten und sicher von Inline-Passwörtern bereinigt, sodass die Anmeldedaten ausschließlich aus Ihrem lokalen verschlüsselten Speicher stammen. Die Synchronisation erfolgt einseitig: korTTY liest von einer Quelle und schreibt niemals zurück.


![Teamwork sync](../assets/diagrams/teamwork-sync-flow.svg)

## Übersicht

Teamwork ermöglicht es Teams, eine zentrale Bibliothek von Verbindungskonfigurationen zu verwalten:

- **Git-Repositorys** – Klonen Sie ein Git-Repository, das eine `kortty-teamwork-connections.xml`-Datei (oder eine ältere Datei `connections.xml`) enthält, und bleiben Sie mit diesem synchron.
- **Gemeinsame Dateien** — Verbindungen aus einem lokalen oder Netzwerkpfad laden. korTTY liest die Datei nur.
- **Automatischer Synchronisierung** — Hintergrund-Synchronisierung in einem konfigurierbaren Intervall überprüft nach Aktualisierungen.
- **Sicherheit der Anmeldeinformationen** — Gemeinsame Verbindungen enthalten keine Passwörter direkt; lediglich Anmeldeinformationen-IDs und SSH-Schlüssel-Referenzen.
- **Local-Überwachungen** — Ihre lokalen Anmeldeinformationen und SSH-Schlüssel werden mit den gemeinsamen Verbindungsbestimmungen kombiniert.
- **Einweg-Synchronisation** — korTTY pusht nie in ein Repository oder schreibt in eine gemeinsame Datei. Gemeinsame Verbindungen werden in der Quelle selbst geändert und kommen mit der nächsten Synchronisation.

## Einrichten von Teamwork-Quellen

Öffnen Sie **Teamarbeit → Teamarbeit-Einstellungen…** (oder **Konfiguration → Globale Einstellungen… → Teamarbeit**) zur Konfiguration der Quellen.

### Fügen Sie eine Quelle hinzu

1. Klicken Sie auf **Hinzufügen**, um eine neue Quelle zu erstellen.
2. Wählen Sie die Quelle **Typ**:
   - **Git** — Aus einem HTTPS-, SSH- oder git://-URL-Link klonen.
   - **Gemeinsames Datei** — Lesen aus einem lokalen oder Netzwerkpfad (z. B. `file:///mnt/share/connections.xml` oder `//host/share/connections.xml`).
3. Geben Sie den **Standort** ein:
   - Für Git: die Klon-URL.
   - Für freigegebene Dateien: ein lokaler/Netzwerk-Dateipfad (kann ein file://-URI oder ein UNC-Pfad sein).
4. Legen Sie das **Prüfintervall** fest (1–1440 Minuten; Standard: 15).
5. Klicken Sie zum Speichern auf **OK**.

### Quellen verwalten

Im Dialogfeld „Teamwork-Einstellungen“ werden alle Quellen mit Typ, Standort und Synchronisierungsintervall aufgelistet:

| Spalte | Bedeutung |
| --- | --- |
| Geben Sie | ein **Git** oder **Shared File** |
| Standort | Repository-URL oder Dateipfad |
| Intervall | Minuten zwischen Synchronisierungsprüfungen |
| Aktiviert | Umschalten zum Aktivieren/Deaktivieren ohne Löschen |

Verwenden Sie die Schaltflächen, um:
- **Hinzufügen** – Erstellen Sie eine neue Quelle.
- **Bearbeiten** – Ändern Sie die ausgewählte Quelle.
- **Entfernen** – Die ausgewählte Quelle löschen.
- **Aktivieren/Deaktivieren** – Schaltet den aktivierten Status für die ausgewählten Quellen um.

Legen Sie unten das **Standardprüfintervall** fest (gilt für neue Quellen, die keins angeben).

## So funktioniert die Synchronisierung

### Hintergrundsynchronisierung

Sobald Sie die Teamwork-Einstellungen gespeichert haben:

1. KorTTY startet einen Hintergrundsynchronisierungsthread.
2. Alle N Minuten (basierend auf dem Mindestintervall zwischen aktivierten Quellen) geschieht Folgendes:
   - Zieht/klont jede Quelle (Git) oder liest die Datei (Shared File).
   - Lädt die XML-Verbindungen.
   - Ersetzt die zwischengespeicherte Kopie jeder abgerufenen Quelle und aktualisiert den Connection-Manager.
3. Wenn eine Quellaktualisierung fehlschlägt, wird die vorherige zwischengespeicherte Version beibehalten.

### Manuelle Synchronisierung

Verwenden Sie **Teamwork → Teamwork-Einstellungen…** und klicken Sie auf **OK**, um sofort eine Synchronisierung auszulösen.

### Versionsverfolgung

Bei jeder Synchronisierung wird ein Versionstoken aufgezeichnet:
- **Git** – Der aktuelle Commit-Hash.
- **Freigegebene Datei** – Der zuletzt geänderte Zeitstempel der Datei.

Wenn sich das Versions-Token einer gemeinsamen Verbindung zwischen Synchronisationen ändert, wurde eine neue Version abgerufen. Bei jeder erfolgreichen Synchronisation ersetzt korTTY seine zwischengespeicherte Kopie der Quelle durch die gerade abgerufene Version; es werden keine lokalen Änderungen zusammengeführt. Für eine Git-Quelle setzt korTTY sein eigenes Klon auf den Remote-Branch zurück, sodass alles, was innerhalb dieses Klons geändert wurde, verworfen wird. Lokale Überschreibungen betreffen ausschließlich Anmeldeinformationen und SSH-Schlüssel (siehe [Lokale Überschreibungen](#lokale-uberschreibungen)).

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

1. Öffnen Sie **Verbindungen verwalten…** (oder drücken Sie ++ctrl+m++).
2. Wechseln Sie zum Tab **Teamwork-Verbindungen**.
3. Doppelklicken Sie auf eine Teamwork-Verbindung, um sich zu verbinden.
4. **Nur lesen** — Teamwork-Verbindungen sind nur lesbar, und korTTY schreibt niemals Änderungen zurück in eine Quelle. Um eine geteilte Verbindung zu ändern, bearbeiten Sie sie im Repository oder der gemeinsamen Datei; die Änderung wird mit dem nächsten Sync übernommen.

Das Löschen einer Teamwork-Verbindung versteckt sie nur auf diesem Computer; die Quelle wird nicht geändert. In der Schaltflächenspalte des Connection-Manager **Gelöschte wiederherstellen** bringt versteckte Verbindungen zurück und **Aktualisieren** lädt die Liste aus dem letzten Sync neu, ohne die Quelle erneut abzurufen.

### Lokale Überschreibungen

- Die Referenzen für Anmeldeinformationen und SSH-Schlüssel (`credentialId`, `sshKeyId`) einer geteilten Verbindung werden aus Ihrem lokalen Speicher aufgelöst.
- Für geteilte Verbindungen, die keines von beiden angeben, wählen Sie **Anmeldung für alle Teamwork-Verbindungen** auf dem Tab **Teamwork-Verbindungen**: eine gespeicherte Anmeldeinformation, einen SSH-Key oder **Temporärer SSH-Key**. Eine gespeicherte Anmeldeinformation, die einen Benutzernamen enthält, verwendet diesen; für einen SSH-Key oder einen temporären Key ersetzt ein optionaler **Standard-Benutzername (SSH-Schlüssel)** den Benutzernamen aus der Quelle.
- Wenn lokal keine Anmeldeinformationen oder Schlüssel gefunden werden, werden Sie beim Herstellen der Verbindung aufgefordert, diese anzugeben.
- Nur die Authentifizierung, einschließlich des Benutzernamens, kann lokal überschrieben werden. Host, Port, Gruppe und die übrigen Verbindungsparameter stammen immer aus der Quelle.

### Quellen unterscheiden

Verbindungen von jeder aktivierten Quelle erscheinen zusammen auf dem Tab **Teamwork-Verbindungen**, sortiert nach den in den gemeinsamen Dateien definierten Gruppen. Der Tab zeigt nicht an, aus welcher Quelle eine Verbindung stammt; geben Sie jeder Quelle eigene Gruppennamen, wenn Ihr Team sie unterscheiden muss.

## Git-Repository-Setup

So teilen Sie Verbindungen über Git:

1. Erstellen Sie ein Repository (z. B. `ssh-connections`).
2. Fügen Sie eine `kortty-teamwork-connections.xml`-Datei mit Ihren Verbindungsdefinitionen zum Stammverzeichnis hinzu.
3. Commit und Push.
4. Teilen Sie die Repository-URL (HTTPS oder SSH) mit den Teammitgliedern.
5. Teammitglieder fügen die URL unter **Teamwork → Teamwork-Einstellungen… → Hinzufügen** hinzu.

### SSH im Vergleich zu HTTPS

- **HTTPS** – Funktioniert ohne SSH-Schlüsseleinrichtung; Möglicherweise ist ein GitHub Personal Access Token oder ein Benutzername/Passwort erforderlich (bewahren Sie das Token sicher auf).
- **SSH** – Erfordert `git` und einen lokalen SSH-Schlüssel in `~/.ssh/id_rsa` (oder konfiguriert in `ssh-add`).

### Beispiel-Repository-Layout

```
ssh-connections/
├── kortty-teamwork-connections.xml
├── .gitignore
└── README.md
```

### Versionstoken

korTTY verwendet automatisch den Commit-Hash des überwachten Branches als Versionstoken. Um zu sehen, welche Version Ihr Team verwendet, führen Sie aus:

```bash
git log -1 --pretty=%H
```

## Einrichtung einer freigegebenen Datei

So teilen Sie Verbindungen über eine Datei:

1. Exportieren Sie Ihre Verbindungen in eine Datei: **Verbindungen → Exportieren… → Verbindungen auswählen → Speichern als `.xml`**.
2. Platzieren Sie die Datei auf einem freigegebenen Netzwerkpfad (z. B. `//server/share/connections.xml`).
3. Geben Sie Teammitgliedern Lesezugriff auf die Datei. korTTY liest sie nur, daher benötigt ausschließlich derjenige, der die Datei pflegt, Schreibzugriff.
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
    Für gemeinsam genutzte Dateien auf Netzwerkpfaden geben Sie nur Teammitgliedern Lesezugriff und nur demjenigen, der die Datei pflegt, Schreibzugriff. Stellen Sie sicher, dass der Pfad nicht für alle lesbar ist. Wer die Datei ändern oder in den überwachten Git-Branch pushen kann, entscheidet, welche Hosts, Sprungserver und Tunnel die geteilten Verbindungen auf jedem Computer des Teammitglieds verwenden.

!!! tip "Prüfpfad"
    Für die Git-basierte Zusammenarbeit liefert der Commit-Verlauf eine Prüfliste. korTTY übernimmt, was immer sich im verfolgten Branch bei der nächsten Synchronisation befindet, sodass Sie Änderungen prüfen, bevor sie in diesen Branch gepusht werden.

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

### Dateipfad wird nicht erkannt (Windows/UNC)

Verwenden Sie Schrägstriche oder das URI-Format „file://“:

- `//server/share/connections.xml` ✓
- `\\server\share\connections.xml` ✗ (Backslashes werden möglicherweise nicht richtig analysiert)
- `file:////server/share/connections.xml` ✓ (UNC-Notation)

