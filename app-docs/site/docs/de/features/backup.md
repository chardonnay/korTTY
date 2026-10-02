---
title: Sichern und wiederherstellen
---

# Sichern und Wiederherstellen

KorTTY erstellt verschlüsselte Backups aller Ihrer Einstellungen, Verbindungen, Anmeldeinformationen und SSH-Schlüssel. Verwenden Sie Sicherung und Wiederherstellung, um Ihre Konfiguration zu schützen oder sie zwischen Computern zu verschieben.

![Backup & restore flow](../assets/diagrams/backup-restore.svg)

## Eigenschaften

* **Verschlüsselte Backups** – Alle Backups werden entweder mit passwortgeschützter ZIP- oder GPG-Verschlüsselung verschlüsselt
* **Konfigurationssicherung** — Enthält Verbindungen, Anmeldeinformationen, Anmeldeumgebungen, SSH/GPG-Schlüssel, vertrauenswürdige interaktive SSH-Hostschlüssel, globale Einstellungen, benutzerdefinierte Terminalthemen, JobScheduler-Konfiguration, Snippets und deren gespeicherte Codeanalysen, KI-Chatverlauf, KI-Swarm-Chats, lokale Modellregistrierungen (GGUF und MLX) sowie Metadaten der Wissensspeicher-Quellen
* **Regenerierbare lokale KI-Daten ausgeschlossen** – GGUF-Gewichte, native llama.cpp-Laufzeiten, signierter Katalog-Cache, temporäre Sidecar-Dateien und HNSW-Snapshots werden absichtlich nicht in das Archiv kopiert
* **Projektverzeichnis** – Alle gespeicherten Projektarbeitsbereiche sind in der Sicherung enthalten
* **Automatische Rotation** – Alte Backups werden automatisch mit Zeitstempeln in ein `old-backups`-Unterverzeichnis verschoben
* **Konfigurierbare Aufbewahrung** – Legen Sie eine maximale Anzahl der aufzubewahrenden Backups fest (0 = unbegrenzt; älteste Backups werden automatisch gelöscht)
* **Importieren/Wiederherstellen** – Wiederherstellung von zuvor erstellten Backups mit optionaler Überschreibkontrolle
* **Flexible Entschlüsselung** — Sowohl passwortverschlüsselte ZIP- als auch GPG-verschlüsselte Formate werden für Importe unterstützt; korTTY erkennt das Format aus dem Dateiinhalt, nicht aus seinem Namen

## Erstellen eines Backups

1. Öffnen Sie **Bearbeiten → Backup erstellen...** oder drücken Sie ++ctrl+shift+b++ (Befehl+Umschalt+B unter macOS)
2. Wählen Sie ein Zielverzeichnis für die Sicherungsdatei
3. Das Backup wird mit der unter **Einstellungen → Backup** konfigurierten Verschlüsselungsmethode erstellt.

KorTTY benennt die aktuelle Sicherung `kortty-backup.zip` (passwortgeschützte ZIP) oder `kortty-backup.zip.gpg` (GPG) im Zielverzeichnis. Die neue Sicherung wird zunächst vollständig geschrieben; erst danach wird jede vorhandene aktuelle Sicherung beider Art in ein `old-backups`-Unterverzeichnis mit angehängtem Zeitstempel rotiert, wobei ihr `.zip` oder `.zip.gpg`-Ende (z. B. `kortty-backup_2025-06-24_14-30-45.zip.gpg`) beibehalten wird. Wenn das Erstellen der Sicherung fehlschlägt, bleibt die vorhandene aktuelle Sicherung unverändert.

Das Backup umfasst:

