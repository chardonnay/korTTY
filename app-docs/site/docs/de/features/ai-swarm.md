---
title: KI-Schwarm
---

# KI-Schwarm

Der KI-Schwarm sendet eine KI-Agentenaufgabe gleichzeitig an viele Server. Jeder ausgewählt
Der Server erhält seinen eigenen unabhängigen Agenten, der über seine SSH-Sitzung ausgeführt wird, und der
Die Antworten pro Server werden in einer einzigen Vergleichstabelle zusammengefasst – eine Zeile pro Server
Server – in einem gemeinsamen Gespräch.

Öffnen Sie es mit **Tools > AI Swarm...** oder ++Strg+Alt+S++ (Cmd unter macOS). Der Schwarm
Wird als normale Registerkarte geöffnet, sodass Terminals während der Ausführung eines Schwarms nutzbar bleiben.

## Der Schwarm-Tab

Die Registerkarte ist in drei Bereiche unterteilt:

| Bereich | Zweck |
| --- | --- |
| **Statusleiste** | Animierte Kugel pro Agent über dem Gespräch – Live-Statusübersicht |
| **Agenten** | Eine Zeile pro Server mit Status-Badge, verstrichener Zeit, Token-Anzahl und einem erweiterbaren Live-Transkript |
| **Gespräch** | Der gemeinsame Chat: Ihre Eingabeaufforderung, der Fortschritt pro Lauf und die kombinierte Antwort |

Der Composer unten (*Alle ausgewählten Server fragen…*) ist ein klar umrahmter,
dreizeilige Eingabe. **Senden** startet einen Lauf auf jedes ausgewählte Ziel; nachverfolgen
Aufforderungen setzen das gleiche Gespräch fort.

### Ziele auswählen

**Server auswählen…** öffnet eine Auswahl für Ihre gespeicherten Verbindungen. Das Ziel
Die Übersicht daneben zeigt, wie viele Server ausgewählt sind und wie viele bereits über einen verfügen
offenes Terminal (*Offen: n*) und wie viele davon ohne eines laufen (*Ohne
Terminal: n*).

Server **ohne offenes Terminal werden vollständig unterstützt**: Der Schwarm öffnet a
Hintergrund-SSH-Sitzung für sie bei Bedarf – es wird kein Terminal-Tab geöffnet oder
erforderlich. Dazu muss der Master-Passwort-Tresor entsperrt werden (das gespeicherte Passwort).
Anmeldeinformationen werden verwendet) und der Serverschlüssel wird beim ersten Kontakt akzeptiert
Vertrauensmodell, das das Terminal für neue Verbindungen verwendet. **Verbindung fehlt (n)**
bleibt als explizite Opt-in-Option verfügbar, wenn Sie stattdessen die Öffnung von Terminals *wollen*.
**Lokale Shell einschließen** fügt Ihren lokalen Computer zum Schwarm hinzu (lokale Shells).
benötigen immer ihren offenen Tab und sind von Headless-Läufen ausgeschlossen.

## Statusstreifen

Eine Kugel pro Agent, gefärbt und animiert nach Bundesstaat:

![Swarm status strip states](../assets/diagrams/swarm-status-strip.svg)

| Staat | Kugel | Bedeutung |
| --- | --- | --- |
| **In der Warteschlange** | grau | Warten auf einen freien Slot |
| **Laufen** | blau, pulsierend mit einem umlaufenden Punkt | Agent arbeitet; verstrichene Zeit tickt |
| **Warte auf Eingabe** | bernsteinfarben, blinkender Ring | Ein Genehmigungsdialog erwartet Sie |
| **Pausiert** | violett mit Pausenbalken | Über die Laufsteuerung angehalten; der Timer stoppt |
| **Ungewöhnlich lang** | blau mit einem bernsteinfarbenen Ping-Ring | Läuft viel länger als seine Mitbewerber (siehe unten) |
| **Fertig** | grün | Antwort gesammelt |
| **Fehlgeschlagen** | rot | Der Lauf ist fehlerhaft; Einzelheiten finden Sie in der Agentenzeile |
| **Abgebrochen / Übersprungen** | dunkelgrau | Angehalten oder übersprungen (z. B. nicht unterstützte Shell) |

