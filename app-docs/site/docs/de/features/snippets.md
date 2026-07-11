---
title: Snippet-Manager
---

# Snippet-Manager

Mit dem Snippet Manager können Sie wiederverwendbare Codefragmente, Skripte und Konfigurationsvorlagen speichern, organisieren und schnell einfügen. Verwalten Sie Snippets in mehreren Sprachen mit Syntaxhervorhebung, erweiterter Suche, KI-gestützter Bearbeitung, Platzhaltervariablen und flexiblen Exportoptionen.

## Übersicht

Der Snippet Manager umfasst die folgenden Funktionen:

- **System (OS)-Spalte** – Eine sortierbare Betriebssystemspalte für jedes Snippet (Beliebig, Linux, macOS, Windows). Wird automatisch festgelegt, wenn ein Snippet über *Workflow-Skript generieren* erstellt wird.
- **Sortierbare Spalten** – Alle Spalten (Name, Sprache, Kategorie, System, Tags) sind sortierbar.
- **Skript-Header-Kategorie** – Eine feste, nicht löschbare Kategorie, die wiederverwendbare Header-Vorlagen für die Workflow-Skript-Generierung enthält.

## Öffnen des Snippet-Managers

- **Menü:** Extras → Snippet-Manager
- **Verknüpfung:** ++Strg+Umschalt+s++ (++cmd+Umschalt+S++ unter macOS)

## Snippets erstellen und bearbeiten

1. Klicken Sie auf **Hinzufügen** (oder **Bearbeiten**, um ein vorhandenes Snippet zu ändern).
2. Füllen Sie die Felder aus:
   - **Name** – Ein beschreibender Name.
   - **Sprache** – Wählen Sie die Programmiersprache aus (Bash, Python, Java, JavaScript, SQL, XML, JSON, YAML und mehr). Aktiviert die Syntaxhervorhebung.
   - **Kategorie** – Wählen Sie eine vorhandene Kategorie aus oder geben Sie eine neue ein. Die feste, nicht löschbare Kategorie *Script-Header* enthält wiederverwendbare Header-Vorlagen für generierte Workflow-Skripte.
   - **System** – Wählen Sie optional ein Zielbetriebssystem (Beliebig, Linux, macOS, Windows). Automatische Einstellung bei Erstellung über *Workflow-Skript generieren* basierend auf dem geprüften Betriebssystem des Agenten; Sie können es für jedes Snippet manuell überschreiben.
   - **Tags** – Durch Kommas getrennte Schlüsselwörter für die Suche (z. B. `docker, deploy, backup`).
   - **Beschreibung** – Optionale Freitextbeschreibung des Snippets.
   - **Inhalt** – Der Snippet-Code. Der Editor bietet Live-Syntaxhervorhebung basierend auf der ausgewählten Sprache.
3. Klicken Sie auf **OK**. Wenn sich der Snippet-Inhalt geändert hat, speichert KorTTY das bearbeitete Snippet und schließt gleichzeitig den Dialog. Beim Bearbeiten eines vorhandenen Eintrags speichert **Als neues Snippet speichern** den aktuellen Inhalt als neues Snippet mit einer neuen ID und lässt das Original unverändert.

### Editor-Symbolleiste und Funktionen

Die Symbolleiste des Snippet-Editors bietet:

- **Formatcode** – Formatieren Sie den Inhalt mit lokalen Formatierern oder KI-gestützter Formatierung.
- **Syntax prüfen** – Validieren Sie die Syntax (lokal oder KI-unterstützt).
- **KI-Text** – Korrigieren Sie die Rechtschreibung, übersetzen Sie oder erstellen Sie technische Beschreibungen.
- **KI-Code** – Vervollständigen Sie den Code, führen Sie eine vollständige Codeanalyse durch, verbessern Sie eine Auswahl (Lesbarkeit, Robustheit, Leistung oder eine benutzerdefinierte Anweisung), überprüfen Sie die Sicherheit oder generieren Sie Diagramme.
- **Einzeiler** – Export als Terminal-Einzeiler.
- **Editor-Zoom** – Passen Sie die Textgröße mit ++Strg+Plus++ and ++Strg+Minus++ an.
- **Editor-Profile** – Wechseln Sie zwischen integrierten, von IntelliJ inspirierten Profilen und benutzerdefinierten Farbschemata.
- **Hintergrundhelligkeit** – Editor-Hintergrund anpassen.
- **Zeilenumbruch** – Zeilenumbruch ein-/ausschalten.
- **Zeilennummern** – Anzeige der Zeilennummern umschalten.

Wenn der Editor über den SFTP-Manager für eine lokale oder Remote-Datei geöffnet wird, bleibt dieselbe Symbolleiste verfügbar und das Dialogfeld verwendet Schaltflächen zum Speichern im Dateimodus.

