# Versionshinweise

Das vollständige Versions-Änderungsprotokoll. Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.5.1

### Freigabe und Verpackung

- **Zuverlässige Linux-Paketvalidierung** – Paketgrößenprüfungen verstehen jetzt das standardmäßige Linux-JPackage-Layout `lib/app` und `lib/runtime`, sodass die Anwendungs-JAR und die gebündelte JVM validiert werden, anstatt als fehlend gemeldet zu werden.
- **Zuverlässige Windows-Artefaktvorbereitung** – Der Release-Workflow löst das jpackage-Verzeichnis explizit auf und erkennt den Windows-Launcher im generierten App-Image, bevor das tragbare Archiv erstellt wird.
- **Abhängigkeits- und CI-Wartung** – aktualisierte CodeQL-Aktionen, Apache MINA SSHD- und Bouncy Castle-, Logback- und Apache PDFBox-Abhängigkeiten.

## v2.5.0

### Verpackung und Dokumentation

- **macOS Intel Release-Pakete** – Der Release-Workflow erstellt jetzt separate signierte und notariell beglaubigte `-x86_64` ZIP- und DMG-Assets auf dem Intel macOS Runner von GitHub neben den vorhandenen Apple Silicon-Paketen und überprüft die JDK-, `jpackage`-, Runner- und Launcher-Architekturen vor der Veröffentlichung.
- **Vollständige portable Windows-Archive** – jede Windows-ZIP-Datei enthält jetzt das vollständige, eigenständige `jpackage`-Anwendungsimage mit seinem Launcher, seinen Bibliotheken und der gebündelten Laufzeit. Der frühere Windows-ARM-Job hat tatsächlich x86_64-Binärdateien erstellt und wird nicht mehr unter einem falschen ARM-Label veröffentlicht; Windows auf ARM verwendet das validierte x86_64-Paket durch Emulation, bis ein nativer JavaFX-Paketpfad vorhanden ist.
- **Webformatierer, die kein Node.js benötigen** – Der eigenständige Browser-Build von Prettier verwendet nur die erforderlichen Babel/Estree/TypeScript/HTML/PostCSS-Plugins, und der Browser-Build von sql-formatter verarbeitet SQL; beide laufen jetzt in einem Lazy, isolierten JavaFX WebView. Provider-Labels, Zeilenbreitenbehandlung, Timeout-Recovery und optionale PATH-Fallbacks bleiben verfügbar, während die rund 114 MiB große Node.js-Runtime nicht mehr installiert ist.
- **Eine Monaco-Nutzlast für Editor und Diff** – beide Seiten teilen sich jetzt ein modusbewusstes IIFE- und CSS-Bundle, während alle fünf Monaco Web Worker und die vorhandenen Sprachdienste beibehalten werden, wodurch Monacos komprimierter Anwendungs-JAR-Fußabdruck ungefähr halbiert wird, ohne dass Vervollständigung oder Diagnose entfernt werden müssen.
- **Zielspezifische native Pakete bereinigen** – Durch die endgültige Paketbereitstellung werden veraltete Abhängigkeitsversionen, alte Formatierungsbäume und fremde Mosh-Architekturen entfernt. Mosh verwendet das Bouncy Castle der Anwendung wieder, JNA/pty4j behält nur Ziel-Natives bei, ungenutzte JavaFX/JDK-Module und SithTermFX-Testabhängigkeiten werden ausgeschlossen und die Logo-Animation wird als kompaktes 640×360 H.264-Video gespeichert.
- **Installer-Größengrenzen** – Release CI gibt JSON-/Markdown-Komponentenberichte aus, lehnt unvollständige App-Images und falsch beschriftete Windows-Binärdateien ab, erfordert eine Reduzierung um mindestens 15 % gegenüber der festgeschriebenen Paketbasislinie, wendet die App-/DMG-Grenzwerte an und friert überprüfte Größen mit einer Regressionstoleranz von 2 % ein. `RPM` wird nach dem Signieren gemessen.
- **Ein lokaler Mermaid-Renderer für jedes Diagramm** – Snippet-Diagramme, vollständige Codeanalyse, Workflow-Generierung, Exporte und AI-Chat-Mermaid-Blöcke teilen sich jetzt den SHA-256-fixierten Mermaid 11.16.0-Browser-Renderer. Snippet-Flows behalten SVG/PNG-, Zwischenablage-, Zoom-, Design-/Hintergrund-, Regenerations- und Knoten-zu-Code-Hover-Referenzen bei, ohne dass ein Java/Graphviz-Unterprozess oder ein Download bei der ersten Verwendung erforderlich ist. Ältere gespeicherte Diagrammnutzlasten werden verworfen, ohne Snippets oder Chats zu entfernen, und der veraltete Renderer-Cache wird beim Upgrade sicher gelöscht.
- **Ausführlicher lokaler Release-Build-Anleitung** – ein neues Anleitungkapitel erklärt die genauen allgemeinen und plattformspezifischen Voraussetzungen, tragbare, eigenständige Anwendungsimages, ZIP/TAR-Archive, DMG/MSI/DEB/RPM-Installationsprogramme, Intel Mac-Builds, Signierung, Beglaubigung, Verifizierung und Fehlerbehebung für macOS, Windows und Linux.

### AI-Codeanalyse

- **Wählen Sie die KI-Textsprache in jedem Snippet-Editor** – ein neuer **KI-Textsprachen-Selektor** steuert die Rechtschreibkorrektur sowie neue oder neu geschriebene Codekommentare und benutzerseitige Zeichenfolgen unabhängig von der korTTY-Schnittstelle und der Programmiersprache. Analyseberichte, Verbesserungsbeschreibungen und Diagrammbeschriftungen folgen weiterhin der Oberflächensprache; **Ausgewählte anwenden** verwendet die separate Auswahl für den generierten Code. Eine Änderung kann für diesen Editor vorübergehend bleiben oder als Standard für zukünftige Editoren gespeichert werden.
- **Eine Anfrage für Analyse und Diagramm** – wenn Sie **Vollständige Codeanalyse** öffnen oder auf **Erneut ausführen** klicken, wird jetzt eine kombinierte KI-Anfrage für den Bericht und sein anfängliches Mermaid-Flussdiagramm gesendet, anstatt das Snippet zweimal zu senden. Die kombinierte Eingabeaufforderung verwendet jetzt dasselbe vollständige Beispiel für ein eingeschränktes Flussdiagramm wie **Neugenerieren**, einschließlich sinnvoller Entscheidungen und Verzweigungen. Ein ungültiges oder fehlendes Mermaid-Ergebnis wird ohne versteckten Wiederholungsversuch lokal zurückgesetzt, und das lokale Fallback erkennt eingerückte bedingte Blöcke; **Regenerieren** bleibt eine explizite reine Diagrammanforderung und verwendet das aktive Analyseprofil.
- **Die Massenauswahl ändert keine Abhängigkeiten mehr** – das klar gekennzeichnete Steuerelement **Alle Verbesserungen auswählen** schaltet jetzt nur noch Sicherheits-, Optimierungs- und Designvorschläge um. Jede Abhängigkeit behält ihren unabhängigen ausgewählten oder nicht ausgewählten Status.
- **Wählen Sie die KI-Fähigkeiten für eine Analyse aus** – das Fenster **Vollständige Codeanalyse** zeigt jetzt an, welche KI-Fähigkeiten enthalten waren, als Chips mit einem *(automatisch ausgewählten)* oder *(manuell)*-Abzeichen, und ermöglicht Ihnen, sie mit einer **durchsuchbaren** Auswahl zu ändern (nach Name, Beschreibung oder Tags filtern). Ihre Änderungen werden bei der nächsten **Wiederholung** wirksam, sodass ein bewusster Klick eine Analyse mit genau den von Ihnen ausgewählten Fähigkeiten ausführt. korTTY wählt außerdem vor der ersten Analyse automatisch die für das Snippet relevanten Fähigkeiten aus, sodass der Satz sofort aussagekräftig ist.
- **Härtungsoptionen zeigen eine Zählung an und merken sich ihren Status** – der Titel des Panels *Härtungsoptionen* zeigt jetzt an, wie viele Optionen aktiviert sind (z. B. *Härtungsoptionen (11)*), und das Fenster merkt sich, ob Sie das Panel geöffnet oder geschlossen gelassen haben.
- **Fügen Sie beim Anwenden einen Skript-Header hinzu** – mit einem **Skript-Header**-Selektor können Sie einem Ihrer gespeicherten *Skript-Header*-Snippets dem Code voranstellen, wenn Sie die Analyse anwenden; Es wird ein eigenständiger Header ohne AI-Roundtrip eingefügt.
- **Bericht exportieren** – eine neue Schaltfläche **Exportieren** speichert den gesamten Bericht – Zusammenfassung, kategorisierte Verbesserungen, Abhängigkeiten und Flussdiagramm – als **PDF**, als eigenständige **HTML**-Seite oder **Markdown** in einem attraktiven, druckfreundlichen Design, das das Profil und die enthaltenen Fähigkeiten aufzeichnet.
- **Welches Profil wird verwendet** – das Fenster zeigt jetzt den Namen des AI-Profils an, das die Analyse erstellt hat (der echte Name des Standardprofils, nicht nur „Standardprofil“).
- **Klarere Abschnitte** – Jeder Verbesserungsabschnitt (Sicherheit / Optimierung / Design / Abhängigkeiten) trägt jetzt ein farbcodiertes Symbol und im Zusammenfassungsblock wird keine Auswahlakzentleiste mehr angezeigt, die nie benötigt wurde.
- **Eindeutigere Änderungsüberprüfungen** – im Fenster *Änderungen überprüfen* zeigen die Karten „Warum sich diese Teile geändert haben“ jetzt das farbcodierte Kategoriesymbol jedes Ergebnisses und den Zeilenbereich an, auf den es sich auswirkt (z. B. *Zeilen 23–40*); Die On-Hover-Erklärungen im Diff sind zuverlässiger – beim Matching werden neu eingerückte oder zwischen Groß- und Kleinschreibung geänderte Zeilen toleriert, und Gründe, deren Anker nicht gefunden werden kann, werden an die verbleibenden geänderten Blöcke angehängt, anstatt stillschweigend zu verschwinden.

