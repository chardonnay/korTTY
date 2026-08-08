---
title: KI-Schwarm
---

# KI-Schwarm

Der KI-Schwarm sendet eine KI-Agentenaufgabe gleichzeitig an viele Server. Jeder ausgewählte Server erhält seinen eigenen unabhängigen Agenten, der über seine SSH-Sitzung ausgeführt wird, und die Antworten pro Server werden in einer einzigen Vergleichstabelle – einer Zeile pro Server – in einer gemeinsamen Konversation zusammengefasst.

Öffnen Sie es mit **Tools > AI Swarm...** oder ++ctrl+alt+s++ (Cmd unter macOS). Der Schwarm wird als normaler Tab geöffnet, sodass Terminals während der Ausführung eines Schwarms nutzbar bleiben.

## Der Schwarm-Tab

Die Registerkarte ist in drei Bereiche unterteilt:

| Bereich | Zweck |
| --- | --- |
| **Statusleiste** | Animierte Kugel pro Agent über dem Gespräch – Live-Statusübersicht |
| **Agenten** | Eine Zeile pro Server mit Status-Badge, verstrichener Zeit, Token-Anzahl und einem erweiterbaren Live-Transkript |
| **Gespräch** | Der gemeinsame Chat: Ihre Eingabeaufforderung, der Fortschritt pro Lauf und die kombinierte Antwort |

Der Composer unten (*Alle ausgewählten Server fragen…*) ist eine klar umrahmte, dreizeilige Eingabe. **Senden** startet einen Lauf auf jedes ausgewählte Ziel; Folgeaufforderungen setzen das gleiche Gespräch fort.

### Ziele auswählen

**Server auswählen…** öffnet eine Auswahl für Ihre gespeicherten Verbindungen. Die Zielübersicht daneben zeigt, wie viele Server ausgewählt sind, wie viele bereits über ein offenes Terminal verfügen (*Offen: n*) und wie viele ohne eines laufen (*Ohne Terminal: n*).

Server **ohne offenes Terminal werden vollständig unterstützt**: Der Schwarm öffnet bei Bedarf eine Hintergrund-SSH-Sitzung für sie – es ist kein Terminal-Tab geöffnet oder erforderlich. Dazu muss der Master-Passwort-Tresor entsperrt werden (die gespeicherten Anmeldeinformationen werden verwendet) und der Serverschlüssel wird beim ersten Kontakt akzeptiert, das gleiche Vertrauensmodell, das das Terminal für neue Verbindungen verwendet. **Connect fehlt (n)** bleibt als explizite Option verfügbar, wenn Sie stattdessen Terminals öffnen *möchten*. **Lokale Shell einschließen** fügt Ihren lokalen Computer zum Schwarm hinzu (lokale Shells benötigen immer ihre offene Registerkarte und sind von Headless-Läufen ausgeschlossen).

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

**Adaptive langsame Erkennung** – ein Agent wird als *ungewöhnlich lange* gekennzeichnet, wenn seine verstrichene Zeit `max(60 s, 2 × median of the finished agents)` überschreitet; Bis mindestens zwei Agenten fertig sind, gilt ein fester Schwellenwert von 180 Sekunden. Angehaltene und wartende Agenten werden nie gekennzeichnet und die angehaltene Zeit wird von der verstrichenen Zeit abgezogen, damit der Vergleich fair bleibt.

Während ein Lauf aktiv ist, wird durch **Klicken auf eine Kugel** zur Zeile dieses Agenten in der Agentenliste gescrollt und diese hervorgehoben. Beim Schweben werden der Servername und die verstrichene Zeit angezeigt. Legendenchips unter den Kugeln fassen die Zählungen zusammen (läuft, wartet, pausiert, erledigt, fehlgeschlagen). Nach dem Lauf friert der Streifen im Endzustand ein.

Der Streifen lässt sich von einem einzelnen Server bis hin zu großen Flotten skalieren – die Kugeln schrumpfen und packen sich in Reihen, wenn die Anzahl der Agenten wächst:

![Status strip with all states](../assets/screenshots/ai/swarm-status-strip-states.png)

