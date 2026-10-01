---
title: JobScheduler
---

# JobScheduler

Der JobScheduler führt unbeaufsichtigte Hintergrundjobs aus, während KorTTY geöffnet ist. Es ist kein Betriebssystemdienst oder eine aktive SSH-Terminalregisterkarte erforderlich. Jobs werden automatisch nach einem konfigurierten Zeitplan mithilfe gespeicherter SSH-Verbindungen aus dem Connection-Manager ausgeführt.

Unter macOS und Windows verhindert ein aktivierter Job mit einer zukünftigen Ausführung, dass der Computer in den Systemschlaf wechselt, während korTTY ausgeführt wird, wenn **Konfiguration > Systemschlaf verhindern** aktiviert ist, sodass die geplante Zeit erreichbar bleibt. Der Scheduler verwendet einen einzigen Weckvorgang für den nächsten fälligen Lauf und verfügt über keinen Abfrage-Timer, wenn kein aktivierter zukünftiger Job vorhanden ist. Ohne Terminalverbindung, zukünftiger oder laufender Scheduler-Auftrag oder aktive KI-Anfrage bleibt der Systemschlaf auch dann verfügbar, wenn die Einstellung überprüft wird. Während ein Job ausgeführt wird, fügt macOS korTTY nicht in App Nap ein. Der Display-Ruhezustand ist nicht blockiert. Die Linux-Stromunterdrückung wird noch nicht unterstützt.

Öffnen Sie es mit **Tools > JobScheduler...**. Der Dialog merkt sich seine Fensterposition und -größe. Die deutsche Benutzeroberfläche übersetzt jetzt alle Beschriftungen, Aktionsnamen, Statuswerte, Validierungsmeldungen, Zielselektoren und unterstützenden Eingabeaufforderungen. Technische Protokoll- und Formatnamen wie SFTP, Rsync, ZIP, TAR, stdout und stderr bleiben unverändert.


![JobScheduler execution](../assets/diagrams/jobscheduler-execution.svg)

## Übersicht

JobScheduler unterstützt sechs Arten von Aktionen:

- **COMMAND** – Führen Sie einen nicht interaktiven Remote-Shell-Befehl aus
- **SNIPPET_SCRIPT** – Führen Sie ein SnippetManager-Skript auf dem Ziel mit optionalen Parametern aus
- **AI_AGENT** – Führen Sie einen Headless-KI-Agenten mit expliziter automatischer Genehmigung aus
- **AI_SWARM** – Senden Sie eine KI-Agent-Aufgabe parallel an alle ausgewählten Ziele und speichern Sie die kombinierte Antwort als gespeicherten Schwarm-Chat
- **SFTP** – Hochladen, Herunterladen, Synchronisieren, Löschen, Umbenennen, Verzeichnisse erstellen, Berechtigungen festlegen, Eigentümer ändern, Remote-Kopieren oder Archive erstellen
- **RSYNC_SYNC** – Verzeichnisse über externes `rsync` über SSH synchronisieren

## Job-Konfiguration

Das JobScheduler-Dialogfeld verfügt über drei Registerkarten: **Job**, **Aktion** und **Journal**.

### Job-Registerkarte: Ziele und Zeitpläne

Verwenden Sie die Registerkarte **Job**, um zu definieren, wo und wann ein Job ausgeführt wird.