### Dateibrowser

- **Klarere Beschriftung des Kontextmenüs** – Wenn Sie mit der rechten Maustaste auf eine Datei im lokalen **Dateibrowser** (Menü „Ansicht“) klicken, wird jetzt **Im Snippet-Editor öffnen** anstelle des irreführenden *Als Textdatei laden* (die Aktion öffnete bereits den Snippet-Editor) angezeigt, passend zum Terminal-Kontextmenü.

### Korrekturen

- **Kein Absturz, wenn das Speichern eines Snippets fehlschlägt** – Das Speichern eines Snippets als neuer Eintrag könnte, wenn das Speichern fehlschlägt, beim Versuch, die Fehlermeldung anzuzeigen, mit einem Nullzeigerfehler abstürzen. Der Fehler wird nun sauber gemeldet.
- **Bei „Als neues Snippet speichern“ wird kein falscher „Bereits vorhanden“-Fehler mehr angezeigt** – nachdem ein Snippet bearbeitet (z. B. durch Anwenden von Analyseverbesserungen) und unter einem neuen Namen mit **Als neues Snippet speichern** gespeichert wurde, wurde von korTTY fälschlicherweise *„Snippet-Name existiert bereits“* angezeigt, obwohl das Snippet gerade korrekt gespeichert wurde. Der Dialog übermittelt seinen Speichervorgang nun genau einmal.

## v2.4.2

### Branding

- **Neues App-Symbol und Logo** – das Programmsymbol (macOS `.icns`, Windows `.ico` und das 1024²-Master-PNG) wurde neu gestaltet: ein neonfarbener `>`-Chevron und Eingabeaufforderungscursor mit einem lila→grünen Gehirnnetzwerk, ohne die Wortmarke „korTTY“ (sie überfüllte kleine Dock-/Taskleistengrößen). Das im Master-Passwort-Dialogfeld und im Info-Feld angezeigte In-App-Logo wurde entsprechend aktualisiert, mit dem Untertitel „AI-Driven Terminal Experience“.

### Schnellverbindung

- **Reduzierbare Optionsabschnitte** – die optionalen Einstellungen (Verbindungszeitlimit, Terminaldarstellung, Terminaleffekt, AI) sind in reduzierbaren Abschnitten gruppiert, sodass das Dialogfeld kompakt geöffnet wird; Das Verbindungs-Timeout wird jetzt mit festen Standardwerten geöffnet (10 s / 0 Wiederholungen). Das Formular scrollt innerhalb des Dialogs, wenn erweiterte Abschnitte über den Bildschirm hinausgehen, und der Dialog merkt sich bei Neustarts **merkt sich, welche Abschnitte Sie erweitert gelassen haben**.
- **Verbindungs-Skills-Auswahl** – Wählen Sie KI-Skills für die neue Verbindung vorab aus: eine globale Suche (`*`-Platzhalter), Umschalter „Alles/Löschen“ und eine Schaltfläche **Speichern**, die die aktuelle Auswahl als Standard für jede neue Verbindung beibehält.

### Terminal

- **Scrollback-Einstellung funktioniert jetzt** – der **Scrollback**-Wert unter **Konfiguration → Globale Einstellungen → Terminal** (100–100.000 Zeilen) wird jetzt tatsächlich auf den Terminalpuffer angewendet; es wurde zuvor ignoriert und jeder Bereich verwendete feste 10.000 Zeilen. Der Wert wird beim Erstellen eines Terminals gelesen und gilt daher für neu geöffnete Registerkarten und geteilte Bereiche.
- **Lokale Shells werden in Ihrem Home-Verzeichnis geöffnet** – eine neue lokale Shell (macOS/Linux) startet nicht mehr in `/`, wenn korTTY über den Finder/Dock gestartet wird; Es startet jetzt korrekt in Ihrem Home-Verzeichnis.

### Snippet AI

- **Entartete KI-Antworten löschen Ihren Code nicht mehr** – Das Anwenden von KI-Verbesserungen oder einer Sicherheitskorrektur auf ein Snippet könnte bei einem schwachen/lokalen Modell und einer aktiven KI-Fähigkeit dazu führen, dass das gesamte Snippet durch einen bloßen Platzhalter (wörtlich `$code`) ersetzt wird, der vom Modell zurückgegeben wird. Dies wird jetzt erkannt und abgelehnt – Sie erhalten die Meldung „KI-Antwort war kein gültiges Snippet – Code blieb unverändert“*, anstatt Ihren Code zu verlieren – und die KI-Eingabeaufforderung selbst wurde gehärtet, sodass die Anweisungen eines Skills das Modell nicht mehr dazu überreden können, einen Platzhalter anstelle einer echten Quelle zurückzugeben.
- **Der dunkle Modus des Diagramms deckt alle Farben ab** – im dunklen Modus des AI Code Analysis-Flussdiagramms werden jetzt alle hellen Knotenfarben auf einen passenden dunklen Farbton abgedunkelt, nicht nur die drei häufigsten, sodass der helle Knotentext unabhängig von der Farbe, die einem Knoten zugewiesen wurde, lesbar bleibt.

### Verschiedenes

- **AI Manager merkt sich sein Fenster** – das AI Manager-Dialogfeld wird jetzt in der Größe und Position erneut geöffnet, an der Sie es zuletzt verlassen haben, anstatt jedes Mal neu gestartet zu werden.