**Adaptive Langsamerkennung** – ein Agent wird *ungewöhnlich lange* markiert, wenn er aktiv ist
verstrichene Zeit überschreitet `max(60 s, 2 × median of the finished agents)`; bis um
Wenn mindestens zwei Agenten fertig sind, gilt ein fester Schwellenwert von 180 Sekunden. Angehalten und
Wartende Agenten werden nie markiert und die Pausenzeit wird von der verstrichenen abgezogen
Zeit, so dass der Vergleich fair bleibt.

Während ein Lauf aktiv ist, wird durch **Klicken auf eine Kugel** zu dieser gescrollt und diese hervorgehoben
Zeile des Agenten in der Agentenliste; Beim Schweben werden der Servername und die verstrichene Zeit angezeigt.
Legendenchips unter den Kugeln fassen die Zählungen zusammen (Laufen, Warten, Pause,
erledigt, fehlgeschlagen). Nach dem Lauf friert der Streifen im Endzustand ein.

Der Streifen lässt sich von einem einzelnen Server bis hin zu großen Flotten skalieren – die Kugeln schrumpfen und packen sich
in Zeilen, wenn die Anzahl der Agenten wächst:

![Status strip with all states](../assets/screenshots/ai/swarm-status-strip-states.png)

![Status strip with 50 agents](../assets/screenshots/ai/swarm-status-strip-many.png)

## Agentenzeilen und Live-Transkripte

Für jeden Server gibt es in der **Agents**-Liste eine Zeile mit seinem Statusabzeichen „Abgelaufen“.
Zeit und Tokenanzahl. **Klicken Sie mit der linken Maustaste auf eine Zeile**, um sie inline zu erweitern und anzusehen
Live-Transkript des Agenten (Befehle, Ausgabe und Fortschritt) während der Ausführung – nein
Zusätzliches Fenster erforderlich. Sehr lange Transkripte werden von vorne beschnitten
Die neueste Ausgabe ist immer sichtbar.

**Klicken Sie mit der rechten Maustaste auf eine Zeile**, um die Kontrolle pro Agent zu erhalten: **Pause**, **Fortsetzen**,
**Neustart** und **Stop** gelten nur für diesen Agenten. Der Neustart eines Agenten reicht aus
die anderen nicht stören; seine Antwort wird im kombinierten Ergebnis ersetzt.

## Steuerung ausführen

Die Symbolleiste bietet die gleichen vier Steuerelemente für den **gesamten Schwarm**: **Pause**,
**Fortsetzen**, **Neustart** und **Stop**. Das Pausieren ist kooperativ – jeder Agent
hält an seinem nächsten sicheren Kontrollpunkt an (auf dem Badge steht *Pause…*, bis es dauert).
Effekt), und abgelaufene Timer stoppen, während sie angehalten werden.

## Schreibgeschützter Modus und Genehmigungen

Durch das Kontrollkästchen **Schreibgeschützt** wird sichergestellt, dass jeder Agent nicht mutiert
Befehle. Wenn der schreibgeschützte Zugriff deaktiviert ist, entscheidet die **Genehmigungsrichtlinie** darüber, wie
Systemverändernde Befehle werden bestätigt:

| Politik | Verhalten |
| --- | --- |
| **Eine Genehmigung für alle** | Der erste Agent, der eine Änderung benötigt, löst einen Dialog aus; **Approve on all** deckt jeden Server im Lauf | ab
| **Pro Server** | Die Änderungen jedes Servers werden einzeln genehmigt |

Der Genehmigungsdialog bietet außerdem die Option **Schwarm abbrechen**, um den gesamten Lauf zu stoppen.

## Kombinierte Antwort- und Zeilendetails

Wenn alle Agenten fertig sind, kombiniert der Schwarm die Antworten pro Server zu einer einzigen
Markdown-Vergleichstabelle mit genau einer Zeile pro Server. Die letzte Spalte ist
immer mit **"Fehler"** betitelt und Abweichungen, fehlende Daten und Fehler (bzw
`-`, wenn nichts zu melden ist), unabhängig von der Antwortsprache.

Tabellenzellen sind oft zu klein für eine vollständige Befehlsausgabe – **klicken Sie auf eine beliebige Tabelle
row**, um es in einem separaten *Zeilendetails*-Fenster mit lesbarem Layout zu öffnen,
Schaltflächen für die Schriftgröße **A− / A+** und eine Schaltfläche zum Kopieren in die Zwischenablage.