| Feld | Beschreibung |
|-------|-------------|
| **Aktiviert** | Aktiviert oder deaktiviert den Job. |
| **Name** | Anzeigename, der in der Auftragsliste, im Journal und im Menüleistenstatus angezeigt wird. |
| **Verbindung** | Öffnet den Connection-Manager-Auswahldialog. Wählen Sie einzelne SSH-TCP-Server, ganze Gruppen oder beides aus. Mosh-Verbindungen werden nicht unterstützt. |
| **Arbeitsverzeichnis** | Optionales Remote-Verzeichnis. Verwenden Sie **Durchsuchen...**, um eine Verbindung zum Ziel herzustellen und ein Verzeichnis auszuwählen, wenn der Pfad nicht bekannt ist. |
| **Journal** | `LIMITED_REDACTED` speichert begrenzte, geschwärzte Auszüge. `FULL` speichert die vollständige Ausgabe/Transkripte (von KorTTY verwaltete Geheimnisse werden weiterhin geschwärzt). |
| **Aktiv von** / **Aktiv bis** | Optionaler Datumsbereich für den Job. Außerhalb dieses Bereichs wird der Job nicht ausgeführt. |
| **Fensteranfang** / **Fensterende** | Zeitfenster für den Job (z. B. 09:00 bis 17:00 Uhr). Die Werte werden aus validierten Zeitlisten ausgewählt. |
| **Intervallminuten** | Optionales wiederholtes Intervall innerhalb des aktiven Fensters. Wenn festgelegt, wird der Job alle N Minuten zwischen Fensterstart und -ende ausgeführt. |
| **Feste Zeiten** | Optionale explizite Startzeiten (z. B. 09:30, 12:00, 15:30). Die Werte werden aus validierten Zeitlisten ausgewählt. |
| **Wochentage** | Optionaler Wochentagsfilter. Mit der „Alle“-Taste können Sie alle Wochentage auf einmal auswählen oder löschen. |

Zeitplanberechnungen verwenden die Zeitzone des lokalen Systems. Wenn keine feste Zeit und kein Intervall konfiguriert sind, ist der nächste Lauf der Fensterstart an einem erlaubten Datum.

!!! note
    Scheduler-Jobs unterstützen nur gespeicherte SSH-TCP-Verbindungen. Mosh-Ziele werden als nicht unterstützt blockiert und der Grund wird in das Journal geschrieben.

### Sitzungsjournal pro Ausführung

