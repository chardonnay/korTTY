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
| `session-log.xml` / `.json` / `.yaml` | Das Nur-Anhang-Capture-Log – zeitgestempelte Serverausgabe und typisierte Eingabezeilen mit Sequenz-IDs |
| `session-log-2.xml.gz`, … | Gedrehte Stammteile; Geschlossene Teile werden automatisch gzip-komprimiert, das Journal löscht niemals den Verlauf |
| `journal.html` | Die generierte Timeline-Seite, die nach jeder Änderung automatisch neu generiert wird |
| `screenshots/*.png` | Screenshots, die Sie während der Sitzung angehängt haben |

Das Capture-Log-Format kann im Dialogfeld **Optionen** des Journalmanagers ausgewählt werden: **XML** (Standard), **JSON** (JSON-Zeilen) oder **YAML**. Alle Formate enthalten die gleichen Felder und jeder Eintrag besteht aus genau einer Zeile, sodass ein Absturz nie mehr als die letzte Zeile beschädigt. Der aktive Protokollteil bleibt für Live-Lesevorgänge unkomprimiert. Rotation (Standard 25 MB pro Teil) und Sitzungsende komprimieren fertige Teile auf `.gz`.

## Das Journal wird aktiviert

### Automatisch für eine Verbindung

1. Öffnen Sie **Verbindungen > Verbindungen verwalten** und bearbeiten Sie eine Verbindung
2. Aktivieren Sie auf der Registerkarte **Journal** die Option **Sitzungsjournal für diese Verbindung aktivieren**
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
| Rückstand auf mehrere Prompts aufteilen (Chunking) | Journalmanager **Optionen** | aus |
| AI-Profil für Zusammenfassungen | Journalmanager **Optionen** oder **Einstellungen > Protokollierung > Sitzungsjournal** | Standardprofil |

Zusammenfassungen verwenden Ihr **Standard-KI-Profil**, es sei denn, Sie wählen ein spezielles Journalprofil aus. Diese Auswahl ist an drei gleichwertigen Stellen verfügbar: im Dialogfeld **Optionen** des Journalmanagers, **Einstellungen > Protokollierung > Sitzungsjournal** und **KI > AI-Manager > Lokale KI** neben den Rollen Text und Codierung. Die Rollenprofile Text/Coding selbst werden bewusst nicht für das Journal verwendet.

Wenn Sie **max Zeilen auf 0** setzen, wird auf Kontextfüllung umgeschaltet: Der Zusammenfassungstext packt so viele der neuesten Zeilen, wie in das konfigurierte Token-Budget passen. Wenn **Chunking** aktiviert ist, wird das gesamte Backlog verarbeitet und nicht nur das neueste Fenster – aufeinanderfolgende Fenster der konfigurierten Größe, jeweils eine KI-Eingabeaufforderung.

!!! warning
    Chunking kann bei großen Sitzungen sehr lange dauern und wird nicht für den täglichen Gebrauch empfohlen – es ist für Power-User mit leistungsfähiger Hardware und einem leistungsstarken LLM gedacht.

Wenn die Sitzung endet, schreibt die Zusammenfassung einen abschließenden **Sitzungszusammenfassungs-Eintrag** (was wurde erreicht, welche Fehler sind aufgetreten). Optional – **Lassen Sie die KI das Journal betiteln, wenn die Sitzung endet** im Dialogfeld „Optionen“ – ein abschließender KI-Aufruf benennt das Journal, es sei denn, Sie haben es manuell umbenannt.

!!! note
    Das Journal funktioniert ohne KI: Wenn kein KI-Profil verfügbar ist, KI-Funktionen deaktiviert oder Zusammenfassungen ausgeschaltet sind, zeichnet die Zeitleiste stattdessen rohe Aktivitätseinträge auf. KI-Zusammenfassungsaufforderungen verwenden niemals Tools für den Internetzugang; Der Terminalauszug geht nur an das konfigurierte AI-Profil.

## Passwortschutz

Getippte Eingaben werden nur als vollständig übermittelte Zeilen erfasst und mehrere Ebenen halten Passwörter aus dem Journal fern:

