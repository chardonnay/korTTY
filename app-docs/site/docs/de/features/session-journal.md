---
title: Sitzungsjournal
---

# Sitzungsjournal

Das Sitzungsjournal dokumentiert eine Terminalsitzung als lesbare Zeitleiste: Jede Ausgabezeile des Servers und jeder von Ihnen eingegebene Befehl werden in ein Capture-Log geschrieben, eine KI komprimiert die Aktivität regelmäßig in kurze Journal-Einträge und das Ergebnis wird als eigenständige HTML-Seite mit Verbindungsdetails, farbcodierten Auszügen, Screenshots und Ihren eigenen Notizen gerendert. Journale werden wie gespeicherte Chats verwaltet – in einem eigenen Managerfenster mit Suche, Bearbeitung und Export.

![Session journal flow](../assets/diagrams/session-journal-flow.svg)

## Speicher und Formate

Jedes Journal ist ein eigenständiges Verzeichnis unter `~/.kortty/journals` (konfigurierbar unter **Einstellungen > Protokollierung > Sitzungsjournal**):

| Datei | Zweck |
|------|---------|
| `journal.xml` | Das kuratierte Journaldokument: Metadaten, KI-Zusammenfassungen, Markierungen, Notizen, Screenshot-Referenzen |
| `session-log.json` / `.xml` / `.yaml` | Das Nur-Anhängen-Capture-Log – zeitgestempelte Serverausgabe und typisierte Eingabezeilen mit Sequenz-IDs |
| `session-log-2.json.zst`, … | Gedrehte Stammteile; Geschlossene Teile werden automatisch zstd-komprimiert, das Journal löscht niemals den Verlauf. Mit älteren Versionen aufgezeichnete Journale behalten ihre `.gz`-Teile und bleiben vollständig lesbar |
| `journal.html` | Die generierte Timeline-Seite, die nach jeder Änderung automatisch neu generiert wird |
| `screenshots/*.png` | die während der Sitzung angehängten Screenshots |

Das Capture-Log-Format kann im Dialogfeld **Optionen** des Journalmanagers ausgewählt werden: **JSON** (JSON Lines, Standard), **XML** oder **YAML**. Alle Formate enthalten die gleichen Felder und jeder Eintrag besteht aus genau einer Zeile, sodass ein Absturz nie mehr als die letzte Zeile beschädigt. JSON ist die Standardeinstellung, weil die Protokolltools es lesen, ohne dass ein eigener Parser erforderlich ist – und nicht, weil es Platz spart. Die Größe trennt die drei kaum voneinander: Bei normaler Ausgabe ist XML etwa 9 Byte pro Eintrag kleiner, bei Ausgabe voller `<`, `>` und `&` ist JSON etwa 10 % kleiner (XML muss diese maskieren, JSON nicht), und sobald ein fertiger Teil komprimiert ist, liegen alle drei innerhalb von 2 % voneinander. YAML ist das größte, da es JSON-Zuordnungen mit dem Präfix `- ` schreibt. Der aktive Protokollteil bleibt für Live-Lesevorgänge unkomprimiert; Rotation (Standard 25 MB pro Teil) und Sitzungsende komprimieren fertige Teile auf `.zst` (zstd – Journale aus älteren Versionen behalten ihre `.gz`-Teile und öffnen sich genau wie zuvor).

Zwei weitere Maßnahmen halten lange, laute Sitzungen klein und vollständig. Aufeinanderfolgende identische Ausgabelinien (Fortschrittszyklen, `tail -f` Wiederholungen) werden syslog-ähnlich zusammengefasst: der erste Vorkommnis wird sofort geschrieben, Folgeschritte werden gezählt und als ein Eintrag mit einer Wiederholungsanzahl gespeichert. Der Viewer zeigt einen solchen Lauf kompakt als `Zeile ×12`, während das Kopieren oder Exportieren des Logs die ursprünglichen Zeilen vollständig reproduziert. Und wenn Serverausgaben schneller eintreffen, als das Log sie speichern kann, wendet die Aufnahme Rückdruck anstelle von Zeilenverlusten an – das Terminal kann sich bei einem extremen Flut kurz verlangsamen, aber das Journal bleibt vollständig.

Die Rotation kann pro Verbindung auf der Registerkarte **Journal** konfiguriert werden: **Maximale Größe pro Log-Teil (MB)** (Standard 25) und **Maximale Anzahl rotierter Log-Teile** (Standard 20). Nach der konfigurierten Anzahl von Teilen stoppt die Ausgabeerfassung mit einer Notiz im Journal; Eingaben, Screenshots und Notizen werden fortgesetzt. Eine Unternehmensrichtlinie kann die Teileanzahl über `max-log-parts` begrenzen.

## Das Journal wird aktiviert

### Automatisch für eine Verbindung

1. Öffnen Sie **Verbindungen > Verbindungen verwalten** und bearbeiten Sie eine Verbindung
2. Aktivieren Sie auf der Registerkarte **Journal** die Option **Sitzungsjournal für diese Verbindung aktivieren**
3. Passen Sie optional **Typisierte Eingabezeilen erfassen**, **KI-Zusammenfassungen generieren**, das **Zusammenfassungsintervall pro Verbindung** und das Rotationspaar **Maximale Größe pro Log-Teil (MB)** und **Maximale Anzahl rotierter Log-Teile** an.

Jede zukünftige Verbindung dieses Servers startet dann automatisch sein Journal. Das Journal übersteht erneute Verbindungen – ein Journal pro Tab-Lebensdauer, mit einer Wiederverbindungsmarkierung im Protokoll.

### Rückwirkend für eine laufende Sitzung

Verwenden Sie **Extras > Sitzungsjournal starten/stoppen** (++ctrl+alt+t++), das Tab-Kontextmenü (**Sitzungsjournal > Journal starten**) oder die Schaltfläche **Journal starten** in der Journalleiste. Der vorhandene Scrollback wird zunächst als Starteinträge in das Journal importiert, sodass die Zeitleiste abdeckt, was bereits passiert ist. Anschließend wird eine Live-Aufnahme angehängt.

### KI-Verbindungsprüfung vor dem Start des Journals

Ein Journal, dessen Zusammenfassungen niemals geschrieben werden können, ist wenig nützlich; deshalb prüft korTTY zunächst, ob das KI-Profil antwortet, wenn das Journal die KI aufruft (Zusammenfassungen sind global und für die Verbindung aktiviert und von der Richtlinie erlaubt). Ein erfolgreicher Test wird pro Profil zehn Minuten lang gespeichert, sodass beim Öffnen weiterer Tabs der Anbieter nicht erneut abgefragt wird.