### Leistung und Stellfläche

- **Viel geringerer Speicherverbrauch** – KI-Chat-Registerkarten, Dateieditoren und die Snippet-KI-Fenster geben jetzt ihre eingebetteten Browser-Engines (Monaco/WebView) frei, wenn sie geschlossen werden, wenn Chat-Nachrichten nach einer Änderung der Schriftgröße neu gerendert werden und wenn Lösungen oder Skripte neu generiert werden; Durch das Schließen eines geteilten Bereichs werden jetzt auch der Scrollback-Puffer und die Timer freigegeben. Lange Sitzungen mit vielen Chats, Redakteuren und Splits sammeln keinen Speicher mehr an.
- **Begrenzter Speicherbedarf** – die gepackte Anwendung wird jetzt mit einer Java-Heap-Obergrenze von 2 GB ausgeführt und gibt im Leerlauf regelmäßig ungenutzten Speicher an das Betriebssystem zurück.
- **Opt-in-Ressourcenprofil** – eine neue Registerkarte **Konfiguration → Globale Einstellungen → Ressourcen** ermöglicht Ihnen, diesen geringen Platzbedarf bei Bedarf gegen mehr Ressourcen Ihres Computers einzutauschen: **Hoch** erhöht den Heap auf etwa die Hälfte Ihres RAM und **Maximum** auf etwa drei Viertel mit dem Z-Garbage-Collector mit niedriger Pause. Der Standardwert (**Balanced**) bleibt unverändert. Siehe [Resources](../reference/settings/resources.md).
- **Kleinere Downloads und Installationen** – die gebündelte Formatter-Laufzeit, Monaco-Ressourcen, native Abhängigkeits-JARs und eingebettete Java-Laufzeit wurden gekürzt und komprimiert, das Logo-Video wurde reduziert, das macOS-Disk-Image verwendet eine stärkere LZMA-Komprimierung und der Offline-Anleitung enthält keine Entwickler-Quellkarten oder ungenutzten Suchkomponenten mehr. Das gemessene Apple-Silicon-App-Image ist etwa 165 MiB statt 312 MiB und sein DMG etwa 129 MiB statt 180 MiB.
- **Datums- und Zahlenformate** – die installierte Anwendung bündelt Java-Gebietsschemadaten nur für die 8 unterstützten Schnittstellensprachen; Bei Betriebssystemgebietsschemata außerhalb dieser Liste werden Datums- und Zahlenangaben nach englischen Konventionen formatiert.

## v2.4.1

### Snippet AI-Codeanalyse

- **Vollständige Code-Analyse** – Die Option *AI-Code → Vollständige Code-Analyse* des Snippet-Editors öffnet ein umfangreiches, nicht modales Fenster mit einer Zusammenfassung der Funktionsweise des Skripts im Klartext, seinen externen Abhängigkeiten (jeweils mit einem Reduzierungs-/Ersetzungsvorschlag), Verbesserungsvorschlägen gruppiert in Sicherheit/Optimierung/Design, die Sie ankreuzen und anwenden, und einem automatisch generierten Flussdiagramm. Das Diagramm verfügt über die vollständige Symbolleiste (Zoom, Anpassen, SVG/PNG speichern, Bild/PlantUML kopieren, Hintergrundfarbe, Regenerieren) und hebt die passenden Quellzeilen hervor, wenn Sie mit der Maus über einen Knoten fahren.
- **Dateinamen in Titelleisten** – Der Snippet-Editor und das Analysefenster zeigen jetzt den Dateinamen des Skripts in ihren Titelleisten an.
- **KI-Profil pro Lauf und erneuter Lauf** – In den Analyse- und anderen AI-Code-Fenstern können Sie ein vorübergehendes KI-Profil für den nächsten Lauf auswählen und damit erneut laufen. Schriftgrößen werden pro Fenster gespeichert.
- **KI-Skills-Auswahl** – relevante KI-Skills werden automatisch vorab ausgewählt und können angeheftet werden, sodass sie für jede AI-Code-Aktion gelten.
- **Härtungsoptionen** – *Verbesserung der Robustheit*, *Benutzerdefinierte Verbesserung*, *Vollständige Codeanalyse*, und beide Workflow-Skriptgeneratoren können einen ausgewählten Satz von Techniken in Produktionsqualität (strenger Modus, Fehlerfallen, sinnvolle Exit-Codes, Protokollierung, Idempotenz, Probelauf, `--help` und mehr) in das Ergebnis integrieren. In der neuen Referenz [Hardening options](../reference/hardening-options.md) erfahren Sie, was die einzelnen Optionen bedeuten und wie sie angewendet werden.
- **Diagramm-Dunkelmodus** – beide Diagrammfenster (vollständige Codeanalyse und das eigenständige Diagrammdialogfeld) haben eine **Dunkelmodus**-Schaltfläche mit *Auto* (dem Erscheinungsbild des Betriebssystems folgend), *Hell* und *Dunkel* erhalten. Die Auswahl wird gespeichert und färbt das gesamte Diagramm neu ein – dunkle Leinwand, abgedunkelte Knotenkarten mit hellem Text und helle Anschlüsse –, während der manuelle Hintergrundfarbwähler jetzt die Diagrammseite selbst (nicht nur ihren Rand) einfärbt und auf exportiertes SVG/PNG anwendet.
- **Windows sperrt das Hauptfenster nicht mehr** – die Snippet-Fenster **Diff** und **Variablen verwalten**, die über den Snippet-Manager geöffnet werden, frieren das Hauptfenster von KorTTY nicht mehr ein, während sie geöffnet sind.
- **Härtungsoptionen – Alle / Löschen / Speichern** – jedes Härtungsoptionsfeld verfügt über die Schaltflächen **Alle** (alles ankreuzen), **Löschen** (alles abhaken) und **Speichern**. Speichern speichert Ihre Auswahl dauerhaft, sodass jedes Härtungsfenster dann mit Ihren bevorzugten Optionen anstelle der vollständigen Standardoptionen geöffnet wird.

### Snippet-Sicherheitsüberprüfung

- **Erläuterte Sicherheitsfixes** – Das Fenster **Sicherheitsfixes überprüfen** stellt jetzt den ursprünglichen und den korrigierten Snippet in einem Parallelvergleich dar, der geänderte Zeilen automatisch hervorhebt. Wenn Sie den Mauszeiger über einen geänderten Block bewegen, benennen Sie den/die Befund(e), auf den/die er sich bezieht (zum Beispiel `S1` oder `S1 + S2`, wenn ein Block zwei Befunde abdeckt) und zeigen Sie den/die Grund(e) an. Jeder Grund wird auch als Karte unter dem Diff aufgeführt, sodass die Begründung sichtbar bleibt.
- **Spezielles KI-Profil für Sicherheitsüberprüfungen** – Sie können ein separates KI-Profil nur für Sicherheitsüberprüfungen auswählen und KorTTY speichert es dauerhaft. Legen Sie es im Fenster „Sicherheitsüberprüfung“ oder unter **Konfiguration → Globale Einstellungen → AI** fest; Beide Orte haben dieselbe Einstellung, und wenn Sie diese leer lassen, wird das Standardprofil wiederverwendet.
- **Verbesserungen des Sicherheitsüberprüfungsfensters** – anpassbare (und gespeicherte) Schriftgröße, eine Schaltfläche zum Kopieren in die Zwischenablage für alle Ergebnisse, farbcodierte Schweregrad-Badges mit der Sortierung „Schweregrad zuerst“, Umschalter „Alles auswählen“ und eine Schaltfläche **Überprüfung erneut ausführen**, die die Überprüfung mit dem ausgewählten Profil wiederholt.
- **Diff-Zoom gespeichert** – Die Schriftgröße in den AI-Differenz-/Überprüfungsfenstern wird jetzt global und nicht nur für die aktuelle Sitzung gespeichert.

