---
title: Snippet-Manager
---

# Snippet-Manager

Mit dem Snippet Manager können Sie wiederverwendbare Codefragmente, Skripte und Konfigurationsvorlagen speichern, organisieren und schnell einfügen. Verwalten Sie Snippets in mehreren Sprachen mit Syntaxhervorhebung, erweiterter Suche, KI-gestützter Bearbeitung, Platzhaltervariablen und flexiblen Exportoptionen.

## Übersicht

Der Snippet Manager umfasst die folgenden Funktionen:

- **Systemspalte (Betriebssystem)** – Eine sortierbare Betriebssystemspalte für jedes Snippet (Beliebig, Linux, macOS, Windows). Wird automatisch festgelegt, wenn ein Snippet über *Workflow-Skript generieren* erstellt wird.
- **Sortierbare Spalten** – Alle Spalten (Name, Sprache, Kategorie, System, Tags) sind sortierbar.
- **Skript-Header-Kategorie** – Eine feste, nicht löschbare Kategorie, die wiederverwendbare Header-Vorlagen für die Workflow-Skript-Generierung enthält.

## Öffnen des Snippet-Managers

- **Menü:** Extras → Snippet-Manager
- **Shortcut:** ++ctrl+shift+s++ (++cmd+shift+s++ on macOS)

## Snippets erstellen und bearbeiten

1. Klicken Sie auf **Hinzufügen** (oder **Bearbeiten**, um ein vorhandenes Snippet zu ändern).
2. Füllen Sie die Felder aus:
   - **Name** – Ein beschreibender Name.
   - **Codesprache** – Wählen Sie die Programmiersprache aus (Bash, Python, Java, JavaScript, TypeScript, SQL, XML, JSON, YAML und mehr). Aktiviert die Syntaxhervorhebung. Mit der Schaltfläche „Hinzufügen“ (**+**) neben der Liste wird eine Sprache hinzugefügt, die noch nicht angeboten wird: Geben Sie ihren Namen einmal ein und sie wird gespeichert und in jedem zukünftigen Snippet-Editor angeboten. Für die KI-Eingabeaufforderungen und die Dateierweiterung wird eine selbst hinzugefügte Sprache verwendet; Die Syntaxhervorhebung greift auf einfachen Text zurück, es sei denn, korTTY liefert zufällig eine Grammatik dafür.
   - **Text-Sprache** – Die natürliche Sprache, die korTTY in jedem von einer KI-Aktion zurückgegebenen Codebereich erfordert. KorTTY benötigt diese Sprache für jeden vorhandenen, neuen oder umgeschriebenen Kommentar, jede den Benutzern angezeigte Nachricht, jede Protokollmeldung und jede Hilfemeldung. Eine Ersetzung einer ausgewählten Region normalisiert diese Region; Eine vollständige Skriptersetzung normalisiert das gesamte Skript. Es ist unabhängig von der korTTY-Schnittstellensprache. Aktivieren Sie **Als Standard speichern**, um die Auswahl für zukünftige Snippets beizubehalten. andernfalls gilt es nur für diesen Editor.
   - **Kategorie** – Wählen Sie eine vorhandene Kategorie aus oder geben Sie eine neue ein. Die feste, nicht löschbare Kategorie *Script-Header* enthält wiederverwendbare Header-Vorlagen für generierte Workflow-Skripte.
   - **System** – Wählen Sie optional ein Zielbetriebssystem (Beliebig, Linux, macOS, Windows). Automatische Einstellung bei Erstellung über *Workflow-Skript generieren* basierend auf dem geprüften Betriebssystem des Agenten; Sie können es für jedes Snippet manuell überschreiben.
   - **Tags** – Durch Kommas getrennte Schlüsselwörter für die Suche (z. B. `docker, deploy, backup`).
   - **Description** – Optionale Freitextbeschreibung des Snippets.
   - **Inhalt** – Der Snippet-Code. Der Editor bietet Live-Syntaxhervorhebung basierend auf der ausgewählten Sprache.
3. Klicken Sie auf **OK**. Wenn sich der Snippet-Inhalt geändert hat, speichert KorTTY das bearbeitete Snippet und schließt gleichzeitig den Dialog. Beim Bearbeiten eines vorhandenen Eintrags speichert **Als neues Snippet speichern** den aktuellen Inhalt als neues Snippet mit einer neuen ID und lässt das Original unverändert.

### Editor-Symbolleiste und Funktionen

Die Symbolleiste des Snippet-Editors bietet:

- **Formatierungscode** – Formatieren Sie den Inhalt mit lokalen Formatierern oder KI-gestützter Formatierung.
- **Syntax prüfen** – Validieren Sie die Syntax (lokal oder KI-unterstützt).
- **AI-Text** – Korrigieren Sie die Rechtschreibung, übersetzen Sie oder erstellen Sie technische Beschreibungen.
- **AI-Code** – Vervollständigen Sie den Code, führen Sie eine vollständige Codeanalyse durch, verbessern Sie eine Auswahl (Lesbarkeit, Robustheit, Leistung, Kommentare oder eine benutzerdefinierte Anweisung), überprüfen Sie die Sicherheit oder generieren Sie Diagramme.
- **Einzeiler** – Als Terminal-Einzeiler exportieren.
- **Editor zoom** — Adjust text size with ++ctrl+plus++ and ++ctrl+minus++.
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

## AI-unterstützte Editorfunktionen

Wenn AI konfiguriert ist, bietet der Snippet-Editor zusätzliche Aktionen:

### AI-Vorschläge

- **AI-Vorschlag** – Erzeugt einen Dateinamen, eine Beschreibung, **Codesprache** und **Text-Sprache** aus dem aktuellen Codeinhalt. Eine erkannte Codesprache, die noch nicht in der Liste enthalten ist, wird dieser hinzugefügt, sodass die Erkennung niemals stillschweigend verworfen wird.
- **Korrekte Schreibweise** – Im Beschreibungsfeld; sendet nur Beschreibungstext an die KI.

### Textsprache

Wenn KI konfiguriert ist, wählt **Text-Sprache** – direkt unter **Codesprache** im Editorformular – die natürliche Sprache für die Rechtschreibkorrektur und für den von KI-Aktionen zurückgegebenen Code. Der zurückgegebene Codebereich muss diese Sprache für jeden vorhandenen, neuen oder umgeschriebenen Kommentar in natürlicher Sprache, jede den Benutzern angezeigte Nachricht, jede Protokollmeldung und jede Hilfemeldung verwenden. Vorhandener Text wird bei Bedarf übersetzt, während Bezeichner, Dateipfade, Befehle, Optionen, Konfigurationsschlüssel und andere Code-Tokens unverändert bleiben. Eine auswahlbasierte Aktion wendet diesen Vertrag auf die zurückgegebene Auswahl an, während eine vollständige Ersetzung ihn auf das gesamte Skript anwendet. Es ist sowohl von der korTTY-Schnittstellensprache als auch vom **Codesprache**-Selektor unabhängig, der weiterhin die Programmiersprache und die Syntaxhervorhebung definiert. Analyseberichte, Verbesserungsbeschreibungen und Analysediagrammbeschriftungen folgen immer der korTTY-Schnittstellensprache.