- **Das Journal manuell starten** — die Journalleiste, das Tab-Menü oder ++ctrl+alt+t++ — zeigt **KI-Verbindung wird geprüft…** und startet das Journal erst, wenn der Test bestanden ist. Ausgaben, die dabei erzeugt werden, gehen nicht verloren: Der Scrollback wird importiert, sobald das Journal startet. Scheitert der Test, benennt ein Dialog das Profil und den Grund (z.B. fehlender API-Schlüssel oder verweigerte Verbindung) und bietet **Erneut testen**, **Ohne KI aufzeichnen** — das Journal zeichnet rohe Aktivität auf und kann später [ausgewertet werden](#ein-journal-erneut-mit-ki-bewerten) — oder **Abbrechen**.
- **Journale, die beim Verbinden automatisch starten,** beginnen sofort mit der Aufzeichnung, sodass das Terminal nie blockiert wird; der Test läuft im Hintergrund. Wenn er fehlschlägt, zeigt eine rote Leiste unter dem Terminal das Profil und den Grund an, mit **Erneut testen**, **Ohne KI aufzeichnen** und **Journal beenden**.
- **Automatisierungsdurchläufe** testen die KI einmal pro Lauf vor ihrem Abschlussdurchlauf. Wenn sie nicht antwortet, wird der Lauf nur als Log gespeichert und ein Zeitstrahl-Eintrag **KI nicht erreichbar** erklärt, warum – werten Sie ihn später im Journal-Manager aus.

### Die Journalleiste

Während ein Journal verfügbar ist, zeigt eine Leiste unter dem Terminal seinen Status an (**Journal aktiv seit HH:MM**) und bietet **Journal stoppen**, **Screenshot** und **Notiz**:

- **Screenshot** (++ctrl+alt+c++, auch im Rechtsklick-Menü des Terminals) erstellt einen Schnappschuss des Terminals – in einem geteilten Layout erfasst das Rechtsklick-Menü genau den Bereich unter dem Cursor – und legt ihn in der Journal-Timeline ab.
- **Notiz** öffnet den [Notiz-Editor](#notizen-schreiben) für einen freitextlichen Kommentar, der als eigener Zeitstrahl-Eintrag an der aktuellen Position erscheint.

### Notizen schreiben

Notizen werden überall im selben Editor geschrieben, wenn sie bearbeitet werden – der **Notiz**-Knopf in der Journalleiste, im Live-Paneel, das Eintragformular im [Viewer](#der-viewer-und-die-bearbeitung) und der [Screenshot-Editor](#screenshot-notizen-und-anmerkungen):

- Das Feld enthält **mindestens sechs Zeilen** und die Größe des Dialogfelds kann geändert werden, sodass eine Notiz ein Absatz statt einer einzelnen Zeile sein kann.
- **Links sind anklickbar.** Jede `http://`- oder `https://`-Adresse in einer Notiz wird zu einem Link auf der Journalseite – klicken Sie darauf und die Adresse wird in Ihrem Systembrowser geöffnet, niemals in der Journalansicht. Nur diese beiden Schemata werden jemals zu Links, und zwar nur in Texten, die Sie selbst geschrieben haben: KI-Zusammenfassungen und Terminalauszüge bleiben wörtlich.
- **Übersetzen** übergibt die Notiz an die KI und ersetzt sie durch die Übersetzung in der neben der Schaltfläche ausgewählten Sprache. Die Liste bietet die Schnittstellensprachen und jede Sprache, für die korTTY Übersetzungen hat, und akzeptiert eine typisierte Sprache, die es nicht auflistet. Ihre Auswahl wird für die nächste Notiz gespeichert. Die Übersetzung wird auf dem Profil ausgeführt, das dem **Text- und Übersetzungsprofil** im [KI-Manager](../reference/settings/ai.md) zugewiesen ist – derselben Rolle, die auch die Übersetzung des Snippet-Editors verwendet – und fällt auf Ihr Standardprofil zurück, wenn diese Rolle nicht festgelegt ist. Der Internetzugang bleibt dabei, genau wie bei den Zusammenfassungen, deaktiviert; ++cmd+z++ / ++ctrl+z++ bringt das Original zurück. Ohne ein verwendbares KI-Profil ist die Schaltfläche deaktiviert und zeigt dies an.

### Wenn die Verbindung endet

Ein Journal ist an seine Registerkarte gebunden, nicht an eine einzelne Verbindung. Wenn die Verbindung beendet wird, während das Journal ausgeführt wird – ein `reboot`, ein unterbrochenes Netzwerk oder der Server, der die Sitzung schließt –, bleibt die Registerkarte **offen** und eine rote Entscheidungsleiste wird angezeigt, anstatt dass sich die Registerkarte lautlos schließt:

- **Reconnect** stellt die Verbindung wieder her und das Journal wird einfach fortgesetzt, mit einer Wiederverbindungsmarkierung im Capture-Log. Arbeiten, die nach einem Serverneustart fortgesetzt werden, landen im selben Journal.
- **Journal beenden** stoppt das Journal und schreibt seine abschließende Zusammenfassung (und, falls aktiviert, den KI-Titel) genau wie die Stopp-Schaltfläche der Journalleiste. Auf der Registerkarte wird dann die einfache Leiste zum erneuten Verbinden angezeigt, so dass eine erneute Verbindung weiterhin möglich ist – wenn das Journaling für die Verbindung aktiviert ist, startet diese neue Sitzung ein neues Journal.

Wenn Sie stattdessen die Registerkarte schließen, wird auch das Journal mit seiner abschließenden Zusammenfassung beendet. Ohne laufendes Journal ist das Verhalten unverändert: Bei einer sauber beendeten Verbindung wird der Tab geschlossen, bei einem Fehler bleibt er mit der Reconnect-Leiste geöffnet (Doppelklick zum erneuten Verbinden).

### Journale der Automatisierungsdurchläufe

JobScheduler-Jobs — einschließlich geplanter KI-Swarm-Jobs — und Ausführungen, die im [KI-Swarm](ai-swarm.md#session-journal-pro-lauf) Tab kann für jeden Lauf eigenständig ein Sitzungsprotokoll aufzeichnen, ohne einen Terminal-Tab zu verwenden: ein Protokoll pro Zielserver, das die vom Automatisierung gesendeten Befehle, deren Ausgabe, ihre Zusammenfassung und das Ergebnis auf diesem Server enthält. Sie werden pro Aufgabe im JobScheduler eingerichtet — siehe [Session-Journal pro Lauf](jobscheduler.md#sitzungsjournal-pro-ausfuhrung) für die Einstellungen (KI-Zusammenfassungen immer / nur bei fehlgeschlagenen Läufen / aus, behalten immer / nur fehlgeschlagene Läufe, automatische Löschung nach N Tagen oder an einem Datum, maximale Läufe und Speicherplatz, verwerfen von Läufen identisch zum vorherigen) und die Kostenwarnung, die angezeigt wird, wenn sie aktiviert werden.

Jobs, deren Befehl im [virtuellen Terminal](jobscheduler.md#virtuelles-terminal-und-screenshots) läuft, fügen außerdem Screenshots dieses unsichtbaren Bildschirms hinzu; die KI beschreibt sie im abschließenden Durchlauf, wenn der KI-Modus angewendet wird. Automations-Journale werden nach dem Job, dem Server und der Startzeit benannt, und ihre KI-Zusammenfassungen laufen einmal nach Abschluss des Laufs — auf ihrem eigenen Worker, sodass sie die Zusammenfassungen des Terminals, in dem Sie arbeiten, niemals verzögern. **KI-Profil für Automations-Journale** in **Einstellungen > Logs > Sitzungsjournal** wählt das Profil, das verwendet wird, wenn ein Job kein eigenes auswählt; **Wie für Zusammenfassungen** nutzt das Journal-Profil, das unter [KI-Zusammenfassungen](#ki-zusammenfassungen) beschrieben ist. Ein günstiges oder lokales Modell hält unbeaufsichtigte Läufe erschwinglich.

Abgelaufene Automationsjournale werden automatisch gelöscht – eine Minute nach dem Start von korTTY und anschließend jede Stunde, ebenso wie die ältesten Ausführungen eines Jobs, die ihre maximale Anzahl an Durchläufen oder den verfügbaren Speicherplatz überschreiten. Laufende Journale, Journale deren Zusammenfassung noch geschrieben wird, angeheftete Journale und interaktive Journale werden niemals verändert. In the [Journal-Manager](#journale-verwalten) the **Löschung am** column shows when a journal goes, and **Behalten (Pin)** in a journal's right-click menu exempts it from automatic deletion (**Pin lösen** undoes it).

## Das Live-Journal-Panel

**Ansehen > Live-Journal** (oder ++ctrl+alt+l++) dockt die laufende Journal-Seite **vollständige Journal-Seite** — dieselbe Seite, die der [viewer](#die-journalseite) anzeigt — links oder rechts vom Terminal an, bleibt in Echtzeit aktuell. Auswahl der markierten Seite im Menü versteckt das Panel erneut; die Trennlinie daneben passt die Breite an, und Seite sowie Breite werden über Neustarts hinweg gespeichert.

Zwei Dinge werden während der Sitzung live aktualisiert:

- **Das Live-Protokoll** – Die Schaltfläche **Live-Protokoll** in der Kopfzeile des Bedienfelds öffnet das Protokollfeld der Seite im Folgemodus und streamt das Capture-Log, während es geschrieben wird: Befehlsausgabe, die von Ihnen eingegebenen Befehle, Notizen und Screenshot-Markierungen, jeweils mit einem Zeitstempel. Es beginnt im Verborgenen; Die Zeilen sammeln sich in beide Richtungen an, sodass beim späteren Öffnen alles angezeigt wird. Wenn Sie nach oben scrollen, wird das Folgende angehalten, wenn Sie nach unten scrollen, wird es fortgesetzt, das ✕ in seiner Ecke blendet es wieder aus (die Schaltfläche bleibt synchron) und durch Ziehen an der Oberkante wird die Höhe angepasst – was gespeichert wird. Die Ansicht behält die neuesten 5000 Zeilen; Alles bleibt im Capture-Log und in den Protokollauszügen der Eintrittskarten.
- **Die Zeitleiste** – neue Karten (KI-Zusammenfassungen, Notizen, Screenshots) und Änderungen werden kurz nach ihrer Ausführung angezeigt, ohne dass Sie Ihre Scrollposition verlieren. Ein [terminaler KI-Agent](ai-assistant.md)-Lauf fügt in dem Moment, in dem er beendet ist, seine eigene **KI-Agent**-Karte hinzu: Ihre Eingabeaufforderung als Titel, die endgültige Antwort des Agenten als Text und eine Metazeile mit dem Modell, der Laufdauer und der gemeldeten Token-Anzahl. Lange Antworten werden zu einer Vorschau verkleinert. Klicken Sie zum Erweitern auf den Text (oder **Vollständige Antwort anzeigen**). Die Agentenarbeit ist Teil des Journaldatensatzes, auch wenn die Zusammenfassung den Inline-Terminaltext des Agenten als Rauschen behandelt.

Da es sich um die echte Journalseite handelt, funktioniert alles, was die Viewerseite bietet, hier direkt: klicken Sie auf eine Eintragskarte, um deren Logauszug anzuzeigen, durchsuchen Sie das Journal, springen Sie zwischen markierten Einträgen und **Rechtsklick** auf einen Screenshot, um den [Annotation-Editor](#screenshot-notizen-und-anmerkungen) (Stift, Box, unleserlich, Text und eine Notiz) zu öffnen oder ihn zu kopieren – das bearbeitete Bild erscheint im Panel, sobald Sie speichern. Ein Rechtsklick auf einen Eintrag bietet denselben Marker-Picker wie der Viewer. Die Karten passen sich an die Panelbreite an, sodass der Text auch bei sehr schmaler oder breiter Anordnung lesbar bleibt.

### Spring zu einer Zeit

Die Schaltfläche **◷** in der Kopfzeile der Seite öffnet ein Zeitfeld: Geben Sie eine Zeit ein und die Zeitleiste scrollt zum nächstgelegenen Eintrag und hebt ihn kurz hervor. Die Eingabe ist nachsichtig – `19:00`, `19.00`, `1900` und `19` bedeuten alle dasselbe, und ein Datum kann vorangestellt werden (`13.08. 19:00`, `13.08.2026 19:00` oder `2026-08-13 19:00`). Ohne Datum wird die Uhrzeit mit dem jeweiligen Tag jedes Eintrags abgeglichen, sodass eine Sitzung, die nach Mitternacht läuft, zum nächsten Vorkommen springt und nicht immer zum ersten Tag.

Die Kopfzeile des Panels fügt die sofortigen Steuerelemente hinzu: **Notiz** und **Screenshot** wirken auf das angezeigte Journal genau wie die [journal bar](#die-journalleiste) – eine Notiz, die Sie hinzufügen, erscheint sowohl in der Zeitleiste als auch im Live-Log – **Live-Log** zeigt oder versteckt die Log-Ansicht, und **Viewer öffnen** öffnet das vollständige Viewer-Fenster zum Bearbeiten, Suchen & ersetzen und Exportieren. Das **⋯**-Menü wechselt die Seite zwischen Hell und Dunkel, aktualisiert sie und öffnet die Seite [appearance](#aussehen) Einstellungen.

Das Panel folgt Ihren Tabs mit einem Gedächtnis: Es zeigt das Journal des aktuellen Tabs an, und wenn Sie zwischen Tabs wechseln, schaltet es nur weiter, **wenn der neu ausgewählte Tab auch ein laufendes Journal hat** – andernfalls zeigt es weiterhin das Journal an, das es bereits anzeigt. Wenn das angezeigte Journal gestoppt oder sein Tab geschlossen wird, bleibt die Seite mit dem Abzeichen **Journal gestoppt** / **Tab geschlossen** sichtbar, bis Sie einen anderen Tab mit einem Live-Journal auswählen.

Alles, was angezeigt wird, hat bereits [Passwortschutz](#passwortschutz) durchlaufen – unterdrückte Eingaben und geschwärzte Geheimnisse erreichen das Panel nie.

## KI-Zusammenfassungen

Während das Journal läuft, liest der KI-Summierer regelmäßig die neuesten Capture-Log-Zeilen und hängt einen kompakten Journaleintrag an (Titel, Zusammenfassung und eine vorgeschlagene Markierung: Info, wichtig oder Fehler). Standardwerte und Grenzwerte:

| Option | Wo | Default |
|--------|-------|---------|
| Zusammenfassungsintervall | **Einstellungen > Protokollierung > Sitzungsjournal** (global) oder pro Verbindung | 5 Minuten |
| Max. Terminalzeilen pro KI-Auswertung | Journalmanager **Optionen** | 100 |
| Token-Budget für Kontextfüllung | Journalmanager **Optionen**, sichtbar, wenn die maximale Anzahl an Zeilen 0 beträgt | 130000 |
| Rückstand auf mehrere Prompts aufteilen (Chunking) | Journalmanager **Optionen** | aus |
| KI-Profil für Zusammenfassungen | Journalmanager **Optionen** oder **Einstellungen > Protokollierung > Sitzungsjournal** | Standardprofil |
| Screenshots mit KI analysieren (Beschreibung und Tags) | Journalmanager **Optionen** | auf |
| Semantische Journalsuche (Einbettungen) | Journalmanager **Optionen** | aus |

Zusammenfassungen verwenden Ihr **Standard-KI-Profil**, es sei denn, Sie wählen ein spezielles Journalprofil aus. Diese Auswahl ist an drei gleichwertigen Stellen verfügbar: im Dialogfeld **Optionen** des Journalmanagers, **Einstellungen > Protokollierung > Sitzungsjournal** und **KI > KI-Manager > Lokale KI** neben den Rollen Text und Codierung. Die Rollenprofile Text/Coding selbst werden bewusst nicht für das Journal verwendet.

Wenn Sie **max Zeilen auf 0** setzen, wird auf Kontextfüllung umgeschaltet: Der Zusammenfassungstext packt so viele der neuesten Zeilen, wie in das konfigurierte Token-Budget passen. Wenn **Chunking** aktiviert ist, wird das gesamte Backlog verarbeitet und nicht nur das neueste Fenster – aufeinanderfolgende Fenster der konfigurierten Größe, jeweils eine KI-Eingabeaufforderung.

!!! warning
    Chunking kann bei großen Sitzungen sehr lange dauern und wird nicht für den täglichen Gebrauch empfohlen – es ist für Power-User mit leistungsfähiger Hardware und einem leistungsstarken LLM gedacht.

Wenn die Sitzung endet, schreibt der Zusammenfassungs-Generator einen abschließenden **Sitzungsfazit** Eintrag (was erreicht wurde, welche Fehler aufgetreten sind) und extrahiert bis zu zwölf wörtlich **Keywords** – Hostnamen, Skript- und Dateinamen, Fehlertypen – in die Journal-Metadaten, wo der Filter des Managers, Keyword-Chips und [KI-Suche](#ki-suche-in-allen-journale) sie abgreifen. Optional – **Lassen Sie die KI den Journal-Titel vergeben, wenn die Sitzung endet** im Optionsdialog – ein letzter KI-Aufruf benennt das Journal, sofern Sie es nicht manuell umbenannt haben.

!!! note
    Das Journal funktioniert ohne KI: Wenn kein KI-Profil verfügbar ist, KI-Funktionen deaktiviert oder Zusammenfassungen ausgeschaltet sind, zeichnet die Zeitleiste stattdessen rohe Aktivitätseinträge auf. KI-Zusammenfassungsaufforderungen verwenden niemals Tools für den Internetzugang; Der Terminalauszug geht nur an das konfigurierte KI-Profil.

## KI-Screenshot-Analyse

Wenn das KI-Profil des Journals Bilder akzeptiert, werden auch Screenshots analysiert: Das Modell schreibt eine kurze **Beschreibung** (ein bis drei Sätze) und ein paar Kleinbuchstaben-**Tags**, die rechts neben dem Miniaturbild auf der Journalseite angezeigt werden. Tags sind anklickbare Chips – ein Klick startet eine [Journal-Suche](#suche-im-journal) nach diesem Tag – und beide Texte werden durch den **Inhalte durchsuchen**-Scan des Managers gefunden, von [Suchen und Ersetzen](#suchen-und-ersetzen) und den automatischen Schwärzungsregeln umgeschrieben und in die Markdown- und PDF-Exporte aufgenommen. Wie die Zusammenfassungen beantwortet die Analyse in der Sprache, unter der das Journal erstellt wurde.

- **Screenshots mit KI analysieren (Beschreibung und Tags)** im Dialogfeld **Optionen** des Journalmanagers steuert die automatische Analyse bei der Erfassung (standardmäßig aktiviert). Es wird nur ausgeführt, wenn KI-Zusammenfassungen für die Verbindung aktiviert sind und das Profil Bilder aufnehmen kann. andernfalls wird der Screenshot einfach unanalysiert abgelegt.
- **Screenshot mit KI analysieren** im Rechtsklickmenü eines Screenshots analysiert ein Bild auf Abruf – die Möglichkeit, Screenshots in zuvor aufgezeichneten Journals zu analysieren, um einen fehlgeschlagenen Lauf erneut auszuführen oder die Analyse erneut auszuführen, nachdem etwas im [Anmerkungseditor](#screenshot-notizen-und-anmerkungen) unlesbar gemacht wurde. Die Analyse liest immer das annotierte Bild, niemals die unveränderte `.orig.png`Aufnahme.

Ob ein Profil Bilder aufnehmen kann, ist eine profilspezifische Eigenschaft: **Bildeingabe (Vision)** in den [KI-Einstellungen. ](../reference/settings/ai.md) ist standardmäßig auf **Auto** eingestellt – für einen lokalen LM Studio-Endpunkt liest korTTY die Antwort aus den Modellmetadaten (während derselben Aktualisierung, Reasoning-Optionen erkennt), für Cloud-Endpunkte erkennt es die allgemeinen vision-fähigen Modellnamen – und kann mit **Aktiviert**/**Deaktiviert** für Modelle der Erkennung überschrieben werden schätzt falsch ein.

!!! warning
    Die automatische Analyse sendet den Screenshot zum Zeitpunkt der Aufnahme – bevor Sie die Möglichkeit hatten, etwas unleserlich zu machen. Das Bild geht nur zum konfigurierten KI-Profil und niemals über internetzugängliche Werkzeuge, aber für Sitzungen, deren Bildschirminhalt nicht die Maschine verlassen darf, schalten Sie die Option aus oder lassen Sie Ihren Administrator die Analyse über [Unternehmensrichtlinie](#unternehmensrichtlinie) verbieten – das Richtlinienmandat deaktiviert auch die manuelle Ausführung.

## Die KI nach einem Journal fragen

**AI Q&A** in der Symbolleiste des Viewers öffnet ein Chat-Panel neben der Journalseite. Fragen Sie etwas zu dieser Sitzung – *„Wurden Screenshots gemacht, die Fehler von result_complex.pl zeigen?“* – und die KI antwortet aus dem, was das Journal bereits während der Sitzung gesammelt hat: die KI-Zusammenfassungen, Screenshot-Beschreibungen und Tags sowie Ihre Notizen. Das Roherfassungsprotokoll wird niemals an das Modell gesendet.

![Asking the AI about a journal](../assets/screenshots/journal/journal-ask-panel.png)

Wenn für eine Frage konkrete Beweise aus dem Protokoll benötigt werden – genaue Fehlerzeilen, ob ein Skript wirklich fehlgeschlagen ist, wie oft etwas passiert ist – benennt das Modell ein paar wörtliche Suchzeichenfolgen, korTTYs eigene Streaming-Suche durchsucht das Capture-Log nach ihnen, und nur die Übereinstimmungszahlen sowie eine Handvoll Beispielzeilen werden für die endgültige Antwort an das Modell zurückgesendet. Das Panel zeigt beides neben der Antwort:

- **Quellen** – die in der Antwort zitierten Journal-Einträge; Wenn Sie auf eines klicken, scrollt die Zeitleiste zu diesem Eintrag.
- **Protokollbeweise** – pro Suchzeichenfolge die genaue Anzahl übereinstimmender Protokollzeilen, mit anklickbaren Beispielen, die das Protokollfenster öffnen und bis zur genauen Zeile scrollen.

Anschlussfragen führen das Gespräch fort (das Gremium behält die jüngsten Gespräche als Kontext bei); **Neues Gespräch** beginnt von vorne. **Als Notiz speichern** fügt ein Frage-Antwort-Paar als Eintrag an die Journalzeitleiste an, sodass ein Befund Teil der Aufzeichnung wird.

!!! note
    Wenn kein KI-Profil erreichbar ist oder die Anfrage fehlschlägt, degradiert das Panel anstelle eines Fehlers: es extrahiert die Identifikatoren aus Ihrer Frage, führt die interne Textsuche durch und zeigt die passenden Einträge und Logzeilen mit einer Mitteilung, dass kein Modell beteiligt war. Das Q&A verwendet niemals internetbasierte Tools, und Administratoren können es vollständig verbieten (`ai-ask` unter [Unternehmensrichtlinie](#unternehmensrichtlinie)).

## KI-Token-Nutzung und Kosten

Jede KI-Aufruf, der für ein Journal gemacht wird — periodische Zusammenfassungen, das abschließende Sitzungsfazit und der Titel, die Screenshot-Analyse und Fragen im KI Q&A Panel — trägt seine Token-Nutzung zu den laufenden Gesamtsummen des Journals bei. Die Journal-Seite zeigt die Summe in ihrem Header als **KI-Tokens** (zum Beispiel `12.3k · ≈ 0.04 €`), und der Journal-Manager hat eine Spalte **KI-Tokens**, deren Tooltip die Zahl in Eingabe- und Ausgabe-Tokens, Anzahl der KI-Aufrufe und das verwendete Profil aufschlüsselt. Der Geldbetrag erscheint, wenn das Profil ein [Preis pro 1M Tokens](../reference/settings/ai.md#token-quoten-verwaltung) hat; ein lokal laufendes Profil zeigt `local` stattdessen. Jeder Aufruf wird mit dem Preis des Profils zum Zeitpunkt des Aufrufs berechnet.

Die gleichen Aufrufe zählen ebenfalls zum Token-Kontingent des KI-Profils, sodass Journale zusammengefasst im Kontingentbalken des KI-Managers erscheinen wie jede andere KI-Anfrage. Journale, die vor dieser Verfolgung aufgezeichnet wurden, zeigen keine KI-Tokens.

## Passwortschutz

Getippte Eingaben werden nur als vollständig übermittelte Zeilen erfasst und mehrere Ebenen halten Passwörter aus dem Journal fern:

- Wenn die Serverausgabe mit einer Passwortabfrage endet (`password:`, `[sudo] password for …`, `passphrase`, `PIN` und lokalisierte Varianten), wird die nächste übermittelte Eingabezeile unterdrückt und als geschwärzter Platzhalter protokolliert – der eingegebene Text wird nie zwischengespeichert oder geschrieben.
- Das eigene gespeicherte Passwort der Verbindung wird zusätzlich durch `***` ersetzt, wo immer es im erfassten Text erscheinen würde.
- **Eingegebene Befehlszeilen erfassen** kann pro Verbindung vollständig deaktiviert werden; Befehle erscheinen dann nur noch als Echo des Servers im Ausgabestream.

!!! warning
    Bei der Prompt-Erkennung handelt es sich um eine Heuristik – ein Remote-Terminal kann nicht zuverlässig erkennen, wann der Server das Echo deaktiviert hat. Exotische oder Vollbild-Passwortabfragen werden möglicherweise nicht erkannt und in sichtbare Befehle eingefügte Geheimnisse (außer den Anmeldeinformationen der Verbindung) werden wie jeder andere Text erfasst. Behandeln Sie Protokolle sensibler Sitzungen entsprechend.

Falls etwas dennoch durchgegangen ist, entfernt der Viewer's [Suchen und Ersetzen](#suchen-und-ersetzen) es aus den Einträgen und dem Aufzeichnungsprotokoll nachträglich. Administratoren können korTTY auch automatisch Muster ausblenden lassen — siehe [Unternehmensrichtlinie](#unternehmensrichtlinie) unten.

## Die Journalseite

`journal.html` ist vollständig eigenständig (keine externen Ressourcen) und funktioniert im integrierten Viewer, in jedem Browser und innerhalb des exportierten Bundles:

- Ein Sticky-Header zeigt an, wer mit welchem Server verbunden war, Startzeit, Dauer und Anzahl der Einträge, Befehle, Fehler und Screenshots sowie die Journalbeschreibung. Live-Journale weisen ein **Live**-Abzeichen auf. Die Zeile unter dem Titel enthält nur das, was der Titel nicht bereits sagt, sodass eine nach ihrem Endpunkt benannte Journal die Verbindung einmal statt dreimal angibt.
- Die Zeitleiste gruppiert Einträge nach Tag; Jeder Eintrag trägt seine Zeit, einen Markierungspunkt und ein Abzeichen in der Farbe, die Sie dieser Markierung gegeben haben, den KI-Titel und die Zusammenfassung sowie farbcodierte Eingabe- (grün) und Ausgabeauszüge (blau).
- Durch Klicken auf einen Eintrag wird von unten ein Protokollfenster mit dem genauen Capture-Logbereich hinter diesem Eintrag eingeblendet. Das Panel verfügt über eine eigene Bildlaufleiste, ein Suchfeld mit Trefferzähler und ▲/▼-Navigation (++enter++ / ++shift+enter++ zyklisch auch Treffer, ++esc++ schließt) und färbt Eingabe- und Ausgabezeilen unterschiedlich ein.
- Screenshot-Einträge zeigen Miniaturansichten; ein Klick öffnet eine Vollbild-Lightbox. Wenn die [KI-Screenshot-Analyse](#ki-screenshot-analyse) ausgeführt wurde, befinden sich deren Beschreibung und Tag-Chips rechts vom Bild, und ein Klick auf einen Chip durchsucht das Journal nach diesem Tag.
- Die Seite wird standardmäßig dunkel gerendert, folgt der Hell/Dunkel-Einstellung des Systems und verfügt über eine eigene Designumschaltung.
- Screenshots, Auszugsfenster, die Zeitleistenspalte und das Protokollfenster passen sich dem Fenster an, sodass die Seite sowohl in einem schmalen Viewer-Tab als auch im Vollbildmodus lesbar bleibt. Lange Ausschnitte scrollen in ihrem eigenen Rahmen, anstatt die Zeitleiste zu dehnen.

### Suche im Journal

Die Lupe in der Kopfzeile öffnet eine Suchleiste direkt unter den Verbindungsdetails (++ctrl+f++ funktioniert auch). Durch die Eingabe eines Begriffs oder eines ganzen Satzes wird jedes Vorkommen auf der Zeitleiste hervorgehoben – Eintragstitel, KI-Zusammenfassungen, KI-Screenshot-Beschreibungen und Tags, Ein- und Ausgabeauszüge, Notizen und Zeitstempel – und ein Übereinstimmungszähler angezeigt; ▲ und ▼ oder ++enter++ / ++shift+enter++ springen zwischen den Treffern, ++esc++ oder ✕ schließt die Leiste und löscht die Hervorhebung.

!!! note
    Dies durchsucht die Journal-Einträge. Das rohe Aufnahmelog hat seine eigene Suche im Log-Panel, der Journal-Manager kann über *alle* Journale hinweg mit **Inhalte durchsuchen** oder dem [KI-Suche](#ki-suche-in-allen-journale) suchen, und der Viewer's [AI Q&A](#die-ki-nach-einem-journal-fragen) beantwortet Fragen zu diesem Journal.

### Zwischen markierten Einträgen wechseln

Wenn mindestens ein Eintrag ein Marker trägt, erhält der Header eine ◆-Schaltfläche, die eine Markerleiste öffnet. Wählen Sie **Alle Marker** oder einen einzelnen, dann navigieren Sie durch die Treffer mit ▲ und ▼ – die Liste wird umgewunden, und der aktuelle Eintrag wird in den Blickfeld gerückt und kurz hervorgehoben. ++alt+down++ und ++alt+up++ erledigen dies ebenfalls ohne Maus, und ++alt+m++ schaltet die Leiste ein und aus; ++esc++ schließt sie.

Ein Protokoll ohne Marker enthält weder die Schaltfläche noch die Leiste, sodass der Header wie zuvor bleibt.

### Ein Zeitintervall mit der Maus auswählen

Im korTTY enthält der Header auch eine ⇥-Schaltfläche, die die Zeitlinie in den Bereichsmodus wechselt. Klicken Sie auf das erste Element, dann auf das letzte — alles dazwischen wird hervorgehoben und die Leiste zeigt den Zeitraum an sowie, wie viele Einträge enthalten sind. Die Reihenfolge spielt keine Rolle: Ein Klick auf das spätere Element zuerst funktioniert genauso gut.

- **Weiteres Fenster** legt die aktuelle Auswahl beiseite und startet eine neue, so dass mehrere Fenster in einem Durchlauf gesammelt werden können.
- **Für Export verwenden** öffnet den Exportdialog mit bereits ausgefüllten Fenstern.
- **Abbrechen** oder ++esc++ verlässt den Bereichsmodus.

Wenn der Bereichsmodus aktiviert ist, wird durch Klicken auf einen Eintrag das Protokollfenster ausgewählt, anstatt es zu öffnen. In einem externen Browser fehlt die Schaltfläche, da zum Exportieren die App erforderlich ist.

In der Eingabetabelle des Bearbeitungsmodus ist das Gleiche auch ohne Zeitleiste verfügbar: Wählen Sie mehrere Zeilen aus, klicken Sie mit der rechten Maustaste und wählen Sie **Auswahl als Zeitfenster übernehmen**.

### Inhalt wird kopiert

Jeder Eintrag verfügt über eine Schaltfläche zum Kopieren in der oberen rechten Ecke: Texteinträge kopieren den gesamten Eintrag, Screenshot-Einträge kopieren das Bild. Das Protokollfenster verfügt über dieselbe Schaltfläche für den Protokollabschnitt, den es gerade anzeigt.

Wenn Sie mit der rechten Maustaste auf die Seite klicken, wird ein Kopiermenü mit gezielteren Aktionen geöffnet, je nachdem, was Sie angeklickt haben:

| Aktion | Kopiert |
|--------|--------|
| **Auswahl kopieren** | Der aktuell ausgewählte Text (wird angezeigt, wenn eine Auswahl vorhanden ist) |
| **Zusammenfassung kopieren** | Zeit, Titel und KI-Zusammenfassung des Eintrags |
| **Eintrag kopieren** | Dasselbe plus die Ein-/Ausgabeauszüge und Ihre Notiz |
| **Screenshot kopieren** | Der Screenshot selbst, als Bild, in die Zwischenablage |
| **Screenshot-Pfad kopieren** | Der Pfad des Screenshots im Journalordner |
| **Log-Ausschnitt kopieren** | Jede Zeile des derzeit im Panel angezeigten Protokollbereichs mit Zeitstempeln |

In korTTY verwenden die Kopieraktionen die Zwischenablage der App, sodass Bilder in der Zwischenablage des Systems landen und zum Einfügen bereit sind. In einem externen Browser wird der Text weiterhin normal kopiert; Das Kopieren eines Bildes kann auf seinen Pfad zurückgreifen, da Browser das Lesen lokaler Bilddaten von einer `file://`-Seite blockieren.

### Aussehen

Die Schaltflächen **A−**, **A** und **A+** im Seitenkopf skalieren die gesamte Seite zwischen 70 % und 250 %. korTTY merkt sich die Größe und wendet sie auf jede anschließend gerenderte Journalseite an, sodass eine Seite, die neu generiert wird (eine neue KI-Zusammenfassung, eine bearbeitete Markierung), wieder die von Ihnen gewählte Größe hat. Wenn die Seite eigenständig in einem Browser geöffnet wird, merkt sie sich stattdessen die Größe pro Browser.

Die **Darstellung**-Schaltfläche des Viewers öffnet ein kleines Fenster mit dem Rest:

| Einstellung | Wirkung |
|---------|--------|
| **Farbschema** | *Automatisch* behält das eigene Dunkel/Hell-Paar der Seite bei und folgt dem Betriebssystem. *Dem Terminal-Theme folgen* leitet die Seitenfarben vom Hintergrund und Vordergrund Ihres Terminals ab. Die übrigen Einträge (Paper, Midnight, Ocean, Forest, Retrowave, High contrast) sind feste Paletten. |
| **Textschrift** | Die Schriftart für Überschriften, Zusammenfassungen und Notizen. *(Standard)* stellt die Standardschriften der Seite wieder her. |
| **Festbreitenschrift** | Die Schriftart für die Ein-/Ausgabeauszüge und das Protokollfenster. |
| **Textgröße** | Die gleichen 70–250 % wie die Tasten A−/A/A+. |

Änderungen werden sofort im Viewer in der Vorschau angezeigt und für jede Journalseite gespeichert. Bei einem festen Schema bleibt der ◐-Schalter der Seite sichtbar, ist jedoch deaktiviert, da das Schema bereits über die Farben entscheidet.

## Journale verwalten

**Werkzeuge > Sitzungsjournale…** (++ctrl+alt+j++) öffnet den Journal-Manager: alle Journale sind nach Startzeit sortiert (neueste zuerst) mit Titel, Dauer, Verbindung, Server, Eintragszahl, [KI-Tokens](#ki-token-nutzung-und-kosten) und, für [automatisierte Journale](#journale-der-automatisierungsdurchlaufe), dem Datum, an dem sie automatisch gelöscht werden (oder „behalten“, wenn angeheftet). Laufende Journale sind markiert und können nicht umbenannt oder gelöscht werden, solange sie aktiv sind.

Interaktive Journale sind einzelne Zeilen. Die Journale von Automatisierungsdurchläufen werden gruppiert, damit klar bleibt, was zusammengehört: eine Zeile pro Quelle — **Job: …**, **KI-Swarm: …** oder **Geplanter Swarm: …** — mit seinen Durchläufen darunter, und unter jedem Durchlauf ein Journal pro Server; ein Durchlauf eines einzelnen Servers zeigt dieses Journal direkt an. Gruppen- und Durchlaufzeilen zeigen die Summen der darunterliegenden Journale (Durchläufe, Journale und Festplattenspeicher in der Verbindungsspalte, aufsummierte Einträge, KI-Tokens und Kosten, das nächste Löschdatum); ein Doppelklick erweitert sie, und die Auswahl eines Eintrags wählt alle seine Journale für **Exportieren** und **Löschen** aus. Das Dropdown neben dem Filterfeld zeigt **Alle Journale**, nur **Interaktiv**, **JobScheduler** oder **KI-Swarm** Journale, **Fehlgeschlagene Läufe** oder **Angeheftet** Journale.

Kleine Abzeichen neben jedem Titel zeigen auf einen Blick, was eine Zeile ist:

| Abzeichen | Bedeutung |
|-------|---------|
| **Interaktiv** · **Job** · **Schwarm** · **Geplanter Swarm** | Woher kommt das Journal? |
| **Erfolg** · **Fehlgeschlagen** · **Blockiert** · **Abgebrochen** | Das Ergebnis des Laufs auf diesem Server (der schwerwiegendste in den Gruppen- und Laufzeilen) |
| **AI** · **Nur Protokoll** · **KI bei Fehler** | Der KI-Modus, mit dem der Lauf aufgezeichnet wurde |
| **lokal** | Die Zusammenfassungen stammten von einem lokalen Modell (keine Token-Kosten) |
| **×N identisch** | N Durchläufe erzeugten genau diese Ausgabe; die späteren wurden zugunsten dieses Journals verworfen |
| **📷 N** | Das Journal enthält N Screenshots des virtuellen Terminals des Auftrags |
| **📌 Behalten** | Angeheftet: wird nie automatisch gelöscht |
| **wird in N d gelöscht** · **wird heute gelöscht** | Verbleibende Zeit bis zur automatischen Löschung (hervorgehoben, wenn weniger als zwei Tage übrig sind) |
| **durch Richtlinie begrenzt** | Ein Administrator begrenzt die Aufbewahrung, den Speicherplatz oder die Anzahl der Automatisierungsjournale |

Rechtsklick auf eine Automatisierungszeile für **Behalten (Pin)** / **Pin lösen** — bei einem Lauf oder einer Gruppe, **Alle behalten (Pin)** / **Alle Pins lösen** — und für Jobjournale, **Job im JobScheduler öffnen**. Die Journalseite eines Automatisierungsjournals zeigt außerdem seinen Ursprung, das Lauf-Ergebnis und das Löschdatum im Header an.

![Session journal manager](../assets/screenshots/journal/journal-manager.png)

- Das Filterfeld entspricht Titel, Verbindung, Host, Benutzer, Beschreibung und den KI-Schlüsselwörtern. Wenn Sie **Inhalte durchsuchen** aktivieren, werden zusätzlich die Journaleinträge gescannt – einschließlich KI-Screenshot-Beschreibungen und Tags – und Protokolle jedes Journals werden im Hintergrund erfasst (die Protokolle werden Teil für Teil per Streaming gelesen, sodass selbst große Journale nicht in den Speicher geladen werden).
- Unterhalb des Filterfelds zeigen anklickbare **Keyword-Chips** die häufigsten KI-Keywords in den aufgelisteten Journale an – wobei genau eine Journal ausgewählt ist, also die eigenen Keywords dieser Journal. Durch Klicken auf einen Chip wird nach ihm gefiltert. Die Schlüsselwörter stammen aus der Zusammenfassung der Abschlusssitzung, die bis zu zwölf wörtliche Suchbegriffe (Hostnamen, Skript- und Dateinamen, Fehlerklassen) in die Metadaten des Journals extrahiert.
- **Öffnen** (oder Doppelklick) öffnet den Journal-Viewer; **Umbenennen** ändert den Titel; **Löschen** fragt nach einer Bestätigung und entfernt dann dauerhaft den Journalordner einschließlich des Protokolls und aller Screenshots.
- Es können mehrere Journale gleichzeitig ausgewählt werden (Klick ++ctrl++ / ++shift++), um sie in einem Schritt zu löschen oder zu exportieren. Laufende Journale können nicht umbenannt oder gelöscht werden.
- Der Bereich **Beschreibung** unterhalb der Tabelle speichert eine Freitextbeschreibung pro Journal; Es erscheint auf der Journalseite und in jedem Export und wird in die Inhaltssuche einbezogen.
- **Mit KI neu auswerten…** (Button und Rechtsklickmenü) fasst die ausgewählten geschlossenen Journale erneut mit einem von Ihnen gewählten Profil zusammen — siehe [Ein Journal erneut mit KI bewerten](#ein-journal-erneut-mit-ki-bewerten).
- **Optionen** enthält die oben beschriebenen globalen Erfassungs- und KI-Einstellungen sowie **Zusammenfassungen aufholen**: Es zählt die geschlossenen Journale, die nie zusammengefasst wurden (aufgezeichnet, während Zusammenfassungen deaktiviert waren oder kein Modell erreichbar war) und führt bei Bedarf die reguläre Zusammenfassung nacheinander hinter einem Fortschrittsdialog aus – zwischen Journalen abbrechbar, und ein unterbrochener Lauf wird an der Stelle fortgesetzt, an der er gestoppt wurde.

### Ein Journal erneut mit KI bewerten

Wenn eine Zusammenfassung schlecht ausfiel oder die KI während der Aufzeichnung des Journals nicht erreichbar war, wählen Sie das Journal – oder einen Lauf bzw. eine Gruppe, die alle seine Journale repräsentiert – und wählen Sie **Mit KI neu auswerten…**. Das Dialogfenster bietet jedes KI-Profil (vorausgewählt: das Profil, das das Journal verfasst hat, sonst das Journal-Profil) und für Journale mit Screenshots **Screenshots erneut beschreiben**.

Das ausgewählte Profil wird zuerst getestet; antwortet es nicht, wird nichts verändert. Andernfalls werden die vorherigen KI-Zusammenfassungen und das abschließende Sitzungsfazit **ersetzt**: Das gesamte Aufzeichnungsprotokoll wird neu zusammengefasst, einschließlich neuer Schlüsselwörter. Notizen, Screenshots, Agenten- und Systemeinträge bleiben unverändert. Sollte die KI mittendrin scheitern, werden die betroffenen Fenster als rohe Aktivitätseinträge beibehalten, sodass die Zeitleiste niemals leer wird. Ein Fortschrittsdialog zeigt das Journal während der Auswertung; am Ende meldet er, wie viele Journale neu bewertet wurden und wie viele Tokens verbraucht wurden; sie zählen wie jeder andere Journal-KI-Aufruf zum Kontingent des Profils. Laufende Journale können nicht neu bewertet werden.

### KI-Suche in allen Journale

**KI-Suche** neben dem Filterfeld öffnet ein Suchfenster unter der Tabelle. Stellen Sie eine Frage zu allen gespeicherten Journalen — *„In welchen Journalen hat result_complex.pl mit einem Fehler beendet?“* — und korTTY antwortet in zwei Schritten: eine schnelle lokale Rangfolge wählt die relevantesten Journale aus deren Metadaten und gesammelten Einträgen, dann schreibt eine einzelne KI-Anfrage über diese Kandidaten die Zusammenfassung und wählt die Journale aus, die tatsächlich die Frage beantworten. Wie bei der [per-Journal Q&A](#die-ki-nach-einem-journal-fragen) sieht das Modell nur die gesammelten Einträge, niemals die Capture-Loge; exakte Logpositionen stammen aus der internen Streaming-Suche.

![AI search across all journals](../assets/screenshots/journal/journal-search-panel.png)

Das Ergebnis erscheint auf beiden Seiten des Panels:

- Der **Antwort-Chat** auf der linken Seite unterstützt Folgefragen zu denselben Ergebnissen.
- Der **Trefferbaum** auf der rechten Seite listet jedes übereinstimmende Journal mit seiner Gesamttrefferzahl auf (bewegen Sie den Mauszeiger, um den Ein-Satz-Grund der KI anzuzeigen) und darunter die einzelnen Treffer – übereinstimmende Einträge und genaue Protokollzeilen mit Zeitstempel und Snippet. Ein Klick auf einen Treffer öffnet das Journal und springt direkt zu diesem Eintrag oder dieser Protokollzeile. Treffer, die Sie bereits geöffnet haben, werden mit einem Häkchen gedämpft, und diese Markierung übersteht neue Suchvorgänge und Neustarts – Orientierung für das Durcharbeiten einer langen Trefferliste.
- In der Kopfzeile wird die Gesamtsumme angezeigt: *n Treffer in m Journale*. Wiederholte identische Ausgabezeilen werden zusammengeführt gespeichert und die Zählung umfasst ihre Wiederholungsfaktoren, sodass sie reale Vorkommnisse widerspiegelt.

Die Tabelle selbst bleibt vollständig: Journale mit Treffern erhalten eine sortierte Spalte **Treffer** und eine Zeilenhervorhebung, anstatt alles andere wegzufiltern. **Nur Auswahl** beschränkt die Suche auf die in der Tabelle ausgewählten Journale.

!!! note
    Ohne ein erreichbares KI-Profil funktioniert die Suche weiterhin: Die Identifikatoren der Frage werden direkt mit den Journalen und Protokollen abgeglichen, nur die KI-Zusammenfassung und die Journalauswahl werden übersprungen (so steht es in einem Hinweis). Optional fügt die **Semantische Journalsuche** im Dialogfeld „Optionen“ ein einbettungsbasiertes Ranking zusätzlich zum lexikalischen hinzu – es erfordert ein lokales Einbettungsmodell, das in den Wissensspeichern konfiguriert ist, und greift stillschweigend auf das lexikalische Ranking zurück, wenn keine Einbettungen verfügbar sind.

### Der Viewer und die Bearbeitung

![Session journal viewer](../assets/screenshots/journal/journal-viewer.png)

Der Viewer zeigt die Journalseite in einem eingebetteten Browser an und aktualisiert sich automatisch, während das Journal noch geschrieben wird. **Im Browser öffnen** übergibt die Seite an Ihren Systembrowser. **Bearbeiten** teilt die Ansicht: eine Eintragstabelle neben einem Formular mit dem **Titel**, der **Zusammenfassung** des Eintrags, einer Markierungsauswahl und einem Notizenfeld. Durch die Bearbeitung können Sie Einträge korrigieren oder kategorisieren – Fehler kennzeichnen, wichtige Ergebnisse hervorheben oder eine Zusammenfassung neu schreiben. Beim Speichern wird die Seite an der Position des bearbeiteten Eintrags neu generiert; Eine von Ihnen manuell gesetzte Markierung wird niemals von der KI oder einer Regel überschrieben.

Der schnellste Weg, einen einzelnen Eintrag zu markieren, ist die Zeitleiste selbst: Klicken Sie mit der rechten Maustaste darauf und wählen Sie **Marker setzen…**.

### Screenshot-Notizen und Anmerkungen

Ein Screenshot allein sagt selten aus, warum er aufgenommen wurde. Wenn Sie mit der rechten Maustaste auf eines davon klicken – das Miniaturbild in der Timeline oder den Leuchtkasten in voller Größe – bietet es zwei eigene Aktionen:

| Aktion | Was es bewirkt |
|--------|--------------|
| **Screenshot bearbeiten…** | Öffnet den unten beschriebenen Editor |
| **Screenshot mit KI analysieren** | Führt die [KI-Screenshot-Analyse](#ki-screenshot-analyse) für dieses Bild aus; angeboten, wenn ein bildfähiges KI-Profil konfiguriert ist und die Richtlinie die Analyse zulässt |
| **Screenshot exportieren…** | Speichert das Bild mit seinen Markierungen in einer von Ihnen ausgewählten Datei. |

Bearbeiten und exportieren befinden sich ebenfalls im Kontextmenü der Tabelle des Bearbeitungsmodus-Eintrags und das Doppelklicken auf eine Zeile mit Screenshot öffnet den Editor direkt. Diese Aktionen erscheinen nur innerhalb von korTTY: Ein eigenständiges Fenster in einem Browser kann weder das Journal ändern noch ein Dateidialogfeld öffnen.

Innerhalb von korTTY ist der **Titel** des Journals direkt von der Seite aus bearbeitbar: Doppelklicken Sie darauf oder wählen Sie mit der rechten Maustaste den Eintrag aus und wählen **Journal umbenennen…** – dies ist die gleiche Funktion, die der Manager anbietet, unter Berücksichtigung der gleichen Organisationspolitik.

Der Editor selbst:

| Werkzeug | Was es tut |
|------|--------------|
| **Stift** | Ein dicker freihändiger Strich zum Umkreisen oder Unterschreiben von Inhalten |
| **Kasten** | Ein Rechteck, das Sie ziehen können, um die gewünschte Größe zu erreichen |
| **Unlesbar** | Ein Rechteck, dessen Inhalt in Blocks aufgeteilt wird, bis er nicht mehr lesbar ist — zum Verstecken eines Werts, während der umliegende Kontext erhalten bleibt. **Breite** bestimmt die Dichte der Blocks. |
| **Text** | ein Etikett mit dunklem Halbmond, damit es auf einem hellen Terminal-Hintergrund lesbar bleibt |

**Farbe** gilt für den nächsten Markierungsabschnitt (rot als Standard), **Breite** bestimmt die Stiftstärke und skaliert die Textbezeichnungen entsprechend. **Rückgängig** entfernt die letzte Markierung, **Alle entfernen** löscht alle Markierungen. Unten neben dem Bild befindet sich ein fünfzeiliges **Notiz**-Feld für den Hinweis, der der Aufnahme zugeordnet ist; es handelt sich um denselben Hinweis, den der Eintrag sonst im Journal trägt.

Markierungen werden als Daten gespeichert und können jederzeit erneut bearbeitet werden – beim erneuten Öffnen des Editors werden sie erneut angezeigt und nicht als abgeflachtes Bild. Die markierte Version wird zum Bild, das in der Zeitleiste, im PDF, im Markdown-Export und im HTML-Bundle angezeigt wird. Die nicht markierte Aufnahme verbleibt im Journalordner als `shot-000004.orig.png`.

!!! warning
    Die Annotation wird **auf das Bild** gelegt – auch **Unlesbar** eingeschlossen. Es handelt sich nicht um eine Schwärzung. Der ungezeichnete Abschnitt bleibt auf dieser Maschine im Journal-Ordner als `shot-000004.orig.png` erhalten. Er wird niemals in eine Exportversion kopiert, sodass ein von Ihnen gezeichneter Bereich über etwas Sensibles hinaus in der exportierten Dokumentation erhalten bleibt. Jedoch kann jeder, der den Journal-Ordner selbst zugreifen kann, den ursprünglichen Inhalt weiterhin öffnen. Um etwas aus dem Journal endgültig zu entfernen, verwenden Sie **Suche und Ersatz** oder die Schwärzungsregeln – und löschen Sie den `.orig.png` manuell, falls das Problem ein Screenshot ist.

### Markers

![Managing journal markers](../assets/screenshots/journal/journal-markers.png)

Über die vier integrierten Markierungen hinaus (**Keine**, **Info**, **Wichtig**, **Fehler**) können Sie Ihre eigenen Markierungen definieren – einen Namen wie *Softwareinstallation* und eine Farbe Ihrer Wahl. **Markierungen verwalten…** neben der Markierungsauswahl öffnet den Editor:

- **Farbe**, **Name** und **Zählt als**. Der letzte entscheidet, auf welchen integrierten Wert der Marker herabgestuft wird, was dafür sorgt, dass ein Journal in einem älteren korTTY lesbar bleibt und was dafür sorgt, dass ein *Outage*-Marker zur Fehlersumme zählt.
- **Hinzufügen**, **Duplizieren** und **Löschen**. Die Löschung entfernt auch die Regeln, die auf diesen Marker verwiesen haben, sodass keine Regel mehr schweigend nichts tut.

Markierungen leben in Ihren Einstellungen und sind in jedem Journal verfügbar. Eine von Ihnen tatsächlich verwendete Markierung wird zusätzlich in diesem Journal gespeichert, sodass ein exportiertes oder freigegebenes Journal automatisch in den richtigen Farben gerendert wird – und das spätere Löschen einer Markierung ändert nie das Aussehen eines vorhandenen Journals.

#### Automatische Markierer

Die untere Hälfte desselben Dialogs enthält Regeln, die selbst Markierungen setzen. Aktivieren Sie **Markierungen automatisch in neuen Einträgen setzen** und fügen Sie dann eine Regel pro Suchbegriff hinzu:

| Spalte | Bedeutung |
|--------|---------|
| **Aktiv** | Ob die Regel überhaupt angewendet wird |
| **Marker** | Welcher Marker gesetzt werden soll |
| **Suchbegriff** | Ein Wort oder ein ganzer Satz; Wenn **Regex** deaktiviert ist, entspricht es buchstäblich |
| **Regex** | Behandeln Sie den Suchbegriff als regulären Ausdruck |
| **Groß-/Kleinschreibung ignorieren** | Standardmäßig aktiviert |

Die Regeln werden von oben nach unten überprüft und das erste Spiel gewinnt – verwenden Sie ▲/▼, um sie zu ordnen. Sie sehen sich den Titel, die Zusammenfassung, die Notiz und die Ein-/Ausgabeauszüge des Eintrags an, niemals das Roherfassungsprotokoll, und sie folgen den Schwärzungsregeln, sodass ein geschwärztes Geheimnis niemals eines auslösen kann.

Eine von Ihnen manuell gesetzte Markierung wird niemals überschrieben; ein Marker, der von der KI vorgeschlagen wurde. **Jetzt anwenden** führt die Regeln für das aktuell geöffnete Journal aus und meldet, wie viele Einträge sich geändert haben – das funktioniert auch, während die Sitzung noch läuft. Aktivieren Sie **Auch manuell gesetzte Markierungen überschreiben** nur dann, wenn Sie wirklich möchten, dass Ihre eigenen Auswahlen ersetzt werden.

### Suchen und ersetzen

Durch die Suche wird ein Begriff gefunden. **Suchen & ersetzen** schreibt jedes Vorkommen neu. Verwenden Sie es, um etwas zu löschen, das nicht im Journal bleiben darf – ein in einen sichtbaren Befehl eingefügtes Passwort, ein Token in einer Serverantwort – oder einfach um ein wiederkehrendes Wort zu korrigieren.

Es ist von zwei Stellen aus erreichbar: der **Suchen & ersetzen…**-Schaltfläche im Bearbeitungsmodus und der **Ersetzen…**-Schaltfläche in [der Suchleiste auf der Journalseite](#suche-im-journal), die das gleiche Dialogfeld öffnet, wobei der gesuchte Begriff bereits ausgefüllt ist. Diese Schaltfläche erscheint nur innerhalb von korTTY – die Seite wird *aus* den Journaldateien generiert, sodass eine Kopie in einem Browser suchen kann, aber nichts umschreiben kann.

| Option | Wirkung |
|--------|--------|
| **Suchen nach** / **Ersetzen durch** | Der zu suchende Text und was an seiner Stelle eingefügt werden soll (standardmäßig `***`) |
| **Regulärer Ausdruck** | Behandelt den Suchtext als regulären Ausdruck; `$1` im Ersatz fügt eine erfasste Gruppe ein |
| **Groß-/Kleinschreibung ignorieren** | Entspricht jeder Groß-/Kleinschreibung |
| **Capture-Log ebenfalls umschreiben** | Standardmäßig aktiviert. Aus ändert nur die Journaleinträge und lässt das Capture-Log unberührt |
| **Treffer zählen** | Ein Probelauf über das echte Journal: Gibt an, wie viele Eintragsfelder und Protokollzeilen sich *ändern* würden, ohne etwas zu schreiben |

Das Ersetzen umfasst jeden Eintragstitel, jede KI-Zusammenfassung, jede Notiz und jeden Auszug sowie – sofern Sie es nicht deaktiviert haben – jeden Teil des Capture-Logs, einschließlich der komprimierten Teile. Der Dateikopf und jede unberührte Zeile bleiben exakt erhalten, sodass das Protokoll seine Struktur behält.

!!! warning
    Durch das Ersetzen werden die vorhandenen Journaldateien neu geschrieben und können nicht rückgängig gemacht werden. Verwenden Sie zuerst **Treffer zählen**, insbesondere bei einem regulären Ausdruck. Dokumente, die Sie bereits exportiert haben, sind separate Dateien und werden nicht geändert – exportieren Sie sie anschließend erneut. Ein Journal, das noch geschrieben wird, kann nicht neu geschrieben werden; beenden Sie zunächst die Sitzung.

Der Suchtext wird niemals in korTTYs eigenes Protokoll geschrieben, da er für eine Schwärzung das Geheimnis ist.

### Eintrag wird gelöscht

**Eintrag löschen** entfernt nach einer Bestätigung den ausgewählten Timeline-Eintrag und damit auch die Bilddatei eines Screenshot-Eintrags. Das Capture-Log wird nicht berührt. Um einen Text auch von dort zu entfernen, verwenden Sie „Suchen und Ersetzen“.

## Exportieren

Das **Exportieren**-Menü im Manager und im Viewer bietet drei Formate:

| Format | Inhalt |
|--------|---------|
| **PDF** | Das einfache Journal: Kopfzeile mit Verbindungsdetails und Statistiken, nach Tagen gruppierte Einträge mit Markierungsplaketten, Eingabe-/Ausgabeauszüge, Notizen – und, falls ausgewählt, verkleinerte eingebettete Screenshots |
| **Markdown** | Das gleiche einfache Journal als `.md`-Datei; Screenshots werden in einen benachbarten Ordner `<name>-files/` kopiert |
| **HTML-Bundle (vollständig)** | Ein Zip-Archiv des gesamten Journals – `journal.html`, `journal.xml`, die dekomprimierten Aufnahmeprotokolle und alle Screenshots – so angeordnet, dass die Seite sofort nach dem Entpacken funktioniert |

PDF und Markdown fragen, ob Screenshots enthalten sein sollen.

### Es wird nur ein Teil eines Journals exportiert

![Journal export options](../assets/screenshots/journal/journal-export-options.png)

Der Exportdialog kann eingrenzen, was tatsächlich in das Dokument gelangt. Jeder Filter ist optional und in der Fußzeile wird live gezählt, wie viele Einträge exportiert werden.

**Zeitfenster.** Fügen Sie so viele hinzu, wie Sie möchten; Ein Eintrag muss nur in *einen* davon fallen, daher exportiert `08:00–12:00` plus `14:00–16:00` beide Blöcke eines Tages. Wenn Sie die Datumsangaben leer lassen, wird das Fenster auf jeden Tag angewendet, den das Journal umfasst, und ein Fenster, dessen Beginn nach seinem Ende liegt, läuft über Mitternacht.

Die Zeiten können ungefähre Angaben sein – das ist der Punkt:

- Die Eingabe ist fehlerverzeihend: `8`, `08`, `8:00`, `8.30` und `0800` funktionieren alle.
- Jedes Fenster wird um die **Toleranz** erweitert (standardmäßig ± 5 Minuten, für genaue Grenzen auf 0 setzen).
- Ein Eintrag fasst alles seit dem vorherigen zusammen, sodass ein um 12:03 Uhr geschriebener Eintrag, der 11:58 Uhr abdeckt, immer noch zu einem Fenster gehört, das um 12:00 Uhr endet. Ohne das würde der Eintrag an der Grenze – normalerweise der interessante – aus jedem Fenster fallen.

**Thema.** Ein Wort oder ein Satz, abgeglichen mit Titeln, Zusammenfassungen, Notizen und Auszügen; **Regulärer Ausdruck** wechselt zum Regex-Matching. **Lassen Sie die KI die Einträge auswählen** übergibt das Thema und die Einträge stattdessen an die KI, die findet, dass *Apache installiert* wird, auch wenn keines dieser Wörter wörtlich vorkommt. Es benötigt ein KI-Profil und ist ansonsten ausgegraut; Wenn das Modell nicht erreichbar ist oder mit Unsinn antwortet, greift der Export auf die Textübereinstimmung zurück und sagt dies, anstatt fehlzuschlagen.

**Markierungen.** Alle Einträge, nur markierte oder nur die Markierungen, die Sie ankreuzen. Die Liste zeigt die Markierungen, die das Journal tatsächlich verwendet.

### Filterte HTML-Bundles

Ohne Filter bleibt das HTML-Bundle die wörtliche Kopie, die es immer war. Mit einem wird es **neu aufgebaut**:

- `journal.xml` enthält ausschließlich die exportierten Einträge, und die Markierungsdefinitionen folgen ihnen.
- Das Capture-Log wird auf die Sequenzbereiche umgeschrieben, auf die sich diese Einträge beziehen, und auf die angeforderten Zeitfenster zugeschnitten. Dies ist nicht optional – ein Bundle ist das Artefakt, das Sie jemand anderem geben, und zwölf Einträge neben acht Stunden Terminalausgabe wären genau das Leck, das der Filter verhindern soll.
- Nur die Screenshots, die noch referenziert werden, werden kopiert, und `journal.html` wird neu gerendert, sodass seine tiefen Links funktionieren.
- Die Header-Zählungen werden neu berechnet, um mit den Angaben im Bundle übereinzustimmen.

Jeder gefilterte Export – PDF, Markdown und Bundle gleichermaßen – trägt ein **Auszug**-Banner mit dem Namen des Bereichs und der Eintragsanzahl, sodass niemand es mit der gesamten Sitzung verwechselt.

### Mehrere Tagebücher exportieren

Wenn mehr als ein Journal ausgewählt ist, erstellt der Export ein einzelnes ZIP-Archiv, das jedes Journal separat hält: ein PDF- oder Markdown-Dokument pro Journal oder ein Ordner pro Journal für das HTML-Bundle. Namen werden aus den Journaltiteln übernommen, mit einem numerischen Suffix, wenn zwei Titel kollidieren.

Filter gelten für jede ausgewählte Journal. Ein Journal, in dem der Filter mit nichts übereinstimmt, wird übersprungen und anschließend gemeldet, sodass ein leeres Ergebnis einen Export von zehn Journalen nicht zum Scheitern bringen kann; Nur wenn *jedes* Journal leer ausgeht, wird der Export abgelehnt – bevor eine Datei geschrieben wird.

Jedes Archiv – einschließlich des HTML-Bundles eines einzelnen Journals – kann **mit einem Passwort geschützt** werden. Die Option befindet sich im Exportdialog und verschlüsselt das Archiv mit **AES-256**; ohne sie wird das Archiv unverschlüsselt geschrieben. Da Journale vollständige Terminal-Mitschriften enthalten, ist die Wahl eines ungeschützten Archivs eine bewusste Wahl.

!!! warning
    Das Passwort wird nirgendwo gespeichert. korTTY kann ein verschlüsseltes Archiv nicht wiederherstellen, wenn Sie es verlieren.

### Fußzeile und Wasserzeichen

Standardmäßig enthält jedes exportierte Dokument eine Fußzeile, die angibt, dass es mit korTTY erstellt wurde, mit einem Link zum Projekt-Repository – unten auf jeder PDF-Seite, am Ende der Markdown-Datei und in der Fußzeile der Journalseite innerhalb des HTML-Pakets. PDFs können zusätzlich ein diagonales Wasserzeichen tragen, das **standardmäßig deaktiviert** ist.

Beide werden unter [**Konfiguration → Globale Einstellungen → Export**](../reference/settings/export.md) konfiguriert, wo Sie den Fußzeilentext ändern, die Fußzeile ausschalten, das Wasserzeichen aktivieren und dessen Text und Farbe auswählen können. Die gleichen Einstellungen gelten für KI-Chat-Exporte.

## Unternehmensrichtlinie

Administratoren können die Funktion verweigern (`session-journal` unter `[rule.features]`), oder bestimmen Sie sein Verhalten über `[rule.session-journal]`: Erzwingen Sie ein Journal für jede Verbindung, beheben das Log-Format, KI-Zeilenfenster oder Speicherverzeichnis, verbieten Sie das Umbenennen oder Löschen von Journals, legen Sie eine Namensvorlage fest, erzwingen Sie den schließenden KI-Titel, zwingen Sie das [KI-Bildschirmfoto-Analyse](#ki-screenshot-analyse) Ein- oder ausgeschaltet – ein erzwungenes *ausgeschaltet* deaktiviert zudem die manuelle Ausführung pro Screenshot – und verhindert die On-Demand-KI über den Journalinhalt.`ai-ask = false` entfernt den Viewer [Fragen & Antworten-Panel](#die-ki-nach-einem-journal-fragen) und des Managers [KI-Suche](#ki-suche-in-allen-journale) während die Zusammenfassungen unverändert bleiben). Siehe [Richtlinienkonfiguration](../reference/enterprise-policy.md) für die Tasten.

### Automatische Schwärzung

Eine `[[rule.session-journal.replace]]`-Liste sorgt dafür, dass korTTY das Suchen und Ersetzen automatisch anwendet, mit regulären Ausdrücken, wenn der Administrator dies wünscht – für Cloud-Zugriffsschlüssel, interne Hostnamen, Ticketnummern und alles, was niemals in einem Transkript landen darf:

```toml
[[rule.session-journal.replace]]
pattern = "AKIA[0-9A-Z]{16}"
replacement = "***AWS-ACCESS-KEY***"
regex = true
label = "AWS access keys"
```

Diese Regeln werden im Capture-Thread ausgeführt, bevor eine Zeile geschrieben wird, sodass ein übereinstimmender Text überhaupt nicht in die Protokolldatei gelangt. Sie werden auch auf KI-Zusammenfassungen und -Notizen angewendet. Es gilt jede Regel jeder übereinstimmenden Richtlinienstufe – eine Regel, die ein Muster hinzufügt, schaltet niemals ein anderes aus. Im obigen Dialog erfahren Sie, wie viele vorgeschriebene Regeln in Kraft sind. Journale, die vor dem Inkrafttreten einer Regel geschrieben wurden, werden nicht rückwirkend umgeschrieben; Verwenden Sie für diese Suchen und Ersetzen. Siehe [Richtlinienkonfiguration](../reference/enterprise-policy.md#rulesession-journalreplace) für jeden Schlüssel.