![Status strip with 50 agents](../assets/screenshots/ai/swarm-status-strip-many.png)

## Agentenzeilen und Live-Transkripte

Jeder Server verfügt über eine Zeile in der **Agents**-Liste, in der sein Status-Badge, die verstrichene Zeit und die Token-Anzahl angezeigt werden. **Klicken Sie mit der linken Maustaste auf eine Zeile**, um sie inline zu erweitern und das Live-Transkript des Agenten (Befehle, Ausgabe und Fortschritt) während der Ausführung anzusehen – kein zusätzliches Fenster erforderlich. Sehr lange Transkripte werden von vorne beschnitten, sodass immer die neueste Ausgabe sichtbar ist.

**Klicken Sie mit der rechten Maustaste auf eine Zeile**, um die Kontrolle pro Agent zu erhalten: **Pause**, **Fortsetzen**, **Neustart** und **Stopp** gelten nur für diesen Agenten. Durch den Neustart eines Agenten werden die anderen nicht gestört. seine Antwort wird im kombinierten Ergebnis ersetzt.

## Steuerung ausführen

Die Symbolleiste bietet die gleichen vier Steuerelemente für den **gesamten Schwarm**: **Pause**, **Fortsetzen**, **Neustart** und **Stop**. Das Pausieren ist kooperativ – jeder Agent pausiert an seinem nächsten sicheren Kontrollpunkt (auf dem Abzeichen wird *Pause…* angezeigt, bis es wirksam wird), und abgelaufene Timer bleiben während der Pause stehen.

## Schreibgeschützter Modus und Genehmigungen

Durch das Kontrollkästchen **Schreibgeschützt** bleibt jeder Agent auf nicht mutierende Befehle beschränkt. Wenn der schreibgeschützte Zugriff deaktiviert ist, entscheidet die **Genehmigungsrichtlinie**, wie systemverändernde Befehle bestätigt werden:

| Politik | Verhalten |
| --- | --- |
| **Eine Genehmigung für alle** | Der erste Agent, der eine Änderung benötigt, löst einen Dialog aus; **Approve on all** deckt jeden Server im Lauf ab |
| **Pro Server** | Die Änderungen jedes Servers werden einzeln genehmigt |

Der Genehmigungsdialog bietet außerdem die Option **Schwarm abbrechen**, um den gesamten Lauf zu stoppen.

## Kombinierte Antwort- und Zeilendetails

Wenn alle Agenten fertig sind, kombiniert der Schwarm die Antworten pro Server in einer Markdown-Vergleichstabelle mit genau einer Zeile pro Server. Die letzte Spalte trägt immer den Titel **"Fehler"** und listet Abweichungen, fehlende Daten und Fehler (oder `-`, wenn es nichts zu melden gibt) auf, unabhängig von der Antwortsprache.

Tabellenzellen sind oft zu klein für eine vollständige Befehlsausgabe – **klicken Sie auf eine beliebige Tabellenzeile**, um sie in einem separaten Fenster *Zeilendetails* mit lesbarem Layout, **A− / A+**-Schriftgrößenschaltflächen und einer Schaltfläche zum Kopieren in die Zwischenablage zu öffnen.

## Konversation kopieren, exportieren und speichern