- Wenn die Serverausgabe mit einer Passwortabfrage endet (`password:`, `[sudo] password for …`, `passphrase`, `PIN` und lokalisierte Varianten), wird die nächste übermittelte Eingabezeile unterdrückt und als geschwärzter Platzhalter protokolliert – der eingegebene Text wird nie zwischengespeichert oder geschrieben.
- Das eigene gespeicherte Passwort der Verbindung wird zusätzlich durch `***` ersetzt, wo immer es im erfassten Text erscheinen würde.
- **Eingegebene Befehlszeilen erfassen** kann pro Verbindung vollständig deaktiviert werden; Befehle erscheinen dann nur noch als Echo des Servers im Ausgabestream.

!!! warning
    Bei der Prompt-Erkennung handelt es sich um eine Heuristik – ein Remote-Terminal kann nicht zuverlässig erkennen, wann der Server das Echo deaktiviert hat. Exotische oder Vollbild-Passwortabfragen werden möglicherweise nicht erkannt und in sichtbare Befehle eingefügte Geheimnisse (außer den Anmeldeinformationen der Verbindung) werden wie jeder andere Text erfasst. Behandeln Sie Protokolle sensibler Sitzungen entsprechend.

Sollte dennoch etwas durchgerutscht sein, wird es durch die [Schwärzung](#sensible-inhalte-im-nachhinein-entfernen) des Viewers nachträglich aus den Einträgen und dem Capture-Log entfernt.

## Die Journalseite

`journal.html` ist vollständig eigenständig (keine externen Ressourcen) und funktioniert im integrierten Viewer, in jedem Browser und innerhalb des exportierten Bundles:

- Ein Sticky-Header zeigt an, wer mit welchem ​​Server verbunden war, Startzeit, Dauer und Anzahl der Einträge, Befehle, Fehler und Screenshots sowie die Journalbeschreibung. Live-Journale weisen ein **Live**-Abzeichen auf. Die Zeile unter dem Titel enthält nur das, was der Titel nicht bereits sagt, sodass eine nach ihrem Endpunkt benannte Journal die Verbindung einmal statt dreimal angibt.
- Die Zeitleiste gruppiert Einträge nach Tag; Jeder Eintrag trägt seine Zeit, einen farbigen Markierungspunkt/-abzeichen (rot = Fehler, gelb = wichtig, blau = Info), den AI-Titel und die Zusammenfassung sowie farbcodierte Eingabe- (grün) und Ausgabeauszüge (blau).
- Durch Klicken auf einen Eintrag wird von unten ein Protokollfenster mit dem genauen Capture-Logbereich hinter diesem Eintrag eingeblendet. Das Panel verfügt über eine eigene Bildlaufleiste, ein Suchfeld mit Trefferzähler und ▲/▼-Navigation (++enter++ / ++shift+enter++ zyklisch auch Treffer, ++esc++ schließt) und färbt Eingabe- und Ausgabezeilen unterschiedlich ein.
- Screenshot-Einträge zeigen Miniaturansichten; Wenn Sie darauf klicken, wird ein Leuchtkasten in voller Größe geöffnet.
- Die Seite wird standardmäßig dunkel gerendert, folgt der Hell/Dunkel-Einstellung des Systems und verfügt über eine eigene Designumschaltung.
- Screenshots, Auszugsfenster, die Zeitleistenspalte und das Protokollfenster passen sich dem Fenster an, sodass die Seite sowohl in einem schmalen Viewer-Tab als auch im Vollbildmodus lesbar bleibt. Lange Ausschnitte scrollen in ihrem eigenen Rahmen, anstatt die Zeitleiste zu dehnen.

### Suche im Journal

Die Lupe in der Kopfzeile öffnet eine Suchleiste direkt unter den Verbindungsdetails (++ctrl+f++ funktioniert auch). Durch die Eingabe eines Begriffs oder eines ganzen Satzes wird jedes Vorkommen auf der Zeitleiste hervorgehoben – Eintragstitel, KI-Zusammenfassungen, Eingabe- und Ausgabeauszüge, Notizen und Zeitstempel – und ein Übereinstimmungszähler angezeigt; ▲ und ▼ oder ++enter++ / ++shift+enter++ springen zwischen den Treffern, ++esc++ oder ✕ schließt die Leiste und löscht die Hervorhebung.

!!! note
    Dadurch werden die Journaleinträge durchsucht. Das Roherfassungsprotokoll verfügt über eine eigene Suche im Protokollbereich, und der Journalmanager kann mit **Inhalte durchsuchen** in *allen* Journalen suchen.

### Inhalt wird kopiert

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

In korTTY verwenden die Kopieraktionen die Zwischenablage der App, sodass Bilder in der Zwischenablage des Systems landen und zum Einfügen bereit sind. In einem externen Browser wird der Text weiterhin normal kopiert. Das Kopieren eines Bildes kann auf seinen Pfad zurückgreifen, da Browser das Lesen lokaler Bilddaten von einer `file://`-Seite blockieren.

### Schriftgröße

Die Schaltflächen **A−**, **A** und **A+** im Seitenkopf skalieren die gesamte Seite zwischen 70 % und 250 %. korTTY merkt sich die Größe und wendet sie auf jede anschließend gerenderte Journalseite an, sodass eine Seite, die neu generiert wird (eine neue KI-Zusammenfassung, eine bearbeitete Markierung), wieder die von Ihnen gewählte Größe hat. Wenn die Seite eigenständig in einem Browser geöffnet wird, merkt sie sich stattdessen die Größe pro Browser.

## Journale verwalten

**Tools > Sitzungsjournale…** (++ctrl+alt+j++) öffnet den Journalmanager: alle Journale in einer Tabelle, sortiert nach Startzeit (neueste zuerst) mit Dauer, Verbindung, Server, Titel und Eintragsanzahl. Laufende Journale sind markiert und können im laufenden Betrieb weder umbenannt noch gelöscht werden.

- Das Filterfeld entspricht Titel, Verbindung, Host, Benutzer und Beschreibung; Wenn Sie **Inhalte durchsuchen** aktivieren, werden zusätzlich die Journaleinträge gescannt und Protokolle aller Journale im Hintergrund erfasst.
- **Öffnen** (oder Doppelklick) öffnet den Journal-Viewer; **Umbenennen** ändert den Titel; **Löschen** fragt nach einer Bestätigung und entfernt dann dauerhaft den Journalordner einschließlich des Protokolls und aller Screenshots.
- Es können mehrere Journale gleichzeitig ausgewählt werden (Klick ++ctrl++ / ++shift++), um sie in einem Schritt zu löschen oder zu exportieren. Laufende Journale können nicht umbenannt oder gelöscht werden.
- Der Bereich **Beschreibung** unterhalb der Tabelle speichert eine Freitextbeschreibung pro Journal; Es erscheint auf der Journalseite und in jedem Export und wird in die Inhaltssuche einbezogen.
- **Optionen** enthält die oben beschriebenen globalen Erfassungs- und KI-Einstellungen.

### Der Viewer und die Bearbeitung

Der Viewer zeigt die Journalseite in einem eingebetteten Browser an und aktualisiert sich automatisch, während das Journal noch geschrieben wird. **Im Browser öffnen** übergibt die Seite an Ihren Systembrowser. **Bearbeiten** teilt die Ansicht: eine Eintragstabelle neben einem Formular mit **Titel** und **Zusammenfassung** des Eintrags, einer Markierungsauswahl (**Keine / Info / Wichtig / Fehler**) und einem Notizfeld. Durch die Bearbeitung können Sie Einträge korrigieren oder kategorisieren – Fehler kennzeichnen, wichtige Ergebnisse hervorheben oder eine Zusammenfassung neu schreiben. Beim Speichern wird die Seite an der Position des bearbeiteten Eintrags neu generiert. Eine von Ihnen manuell gesetzte Markierung wird niemals von der KI überschrieben.

### Sensible Inhalte im Nachhinein entfernen

Manchmal landet etwas in einem Journal, das dort nicht bleiben darf – ein Passwort, das in einen sichtbaren Befehl eingefügt wurde, ein Token in einer Serverantwort. Im Bearbeitungsmodus gibt es zwei Möglichkeiten, es zu entfernen:

- **Eintrag löschen** entfernt den ausgewählten Zeitstrahl-Eintrag nach einer Bestätigung. Die Bilddatei eines Screenshot-Eintrags wird mit gelöscht. Das Capture-Log bleibt dabei unberührt.
- **Schwärzen…** entfernt einen wörtlich angegebenen Text aus dem **gesamten Journal**: aus jedem Eintragstitel, jeder Zusammenfassung, jeder Notiz und jedem Auszug *und* aus jedem Capture-Log-Teil, auch aus den komprimierten. Sie geben den zu entfernenden Text an und wodurch er ersetzt werden soll (standardmäßig `***`); korTTY meldet anschließend, wie viele Eintragsfelder und Log-Zeilen geändert wurden.

!!! warning
    Die Schwärzung schreibt die Dateien direkt neu und lässt sich nicht rückgängig machen. Bereits exportierte Dokumente sind eigene Dateien und werden nicht geändert – exportieren Sie sie danach erneut. Ein Journal, das noch geschrieben wird, kann nicht geschwärzt werden; beenden Sie dafür zuerst die Sitzung.

Der Text wird wörtlich abgeglichen – geben Sie also genau die Zeichenfolge an, die verschwinden soll. Das Geheimnis selbst schreibt korTTY niemals in sein eigenes Protokoll.

## Exportieren

Das **Exportieren**-Menü im Manager und im Viewer bietet drei Formate:

| Format | Inhalt |
|--------|---------|
| **PDF** | Das einfache Journal: Kopfzeile mit Verbindungsdetails und Statistiken, nach Tagen gruppierte Einträge mit Markierungsplaketten, Eingabe-/Ausgabeauszüge, Notizen – und, falls ausgewählt, verkleinerte eingebettete Screenshots |
| **Markdown** | Das gleiche einfache Journal als `.md`-Datei; Screenshots werden in einen benachbarten Ordner `<name>-files/` kopiert |
| **HTML-Bundle (vollständig)** | Ein Zip-Archiv des gesamten Journals – `journal.html`, `journal.xml`, die dekomprimierten Aufnahmeprotokolle und alle Screenshots – so angeordnet, dass die Seite sofort nach dem Entpacken funktioniert |

PDF und Markdown fragen, ob Screenshots enthalten sein sollen.

### Mehrere Journale exportieren

Wenn mehr als ein Journal ausgewählt ist, erstellt der Export ein einzelnes ZIP-Archiv, das jedes Journal separat hält: ein PDF- oder Markdown-Dokument pro Journal oder ein Ordner pro Journal für das HTML-Bundle. Namen werden aus den Journaltiteln übernommen, mit einem numerischen Suffix, wenn zwei Titel kollidieren.

Jedes Archiv – einschließlich des HTML-Bundles eines einzelnen Journals – kann **mit einem Passwort geschützt** werden. Die Option befindet sich im Exportdialog und verschlüsselt das Archiv mit **AES-256**; ohne sie wird das Archiv unverschlüsselt geschrieben. Da Journale vollständige Terminal-Mitschriften enthalten, ist die Wahl eines ungeschützten Archivs eine bewusste Entscheidung.

!!! warning
    Das Passwort wird nirgendwo gespeichert. korTTY kann ein verschlüsseltes Archiv nicht wiederherstellen, wenn Sie es verlieren.

### Fußzeile und Wasserzeichen

Standardmäßig enthält jedes exportierte Dokument eine Fußzeile, die angibt, dass es mit korTTY erstellt wurde, mit einem Link zum Projekt-Repository – unten auf jeder PDF-Seite, am Ende der Markdown-Datei und in der Fußzeile der Journalseite innerhalb des HTML-Pakets. PDFs können zusätzlich ein diagonales Wasserzeichen tragen, das **standardmäßig deaktiviert** ist.

Beide werden unter [**Konfiguration → Globale Einstellungen → Export**](../reference/settings/export.md) konfiguriert, wo Sie den Fußzeilentext ändern, die Fußzeile ausschalten, das Wasserzeichen aktivieren und dessen Text und Farbe auswählen können. Die gleichen Einstellungen gelten für AI-Chat-Exporte.

## Unternehmensrichtlinie

Administratoren können die Funktion verweigern (`session-journal` unter `[rule.features]`) oder ihr Verhalten über `[rule.session-journal]` festlegen: ein Journal für jede Verbindung erzwingen, das Protokollformat, das AI-Zeilenfenster oder das Speicherverzeichnis festlegen, das Umbenennen oder Löschen von Journalen verbieten, eine Benennungsvorlage vorschreiben und den abschließenden AI-Titel erzwingen. Die Schlüssel finden Sie unter [Unternehmensrichtlinie](../reference/enterprise-policy.md).
