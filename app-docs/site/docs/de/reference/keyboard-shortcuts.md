# Tastaturkürzel

Verwenden Sie unter macOS ++cmd++, wo ++ctrl++ angezeigt wird.

## Allgemein

| Verknüpfung | Aktion |
| --- | --- |
| ++ctrl+t++ | Neuer Tab (Schnellverbindung) |
| ++ctrl+w++ | Tab schließen |
| ++ctrl+shift+n++ | Neues Fenster |
| ++ctrl+shift+w++ | Fenster schließen |
| ++ctrl+tab++ | Nächste Registerkarte |
| ++ctrl+shift+tab++ | Vorheriger Tab |
| ++ctrl+o++ | Projekt öffnen |
| ++ctrl+s++ | Projekt speichern |
| ++ctrl+shift+b++ | Backup erstellen |
| ++ctrl+q++ | Aufhören |
| ++ctrl+x++ | Ausschneiden (deaktiviert für Anschlusslaschen) |
| ++ctrl+c++ | Kopie |
| ++ctrl+v++ | Einfügen |
| ++ctrl+f++ | Suchen im aktiven Tab (in einem fokussierten Terminal auf Windows und Linux geht die Taste zur Shell, siehe [Terminal](#terminal)) |
| ++ctrl+k++ | Schnellverbindung |
| ++ctrl+m++ | Verbindungen verwalten |
| ++ctrl+shift+u++ | SFTP-Client |
| ++ctrl+shift+p++ | Anmeldeinformationen verwalten |
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

Die Zoom-Tasten (++ctrl++ oder ++alt++ mit ++plus++ / ++minus++ / ++0++; ++cmd++ auf macOS) vergrößern das Terminal nur, wenn ein Terminal-Tab ausgewählt ist. In einem Snippet-Editor oder Dateieditor-Tab erreichen sie den Editor, und ++alt-graph+plus++ tippt dort sein Zeichen.

## Terminal

Diese Tasten gelten, solange ein Terminalbereich den Tastaturfokus hat.

| Verknüpfung | Aktion |
| --- | --- |
| ++ctrl+shift+c++ / ++ctrl+shift+v++ | Terminalauswahl kopieren / in das Terminal einfügen (Windows und Linux; ++cmd+c++ / ++cmd+v++ auf macOS) |
| ++ctrl+l++ (Windows und Linux) | An die Shell gesendet: zeichnet den Bildschirm neu oder löscht den Bildschirm in bash, psql oder einer REPL, und der Scrollback bleibt erhalten |
| ++ctrl+f++ (Windows und Linux) | An die Shell gesendet: bewegt den Cursor um ein Zeichen vorwärts bei einem Bash-Prompt und scrollt vorwärts in `less` oder `vim` |
| ++cmd+k++ (macOS) | Puffer löschen: den Terminalbildschirm und seinen Scrollback leeren |
| ++cmd+f++ (macOS) | Suchen im Terminal-Scrollback |

Auf Windows und Linux haben **Puffer löschen** und **Suchen** keine eigenen Tasten, daher bleiben ++ctrl+l++ und ++ctrl+f++ bei den Programmen, die im Terminal laufen. Verwenden Sie Rechtsklick → **Puffer löschen** oder **Suchen** im Terminal, oder **Bearbeiten → Suchen…** mit der Maus. ++ctrl+shift+f++ bleibt **Nur korTTY Applikationsfenster**, und ++ctrl+shift+k++ dockt den Dateibrowser weiterhin links an.

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