Der Konversationskopf verfügt über eine Schaltfläche **Kopieren** (gesamte Konversation in die Zwischenablage) und ein Menü **Exportieren** mit **Nur-Text**, **Markdown** und **PDF**. **Speichern** speichert die Konversation als benannten Schwarm-Chat; Gespeicherte Schwarm-Chats werden in einem speziellen Abschnitt **Schwarm-Chats** des [AI Manager](ai-assistant.md#ai-manager)] angezeigt und können später wieder geöffnet werden.

## Skripte ohne KI ausführen

**Skript ausführen…** führt ein Snippet-Manager-Skript auf **allen Schwarmzielen parallel aus – ohne KI-Beteiligung**. Der Dialog bietet eine durchsuchbare Skriptauswahl (nach Name, Kategorie, Sprache oder ID), ein Parameterfeld (ein Parameter pro Zeile) und eine Live-Zusammenfassung; Die Schaltfläche **Ausführen** ist die einzige Bestätigung.

Das Skript wird Base64-kodiert übertragen (keine Probleme mit Anführungszeichen oder Sonderzeichen) und auf dem Server dekodiert, wobei die Parameter als `$1`, `$2`, … übergeben werden. Der Fortschritt wird in denselben Agentenzeilen angezeigt – erweitern Sie eine Zeile, um die Live-Ausgabe anzusehen – und das Ergebnis ist eine Tabelle pro Server mit Exit-Code und Ausgabe. Nicht-POSIX-Shells (z. B. Windows-Ziele) werden mit dem Hinweis *Skipped: Shell is not POSIX* übersprungen, während der Rest des Schwarms weiterläuft; Nicht erreichbare Server werden als *Nicht verbunden* gemeldet. **Stop** bricht eine laufende Skriptausführung ab.

## Generieren Sie einen Multi-Server-Workflow

Die Schaltfläche **Workflow** verwandelt die aktuelle Schwarmaufgabe über das Dialogfeld **Multiserver-Workflow generieren** in ein einzelnes wiederverwendbares Multi-Server-Skript: Wählen Sie die Skriptsprache, die Hostlistenquelle (ausgewählte Verbindungen, manuelle Liste oder externe Hostdatei/Inventar) und Multi-Server-Härtungsoptionen (paralleles Fanout, pro-Host-Timeout, Wiederholung mit Backoff, aggregierter End-of-Run-Bericht, Jump-Host, sudo/become, Trockenlauf und mehr).

Der Dialog beinhaltet:

- **Syntaxhervorhebung** – das generierte Skript wird in einem vollständigen Editor mit Hervorhebung für die ausgewählte Sprache angezeigt.
- **Sichtbarer Fortschritt** – eine funktionierende Animation mit einem Live-Ablaufzähler (*Generierung… 0:42*), während die KI arbeitet, und der Gesamtdauer (*Fertig – hat 1:37 gedauert*), wenn sie fertig ist.
- **Zusätzliche Anweisungen** – ein dreizeiliges Feld für zusätzliche Anweisungen, denen die KI folgen muss, mit einem **Verlauf**-Menü Ihrer letzten 10 einzelnen Einträge.
- **In Snippets speichern** – speichert das Skript im Snippet-Manager mit einem passenden, vorab ausgefüllten Skriptnamen und der richtigen Dateierweiterung.
- **Härtungsoptionen** – dieselben pro Skript [Härtungsoptionen](../reference/hardening-options.md) wie der Single-Host-Workflow-Generator (strikter Modus, Fehlerfallen, Idempotenz, Probelauf, `--help` und mehr) werden automatisch mit ihren All-On-Standardwerten auf das generierte Skript angewendet; In diesem Dialogfeld wird kein Bereich für sie angezeigt. Sie unterscheiden sich von den oben genannten Multiserver-Optionen.
- **Eingabe-Härtung** – ein zusammenklappbares [Eingabe-Härtung](../reference/input-hardening.md)-Panel fordert die KI auf, einen Eingabevalidierungs-Schutzblock in das generierte Skript einzubauen (Parameter-Zulassungslisten und Längenbeschränkungen, Dateiformatprüfungen, ein einstellbares `MAX_FILE_SIZE`-Limit, Sicherheitswarnungen im Protokoll des Skripts und eine `KORTTY_FORCE=1`-Überschreibung). Die Größenprüfung verwendet Metadaten, bevor der Dateiinhalt gelesen wird, und `0` bedeutet unbegrenzt. Streng opt-in – das Master-Kontrollkästchen ist zu Beginn deaktiviert.

## Tab-Aktivitätsanzeige

Auf der Registerkarte „AI Swarm“ selbst wird ein farbiger Statuspunkt angezeigt, sodass Sie den Fortschritt von jeder anderen Registerkarte aus verfolgen können:

| Punkt | Bedeutung |
| --- | --- |
| Blau, pulsierend | Schwarm läuft |
| Bernsteinfarbener, schneller Puls | Ein Agent **wartet auf Ihre Eingabe** |
| Violett, stetig | Schwarm ist pausiert |
| Grün, stetig | Lauf beendet – bleibt bis zum Start des nächsten Laufs |

## Schwarmläufe planen (JobScheduler)

Schwarmläufe können unbeaufsichtigt als [JobScheduler](jobscheduler.md)-Jobs mit dem Aktionstyp **AI Swarm** ausgeführt werden. Die Schaltfläche **Schedule…** in der Swarm-Symbolleiste ist der schnellste Weg: Sie öffnet den JobScheduler mit einem neuen Job, der auf der aktuellen Registerkarte vorab ausgefüllt ist – die ausgewählten Server, die aktuelle Eingabeaufforderung, das KI-Profil und die schreibgeschützte Einstellung. Der Job wird deaktiviert erstellt, sodass Sie den Zeitplan überprüfen können, bevor Sie ihn aktivieren.

Geplante Schwarmjobs laufen völlig kopflos über SSH-Hintergrundsitzungen – es werden keine Terminal-Registerkarten geöffnet. Schwarmspezifische Berufsfelder:

| Feld | Beschreibung |
| --- | --- |
| **AI-Profil** | Das AI-Profil, das für alle Agenten im Lauf verwendet wird |
| **KI-Eingabeaufforderung** | Die Aufgabe wird an jeden Zielserver gesendet |
| **Automatisch genehmigen** | Genehmigen Sie systemverändernde Befehle ohne Dialog (bei unbeaufsichtigten Ausführungen muss niemand gefragt werden) |
| **Schwarmparallelität** | Wie viele Server laufen gleichzeitig (1–16, Standard 4) |
| **Schwarm schreibgeschützt** | Alle Agenten auf nicht mutierende Befehle beschränken (Standard: Ein) |

Die Ergebnisse landen an **zwei Stellen**: Das Job-**Journal** zeichnet das Ergebnis pro Lauf auf, und die vollständige Konversation – einschließlich der kombinierten Vergleichstabelle – wird als **gespeicherter Schwarm-Chat** gespeichert, sodass Sie ihn später im Abschnitt *Schwarm-Chats* des AI Managers öffnen und wie bei einem interaktiven Lauf durch die Ergebnistabelle klicken können. Die Master-Passwort- und Host-Key-Gates des Schedulers gelten wie für andere Jobtypen.

!!! tip "Empfohlener Arbeitsablauf: Interaktiv optimieren, dann planen"
    Pünktliche Qualität entscheidet über die Ergebnisqualität. Führen Sie den Schwarm zunächst interaktiv aus, verfeinern Sie die Eingabeaufforderung, bis die Vergleichstabelle richtig aussieht, und klicken Sie dann auf **Planen…** – die abgestimmte Eingabeaufforderung und die Zielliste werden in den Job übernommen.

Typische Kombinationen von Schwarm + Scheduler:

- **Nächtlicher Flottenzustandsbericht** – eine schreibgeschützte Eingabeaufforderung wie *„Festplattennutzung, ausgefallene Systemeinheiten und ausstehende Sicherheitsupdates melden“* für alle Produktionsserver jede Nacht; Sehen Sie sich jeden Morgen die kombinierte Tabelle vom AI-Manager an.
- **Erkennung von Konfigurationsdrifts** – Abfrage der effektiven Einstellungen eines Dienstes auf jedem Host; Abweichungen fallen in den Zeilen pro Server und in der Spalte *Fehler* auf.
- **Bestandsaufnahme auf Patch-Ebene** – Sammeln Sie Kernel- und Paketversionen in der gesamten Flotte in einem wöchentlichen Zeitplan und exportieren Sie die resultierende Tabelle.

!!! warning "Unbeaufsichtigte Änderungen"
    Ein geplanter Schwarm mit **Schreibgeschützt aus** und **Auto-Genehmigung ein** ändert Systeme, ohne dass jemand zuschaut. Halten Sie geplante Schwärme schreibgeschützt, es sei denn, die Eingabeaufforderung ist absichtlich darauf ausgelegt (und interaktiv getestet), Änderungen vorzunehmen.
