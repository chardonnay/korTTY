---
title: Sicherheit – Anmeldeinformationen, Schlüssel und Verschlüsselung
---

# Sicherheit: Anmeldeinformationen, Schlüssel und Verschlüsselung

KorTTY schützt Ihre sensiblen Daten mit branchenüblicher Verschlüsselung und zentraler Schlüsselverwaltung. Alle Verbindungskennwörter, SSH-Schlüsselpassphrasen und die Speicherung von Anmeldeinformationen verwenden die AES-256-GCM-Verschlüsselung, die von einem Hauptkennwort abgeleitet wird, das beim ersten Start erstellt wird.


![Persistence & encryption](../assets/diagrams/persistence-encryption-flow.svg)

## Master-Passwort

Beim ersten Start werden Sie aufgefordert, ein Master-Passwort (mindestens 6 Zeichen) zu erstellen, das alle gespeicherten Geheimnisse verschlüsselt.

### Setup

1. Geben Sie ein Passwort ein (der Feldrand wird grün, wenn die Länge ausreichend ist, rot, wenn die Länge zu kurz ist).
2. Ein Stärke-Indikator zeigt die Passwortqualität; eine Warnung erscheint bei schwachen oder häufig verwendeten Passwörtern, können Sie jedoch gegebenenfalls bestätigen.
3. Bestätigen Sie das Passwort.
4. Klicken Sie auf **Setup**.

### At-Rest-Verschlüsselung

Das Master-Passwort selbst wird mit PBKDF2 gehasht (310.000 Iterationen) und niemals im Klartext gespeichert. Das Salz und der Hash werden in `~/.kortty/master.key` gespeichert.