## Konversation kopieren, exportieren und speichern

Der Konversationsheader verfügt über eine Schaltfläche **Kopieren** (die gesamte Konversation wird in die Kopfzeile verschoben).
Zwischenablage) und ein **Exportieren**-Menü mit **Nur-Text**, **Markdown** und
**PDF**. **Speichern** speichert die Konversation als benannten Schwarm-Chat; geretteter Schwarm
Chats erscheinen in einem speziellen **Swarm Chats**-Bereich des
[AI Manager](ai-assistant.md#ai-manager) und kann später wieder geöffnet werden.

## Skripte ohne KI ausführen

**Skript ausführen…** führt ein Snippet-Manager-Skript auf **allen Schwarmzielen in aus
parallel – ohne KI-Beteiligung**. Der Dialog bietet ein durchsuchbares Skript
Auswahl (nach Name, Kategorie, Sprache oder ID), ein Parameterfeld (ein Parameter).
pro Zeile) und eine Live-Zusammenfassung; Die Schaltfläche **Ausführen** ist die einzige Bestätigung.

Das Skript wird Base64-kodiert übertragen (keine Anführungszeichen oder Sonderzeichen).
Probleme) und auf dem Server dekodiert, wobei die Parameter als `$1`, `$2`, … übergeben werden.
Der Fortschritt wird in denselben Agentenzeilen angezeigt. Erweitern Sie eine Zeile, um den Live-Vorgang anzusehen
Ausgabe – und das Ergebnis ist eine Tabelle pro Server mit Exit-Code und Ausgabe.
Nicht-POSIX-Shells (z. B. Windows-Ziele) werden mit einem *Skipped: Shell übersprungen
nicht POSIX*-Notiz, während der Rest des Schwarms fortschreitet; nicht erreichbare Server sind
als *Nicht verbunden* gemeldet. **Stop** bricht eine laufende Skriptausführung ab.

## Generieren Sie einen Multi-Server-Workflow

Die Schaltfläche **Workflow** verwandelt die aktuelle Schwarmaufgabe in eine einzelne wiederverwendbare Aufgabe
Multi-Server-Skript über den Dialog **Multi-Server-Workflow generieren**: Wählen Sie
die Skriptsprache, die Hostlistenquelle (ausgewählte Verbindungen, manuelle Liste oder
externe Hostdatei/Inventar) und Multi-Server-Härtungsoptionen (parallel).
Fan-Out, Timeout pro Host, Wiederholung mit Backoff, aggregierter End-of-Run-Bericht,
Jump Host, Sudo/Become, Dry-Run und mehr).

Der Dialog beinhaltet:

- **Syntaxhervorhebung** – das generierte Skript wird in einem vollständigen Editor mit angezeigt
Hervorhebung für die ausgewählte Sprache.
- **Sichtbarer Fortschritt** – eine funktionierende Animation mit einem Live-Zähler
(*Generieren… 0:42*), während die KI arbeitet, und die Gesamtdauer (*Fertig – hat gedauert
1:37*), wenn es fertig ist.
- **Zusätzliche Anweisungen** – ein dreizeiliges Feld zur zusätzlichen Anleitung der KI
muss folgen, mit einem **Verlauf**-Menü Ihrer letzten 10 einzelnen Einträge.
- **In Snippets speichern** – speichert das Skript mit einem im Snippet-Manager
passenden, vorausgefüllten Skriptnamen und die richtige Dateierweiterung.
- **Härtungsoptionen** – pro Skript gleich
[Härtungsoptionen](../reference/hardening-options.md) als Einzelhost
Workflow-Generator (strenger Modus, Fehlerfallen, Idempotenz, Trockenlauf, `--help`,
und mehr), die auf das generierte Skript angewendet werden. Diese sind getrennt von der
Multi-Server-Optionen oben.

##Tab-Aktivitätsanzeige

Auf der Registerkarte „AI Swarm“ selbst wird ein farbiger Statuspunkt angezeigt, sodass Sie den Fortschritt verfolgen können
von jeder anderen Registerkarte:

| Punkt | Bedeutung |
| --- | --- |
| Blau, pulsierend | Schwarm läuft |
| Bernsteinfarbener, schneller Puls | Ein Agent **wartet auf Ihre Eingabe** |
| Violett, stetig | Schwarm ist pausiert |
| Grün, stetig | Lauf beendet – bleibt bis zum Start des nächsten Laufs |

## Schwarmläufe planen (JobScheduler)

Schwarmläufe können unbeaufsichtigt als [JobScheduler](jobscheduler.md)-Jobs mit ausgeführt werden
der Aktionstyp **KI-Schwarm**. Die Schaltfläche **Planen…** in der Schwarmsymbolleiste ist
Der schnellste Weg: Er öffnet den JobScheduler mit einem neuen Job, der aus dem vorab ausgefüllt ist
Aktuelle Registerkarte – die ausgewählten Server, die aktuelle Eingabeaufforderung, das AI-Profil und die
schreibgeschützte Einstellung. Der Job wird deaktiviert erstellt, sodass Sie den Zeitplan überprüfen können
bevor Sie es aktivieren.

Geplante Schwarmjobs laufen völlig kopflos über SSH-Hintergrundsitzungen – nein
Terminalregisterkarten werden geöffnet. Schwarmspezifische Berufsfelder:

| Feld | Beschreibung |
| --- | --- |
| **KI-Profil** | Das AI-Profil, das für alle Agenten im Lauf | verwendet wird
| **KI-Eingabeaufforderung** | Die Aufgabe wird an jeden Zielserver gesendet |
| **Automatisch genehmigen** | Genehmigen Sie systemverändernde Befehle ohne Dialog (bei unbeaufsichtigten Ausführungen muss niemand gefragt werden) |
| **Schwarmparallelität** | Wie viele Server laufen gleichzeitig (1–16, Standard 4) |
| **Schwarm schreibgeschützt** | Alle Agenten auf nicht mutierende Befehle beschränken (Standard: Ein) |

Die Ergebnisse landen an **zwei Stellen**: Das Job-Journal** zeichnet die Ergebnisse pro auf
ausgeführt, und die vollständige Konversation – einschließlich der kombinierten Vergleichstabelle – ist
als **gespeicherter Schwarm-Chat** gespeichert, sodass Sie ihn später von der KI aus öffnen können
Gehen Sie zum Abschnitt *Swarm Chats* des Managers und klicken Sie sich wie ein durch die Ergebnistabelle
interaktiver Lauf. Es gelten die Master-Passwort- und Host-Key-Gates des Schedulers
für andere Jobtypen.

!!! Tipp „Empfohlener Arbeitsablauf: Interaktiv optimieren, dann planen“
Pünktliche Qualität entscheidet über die Ergebnisqualität. Führen Sie den Schwarm zunächst interaktiv aus.
Verfeinern Sie die Eingabeaufforderung, bis die Vergleichstabelle richtig aussieht, und klicken Sie dann auf
**Zeitplan…** – die abgestimmte Eingabeaufforderung und Zielliste werden in den Job übernommen.

Typische Kombinationen von Schwarm + Scheduler:

- **Nächtlicher Flottenzustandsbericht** – eine schreibgeschützte Eingabeaufforderung wie *„Datenträger melden“.
Nutzung, ausgefallene Systemd-Einheiten und ausstehende Sicherheitsupdates"* für alle
Produktionsserver jede Nacht; Sehen Sie sich jeden Morgen die kombinierte Tabelle an
der KI-Manager.
- **Erkennung von Konfigurationsabweichungen** – Fragen Sie nach den effektiven Einstellungen von a
Service auf jedem Host; Abweichungen fallen in den Zeilen pro Server auf
Spalte *Fehler*.
- **Bestandsaufnahme auf Patch-Ebene** – Sammeln Sie Kernel- und Paketversionen im gesamten
Flotte nach einem wöchentlichen Zeitplan und exportieren Sie die resultierende Tabelle.

!!! Warnung „Unbeaufsichtigte Änderungen“
Ein geplanter Schwarm mit Änderungen, die **schreibgeschützt aus** und **automatisch genehmigen ein** sind
Systeme, ohne dass jemand zuschaut. Halten Sie geplante Schwärme schreibgeschützt, es sei denn, die
prompt ist bewusst darauf ausgelegt (und interaktiv getestet), Änderungen vorzunehmen.