### Terminal

- **Anpassbare Terminal-Hintergrundtransparenz** – **Ansicht → Zoom → Hintergrundtransparenz** ist ein neuer Schieberegler (0–100 %), der den Terminalhintergrund bis zum Desktop durchscheinen lässt, während der Text völlig undurchsichtig und scharf bleibt. Der Wert wird über Neustarts hinweg gespeichert. Nur der Terminalbereich wird transparent – ​​Titel, Menü und Statusleiste bleiben einfarbig. Da der Durchsichtmodus ein randloses Fenster verwendet, wird das Ein- oder Ausschalten erst nach einem Neustart wirksam. In diesem Modus ermöglicht eine schlanke benutzerdefinierte Titelleiste das Verschieben, Ändern der Größe, Minimieren, Maximieren und Schließen. Die Anpassung des Pegels bei bereits transparentem Zustand gilt live.
- **Leiseres Schließen eines Tabs** – Beim Schließen eines Terminal-Tabs wird jetzt nur noch nach einer Bestätigung gefragt, wenn etwas verloren geht: Der Tab hat geteilte Bereiche oder ein Befehl wird noch ausgeführt (erkannt aus der Prozessstruktur der lokalen Shell oder aus der SSH-Shell-Eingabeaufforderung). Ein inaktives einzelnes Terminal wird sofort geschlossen. Die verbindungsspezifische Option *Ohne Bestätigung schließen* unterdrückt die Eingabeaufforderung weiterhin vollständig.
- **Eindeutigere Beschriftung des Kontextmenüs** – die Terminal-Rechtsklick-Aktion, die eine ausgewählte Remote-Datei in den Snippet-Editor lädt, heißt jetzt **Im Snippet-Editor öffnen** (vorher *Als Textdatei laden*).

## v2.4.0

### Terminale Auswirkungen

- **Zehn neue integrierte Terminaleffekte** – neben MU/TH/UR 6000 enthält ein gebündeltes Effektpaket jetzt zehn thematische Effekte, die Cyberpunk-, Retro- und Grusel-Stile umfassen: **Amber CRT '90** (Bernstein-Phosphor-Monitor der 90er Jahre mit Scanlines, Leuchten, Flimmern und einem rollenden Aktualisierungsband), **Commodore Heritage** (C64 blau mit Ladebalken), **Neon City** (Glitch Tears und RGB-geteiltes Flackern), **Digital Rain** (schwach fallende Matrixglyphen), **Hologramm-HUD** (Interferenzbänder und HUD-Eckklammern), **Poltergeist** (atmende Vignette, statische Ausbrüche und geisterhafte Blitze), **VHS 1987** (Verfolgungsgeräusch, rollende Verzerrung und eine PLAY-Überlagerung), **Synthwave Horizon** (leuchtendes perspektivisches Raster), **Deep Space Radar** (langsamer Radardurchlauf mit Blips) und **Schreibmaschine Noir** (Sepia-Papieroptik mit zeichenweiser Ausgabegeschwindigkeit). Jeder Effekt berücksichtigt die Einstellung der Animationsgeschwindigkeit und seine Beschreibung ist in allen unterstützten Sprachen lokalisiert.
- **Getaktete Ausgabe der Typewriter Noir** – der Effekt **Typewriter Noir** gibt die Terminalausgabe Zeichen für Zeichen aus, um das Gefühl einer mechanischen Schreibmaschine zu erzeugen; Bei Massenausgaben wie dem Drucken einer großen Datei wird die Geschwindigkeit umgangen, sodass sie nie verlangsamt wird.
- **Terminaleffekte pro Bereich** – Terminaleffekte beziehen sich jetzt auf jeden einzelnen geteilten Bereich statt auf die gesamte Registerkarte. Innerhalb einer Registerkarte können Sie einen Effekt in einem Bereich ausführen, während ein gleichgeordneter Bereich einen anderen oder keinen Effekt anzeigt und die Farben und Schriftarten jedes Effekts auf seinen eigenen Bereich beschränkt bleiben. Globales Zoomen und Zurücksetzen gelten weiterhin für die gesamte Registerkarte.
- **Fensterspezifisches Effektmenü und Vererbung** – das Rechtsklick-Menü jedes geteilten Fensters erhält ein Untermenü „Terminaleffekt“, in dem Sie „Keine“ oder einen beliebigen installierten Effekt nur für dieses Fenster auswählen können, sowie einen Schieberegler für die Animationsgeschwindigkeit. Die Auswahl gilt nur zur Laufzeit und wird nicht in der Verbindung gespeichert. Beim Teilen eines Bereichs wird der neue Bereich mit dem gleichen Effekt und der gleichen Animationsgeschwindigkeit wie der Bereich gestartet, von dem er geteilt wurde.
- **Animierte Effektvorschauen im Plugin-Manager** – **Plugins → Terminaleffekte** zeigt jetzt eine animierte Live-Vorschau des ausgewählten Effekts neben der Plugin-Liste an, sodass Effekte verglichen werden können, bevor sie in einer Sitzung aktiviert werden. Plugins ohne Vorschau zeigen stattdessen einen Platzhalter an.

### AI-Chat

- **Chat-Farbprofile** – der AI-Chat und der AI-Swarm-Chat verfügen jetzt über auswählbare Farbthemen. Elf integrierte Profile werden mitgeliefert: **Automatic (Theme)**, das Ihrem aktiven Terminal-Theme folgt, plus **Original**, **Paper**, **Midnight**, **Cyberpunk**, **Retrowave**, **Forest**, **Ocean**, **Terminal**, **GPT** und **Cute**. Wählen Sie eines aus der Dropdown-Liste „Farbprofil“ in der Chat-Symbolleiste oder unter **Einstellungen → Erscheinungsbild → Chat-Farbprofil**; Die Auswahl wird gespeichert und live auf jeden offenen Chat angewendet.
- **Volltext-Chat-Suche** (++Strg+F++, Befehl+F auf macOS) – klicken Sie auf die Schaltfläche **Suchen** in der Chat-Symbolleiste oder drücken Sie die Tastenkombination, um eine Suchleiste über einer KI- oder Schwarmkonversation zu öffnen. Es durchsucht den gesamten Chat einschließlich Codeblöcken, zeigt die Anzahl der Live-Matches an, springt mit den Pfeiltasten oder der Eingabetaste zwischen Treffern und skizziert und scrollt jedes Match in die Ansicht. Esc schließt die Leiste.
- **Neu gestalteter, vollständig themenbezogener Chat** – der KI-Chat und der Schwarm-Chat wurden neu gestaltet, sodass Ihre Nachrichten in einer nach rechts eingerückten, abgerundeten Blase angezeigt werden und jede KI-Antwort eine Karte in voller Breite darstellt, wobei Codeblöcke, Tabellen, der Komponist und Bildlaufleisten alle dem ausgewählten Farbprofil folgen und nicht einem festen Lichtstil.

### Datenschutz und Analyse

