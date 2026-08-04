---
title: Sitzungsjournal
---

# Sitzungsjournal

Das Sitzungsjournal dokumentiert eine Terminalsitzung als lesbare Zeitleiste: Jede Ausgabezeile des Servers und jeder von Ihnen eingegebene Befehl werden in ein Capture-Log geschrieben, eine KI verdichtet die Aktivität regelmäßig in kurzen Journal-Einträgen und das Ergebnis wird als eigenständige HTML-Seite mit Verbindungsdetails, farbcodierten Auszügen, Screenshots und Ihren eigenen Notizen gerendert. Journale werden wie gespeicherte Chats verwaltet – in einem eigenen Managerfenster mit Suche, Bearbeitung und Export.

![Session journal flow](../assets/diagrams/session-journal-flow.svg)

## Speicher und Formate

Jedes Journal ist ein eigenständiges Verzeichnis unter `~/.kortty/journals` (konfigurierbar unter **Einstellungen > Protokollierung > Sitzungsjournal**):

| Datei | Zweck |
|------|---------|
| `journal.xml` | Das kuratierte Journaldokument: Metadaten, KI-Zusammenfassungen, Markierungen, Notizen, Screenshot-Referenzen |
| `session-log.json` / `.xml` / `.yaml` | Das Nur-Anhang-Capture-Log – zeitgestempelte Serverausgabe und typisierte Eingabezeilen mit Sequenz-IDs |
| `session-log-2.json.gz`, … | Gedrehte Stammteile; Geschlossene Teile werden automatisch gzip-komprimiert, das Journal löscht niemals den Verlauf |
| `journal.html` | The generated timeline page, regenerated automatically after every change |
| `screenshots/*.png` | Screenshots you attached during the session |

Das Capture-Log-Format kann im Dialogfeld **Optionen** des Journalmanagers ausgewählt werden: **JSON** (JSON Lines, Standard), **XML** oder **YAML**. Alle Formate enthalten die gleichen Felder und jeder Eintrag besteht aus genau einer Zeile, sodass ein Absturz nie mehr als die letzte Zeile beschädigt. JSON ist die Standardeinstellung, weil die Protokolltools es lesen, ohne dass ein eigener Parser erforderlich ist – und nicht, weil es Platz spart. Die Größe trennt die drei kaum voneinander: Bei normaler Ausgabe ist XML etwa 9 Byte pro Eintrag kleiner, bei Ausgabe voller `<`, `>` und `&` ist JSON etwa 10 % kleiner (XML muss diese maskieren, JSON nicht), und sobald ein fertiger Teil gzip-komprimiert ist, liegen alle drei innerhalb von 2 % voneinander. YAML ist am größten, da es JSON-Zuordnungen mit dem Präfix `- ` schreibt. Der aktive Protokollteil bleibt für Live-Lesevorgänge unkomprimiert; Rotation (Standard 25 MB pro Teil) und Sitzungsende komprimieren fertige Teile auf `.gz`.

## Das Journal wird aktiviert

### Automatisch für eine Verbindung

1. Öffnen Sie **Verbindungen > Verbindungen verwalten** und bearbeiten Sie eine Verbindung
2. On the **Journal** tab, enable **Enable session journal for this connection**
3. Passen Sie optional **Getippte Eingabezeilen erfassen**, **KI-Zusammenfassungen generieren** und das **Zusammenfassungsintervall pro Verbindung** an.

Jede zukünftige Verbindung dieses Servers startet dann automatisch sein Journal. Das Journal übersteht erneute Verbindungen – ein Journal pro Tab-Lebensdauer, mit einer Wiederverbindungsmarkierung im Protokoll.

### Rückwirkend für eine laufende Sitzung

Verwenden Sie **Extras > Sitzungsjournal starten/stoppen** (++ctrl+alt+t++), das Tab-Kontextmenü (**Sitzungsjournal > Journal starten**) oder die Schaltfläche **Journal starten** in der Journalleiste. Der vorhandene Scrollback wird zunächst als Starteinträge in das Journal importiert, sodass die Zeitleiste abdeckt, was bereits passiert ist. Anschließend wird die Live-Aufnahme angehängt.

### Die Journalleiste

Während ein Journal verfügbar ist, zeigt eine Leiste unter dem Terminal seinen Status an (**Journal aktiv seit HH:MM**) und bietet **Journal stoppen**, **Screenshot** und **Notiz**:

