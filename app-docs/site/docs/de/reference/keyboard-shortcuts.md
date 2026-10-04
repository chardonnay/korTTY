# Tastaturkürzel

Verwenden Sie unter macOS ++cmd++, wo ++ctrl++ angezeigt wird.

## Allgemein

| Verknüpfung | Aktion |
| --- | --- |
| ++ctrl+shift+p++ | Die [Befehlspalette](../features/command-palette.md) öffnen oder schließen, um einen beliebigen Menübefehl über seinen Namen zu finden und auszuführen, einen [Terminal- oder Tab-Befehl](../features/command-palette.md#terminal-und-tab-befehle) wie **Puffer löschen** auszuführen, [zu einem offenen Tab zu wechseln](../features/command-palette.md#tabs-wechseln), [eine gespeicherte Verbindung herzustellen](../features/command-palette.md#verbinden) oder [ein Snippet auszuführen](../features/command-palette.md#snippets-ausfuhren) (auch wenn ein Terminal den Fokus hat) |
| ++ctrl+t++ | Neuer Tab (Schnellverbindung) |
| ++ctrl+w++ | Tab schließen |
| ++ctrl+alt+shift+t++ | Zuletzt geschlossenen Terminal-Tab wieder öffnen (auch wenn ein Terminal den Fokus hat) |
| ++ctrl+shift+n++ | Neues Fenster |
| ++ctrl+shift+w++ | Fenster schließen |
| ++ctrl+tab++ | Nächster Tab (++ctrl++ auch auf macOS); bei aktivierter [Reihenfolge der letzten Nutzung](settings/window.md#tabs) der Tab, der vor dem aktuellen benutzt wurde |
| ++ctrl+shift+tab++ | Vorheriger Tab (++ctrl++ auch auf macOS); bei aktivierter Reihenfolge der letzten Nutzung der am längsten nicht benutzte Tab |
| ++ctrl+1++ … ++ctrl+8++ | Zum ersten bis achten Tab des Fensters springen (obere Reihe oder Nummernblock) |
| ++ctrl+9++ | Zum letzten Tab des Fensters springen |
| ++ctrl+o++ | Projekt öffnen |
| ++ctrl+s++ | Projekt speichern |
| ++ctrl+shift+b++ | Backup erstellen |
| ++ctrl+q++ | Aufhören |
| ++ctrl+x++ | Ausschneiden (deaktiviert für Anschlusslaschen) |
| ++ctrl+c++ | Kopie |
| ++ctrl+v++ | Einfügen |
| ++ctrl+f++ | Suchen im aktiven Tab (in einem fokussierten Terminal auf Windows und Linux geht die Taste zur Shell, siehe [Terminal](#terminal)) |
| ++ctrl+shift+space++ | Schnellauswahl: die Webadressen, Pfade, E-Mail-Adressen, IP-Adressen, Hashes und Zahlen, die der fokussierte Terminalbereich anzeigt, mit Kürzeln versehen, um eines davon mit einer Taste zu kopieren oder zu öffnen (nur Terminal-Tabs), siehe [Schnellauswahl](../features/terminal.md#schnellauswahl) |
| ++ctrl+k++ | Schnellverbindung |
| ++ctrl+m++ | Verbindungen verwalten |
| ++ctrl+shift+u++ | SFTP-Client |
| ++ctrl+shift+m++ | Zugangsdaten verwalten (auch wenn ein Terminal den Fokus hat) |
| ++ctrl+shift+g++ | GPG-Schlüssel verwalten |
| ++ctrl+shift+i++ | SSH-Schlüssel verwalten |
| ++ctrl+comma++ | Globale Einstellungen |
| ++ctrl+shift+s++ | Snippet-Manager |
| ++ctrl+shift+j++ | JobScheduler |
| ++ctrl+shift+v++ | Videomanager |
| ++ctrl+shift+e++ | Terminalaufzeichnung starten/stoppen |
| ++ctrl+alt+j++ | Sitzungsjournale |
| ++ctrl+alt+t++ | Starten/stoppen Sie das Sitzungsjournal des aktiven Tabs |
| ++ctrl+alt+c++ | Fügen Sie dem Protokoll der laufenden Sitzung einen Screenshot hinzu |
| ++ctrl+alt+l++ | Blenden Sie das Live-Journal-Panel auf der zuletzt verwendeten Seite ein/aus |
| ++ctrl+alt+g++ | Blenden Sie das Coding-Agents-Panel auf der zuletzt verwendeten Seite ein/aus |
| ++ctrl+alt+n++ | Springen Sie zum nächsten Coding-Agent, der auf eine Entscheidung wartet, über Fenster hinweg |
| ++ctrl+shift+a++ | ASCII-Art |
| ++ctrl+shift+y++ | Öffnen Sie KI-Manager |
| ++ctrl+alt+a++ | Öffnen Sie AI Agent |
| ++ctrl+alt+p++ | Öffnen Sie KI-Planung |
| ++ctrl+alt+s++ | Öffnen Sie den KI-Schwarm |
| ++ctrl+shift+d++ | Dashboard umschalten |
| ++ctrl+shift+t++ | Befehlszeitstempel umschalten |
| ++ctrl+shift+l++ | Menüleiste ein-/ausblenden |
| ++ctrl+shift+k++ | Docken Sie den Dateibrowser links an |
| ++ctrl+shift+r++ | Docken Sie den Dateibrowser rechts an |
| ++alt+plus++ | Vergrößern |
| ++alt+minus++ | Herauszoomen |
| ++alt+0++ | Zoom zurücksetzen |
| ++ctrl++ + Mausrad | Vergrößern/verkleinern Sie die Terminalschriftart (Befehlstaste + Rad unter macOS). |
| ++ctrl+d++ | Schließen Sie eine lokale Registerkarte „cmd.exe/PowerShell“ (EOF für Shells der Bash-Familie und SSH). |
| ++f1++ | Öffnen Sie die Anleitung (**Hilfe → Anleitung**) |
| ++f12++ | Vollbild umschalten |
| ++ctrl+shift+f++ | Schalten Sie den Nur-Terminal-Vollbildmodus um |
| ++ctrl+shift+h++ | Schalten Sie die [Hervorhebung von Schlüsselwörtern](../features/highlighting.md) für den fokussierten Terminalbereich ein oder aus |

Die Zoom-Tasten (++ctrl++ oder ++alt++ mit ++plus++ / ++minus++ / ++0++; ++cmd++ auf macOS) vergrößern das Terminal nur, wenn ein Terminal-Tab ausgewählt ist. In einem Snippet-Editor oder Dateieditor-Tab erreichen sie den Editor, und ++alt-graph+plus++ tippt dort sein Zeichen.

Die Tab-Sprungtasten funktionieren in jedem Tab, auch wenn ein Terminal oder ein Editor den Fokus hat, und eine Zahl, an deren Position kein Tab steht, bewirkt nichts. Auf macOS sind es ++cmd++ mit einer Ziffer, und ++cmd+shift++ mit einer Ziffer funktioniert ebenfalls, sodass ein französischer Mac (AZERTY) seine Ziffern erreichen kann (macOS reserviert ++cmd+shift+3++ bis ++cmd+shift+5++ für Bildschirmfotos). Unter Windows und Linux sind es genau ++ctrl++ mit einer Ziffer: ++alt-graph++-Kombinationen (die als ++ctrl+alt++ ankommen) und ++ctrl+shift+6++ (die Cisco-Break-Sequenz) erreichen weiterhin das Terminal. Eine Zifferntaste, die in Ihrer Tastaturbelegung ++plus++ oder ++minus++ eingibt, etwa die Taste 6 bei AZERTY, bleibt stattdessen eine Zoomtaste. Unter Linux mit einer Tastaturbelegung, deren Zahlenreihe andere Zeichen eingibt, springen die Ziffern der oberen Reihe möglicherweise nicht; die Ziffern des Nummernblocks (bei eingeschaltetem ++num-lock++) schon.

++ctrl+tab++ und ++ctrl+shift+tab++ folgen der Tab-Leiste, es sei denn, **Strg+Tab wechselt die Tabs in der Reihenfolge ihrer letzten Nutzung** ist in den [Fenster-Einstellungen](settings/window.md#tabs) aktiviert. Dann springt ++ctrl+tab++ zu dem Tab zurück, den Sie vor dem aktuellen benutzt haben; halten Sie ++ctrl++ gedrückt und drücken Sie erneut ++tab++, um weiter zurückzugehen, nehmen Sie ++shift++ hinzu, um in die andere Richtung zu gehen, und lassen Sie ++ctrl++ beim gewünschten Tab los. Nur dieser Tab zählt als benutzt. Jede andere Taste beendet zuerst das Durchblättern und wirkt dann auf den Tab, bei dem Sie angehalten haben.

Für „Geschlossenen Tab wieder öffnen“ wird ++ctrl+alt+shift+t++ verwendet, weil ++ctrl+shift+t++ die Befehlszeitstempel umschaltet und ++ctrl+alt+t++ das Sitzungsjournal. Unter Windows kommt ++alt-graph++ als ++ctrl+alt++ an; bei einer Tastaturbelegung, in der ++alt-graph+shift+t++ ein Zeichen eingibt (etwa `Þ` bei US-International), öffnet diese Kombination daher stattdessen einen geschlossenen Tab wieder, und das Zeichen wird nicht eingegeben.

## Terminal

Diese Tasten funktionieren, solange ein Terminalbereich den Fokus hat. Wie sie sich bei mehreren Bereichen verhalten, siehe [Broadcast-Modus](../features/terminal.md#broadcast-modus).

| Verknüpfung | Aktion |
| --- | --- |
| ++ctrl+shift+c++ / ++ctrl+shift+v++ | Terminalauswahl kopieren / in das Terminal einfügen (Windows und Linux; ++cmd+c++ / ++cmd+v++ auf macOS) |
| ++ctrl+l++ (Windows und Linux) | An die Shell gesendet: zeichnet den Bildschirm neu oder löscht den Bildschirm in bash, psql oder einer REPL, und der Scrollback bleibt erhalten |
| ++ctrl+f++ (Windows und Linux) | An die Shell gesendet: bewegt den Cursor um ein Zeichen vorwärts bei einem Bash-Prompt und scrollt vorwärts in `less` oder `vim` |
| ++cmd+k++ (macOS) | Puffer löschen: den Terminalbildschirm und seinen Scrollback leeren |
| ++cmd+f++ (macOS) | Suchen im Terminal-Scrollback |
| ++shift+tab++ | Back-tab (`ESC [ Z`), zum Beispiel um in einem Vollbildprogramm ein Feld oder einen Tab zurückzugehen |
| ++ctrl+left++ / ++ctrl+right++ | In der Shell ein Wort nach links / rechts springen (Windows und Linux) |
| ++option+left++ / ++option+right++ | Unter macOS ein Wort nach links / rechts springen (sendet `ESC b` / `ESC f` wie Terminal.app) |
| ++shift+page-up++ / ++shift+page-down++ | Den Scrollback von korTTY um eine Seite scrollen; in einem Vollbildprogramm wie `vim`, `less` oder `mc` geht die Taste an das Programm |
| ++ctrl+up++ / ++ctrl+down++ | Den Scrollback von korTTY um eine Zeile scrollen (++cmd+up++ / ++cmd+down++ unter macOS); unter Windows und Linux gehen die Tasten stattdessen an ein laufendes Vollbildprogramm |
| ++ctrl+tab++ / ++ctrl+shift+tab++ | Nächster / vorheriger Tab, auch wenn das Terminal den Fokus hat (auch unter macOS mit ++ctrl++) |
| ++ctrl+1++ … ++ctrl+9++ | Zu einem Tab springen, auch wenn das Terminal den Fokus hat (++cmd++ auf macOS, siehe [Allgemein](#allgemein)) |
| ++ctrl+alt+shift+t++ | Zuletzt geschlossenen Terminal-Tab wieder öffnen, auch wenn das Terminal den Fokus hat (++cmd+option+shift+t++ auf macOS) |
| ++ctrl++ + Klick auf einen Link | Den Link im Browser oder Mailprogramm öffnen (++cmd++ + Klick unter macOS), auch eine als Klartext ausgegebene Web- oder E-Mail-Adresse; ein Dateipfad oder `file:`-Link öffnet sich in SSH- und lokalen Shell-Tabs als Text im Snippet-Editor; ein einfacher Klick auf einen Link bewirkt nichts, und Rechtsklick → **Link öffnen** oder **Datei im Snippet-Editor öffnen** funktioniert ohne die Taste, siehe [Links in der Terminalausgabe](../features/terminal.md#links-in-der-terminalausgabe) |
| die Buchstaben eines Kürzels / ++shift++ + der letzte Buchstabe (während die Schnellauswahl läuft) | Den gekennzeichneten Text kopieren / eine Web- oder E-Mail-Adresse oder einen Dateipfad im Snippet-Editor öffnen; ++backspace++ nimmt den ersten Buchstaben eines zweibuchstabigen Kürzels zurück, ++esc++ oder jede andere Taste beendet die Schnellauswahl, siehe [Schnellauswahl](../features/terminal.md#schnellauswahl) |

Unter Windows und Linux erreichen ++ctrl+1++ bis ++ctrl+9++ das Programm im Terminal nicht mehr: Das Terminal von korTTY hat sie nie als eigene Tasten gesendet, daher verliert kein Programm eine Belegung, die es empfangen könnte.

Unter Windows und Linux haben **Puffer löschen** und **Suchen** keine eigenen Tasten, daher bleiben ++ctrl+l++ und ++ctrl+f++ bei den Programmen, die im Terminal laufen. Verwenden Sie Rechtsklick → **Puffer löschen** oder **Suchen** im Terminal, **Bearbeiten → Suchen…** mit der Maus, oder erreichen Sie beide per Tastatur über die [Befehlspalette](../features/command-palette.md#puffer-loschen-und-suchen-unter-windows-und-linux): ++ctrl+shift+p++, `puffer` oder `suchen` eingeben, ++enter++. ++ctrl+shift+f++ bleibt **Nur korTTY Applikationsfenster**, und ++ctrl+shift+k++ dockt den Dateibrowser weiterhin links an. ++ctrl+shift+h++ schaltet die Hervorhebung von Schlüsselwörtern um und erreicht das Terminal nicht, während ein einfaches ++ctrl+h++ die Shell weiterhin als Rücktaste erreicht.

Die Einfügen-Tasten laufen über den [Einfügeschutz](../features/terminal.md#einfugeschutz): Eingefügter Text mit Zeilenumbrüchen, mit Steuerzeichen oder von großem Umfang kann zuerst eine Bestätigung öffnen. In diesem Dialog ist **Abbrechen** die Standardschaltfläche, sodass ++enter++, ++space++ und ++esc++ das Einfügen verwerfen; klicken Sie auf **Einfügen**, oder wechseln Sie mit ++tab++ dorthin und lösen Sie die Schaltfläche mit ++space++ aus. Während eingefügter Text Zeile für Zeile gesendet wird ([Pause nach jeder eingefügten Zeile](../features/terminal.md#einfugen-in-langsame-gerate)), nimmt der Bereich keine anderen Tasten an, und ++esc++ bricht das Einfügen ab.

Jede andere Kombination von ++shift++, ++ctrl++ und ++alt++ mit den Pfeiltasten, ++home++ / ++end++, ++page-up++ / ++page-down++, ++insert++ / ++delete++ und ++f1++ bis ++f11++ wird so gesendet, wie xterm es sendet (++f12++ schaltet immer den Vollbildmodus um, und in einem Tab mit zwei oder mehr Bereichen setzt ++ctrl+alt++ mit einer Pfeiltaste stattdessen den Fokus auf einen anderen Bereich, siehe [Bereiche](#bereiche)), zum Beispiel ++ctrl+page-up++ als `ESC [ 5 ; 5 ~` und ++shift+f1++ als `ESC [ 1 ; 2 P`. Die Pfeiltasten folgen dem Cursor-Key-Modus des Programms: `mc` und `vim` schalten ihn ein und dann empfangen sie `ESC O A`, während eine Shell `ESC [ A` erhält. Verbindungen mit einer Nicht-xterm-Terminalemulation (Wyse, TeleVideo, HP, SCO ANSI, IBM 3270/5250, PETSCII) senden weiterhin feste Sequenzen ohne Modifikatoren.

## Bereiche

Diese Tasten teilen den aktiven Terminal-Tab, verschieben den Tastaturfokus zwischen seinen Bereichen und maximieren einen davon; siehe [Vorgänge aufteilen](../features/terminal.md#vorgange-aufteilen).

| Verknüpfung | Aktion |
| --- | --- |
| ++ctrl+shift+o++ | Den fokussierten Bereich auf dessen eigenem Server teilen: nach rechts, wenn der Bereich breit ist, nach unten, wenn er hoch ist; der neue Bereich erhält den Fokus (++cmd+shift+o++ auf macOS) |
| ++ctrl+alt+left++ / ++ctrl+alt+right++ | Fokus auf den Bereich links / rechts setzen (++cmd+option+left++ / ++cmd+option+right++ auf macOS) |
| ++ctrl+alt+up++ / ++ctrl+alt+down++ | Fokus auf den Bereich darüber / darunter setzen (++cmd+option+up++ / ++cmd+option+down++ auf macOS) |
| ++ctrl+shift+enter++ | Den fokussierten Bereich maximieren, sodass er den Tab ausfüllt, oder wieder alle Bereiche anzeigen (++cmd+shift+enter++ auf macOS) |

Die Teilen-Taste funktioniert in jedem Terminal-Tab, solange sich die Tastatur darin befindet. Unter Windows und Linux erreicht sie die Shell nicht mehr, die sie als ++ctrl+o++ erhielt; ++ctrl+o++ selbst erreicht sie weiterhin, sodass `nano` damit weiterhin speichert.

Die Fokustasten und die Maximieren-Taste wirken nur, solange ein Terminal-Tab mit zwei oder mehr Bereichen aktiv ist und sich die Tastatur in diesem Tab befindet; bei einem einzelnen Bereich erreichen sie wie bisher das Programm im Terminal, ++ctrl+shift+enter++ als ++enter++. Am Rand des Tabs bleibt der Fokus, wo er ist. Liegen auf dieser Seite mehrere Bereiche, geht der Fokus zu dem, der neben dem fokussierten Bereich liegt, statt nur dessen Ecke zu berühren, und unter diesen zum nächstgelegenen. Wird der Fokus gewechselt, während ein Bereich maximiert ist, werden wieder alle Bereiche angezeigt. *Ansicht → Bereiche* enthält dieselben Befehle, **Rechts teilen** und **Unten teilen**, um die Seite einer Teilung zu wählen, **Bereich schließen**, **Bereich maximieren** sowie **Nächster Bereich** und **Vorheriger Bereich**, die alle Bereiche des Tabs durchlaufen und nach dem letzten von vorn beginnen.

!!! note "Ein Tastaturkürzel des Desktops kann diese Tasten zuerst abfangen"
    Einige Linux-Desktops wie GNOME, Xfce und Cinnamon wechseln mit ++ctrl+alt++ und einer Pfeiltaste den Arbeitsbereich, und einige Windows-Grafiktreiber (Intel) drehen damit den Bildschirm, bevor korTTY die Taste sieht. Verwenden Sie dort *Ansicht → Bereiche*, oder schalten Sie das Tastaturkürzel des Desktops aus.

## Befehlspalette

Diese Tasten funktionieren, solange die [Befehlspalette](../features/command-palette.md) geöffnet ist. Jede andere Taste bleibt in der Palette und erreicht nie das Terminal dahinter.

| Tastaturkürzel | Aktion |
| --- | --- |
| ++up++ / ++down++ | Zeile wählen |
| ++enter++ | Palette schließen und die gewählte Zeile ausführen, oder die erste Zeile, wenn keine gewählt ist |
| ++alt+enter++ | Das gewählte Snippet im Snippet-Manager öffnen, statt es auszuführen (++option+enter++ unter macOS); in jeder anderen Zeile wie ++enter++ |
| ++tab++ / ++shift+tab++ | Zwischen Suchfeld und Liste wechseln |
| ++esc++ oder ++ctrl+shift+p++ | Palette schließen (++cmd+shift+p++ unter macOS) |

## SFTP-Manager

Diese Tasten funktionieren in der lokalen und der entfernten Liste eines SFTP-Manager-Tabs; siehe [Tasten](../features/sftp.md#tasten).

| Verknüpfung | Aktion |
| --- | --- |
| ++f2++ | Ausgewählten Eintrag umbenennen |
| ++delete++ oder ++ctrl+backspace++ | Auswahl nach Bestätigung löschen |

## Snippet-Manager

Diese Tastenkombinationen funktionieren im Snippet-Manager (Bibliotheks- und Bearbeitungs-Tab); siehe [Das Öffnen des Snippet-Managers](../features/snippets.md#offnen-des-snippet-managers).

| Verknüpfung | Aktion |
| --- | --- |
| ++ctrl+shift+s++ | Öffnen Sie den Snippet-Manager oder fokussieren Sie sein Suchfeld, wenn er bereits geöffnet ist |
| ++up++ / ++down++ (Liste) | Durchsuchen: zeigen Sie das ausgewählte Snippet im schreibgeschützten Vorschau-Tab an |
| ++enter++ (Liste) | Öffnen Sie das ausgewählte Snippet in einem Editor-Tab |
| Tippen (Vorschau-Tab) | Öffnen Sie das Vorschau-Snippet in einem Editor-Tab; der getippte Charakter bleibt erhalten |
| ++ctrl+s++ | Speichern Sie den aktiven Editor-Tab (auch in einem eigenständigen Snippet-Editor-Fenster) |
| ++ctrl+w++ | Schließen Sie den aktiven Editor-Tab (fragt nach nicht gespeicherten Änderungen) |
| ++ctrl+b++ | Verbergen oder anzeigen Sie die Bibliothek, sodass die Editor-Tabs die volle Breite erhalten |
| ++ctrl+p++ | Schnell öffnen: ein Snippet anhand eines Teils seines Namens oder eines Tags finden und in einem Editor-Tab öffnen (++up++ / ++down++ wählen, ++enter++ öffnet, ++esc++ schließt) |
| ++esc++ (Suchfeld) | Die Suche löschen; ++esc++ schließt den Snippet-Manager nie |
| ++down++ (Suchfeld) | Zum Snippet-Listenfenster wechseln (wählt das erste Snippet, wenn keines ausgewählt ist) |

Wenn der Fokus auf dem eigenen Tab-Header des Snippet-Managers im Hauptfenster (Tab-Modus) liegt, ++ctrl+s++ speichert das Projekt weiterhin, und ++ctrl+w++ schließt den Snippet-Manager-Tab nach einer Nachfrage zu nicht gespeicherten Änderungen.

## Snippet-Editor

Diese Tastenkombinationen funktionieren im Code-Feld des Snippet-Editors; siehe [KI-Code-Vervollständigungen](../features/snippets.md#ai-codevervollstandigungen).

| Verknüpfung | Aktion |
| --- | --- |
| ++shift+tab++ (Cursor am Ende einer Zeile mit Text) | Öffnen Sie die Vervollständigungsliste (die Kombination ist konfigurierbar, siehe [Einstellungen des Snippet-Editors](settings/snippet-editor/index.md)) |
| ++ctrl+space++ | Öffnen Sie die Vervollständigungsliste an einer beliebigen Stelle (unter macOS die physische). ++ctrl++ Schlüssel, nicht ++cmd++; ++cmd+i++ Und ++alt+esc++ Funktionieren dort auch, da macOS oft reserviert ++ctrl+space++ zum Umschalten von Eingangsquellen) |
| ++up++ / ++down++ (Liste offen) | Verschieben Sie die Auswahl. Durch die Eingabe wird die Liste gefiltert |
| ++tab++ oder ++enter++ (Liste offen) | Den ausgewählten Eintrag einfügen |
| ++esc++ | Schließen Sie die Liste oder verwerfen Sie Geistertext |
| ++esc++ (eine KI-Anfrage wird ausgeführt) | Die laufende KI-Anfrage dieses Editors beenden — eine KI-Code-Aktion, die Vollständige Code-Analyse oder deren Anwenden, ein Diagramm; funktioniert im gesamten Editor und in seinem Analysepanel, schließt den Editor nie (siehe [Beenden und erneut starten von KI-Anfragen](../features/snippets.md#stoppen-und-erneutes-ausfuhren-von-ki-anfragen)) |
| ++enter++ (Neue Analyse-Bereich des Analysepanels) | Starte die Vollständige Code-Analyse mit dem gewählten KI-Profil (siehe [Wahl des KI-Profil vor der Analyse](../features/snippets.md#das-ki-profil-vor-der-analyse-auswahlen)) |
| ++tab++ (Geistertext sichtbar) | Akzeptieren Sie den Geistertext |
| ++alt+bracket-right++ / ++alt+bracket-left++ (Geistertext sichtbar) | Nächster/vorheriger Ghosttext-Kandidat (physisch `]` Und `[` Tasten eines US-Layouts) |
| ++tab++ (in einer eingefügten Redewendungsvorlage) | Zum nächsten Platzhalter springen |
| ++shift+tab++ (an jedem anderen Ort oder wenn eine andere Kombination konfiguriert ist) | Die Zeile wie zuvor einrücken |
| ++ctrl+enter++ (Änderungserfassung angezeigt ändern) | **Übernehmen & anwenden** die überprüfte KI-Änderung (siehe [Die Überprüfung einer KI-Änderung](../features/snippets.md#uberprufung-einer-ki-anderung)); ++esc++ erfüllt dort nichts |
| ++ctrl+plus++ / ++ctrl+minus++ (gezeigte Änderungsüberprüfung) | Zoomen Sie die Schriftgröße des Code-Reviews |

## Diagramm-Zoomfenster

Diese Tastenkombinationen funktionieren im Zoom-Fenster des Diagramms für die Vollständige Code-Analyse; siehe [Vollständige Code-Analyse](../features/snippets.md#vollstandige-code-analyse).

| Verknüpfung | Aktion |
| --- | --- |
| ++ctrl+plus++ / ++ctrl+minus++ | Zoomen ein / aus |
| ++ctrl+0++ | Diagramm in das Fenster einpassen |
| ++ctrl+1++ | Diagramm bei 100 % anzeigen |
| ++ctrl++ + Mausrad | Zoom (++cmd++ + Mausrad auf macOS) |

## Terminal-KI-Agent

| Verknüpfung | Aktion |
| --- | --- |
| `agent` + ++tab++ (an der Shell-Eingabeaufforderung) | Agentenbefehlsvarianten anzeigen (`agent`, `agent-ask`, `agent-plan`) |
| `agent ` + ++tab++ (an der Shell-Eingabeaufforderung) | Aktuellen Verlauf der Agent-Eingabeaufforderungen anzeigen (neueste zuerst) |
| ++esc++ oder ++ctrl+c++ (während eines Laufs) | Brechen Sie die Registerkarte der ausgewählten Agentenausführung ab |
| ++ctrl+r++ (während eines Laufs) | Schalten Sie die Denkdetails für den ausgewählten Lauf um |
| ⏸ (Aktivitätsfeld) | Unterbrechen Sie den ausgewählten Lauf an einem sicheren Kontrollpunkt |
| ▶️ (Aktivitätsfeld) | Einen angehaltenen Lauf fortsetzen |

!!! note
    Die vollständige, immer aktuelle Shortcut-Liste wird aus den Beschleunigerdefinitionen der Anwendung generiert. Wenn eine Verknüpfung hier von der in der App angezeigten abweicht, ist die App maßgeblich.