Der einklappbare **Session-Journal pro Lauf**-Bereich am unteren Rand des **Job**-Tabs zeichnet ein vollständiges [Session-Journal](session-journal.md) für jeden Lauf des Jobs auf – ein Journal pro Zielserver, mit jedem Befehl, den der Job gesendet hat, dessen Ausgabe, dem Zusammenfassung des Jobs und dem Ergebnis auf diesem Server. Im Gegensatz zur kurzen Laufhistorie im **Journal**-Tab behält er die komplette Ausgabe bei, kann von der KI zusammengefasst, durchsucht, exportiert und [gestellte Fragen](session-journal.md#die-ki-nach-einem-journal-fragen) wie jedes interaktive Journal behandelt werden, und wird automatisch gelöscht, sobald seine Aufbewahrungsfrist endet. SFTP- und rsync-Aktionen senden keinen Shell-Befehl; ihr Journal protokolliert stattdessen die Aktion und deren Ergebnis.

| Setting | Beschreibung |
|---------|-------------|
| **Für jeden Lauf ein Session-Journal erstellen** | Schaltet die Journale für diesen Job ein. |
| **KI-Zusammenfassungen** | **Aus (nur Protokoll, keine Tokens)**, **Für jeden behaltenen Lauf** (Standard) oder **Nur bei fehlgeschlagenen Läufen**. Die Zusammenfassungen laufen einmal, nachdem der Lauf beendet ist, sodass ein Lauf, dessen Journal verworfen wird, keine Tokens kostet. Sie müssen außerdem KI-Zusammenfassungen in *Einstellungen → Logs → Sitzungsjournal* aktivieren. |
| **KI-Profil** | Das Profil für die Zusammenfassungen. **Aus den Einstellungen** verwendet das *KI-Profil für Automations-Journale* (siehe [Session-Journal](session-journal.md#journale-der-automatisierungsdurchlaufe)); wählen Sie ein günstiges oder lokales Profil, um unbeaufsichtigte Läufe erschwinglich zu halten. Die Liste zeigt den Preis pro 1 M Tokens jedes Profils oder dass es lokal läuft. |
| **Journale behalten** | **Immer**, oder **Nur bei Fehler** — das Journal eines erfolgreichen Laufs wird dann sofort gelöscht; fehlgeschlagene, blockierte und abgebrochene Läufe werden behalten. In einem Job mit mehreren Zielen wird die Entscheidung pro Server getroffen. |
| **Automatisch löschen** | Nach einer bestimmten Anzahl von Tagen (Standard 14), an einem festen Datum oder niemals. |
| **Max. Läufe** / **Max. Speicher** | Behalten Sie höchstens diese Anzahl Läufe oder diesen Speicherplatz (MB) der Journale dieses Jobs; die ältesten Läufe werden zuerst gelöscht, und der neueste Lauf wird nie gelöscht. `0` bedeutet unbegrenzt. |
| **Verwerfen eines Laufs, der identisch mit dem vorherigen ist (Bezug behalten)** | Wenn ein Lauf exakt die gleichen Befehle, Ausgaben und Ergebnisse auf einem Server wie der vorher beibehaltenen Lauf dieses Servers zeichnet, wird sein Journal gelöscht und die Laufhistorie verweist stattdessen auf das frühere Journal („= identisch mit einem früheren Lauf“). Das frühere Journal wird dann mindestens so lange aufbewahrt, wie der neue Lauf gewesen wäre. Standardmäßig aktiviert. |
| **Gesendete Befehle aufzeichnen** | Aufzeichnet die Befehle zusätzlich zu ihrer Ausgabe. |

Unter den Einstellungen zeigt eine Statuszeile die Tokens und Kosten des letzten Durchlaufs sowie aller gespeicherten Durchläufe an, ebenso den Speicherplatz, den die Journale des Auftrags verbrauchen. Die Auftragsliste verfügt über eine Spalte **Journal-Tokens** mit der Gesamtsumme pro Auftrag.

!!! warning "Session-Journale von Automatisierungen können sehr teuer werden"
    Schalten Sie das Journal ein — und die KI-Zusammenfassungen ein — zeigt zunächst eine Warnung: Das Journal wird bei jedem Lauf und für jeden Server ohne Beobachtung geschrieben, und mit KI-Zusammenfassungen macht jedes gespeicherte Journal mindestens zwei KI-Aufrufe. Die Warnung schätzt die Tokens pro Lauf (aus den früheren Läufen des Jobs oder eine grobe Obergrenze, wenn es keine gibt), pro Tag und pro Monat aus dem Zeitplan und der Anzahl der Ziele, die Kosten in Geld, wenn das KI-Profil einen [Preis pro 1 M Tokens](../reference/settings/ai.md#token-quoten-verwaltung) hat, und was von der Quote des Profils übrig bleibt. Günstigere Optionen: ein lokales KI-Profil, **Nur bei fehlgeschlagenen Läufen**, **Nur bei Fehler**, und das Wegwerfen identischer Läufe. Abbrechen lässt das Journal (oder seine KI-Zusammenfassungen) ausfallen.

Gepinnte Journale (Rechtsklick im Journal-Manager → **Behalten (Pin)**) werden nie automatisch gelöscht. Ein Administrator kann Automationsjournale verbieten oder deren Aufbewahrungszeit, Speicherplatz und Anzahl der Durchläufe begrenzen; die Sektion gibt dann an und ihre Steuerungen sind eingeschränkt — siehe [Unternehmensrichtlinie](../reference/enterprise-policy.md#rulesession-journal).

### Host-Schlüssel, Sudo und Geheimnisse

Die Überprüfung des Hostschlüssels ist standardmäßig sicher. Wählen Sie vor der unbeaufsichtigten SSH/SFTP/Rsync-Ausführung das Ziel aus und klicken Sie auf **Hostschlüssel bestätigen**, damit KorTTY den angehefteten Fingerabdruck und das öffentliche OpenSSH-Schlüsselmaterial speichert.

Das Kontrollkästchen **Hostschlüsselüberprüfung für diesen Job deaktivieren** deaktiviert die Hostschlüsselüberprüfung nur für den ausgewählten Job. Dies ist unsicher und sollte nur verwendet werden, wenn das Risiko bekannt ist.

Sudo-Passwörter können für einen Server oder für eine Servergruppe gespeichert werden:

- Serverspezifische Sudo-Passwörter werden zuerst verwendet.
- Gruppen-Sudo-Passwörter werden als Fallback verwendet.
- Gespeicherte Sudo-Passwörter werden mit dem Master-Passwort verschlüsselt.
- Wenn das Hauptkennwort gesperrt ist und ein Job SSH-, Sudo-, API- oder Archivgeheimnisse benötigt, wird der Job blockiert und protokolliert.

Bei Rsync-Jobs bedeutet **Sudo verwenden** passwortloses Remote-Sudo nur über `sudo -n rsync`. Gespeicherte Sudo-Passwörter werden von der aktuellen Rsync-Integration nicht verwendet.

### Aktionsregisterkarte: Jobtypen und Konfiguration

Verwenden Sie die Registerkarte **Aktion**, um auszuwählen, was der Job tun soll. Auf der Registerkarte werden nur die Felder angezeigt, die die ausgewählte Aktion verwenden kann. Nicht verwandte Felder werden daher ausgeblendet. Der Aktionsselektor umfasst:

| Aktion | Zweck |
|--------|---------|
| **BEFEHL** | Führen Sie einen nicht interaktiven Remote-Befehl aus. |
| **SNIPPET_SCRIPT** | Führen Sie ein SnippetManager-Skript auf dem ausgewählten Ziel aus. Das Feld **Snippet-Suche** filtert das Skript-Dropdown nach Snippet-Name, Kategorie, Sprache oder ID; **Snippet-Parameter** übergibt zusätzliche Argumente als einen argv-Wert pro Zeile. |
| **AI_AGENT** | Führen Sie den Headless-Scheduler-KI-Agenten aus. Die unbeaufsichtigte Befehlsausführung erfordert die **automatische Genehmigung von KI-Befehlen** während des Auftrags. |
| **AI_SWARM** | Führen Sie das aus [KI-Schwarm](ai-swarm.md) kopflos auf jedes ausgewählte Ziel parallel und fassen die Antworten in einer Vergleichstabelle zusammen. |
| **SFTP_UPLOAD** | Laden Sie einen lokalen Pfad auf einen Remote-Pfad hoch. |
| **SFTP_DOWNLOAD** | Laden Sie einen Remote-Pfad auf einen lokalen Pfad herunter. |
| **SFTP_SYNC** | Lokale und Remote-Pfade in der ausgewählten Upload-/Download-Richtung synchronisieren. |
| **SFTP_DELETE** | Einen Remote-Pfad löschen. |
| **SFTP_RENAME** | Benennen Sie einen Remote-Pfad um. |
| **SFTP_MKDIR** | Erstellen Sie ein Remote-Verzeichnis. |
| **SFTP_CHMOD** | Berechtigungen ändern. Es werden numerische Modi wie `755` und symbolische Modi wie `u+rw,o-w` akzeptiert. |
| **SFTP_CHOWN** | Besitzer und/oder Gruppe ändern. Besitzer- und Gruppenschaltflächen können das Ziel abfragen und verfügbare Werte anzeigen. |
| **SFTP_COPY_REMOTE** | Kopieren Sie einen Remote-Pfad in einen anderen Remote-Pfad auf demselben Ziel. |
| **SFTP_ARCHIVE** | Erstellen Sie ein Remote-Archiv. |
| **RSYNC_SYNC** | Synchronisieren Sie ein oder mehrere Verzeichnisse über externes `rsync` über SSH. |

Pfadfelder bieten eine lokale Finder-/Explorer-Auswahl, wenn der Pfad lokal ist, und das Durchsuchen von Remote-Verzeichnissen, wenn der Pfad remote ist. Remote-Browsing erfordert eine ausgewählte Ziel- und Hostschlüsselüberprüfung, es sei denn, der Job deaktiviert die Hostschlüsselüberprüfung ausdrücklich.

#### Virtuelles Terminal und Screenshots

**COMMAND** und **SNIPPET_SCRIPT** Aktionen können in einem unsichtbaren **virtual terminal** ausgeführt werden: der Befehl erhält ein Pseudo-Terminal auf dem Server, korTTY interpretiert dessen Ausgabe lokal mit demselben Terminal-Emulator, den ein Tab verwendet, und Screenshots dieses Bildschirms werden dem Lauf hinzugefügt. [Sitzungsjournal](#sitzungsjournal-pro-ausfuhrung)Kein Fenster öffnet sich und kein Terminal, in dem Sie arbeiten, wird berührt, sodass Fortschrittsbalken, `top` oder KI-Coding-Tools im nicht-interaktiven Modus (zum Beispiel `claude -p …` oder `codex exec …`) werden im Journal sichtbar.

| Setting | Beschreibung |
|---------|-------------|
| **In einem unsichtbaren virtuellen Terminal (PTY) ausführen und Screenshots für das Sitzungsjournal aufnehmen** | Schaltet das virtuelle Terminal für diese Aktion ein. Benötigt das Sitzungsjournal des Jobs; ohne es läuft der Befehl wie üblich. |
| **Größe** | Spalten × Zeilen des virtuellen Terminals (Standard 120 × 40). |
| **Screenshot alle … s (0 = aus)** / **und bei Bildschirmänderung** | Ein Screenshot wird in festem Intervall (Standard 10 s) und/oder jedes Mal, wenn sich der Bildschirm geändert hat, höchstens alle zwei Sekunden aufgenommen. Ein unveränderter Bildschirm wird nie zweimal erfasst; der letzte Bildschirm wird immer erfasst. |
| **Max. Screenshots pro Befehl** | Obergrenze pro Befehl, einschließlich des letzten Bildschirms (Standard 30). |
| **Laufzeit-Limit … s (0 = keins)** / **Erreichen des Limits als Erfolg werten** | Stoppt den Befehl mit Ctrl+C nach dieser Anzahl Sekunden — für Monitore wie `top`, die nie von selbst beenden. Ohne die zweite Option zählt ein durch das Limit gestoppter Lauf als abgebrochen. |

Die Laufhistorie behält den letzten Bildschirm als Ausgabe bei. Die KI beschreibt die Screenshots im Abschlussdurchlauf, wenn der KI-Modus des Journals angewendet wird (bei jedem gespeicherten Lauf oder nur bei fehlgeschlagenen Läufen), mit dem KI-Profil des Journals – so zählt die Kostenwarnung die Screenshots in ihrer Schätzung.

!!! note "Ein Job kann kein Programm ausführen."
    Nichts wird in das Programm eingegeben, außer was der Befehl selbst sendet. Interaktive Programme (Midnight Commander, ein Editor, ein Prompt, der auf eine Antwort wartet) laufen daher nur bis zur Laufzeitbegrenzung – Sie erhalten ihre Screenshots, aber der Job kann sie nicht nutzen. Ein Befehl, der ein gespeichertes sudo-Passwort sendet, läuft immer **ohne** das virtuelle Terminal: ein Passwort, das an ein Pseudo-Terminal gesendet wird, kann auf dem Bildschirm wiedergegeben werden und würde in einem Screenshot landen.

#### Snippet-Skriptjobs

Snippet-Skriptjobs verwenden den ausgewählten SnippetManager-Eintrag, ohne dass eine geöffnete Terminalregisterkarte erforderlich ist. KorTTY löst integrierte Snippet-Variablen und gespeicherte SnippetManager-Variablen vor der Ausführung auf. Fehlende Snippets, fehlende gespeicherte Variablenwerte und nicht unterstützte Snippet-Sprachen blockieren den Job und schreiben den Grund in das Journal. Zusätzliche Snippet-Parameter werden einzeln pro Zeile eingegeben, sodass Werte mit Leerzeichen als einzelne Skriptargumente übergeben werden.

#### AI Schwarmjobs

AI Swarm-Jobs führen über Hintergrund-SSH-Sitzungen eine KI-Agent-Eingabeaufforderung auf **allen ausgewählten Zielen parallel** aus – es werden keine Terminal-Registerkarten geöffnet. Über die gemeinsamen Felder **KI-Profil**, **KI-Eingabeaufforderung** und **Automatisch genehmigende KI-Befehle** hinaus gelten zwei schwarmspezifische Felder:

| Feld | Beschreibung |
|-------|-------------|
| **Schwarmparallelität** | Wie viele Ziele gleichzeitig ausgeführt werden (1–16, Standard 4). |
| **Schwarm schreibgeschützt** | Beschränkt jeden Agenten auf nicht mutierende Befehle. Standardmäßig aktiviert. |

Die Ergebnisse werden zweimal gespeichert: Das **Journal** zeichnet das Laufergebnis auf, und die vollständige Konversation – einschließlich der kombinierten Vergleichstabelle pro Server – wird als **Schwarm-Chat** gespeichert, der über den Abschnitt *Schwarm-Chats* des KI-Managers erneut geöffnet werden kann.

Der schnellste Weg, einen KI-Swarm-Job zu erstellen, ist der **Planen…**-Button im [KI-Swarm Tab](ai-swarm.md#schwarmlaufe-planen-jobscheduler): er füllt einen neuen Job mit den aktuellen Zielen, Prompt, KI-Profil und der schreibgeschützten Einstellung des Tabs vor. Siehe diese Seite für empfohlene Swarm-/Scheduler-Verwendungsszenarien.

!!! warning
    Ein geplanter Schwarm, bei dem **Schwarm schreibgeschützt** deaktiviert und **Automatisch genehmigende KI-Befehle** aktiviert ist, verändert Systeme unbeaufsichtigt. Testen Sie die Eingabeaufforderung interaktiv auf der Registerkarte „AI Swarm“, bevor Sie einen solchen Job aktivieren.

#### SFTP-Archivierungsjobs

SFTP-Archivierungsjobs unterstützen ZIP, passwortgeschütztes ZIP, TAR und TAR.BZ2. Archivquellen und Ausschlussmuster akzeptieren einen Pfad oder ein Muster pro Zeile. Das Archiv kann optional nach der Erstellung heruntergeladen werden.

Wenn **Sudo-Staging für SFTP-Pfade verwenden** aktiviert ist, stellt KorTTY Dateien an einem temporären Speicherort bereit und verwendet Sudo-unterstützte Remote-Befehle wie `mv`, `cp`, `tar`, `chmod` und `chown`, wenn erhöhte Rechte erforderlich sind. Bereinigungsfehler werden in das Journal geschrieben.

## Rsync-Jobs

`RSYNC_SYNC` unterstützt den Upload und Download zwischen dem lokalen Dateisystem und gespeicherten SSH-TCP-Verbindungen.

- **Upload**: Lokale Quellverzeichnisse werden unter dem Remote-Zielstamm synchronisiert.
- **Download**: Remote-Quellverzeichnisse werden unter dem lokalen Zielstamm synchronisiert.
- **Mehrere Quellen** werden unterstützt.
- **Fehlende Dateien löschen** fügt `--delete` hinzu; es ist standardmäßig deaktiviert.
- Bei Downloads von mehreren Gruppenzielen schreibt KorTTY jedes Ziel in sein eigenes Unterverzeichnis unterhalb des lokalen Zielstammverzeichnisses, um ein Überschreiben von Dateien von einem anderen Server zu vermeiden.

KorTTY erstellt die Rsync-Ausführung als `ProcessBuilder`-Argumentliste, anstatt den Befehl per Shell zu verketten. Der Befehl verwendet `-a --itemize-changes`; `--delete` wird nur hinzugefügt, wenn das Job-Kontrollkästchen aktiviert ist.

### Rsync-Voraussetzungen

- `rsync` wird von `PATH` übernommen, es sei denn, unter **Einstellungen > SFTP > JobScheduler Rsync** ist ein expliziter Binärpfad konfiguriert.
- `ssh` muss in `PATH` verfügbar sein.
- Das Fixieren des Hostschlüssels ist erforderlich, es sei denn, der Job deaktiviert die Hostschlüsselüberprüfung ausdrücklich.
- Passwort- und privater-Schlüssel-Authentifizierung verwenden einen temporären, nur für den Benutzer bestimmt `SSH_ASKPASS`Hilfeprozess. Geheime Informationen, Pfade der Hilfeprozesse und temporäre Pfade von Geheimdateien werden vor der Protokollierung gelöscht.

## Journal-Tab

Der **Journal** Tab listet Jobläufe mit lokalen KorTTY-Zeitstempeln, Status, Jobname, Session-Journal und Zusammenfassung.  

Für Jobs mit einem [Session-Journal pro Lauf](#sitzungsjournal-pro-ausfuhrung) zeigt die Spalte **Session-Journal** an, wie viele Journale der Lauf behalten hat und deren KI-Tokens sowie Kosten. „= identisch mit einem früheren Lauf“ erscheint, wenn der Lauf als Duplikat verworfen wurde, oder „gelöscht (Aufbewahrung)“, sobald die Journale verschwunden sind. Der Detailbereich listet jedes Journal mit seinem Status, Tokenaufteilung und Löschdatum auf, und **Session-Journal öffnen** öffnet die Journale des Laufs (oder der identischen früheren Lauf) im Journal-Viewer.

Die Suchzeile kann mit allen persistenten Journalfeldern oder nur mit ausgewählten Spalten wie Status, Job, Zusammenfassung, Stdout, Stdderr und Detail übereinstimmen. Geben Sie mehrere durch Leerzeichen getrennte Begriffe ein, wenn jeder Begriff irgendwo im ausgewählten Suchbereich vorkommen muss; Verwenden Sie `*` innerhalb eines Begriffs als Platzhalter, zum Beispiel `backup*fail`.

Wenn Sie eine Zeile auswählen, werden stdout, stderr und Detailtext angezeigt. Verwenden Sie **Ausgewählte löschen**, um ausgewählte Journaleinträge zu entfernen. Standardmäßig löscht KorTTY automatisch Planer-Journaleinträge, die älter als 14 Tage sind; Legen Sie den Aufbewahrungswert auf `0` fest, um Einträge auf unbestimmte Zeit aufzubewahren.

Der Protokoll-/Detailbereich unterhalb der Tabelle ist durch einen vertikalen Splitter getrennt. Ändern Sie die Größe, um der Ausgabe mehr oder weniger Höhe zu verleihen. KorTTY speichert diese Teilerposition in den globalen Einstellungen. Markieren Sie im Detailtextbereich den Text und klicken Sie mit der rechten Maustaste, um den ausgewählten Text in die Zwischenablage zu kopieren.

Zu den Journalstatus gehören erfolgreiche, fehlgeschlagene, blockierte, abgebrochene und ausgeführte/Systemeinträge. Gründe wie gesperrtes Master-Passwort, fehlende Hostschlüssel-PIN, fehlender `rsync`/`ssh`, nicht unterstütztes Mosh-Ziel oder Shutdown-Drain werden als Journaldetails geschrieben.

## Menüleistenstatus und Stornierung

Wenn **Jobstatus in Menüleiste anzeigen** aktiviert ist, zeigt KorTTY den Scheduler-Status nach **Hilfe** nur an, wenn ein aktivierter Scheduler-Eintrag vorhanden ist oder ein Job gerade ausgeführt wird. Der Status zeigt den laufenden Auftrag, den Abbruchstatus oder den nächsten Auftrag mit einem Live-Countdown an.

Klicken Sie auf das Statusmenü, um Folgendes anzuzeigen:

- **JobScheduler öffnen...**
- Jobs ausführen und Einträge abbrechen
- bis zu fünf nächste Aufträge in der Warteschlange mit Startzeit und Live-Countdown

Klicken Sie mit der rechten Maustaste auf die Statusbezeichnung, um ein kompaktes Menü mit Abbruchaktionen für laufende Jobs und einer Verknüpfung zum Öffnen von JobScheduler anzuzeigen. Stornierungsanfragen werden protokolliert und die Ausführung von SSH/Rsync-Arbeiten wird nach Möglichkeit sauber unterbrochen.

## KorTTY wird während der Ausführung von Jobs beendet

Wenn KorTTY kurz vor dem Beenden steht, während JobScheduler-Jobs ausgeführt werden, wird eine Warnung mit den aktiven Jobnamen angezeigt. Wenn Sie **Abbrechen** wählen, läuft KorTTY weiter. Wenn Sie **Warten und beenden** auswählen, wird der Shutdown-Drain-Modus gestartet:

- neue geplante oder manuelle Jobstarts sind blockiert;
- Die Wartezeit beim Herunterfahren wird in das Journal geschrieben.
- KorTTY wartet darauf, dass laufende Jobs abgeschlossen oder abgebrochen werden;
- KorTTY wird automatisch beendet, nachdem der Entleerungsvorgang abgeschlossen ist.

## Sicherheit und Geheimnisse

- Host-Schlüssel sind standardmäßig fixiert, um Man-in-the-Middle-Angriffe auf die unbeaufsichtigte Ausführung zu verhindern.
- Sudo-Passwörter werden verschlüsselt mit dem Master-Passwort gespeichert.
- SSH-Schlüsselpassphrasen und Archivpasswörter werden verschlüsselt gespeichert.
- KorTTY geschwärzt verwaltete Geheimnisse (Passwörter, Passphrasen, Archivanmeldeinformationen) aus der Journalausgabe vor der Persistenz.
- Wenn das Hauptkennwort gesperrt ist, wenn ein Job SSH-, Sudo-, API- oder Archivgeheimnisse benötigt, wird der Job blockiert.

## Fehlerbehebung

!!! warning
    **JobScheduler-Job ist blockiert:** Öffnen Sie **Extras > JobScheduler... > Journal** und überprüfen Sie den Detailtext des ausgewählten Eintrags. Häufige Ursachen sind:
    - Master-Passwort gesperrt
    - Fehlende Hostschlüssel-PIN
    - Nicht unterstütztes Mosh-Ziel
    - Fehlendes `rsync` oder `ssh` im PATH
    - Alte Hostschlüssel-PIN ohne OpenSSH-Public-Key-Material für Rsync

    **JobScheduler Rsync kann nicht gestartet werden:** Überprüfen Sie die lokalen Werte `rsync --version` und `ssh -V` oder konfigurieren Sie den Rsync-Binärpfad unter **Einstellungen > SFTP > JobScheduler Rsync**.

    **JobScheduler-Remotebrowser kann nicht geöffnet werden:** Wählen Sie genau ein Ziel aus und bestätigen Sie zuerst den Hostschlüssel, es sei denn, der Job deaktiviert die Hostschlüsselüberprüfung ausdrücklich.

---