- **Screenshot** (++ctrl+alt+c++, auch im Rechtsklick-Menü des Terminals) erstellt einen Schnappschuss des Terminals – in einem geteilten Layout erfasst das Rechtsklick-Menü genau den Bereich unter dem Cursor – und legt ihn in der Journal-Timeline ab.
- **Notiz** öffnet eine kleine Eingabe für eine Freitext-Bemerkung, die als eigener Timeline-Eintrag an der aktuellen Position erscheint.

## KI-Zusammenfassungen

Während das Journal läuft, liest die KI-Zusammenfassung regelmäßig die neuesten Capture-Log-Zeilen und hängt einen kompakten Journaleintrag an (Titel, Zusammenfassung und eine vorgeschlagene Markierung: Info, wichtig oder Fehler). Standardwerte und Grenzwerte:

| Option | Wo | Standard |
|--------|-------|---------|
| Zusammenfassungsintervall | **Einstellungen > Protokollierung > Sitzungsjournal** (global) oder pro Verbindung | 5 Minuten |
| Max. Terminalzeilen pro KI-Auswertung | Journalmanager **Optionen** | 100 |
| Token-Budget für Kontextfüllung | Journalmanager **Optionen**, sichtbar, wenn die maximale Anzahl an Zeilen 0 beträgt | 130000 |
| Split backlog into multiple prompts (chunking) | Journal manager **Options** | off |
| AI profile for summaries | Journal manager **Options** or **Settings > Logging > Session Journal** | Default profile |

Summaries use your **default AI profile** unless you pick a dedicated journal profile. That choice is available in three equivalent places: the journal manager's **Options** dialog, **Settings > Logging > Session Journal**, and **AI > AI Manager > Local AI** next to the Text and Coding roles. The Text/Coding role profiles themselves are deliberately not used for the journal.

Wenn Sie **max Zeilen auf 0** setzen, wird auf Kontextfüllung umgeschaltet: Der Zusammenfassungstext packt so viele der neuesten Zeilen, wie in das konfigurierte Token-Budget passen. Wenn **Chunking** aktiviert ist, wird das gesamte Backlog verarbeitet und nicht nur das neueste Fenster – aufeinanderfolgende Fenster der konfigurierten Größe, jeweils eine KI-Eingabeaufforderung.

!!! warning
    Chunking can take very long on large sessions and is not recommended for everyday use — it is intended for power users with capable hardware and a powerful LLM.

Wenn die Sitzung endet, schreibt die Zusammenfassung einen abschließenden **Sitzungszusammenfassungs-Eintrag** (was wurde erreicht, welche Fehler sind aufgetreten). Optional – **Lassen Sie die KI das Journal betiteln, wenn die Sitzung endet** im Dialogfeld „Optionen“ – ein abschließender KI-Aufruf benennt das Journal, es sei denn, Sie haben es manuell umbenannt.

!!! note
    Das Journal funktioniert ohne KI: Wenn kein KI-Profil verfügbar ist, KI-Funktionen deaktiviert oder Zusammenfassungen ausgeschaltet sind, zeichnet die Zeitleiste stattdessen rohe Aktivitätseinträge auf. KI-Zusammenfassungsaufforderungen verwenden niemals Tools für den Internetzugang; Der Terminalauszug geht nur an das konfigurierte AI-Profil.

## Passwortschutz

Getippte Eingaben werden nur als vollständig übermittelte Zeilen erfasst und mehrere Ebenen halten Passwörter aus dem Journal fern:

- Wenn die Serverausgabe mit einer Passwortabfrage endet (`password:`, `[sudo] password for …`, `passphrase`, `PIN` und lokalisierte Varianten), wird die nächste übermittelte Eingabezeile unterdrückt und als geschwärzter Platzhalter protokolliert – der eingegebene Text wird nie zwischengespeichert oder geschrieben.
- The connection's own stored password is additionally replaced by `***` wherever it would appear in captured text.
- **Eingegebene Befehlszeilen erfassen** kann pro Verbindung vollständig deaktiviert werden; Befehle erscheinen dann nur noch als Echo des Servers im Ausgabestream.

!!! warning
    Bei der Prompt-Erkennung handelt es sich um eine Heuristik – ein Remote-Terminal kann nicht zuverlässig erkennen, wann der Server das Echo deaktiviert hat. Exotische oder Vollbild-Passwortabfragen werden möglicherweise nicht erkannt und in sichtbare Befehle eingefügte Geheimnisse (außer den Anmeldeinformationen der Verbindung) werden wie jeder andere Text erfasst. Behandeln Sie Protokolle sensibler Sitzungen entsprechend.