- **Anonyme Nutzungsanalyse (Opt-in, standardmäßig deaktiviert)** – korTTY kann optional anonyme, DSGVO-konforme Nutzungsstatistiken über Aptabase teilen (verarbeitet auf EU-Servern), um Funktionen zu priorisieren und Abstürze und häufige Fehler anzuzeigen. Es ist standardmäßig eine strikte Opt-in- und Deaktivierungsfunktion. Es werden nur Ereignisnamen, aggregierte Anzahlen und Flags, die App-Version, der Name und die Version des Betriebssystems, die App-Sprache und eine anonyme Sitzungs-ID pro Start gesendet – Hostnamen, Benutzernamen, Verbindungsdaten, Dateipfade, Snippet-/Terminal-/Chat-Inhalte, Schlüssel, Passwörter und Fehlermeldungstexte werden niemals erfasst.
- **Einmalige Einwilligungsaufforderung** – Sie werden genau einmal gefragt, ob Sie anonyme Daten weitergeben möchten: bei Neuinstallationen als Kontrollkästchen neben der Einrichtung des Master-Passworts und bei bestehenden Installationen als einmalige Aufforderung nach dem Entsperren. Jede Entlassung gilt als *nein* und Sie werden nicht erneut gefragt. Jede Einwilligungsoberfläche verfügt über eine Schaltfläche **Weitere Informationen**, die das neue Anleitungkapitel *Anonyme Daten zur Anwendungsoptimierung* öffnet.
- **Einstellungen → Registerkarte „Datenschutz“** – eine neue Registerkarte **Datenschutz** unter **Einstellungen** ermöglicht es Ihnen, anonyme Nutzungsstatistiken jederzeit ein- oder auszuschalten und zeigt genau an, was erfasst wird und was nicht, sowie das Datum, an dem Ihre Auswahl aufgezeichnet wurde. Wenn Sie es deaktivieren, wird die Erfassung sofort gestoppt und sowohl die ausstehende Warteschlange als auch alle lokal zwischengespeicherten Ereignisse verworfen.
- **Offline-Ereignis-Caching** – während Sie offline sind, werden anonyme Ereignisse lokal unter `~/.kortty` zwischengespeichert und gesendet, sobald eine Verbindung verfügbar ist; Sie überleben App-Neustarts, werden nach drei Tagen gelöscht und werden vollständig verworfen, wenn Sie sich abmelden.

### Korrekturen

- **Effekte leuchten korrekt, wenn sie für ein Live-Fenster aktiviert sind** – das zeilenweise Leuchten des MU/TH/UR-Effekts funktioniert jetzt, wenn Sie einen Effekt auf einem bereits verbundenen Fenster aktivieren, nicht nur, wenn der Effekt vor dem Verbinden aktiv war, und es pulsiert bei der Ausgabe mit schnellem Bildlauf gleichmäßig, anstatt zu flackern.
- **Strg+D in einem geteilten Bereich schließt nur diesen Bereich** – das Verlassen der Shell (Strg+D oder `exit`) in einem Bereich einer Teilung schließt jetzt nur diesen Bereich und lässt die anderen Bereiche geöffnet; Die Registerkarte wird erst geschlossen, wenn die Sitzung des letzten verbleibenden Bereichs endet.
- **Terminaleffekte stürzen nicht mehr beim Rendern ab, wenn viele Registerkarten geöffnet sind** – Das Öffnen mehrerer Terminalregisterkarten mit jeweils einem aktiven Effekt könnte den GPU-Texturpool erschöpfen und das Rendern abstürzen lassen. Effekt-Overlays (einschließlich MU/TH/UR 6000) geben jetzt ihre Leinwandtextur für das gesamte Fenster frei, während sich ihre Registerkarte im Hintergrund befindet, und binden sich automatisch neu, wenn die Registerkarte erneut angezeigt wird.
- **AI Planning stellt abgeschnittenes oder fehlerhaftes JSON wieder her** – die Registerkarte **AI Planning** schlägt nicht mehr komplett mit der Meldung *„AI-Antwort enthielt nicht das erforderliche JSON-Objekt“* fehl, wenn ein Modell einen unvollständigen oder nicht geschlossenen Plan zurückgibt, was häufig bei sehr kleinen Modellen vorkommt, die vor Abschluss des Schemas anhalten. Planning versucht es jetzt einmal mit einer Reparaturaufforderung erneut und ist normalerweise beim zweiten Versuch erfolgreich. Wenn es immer noch fehlschlägt, erklärt der Fehler, dass das Modell zweimal ungültiges JSON zurückgegeben hat, und schlägt ein größeres oder leistungsfähigeres Planungsmodell vor.
- **Guide-Fenster stürzt nicht mehr im Hintergrund ab** – wenn der In-App-Guide (**Hilfe → Anleitung**, F1) geöffnet bleibt, während zu einer anderen App gewechselt wird, kann korTTY nach einer Weile nativ abstürzen, da der inaktive eingebettete Browser im Hintergrund läuft. korTTY entlädt jetzt die Guide-Seite, nachdem das Fenster 20 Sekunden lang minimiert oder unfokussiert war, und stellt beim Zurückkehren dieselbe Seite und Bildlaufposition wieder her, und das Einführungsvideo des Guides wird einmal abgespielt, anstatt sich endlos zu wiederholen.

## v2.3.3

### Korrekturen

- **macOS: „Quit korTTY“ beendet tatsächlich die App** – in der gepackten macOS-App wurde korTTY durch ein natives Beenden (Cmd+Q, **Quit korTTY** im App-Menü, **Quit** des Docks oder Abmelden) im Hintergrund ausgeführt, sodass der Prozess abgebrochen werden musste. Die gepackte App läuft absichtlich weiter, nachdem das letzte Fenster geschlossen wurde (damit der JobScheduler Hintergrundjobs ausführen kann), aber JavaFX hat ein natives Beenden nur in „Fenster schließen“ übersetzt, nie in ein tatsächliches Beenden. korTTY fängt nun das native Quit ab und führt seine eigentliche Quit-Sequenz aus. Ein Shutdown-Watchdog garantiert außerdem, dass der Prozess immer beendet wird, das Dialogfeld „Warten auf laufende Jobs“ hat eine Schaltfläche „Jetzt beenden erzwingen“ erhalten und die Bereinigung der Menüleistensymbole birgt nicht mehr das Risiko, dass das Herunterfahren verzögert wird.

## v2.3.2

### Korrekturen

- **Snippet-Editor-Diagramme werden gerendert, wenn Graphviz außerhalb des PATHs der App installiert wird** – das Generieren eines **Diagramms** im Snippet-Editor schlägt nicht mehr mit „Graphviz dot ist erforderlich, um PlantUML-Diagramme zu rendern“ fehl, wenn `dot` installiert ist (z. B. über Homebrew), aber nicht auf dem minimalen PATH, den eine auf dem Desktop gestartete App von launchd erbt. korTTY findet `dot` und die Java-Laufzeit jetzt auf die gleiche Weise, wie es AI-CLIs findet – durchsucht den PATH sowie allgemeine Installationsverzeichnisse (`/opt/homebrew/bin`, `/usr/local/bin`, …) – und übergibt den aufgelösten `dot`-Pfad über `GRAPHVIZ_DOT` an den PlantUML-Renderer, sodass dieser ihn nicht erneut ermitteln muss.

## v2.3.1

### Korrekturen