### Spaltenlineal und Formatierung der Zeilenbreite

Oberhalb des Inhaltsfelds hält das Spaltenlineal die aktuelle Caret-Spalte links als `Column N` fest und zeigt eine Live-Markierung an der entsprechenden Editorposition an. Wenn Sie mit der Maus über den Live-Marker fahren, wird `Position N` angezeigt. Klicken Sie auf das Lineal, um eine Markierung für die maximale Zeilenlänge festzulegen (Spalten 20–240). Klicken Sie mit der rechten Maustaste auf diese Markierung, um den Inhalt auf die ausgewählte Breite zu formatieren oder die Markierung zu entfernen.

Die Formatierung der Zeilenbreite funktioniert lokal nur für Formatierer, die konfigurierbare Breite unterstützen:

- Prettier-gestützte Webformate (JavaScript, TypeScript, HTML, CSS)
- Python (Schwarz)
- Perl (Perl::Tidy)

Bei Sprachen ohne lokale Zeilenbreitenunterstützung fragt KorTTY, ob eine KI-gestützte Formatierung verwendet werden soll. Sowohl die lokale als auch die KI-gestützte Formatierung zeigen eine Vorher/Nachher-Vorschau an, bevor Änderungen übernommen werden.

### Formatcode

**Format Code** nutzt den gemeinsam genutzten lokalen Formatierungsdienst von KorTTY:

- **Eingebaute Formatierer:** JSON, XML, YAML/YML, TOML, INI/properties, Groovy
- **Gebündelte Formatierer:** Java (google-java-format), Bash/Shell (shfmt), Web/JS/TS/HTML/CSS (Prettier), SQL (sql-formatter), Perl (Perl::Tidy)
- **Fallback:** Optionale PATH-Fallbacks für Entwickler-Setups, wenn ein gebündelter Formatierer fehlt

Prettier läuft als Offline-Standalone-Browser-Build mit nur den Plugins Babel, Estree, TypeScript, HTML und PostCSS; SQL verwendet den gebündelten SQL-Formatter-Browser-Build. Beide werden träge in einem isolierten JavaFX-WebView initialisiert und benötigen keine installierte oder gepackte Node.js-Laufzeit. Anfragen werden serialisiert und behalten das gleiche 15-Sekunden-Timeout, die gleiche Anbieteranzeige und die konfigurierbare Prettier-Linienbreite wie das Prozess-Backend; Eine ausgefallene oder abgelaufene Engine wird vor der nächsten Anfrage verworfen.

Wenn die lokale Formatierung nicht verfügbar ist und das konfigurierte AI-Profil Snippet-AI-Funktionen bietet, fragt KorTTY, ob die AI-Unterstützung verwendet werden soll. Die AI-Formatierung wird erst angewendet, nachdem die Vorher/Nachher-Vorschau akzeptiert wurde.

### Editor-Profile

Wechseln Sie zwischen:

- **Aktuelle benutzerdefinierte Farben** – Benutzerdefinierte Palette
- **10 integrierte, von IntelliJ inspirierte Profile** – Vordefinierte Farbschemata
- **Vom Benutzer erstellte Profile** – Von Ihnen erstellte benutzerdefinierte Profile

Profile speichern Vordergrund-/Hintergrundfarben, Syntaxfarben, Cursorfarbe und Cursorstil.

![Snippet editor workflow](../assets/diagrams/snippet-editor-workflow.svg)

## KI-gestützte Editorfunktionen

Wenn AI konfiguriert ist, bietet der Snippet-Editor zusätzliche Aktionen:

### KI-Vorschläge

- **KI-Vorschlag** – Erzeugt einen Dateinamen, eine Beschreibung und eine passende Sprache aus dem aktuellen Codeinhalt.
- **Korrekte Schreibweise** – Im Beschreibungsfeld; sendet nur Beschreibungstext an die KI.

### AI-Textmenü

- **Rechtschreibung in der Auswahl korrigieren** – Tippfehler im ausgewählten Text korrigieren.
- **Auswahl übersetzen…** – Ausgewählten Text in eine andere Sprache übersetzen.
- **Technische Beschreibung** – Dokumentation für den ausgewählten Code oder das gesamte Snippet erstellen.

### Optionale zusätzliche Anweisungen

Wenn die Option in *Einstellungen → KI* aktiviert ist, zeigt der Editor ein Feld mit gemeinsamen Anweisungen an, das bei Anfragen zur Rechtschreibkorrektur, Übersetzung und technischen Beschreibung gesendet wird.

### Letzte AI-Änderung umschalten

Die Schaltfläche ↺ wechselt zwischen dem Originalcode und der letzten KI-generierten Editoränderung.

### AI-Code-Vervollständigungen