Wenn trotzdem etwas durchgerutscht ist, dann das des Viewers [suchen und ersetzen](#search-and-replace) entfernt es nachträglich aus den Einträgen und dem Capture-Log. Administratoren können korTTY-Muster auch automatisch schwärzen lassen – siehe [Unternehmenspolitik](#unternehmensrichtlinie) unten.

## The journal page

`journal.html` ist vollständig eigenständig (keine externen Ressourcen) und funktioniert im integrierten Viewer, in jedem Browser und innerhalb des exportierten Bundles:

- A sticky header shows who was connected to which server, start time, duration, and counts for entries, commands, errors and screenshots, plus the journal description. Live journals show a **live** badge. The line below the title only carries what the title does not already say, so a journal named after its endpoint states the connection once instead of three times.
- Die Zeitleiste gruppiert Einträge nach Tag; Jeder Eintrag trägt seine Zeit, einen Markierungspunkt und ein Abzeichen in der Farbe, die Sie dieser Markierung gegeben haben, den AI-Titel und die Zusammenfassung sowie farbcodierte Eingabe- (grün) und Ausgabeauszüge (blau).
- Durch Klicken auf einen Eintrag wird von unten ein Protokollfenster mit dem genauen Capture-Logbereich hinter diesem Eintrag eingeblendet. Das Panel verfügt über eine eigene Bildlaufleiste, ein Suchfeld mit Trefferzähler und ▲/▼-Navigation (++enter++ / ++shift+enter++ zyklisch auch Treffer, ++esc++ schließt) und färbt Eingabe- und Ausgabezeilen unterschiedlich ein.
- Screenshot-Einträge zeigen Miniaturansichten; Wenn Sie darauf klicken, wird ein Leuchtkasten in voller Größe geöffnet.
- Die Seite wird standardmäßig dunkel gerendert, folgt der Hell/Dunkel-Einstellung des Systems und verfügt über eine eigene Designumschaltung.
- Screenshots, Auszugsfenster, die Zeitleistenspalte und das Protokollfenster passen sich dem Fenster an, sodass die Seite sowohl in einem schmalen Viewer-Tab als auch im Vollbildmodus lesbar bleibt. Lange Ausschnitte scrollen in ihrem eigenen Rahmen, anstatt die Zeitleiste zu dehnen.

### Searching the journal

Die Lupe in der Kopfzeile öffnet eine Suchleiste direkt unter den Verbindungsdetails (++ctrl+f++ funktioniert auch). Durch die Eingabe eines Begriffs oder eines ganzen Satzes wird jedes Vorkommen auf der Zeitleiste hervorgehoben – Eintragstitel, KI-Zusammenfassungen, Eingabe- und Ausgabeauszüge, Notizen und Zeitstempel – und ein Übereinstimmungszähler angezeigt; ▲ und ▼ oder ++enter++ / ++shift+enter++ springen zwischen den Treffern, ++esc++ oder ✕ schließt die Leiste und löscht die Hervorhebung.

!!! note
    This searches the journal entries. The raw capture log has its own search inside the log panel, and the journal manager can search across *all* journals with **Search contents**.

### Jumping between marked entries

Wenn mindestens ein Eintrag eine Markierung trägt, erhält die Kopfzeile eine Schaltfläche ◆, die eine Markierungsleiste öffnet. Wählen Sie **Alle Markierungen** oder eine einzelne aus und gehen Sie dann mit ▲ und ▼ durch die Übereinstimmungen – die Liste wird umbrochen und der aktuelle Eintrag wird in die Ansicht gescrollt und kurz umrissen. ++alt+down++ und ++alt+up++ machen dasselbe ohne die Maus, und ++alt+m++ schaltet die Leiste um; ++esc++ schließt es.

Ein Journal ohne Markierungen enthält weder die Schaltfläche noch die Leiste, sodass die Kopfzeile unverändert bleibt.

### Mit der Maus einen Zeitbereich auswählen

Inside korTTY the header also carries a ⇥ button that switches the timeline into range mode. Click the first entry, then the last one — everything between them is highlighted and the bar shows the span and how many entries it covers. The order does not matter: clicking the later entry first works just as well.

- **Add another window** puts the current selection aside and starts a new one, so several windows can be collected in one pass.
- **Für den Export verwenden** öffnet den Exportdialog mit bereits ausgefüllten Fenstern.
- **Abbrechen** oder ++esc++ verlässt den Bereichsmodus.

While range mode is on, clicking an entry selects instead of opening the log panel. The button is absent in an external browser, because exporting needs the app.

In der Eingabetabelle des Bearbeitungsmodus ist das Gleiche auch ohne Zeitleiste verfügbar: Wählen Sie mehrere Zeilen aus, klicken Sie mit der rechten Maustaste und wählen Sie **Auswahl als Zeitfenster übernehmen**.

### Copying content

Jeder Eintrag trägt in der oberen rechten Ecke eine Schaltfläche zum Kopieren: Texteinträge kopieren den gesamten Eintrag, Screenshot-Einträge kopieren das Bild. Das Protokollfenster verfügt über dieselbe Schaltfläche für den Protokollabschnitt, den es gerade anzeigt.

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

The viewer's **Appearance** button opens a small panel with the rest:

| Einstellung | Wirkung |
|---------|--------|
| **Farbschema** | *Automatisch* behält das eigene Dunkel/Hell-Paar der Seite bei und folgt dem Betriebssystem. *Dem Terminal-Theme folgen* leitet die Seitenfarben vom Hintergrund und Vordergrund Ihres Terminals ab. Die übrigen Einträge (Paper, Midnight, Ocean, Forest, Retrowave, High contrast) sind feste Paletten. |
| **Textschrift** | Die Schriftart für Überschriften, Zusammenfassungen und Notizen. *(Standard)* stellt die Standardschriften der Seite wieder her. |
| **Festbreitenschrift** | Die Schriftart für die Ein-/Ausgabeauszüge und das Protokollfenster. |
| **Textgröße** | Die gleichen 70–250 % wie die Tasten A−/A/A+. |

Änderungen werden sofort im Viewer in der Vorschau angezeigt und für jede Journalseite gespeichert. Bei einem festen Schema bleibt der ◐-Schalter der Seite sichtbar, ist jedoch deaktiviert, da das Schema bereits über die Farben entscheidet.

## Journale verwalten

**Tools > Sitzungsjournale…** (++ctrl+alt+j++) öffnet den Journalmanager: alle Journale in einer Tabelle, sortiert nach Startzeit (neueste zuerst) mit Dauer, Verbindung, Server, Titel und Eintragsanzahl. Laufende Journale sind markiert und können im laufenden Betrieb weder umbenannt noch gelöscht werden.

![Session journal manager](../assets/screenshots/journal/journal-manager.png)

- Das Filterfeld entspricht Titel, Verbindung, Host, Benutzer und Beschreibung; Wenn Sie **Inhalte durchsuchen** aktivieren, werden zusätzlich die Journaleinträge gescannt und Protokolle aller Journale im Hintergrund erfasst.
- **Öffnen** (oder Doppelklick) öffnet den Journal-Viewer; **Umbenennen** ändert den Titel; **Löschen** fragt nach einer Bestätigung und entfernt dann dauerhaft den Journalordner einschließlich des Protokolls und aller Screenshots.
- Es können mehrere Journale gleichzeitig ausgewählt werden (Klick ++ctrl++ / ++shift++), um sie in einem Schritt zu löschen oder zu exportieren. Laufende Journale können nicht umbenannt oder gelöscht werden.
- Der Bereich **Beschreibung** unterhalb der Tabelle speichert eine Freitextbeschreibung pro Journal; Es erscheint auf der Journalseite und in jedem Export und wird in die Inhaltssuche einbezogen.
- **Optionen** enthält die oben beschriebenen globalen Erfassungs- und KI-Einstellungen.

### Der Viewer und die Bearbeitung

![Session journal viewer](../assets/screenshots/journal/journal-viewer.png)

Der Viewer zeigt die Journalseite in einem eingebetteten Browser an und aktualisiert sich automatisch, während das Journal noch geschrieben wird. **Im Browser öffnen** übergibt die Seite an Ihren Systembrowser. **Bearbeiten** teilt die Ansicht: eine Eintragstabelle neben einem Formular mit dem **Titel**, der **Zusammenfassung** des Eintrags, einer Markierungsauswahl und einem Notizenfeld. Durch die Bearbeitung können Sie Einträge korrigieren oder kategorisieren – Fehler kennzeichnen, wichtige Ergebnisse hervorheben oder eine Zusammenfassung neu schreiben. Beim Speichern wird die Seite an der Position des bearbeiteten Eintrags neu generiert. Eine von Ihnen manuell gesetzte Markierung wird niemals von der KI oder einer Regel überschrieben.

Der schnellste Weg, einen einzelnen Eintrag zu markieren, ist die Zeitleiste selbst: Klicken Sie mit der rechten Maustaste darauf und wählen Sie **Marker setzen…**.

### Screenshot-Notizen und Anmerkungen

Ein Screenshot allein sagt selten aus, warum er aufgenommen wurde. Wenn Sie mit der rechten Maustaste auf eines davon klicken – das Miniaturbild in der Timeline oder den Leuchtkasten in voller Größe – bietet es zwei eigene Aktionen:

| Aktion | Was es bewirkt |
|--------|--------------|
| **Edit screenshot…** | Opens the editor described below |
| **Screenshot exportieren…** | Speichert das Bild mit seinen Markierungen in einer von Ihnen ausgewählten Datei. |

Beide befinden sich auch im Kontextmenü der Eingabetabelle des Bearbeitungsmodus, und ein Doppelklick auf eine Screenshot-Zeile öffnet den Editor direkt. Sie erscheinen nur innerhalb von korTTY: Eine eigenständige Seite in einem Browser kann weder das Journal umschreiben noch einen Dateidialog erreichen.

In korTTY kann der **Titel** des Journals auch von der Seite aus bearbeitet werden: Doppelklicken Sie darauf oder klicken Sie mit der rechten Maustaste darauf und wählen Sie **Journal umbenennen…** – die gleiche Umbenennung bietet der Manager an, unterliegt den gleichen Organisationsrichtlinien.

Der Herausgeber selbst:

| Werkzeug | Was es macht |
|------|--------------|
| **Stift** | Ein dicker Freihandstrich zum Einkreisen oder Unterstreichen von etwas |
| **Box** | A rectangle you drag to whatever size you need |
| **Unlesbar** | Ein Rechteck, dessen Inhalt in Blöcke vergröbert wird, bis er nicht mehr gelesen werden kann – um einen Wert auszublenden, während der umgebende Kontext an Ort und Stelle bleibt. **Breite** legt fest, wie grob die Blöcke sind. |
| **Text** | Ein Etikett mit einem dunklen Halo, damit es auf einem hellen Terminalhintergrund lesbar bleibt. |

**Farbe** gilt für die nächste Markierung (zu Beginn rot), **Breite** legt die Stiftstärke fest und skaliert die Textbeschriftungen damit. **Rückgängig** entfernt die letzte Markierung, **Alle entfernen** löscht sie. Unterhalb des Bildes befindet sich ein fünfzeiliges **Notiz**-Feld für die Bemerkung, die zum Screenshot gehört; Es handelt sich um die gleiche Anmerkung, die der Eintrag an anderer Stelle im Journal trägt.

Markierungen werden als Daten gespeichert und können jederzeit erneut bearbeitet werden – beim erneuten Öffnen des Editors werden sie erneut angezeigt und nicht als abgeflachtes Bild. Die markierte Version wird zum Bild, das in der Zeitleiste, im PDF, im Markdown-Export und im HTML-Bundle angezeigt wird. Die nicht markierte Aufnahme verbleibt im Journalordner als `shot-000004.orig.png`.

!!! warning
    Annotation draws **on top of** the picture — **Unreadable** included. It is not redaction. The unmarked capture remains on this machine inside the journal folder as `shot-000004.orig.png`. It is never copied into an export, so a box you drew over something sensitive does hold in an exported document, but anyone with access to the journal folder itself can still open the original. To remove something from a journal for good, use **Search and replace** or the redaction rules — and delete the `.orig.png` by hand if a screenshot is the problem.

### Markers

![Managing journal markers](../assets/screenshots/journal/journal-markers.png)

Über die vier integrierten Markierungen hinaus (**Keine**, **Info**, **Wichtig**, **Fehler**) können Sie Ihre eigenen Markierungen definieren – einen Namen wie *Softwareinstallation* und eine Farbe Ihrer Wahl. **Markierungen verwalten…** neben der Markierungsauswahl öffnet den Editor:

- **Farbe**, **Name** und **Zählt als**. Der letzte entscheidet, auf welchen integrierten Wert der Marker herabgestuft wird, was dafür sorgt, dass ein Journal in einem älteren korTTY lesbar bleibt und was dafür sorgt, dass ein *Outage*-Marker zur Fehlersumme zählt.
- **Add**, **Duplicate** and **Delete**. Deleting also removes the rules that pointed at that marker, so no rule is left silently doing nothing.

Markierungen leben in Ihren Einstellungen und sind in jedem Journal verfügbar. Eine von Ihnen tatsächlich verwendete Markierung wird zusätzlich in diesem Journal gespeichert, sodass ein exportiertes oder freigegebenes Journal automatisch in den richtigen Farben gerendert wird – und das spätere Löschen einer Markierung ändert nie das Aussehen eines vorhandenen Journals.

#### Automatische Markierer

Die untere Hälfte desselben Dialogs enthält Regeln, die selbst Markierungen setzen. Aktivieren Sie **Markierungen automatisch in neuen Einträgen setzen** und fügen Sie dann eine Regel pro Suchbegriff hinzu:

| Spalte | Bedeutung |
|--------|---------|
| **Aktiv** | Ob die Regel überhaupt angewendet wird |
| **Marker** | Welcher Marker gesetzt werden soll |
| **Search term** | A word or a whole sentence; with **Regex** off it is matched literally |
| **Regex** | Behandeln Sie den Suchbegriff als regulären Ausdruck |
| **Groß-/Kleinschreibung ignorieren** | Standardmäßig aktiviert |

Die Regeln werden von oben nach unten überprüft und das erste Spiel gewinnt – verwenden Sie ▲/▼, um sie zu ordnen. Sie sehen sich den Titel, die Zusammenfassung, die Notiz und die Ein-/Ausgabeauszüge des Eintrags an, niemals das Roherfassungsprotokoll, und sie folgen den Schwärzungsregeln, sodass ein geschwärztes Geheimnis niemals eines auslösen kann.

A marker **you** set by hand is never overwritten; a marker the AI suggested may be. **Apply now** runs the rules over the journal that is currently open and reports how many entries changed — that also works while the session is still running. Tick **Also overwrite markers set by hand** only if you really want your own choices replaced.

### Search and replace

Durch die Suche wird ein Begriff gefunden. **Suchen & ersetzen** schreibt jedes Vorkommen neu. Verwenden Sie es, um etwas zu löschen, das nicht im Journal bleiben darf – ein in einen sichtbaren Befehl eingefügtes Passwort, ein Token in einer Serverantwort – oder einfach um ein wiederkehrendes Wort zu korrigieren.

Sie ist von zwei Stellen aus erreichbar: über die Schaltfläche **Suchen & ersetzen…** im Bearbeitungsmodus und über die Schaltfläche **Ersetzen…** in [der Suchleiste auf der Journalseite](#searching-the-journal), die das gleiche Dialogfeld mit dem von Ihnen gesuchten Begriff bereits ausgefüllt öffnet. Diese Schaltfläche erscheint nur in korTTY – die Seite wird *aus* den Journaldateien generiert, sodass eine in einem Browser geöffnete Kopie suchen kann, aber keine Möglichkeit hat, etwas umzuschreiben.

| Option | Effect |
|--------|--------|
| **Search for** / **Replace with** | The text to find and what to put in its place (`***` by default) |
| **Regulärer Ausdruck** | Behandelt den Suchtext als regulären Ausdruck; `$1` im Ersatz fügt eine erfasste Gruppe ein |
| **Groß-/Kleinschreibung ignorieren** | Entspricht jeder Groß-/Kleinschreibung |
| **Capture-Log ebenfalls umschreiben** | Standardmäßig aktiviert. Aus ändert nur die Journaleinträge und lässt das Capture-Log unberührt |
| **Treffer zählen** | Ein Probelauf über das echte Journal: Gibt an, wie viele Eintragsfelder und Protokollzeilen sich *ändern* würden, ohne etwas zu schreiben |

Replacing covers every entry title, AI summary, note and excerpt, and — unless you turned it off — every capture-log part including the compressed ones. The file header and every untouched line are preserved exactly, so the log keeps its structure.

!!! warning
    Replacing rewrites the journal files in place and cannot be undone. Use **Count matches** first, especially with a regular expression. Documents you already exported are separate files and are not changed — export them again afterwards. A journal that is still being written cannot be rewritten; stop the session first.

Der Suchtext wird niemals in korTTYs eigenes Protokoll geschrieben, da er für eine Schwärzung das Geheimnis ist.

### Eintrag wird gelöscht

**Eintrag löschen** entfernt nach einer Bestätigung den ausgewählten Timeline-Eintrag und damit auch die Bilddatei eines Screenshot-Eintrags. Das Capture-Log wird nicht berührt. Um einen Text auch von dort zu entfernen, verwenden Sie „Suchen und Ersetzen“.

## Exportieren

The **Export** menu in the manager and the viewer offers three formats:

| Format | Inhalt |
|--------|---------|
| **PDF** | Das einfache Journal: Kopfzeile mit Verbindungsdetails und Statistiken, nach Tagen gruppierte Einträge mit Markierungsplaketten, Eingabe-/Ausgabeauszüge, Notizen – und, falls ausgewählt, verkleinerte eingebettete Screenshots |
| **Markdown** | The same simple journal as a `.md` file; screenshots are copied into a sibling `<name>-files/` folder |
| **HTML-Bundle (vollständig)** | Ein Zip-Archiv des gesamten Journals – `journal.html`, `journal.xml`, die dekomprimierten Aufnahmeprotokolle und alle Screenshots – so angeordnet, dass die Seite sofort nach dem Entpacken funktioniert |

PDF und Markdown fragen, ob Screenshots enthalten sein sollen.

### Es wird nur ein Teil eines Journals exportiert

![Journal export options](../assets/screenshots/journal/journal-export-options.png)

The export dialog can narrow down what actually goes into the document. Every filter is optional, and the footer keeps a live count of how many entries would be exported.

**Zeitfenster.** Fügen Sie so viele hinzu, wie Sie möchten; Ein Eintrag muss nur in *einen* davon fallen, daher exportiert `08:00–12:00` plus `14:00–16:00` beide Blöcke eines Tages. Wenn Sie die Datumsangaben leer lassen, wird das Fenster auf jeden Tag angewendet, den das Journal umfasst, und ein Fenster, dessen Beginn nach seinem Ende liegt, läuft über Mitternacht.

Die Zeiten können ungefähre Angaben sein – das ist der Punkt:

- Die Eingabe ist fehlerverzeihend: `8`, `08`, `8:00`, `8.30` und `0800` funktionieren alle.
- Jedes Fenster wird um die **Toleranz** erweitert (standardmäßig ± 5 Minuten, für genaue Grenzen auf 0 setzen).
- Ein Eintrag fasst alles seit dem vorherigen zusammen, sodass ein um 12:03 Uhr geschriebener Eintrag, der 11:58 Uhr abdeckt, immer noch zu einem Fenster gehört, das um 12:00 Uhr endet. Ohne das würde der Eintrag an der Grenze – normalerweise der interessante – aus jedem Fenster fallen.

**Thema.** Ein Wort oder ein Satz, abgeglichen mit Titeln, Zusammenfassungen, Notizen und Auszügen; **Regulärer Ausdruck** wechselt zum Regex-Matching. **Lassen Sie die KI die Einträge auswählen** übergibt stattdessen das Thema und die Einträge an die KI, die findet, dass *Apache installiert* wird, auch wenn keines dieser Wörter wörtlich vorkommt. Es benötigt ein KI-Profil und ist ansonsten ausgegraut; Wenn das Modell nicht erreichbar ist oder mit Unsinn antwortet, greift der Export auf die Textübereinstimmung zurück und sagt dies, anstatt fehlzuschlagen.

**Markierungen.** Alle Einträge, nur markierte oder nur die Markierungen, die Sie ankreuzen. Die Liste zeigt die Markierungen, die das Journal tatsächlich verwendet.

### Gefilterte HTML-Bundles

Ohne Filter bleibt das HTML-Bundle die wörtliche Kopie, die es immer war. Mit einem wird es **neu aufgebaut**:

- `journal.xml` enthält nur die exportierten Einträge und die Markierungsdefinitionen werden mit ihnen übertragen.
- Das Capture-Log wird auf die Sequenzbereiche umgeschrieben, auf die diese Einträge verweisen, und auf die angeforderten Zeitfenster zugeschnitten. Dies ist nicht optional – ein Bundle ist das Artefakt, das Sie jemand anderem geben, und zwölf Einträge neben acht Stunden Terminalausgabe wären genau das Leck, das der Filter verhindern soll.
- Nur die Screenshots, auf die noch verwiesen wird, werden kopiert und `journal.html` wird neu gerendert, damit seine Deep-Links aufgelöst werden.
- Die Header-Zählungen werden neu berechnet, um mit den Angaben im Bundle übereinzustimmen.

Jeder gefilterte Export – PDF, Markdown und Bundle gleichermaßen – trägt ein **Auszug**-Banner mit dem Namen des Bereichs und der Eintragsanzahl, sodass niemand es mit der gesamten Sitzung verwechselt.

### Mehrere Journale exportieren

Wenn mehr als ein Journal ausgewählt ist, erstellt der Export ein einzelnes ZIP-Archiv, das jedes Journal separat hält: ein PDF- oder Markdown-Dokument pro Journal oder ein Ordner pro Journal für das HTML-Bundle. Namen werden aus den Journaltiteln übernommen, mit einem numerischen Suffix, wenn zwei Titel kollidieren.

Filter gelten für jede ausgewählte Journal. Ein Journal, in dem der Filter mit nichts übereinstimmt, wird übersprungen und anschließend gemeldet, sodass ein leeres Ergebnis einen Export von zehn Journalen nicht zum Scheitern bringen kann; Nur wenn *jedes* Journal leer ausgeht, wird der Export abgelehnt – bevor eine Datei geschrieben wird.

Jedes Archiv – einschließlich des HTML-Bundles eines einzelnen Journals – kann **mit einem Passwort geschützt** werden. Die Option befindet sich im Exportdialog und verschlüsselt das Archiv mit **AES-256**; ohne sie wird das Archiv unverschlüsselt geschrieben. Da Journale vollständige Terminal-Mitschriften enthalten, ist die Wahl eines ungeschützten Archivs eine bewusste Entscheidung.

!!! warning
    Das Passwort wird nirgendwo gespeichert. korTTY kann ein verschlüsseltes Archiv nicht wiederherstellen, wenn Sie es verlieren.

### Fußzeile und Wasserzeichen

Standardmäßig enthält jedes exportierte Dokument eine Fußzeile, die angibt, dass es mit korTTY erstellt wurde, mit einem Link zum Projekt-Repository – unten auf jeder PDF-Seite, am Ende der Markdown-Datei und in der Fußzeile der Journalseite innerhalb des HTML-Pakets. PDFs können zusätzlich ein diagonales Wasserzeichen tragen, das **standardmäßig deaktiviert** ist.

Beide werden unter [**Konfiguration → Globale Einstellungen → Export**](../reference/settings/export.md) konfiguriert, wo Sie den Fußzeilentext ändern, die Fußzeile ausschalten, das Wasserzeichen aktivieren und dessen Text und Farbe auswählen können. Die gleichen Einstellungen gelten für AI-Chat-Exporte.

## Unternehmensrichtlinie

Administratoren können die Funktion verweigern (`session-journal` unter `[rule.features]`) oder ihr Verhalten über `[rule.session-journal]` festlegen: ein Journal für jede Verbindung erzwingen, das Protokollformat, das AI-Zeilenfenster oder das Speicherverzeichnis festlegen, das Umbenennen oder Löschen von Journalen verbieten, eine Benennungsvorlage vorschreiben und den abschließenden AI-Titel erzwingen. Die Schlüssel finden Sie unter [Unternehmensrichtlinie](../reference/enterprise-policy.md).

### Automatische Schwärzung

Eine `[[rule.session-journal.replace]]`-Liste sorgt dafür, dass korTTY das Suchen und Ersetzen automatisch anwendet, mit regulären Ausdrücken, wenn der Administrator dies wünscht – für Cloud-Zugriffsschlüssel, interne Hostnamen, Ticketnummern und alles, was niemals in einem Transkript landen darf:

```toml
[[rule.session-journal.replace]]
pattern = "AKIA[0-9A-Z]{16}"
replacement = "***AWS-ACCESS-KEY***"
regex = true
label = "AWS access keys"
```

Diese Regeln werden im Capture-Thread ausgeführt, bevor eine Zeile geschrieben wird, sodass ein übereinstimmender Text überhaupt nicht in die Protokolldatei gelangt. Sie werden auch auf KI-Zusammenfassungen und -Notizen angewendet. Es gilt jede Regel jeder übereinstimmenden Richtlinienstufe – eine Regel, die ein Muster hinzufügt, schaltet niemals ein anderes aus. Im obigen Dialog erfahren Sie, wie viele vorgeschriebene Regeln in Kraft sind. Journale, die vor dem Inkrafttreten einer Regel geschrieben wurden, werden nicht rückwirkend umgeschrieben; Verwenden Sie für diese Suchen und Ersetzen. Für jeden Schlüssel siehe [Unternehmensrichtlinie](../reference/enterprise-policy.md#rulesession-journalreplace).