- **„Als Textdatei laden“ folgt `cd` in lokalen Shells** – in einer lokalen Shell-Registerkarte wird die Datei beim Laden einer ausgewählten Datei mit **Als Textdatei laden** nach einem Verzeichniswechsel nicht mehr gefunden. korTTY liest jetzt das Live-Arbeitsverzeichnis der Shell direkt vom Betriebssystem (dem aktuellen Verzeichnis des Shell-Prozesses), anstatt sich nur auf den Eingabeaufforderungstext zu verlassen, der nicht den vollständigen Pfad preisgibt, wenn die Eingabeaufforderung nur den Ordnernamen anzeigt (die macOS-zsh-Standardeinstellung). Unter macOS/Linux wird die Auswahl anhand des Verzeichnisses aufgelöst, in dem sich die Shell tatsächlich befindet. Unter Windows greift es auf das vorherige auf Eingabeaufforderungen basierende Verhalten zurück.
- **Die KI-Funktionen des Snippet-Editors arbeiten mit Argumentationsmodellen und gesprächigen Antworten** – KI-Aktionen im Snippet-Editor (**Diagramm**, **Überprüfung**, **Verbessern**, **Assistent**, **Sicherheit**, **Alternativen**, **Beschreiben**, **Vollständig**, **Einzeiler**) schlagen nicht mehr fehl – ​​z. B. *„PlantUML-Generierung fehlgeschlagen“* – wenn das Modell seine JSON-Antwort in Prosa oder einen Code-Fence verpackt oder wenn ein lokales Argumentationsmodell (LM Studio, Ollama, llama.cpp, das DeepSeek-R1/QwQ/gpt-oss bereitstellt) einen `<think>…</think>`-Block ausgibt. Der Antwortparser entfernt jetzt durchgesickerte Argumente und extrahiert die echte JSON-Nutzlast robust anstelle eines gierigen Matchs, das bei einer verirrten Klammer fehlschlug.
- **Snippet-Editor-KI-Fehler sind jetzt sichtbar** – wenn eine Snippet-Editor-KI-Aktion fehlschlägt, wird die eigentliche Ursache in das Protokoll geschrieben und die entsprechende Meldung wird in der Statusleiste angezeigt. Zuvor wurde die Ausnahme verworfen, sodass ein falsch konfiguriertes KI-Profil (z. B. ein Cloud-Profil ohne ausgewähltes Modell, das *„Wählen Sie ein Modell…“* meldet) dazu führte, dass jede KI-Funktion stillschweigend fehlschlug und nur eine generische Meldung und nichts im Protokoll angezeigt wurde.

## v2.3.0

### AI Schwarm

- **AI Swarm-Registerkarte** (**AI → AI Swarm...**, ++ctrl+alt+s++ / Cmd auf macOS) – sendet eine AI-Agent-Aufgabe gleichzeitig an viele Server; Jeder Server führt seinen eigenen Agenten aus und die Antworten werden in einer einzigen Vergleichstabelle mit einer Zeile pro Server und einer wörtlichen Spalte „Fehler“ für Abweichungen und Fehler zusammengefasst.
- **Animierter Statusstreifen** – eine Kugel pro Agent über der Konversation zeigt auf einen Blick an, ob sie in der Warteschlange steht/läuft/auf Eingaben wartet/angehalten/erledigt/fehlgeschlagen/abgebrochen ist, markiert *ungewöhnlich lange* Ausführungen über einen adaptiven Schwellenwert (`max(60 s, 2 × median of finished agents)`) und durch Klicken auf eine Kugel wird zur Zeile des Agenten gesprungen. Der Strip skaliert von 1 bis 50+ Servern.
- **Pro-Agent und schwarmweite Ausführungssteuerung** – Anhalten, Fortsetzen, Neustarten und Stoppen entweder eines einzelnen Agenten (Rechtsklick auf seine Zeile) oder des gesamten Schwarms (Symbolleiste). Das Pausieren ist kooperativ und stoppt die abgelaufenen Timer. Neustarts ersetzen nur die Antwort dieses Agenten.
- **Erweiterbare Live-Transkripte** – Klicken Sie mit der linken Maustaste auf eine Agentenzeile, um deren Live-Befehls-/Ausgabetranskript während der Ausführung inline anzusehen.
- **Konversation kopieren und exportieren** – Kopieren Sie die gesamte Schwarmkonversation in die Zwischenablage oder exportieren Sie sie als einfachen Text, Markdown oder PDF; Gespeicherte Schwarm-Chats erhalten im AI Manager einen eigenen Bereich **Schwarm-Chats**.
- **Lesbare Ergebniszeilen** – Wenn Sie auf eine Zeile der kombinierten Antworttabelle klicken, wird diese in einem separaten Fenster mit **Zeilendetails** mit der Schriftgröße A−/A+ und der Option zum Kopieren in die Zwischenablage geöffnet.
- **Ziele ohne offene Terminals** – Schwarmläufe (KI und Skript) funktionieren jetzt über Hintergrund-SSH-Sitzungen auf gespeicherten Servern ohne offene Terminals; Es wird kein Terminal-Tab geöffnet. Erfordert einen entsperrten Master-Passwort-Tresor.
- **Skripte ohne KI ausführen** – Führen Sie ein Snippet-Manager-Skript mit Parametern auf allen Schwarmzielen parallel aus (Base64-übertragen, einzelne Bestätigung), mit Live-Ausgabe pro Server und einer Exit-Code-/Ausgabeergebnistabelle pro Server.
- **Schedule Swarm Runs** – ein neuer JobScheduler-Aktionstyp **AI_SWARM** mit **Swarm-Parallelität** (1–16) und **Swarm-schreibgeschützten** Feldern; Über die Schaltfläche **Planen…** auf der Registerkarte „Schwarm“ wird ein Job anhand der aktuellen Ziele und Eingabeaufforderungen vorab ausgefüllt. Die Ergebnisse gehen in das Journal *und* in einen gespeicherten Schwarm-Chat.
- **Sichtbarer Composer- und Tab-Statuspunkt** – die Schwarmeingabe ist ein klar umrahmtes dreizeiliges Feld und der Tab zeigt einen farbigen Aktivitätspunkt (wird ausgeführt / wartet auf Eingabe / pausiert / beendet – der grüne Punkt bleibt bis zur nächsten Ausführung bestehen).
- **Multi-Server-Workflow-Dialog überarbeitet** – syntaxhervorgehobene Skriptansicht, eine sichtbare Arbeitsanimation mit verstrichener Live-Zeit und Gesamtdauer, ein Feld für zusätzliche Anweisungen mit einem deduplizierten 10-Eintrags-Verlauf und **In Snippets speichern** mit einem passenden vorab ausgefüllten Skriptnamen.

### Aussehen

- **Fünf neue App-Designs** – *Amber CRT* (warmes Bernstein-Phosphor-Retro-Terminal), *Synthwave '84* (80er-Jahre-Outrun-Neon), *Gruvbox Retro* (gemütlich warm erdig), *Nord Arctic* (ruhiges, flaches arktisches Blaugrau) und *Dracula* (sanftes Lila/Rosa) ergänzen die bestehenden Designs unter *Einstellungen → Aussehen*, jedes mit seinem eigenen Vorschaubild. Die bestehenden Designs bleiben unverändert.
- **Dezente Designanimationen umschalten** – eine neue Darstellungseinstellung (Standardeinstellung) lässt die leuchtenden Designs einen kleinen Akzentpunkt in der Statusleiste einatmen; Das Ausschalten dient gleichzeitig als Option zur Bewegungsreduzierung und die Animation stoppt, während das Fenster ausgeblendet ist.
- **Konsistenteres Design-Chrom** – Die Farben eines benutzerdefinierten Designs werden jetzt deterministisch auf alle Menüs und Dialoge angewendet, und das dynamische Stylesheet des Terminal-Designs überschreibt nicht mehr die Chromfarben des aktiven Designs.
- **App-Design `Normal` wurde unter *Einstellungen → Erscheinungsbild* in `Default`** umbenannt. Der gespeicherte Wert bleibt unverändert, sodass vorhandene Konfigurationen ihr ausgewähltes Design behalten.
- **Mit den Schaltflächen „Zurück/Weiter“ neben dem Dropdown-Menü „App-Design“ können Sie durch die Designs vor- und zurückblättern (an den Enden umschließen), ohne das Dropdown-Menü zu öffnen.
- **Die Designvorschau wurde unterhalb der Steuerelemente** in einen Bereich mit fester Größe verschoben, sodass beim Wechseln der Designs (oder zurück zu `Default`, das keine Vorschau hat) die Vorschau nicht mehr über dem Dropdown-Menü angezeigt wird.

### Lokale Shell-Verbindungen