Bei nachfolgenden Starts fordert KorTTY Sie auf, das Master-Passwort einzugeben, um verschlüsselte Daten zu entsperren. Das Deaktivieren von **Master-Passwort beim Programmstart anfordern** in **Einstellungen > Sicherheit** versteckt diese Aufforderung und startet mit dem Tresor gesperrt: gespeicherte Passwörter und Schlüssel bleiben unzugänglich, bis Sie ihn entsperren (siehe [Später den Tresor entsperren](#spater-den-tresor-entsperren)).

!!! danger "Optionale automatische Anmeldung schwächt den Schutz im Ruhezustand"
    Die zweite Sicherheitsoption, **Master-Passwort-Eingabeaufforderung beim Start deaktivieren (automatische Anmeldung)**, entfernt die Eingabeaufforderung ebenfalls, hält den Tresor jedoch vollständig nutzbar: korTTY schreibt Ihr Master-Passwort in `~/.kortty/master.autounlock` – **Nur verschleiert, nicht verschlüsselt**, mit Dateiberechtigungen nur für den Besitzer – und wird bei jedem Start automatisch entsperrt. Der Verschleierungsschlüssel ist in die Anwendung eingebettet, sodass Dateiberechtigungen die einzige wirkliche Grenze darstellen. jeder, der lesen kann `~/.kortty` oder ein Backup kann alle gespeicherten Geheimnisse entschlüsseln. Bei einem brandneuen Profil führt die Option ein Standard-Master-Passwort ohne Dialog aus. korTTY fragt vor der Aktivierung nach einer Bestätigung, es ist für Wegwerf-/Testumgebungen gedacht und a [Richtlinienkonfiguration](../reference/enterprise-policy.md) das ein Master-Passwort erfordert, deaktiviert es. Einzelheiten: [Sicherheitseinstellungen](../reference/settings/security.md).

!!! note
    Wenn Sie das Master-Passwort verlieren, können verschlüsselte Daten nicht wiederhergestellt werden. Löschen Sie `master.key` und `credentials.xml`, starten Sie neu, legen Sie ein neues Master-Passwort fest und geben Sie Ihre Passwörter erneut ein.

### Später den Tresor entsperren

Bei gesperrtem Tresor wählen Sie **Konfiguration > Sicherheit > Tresor entsperren…** und geben das Master-Passwort ein; der Menüeintrag ist ausgegraut, solange der Tresor geöffnet ist. Das Speichern eines KI- oder Übersetzungs-API-Schlüssels, eines Hugging-Face-Tokens, eines Jump-Server-Passworts oder eines JobScheduler-Passworts, das Starten eines KI-Swarm-Laufs auf Servern ohne geöffnetes Terminal, eine Frage an das Handbuch und das Generieren eines Workflow-Skripts für einen oder mehrere Server mit einem verschlüsselten KI-Schlüssel zeigen ihre Meldung "Tresor gesperrt" mit einer Schaltfläche **Tresor entsperren…**, statt in einer Sackgasse zu enden. Auch das Verbinden einer gespeicherten Verbindung, deren Passwort im Tresor liegt, über **Verbinden** im Connection-Manager, **Duplizieren** an einem Tab oder **Geschlossenen Tab wieder öffnen** bietet **Tresor entsperren…** an; wenn Sie den Tresor dort nicht entsperren, fordert korTTY Sie stattdessen auf, das Passwort einzugeben (siehe [Anmelden](connections.md#anmelden)). Wenn Sie ein Projekt öffnen, während der Tresor gesperrt ist, werden die Tabs, deren Passwort im Tresor liegt, in der Wiederherstellungsleiste des Fensters aufgeführt; das Entsperren des Tresors, über **Tresor entsperren…** in dieser Leiste oder auf jedem anderen Weg, öffnet sie in jedem Fenster (siehe [Tabs, die auf Sie warten](projects.md#tabs-die-auf-sie-warten)). Einige Stellen melden weiterhin nur den gesperrten Tresor, z. B. eine KI-Chat-Anfrage oder eine Schlüssel-Passphrase im Verbindungseditor; entsperren Sie den Tresor dann zuerst über das Menü. Nach erfolgreichem Entsperren wird die Aktion fortgesetzt; **Abbrechen** lässt den Tresor gesperrt, ohne einen zweiten Fehler zu erzeugen. Ein falsches Passwort lässt den Dialog geöffnet, und das Abbrechen beendet korTTY nie.

Das Entsperren stellt auch die temporären SSH-Schlüssel wieder her, die beim gesperrten Start nicht entschlüsselt werden konnten; bis dahin bleiben sie verschlüsselt gespeichert, sodass das Speichern von Verbindungen in der Zwischenzeit nicht verloren geht. Fenster, die bereits geöffnet waren, während der Tresor gesperrt war, wie der Connection-Manager oder die Anmelde- und Schlüsselmanager, erkennen das Entsperren nicht – öffnen Sie sie erneut, um dort gespeicherte Geheimnisse zu nutzen.

## Verschlüsselungsmodell

Alle vertraulichen Daten im Ruhezustand werden mit **AES-256-GCM** verschlüsselt:

- **Algorithmus**: AES-256-GCM (Galois/Counter-Modus)
- **Schlüsselableitung**: PBKDF2WithHmacSHA256 mit 310.000 Iterationen
- **IV Länge**: 12 Bytes (zufällig pro Verschlüsselung)
- **Authentifizierungs-Tag**: 128 Bit (integrierte Integritätsüberprüfung)
- **Salt-Länge**: 32 Bytes (zufällig pro Master-Passwort-Einrichtung)

Jeder verschlüsselte Wert kombiniert einen zufälligen IV mit dem Chiffretext, der zur Speicherung als Base64 codiert wird.

## Anmeldeinformationsverwaltung

Speichern Sie zentralisierte Benutzernamen-/Passwort-Anmeldeinformationen, die über mehrere Verbindungen hinweg wiederverwendet werden können.

### Öffnen des Managers

**Menü:** Konfiguration > Sicherheit > Zugangsdaten… (++ctrl+shift+m++, auf macOS ++cmd+shift+m++). Die Tastenkombination funktioniert auch, wenn ein Terminal den Fokus hat.

### Anmeldeinformationen hinzufügen

1. Klicken Sie auf **Hinzufügen**.
2. Ausfüllen:
   - **Name** – Beschreibender Bezeichner
   - **Benutzername** – Login-Benutzername
   - **Passwort-Typ** — **Gespeichertes Passwort**, oder **Externes Kommando** zum Abrufen des Passworts aus einem Passwort-Manager (siehe [Passwörter von einem externen Kommando](#passworter-von-einem-externen-kommando))
   - **Passwort** – Mit AES-256-GCM verschlüsselt gespeichert
   - **Umgebung** — Produktion (Standard), Entwicklung, Test, Staging oder eine Ihrer eigenen (siehe [Umgebungen und Tab-Farben](#umgebungen-und-tab-farben))
   - **Servermuster** (optional) – Glob-Muster (z. B. `*.example.com`, `10.0.0.*`) für den automatischen Abgleich von Anmeldeinformationen mit Verbindungen
   - **Beschreibung** (optional) – Freitextnotizen
3. Klicken Sie auf **OK**.

### Passwörter von einem externen Kommando

Anstatt das Passwort zu speichern, kann eine Anmeldeinformation jedes Mal aus Ihrem Passwort-Manager abgerufen werden, wenn sie benötigt wird. Wählen Sie **Externes Kommando** als **Passwort-Typ** und geben Sie ein Kommando ein, das das Passwort ausgibt, zum Beispiel:

```bash
op item get "db-prod" --fields password
bw get password "db-prod"
enpass-cli show "db-prod" -field password
```

- **Verschlüsselt gespeichert** — das Kommando selbst ist mit Ihrem Master-Passwort verschlüsselt, ähnlich wie ein gespeichertes Passwort.
- **Shell** — der Befehl läuft in `/bin/sh` auf macOS und Linux und in PowerShell unter Windows, daher schreiben Sie unter Windows PowerShell-Syntax (`$env:NAME`, nicht `%NAME%`). Im Flatpak-Paket läuft der Befehl auf dem Host, wo das Kommandozeilenwerkzeug Ihres Passwortmanagers installiert ist.
- **Ausgabe** — die erste Zeile, die der Befehl ausgibt, ist das Passwort. Wenn der Befehl mit einem Fehler beendet wird, zeigt korTTY dessen Exit-Code und Fehlermeldung an; eine leere Ausgabe gilt ebenfalls als Fehler.
- **Keine Eingabeaufforderungen** — der Befehl erhält keine Eingabe, daher kann ein Tool, das nach einem Master-Passwort oder PIN fragt, nicht beantwortet werden und schlägt sofort fehl. Entsperren Sie zunächst den Passwortmanager, zum Beispiel mit `op signin`, oder mit `bw unlock` und `BW_SESSION`, die in der Umgebung gesetzt sind, aus der korTTY gestartet wurde. Das Flatpak-Paket führt den Befehl auf dem Host ohne die Umgebung von korTTY aus, daher muss dort `BW_SESSION` in Ihrer Desktop-Sitzung gesetzt werden oder im Befehl selbst übergeben werden (zum Beispiel `bw get password my-server --session <key>`).
- **10-Sekunden-Grenze** — ein Befehl, der nach 10 Sekunden nicht beendet ist, wird zusammen mit allen von ihm gestarteten Prozessen gestoppt, und korTTY meldet das Timeout. Ein Touch-ID- oder Systemdialog, den das Tool selbst öffnet, muss innerhalb dieser Zeit bestätigt werden.
- **Jedes Mal ausführen** — der Befehl wird ausgeführt, sobald ein Passwort benötigt wird: wenn Sie die Anmeldeinformationen im Verbindungseditor auswählen und wann immer eine Verbindung, die sie nutzt, hergestellt wird. Von einem verbundenen Tab aufgeteilte Bereiche verwenden wieder das Passwort dieses Tabs.
- **Testen** — die **Testen**-Schaltfläche neben dem Befehl führt ihn einmal aus und zeigt, wie viele Zeichen zurückgegeben wurden, niemals das Passwort selbst.

Im Verbindungseditor zeigt das Passwortfeld *Passwort wird abgerufen...* während der Befehl läuft, und korTTY bleibt reaktionsfähig. Das Öffnen, Duplizieren oder Wiederherstellen einer Verbindung wartet auf den Befehl, bevor sie sich verbindet, höchstens bis zum 10-Sekunden-Limit, und das Fenster reagiert in dieser Zeit nicht; wenn der Befehl dort fehlschlägt, verwendet korTTY das eigene gespeicherte Passwort der Verbindung oder fordert eines an.

### Anmeldeinformationen in Verbindungen verwenden

Beim Erstellen oder Bearbeiten einer Verbindung:

1. Gehen Sie zur Registerkarte **Verbindung**.
2. Wählen Sie im Dropdown-Menü **Anmeldeinformationen** eine gespeicherte Anmeldeinformation aus.
3. Benutzername und Passwort werden automatisch ausgefüllt; für einen externen Befehl, sobald er das Passwort zurückgegeben hat.

Das folgende Diagramm zeigt, wie Anmeldeinformationen und SSH-Schlüssel vom verschlüsselten Speicher zu aktiven Verbindungen fließen:

![Credential & encryption flow](../assets/diagrams/credential-flow.svg)

### Umgebungen und Tab-Farben

Alle Anmeldeinformationen gehören zu einer Umgebung: einer der integrierten Umgebungen Produktion, Entwicklung, Test und Staging oder einer, die Sie hinzufügen. **Umgebungen...** in der Anmeldeinformationsverwaltung listet sie auf; dort können Sie eigene Umgebungen hinzufügen, umbenennen oder löschen. Die integrierten Umgebungen bleiben immer erhalten, und eine Umgebung, die noch von Anmeldeinformationen verwendet wird, kann nicht gelöscht werden.

Jede Umgebung, ob integriert oder selbst angelegt, kann eine **Tab-Farbe** haben: wählen Sie sie aus, aktivieren Sie **Tabs dieser Umgebung farbig markieren** und wählen Sie die Farbe. Ein Terminal-Tab, der sich mit gespeicherten Anmeldeinformationen dieser Umgebung angemeldet hat, zeigt die Farbe dann als Punkt vor seinem Titel und als Rahmen um sein Terminal, genau wie die [eigene Tab-Farbe einer Verbindung](connections.md#tab-farbe). Wenn Sie zum Beispiel die Umgebung Produktion rot markieren, markieren Sie damit auf einen Schlag jeden Tab, der sich mit Anmeldeinformationen aus der Produktion anmeldet.

- **Opt-in** — keine Umgebung hat eine Farbe, bis Sie eine wählen. Neue Anmeldeinformationen beginnen in Produktion, daher würde eine Standardfarbe die meisten Tabs als Produktions-Tabs markieren.
- **Die Farbe der Verbindung und des Ordners hat Vorrang** — eine Verbindung mit eigener Tab-Farbe zeigt diese Farbe, und eine Verbindung in einem Ordner mit einer [Tab-Farbe](connections.md#tab-farbe) zeigt die Farbe des Ordners, gleich zu welcher Umgebung ihre Anmeldeinformationen gehören.
- **Nur Verbindungen mit gespeicherten Anmeldeinformationen** — die Farbe erreicht Tabs, die sich mit gespeicherten Anmeldeinformationen angemeldet haben, einschließlich einer [Teamarbeit](teamwork.md)-Verbindung, die die Standard-Anmeldeinformationen des Teams verwendet. Verbindungen, die sich mit einem SSH-Schlüssel, einem temporären Schlüssel oder einem eigenen Passwort anmelden, haben keine Umgebung; geben Sie ihnen stattdessen eine eigene Tab-Farbe, oder färben Sie den Connection-Manager-Ordner ein, in dem sie liegen.
- **Wirksam mit OK** — Farbänderungen werden wirksam, wenn Sie im Dialog *Umgebungen* auf **OK** klicken, und **Abbrechen** verwirft sie. Offene Tabs in allen Fenstern übernehmen die neuen Farben sofort, so wie wenn Sie Anmeldeinformationen in eine andere Umgebung verschieben.
- **Nicht nur über die Farbe** — wenn Sie auf einen so gefärbten Tab zeigen, werden die Farbe und die Umgebung genannt, zum Beispiel *Tab-Farbe: lila (#7B1FA2), aus der Zugangsdaten-Umgebung Lab* für eine eigene Umgebung namens Lab. Screenreader lesen für den Punkt denselben Text vor.
- **Gespeichert in `environments.xml`** — zusammen mit Ihren eigenen Umgebungen; siehe [Konfigurationsdateien](../reference/config-files.md#environmentsxml).

### Eigenschaften

- **Umgebungsspezifisch** – Anmeldeinformationen nach Bereitstellungsumgebung organisieren
- **Server Pattern Matching** – Anmeldeinformationen automatisch passenden Servern zuweisen
- **Verschlüsselter Speicher** – Passwörter werden mit AES-256-GCM verschlüsselt
- **Passwort-Manager** — Rufen Sie das Passwort von einem Befehl wie `op`, `bw` oder `enpass-cli` ab, anstatt es zu speichern.
- **Automatische Nutzung** – Wählen Sie Anmeldeinformationen direkt in den Verbindungseinstellungen aus

## SSH Schlüsselverwaltung

Zentralisierte Verwaltung privater SSH-Schlüssel mit verschlüsselten Passphrasen.

### Öffnen des Managers

**Menü:** Konfiguration > Sicherheit > SSH-Schlüssel… (++ctrl+shift+i++, auf macOS ++cmd+shift+i++)

### Schlüssel hinzufügen

1. Klicken Sie auf **Hinzufügen**.
2. Wählen Sie den Pfad zu Ihrer privaten SSH-Schlüsseldatei.
3.  (Optional) Geben Sie die Passphrase ein – sie wird verschlüsselt und gespeichert.
4. Klicken Sie auf **OK**.

### Hauptmerkmale

- **Zentralisierte Verwaltung** – Verwalten Sie alle SSH-Schlüssel an einem Ort
- **Verschlüsselte Passphrasen** – Schlüsselpassphrasen werden mit AES-256-GCM verschlüsselt gespeichert
- **Schlüsselkopieren** — Verwenden Sie **In Benutzerverzeichnis kopieren**, um Schlüssel nach `~/.kortty/ssh-keys/` zu kopieren; unter macOS und Linux wird der kopierte private Schlüssel nur für den Besitzer lesbar (`rw-------`) gesetzt und das Verzeichnis `rwx------`, und kopierte Schlüssel werden in verschlüsselten Backups eingeschlossen und mit Besitzer-Nur Berechtigungen wiederhergestellt.
- **Platzhaltersuche** – Schnelle Suche nach Schlüsseln mithilfe von `*`-Mustern
- **Automatische Nutzung** – Wählen Sie Schlüssel direkt in den Verbindungseinstellungen aus

### Verwenden von Schlüsseln in Verbindungen

Beim Erstellen oder Bearbeiten einer Verbindung:

1. Gehen Sie zur Registerkarte **Verbindung**.
2. Wählen Sie **Privater Schlüssel** als Authentifizierungsmethode.
3. Wählen Sie den gewünschten Schlüssel aus der Dropdown-Liste **SSH-Schlüssel** aus.
4. Der Schlüsselpfad und die Passphrase werden automatisch ausgefüllt.

## Interaktive SSH-Hostschlüssel-Vertrauensstellung

Terminal- und SFTP-Verbindungen, einschließlich des SSH-Bootstraps, der von Mosh verwendet wird, teilen einen Trust-on-First-Use (TOFU)-Verifizierer, der nach normalisiertem Hostnamen und Port gekennzeichnet ist. Beim ersten Einsatz zeigt korTTY den Server-Schlüsselalgorithmus und den OpenSSH-SHA-256-Fingerabdruck an; verifizieren Sie ihn außerhalb des Kanals, bevor Sie ihn akzeptieren. Die Bestätigung ist standardmäßig auf **Nein** gesetzt. Ein zuvor vertrauenswürdiger übereinstimmender Schlüssel wird stillschweigend akzeptiert, während ein geänderter Schlüssel mit dem erwarteten und angebotenen Fingerabdruck hart blockiert wird und niemals automatisch erneut versucht oder ersetzt wird.

Ein geänderter Schlüssel kann nur durch eine explizite Entscheidung und ausschließlich in einer Verbindung ersetzt werden, die Sie selbst in einem Terminal-Tab oder im SFTP-Manager (einschließlich seines Sprungservers) geöffnet haben. **Prüfen und ersetzen…** in der Warnung zum geänderten Schlüssel zeigt den vertrauenswürdigen und den neuen Fingerabdruck nebeneinander; die Ersetzen-Schaltfläche bleibt deaktiviert, bis Sie bestätigen, dass Sie den neuen Fingerabdruck mit dem Server-Administrator verifiziert haben, und **Schließen** sowie **Abbrechen** bleiben die Standardknöpfe. Der Ersatz ist ein Compare-and-Swap: er wird nur gespeichert, solange der vertrauenswürdige Schlüssel noch exakt derselbe ist, den Sie überprüft haben; ein in einem anderen Fenster geänderter Schlüssel wird damit niemals überschrieben. Jeder Ersatz wird mit dem alten und dem neuen Fingerabdruck protokolliert. Hintergrund- und wiederhergestellte Verbindungen sowie der SSH-Bootstrap von Mosh behalten die reine Blockierung bei.

**Konfiguration → Sicherheit → Bekannte Hosts…** listet, durchsucht und entfernt vertrauenswürdige Schlüssel. Die Entfernung funktioniert auf die gleiche Weise: sie löscht einen Schlüssel nur, solange er noch den in der Bestätigung angezeigten Fingerabdruck besitzt, und die nächste Verbindung fragt erneut wie beim ersten Einsatz. Mit dem Unternehmensrichtlinien-Schlüssel `enforce-host-key-check` sind sowohl Ersetzen als auch Entfernen deaktiviert, sodass nur ein Administrator einen vertrauenswürdigen Schlüssel ändern kann.

Interaktive Pins werden atomar in `~/.kortty/ssh-host-keys.properties` geschrieben; Eine Companion-Sperre koordiniert gleichzeitige korTTY-Prozesse. Dieser Speicher unterscheidet sich von den verbindungs-ID-basierten Hostschlüssel-Pins des JobScheduler in `job-scheduler.xml`, die die unbeaufsichtigte SSH-, SFTP- und Rsync-Ausführung schützen.

### Lockere Überprüfung des Hostschlüssels

Für Labor- oder Wegwerf-Hosts können Sie die Eingabeaufforderung bei der ersten Verwendung deaktivieren und korTTY veranlassen, einen unbekannten Schlüssel stillschweigend zu akzeptieren. Hierbei handelt es sich um eine **Akzeptieren-Neu**-Lockerung, nicht um blindes Vertrauen: Ein Schlüssel, der sich von dem unterscheidet, der bereits für diesen Host festgelegt wurde, ist immer noch hart blockiert, sodass ein Man-in-the-Middle auf einem Host, mit dem Sie sich zuvor verbunden haben, immer noch abgefangen wird. Es ist standardmäßig deaktiviert und kann in der Reihenfolge der Priorität auf drei Bereiche eingestellt werden:

1. **Pro Verbindung** – das Steuerelement *Hostschlüsselüberprüfung* im Verbindungseditor des Connection-Managers und in der Schnellverbindung mit drei Zuständen: **Standard verwenden** (übernehmen), **Überprüfen** (strikt erzwingen, auch wenn die Gruppen- oder globale Einstellung dies gelockert hat) und **Nicht überprüfen**.
2. **Pro Gruppe** – Klicken Sie im Connection-Manager mit der rechten Maustaste auf eine Gruppe und aktivieren Sie **Hostschlüsselüberprüfung deaktivieren**; Es gilt für jede Verbindung in der Gruppe, die erbt. Die Einstellung gehört zum Ordner, nicht zu seinem Namen: Beim Umbenennen eines Ordners wandert sie, ebenso wie die seiner Unterordner, zum neuen Namen mit (führt das Umbenennen zwei Ordner zusammen, bleibt die Überprüfung nur aus, wenn sie in beiden aus war), und beim Löschen eines Ordners wird sie entfernt, sodass ein neuer Ordner mit demselben Namen mit eingeschalteter Überprüfung beginnt.
3. **Global** – **Einstellungen → Terminal → Hostschlüsselüberprüfung für alle Verbindungen deaktivieren**, der Basisstandard für jede Verbindung, die auf beiden oben genannten Ebenen erbt.

Der eigene Hostschlüssel eines Jump-Servers wird durch keines davon gelockert – die Bastion wird immer streng überprüft.

## GPG Schlüsselverwaltung

Verwalten Sie GPG-Schlüssel für die Backup-Verschlüsselung und die Verbindungs-/Snippet-Exportverschlüsselung.

### Öffnen des Managers

**Menü:** Konfiguration > Sicherheit > GPG-Schlüssel… (++ctrl+shift+g++, auf macOS ++cmd+shift+g++)

### Schlüssel hinzufügen

- **Manuelle Eingabe** – Klicken Sie auf **Hinzufügen**, um die Schlüssel-ID und die E-Mail-Adresse manuell einzugeben.
- **Systemimport** – Klicken Sie auf **Aus GPG importieren**, um Schlüssel aus dem GPG-Schlüsselbund Ihres Systems zu importieren.

### Bearbeiten und Entfernen von Schlüsseln

1. Wählen Sie einen Schlüssel aus der Liste aus.
2. Klicken Sie auf **Bearbeiten**, um Details zu ändern, oder auf **Löschen**, um sie zu entfernen.

### Verwenden von Schlüsseln für die Sicherung

1. Öffnen Sie **Einstellungen > Backup**.
2. Wählen Sie **GPG-Verschlüsselung** als Verschlüsselungstyp aus.
3. Wählen Sie den GPG-Schlüssel aus, der für die Verschlüsselung verwendet werden soll.

GPG-verschlüsselte Backups und Exporte werden als `.gpg` Dateien gespeichert — ein GPG-Backup als `kortty-backup.zip.gpg` — und benötigen den `gpg` Befehl Ihres Systems. Die Erstellung eines Backups erfordert den öffentlichen Schlüssel des Empfängers; die Wiederherstellung oder das Öffnen eines Backups erfordert den passenden **privaten** Schlüssel, und `gpg` kann nach dessen Passphrase fragen.

## Gespeicherte Sicherheitsdaten

Die folgenden sensiblen und sicherheitsrelevanten Daten werden in `~/.kortty/` gespeichert; Geheime Werte werden verschlüsselt, während öffentliches Verifizierungsmaterial nicht verschlüsselt ist:

| Datei | Inhalt | Verschlüsselung |
|------|----------|------------|
| `credentials.xml` | Gespeicherte Benutzername/Passwort-Anmeldeinformationen | AES-256-GCM |
| `ssh-keys.xml` | SSH-Schlüsselpfade und verschlüsselte Passphrasen | AES-256-GCM |
| `connections.xml` | Verbindungskennwörter (inline) und Schlüsselpassphrasen (wenn keine SSH-Schlüsselverwaltung verwendet wird) | AES-256-GCM |
| `ssh-host-keys.properties` | Vertrauenswürdige öffentliche Hostschlüssel für interaktive Terminal-, SFTP- und Mosh-Bootstrap-Verbindungen | Öffentliche Verifizierungsdaten; nicht verschlüsselt |
| `job-scheduler.xml` | Scheduler-Sudo-Passwörter und Archiv-Passwörter; Journaleinträge schwärzen von KorTTY verwaltete Geheimnisse | AES-256-GCM |
| `master.key` | Master-Passwort-Hash (PBKDF2, 310.000 Iterationen) und Salt | PBKDF2-Hash nur |
| `master.autounlock` | Gespeichertes Master-Passwort für die optionale automatische Anmeldung | Nur verschleiert – nicht verschlüsselt; Nur-Eigentümer-Dateiberechtigungen |
| `global-settings.xml` | KI-Profil-API-Schlüssel, Übersetzungs-API-Schlüssel, optionales Hugging Face-Token | AES-256-GCM |
| `session/last-session.xml`, `session/previous-session.xml` | Die offenen Fenster und Tabs, mit den IDs ihrer gespeicherten Verbindungen und dem Ordner, in dem sich jede lokale Shell befand; keine Passwörter, Schlüssel oder Bildschirmtext | Nicht verschlüsselt; Dateiberechtigungen nur für den Besitzer, nicht Teil von Backups |
| `session/scrollback/*.enc` | Nur wenn [das Wiederherstellen der Ausgabe](projects.md#die-ausgabe-wiederherstellen) eingeschaltet ist (standardmäßig aus): die neueste Ausgabe jedes Terminalbereichs, die alles enthalten kann, was ein Terminal angezeigt hat | AES-256-GCM mit dem Schlüssel des Master-Passworts; wird nie geschrieben, solange der Tresor gesperrt ist, nur für den Besitzer, nicht Teil von Backups, gelöscht, wenn sich das Master-Passwort ändert, ein Backup wiederhergestellt oder die Option ausgeschaltet wird |

korTTY schreibt diese Dateien nicht an Ort und Stelle neu: ein Speichern erfolgt in einer temporären Datei in `~/.kortty`, die dann über die alte Datei umbenannt wird, für Verbindungen, Anmeldedaten, SSH-Schlüssel, geplante Aufgaben und `master.key` nach dem Flush auf die Festplatte. So bleibt bei einem Absturz, einer vollen Festplatte oder einem beendeten Prozess die vorherige Version intakt. Unter macOS und Linux behält korTTY `~/.kortty` bei `rwx------` und schreibt `connections.xml`, `credentials.xml`, `ssh-keys.xml`, `job-scheduler.xml` und `master.key` auf Besitzer-Nur (`rw-------`), auch wenn eine ältere Version sie für andere Benutzer lesbar ließ; unter Windows sind sie durch die Berechtigungen Ihres Benutzerprofils geschützt.

Eine Datendatei mit Verbindungen, Anmeldedaten, SSH- oder GPG-Schlüsseln, Umgebungen, Themen oder geplanten Aufgaben, die korTTY beim Start nicht parsen kann, wird als `<name>.corrupt-<timestamp>` ohne Änderung verschoben; korTTY fährt ohne deren Inhalt fort und ein Hinweis listet die verschobenen Dateien auf. Eine Datei, die überhaupt nicht gelesen werden kann – zum Beispiel weil ein Virenscanner oder ein anderes Programm sie hält oder ein Netzwerk-Home-Verzeichnis nicht erreichbar ist – bleibt an ihrem Ort und korTTY speichert nicht darüber hinweg für den Rest der Sitzung; der Hinweis listet sie separat auf. `master.key` wird niemals verschoben, weil korTTY ohne ihn das Profil als neu behandeln und einen neuen Salzwert erzeugen würde: wenn es beschädigt ist, schlägt das Entsperren mit einem Fehler fehl, der die Datei nennt, und Sie stellen sie aus einem Backup wieder her.

## Best Practices für die Sicherheit

!!! warning
    Ausgewählter Terminaltext, der an KI-Dienste gesendet wird, kann sensible Informationen wie Anmeldeinformationen, Hostnamen, Dateipfade, Stack-Traces oder betriebliche Details enthalten. Bevor eine Auswahl an ein Profil außer einem integrierten Modell oder einem vertrauenswürdigen lokalen Endpunkt geht, maskiert korTTY das Passwort der Verbindung, die Ersetzungsregeln Ihrer Organisation und bekannte geheime Formate wie private Schlüssel, Zugriffstoken und Passwörter in URLs mit `***` (siehe [Verbergen von Geheimnissen vor dem Senden](ai-assistant.md#geheimnisse-vor-dem-senden-maskieren)). Diese Maskierung ist muster-basiert und erkennt Geheimnisse in anderen Formaten nicht, daher sollten Sie für sensible Daten weiterhin ein integriertes lokales GGUF-Modell bevorzugen oder verifizieren, dass Sie dem Remote-Endpunkt vertrauen, bevor Sie etwas senden.

### Master-Passwort

- Verwenden Sie ein sicheres, eindeutiges Master-Passwort (mindestens 12 Zeichen, eine Mischung aus Groß-/Kleinbuchstaben, Zahlen und Symbolen).
- Geben Sie niemals Ihr Master-Passwort weiter.
- Speichern Sie es sicher (Passwort-Manager empfohlen).

### SSH-Schlüssel

- Schützen Sie private Schlüsseldateien mit einer Passphrase.
- Schlüssel nach `~/.kortty/ssh-keys/` kopieren, um sie in verschlüsselte Backups aufzunehmen; Schlüssel, die an ihren ursprünglichen Speicherorten verbleiben, werden nur referenziert und müssen separat migriert werden.
- Beschränken Sie die Dateiberechtigungen für Schlüsseldateien (z. B. `chmod 600`). Kopien in `~/.kortty/ssh-keys/` werden automatisch nur für den Besitzer lesbar gesetzt.
- Verifizieren Sie einen Fingerabdruck eines Hosts bei Erstverwendung über einen vertrauenswürdigen Kanal, bevor Sie ihn akzeptieren. Behandeln Sie eine Warnung über einen geänderten Schlüssel als möglichen Server-Neuaufbau, DNS-Fehler oder Man-in-the-Middle-Angriff und untersuchen Sie das Problem statt sich wiederholt neu zu verbinden. Verwenden Sie **Prüfen und ersetzen…** erst, nachdem der Server-Administrator den neuen Fingerabdruck bestätigt hat.

### JobScheduler

- **Host-Schlüssel-Pinning**: Host-Schlüssel werden standardmäßig für unbeaufsichtigte SSH-/SFTP-/Rsync-Jobs gepinnt, um Man-in-the-Middle-Angriffe zu verhindern. Diese Verbindungs-ID-Pins sind absichtlich von den normalisierten Endpunkt-Pins getrennt, die von interaktiven Terminal-/SFTP-Sitzungen verwendet werden.
- **Sudo-Passwörter**: Scheduler-Sudo-Passwörter werden verschlüsselt und in `~/.kortty/job-scheduler.xml` gespeichert.
- **Journal-Schwärzung**: Job-Journaleinträge schwärzen von KorTTY verwaltete Geheimnisse vor der Persistenz (der geschwärzte Modus ist die Standardeinstellung; der vollständige Modus speichert nicht geschwärzte Ausgaben).

### Backup-Verschlüsselung

- Verschlüsseln Sie Backups immer entweder mit passwortgeschützter ZIP- oder GPG-Verschlüsselung.
- Speichern Sie Sicherungsdateien an einem sicheren Ort.
- Testen Sie die Wiederherstellungsverfahren regelmäßig, um sicherzustellen, dass Backups verwendbar sind.

### KI-Integration

- API-Schlüssel für KI-Endpunkte werden mit Ihrem Master-Passwort verschlüsselt.
- Das optionale Hugging Face-Token ist mit dem Master-Passwort verschlüsselt und wird nur für genehmigte Modellsuch-/Downloadanfragen an den vertrauenswürdigen Hugging Face-Host verwendet.
- Jeder integrierte `llama-server` bindet nur an `127.0.0.1` an einem zufälligen Port und erfordert einen generierten lokalen API-Schlüssel. Der Offline-Modus ist obligatorisch; Web-UI-, Agent-, UI-MCP-Proxy-, Slot-Endpunkt- und geerbte Serveroptionsüberschreibungen sind deaktiviert.
- GGUF-Downloads erfordern eine unveränderliche Repository-Revision und genaue SHA-256-Metadaten. Laufzeitindizes erfordern eine Ed25519-Signatur, und jede Laufzeit-ZIP-Datei wird vor der sicheren Extraktion anhand ihrer signierten Größe und SHA-256 überprüft. Offizielle Anwendungs-Builds betten nur das Vertrauensstammverzeichnis des öffentlichen Laufzeitkanals ein; Ein fehlender oder ungültiger Schlüssel schlägt fehl, bevor eine Aktualisierungsanforderung erfolgt, während der signierende private Schlüssel im vom Menschen gesendeten Promotion-Workflow isoliert bleibt.
- Signierte Laufzeitabhebungen sind dauerhaft und werden nicht geschlossen. Ein verifizierter Index fügt zurückgezogene Laufzeit- und Installations-IDs zu `llm/runtime/revoked-v1` hinzu, markiert jedes installierte Paket, löscht einen passenden aktiven Zeiger, stoppt seine Sidecars, entfernt es aus dem fehlerfreien Rollback-Verlauf und stellt betroffene Modellbindungen unter Quarantäne. Sowohl das Installationsprogramm als auch der Prozessstarter lehnen diese Pakete ab, auch nach einem unterbrochenen Update. Überprüfungen, bei denen nur eine Benachrichtigung erfolgt, erzwingen eine Auszahlung, ohne dass der angebotene Ersatz stillschweigend installiert wird. **Off** stellt keine Indexanfrage und erfährt daher bis zu einer expliziten oder aktivierten Prüfung keine neue Entnahme.
- Eine neu aktivierte Laufzeit wird nicht in den fehlerfreien Verlauf hochgestuft, nur weil die begrenzte `--version`-Prüfung bestanden wurde. Es bleibt ausstehend, bis der erste echte GGUF-gestützte authentifizierte API-Start erfolgreich ist; Wenn dieser Start fehlschlägt, wird der Kandidat entfernt und das neueste fehlerfreie, nicht widerrufene Paket wiederhergestellt bzw. erneut gebunden, sofern eines vorhanden ist.
- -Modellempfehlungen und die automatische Erkennung von Eingabeaufforderungsfamilien können aus einem separaten Ed25519-signierten HTTPS-Katalog aktualisiert werden. Der letzte gültige Cache wird vor der Verwendung erneut überprüft, und eine monotone Sequenz lehnt signierte ältere Wiederholungen oder Versionskollisionen mit gleicher Sequenz vor einem atomaren Hochwasser-Update ab. Ohne den unabhängigen öffentlichen Katalogschlüssel vertraut korTTY weder Netzwerk- noch Cache-Daten und greift auf den integrierten Bootstrap zurück. Das Signieren von Produktionskatalogen und Laufzeiten ist auf die durch Prüfer geschützten GitHub-Umgebungen im Hauptzweig beschränkt. Anwendungsbuilds erhalten nur die öffentlichen Vertrauenswurzeln.
- Die Konfiguration des KI-Profils wird lokal gespeichert; nur Ihre überprüfte Terminalauswahl oder Ihr Prompt wird an den gewählten Dienst gesendet, wobei erkannte Geheimnisse in der Auswahl maskiert werden. Eingebettete Inferenz bleibt auf diesem Computer.
- Wissensspeicher-Scanning folgt einer festen Text-Zulassungsliste, validiert Inhalte, lehnt symbolische Links ab und zeigt eine Vorschau an. Nur begrenzte abgerufene Auszüge, nicht der gesamte Wissensspeicher, werden in die Modellaufforderung eingegeben. Diese Auszüge bleiben für integrierte/Loopback-Profile lokal, verlassen jedoch den Computer, wenn ein explizit zugewiesenes Cloud-Profil die Anfrage verarbeitet. Wissensspeicherrollen und persistente Profilzuweisungen sind die Offenlegungsberechtigung des Benutzers.
- Remote-Qdrant-Wissensspeicher erfordern HTTPS; Einfaches HTTP wird nur für Loopback akzeptiert und der optionale API-Schlüssel bleibt durch den Tresor geschützt.
- Der Internetzugriff ist für KI-Profile standardmäßig deaktiviert. nur bei Bedarf aktivieren.
- Snippet-KI-Aktionen nutzen niemals den Internetzugang, selbst wenn dieser im Profil aktiviert ist.
- Die feste Snippet-/Workflow-**Diagramm**-Anfrage erhält nie Auszüge aus dem Wissensspeicher, selbst wenn an das Profil Speicher angehängt sind – die Diagramm-Eingabeaufforderung wird ausschließlich aus der Quelle erstellt.

## Sicherheitsübersicht

| Funktion | Implementierung |
|---------|-----------------|
| Master-Passwort-Hashing | PBKDF2 mit 310.000 Iterationen |
| Anmeldeinformationsverschlüsselung | AES-256-GCM |
| SSH-Schlüsselpassphrasen | Verschlüsselt mit AES-256-GCM und Master-Passwort |
| Interaktive SSH/SFTP/Mosh-Hostschlüssel | Geteilte normalisierte host:port TOFU, Bestätigung des Fingerabdrucks bei Erstverwendung (optional gelockert auf accept-new), stilles exaktes Match, harter Block bei Änderung; Ersetzung nur nach expliziter Fingerabdruckbestätigung, als compare-and-swap |
| KI-API-Schlüssel | Verschlüsselt mit AES-256-GCM und Master-Passwort |
| Eingebetteter llama.cpp | Nur-Loopback-Zufallsport, generierter API-Schlüssel, Offline-/gehärtete Server-Flags, Anforderungsleasing |
| GGUF/Laufzeit-Lieferkette | Unveränderliche Revisionen, SHA-256-Verifizierung, signierter Laufzeitindex, dauerhafte Sperrquarantäne, Rollback nach fehlgeschlagener Integritätsprüfung oder erstem echten API-Start |
| Modell-/Prompt-Katalog | Unabhängiger Ed25519 Trust Root, striktes Schema, monotone Anti-Replay-Sequenz, erneut verifizierter Atomcache, geschützte menschliche Förderung, Bootstrap-Fallback |
| RAG-Quellenaufnahme | Zentrale Zulassungsliste, UTF-8/PDF-Inhaltsprüfungen, kein Symlink-Traversal, überprüfte Vorschau |
| RAG-Eingabeaufforderungskontext | Feste Abrufgrenzen, stabile Quellmarkierungen, explizit nicht vertrauenswürdiger Wrapper, explizite profilbasierte lokale/Cloud-Offenlegung |
| Optionale automatische Anmeldung | Master-Passwort verschleiert gespeichert in `master.autounlock` mit Nur-Eigentümer-Berechtigungen; Standardmäßig deaktiviert, Bestätigung erforderlich, blockiert durch eine Richtlinie, die ein Master-Passwort erfordert |
| Backup-Verschlüsselung | AES-256 passwortgeschützt ZIP- oder GPG-verschlüsselt (ältere ZIP-verschlüsselte Backups bleiben importierbar) |
| JobScheduler-Geheimnisse | Sudo- und Archivkennwörter verschlüsselt; Journal-Schwärzung standardmäßig aktiviert |
| JobScheduler-Hostschlüssel | Hostschlüssel-Pinning standardmäßig für unbeaufsichtigte SSH-/SFTP-/Rsync-Jobs erforderlich |
| Anmeldeinformationen | Niemals im Klartext gespeichert |
| Lokale Datendateien | Atomares Ersetzen mit Flush auf die Festplatte, `~/.kortty` und geheime Speicher nur für den Eigentümer, nicht lesbare Dateien werden beiseitegeschoben oder unverändert gelassen statt überschrieben |

## Ändern des Master-Passworts

So ändern Sie Ihr Master-Passwort (wodurch alle gespeicherten Geheimnisse mit einer neuen Ableitung neu verschlüsselt werden):

1. Öffnen Sie **Einstellungen > Sicherheit**.
2. Klicken Sie auf **Master-Passwort ändern**.
3. Geben Sie Ihr aktuelles (altes) Master-Passwort ein.
4. Geben Sie das neue Master-Passwort zweimal ein.
5. Jedes Master-Passwort-geschützte Geheimnis wird automatisch mit dem neuen Schlüssel neu verschlüsselt: Verbindungs- und Jump-Server-Passwörter, SSH-Schlüssel-Passphrasen, gespeicherte Anmeldeinformationen, KI-Profil-API-Schlüssel und die globalen KI-/Übersetzungs-/Hugging-Face-Schlüssel, RAG-Wissensspeicher-Geheimnisse und JobScheduler-Sudo-/Archive-Passwörter. Die Änderung erfolgt stufenweise – das neue Passwort wird erst übernommen, wenn alle Filialen migriert wurden, sodass bei einem Fehler auf halbem Weg das alte Passwort in Kraft bleibt. Einzelne Geheimnisse, die nicht migriert werden können, bleiben unberührt, werden in der Ergebnisnachricht gezählt und im Protokoll vermerkt; Geben Sie diese manuell erneut ein.

## Konfigurationsdateien-Referenz

Alle KorTTY-Daten werden unter `~/.kortty/` gespeichert. Wichtige sicherheitsrelevante Dateien:

```text
~/.kortty/
├── master.key               # Master password hash and salt (PBKDF2)
├── master.autounlock        # Optional auto-login password (obfuscated, owner-only permissions)
├── credentials.xml           # Encrypted credentials (AES-256-GCM)
├── ssh-keys.xml             # SSH key paths and encrypted passphrases
├── gpg-keys.xml             # GPG keys for backup/export encryption
├── connections.xml          # Connection passwords and key passphrases
├── ssh-host-keys.properties # Interactive Terminal/SFTP/Mosh host-key pins
├── global-settings.xml      # AI API keys and other encrypted settings
├── llm/models.xml           # Model paths and typed launch settings (no model contents)
├── llm/runtime/             # Regenerable native packages; excluded from backup
├── llm/catalog/             # Regenerable signed-catalog cache; excluded from backup
├── llm/run/                 # Temporary per-process API keys/logs; excluded from backup
├── rag/stores.json          # Knowledge-store/source configuration
├── rag/stores/              # Regenerable HNSW snapshots; excluded from backup
├── job-scheduler.xml        # JobScheduler sudo/archive passwords (encrypted)
├── kortty.log               # Application log
└── history/                 # Compressed terminal session history
```