Die Liste enthält korTTYs eigene Schnittstellensprachen sowie alle von Ihnen hinzugefügten [AI-generierten Sprachen ](../reference/settings/translation.md). **KI-Vorschlag** kann es auch für Sie ausfüllen: Es liest die Kommentare des Snippets und seine gedruckte Ausgabe (`echo`, `print`, `printf`, `Write-Host` und ähnliches) und wählt die gefundene Sprache vor – einschließlich einer Sprache, für die korTTY keine Schnittstellenübersetzung hat, die dann der Liste hinzugefügt wird. Ein Skript ohne für Menschen lesbaren Text lässt die aktuelle Auswahl unberührt.

Lassen Sie **Als Standard speichern** deaktiviert, um eine vorübergehende Auswahl zu treffen, die nur für das aktuelle Editorfenster gilt. Markieren Sie es, um die ausgewählte Sprache als Standard für neu geöffnete Snippet-Editoren zu speichern; Dadurch wird die bestehende **Standardsprache für AI-Text im Code**-Einstellung unter *Einstellungen → AI* aktualisiert. Andere bereits geöffnete Editorfenster behalten ihre eigene Auswahl.

Die Rechtschreibkorrektur verwendet die ausgewählte Sprache für Grammatik- und Rechtschreibregeln, ohne den Text zu übersetzen. **Auswahl übersetzen…** behält seinen separaten Zielsprachendialog bei und wählt zunächst die aktuelle Textsprache aus. Lokale Formatierer und Syntaxprüfungen sind davon nicht betroffen.

### AI Textmenü

- **Rechtschreibung in der Auswahl korrigieren** – Tippfehler im ausgewählten Text korrigieren.
- **Auswahl übersetzen…** – Ausgewählten Text in eine andere Sprache übersetzen.
- **Technische Beschreibung** – Dokumentation für den ausgewählten Code oder das gesamte Snippet erstellen.

### Optionale zusätzliche Anweisungen

Wenn die Option in *Einstellungen → KI* aktiviert ist, zeigt der Editor ein Feld mit gemeinsamen Anweisungen an, das bei Anfragen zur Rechtschreibkorrektur, Übersetzung und technischen Beschreibung gesendet wird.

### Letzte AI-Änderung umschalten

Die Schaltfläche ↺ wechselt zwischen dem Originalcode und der letzten KI-generierten Editoränderung.

### AI Codevervollständigungen

- **AI Complete** – Fordert die Vervollständigung des Codes an der aktuellen Cursorposition an und zeigt ihn als nicht editierbaren Ghost-Vorschlag an. Klicken Sie zum Einfügen.
- **Auto AI Complete** – Fordert automatisch Abschlüsse an, nachdem Sie an einer Cursorposition angehalten haben. Standardmäßig deaktiviert; Nur für die aktuelle Editor-Sitzung aktiv.

Wenn die **vollständige Codeanalyse** startet, verwirft KorTTY alle noch auf den Pausentimer wartenden Fertigstellungen und schließt einen sichtbaren Ghost-Vorschlag, sodass sich die beiden KI-Aktionen nicht überschneiden können.

### AI Codeaktionen

Das Menü **AI-Code** gruppiert die Aktionen, die den Code selbst lesen oder neu schreiben:

- **AI Complete** / **Auto AI Complete** — Code completion at the cursor (see [AI Code completions](#ai-codevervollstandigungen) above).
- **Full code analysis** — Opens a rich analysis window: a plain-language summary of what the script does, its external dependencies, categorized improvement suggestions you can tick and apply, and an auto-generated flow diagram. See [Full code analysis](#vollstandige-code-analyse) below.
- **Improve readability / robustness / performance** — Rewrites the **selected** code region toward one goal without unrelated changes. Before *Improve robustness* starts, it displays two optional panels for additional rules: [Hardening options](../reference/hardening-options.md), [Eingabe-Härtung](../reference/input-hardening.md). If at least one rule is active, KorTTY rewrites the complete snippet so it can apply global prologue or epilogue changes.
- **Codekommentare optimieren** – Kommentiert den **ausgewählten** Codebereich: Die KI fügt mithilfe der spracheigenen Kommentarsyntax direkt über oder neben den Zeilen, zu denen sie gehören, Erklärungen dazu ein, was der Code tut und warum, und ersetzt veraltete oder irreführende Kommentare. Ausführbarer Code bleibt unberührt. Die Kommentare sind in der **Text-Sprache** des Herausgebers verfasst. Verfügbar im Menü **AI-Code** und im Kontextmenü des Editors auf eine Auswahl.
- **Custom improvement…** — Rewrites the selected code region following a free-text instruction you type. It displays the same two optional rule panels: [Hardening options](../reference/hardening-options.md), [Eingabe-Härtung](../reference/input-hardening.md). As with *Improve robustness*, KorTTY rewrites the complete snippet when any hardening rule is active.
- **Security Check** — Generates a security report. Select findings to fix; KorTTY applies them with a before/after preview that highlights what changed and why. See [Security Check](#sicherheitsprufung) below.
- **Diagramm** – Erzeugt und speichert ein persistentes Mermaid-Flussdiagramm mit logischer Struktur für das Snippet.

Das Editor-Kontextmenü bietet außerdem **AI Assistant…**, der einen Anweisungsdialog für die aktuelle Cursorposition öffnet: KorTTY sendet den vollständigen Snippet, den Cursor-Offset, die Zeile, die Spalte und Ihre Anweisung an das konfigurierte AI-Profil und zeigt das Ergebnis als Vorher/Nachher-Vorschau an.

Lesbarkeit, Leistung, Kommentaroptimierung und eine benutzerdefinierte Verbesserung schreiben nur den ausgewählten Bereich um, wenn in beiden Härtungsfeldern keine ausgewählten Regeln vorhanden sind. Wählen Sie daher **zuerst einen Codebereich aus**. *Robustheit verbessern* und *Benutzerdefinierte Verbesserung* schreiben stattdessen das komplette Snippet immer dann neu, wenn eine klassische Härtungsoption oder ein unterstützter Eingabe-Härtungsschutz aktiv ist, da diese Regeln möglicherweise den Prolog und den Epilog benötigen. Jedes Ergebnis wird als Vorher/Nachher-Vorschau (das Fenster *KI-Änderung prüfen*) angezeigt, bevor etwas angewendet wird; Eine unvollständige Antwort mit vollständigem Skript und einer Auslassungsmarkierung wird abgelehnt.

!!! warning
    Snippet-KI-Aktionen senden den aktuellen Snippet-Inhalt, die Auswahl- oder Cursor-Metadaten und Eingabeaufforderungsanweisungen an das konfigurierte Standard-KI-Profil (oder, für die Sicherheitsüberprüfung, das dedizierte Sicherheitsüberprüfungsprofil). Berechtigte Aktionen können zusätzlich aktivierte, konfigurierbare KI-Fähigkeiten senden; Die Nur-Quelle-Diagrammanforderung sendet keinen konfigurierbaren Bibliotheks-Skill und trägt stattdessen immer den kompakten integrierten Mermaid-Action-Skill von korTTY. Snippet-KI-Aktionen aktivieren keine Internet-Tools, selbst wenn das ausgewählte Profil über Internetzugang verfügt. Die automatische Vervollständigung kann das Snippet wiederholt senden, während sie aktiv ist. Deaktivieren Sie sie daher für sensible Snippets, es sei denn, Sie vertrauen dem konfigurierten Endpunkt.

#### Vollständige Code-Analyse

![Full code analysis](../assets/screenshots/tools/full-code-analysis.png)

**Full code analysis** opens a dedicated window that examines the whole snippet at once and offers concrete improvements you can apply. The window is **non-modal** — you can keep editing the snippet while it stays open — and its title bar shows the script's file name so you can tell several analyses apart. The snippet editor's own title bar likewise shows the name of the file you are editing. When you apply a selection, this analysis window becomes the anchor of a three-part working surface: the narrow AI-processing window docks to one side of it and the review preview to the other, and all three stay open until you close the analysis window.

Der Bericht und das Flussdiagramm werden durch **zwei separate KI-Anfragen** generiert: Die Analyseanforderung gibt die Zusammenfassung, Abhängigkeiten und Verbesserungen zurück, und sobald das Fenster geöffnet wird, startet der Diagrammbereich seine eigene dedizierte Diagrammanforderung – dieselbe fokussierte Anforderung, die auch **Neu erzeugen** verwendet – während ein Spinner angezeigt wird. Jede Anfrage enthält eine zeilennummerierte Kopie des Skripts. Keiner von beiden wiederholt eine zweite Rohskriptkopie in derselben Eingabeaufforderung. Über OpenAI-kompatibles HTTP beschränkt korTTY den ersten Analysebericht auf ein striktes JSON-Schema für Zusammenfassung/Abhängigkeiten/Verbesserungen. Der Versuch wird ohne dieses Schema nur einmal wiederholt, wenn der Endpunkt die strukturierte Ausgabe explizit ablehnt. Eine fehlerhafte Modellausgabe wird nicht wiederholt. Die Diagrammanforderung ist bewusst kompakt und quellenorientiert: Sie verwendet das feste Mermaid-Schema, die Skript- und Beschriftungssprache und eine unveränderliche integrierte Mermaid-Aktionsfähigkeit, fügt jedoch keine konfigurierbaren Bibliotheksfähigkeiten oder Auszüge aus dem Wissensspeicher hinzu. Diese erforderliche Fähigkeit bildet den Laufzeitkontrollfluss statt der Deklarationsreihenfolge ab, gruppiert wiederholte Arbeiten mit demselben Zweck, behält echte Entscheidungen, Fehlerpfade und Schleifenausgänge bei und erfordert, dass jeder Knoten von Anfang bis Ende auf einem verbundenen Pfad mit einem genauen Quellbereich liegt. Wenn der Wert `none` **Reasoning** verfügbar ist und das aktive Profil über eine feste Modellauswahl verfügt, legt korTTY diesen Wert automatisch nur für diese Anfrage fest; Andernfalls wird der konfigurierte Wert des Profils beibehalten und das gespeicherte Profil wird nie geändert. Ein Auto-Profil wird nicht von zuvor erkannten Funktionen überschrieben, da sich sein geladenes Modell ändern kann; ein explizit konfigurierter `none`-Wert gilt weiterhin. OpenAI-kompatible HTTP-, LM Studio-native und eingebettete llama.cpp/MLX-Transporte begrenzen die Diagrammantwort auf 8.192 Ausgabetokens; Anthropic behält seine separate Anbieterobergrenze. Für eingebettetes llama.cpp/MLX wiederholt korTTY keine Antwort, die leer ist oder nur den Gedankengang des Modells enthält. Die automatische Diagrammanforderung kann mit dem Kontrollkästchen **Automatisch erzeugen** in der Kopfzeile des Diagrammbereichs deaktiviert werden. Einzelheiten finden Sie unten unter **Rechts – Flussdiagramm**. Wenn das Diagramm aus der Analyseanforderung herausgehalten wird, entstehen deutlich originalgetreuere Flussdiagramme, insbesondere bei lokalen Modellen, und der Bericht ist lesbar, während das Diagramm noch geladen wird. Durch Klicken auf **Erneut ausführen** wird die Analyse mit dem ausgewählten Profil und den konfigurierbaren KI-Fähigkeiten wiederholt und die separate dedizierte Diagrammanforderung mit derselben obligatorischen Mermaid-Fähigkeit gestartet. Durch das Starten einer weiteren Generierung oder das Schließen der Diagrammansicht wird die überschriebene Clientanforderung abgebrochen. Bevor ein neues KI-Ergebnis akzeptiert wird, lehnt korTTY getrennte Knoten, Rückwärts-Terminalpfade, unvollständige Entscheidungszweige, mehr als 12 Aktions-/Entscheidungsknoten und fehlende oder ungültige Quellzuordnungen ab; Der allgemeine Renderer bleibt abwärtskompatibel mit sicheren Diagrammen, die von älteren korTTY-Versionen gespeichert wurden. Wenn der Anbieter meldet, dass die Diagrammantwort an ihrer Grenze abgeschnitten wurde, die Anfrage fehlschlägt oder keine sichere, verwendbare Mermaid-Quelle zurückgegeben wird, behält korTTY die Analyse bei und zeigt sein deterministisches lokales Fallback-Diagramm an, ohne stillschweigend eine weitere Anfrage zu senden; Der Fallback erkennt auch eingerückte bedingte Blöcke in gängigen Skriptsprachen.

Die Zusammenfassung, Abhängigkeiten, Verbesserungsbeschreibungen und Diagrammbeschriftungen verwenden die aktuelle korTTY-Schnittstellensprache. Die separate **Text-Sprache** wird erst relevant, nachdem Sie auf **Auswahl übernehmen** geklickt haben: Da diese Aktion eine vollständige Ersetzung zurückgibt, muss die ausgewählte Sprache für jeden vorhandenen und neuen Kommentar, jede den Benutzern angezeigte Nachricht, jede Protokollmeldung und jede Hilfemeldung im gesamten resultierenden Skript verwendet werden. **Auswahl übernehmen** und die entsprechende Sicherheitsfix-Anwendungsaktion fordern `none` Reasoning nur dann automatisch an, wenn dieser Wert verfügbar ist und das Profil über eine feste Modellauswahl verfügt; Ein Auto-Profil behält sein konfiguriertes oder vom Anbieter vorgegebenes Verhalten bei, es sei denn, `none` wurde explizit konfiguriert. Das gespeicherte Profil bleibt unverändert. Dadurch wird verhindert, dass ein Reasoning-Modell das begrenzte Ersetzungsbudget verbraucht, bevor es das maschinell analysierte Skript ausgibt. Wenn ein Anbieter dennoch das Ausgabelimit ohne sichtbare Antwort erreicht, zeichnet korTTY die Nutzung auf, meldet den lokalisierten Fehler beim Ausgabelimit und lässt den Editor unverändert, anstatt eine gewöhnliche leere Antwort falsch zu melden oder die Anfrage erneut zu versuchen.

A toolbar runs along the top of the window, the report and flow diagram fill the two panes below it, and a script-header selector plus a collapsible hardening panel sit in the footer. The window is freely resizable, and korTTY remembers its position and size across sessions — including when **Re-run** replaces the window with a fresh analysis. During **Apply selected**, the AI-processing window and the review preview dock to opposite edges of it, match its height, and follow it when you move or resize it. If there is no room beside the analysis window where it currently sits, korTTY slides it far enough to open both sides up, and narrows it only when the three genuinely do not fit side by side on that screen; the original width comes back when the docked windows go. Each docked window stays freely resizable and korTTY remembers the width you give it for the next run. Drag a docked window away and it detaches, keeping the position you gave it; **Arrange windows** in the processing window lays all three out across the screen again.

**Symbolleiste:**

- **Alle Verbesserungen auswählen** – Das erste Steuerelement ganz links aktiviert oder deaktiviert alle Sicherheits-, Optimierungs- und Designverbesserungen auf einmal. Durch den zusätzlichen Abstand wird diese Massenaktion klar vom folgenden **Profil:**-Indikator getrennt. Dieses Steuerelement ändert niemals eine Abhängigkeitsauswahl.
- **Verwendetes Profil** – Der Name des KI-Profils, mit dem die Analyse ausgeführt wurde, wird neben diesem Kontrollkästchen angezeigt (für das Standardprofil wird sein *tatsächlicher* Name angezeigt, z. B. *Profil: LM Studio* – nicht nur „Standardprofil“), sodass Sie immer erkennen können, welches Modell den Bericht erstellt hat.
- **KI-Skills** – Wenn [AI-Fähigkeiten](../reference/settings/ai-skills.md) konfiguriert sind, wird in einer Zeile angezeigt, welche Fertigkeiten enthalten waren, und Sie können diese ändern; siehe **KI-Fähigkeiten für diese Analyse** unten.
- **Erneut ausführen** – Eine vorübergehende KI-Profilauswahl und eine Schaltfläche **Erneut ausführen** wiederholen die Analyse mit dem ausgewählten Profil *und* Ihrer aktuellen KI-Fähigkeitsauswahl. Die Auswahl wird auf die Standardeinstellung zurückgesetzt, wenn das Fenster erneut geöffnet wird.
- **A− / A+** – Passen Sie die Leseschriftgröße an (wird sitzungsübergreifend gespeichert).
- **Kopieren** – Kopieren Sie die Zusammenfassung, Verbesserungen und Abhängigkeiten als Klartext in die Zwischenablage.
- **Export** – Speichern Sie den gesamten Bericht (einschließlich des Diagramms) als Datei. siehe **Bericht exportieren** unten.

**Links – Analyse und Verbesserungen:**

- **Zusammenfassung** – Eine kurze, verständliche Beschreibung der Funktionsweise des Skripts. Da es sich um eine Beschreibung und nicht um ein auswählbares Element handelt, wird es als einfacher Block ohne Auswahlakzent angezeigt.
- **Improvements** — Suggestions grouped into **Security**, **Optimization** and **Design** sections. Each section title carries a colour-coded icon and a count — a padlock for **Security**, a gauge for **Optimization**, stacked layers for **Design**, and a module hexagon for **Dependencies** — and each suggestion has a severity badge, an explanation, and a concrete recommendation. Tick the ones you want; use **Select all improvements** to toggle every improvement at once. Empty sections are hidden.
- **Abhängigkeiten** – Externe Programme, Skripte oder Dienste, auf die sich das Snippet stützt, jedes mit seinem *Zweck* und einem *Reduzieren/Ersetzen*-Vorschlag. Markieren Sie jede Abhängigkeit einzeln, damit auch der entsprechende Vorschlag angewendet wird. **Alle Verbesserungen auswählen** lässt diese Kontrollkästchen unverändert.

**Rechts – Flussdiagramm:**

- An **auto-generated Mermaid flowchart** from a dedicated diagram-only request renders while a spinner is shown, then fills the pane. It carries the full diagram toolbar: zoom **−** / **Fit** / **+**, **Save SVG** / **Save PNG**, **Copy image** / **Copy Mermaid**, a **Dark mode** control and a **Background** colour picker (both remembered), and **Regenerate**. **Regenerate** deliberately sends one new dedicated source-only diagram request using the analysis window's active profile; configurable AI Skills and knowledge-store excerpts remain reserved for the analysis request, while the required built-in Mermaid action skill is always included. See [Diagram appearance](#diagrammdarstellung) below.
- **Hover-Code-Referenzen** – Wenn Sie die Maus über einen Diagrammknoten bewegen, werden die übereinstimmenden Zeilen aus dem Snippet angezeigt, sodass Sie jeden Schritt bis zum Code zurückverfolgen können – dasselbe Verhalten wie im eigenständigen Fenster [Diagram](#mermaid-diagramme).
- **Generate automatically** — A checkbox in the pane's header controls whether the diagram request starts on its own when the window opens. Untick it to skip the automatic AI request entirely — the pane then shows a hint instead, and **Regenerate** remains the manual way to request the diagram. Ticking the box while the window is open fetches the diagram immediately. The choice is remembered across sessions (default: on) and does not affect the standalone [Diagram](#mermaid-diagramme) window, which renders saved diagrams without an AI request.

**KI-Fähigkeiten für diese Analyse:**

Wenn [AI Skills](../reference/settings/ai-skills.md) konfiguriert sind, zeigt eine Zeile oben im Fenster genau **welche Fähigkeiten** in die Analyse einbezogen wurden, als Chips, mit einem Abzeichen **(automatisch ausgewählt)** oder **(manuell)**:

- **Automatisch ausgewählt** – korTTY wählt die für das Snippet relevanten Fertigkeiten vorab aus, indem es die Tags, den Namen und die Beschreibung jeder Fertigkeit mit der Sprache und dem Inhalt des Snippets abgleicht und höchstens die beiden gewöhnlichen Übereinstimmungen mit der höchsten Bewertung in die Analyse einbezieht. Explizit angeheftete oder einer Verbindung zugewiesene Fertigkeiten bleiben außerhalb dieser automatischen Grenze. Aus diesem Grund lautet das Abzeichen beim ersten Durchlauf *(automatisch ausgewählt)*.
- **Manuelle Auswahl** – Klicken Sie auf **Auswählen…**, um eine **durchsuchbare Auswahl** zu öffnen: Geben Sie in das Suchfeld ein, um Ihre gespeicherten Fähigkeiten nach Name, Beschreibung oder Tags zu filtern, und aktivieren oder deaktivieren Sie dann die gewünschten Fähigkeiten. Sobald Sie das Set ändern, wechselt das Badge zu *(manuell)* und korTTY behält Ihre Auswahl bei, anstatt sie automatisch auszuwählen.

Durch das Ändern der Fähigkeiten wird **nicht** sofort eine erneute Analyse durchgeführt – der neue Satz wird bei der nächsten **Erneutausführung** auf die Berichtsanforderung angewendet. Diese explizite Snippet-Auswahl wird zusammen mit allen der aktiven Verbindung zugewiesenen Fähigkeiten als Zulassungsliste verwendet: korTTY führt die globale Relevanzerkennung nicht erneut durch und fügt keine anderen Fähigkeiten hinzu. Fertigkeiten, die Sie hier einbeziehen, werden unabhängig vom konfigurierten *Ziel* der einzelnen Fertigkeiten an die Analyse gesendet; Die separate Diagrammanforderung lässt diese konfigurierbaren Fertigkeiten absichtlich weg und verwendet stattdessen immer ihre eigene unveränderliche Mermaid-Aktionsfertigkeit. Die Zeile wird nur angezeigt, wenn mindestens eine konfigurierbare KI-Fähigkeit aktiviert ist.

**Härtungsmöglichkeiten:**

Unten können Sie in einem zusammenklappbaren Bereich **Härtungsoptionen** Techniken in Produktionsqualität (strenger Modus, Fehlerfallen, aussagekräftige Exit-Codes, Protokollierung, Idempotenz, `--dry-run`, `--help` und mehr) den angewendeten Korrekturen hinzufügen. Der Panel-Titel enthält die Anzahl der aktuell aktivierten Optionen – zum Beispiel *Härtungsoptionen (11)* – und korTTY merkt sich, ob Sie das Panel geöffnet oder geschlossen gelassen haben und stellt diesen Zustand wieder her, wenn das Fenster das nächste Mal geöffnet wird. Unter [Härtungsoptionen](../reference/hardening-options.md) erfahren Sie, was die einzelnen Optionen bedeuten und wie sie angewendet werden.

**Eingabe-Härtung:**

Darunter fordert ein zweites zusammenklappbares Bedienfeld zur **Eingabe-Härtung** die KI auf, einen Schutzblock zur Eingabevalidierung in das Skript einzubauen, wenn die Korrekturen angewendet werden: Parameter-Zulassungslisten und Längenbeschränkungen, Dateiformatprüfungen, eine maximale Eingabedateigröße, die durch eine einstellbare `MAX_FILE_SIZE`-Variable gesteuert wird, Sicherheitswarnungen im eigenen Protokoll des Skripts und eine `FORCE=1`/`--force`-Überschreibung. Die Größenprüfung verwendet Metadaten, bevor der Dateiinhalt gelesen wird, und `0` bedeutet unbegrenzt. Es handelt sich ausschließlich um eine Opt-in-Option – das Master-Kontrollkästchen ist zunächst deaktiviert – und sein Titel zählt nur die Unteroptionen, die tatsächlich aktiv sind. Das Panel ist für YAML/YML/Ansible-Snippets deaktiviert, da für diese deklarativen Formate kein Schutz auf Skriptebene gilt. Den vollständigen Schutzvertrag finden Sie unter [Eingabe-Härtung](../reference/input-hardening.md).

**Skript-Header:**

A **Script header** selector lets you prepend one of your saved *Script-Header* snippets (from the fixed [Script-Header category](#snippets-erstellen-und-bearbeiten)) to the code when you apply the analysis. Pick a header — or leave it on *No header* (the default) — and its content, with variables substituted, is inserted at the top of the snippet, after an existing shebang / lead line, as part of the same change.

**Auswahl übernehmen:**

When you click **Apply selected** in the report, korTTY keeps the analysis window open and processes the ticked improvements, dependency suggestions and hardening options as an atomic sequence. A separate narrow **AI processing** window appears docked beside it. Two independent progress bars at the top track **Improvements** and **Code-Härtungen**, followed by elapsed wall-clock time and cumulative token usage reported by the provider; when a provider supplies no usage data, the value is explicitly shown as *not reported* rather than estimated. The checklist lists improvements first, then classic and Input-hardening requirements. Each analyzed improvement or dependency row places the report's matching colour-coded category icon directly after its ID. Descriptions in this compact checklist are limited to three lines with an ellipsis. The complete descriptions remain visible in the analysis report beside it. The checklist no longer repeats category or severity text on the right; severity remains available in the analysis report, while hardening requirements need no redundant category label because they are already grouped under **Code-Härtungen**. Pending entries use a neutral marker, all entries in the active provider batch are highlighted as running, a repair attempt is marked separately, each completed entry receives a green checkmark on its right, and the failed entry is marked if the sequence stops. If every entry completed and only the final verification rejected the combined result, the header names that instead of pointing at a marked entry. When the run ends — completed, failed or cancelled — the processing window does not close itself. It adds a **run summary**: the final duration, the token usage split into prompt, completion and total, the AI profile the run used, how many work items completed, and the number of repair attempts if there were any. **Copy summary** puts the same figures on the clipboard, and **Show preview again** reopens the review window if you closed it before deciding. Closing the analysis window cancels its running apply task and closes the docked windows with it; after a failure the report stays open so you can inspect the stopped step and retry the selection.

korTTY batches selected analysis items and dependencies into apply stages of up to three items each, then handles classic hardening and Eingabe-Härtung separately in batches of at most three mandatory requirements. Every stage receives the complete result of the previous stage and must preserve its existing behaviour. Intermediate scripts are never inserted into the editor. If a stage's response stream is dropped mid-answer by the connection, korTTY retries that stage once automatically — distinct from the fragment-repair attempt below — before giving up. If a stage still fails, is cut off or returns an incomplete replacement — or you stop the run with the editor's cancel button — before the first stage has completed, korTTY discards the sequence and leaves the editor unchanged. After at least one completed stage, the same aborts open an **Apply interrupted** prompt instead: **Continue remaining stages** resumes the run at the aborted stage and repeats it while keeping the completed stages' work — a fresh processing window opens with those stages already checked off, and the final cumulative verification still covers the whole selection; **Preview partial result** opens the completed stages' combined rewrite in the usual review preview, titled *Apply improvements — review partial changes* — each completed stage's requirements were already verified when that stage finished, and only the final cumulative re-check is skipped, because requirements of stages that never ran are missing by definition, while the degenerate-replacement guard still applies before the preview opens; **Verwerfen** (also the prompt's Esc/close action) throws everything away as before. If a resumed run aborts again, the prompt reappears with the newer state. When every stage completed and only the final cumulative verification failed, the prompt omits the resume choice, since re-running zero remaining stages would fail identically. Closing the analysis window or the snippet editor mid-run remains a deliberate discard and never shows the prompt. A fully completed sequence opens the single *Apply improvements — review changes* window with the final cumulatively verified script, docked opposite the processing window; the analysis window, the completed checklist and the run summary all stay open after the preview is dismissed, so what the run did and cost can still be read. Closing the analysis window is what clears the group.

Dies erfordert mehr Modellaufrufe und kann insgesamt mehr Eingabetoken verbrauchen als eine übergroße Anfrage, aber jede einzelne Aufgabe ist für lokale Modelle wesentlich kleiner. Jede ausgewählte Regel der klassischen Code-Härtung und der Eingabe-Härtung behält über die Stufen hinweg eine stabile, separat nummerierte obligatorische Kennung bei. Jede Stufe bestätigt ihre abgeschlossenen Bezeichner in einer kompakten Liste, anstatt für jede Regel eine vollständige Änderungserklärung zu wiederholen. Die abschließende Validierung überprüft den kumulativen Bezeichnersatz, während explizite Flags und Schutzliterale wie `--dry-run`, `--yes`, `--help`, `--verbose`, `MAX_FILE_SIZE`, `FORCE`, `--force` und `SECURITY:` weiterhin im endgültigen Code vorkommen müssen, wenn ihre Regeln aktiv sind. Da in jeder Phase das gesamte Skript neu geschrieben wird, wird jede einzelne zusätzlich anhand der Literale der Regeln überprüft, die bereits in früheren Phasen bereitgestellt wurden: Eine Phase, die frühere Härtungsarbeiten entfernt und gleichzeitig ihre eigenen implementiert, wird sofort abgelehnt und erhält einen einzigen Reparaturversuch, der genau benennt, was wiederhergestellt werden soll. Dies verhindert, dass eine solche Regression erst in der abschließenden Validierung auftritt, wo die verbleibenden Phasen nicht mehr fortgesetzt werden konnten. Jede Stufe gibt das vollständige Skript als JSON-Array mit einer Quellzeile pro Eintrag zurück, wodurch eine große Escape-empfindliche JSON-Zeichenfolge vermieden wird. Über OpenAI-kompatibles HTTP erfordert ein striktes Antwortschema auch eine konservative Mindestanzahl zurückgegebener Quellzeilen. korTTY wiederholt eine Phase ohne dieses Schema nur dann, wenn der Endpunkt die Fähigkeit zur strukturierten Ausgabe explizit ablehnt. Wenn eine strukturierte Ausgabe nicht verfügbar ist und ein lokales Modell Quell-Escapezeichen wie `\s` ohne gültige JSON-Escapezeichen ausgibt, behält der Kompatibilitätsparser diese Codezeichen bei und überprüft dennoch die obligatorische Checkliste. Für jede Phase ist ein vollständig neu geschriebenes Skript erforderlich, einschließlich aller Codeabschnitte, die keiner absichtlichen Änderung bedürfen und aus der Eingabe kopiert werden. Die ausgewählte **Text-Sprache** muss für jeden Kommentar, jede den Benutzern angezeigte Nachricht, jede Protokollmeldung und jede Hilfemeldung in diesem vollständigen Ersatz verwendet werden. Diese Übersetzung ist beabsichtigt. OpenAI-kompatible HTTP-, LM Studio-native und eingebettete llama.cpp/MLX-Transporte wählen eine Sicherheitsobergrenze pro Phasenabschluss von 32.768 bis 65.536 Token basierend auf der aktuellen Quellgröße. Anthropic behält seine separate Anbieterobergrenze. Diese Obergrenze verhindert eine unbegrenzte Ausgabe, stellt jedoch keine Kapazitätsgarantie für beliebig große Skripte dar: Eine sehr große vollständige Skriptersetzung kann abgelehnt werden, wenn der Anbieter eine Kürzung meldet. Jede Antwort, die eine Auslassungsmarkierung wie `rest unchanged` einführt, ein umfangreiches Skript in ein kurzes Fragment zusammenfasst oder aus anderen Gründen nicht die vollständige Ersetzung enthält, wird vor der nächsten Stufe oder Vorschau abgelehnt. Ein kurzes, nicht abgeschnittenes Fragment erhält genau einen Reparaturversuch für dieselbe Phase, und das Fortschrittsfenster zeigt diesen Wiederholungsversuch an. Wenn auch die Reparaturantwort schlecht ist, bricht korTTY den Vorgang ab. Der Code im Editor bleibt durchgehend unverändert. Ein gültiges Endergebnis zeigt das ursprüngliche und das neu geschriebene Skript nebeneinander, mit hervorgehobenen geänderten Zeilen und den jeweiligen Änderungsgründen, genau wie bei der Sicherheitsüberprüfung unten. Der Editor bleibt unverändert, bis Sie in dieser Vorschau **Änderung anwenden** bestätigen. Jeder ausgewählte **Skript-Header** wird dem Ergebnis vorangestellt, bevor es angezeigt wird. Ein eigenständiger Header – ohne angekreuzte Verbesserungen, Abhängigkeiten oder Härtung – wird direkt eingefügt, ohne einen KI-Roundtrip, und immer noch zuerst als Vorher/Nachher-Vorschau angezeigt.

Es ist höchstens ein Reparaturversuch zulässig, wenn eine vollständige Antwort nicht alle obligatorischen Bezeichner und erforderlichen Literale bestätigt. korTTY verwendet das vollständig zurückgegebene Skript als Reparatureingabe, benennt die Bezeichner, die noch überprüft oder implementiert werden müssen, und fordert das Modell auf, alle anderen Änderungen beizubehalten. Schlägt die Reparaturantwort erneut fehl, benennt der lokalisierte Status die noch fehlenden Bezeichner und der Editor bleibt unverändert.

**Bericht exportieren:**

Die Schaltfläche **Exportieren** speichert den vollständigen Bericht – Zusammenfassung, kategorisierte Verbesserungen, Abhängigkeiten und das Flussdiagramm – als eigenständige Datei in einem attraktiven, druckfreundlichen Design. Im Export-Header werden der Skriptname, das verwendete KI-Profil, das Datum und die enthaltenen KI-Fähigkeiten aufgezeichnet:

- **PDF** – Ein paginiertes Dokument, in das das Diagramm als Bild eingebettet ist.
- **HTML** – Eine einzelne eigenständige Webseite (das Diagramm ist inline eingebettet), die in jedem Browser geöffnet wird.
- **Markdown** – Eine `.md`-Datei, neben der das Diagramm als PNG gespeichert ist.

#### Sicherheitsprüfung

Im Berichtsfenster **Sicherheitsüberprüfung** wird jeder Befund mit einem farblich gekennzeichneten Schweregradsymbol aufgeführt (Befunde werden nach dem schwerwiegendsten zuerst sortiert). In diesem Fenster können Sie:

- Passen Sie die Leseschriftgröße mit **A−** / **A+** an (wird sitzungsübergreifend gespeichert).
- Alle Ergebnisse in die Zwischenablage kopieren.
- **Wählen Sie alle** Ergebnisse auf einmal aus und wenden Sie dann die ausgewählten Korrekturen an.
- Wählen Sie ein dediziertes **Sicherheitsprofil** – das KI-Profil, das für Sicherheitsüberprüfungen verwendet wird. Die Auswahl wird dauerhaft gespeichert und ist auch unter **Konfiguration → Globale Einstellungen → AI** verfügbar; Lassen Sie es auf *Standardprofil verwenden*, um die Standardeinstellung wiederzuverwenden. Änderungen werden sofort wirksam.
- **Prüfung erneut ausführen**, um die Überprüfung mit dem neu ausgewählten Profil zu wiederholen.

When you apply fixes, the **Review security fixes** window shows the original and corrected code side by side. Changed lines are highlighted automatically and carry a marker in the margin. Hover anywhere in a changed block to see which finding(s) it addresses — for example `S1`, or `S1 + S2` when one block covers two findings — together with the reason for the change. Hover matching tolerates re-indented or case-shifted lines, and a reason whose anchor line cannot be found at all is attached to the remaining changed blocks in order, so explanations no longer go missing from the diff. The same explanations are also listed as cards below the diff: each card carries the finding's badge and colour-coded category icon (the same icons as the analysis sections) plus the line range it affects on the corrected side (for example *Lines 23–40*), so the reasoning stays visible even when a marker cannot be placed. A **Highlight** picker below the diff narrows the review to a single finding: pick `SEC-1`, a hardening requirement, or any other listed id and only that finding's places keep their marker and get a coloured line background, while every other changed block is muted to a neutral tint. The window scrolls to the first place and the explanation cards below shrink to that finding. The **◀** / **▶** buttons beside the picker walk the list one finding at a time — from *All changes* they enter it at either end, and the ends wrap into each other — so a report can be worked through without opening the dropdown for every step. *All changes* restores the full colouring. The picker appears once at least two findings carry a reason. Muted blocks stay visible and keep their change markers — this window is the approval step before the editor is touched, so a real change is never made invisible. The summary at the top of the window scrolls inside its own pane and sits above a draggable divider: a staged apply's summary runs to one paragraph per stage, and the divider decides how much of the window it may use. The window remembers the size and position you gave it, and the divider once you move it yourself — until then it keeps following the summary's own height, so a one-line summary never claims the room a multi-paragraph one needs. When it opens docked beside a **Full code analysis** run it remembers only its width, because the dock decides the rest. This review window does not block the rest of korTTY: your terminal sessions stay usable while you read the diff, and so does the processing window beside it. Because the snippet can therefore be edited while the review is open, korTTY checks before applying that the editor still holds the text the result was produced from — if it changed, nothing is applied and you are told to run the analysis again rather than have a stale full replacement overwrite what you typed. The preview font size can be zoomed and is remembered across sessions. The same review window (and its explanation cards) is used when applying **Full code analysis** improvements.

### AI-Profil, erneut ausführen und zoomen

Die AI-Code-Berichtsfenster (vollständige Codeanalyse, Sicherheitsprüfung, die Dialoge zur technischen Beschreibung und alternativen Lösung sowie der Änderungsüberprüfungsunterschied) teilen sich eine kleine Symbolleiste:

- **AI-Profil** – Wählen Sie ein anderes AI-Profil für die **nächste** Ausführung dieses Fensters. Die Auswahl ist vorübergehend: Sie wird auf das Standardprofil zurückgesetzt, wenn das Fenster erneut geöffnet wird. (Security Check behält stattdessen sein eigenes, dauerhaft gespeichertes *Sicherheitsprofil*.)
- **Erneut ausführen** – Wiederholen Sie die Anforderung mit dem aktuell ausgewählten Profil.
- **A− / A+** – Passen Sie die Schriftgröße für das Lesen oder die Vorschau an; Die gewählte Größe wird sitzungsübergreifend gespeichert, getrennt pro Fenstertyp.
- **Kopieren** – Kopieren Sie den Bericht oder Inhalt in die Zwischenablage.

### KI-Skills

Wenn [AI Skills](../reference/settings/ai-skills.md) konfiguriert sind, zeigt der Snippet-Editor eine **KI-Skills**-Auswahl an. Fertigkeiten, die für die Sprache des Snippets relevant sind, werden automatisch vorab ausgewählt, und jede Fertigkeit, die Sie hier ankreuzen, wird unabhängig vom konfigurierten Ziel der Fertigkeit auf fertigkeitsrelevante AI-Code-Aktionen wie Vervollständigung, Analyse, Verbesserung und Sicherheitsüberprüfungen angewendet. Bei der Aktion **Diagramm** mit festem Vertrag werden diese konfigurierbaren Bibliotheksfähigkeiten absichtlich weggelassen, um die quellenbasierte Anforderung klein und vorhersehbar zu halten; korTTY stellt immer seinen separaten kompakten Action-Skill „Mermaid“ bereit, der intern ist und daher nicht in der Auswahl erscheint oder zu den 39 konfigurierbaren integrierten KI-Skills zählt. Die Auswahl erscheint nur, wenn mindestens eine konfigurierbare KI-Fähigkeit aktiviert ist.

The **Full code analysis** window surfaces this same selection as a row of chips — labelled *(auto-selected)* or *(manual)* — and lets you refine it just for that analysis through a searchable picker. Changes made there apply after you next click **Re-run**. See [Full code analysis](#vollstandige-code-analyse).

### Textkorrektur und Übersetzung

Für die auswahlbasierte Textkorrektur und -übersetzung schreibt KorTTY nur bearbeitbaren Kommentartext, Zeichenfolgenliterale und für den Benutzer sichtbare Textsegmente neu. Eine Auswahl kann innerhalb eines solchen Segments beginnen oder enden: KorTTY erkennt die ausgewählten Wörter anhand des umgebenden Snippets und ersetzt nur den überlappenden Text. Die logische Codestruktur wird nicht neu geschrieben.

### Technische Beschreibungen

- Wenn Text ausgewählt ist, beschreibt die KI nur diesen Bereich.
- Wenn nichts ausgewählt ist, beschreibt die KI das gesamte Snippet.

Mit dem Beschreibungsdialog können Sie:

- Kopieren Sie die generierte Beschreibung
- Formatieren Sie es mit der Kommentarsyntax der aktuellen Snippet-Sprache
- Fügen Sie es in das Snippet oberhalb des ausgewählten Codes oder oben ein

### Alternative Lösungen

Klicken Sie mit der rechten Maustaste auf eine ausgewählte Coderegion und wählen Sie **Alternative Lösung**, um:

- Mehrere alternative Implementierungen anfordern (bis zum konfigurierten Limit)
- Fügen Sie ein dreizeiliges Feld für zusätzliche Anweisungen hinzu
- Neue Alternativen laden und neu generieren
- Zoomen Sie eine einzelne Vorschau auf den gesamten Dialogbereich
- Wenden Sie genau den ursprünglich ausgewählten Code an, wenn Sie fertig sind

### Mermaid-Diagramme

Mermaid-Flussdiagramme werden mit dem Snippet gespeichert. Wenn sich der Snippet-Inhalt nach der Diagrammgenerierung ändert, markiert KorTTY das Diagramm als möglicherweise veraltet und bietet eine Neugenerierung an.

- **Generierung:** KI-generierte und lokale Fallback-Diagramme verwenden einen kompakten `flowchart TD`-Dialekt mit stabilen Knoten-IDs und den semantischen Klassen `setup`, `work`, `success` und `failure`; Code-Referenzen ordnen diese IDs genauen Snippet-Zeilen zu. Die KI-Anfrage verfügt immer über eine kleine interne Aktionsfähigkeit, die gruppiertes Laufzeitverhalten gegenüber Anweisung-für-Anweisung-Transkription, vollständige Entscheidungen mit lokalisierten **Ja**/**Nein**-Kantenbezeichnungen und vollständig verbundene Start-zu-Stopp-Pfade bevorzugt. Bei den integrierten Schnittstellensprachen von korTTY werden diese Kantenbeschriftungen lokal überprüft, bevor ein neues KI-Ergebnis akzeptiert wird.
- **Rendering:** Nur lokal – das SHA-256-gepinnte Browserpaket Mermaid 11.17.0 ist in KorTTY enthalten und läuft in einem isolierten, langsam erstellten JavaFX WebView. Es ist kein Rendering-Server, keine Graphviz-Installation, kein Java-Unterprozess und kein Erst-Download erforderlich.
- **Dialogfunktionen:** Bereinigte SVG-Anzeige mit deaktiviertem JavaScript, Skalierung ohne Verzerrung, Zoom/Anpassung, SVG/PNG-Export, Bild- und Mermaid-Quell-Zwischenablagekopie, Hover-Code-Referenzen und die gemeinsamen [Diagrammdarstellung](#diagrammdarstellung)-Steuerelemente.
- **Sicherheit und Wiederherstellung:** KorTTY lehnt Frontmatter, Anweisungen, Links, Rückrufe, externe Bilder/Symbole, übergroße Quellen und übermäßig komplexe Diagramme vor dem Rendern ab. Neue KI-Ergebnisse müssen außerdem über eine verbundene Topologie von Anfang bis Ende, vollständige Entscheidungszweige, höchstens 12 Aktions-/Entscheidungsknoten und genau eine gültige In-Bounds-Quellenzuordnung für jeden dieser Knoten verfügen; Ein abgelehntes Ergebnis verwendet den lokalen Fallback ohne eine weitere AI-Anfrage. Sicher eingeschränkte Diagramme, die von älteren korTTY-Versionen gespeichert wurden, bleiben auch dann renderbar, wenn sie vor diesen strengeren Generierungsqualitätsregeln erstellt wurden. Anfragen werden mit einem Timeout von 30 Sekunden serialisiert; Bei Abbruch oder Zeitüberschreitung wird der Renderer verworfen und die ausgeblendete WebView wird nach der Leerlaufzeit freigegeben.
- **Upgrade-Bereinigung:** Gespeicherte ältere Diagrammeinträge werden verworfen, ohne dass die dazugehörigen Snippets oder Chats entfernt werden. KorTTY entfernt außerdem den Download-Cache für den veralteten Diagramm-Renderer und die aufgegebenen temporären Render-Verzeichnisse, ohne symbolischen Links zu folgen.

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
| `${hostname}` | Hostname der lokalen Maschine |
| `${username}` | Aktueller Systembenutzername |
| `${clipboard}` | Aktueller Inhalt der Zwischenablage |
| `${cursor}` | Cursorposition (aus dem Text entfernt; Position zurückgegeben) |

### Benutzerdefinierte Variablen

Jeder `${variableName}`, der nicht in der integrierten Liste enthalten ist, wird als benutzerdefinierte Variable behandelt. Wenn Sie das Snippet einfügen:

- KorTTY überprüft den Variablenmanager auf gespeicherte Werte
- Variablen ohne gespeicherte Werte erfordern eine Eingabe

## Snippets an das Terminal senden

Der Snippet Manager kann ein ausgewähltes Snippet direkt an das aktive Terminal senden.

### An Terminal senden

- Behält das vorhandene Verhalten bei
- Unterstützte Skriptsprachen werden nach Möglichkeit als Terminal-Einzeiler eingebettet
- Andere Snippets verwenden den vorhandenen Fallback-Pfad

### Mit Parametern an Terminal senden

- Öffnet einen Dialog für fehlende `${...}`-Platzhaltervariablen und Skriptargumente
- Script-Argumente werden einzeln pro Zeile eingegeben; Leerzeilen werden ignoriert
- Wenn Sie ohne Skriptargumente bestätigen, ist das Ergebnis dasselbe wie bei **An Terminal senden**, fehlende Platzhaltervariablen können jedoch weiterhin ausgefüllt werden

### Script-Argumente

Unterstützt für Bash/Shell-, Python-, Perl- und Ruby-Snippets:

- Argumente werden einzeln übergeben und in Shell-Anführungszeichen gesetzt
- Nicht als roher Shell-Text angehängt
- Wenn Argumente für nicht unterstützte Sprachen eingegeben werden, zeigt KorTTY eine Informationsmeldung an und sendet nichts

### Terminalanzeige

Bei eingebetteten/Base64-Einzeilern zeigt das Terminal die Bezeichnung `KorTTY snippet: ...` an, anstatt den vollständig generierten Befehl wiederzugeben.

## Import und Export

Snippets können in mehreren Formaten importiert und exportiert werden.

### Datenformatexporte

Verwenden Sie **Exportieren**, um ausgewählte Snippets oder alle Snippets zu speichern, wenn nichts ausgewählt ist. Verwenden Sie **Importieren**, um Snippets aus einer Datei zusammenzuführen.

| Format | Erweiterung | Anwendungsfall |
|--------|-----------|----------|
| JSON | `.json` | Datenaustausch, programmgesteuerter Zugriff |
| XML | `.xml` | Strukturierte Daten, Tool-Integration |
| YAML | `.yaml` | Für Menschen lesbares, konfigurationsfreundliches |

### Skriptorientierte Exporte

Wählen Sie für skriptspezifische Exporte Folgendes:

#### Einfache Textskriptdateien

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