- **Öffnen Sie die Shell des lokalen Computers in einer Terminal-Registerkarte (kein Netzwerk)** – ein neues **Local Shell**-Protokoll erzeugt ein lokales Pseudo-Terminal (PTY) über pty4j, anstatt eine Verbindung zu einem Remote-Host herzustellen. Unter Windows können Sie **PowerShell** (Standard) oder **cmd.exe** wählen; Unter macOS/Linux wird standardmäßig Ihr `$SHELL` verwendet (wobei auf `/bin/zsh` oder `/bin/bash` zurückgegriffen wird). Ein Freiformfeld **Benutzerdefinierter Befehl** akzeptiert jede ausführbare Datei mit Argumenten (z. B. `pwsh.exe`, `wsl.exe -d Ubuntu`, Git Bash), und ein optionales Startverzeichnis kann festgelegt werden. Die lokale Shell kann sowohl in Quick Connect als auch im Verbindungsmanager ausgewählt werden. Für diese Verbindungen sind Host/Port/Benutzername/Authentifizierung nicht erforderlich und in den Dialogen deaktiviert.
- **Git Bash/Cygwin/WSL-Voreinstellungen** unter Windows – werden jeweils nur angeboten, wenn sie tatsächlich installiert sind (Git Bash/Cygwin über ihre üblichen Installationsorte / `PATH`; WSL nur, wenn `wsl.exe` vorhanden und mindestens eine Distribution installiert ist). Der Befehlsparser erkennt Anführungszeichen, sodass Shell-Pfade mit Leerzeichen (wie `"C:\Program Files\Git\bin\bash.exe"`) korrekt gestartet werden.
- **Gemeinsame Connector-Hooks** – Terminalaufzeichnung/-protokollierung und die AI-Eingabe-/Daten-Hooks wurden auf eine gemeinsam genutzte `ObservableTtyConnector`-Schnittstelle übertragen, sodass sie auch für lokale Shells funktionieren. Nur-SSH-Kanal-Funktionen bleiben nur SSH-Kanal.
- **AI Agent & Planning in lokalen Shells** – die Befehlsausführungs-Engine des Agenten wurde hinter einer `AgentCommandRunner`-Abstraktion (SSH-Ausführungskanal und lokale Prozess-Backends) von SSH entkoppelt. Der **AI Agent** und **AI Planning** laufen jetzt in lokalen Shells unter Windows, macOS und Linux: Befehle werden in der Shell der Verbindung ausgeführt (PowerShell über `-EncodedCommand`, `cmd.exe` oder `$SHELL`), die Umgebungsprüfung und die Systemeingabeaufforderung sind plattformorientiert und es gilt der bestehende Genehmigungsablauf. Einschränkungen für lokale Shells: keine `sudo`/Administrator-Erhöhung unter Windows und keine Live-Nachverfolgung des Arbeitsverzeichnisses. Die kopflose KI-Agent-Aktion des JobScheduler bleibt nur SSH.

### Terminal-Benutzerfreundlichkeit

- **Strg + Mausrad-Zoom** – Halten Sie **Strg** (oder **Befehl** unter macOS) gedrückt und scrollen Sie mit dem Mausrad über das Terminal, um jetzt die Schriftgröße zu ändern, anstatt durch den Puffer zu scrollen. Dies ergänzt die vorhandenen Tastenkombinationen Alt+Plus / Alt+Minus / Alt+0.
- **Strg+D schließt eine lokale cmd.exe/PowerShell-Registerkarte** – diese Windows-Shells werden bei EOF nicht beendet, daher hatte Strg+D dort keine Auswirkung. Bei Shells der Bash-Familie (Git Bash/Cygwin/WSL, macOS/Linux) und SSH behält Strg+D seine normale EOF-Bedeutung.

### AI Chat & Agent