- **AI Complete** – Fordert die Vervollständigung des Codes an der aktuellen Cursorposition an und zeigt ihn als nicht editierbaren Ghost-Vorschlag an. Klicken Sie zum Einfügen.
- **Auto AI Complete** – Fordert automatisch Abschlüsse an, nachdem Sie an einer Cursorposition angehalten haben. Standardmäßig deaktiviert; Nur für die aktuelle Editor-Sitzung aktiv.

### AI-Code-Aktionen

Das Menü **AI-Code** gruppiert die Aktionen, die den Code selbst lesen oder neu schreiben:

- **AI-Abschluss** / **Auto-AI-Abschluss** – Code-Vervollständigung am Cursor (siehe [AI-Code-Vervollständigungen](#ai-code-completions) oben).
- **Vollständige Code-Analyse** – Öffnet ein umfangreiches Analysefenster: eine Zusammenfassung der Funktionsweise des Skripts im Klartext, seine externen Abhängigkeiten, kategorisierte Verbesserungsvorschläge, die Sie ankreuzen und anwenden können, sowie ein automatisch generiertes Flussdiagramm. Siehe [Vollständige Codeanalyse](#full-code-analysis) unten].
- **Verbesserung der Lesbarkeit/Robustheit/Leistung** – Schreibt den **ausgewählten** Codebereich in Richtung eines Ziels ohne unabhängige Änderungen um. *Robustheit verbessern* bietet zusätzlich [Härtungsoptionen](../reference/hardening-options.md) vor der Ausführung.
- **Benutzerdefinierte Verbesserung…** – Schreibt den ausgewählten Codebereich gemäß einer von Ihnen eingegebenen Freitextanweisung neu, mit den gleichen [Härtungsoptionen](../reference/hardening-options.md).
- **Sicherheitsprüfung** – Erstellt einen Sicherheitsbericht. Wählen Sie die zu behebenden Ergebnisse aus; KorTTY wendet sie mit einer Vorher-/Nachher-Vorschau an, die hervorhebt, was sich geändert hat und warum. Siehe [Sicherheitscheck](#security-check) unten].
- **Diagramm** – Erzeugt und speichert ein persistentes PlantUML-Logikstrukturdiagramm für das Snippet.

Das Editor-Kontextmenü bietet außerdem **AI Assistant…**, der einen Anweisungsdialog für die aktuelle Cursorposition öffnet: KorTTY sendet den vollständigen Snippet, den Cursor-Offset, die Zeile, die Spalte und Ihre Anweisung an das konfigurierte AI-Profil und zeigt das Ergebnis als Vorher/Nachher-Vorschau an.

Alle Verbesserungsaktionen schreiben nur die ausgewählte Region neu, also **wählen Sie zuerst eine Coderegion aus** – andernfalls fordert KorTTY Sie dazu auf. Die Umschreibung wird immer als Vorher-/Nachher-Vorschau (das Fenster *AI-Änderung überprüfen*) angezeigt, bevor etwas angewendet wird.

!!! warning
    Snippet-KI-Aktionen senden den aktuellen Snippet-Inhalt, Auswahl- oder Cursor-Metadaten, Eingabeaufforderungsanweisungen und optional aktivierte KI-Fähigkeiten an das konfigurierte Standard-KI-Profil (oder, für die Sicherheitsprüfung, das dedizierte Sicherheitsprüfungsprofil). Snippet-KI-Aktionen aktivieren keine Internet-Tools, selbst wenn das ausgewählte Profil über Internetzugang verfügt. Die automatische Vervollständigung kann das Snippet wiederholt senden, während sie aktiv ist. Deaktivieren Sie sie daher für sensible Snippets, es sei denn, Sie vertrauen dem konfigurierten Endpunkt.

#### Vollständige Code-Analyse

**Vollständige Codeanalyse** öffnet ein spezielles Fenster, das das gesamte Snippet auf einmal untersucht und konkrete Verbesserungen anbietet, die Sie anwenden können. Das Fenster ist **nicht modal** – Sie können das Snippet weiter bearbeiten, während es geöffnet bleibt – und in der Titelleiste wird der Dateiname des Skripts angezeigt, sodass Sie mehrere Analysen unterscheiden können. In der Titelleiste des Snippet-Editors wird ebenfalls der Name der Datei angezeigt, die Sie bearbeiten.

Am oberen Rand des Fensters verläuft eine Symbolleiste, der Bericht und das Flussdiagramm füllen die beiden Bereiche darunter aus, und in der Fußzeile befindet sich eine Skript-Kopfzeilenauswahl sowie ein ausklappbares Härtungsfeld.

**Symbolleiste:**

- **Verwendetes Profil** – Der Name des KI-Profils, mit dem die Analyse ausgeführt wurde, wird links angezeigt (für das Standardprofil wird sein *tatsächlicher* Name angezeigt, z. B. *Profil: LM Studio* – nicht nur „Standardprofil“), sodass Sie immer erkennen können, welches Modell den Bericht erstellt hat.
- **KI-Fähigkeiten** – Wenn [KI-Fähigkeiten](../reference/settings/ai-skills.md) konfiguriert sind, wird in einer Zeile angezeigt, welche Fähigkeiten enthalten waren, und Sie können diese ändern; siehe **KI-Fähigkeiten für diese Analyse** unten.
- **Erneut ausführen** – Eine vorübergehende KI-Profilauswahl und eine Schaltfläche **Erneut ausführen** wiederholen die Analyse mit dem ausgewählten Profil *und* Ihrer aktuellen KI-Fähigkeitsauswahl. Die Auswahl wird auf die Standardeinstellung zurückgesetzt, wenn das Fenster erneut geöffnet wird.
- **Alle auswählen** – Markieren Sie alle Verbesserungen und Abhängigkeiten gleichzeitig.
- **A− / A+** – Passen Sie die Leseschriftgröße an (wird sitzungsübergreifend gespeichert).
- **Kopieren** – Kopieren Sie die Zusammenfassung, Verbesserungen und Abhängigkeiten als Klartext in die Zwischenablage.
- **Exportieren** – Speichern Sie den gesamten Bericht (einschließlich des Diagramms) als Datei; siehe **Bericht exportieren** unten.

**Links – Analyse und Verbesserungen:**

- **Zusammenfassung** – Eine kurze, verständliche Beschreibung dessen, was das Skript tut. Da es sich um eine Beschreibung und nicht um ein auswählbares Element handelt, wird es als einfacher Block ohne Auswahlakzent angezeigt.
- **Verbesserungen** – Vorschläge gruppiert in die Abschnitte **Sicherheit**, **Optimierung** und **Design**. Jeder Abschnittstitel trägt ein farbcodiertes Symbol und eine Anzahl, und jeder Vorschlag verfügt über ein Schweregradkennzeichen, eine Erklärung und eine konkrete Empfehlung. Kreuzen Sie die gewünschten an; Verwenden Sie **Alle auswählen**, um alles auf einmal anzukreuzen. Leere Abschnitte werden ausgeblendet.
- **Abhängigkeiten** – Externe Programme, Skripte oder Dienste, auf die sich das Snippet stützt, jedes mit seinem *Zweck* und einem *Reduzieren/Ersetzen*-Vorschlag. Markieren Sie eine Abhängigkeit, um deren Vorschlag ebenfalls anzuwenden.

**Rechts – Flussdiagramm:**

- Ein **automatisch generiertes Flussdiagramm** der Logik des Skripts wird gerendert, während ein Spinner angezeigt wird, und füllt dann den Bereich aus. Es verfügt über die vollständige Symbolleiste des Diagramms: Zoom **−** / **Anpassen** / **+**, **SVG speichern** / **PNG speichern**, **Bild kopieren** / **PflanzenUML kopieren**, ein Steuerelement **Dunkelmodus** und einen Farbwähler **Hintergrund** (beide gespeichert) und **Regenerieren**. Siehe [Diagrammdarstellung](#diagram-appearance) unten].
- **Hover-Code-Referenzen** – Wenn Sie die Maus über einen Diagrammknoten bewegen, werden die übereinstimmenden Zeilen aus dem Snippet angezeigt, sodass Sie jeden Schritt bis zum Code zurückverfolgen können – das gleiche Verhalten wie im eigenständigen [Diagram](#plantuml-diagrams)-Fenster.

**KI-Fähigkeiten für diese Analyse:**

Wenn [AI Skills](../reference/settings/ai-skills.md) konfiguriert sind, zeigt eine Zeile oben im Fenster genau an, **welche Fähigkeiten** in die Analyse einbezogen wurden, als Chips, mit einem **(automatisch ausgewählten)** oder **(manuell)**-Abzeichen:

- **Automatisch ausgewählt** – korTTY wählt die für das Snippet relevanten Fertigkeiten vorab aus, indem es die Tags, den Namen und die Beschreibung jeder Fertigkeit mit der Sprache und dem Inhalt des Snippets abgleicht und sie in die Analyse einbezieht. Aus diesem Grund lautet das Abzeichen beim ersten Durchlauf *(automatisch ausgewählt)*.
- **Manuell** – Klicken Sie auf **Auswählen…**, um eine **durchsuchbare Auswahl** zu öffnen: Geben Sie in das Suchfeld ein, um Ihre gespeicherten Fertigkeiten nach Namen, Beschreibung oder Tags zu filtern, und aktivieren oder deaktivieren Sie dann die gewünschten Fertigkeiten. Sobald Sie das Set ändern, wechselt das Badge zu *(manuell)* und korTTY behält Ihre Auswahl bei, anstatt sie automatisch auszuwählen.

Durch das Ändern der Fertigkeiten wird **nicht** sofort eine erneute Analyse durchgeführt – der neue Satz wird beim nächsten **Wiederholen** angewendet, sodass ein bewusster Klick eine Analyse mit genau den von Ihnen ausgewählten Fertigkeiten erzeugt (und keine überraschende Flut von KI-Anrufen). Fertigkeiten, die Sie hier einschließen, werden unabhängig vom konfigurierten *Ziel* der einzelnen Fertigkeiten gesendet. Die Zeile wird nur angezeigt, wenn mindestens eine KI-Fähigkeit aktiviert ist.

**Härtungsmöglichkeiten:**

Unten können Sie in einem zusammenklappbaren Bereich **Härtungsoptionen** Techniken in Produktionsqualität (strenger Modus, Fehlerfallen, aussagekräftige Exit-Codes, Protokollierung, Idempotenz, `--dry-run`, `--help` und mehr) den angewendeten Korrekturen hinzufügen. Der Panel-Titel zeigt live **Zählung**, wie viele Optionen derzeit aktiviert sind – zum Beispiel *Härtungsoptionen (11)* – und korTTY **merkt sich, ob Sie das Panel geöffnet oder geschlossen gelassen haben** und stellt diesen Zustand wieder her, wenn das Fenster das nächste Mal geöffnet wird. Unter [Härtungsoptionen](../reference/hardening-options.md)] erfahren Sie, was die einzelnen Optionen bedeuten und wie sie angewendet werden.

**Skript-Header:**

Mit einem **Skript-Header**-Selektor können Sie einem Ihrer gespeicherten *Skript-Header*-Snippets (aus der festen [Skript-Header-Kategorie ](#creating-and-editing-snippets))) dem Code voranstellen, wenn Sie die Analyse anwenden. Wählen Sie einen Header aus – oder belassen Sie ihn auf *Kein Header* (Standardeinstellung) – und sein Inhalt wird mit ersetzten Variablen oben im Snippet nach einer vorhandenen Shebang-/Lead-Zeile als Teil derselben Änderung eingefügt.

**Ausgewählt anwenden:**

Wenn Sie auf **Ausgewählte übernehmen** klicken, sendet korTTY die angekreuzten Verbesserungen und Abhängigkeitsvorschläge (sowie alle Härtungsoptionen) in einer Anfrage an die KI und zeigt das Ergebnis in einem Fenster *Verbesserungen anwenden – Änderungen überprüfen* an: das ursprüngliche und das neu geschriebene Skript nebeneinander, mit hervorgehobenen geänderten Zeilen und den Gründen für jede Änderung, genau wie die Sicherheitsüberprüfung unten. Jeder ausgewählte **Skript-Header** wird dem Ergebnis vorangestellt, bevor es angezeigt wird. Übernehmen Sie die Änderung, um den Editor zu aktualisieren. Ein eigenständiger Header – ohne angekreuzte Verbesserungen, Abhängigkeiten oder Härtung – wird direkt eingefügt, ohne einen KI-Roundtrip, und immer noch zuerst als Vorher/Nachher-Vorschau angezeigt.

**Bericht exportieren:**

Die Schaltfläche **Exportieren** speichert den vollständigen Bericht – Zusammenfassung, kategorisierte Verbesserungen, Abhängigkeiten und das Flussdiagramm – als eigenständige Datei in einem attraktiven, druckfreundlichen Design. Im Export-Header werden der Skriptname, das verwendete KI-Profil, das Datum und die enthaltenen KI-Fähigkeiten aufgezeichnet:

- **PDF** – Ein paginiertes Dokument mit eingebettetem Diagramm als Bild.
- **HTML** – Eine einzelne eigenständige Webseite (das Diagramm ist inline eingebettet), die in jedem Browser geöffnet wird.
- **Markdown** – Eine `.md`-Datei, neben der das Diagramm als PNG gespeichert ist.

#### Sicherheitsüberprüfung

Im Berichtsfenster **Sicherheitsüberprüfung** wird jeder Befund mit einem farblich gekennzeichneten Schweregradsymbol aufgeführt (Befunde werden nach dem schwerwiegendsten zuerst sortiert). In diesem Fenster können Sie:

- Passen Sie die Leseschriftgröße mit **A−** / **A+** an (wird sitzungsübergreifend gespeichert).
- Kopieren Sie alle Ergebnisse in die Zwischenablage.
- **Wählen Sie alle Ergebnisse auf einmal aus und wenden Sie dann die ausgewählten Korrekturen an.
- Wählen Sie ein dediziertes **Sicherheitsprofil** – das KI-Profil, das für Sicherheitsüberprüfungen verwendet wird. Die Auswahl wird dauerhaft gespeichert und ist auch unter **Konfiguration → Globale Einstellungen → AI** verfügbar; Lassen Sie es auf *Standardprofil verwenden*, um die Standardeinstellung wiederzuverwenden. Änderungen werden sofort wirksam.
- **Prüfung erneut ausführen**, um die Überprüfung mit dem neu ausgewählten Profil zu wiederholen.

Wenn Sie Fixes anwenden, werden im Fenster **Sicherheitsfixes überprüfen** der ursprüngliche und der korrigierte Code nebeneinander angezeigt. Geänderte Zeilen werden automatisch hervorgehoben und tragen eine Markierung am Rand. Bewegen Sie den Mauszeiger an eine beliebige Stelle in einem geänderten Block, um zu sehen, auf welche(s) Ergebnis(se) er sich bezieht (zum Beispiel `S1` oder `S1 + S2`, wenn ein Block zwei Ergebnisse abdeckt), zusammen mit dem Grund für die Änderung. Beim Hover-Matching werden neu eingerückte Zeilen oder Zeilen mit geänderter Groß-/Kleinschreibung toleriert, und ein Grund, dessen Ankerzeile überhaupt nicht gefunden werden kann, wird der Reihe nach an die verbleibenden geänderten Blöcke angehängt, sodass im Diff keine Erklärungen mehr fehlen. Dieselben Erklärungen werden auch als Karten unter dem Diff aufgeführt: Jede Karte trägt das Abzeichen des Ergebnisses und ein farbcodiertes Kategoriesymbol (dieselben Symbole wie die Analyseabschnitte) sowie den Zeilenbereich, den sie auf der korrigierten Seite betrifft (z. B. *Zeilen 23–40*), sodass die Begründung auch dann sichtbar bleibt, wenn keine Markierung platziert werden kann. Die Schriftgröße der Vorschau kann gezoomt werden und wird sitzungsübergreifend gespeichert. Das gleiche Überprüfungsfenster (und seine Erklärungskarten) wird verwendet, wenn Verbesserungen der **vollständigen Codeanalyse** angewendet werden.

### AI-Profil, erneut ausführen und zoomen

Die AI-Code-Berichtsfenster (vollständige Codeanalyse, Sicherheitsprüfung, die Dialoge zur technischen Beschreibung und alternativen Lösung sowie der Änderungsüberprüfungsunterschied) teilen sich eine kleine Symbolleiste:

- **KI-Profil** – Wählen Sie ein anderes KI-Profil für die **nächste** Ausführung dieses Fensters. Die Auswahl ist vorübergehend: Sie wird auf das Standardprofil zurückgesetzt, wenn das Fenster erneut geöffnet wird. (Security Check behält stattdessen sein eigenes, dauerhaft gespeichertes *Sicherheitsprofil*.)
- **Erneut ausführen** – Wiederholen Sie die Anforderung mit dem aktuell ausgewählten Profil.
- **A− / A+** – Passen Sie die Schriftgröße für das Lesen oder die Vorschau an; Die gewählte Größe wird sitzungsübergreifend gespeichert, getrennt pro Fenstertyp.
- **Kopieren** – Kopieren Sie den Bericht oder Inhalt in die Zwischenablage.

### KI-Fähigkeiten

Wenn [KI-Fähigkeiten](../reference/settings/ai-skills.md) konfiguriert sind, zeigt der Snippet-Editor eine **KI-Fähigkeiten**-Auswahl an. Fertigkeiten, die für die Sprache des Snippets relevant sind, werden automatisch vorab ausgewählt, und jede Fertigkeit, die Sie hier ankreuzen, wird auf **jede** AI-Code-Aktion (Abschluss, Analyse, Verbesserung, Sicherheitsüberprüfung, Diagramm) angewendet, unabhängig vom konfigurierten Ziel der Fertigkeit. Die Auswahl erscheint nur, wenn mindestens eine KI-Fähigkeit aktiviert ist.

Das Fenster **Vollständige Codeanalyse** zeigt dieselbe Auswahl als eine Reihe von Chips an – mit der Bezeichnung *(automatisch ausgewählt)* oder *(manuell)* – und ermöglicht Ihnen, sie mithilfe einer durchsuchbaren Auswahl nur für diese Analyse zu verfeinern. Dort vorgenommene Änderungen gelten bei der nächsten **Wiederholung**. Siehe [Vollständige Codeanalyse](#full-code-analysis).

### Textkorrektur und Übersetzung

Für die auswahlbasierte Textkorrektur und -übersetzung schreibt KorTTY nur bearbeitbaren Kommentartext, Zeichenfolgenliterale und für den Benutzer sichtbare Textsegmente neu. Die logische Codestruktur wird nicht neu geschrieben.

### Technische Beschreibungen

- Wenn Text ausgewählt ist, beschreibt die KI nur diesen Bereich.
- Wenn nichts ausgewählt ist, beschreibt die KI das gesamte Snippet.

Mit dem Beschreibungsdialog können Sie:

- Kopieren Sie die generierte Beschreibung
- Formatieren Sie es mit der Kommentarsyntax der aktuellen Snippet-Sprache
- Fügen Sie es in das Snippet oberhalb des ausgewählten Codes oder oben ein

### Alternative Lösungen

Klicken Sie mit der rechten Maustaste auf eine ausgewählte Coderegion und wählen Sie **Alternative Lösung**, um:

- Fordern Sie mehrere alternative Implementierungen an (bis zum konfigurierten Limit)
- Fügen Sie ein dreizeiliges Feld für zusätzliche Anweisungen hinzu
- Laden Sie neue Alternativen neu und regenerieren Sie sie
- Zoomen Sie eine einzelne Vorschau auf den gesamten Dialogbereich
- Wenden Sie genau den ursprünglich ausgewählten Code an, wenn Sie fertig sind

### PlantUML-Diagramme

PlantUML-Diagramme werden mit dem Snippet gespeichert. Wenn sich der Snippet-Inhalt nach der Diagrammgenerierung ändert, markiert KorTTY das Diagramm als möglicherweise veraltet und bietet eine Neugenerierung an.

- **Rendering:** Nur lokal – KorTTY lädt PlantUML 1.2026.2 bei der ersten Verwendung herunter, überprüft seinen festen SHA-256 und behält das aktuelle JAR im Benutzercache; Es wird kein Remote-Rendering-Server verwendet.
- **Dialogfunktionen:** Gerendertes Bild, Skalierung ohne Verzerrung, Zoom/Anpassung, SVG/PNG-Export, Kopieren in die Zwischenablage und die gemeinsamen [Diagrammdarstellung](#diagram-appearance)-Steuerelemente.
- **Laufzeit:** Eine gepackte App startet erneut in ihrem eigenen Launcher in einem privaten, stornierbaren Worker-Modus, da die entfernte Laufzeit absichtlich kein `bin/java` hat; Entwicklungsläufe können ihr gebündeltes oder System-Java verwenden. Graphviz `dot` ist für die Aktivitäts-/Sequenzdiagramme von korTTY optional und wird nur von PlantUML-Diagrammtypen benötigt, die von Graphviz abhängen.
- **Cache-Hygiene:** Veraltete PlantUML-Versionen, ältere SHA-1-Dateien und aufgegebene Renderverzeichnisse, die älter als 24 Stunden sind, werden automatisch entfernt.

### Diagrammdarstellung

Beide Diagrammfenster – das eigenständige **Diagramm**-Dialogfeld und das **Vollständige Codeanalyse**-Flussdiagramm – teilen sich zwei Darstellungssteuerelemente und jedes merkt sich seine Einstellung sitzungsübergreifend:

- **Dunkelmodus** – Eine **Dunkelmodus**-Taste mit drei Auswahlmöglichkeiten:
    - **Auto** – folgt dem Hell/Dunkel-Erscheinungsbild des Betriebssystems. Wenn Sie das Betriebssystem in den Dunkelmodus schalten, folgt das Diagramm beim nächsten Rendern (und wenn das Fenster wieder den Fokus erhält).
    - **Licht** – immer hell.
    - **Dunkel** – immer dunkel.

    Eine manuelle Auswahl bleibt so lange bestehen, bis Sie sie ändern. Im Dunkelmodus wird das **gesamte** Diagramm neu eingefärbt – eine dunkle Leinwand, abgedunkelte Knotenkarten mit hellem Text sowie helle Anschlüsse und Beschriftungen – nicht nur der Seitenrand.
- **Hintergrund** – Ein Farbwähler für die Seiten-/Leinwandfarbe im hellen Modus. Dies gilt für das Diagramm selbst und für jedes exportierte SVG/PNG. Der Picker ist deaktiviert, während der Dunkelmodus aktiv ist, da der Dunkelmodus das Erscheinungsbild steuert.

## Platzhaltervariablen

Snippets können Platzhaltervariablen enthalten, die ersetzt werden, wenn Sie das Snippet einfügen.

### Eingebaute Variablen

Diese Variablen werden automatisch ersetzt:

| Variable | Ersatz |
|----------|-------------|
| `${date}` | Aktuelles Datum im `YYYY-MM-DD`-Format |
| `${time}` | Aktuelle Uhrzeit im `HH:MM:SS`-Format |
| `${datetime}` | Aktuelles Datum und Uhrzeit im `YYYY-MM-DD HH:MM:SS`-Format |
| `${hostname}` | Hostname des lokalen Computers |
| `${username}` | Aktueller Systembenutzername |
| `${clipboard}` | Aktueller Inhalt der Zwischenablage |
| `${cursor}` | Cursorposition (aus dem Text entfernt; Position zurückgegeben) |

### Benutzerdefinierte Variablen

Jeder `${variableName}`, der nicht in der integrierten Liste enthalten ist, wird als benutzerdefinierte Variable behandelt. Wenn Sie das Snippet einfügen:

- KorTTY überprüft den Variablenmanager auf gespeicherte Werte
- Variablen ohne gespeicherte Werte fordern zur Eingabe auf

## Snippets an das Terminal senden

Der Snippet Manager kann ein ausgewähltes Snippet direkt an das aktive Terminal senden.

### An Terminal senden

- Behält bestehendes Verhalten bei
- Unterstützte Skriptsprachen werden nach Möglichkeit als Terminal-Einzeiler eingebettet
- Andere Snippets verwenden den vorhandenen Fallback-Pfad

### Mit Parametern an Terminal senden

- Öffnet einen Dialog für fehlende `${...}`-Platzhaltervariablen und Skriptargumente
- Skriptargumente werden einzeln pro Zeile eingegeben; Leerzeilen werden ignoriert
- Wenn Sie ohne Skriptargumente bestätigen, ist das Ergebnis dasselbe wie bei **An Terminal senden**, fehlende Platzhaltervariablen können jedoch weiterhin ausgefüllt werden

### Skriptargumente

Unterstützt für Bash/Shell-, Python-, Perl- und Ruby-Snippets:

- Argumente werden einzeln und in Shell-Anführungszeichen übergeben
- Nicht als roher Shell-Text angehängt
- Wenn Argumente für nicht unterstützte Sprachen eingegeben werden, zeigt KorTTY eine Informationsmeldung an und sendet nichts

### Terminalanzeige

Bei eingebetteten/Base64-Einzeilern zeigt das Terminal die Bezeichnung `KorTTY snippet: ...` an, anstatt den vollständig generierten Befehl wiederzugeben.

## Importieren und Exportieren

Snippets können in mehreren Formaten importiert und exportiert werden.

### Datenformatexporte

Verwenden Sie **Exportieren**, um ausgewählte Snippets oder alle Snippets zu speichern, wenn nichts ausgewählt ist. Verwenden Sie **Importieren**, um Snippets aus einer Datei zusammenzuführen.

| Formatieren | Erweiterung | Anwendungsfall |
|--------|-----------|----------|
| JSON | `.json` | Datenaustausch, programmatischer Zugriff |
| XML | `.xml` | Strukturierte Daten, Tool-Integration |
| YAML | `.yaml` | Für Menschen lesbar, konfigurationsfreundlich |

### Skriptorientierte Exporte

Wählen Sie für skriptspezifische Exporte Folgendes:

#### Nur-Text-Skriptdateien

- Öffnet eine Zielordnerauswahl
- Schreibt eine Datei pro Snippet
- Filename stammt aus der Spalte **Name** des Snippets, einschließlich der Erweiterung
- Unsichere Pfadzeichen werden bereinigt
- Doppelte Namen erhalten ein Suffix wie `script (2).sh`

#### ZIP-Skriptarchiv

- Schreibt eine ZIP-Datei mit einer Skriptdatei pro Snippet
- Behalten Sie die Erweiterung aus der Spalte **Name** bei oder erzwingen Sie eine Erweiterung für alle Dateien
- Unterstützte erzwungene Erweiterungen: `.sh`, `.py`, `.pl`, `.rb`, `.ps1`, `.sql`, `.txt` oder benutzerdefiniert

#### ZIP-Verschlüsselungsoptionen

- **Unverschlüsselt** – Standard-ZIP-Archiv
- **AES passwortgeschützt** – Passwortverschlüsselt mit AES-256
- **GPG-verschlüsselt** – Erstellt eine `.zip.gpg`-Datei; erfordert den lokalen Befehl `gpg` und einen verwendbaren öffentlichen Schlüssel

!!! tip
    Wählen Sie zwei Snippets aus, exportieren Sie sie als Nur-Text und bestätigen Sie, dass die erstellten Dateien die Namen aus der Spalte **Name** verwenden. Exportieren Sie dann dieselbe Auswahl als ZIP mit der erzwungenen Erweiterung `.txt` und überprüfen Sie, ob alle ZIP-Einträge `.txt` verwenden. Bestätigen Sie für den Passwortexport, dass die ZIP-Datei vor dem Extrahieren das Passwort erfordert. Für den GPG-Export entschlüsseln Sie `.zip.gpg` mit Ihrem lokalen GPG-Setup und überprüfen Sie die ZIP-Einträge.