| Artikel | Details |
|------|---------|
| Verbindungen | Alle gespeicherten SSH-Verbindungen und Gruppen |
| Anmeldeinformationen | Gespeicherte Benutzernamen und Passwörter (verschlüsselt) |
| Anmeldeumgebungen | Ihre benutzerdefinierten Anmeldeumgebungen (`environments.xml`); die integrierten benötigen keine Sicherung |
| SSH-Schlüssel | Schlüsselreferenzen mit verschlüsselten Passphrasen sowie die kopierten Schlüsseldateien in `~/.kortty/ssh-keys/` |
| Vertrauenswürdige interaktive Hosts | `ssh-host-keys.properties`, gemeinsam genutzt von Terminal, SFTP und dem Mosh SSH-Bootstrap; der Vergängliche `.lock` Begleiter ist nicht im Lieferumfang enthalten |
| GPG-Schlüssel | Öffentliche GPG-Schlüssel für die Backup-Verschlüsselung |
| Einstellungen | Globale Anwendungseinstellungen, Terminalkonfigurationen und KI-Profile |
| Themen | Terminalfarbthemen, einschließlich der von Ihnen erstellten (`themes.xml`) |
| JobScheduler-Jobs | Alle geplanten Jobs, Hostschlüssel-Pins und verschlüsselten Sudo-Passwörter |
| Snippets | Code-Snippets und Skriptvorlagen mit Metadaten |
| Snippet-Variablen | Benutzerdefinierte Variablen für die Snippet-Ersetzung |
| Snippet-Analysen | Die gespeicherten [Vollcodeanalysen](snippets.md#vollstandige-code-analyse) jedes Snippets und jedes [analysierten Ordners](snippets.md#ordner-als-ein-projekt-analysieren), mit ihren Apply-Läufen, Diagrammen und Modularisierungsvorschlägen |
| KI-Chats | Gespeicherte KI-Gesprächsverläufe und -Profile |
| KI-Swarm-Chats | Gespeicherte Swarm-Chats, einschließlich der von Ihnen geplanten KI-Swarm-Jobspeicher (`swarm-chats.xml`) |
| Lokale KI-Konfiguration | Lokale GGUF-Registrierungen und typisierte Starteinstellungen, MLX-Modellregistrierungen, Text/Coding-Rollen, bevorzugtes Laufzeit-Backend/Update-Politik und verschlüsseltes Hugging Face token |
| Wissensspeicherkonfiguration | Speichermetadaten und Quellpfade, Filter, Synchronisierungsmodi und Einbettungskonfiguration; nicht die HNSW-Vektoren |
| Projekte | Alle `.kortty` Projektarbeitsbereichsdateien |

## Backup-Verschlüsselung

### Passwortgeschützte ZIP-Datei (Standard)

1. Öffnen Sie **Einstellungen → Backup**
2. Wählen Sie **Verschlüsselungstyp: Passwort**
3. Wählen oder erstellen Sie Anmeldeinformationen, die als Verschlüsselungskennwort verwendet werden sollen
4. Optional **Maximale Backups** festlegen (0 = unbegrenzt)
5. Speichern

Passwortgeschützte Backups werden mit AES-256 verschlüsselt (über die zip4j-Bibliothek); Das Passwort der Anmeldeinformationen verschlüsselt alle Dateien im Archiv. Mit älteren korTTY-Versionen erstellte Backups verwendeten die veraltete ZIP-Verschlüsselung und können weiterhin importiert werden – die Entschlüsselungsmethode wird aus dem Archiv selbst gelesen. Beachten Sie, dass AES-verschlüsselte ZIPs ein AES-fähiges Tool (7-Zip, WinZip, `unzip` 6+) benötigen, wenn Sie jemals eines außerhalb von korTTY extrahieren.

### GPG-Verschlüsselung

1. Öffnen Sie **Einstellungen → Backup**
2. Wählen Sie **Verschlüsselungstyp: GPG**
3. Wählen Sie einen GPG-Schlüssel aus **GPG-Schlüssel verwalten...**
4. Optional **Maximale Backups** festlegen (0 = unbegrenzt)
5. Speichern

GPG-Backups werden für den öffentlichen Schlüssel Ihres ausgewählten GPG-Schlüssels verschlüsselt. KorTTY erstellt ein unverschlüsseltes ZIP in einem privaten temporären Ordner, auf den nur Ihr Benutzer zugreifen kann, verschlüsselt es mit `gpg`, speichert das Ergebnis als `kortty-backup.zip.gpg` und löscht anschließend das temporäre ZIP mit seinem Ordner. Ein GPG-Backup zu erstellen erfordert die installierte `gpg` und den öffentlichen Schlüssel des Empfängers; zur Wiederherstellung wird `gpg` und der passende **private** Schlüssel benötigt, daher bewahren Sie diesen Schlüssel (und sein Passwort) an einem Ort außerhalb des Backups auf.

!!! tip
    Wenn Sie noch keine GPG-Schlüssel eingerichtet haben, verwenden Sie **Verwaltung → GPG-Schlüssel verwalten...**, um Schlüssel aus Ihrem Systemschlüsselbund zu importieren oder sie manuell hinzuzufügen.

## Ein Backup wird importiert

1. Öffnen **Bearbeiten → Backup importieren...**
2. Wählen Sie eine Sicherungsdatei (`.zip` oder `.zip.gpg`)
3. Falls das Backup ein passwortgeschütztes ZIP ist, geben Sie das Passwort bei Aufforderung ein; GPG-Backups verlangen kein ZIP-Passwort — `gpg` kann stattdessen die Passphrase Ihres privaten Schlüssels anfordern.
4. Wählen Sie aus, ob **vorhandene Dateien überschrieben werden**:
   * **OK** — Backup-Dateien werden vorhandene Dateien in Ihrer Konfiguration ersetzen
   * **Abbrechen** — Vorhandene Dateien werden übersprungen; nur fehlende Dateien werden importiert
5. **Starten Sie die Anwendung neu**, damit alle Änderungen wirksam werden

KorTTY erkennt das Backup-Format anhand des Dateiinhalts, nicht nach Namen; daher werden GPG-Backups, die ältere Versionen als `kortty-backup.zip` gespeichert haben, ebenfalls als GPG-Backups importiert. Eine Datei, die weder ein ZIP-Archiv noch GPG-verschlüsselt ist, wird vor jeglicher Wiederherstellung abgelehnt.

Nach dem Import lädt korTTY die wiederhergestellten Verbindungen, Anmeldedaten, Umgebungen, SSH- und GPG-Schlüssel, Einstellungen, Themen, Snippets, Snippet-Variablen sowie gespeicherte KI- und Swarm-Chats neu. Ein späteres Speichern überschreibt sie daher nicht mit dem zuvor geladenen Inhalt.

Jede wiederhergestellte Datei ersetzt die lokale atomar, sodass ein unterbrochener Import niemals eine halbfertige Datei zurücklässt. Auf macOS und Linux werden `connections.xml`, `credentials.xml`, `ssh-keys.xml`, `job-scheduler.xml` und `master.key` nur für den Eigentümer lesbar (`rw-------`) wiederhergestellt; die anderen Dateien behalten die Berechtigungen der Datei, die sie ersetzen, und eine Datei, die lokal nicht existierte, wird Eigentümer-berechtigt erstellt. Wenn wiederhergestellte Verbindungen, Anmeldeinformationen, SSH-Schlüssel, GPG-Schlüssel oder Umgebungsdateien nicht geparst werden können, wird der Reload sie als `<name>.corrupt-<timestamp>` seitlich verschieben und korTTY behält das, was es zuvor geladen hatte; die nächste Speicherung schreibt dies in eine neue Datei. Eine Themendatei, die nicht geparst werden kann, wird auf dieselbe Weise seitlich verschoben und durch die eingebauten Themen ersetzt.

!!! warning
    Das Importieren eines Backups mit **Überschreiben** aktiviert wird Ihre aktuellen Einstellungen, Verbindungen und Anmeldedaten ersetzen. Wenn Sie unsicher sind, wählen Sie **Abbrechen**, um das Backup zu mergen, ohne zu überschreiben.

!!! important "Ein Backup mit einem anderen Master-Passwort"
    Wenn ein Überschreibungsimport das `master.key` eines anderen Master-Passworts bringt, gehört alles, was korTTY geladen hat, immer noch zum alten. korTTY fordert Sie auf, neu zu starten und beendet sich ohne die aktuellen Daten zu speichern, sodass die wiederhergestellten Dateien unverändert bleiben. Starten Sie korTTY erneut und entsperren es mit dem Master-Passwort des Backups.

## Inhalt der Sicherungsdatei

Sowohl `.zip`- als auch `.zip.gpg`-Backups enthalten dieselben Dateien:

* `connections.xml` – Alle SSH-Verbindungen und -Gruppen
* `credentials.xml` – Gespeicherte Anmeldeinformationen (immer noch mit Ihrem Master-Passwort verschlüsselt)
* `environments.xml` — Benutzerdefinierte Anmeldeumgebungen
* `ssh-keys.xml` – SSH-Schlüsselreferenzen und verschlüsselte Passphrasen
* `ssh-keys/` – Kopierte SSH-Schlüsseldateien (nur Schlüssel, die Sie über **In Benutzerverzeichnis kopieren** dort platziert haben; Schlüssel, auf die an ihren ursprünglichen Speicherorten verwiesen wird, werden nicht erfasst). Wiederhergestellte Schlüsseldateien erhalten nur Besitzerberechtigungen und ein Import wird zusammengeführt – bereits vorhandene Schlüssel werden nie gelöscht oder, ohne **Überschreiben**, ersetzt
* `ssh-host-keys.properties` – Vertrauenswürdige öffentliche Hostschlüssel für interaktive Terminal-, SFTP- und Mosh-Bootstrap-Verbindungen (`ssh-host-keys.properties.lock` ist absichtlich ausgeschlossen)
* `gpg-keys.xml` – öffentliche GPG-Schlüssel
* `global-settings.xml` — Anwendungseinstellungen, KI-Profile, Terminalstandards
* `themes.xml` — Terminal-Farbschemata, einschließlich Ihrer eigenen
* `job-scheduler.xml` – JobScheduler-Jobs, Host-Key-Pins, verschlüsselte Sudo-Passwörter
* `snippets.xml` – Codeausschnitte und Vorlagen
* `snippet-variables.xml` – Benutzerdefinierte Snippet-Variablen
* `snippet-analyses/` — Gespeicherte Vollcodeanalysen, eine Datei pro Snippet. Ein Import verschmilzt sie: Analysen, die nur lokal existieren, bleiben erhalten; fehlende werden hinzugefügt, und mit **Überschreiben** wird eine lokale Datei nur ersetzt, wenn die Kopie aus dem Backup neuer ist. Nicht gespeicherte Snippet-Entwürfe (`snippet-drafts/`) sind nicht enthalten
* `ai-chats.xml` – Gespeicherte KI-Gespräche
* `swarm-chats.xml` — Gespeicherte KI-Swarm-Chats
* `master.key` — Salt und Prüfhash Ihres Master-Passworts, damit die wiederhergestellten Geheimnisse damit entsperrt werden können
* `llm/models.xml` – Lokale GGUF-Registrierungen und Laufzeiteinstellungen (Modellgewichte sind nicht enthalten)
* `llm/mlx-models.json` — MLX-Modellregistrierungen (Modellgewichte sind nicht enthalten)
* `rag/stores.json` – Wissensspeicher- und Quellkonfiguration (Vektor-Snapshots sind nicht enthalten)
* `projects/` – Alle gespeicherten Projektarbeitsbereichsdateien (`.kortty`)

!!! note
    Alle Passwörter und Anmeldeinformationen im Backup bleiben mit Ihrem Master-Passwort verschlüsselt. Wenn Sie ein Backup importieren, müssen Sie das Hauptkennwort für KorTTY entsperren, um die Anmeldeinformationen zu entschlüsseln.

!!! important "Erstellen Sie lokale KI-Assets nach einer Wiederherstellung neu"
    Die Sicherung schließt `llm/models/`-, `llm/runtime/`-, `llm/catalog/`-, `llm/run/`- und lokale `index.hnsw`-Snapshots aus. Stellen Sie nach dem Wechsel auf einen anderen Computer die GGUF-Dateien und eine kompatible Laufzeit wieder her oder laden Sie sie herunter, verbinden Sie alle externen Modell-/Quellpfade erneut und führen Sie dann **Jetzt aktualisieren** in jedem Wissensspeicher aus, um seinen Index neu zu generieren. Der signierte Katalogcache wird automatisch aktualisiert oder greift auf den Bootstrap zurück. Originalquelldokumente und externe Qdrant-Daten sind nicht Teil einer korTTY-Konfigurationssicherung.

## Backup-Aufbewahrung und -Bereinigung

Wenn Sie ein neues Backup in einem Verzeichnis erstellen, das bereits eines enthält, führt KorTTY Folgendes aus:

1. Erstellt die neue Sicherung — `kortty-backup.zip` für ein passwortgeschütztes ZIP, `kortty-backup.zip.gpg` für GPG — und stoppt hier, wobei die vorhandene Sicherung unverändert bleibt, falls das fehlschlägt
2. Verschiebt jede vorhandene aktuelle Sicherung, egal welcher Art, nach `old-backups/kortty-backup_<timestamp>.zip` oder `old-backups/kortty-backup_<timestamp>.zip.gpg`; eine in der gleichen Sekunde rotierte Sicherung erhält `-1`, `-2` … angehängt statt die frühere zu ersetzen
3. Wenn die Anzahl der alten Backups **Maximale Backups** überschreitet, werden die ältesten gelöscht

**Maximale Backups** zählen ZIP- und GPG-Backups zusammen. Sobald Sie den Verschlüsselungstyp wechseln, rotiert das erste neue Backup auch das aktuelle Backup des anderen Typs, sodass das älteste Backup einen Backup früher gelöscht werden kann, als Sie erwarten würden.

Um unbegrenzte alte Sicherungen zu behalten, setzen Sie **Maximale Backups** auf `0` in **Einstellungen → Sicherung**. Um nur eine alte Sicherung neben der aktuellen zu behalten, setzen Sie **Maximale Backups** auf `1`.

## Verwenden von Backups auf mehreren Maschinen

1. **Exportieren Sie Ihre aktuelle Konfiguration:**
   * Öffnen Sie auf Computer A **Bearbeiten → Backup erstellen...** und speichern Sie es auf einem USB-Laufwerk oder einem Cloud-Speicher

2. **Sicherungsdatei verschieben:**
   * Kopieren Sie `kortty-backup.zip` (oder `kortty-backup.zip.gpg`) auf Maschine B; für ein GPG-Backup benötigt Maschine B außerdem `gpg` und den privaten Schlüssel des GPG-Schlüssels des Backups

3. **Import auf der neuen Maschine:**
   * Öffnen Sie auf Computer B **Bearbeiten → Backup importieren...**
   * Wählen Sie die Sicherungsdatei aus Schritt 2 aus
   * Geben Sie das Backup-Passwort ein, wenn Sie dazu aufgefordert werden
   * Wählen Sie **Abbrechen** bei der Überschreibungsfrage, es sei denn, Sie möchten vorhandene Verbindungen ersetzen
   * KorTTY neu starten

Alle gesicherten Verbindungen, Einstellungen, Snippets, gespeicherten Chats, interaktive Hostschlüssel-Vertrauensentscheidungen, Modellregistrierungen und Wissensquellendefinitionen sind auf Maschine B verfügbar. Wiederhergestellte Hostschlüssel werden weiterhin mit dem normalisierten Hostnamen und Port abgeglichen, sodass ein geänderter Schlüssel nach der Migration blockiert bleibt. Lokale Modellgewichte, Laufzeitpakete, Quelldokumente und HNSW-Vektoren müssen separat wiederhergestellt oder neu generiert werden.

## Fehlerbehebung

**"Sicherungsdatei nicht gefunden"**
: Überprüfen Sie, ob der Dateipfad korrekt ist und die Datei vorhanden ist. Überprüfen Sie die Verzeichnisberechtigungen.

**„Passwort für passwortverschlüsseltes Backup erforderlich“**
: Passwortgeschützte Backups benötigen das richtige Passwort. Stellen Sie sicher, dass Sie das Anmeldepasswort (aus **Einstellungen → Sicherung**) und nicht Ihr Master-Passwort eingeben.

**"… ist kein korTTY-Backup: Die Datei ist weder ein ZIP-Archiv noch GPG-verschlüsselt."**
: Die ausgewählte Datei ist weder ein ZIP-Archiv noch GPG-verschlüsselte Daten, zum Beispiel eine andere Datei mit einem `.zip` Namen oder ein Backup, das beim Kopieren abgeschnitten wurde. Wählen Sie die `kortty-backup.zip` oder `kortty-backup.zip.gpg` Datei selbst.

**"GPG-Verschlüsselung fehlgeschlagen"**
`gpg` konnte die neue Sicherung nicht verschlüsseln: es ist nicht installiert, oder der öffentliche Schlüssel des ausgewählten Schlüssels befindet sich weder in Ihrem GPG-Schlüsselbund noch als Schlüsseldatei, die mit dem Schlüssel in **GPG-Schlüssel verwalten...** gespeichert ist. Die vorherige Sicherung im Zielverzeichnis bleibt unverändert.

**"GPG decryption failed"**
: `gpg` konnte die Sicherung nicht entschlüsseln: der private Schlüssel, der zum GPG-Schlüssel der Sicherung passt, befindet sich nicht in Ihrem Schlüsselbund, `gpg` ist nicht installiert oder Sie haben die Passphrase-Eingabe abgebrochen. Importieren Sie den privaten Schlüssel in Ihren GPG-Schlüsselbund (zum Beispiel mit `gpg --import`) und versuchen Sie es erneut.

**„GPG-Schlüssel nicht gefunden“**
: Der zur Verschlüsselung verwendete GPG-Schlüssel fehlt. Verwenden Sie **Verwaltung → GPG-Schlüssel verwalten...**, um den Schlüssel zu importieren oder hinzuzufügen, und versuchen Sie es dann erneut.

**„Kein Passwort für Backup-Verschlüsselung ausgewählt“ oder „Kein GPG-Schlüssel ausgewählt“**
: Konfigurieren Sie unter **Einstellungen → Backup** ein Passwort oder einen GPG-Schlüssel, bevor Sie ein Backup erstellen.

**Eine `*.corrupt-<timestamp>` Datei erschien in `~/.kortty`**
: korTTY konnte die Datendatei beim Start oder nach einem Import nicht parsen, daher verschob es das Original unter diesem Namen ohne Änderungen und setzte den Betrieb fort. Reparieren Sie die Datei, und wenn korTTY geschlossen ist, verschieben Sie sie zurück unter ihren ursprünglichen Namen oder stellen die Datei aus einer Sicherung wieder her.

**Der Import war erfolgreich, aber die Änderungen wurden nicht wirksam**
: Starten Sie KorTTY neu, damit importierte Einstellungen aktiv werden. Wenn Sie Anmeldeinformationen importiert haben, müssen Sie nach dem Neustart möglicherweise auch das Master-Passwort entsperren.

**Sicherungsdatei ist größer als erwartet**
: Große Backups können auftreten, wenn Sie viele gespeicherte KI-Chats oder ein großes Projektverzeichnis haben. GGUF-Gewichte, llama.cpp-Laufzeitpakete und HNSW-Snapshots sind ausgeschlossen und können nicht die Ursache sein.