- **Bilder, Diagramme und Mathematik werden in KI-Chats gerendert** – KI-Antworten, die ein SVG-Dokument, ein Base64-Rasterbild (`data:image/png;base64,…` – PNG, JPEG, GIF, BMP), einen ` ```plantuml `-Block, einen ` ```mermaid `-Block oder LaTeX-Mathematik enthalten (` ```latex `/` ```tex `/` ```math `-Blöcke und `$$…$$` in Prosa) werden als Bilder anstelle von rohem Markup angezeigt, jeweils mit einer Umschalt- und Kopierschaltfläche **Code anzeigen/Bild anzeigen**. Mermaid und MathJax sind gebündelt (kein Netzwerk); PlantUML verwendet die lokale Toolchain (`java` + Graphviz `dot`); Die SVG-Ausgabe wird bereinigt und mit deaktiviertem JavaScript angezeigt.
- **Vollständige Modellbegründung in Agenten-Denkzeilen** – Erweitern einer 💭-Zeile im Agentenaktivitätsbereich zeigt jetzt die tatsächliche Begründung des Modells an, wenn der Anbieter sie verfügbar macht (anthropisches erweitertes Denken gemäß der Begründungsbemühungen des Profils, OpenAI-kompatibel `reasoning_content`, LM Studio-Begründungsausgabe, `<think>`-Blöcke aus lokalen CLI-Modellen), andernfalls wird auf die Entscheidungszusammenfassung zurückgegriffen.
- **Das Laufprotokoll zeichnet das AI-Profil auf** – jeder Agentenlauf beginnt mit einer `AI profile: <name> (<model>)`-Aktivitätszeile.
- **Neu laden verwendet das aktuell aktive Profil** – die Schaltfläche „Neu laden“ im Aktivitätsbereich führt den Befehl erneut mit dem Profil aus, das jetzt aktiv ist, nicht mit dem, mit dem die ursprüngliche Ausführung gestartet wurde.
- **Agent Ask beinhaltet die Terminalauswahl** – wenn **AI → Ask AI Agent** über das Kontextmenü mit ausgewähltem Text gestartet wird, wird die Auswahl als Kontext gesendet, sodass die Frage zur ausgewählten Ausgabe oder zum ausgewählten Skript beantwortet wird.
- **Konkrete Modelle für Cloud-Profile** – Die Modellauswahl ist vorab mit gängigen Modellnamen für bekannte Cloud-Anbieter gefüllt (offline, kein API-Schlüssel erforderlich), die Schaltfläche „Aktualisieren“ führt die Live-Modellliste des Endpunkts zusammen, das Klicken auf ein Modell in der Dropdown-Liste wendet es jetzt zuverlässig an und die unbrauchbare Option **Auto** wird für Cloud-Endpunkte nicht mehr angeboten (mit einem deutlicheren Fehler, wenn kein Modell ausgewählt ist).

### Guide AI-Dokumentsuche

- **Fragen Sie die Anleitung in natürlicher Sprache** – der integrierte Anleitung (**Hilfe → Anleitung**, ++f1++) verfügt über einen Seitenbereich **KI-Suche**: Geben Sie eine Frage in Ihrer Sprache ein und Sie erhalten eine Antwort, die ausschließlich aus der gebündelten Dokumentation generiert wird, mit anklickbaren Zitaten, die den Anleitung direkt zur referenzierten Seite weiterleiten.
- **Verwendet Ihr Standard-KI-Profil; Der Abruf erfolgt vollständig offline** – kein Server, keine zusätzlichen API-Schlüssel, keine neuen Abhängigkeiten. Die Recherche erfolgt lokal über den gebündelten Suchindex (mit zweisprachigen Synonymen, deutschem Kompositumsplitting und Umlautfaltung); Off-Topic-Fragen werden lokal beantwortet, ohne dass der KI-Endpunkt überhaupt kontaktiert werden muss.
- **Begründete Antworten** – das Modell ist auf die abgerufenen Auszüge beschränkt, erfundene Links werden repariert oder entfernt und eine native **Quellenliste** zeigt immer die zitierten Seiten an, unabhängig von der Antwort des Modells.

### Workflow-Skriptgenerator

- **Zwei neue Zielsprachen** – der Agentenlauf → **Workflow**-Skriptgenerator kann jetzt zusätzlich zu Bash, Python, Perl, Ruby, PowerShell und Ansible auch **Windows-CMD** (`.cmd`-Batch) und **AppleScript** (`.applescript`) erzeugen.
- **Anpassbare Skriptschriftgröße** – jeder Editor für generierte Skripte verfügt über die Schaltflächen **A−** / **A+** und unterstützt **Strg + Mausrad** (Befehlstaste unter macOS); Die gewählte Größe wird sitzungsübergreifend gespeichert.
- **Sichtbarer Fortschritt beim Generieren eines Diagramms** – Beim Generieren eines PlantUML-Diagramms aus einem Skript wird jetzt der funktionierende Spinner angezeigt.
- **Deutlichere AI-Backend-Fehler** – Nicht genügend Arbeitsspeicher/Ressourcenlimit-Fehler vom AI-Server (z. B. LM Studio/MLX „Ressourcenlimit überschritten“, „metal::malloc“) zeigen einen kurzen, umsetzbaren Hinweis anstelle des rohen Stack-Trace; Alle anderen KI-Fehler werden in einer einzigen Zeile zusammengefasst.

### Korrekturen

- **Das Schließen einer lokalen Shell friert korTTY nicht mehr ein** – der PTY-Prozess wird jetzt zerstört, bevor seine Streams geschlossen werden, wodurch ein in einem PTY `read()` blockierter Terminal-Reader-Thread freigegeben wird, anstatt das Schließen im JavaFX-Thread zu blockieren.
- **Korrekter Wortlaut für lokale Shells beim Schließen** – die Schließbestätigung sagt nicht mehr „SSH-Verbindung beenden?“ für eine lokale Shell, und die Eingabeaufforderung zum Schließen des Fensters ist jetzt transportneutral („Aktive Sitzungen“).
- **Keine Passwortabfrage für lokale Shells** – Beim Öffnen einer lokalen Shell wird kein irrelevantes Passwortdialogfeld mehr angezeigt (lokale Shells verwenden keine Authentifizierung).
- **„Als Textdatei laden“ funktioniert in lokalen Shells** – wenn Sie mit der rechten Maustaste auf einen ausgewählten Dateinamen in einer lokalen Shell-Registerkarte klicken und **Als Textdatei laden** auswählen, schlägt die Fehlermeldung „Keine aktive SSH-Verbindung ist verfügbar“ nicht mehr fehl. Die Datei wird aus dem lokalen Dateisystem gelesen – aufgelöst anhand des Arbeitsverzeichnisses, das in der Shell-Eingabeaufforderung angezeigt wird, sofern verfügbar, andernfalls dem Verzeichnis, in dem die Shell gestartet wurde – und wird im Snippet-Editor mit **Lokale Datei überschreiben** und **Speichern unter...** geöffnet, genau wie die SSH/SFTP-Variante. Die Fehlermeldung „Nicht verbunden“ ist jetzt transportneutral.
- **Lokale Dateiüberschreibungen sind jetzt atomar** – beide „Lokale Datei überschreiben“-Abläufe (Local-Shell **Als Textdatei laden** und der SFTP-Manager-Lokaldatei-Editor) werden verwendet, um die Zieldatei an Ort und Stelle zu kürzen, sodass ein Fehler während des Schreibvorgangs (Festplatte voll, Prozessabbruch, Stromausfall) dazu führen kann, dass sie ohne Wiederherstellung abgeschnitten wird. Überschreiber schreiben jetzt in eine gleichgeordnete temporäre Datei und verschieben sie an ihren Platz, behalten die POSIX-Berechtigungen der Originaldatei bei und schreiben über symbolische Links zu ihrem eigentlichen Ziel, anstatt den Link selbst zu ersetzen.

## v2.2.3

### Kritischer Fix: Monaco-Editoren konnten die gepackte App nicht laden

- **Behoben, dass die auf Monaco basierenden Editoren (Snippet, Datei, AI, Diff) in der gepackten/beglaubigten macOS-App** ein leeres Fenster öffneten – kein Caret, kein Tippen, kein Einfügen. In der gepackten App hat WebView seine Seite von einer `jar:`-URL geladen, und die Content-Security-Policy (`script-src 'self'`) der Seite hat dann die eigene `monaco-host.js`/`.css` des Herausgebers blockiert, da ein `jar:`-Ursprungsdokument seine `jar:`-Geschwister nicht autorisiert. Die Monaco-Ressourcen werden nun in ein temporäres Verzeichnis extrahiert und von einer `file:`-URL geladen, was der CSP zulässt. Ein fehlgeschlagener Editor-Ladevorgang zeigt jetzt auch einen Fehler anstelle eines stillschweigend leeren Bereichs an, und das Editor-Bundle wird zusätzlich durch ein großzügigeres Boot-Budget verkleinert.

## v2.2.2

### Kritischer Fix: Absturz beim Öffnen der Monaco-Editoren

- **Ein schwerwiegender Absturz (kein Bildschirmfehler) beim Öffnen des Snippet-Managers, des Snippet-Editors oder des AI-Skill-Editors für Einstellungen in gepackten Builds wurde behoben**: In der gebündelten Laufzeit fehlte das `jdk.jsobject`-Modul, sodass `netscape.javascript.JSObject` zur Laufzeit nicht verfügbar war und die JVM in JNI `get_method_id` (`SIGSEGV`) abstürzte. `jdk.jsobject` ist jetzt in der Paketlaufzeit gebündelt. Diese Version ersetzt v2.2.0 und v2.2.1, deren Binärdateien von diesem Absturz betroffen sind.

## v2.2.1

### Stabilitätskorrekturen

- **Absturz der Einstellungen/Snippet-Manager behoben**: Das Öffnen der **Globalen Einstellungen** oder des **Snippet-Managers** konnte zum Abbruch der App führen. Die JavaScript→Java-Brücke des eingebetteten Monaco-Editors wird jetzt für die gesamte Lebensdauer des Editors von einer starken Referenz gehalten.
- **WebView-Lebenszyklushärtung**: Monaco-Editoren werden verworfen, wenn ihr Dialog geschlossen wird; Späte Timer-/Laderückrufe nach dem Schließen werden ignoriert. Der Einstellungen-Editor *AI Skills* lädt bei der ersten Verwendung langsam.

### Master-Passwort-Anmeldefenster

- **Vollflächiges animiertes Logo** im Standard-App-Design, mit dem Passwortformular auf einer durchsichtigen Karte überlagert.

## v2.2.0

### Terminal-Engine und Hyperlinks

- **SithTermFX 1.2.0** Terminal-Engine (aus dem Quellcode erstellt).
- **OSC 8 anklickbare Hyperlinks** – von Programmen wie `ls --hyperlink` oder `eza` ausgegebene Links, beschränkt auf eine sichere URI-Schema-Zulassungsliste.

### Mosh (mosh4j) 2.0.2 Upgrade und Sicherheitshärtung

- mosh4j `2.0.0 → 2.0.2` mit richtungsabhängigem Wiedergabe-/Aktivitätsschutz und Dekompressionsbombengrenzen; Geben Sie JARs frei, die in nativen Builds gebündelt sind.
- Bouncy Castle `1.78.1 → 1.84` (behebt CVE-2026-5598 HIGH und CVE-2026-0636 MODERATE); protobuf-java `4.28.2 → 4.35.1`.

### AI Agentenpanel und Aktivität

- **AI Agent Panel-Platzierung**: *Unten* (Standard), *Links andocken* oder *Rechts andocken*, wird bei jedem Neustart gespeichert.
- **Mehrere gleichzeitige Läufe pro Split** (maximal 5), Pause/Fortsetzung pro Lauf und Dashboard-/Tab-Statusabzeichen (✋ wartend · ⚡ in Arbeit · ⏸ pausiert · ✓ beendet).

!!! note
    Ältere Releases sind im `app-docs/RELEASE_NOTES.adoc` des Repositorys erfasst und werden hierher vollständig migriert.
