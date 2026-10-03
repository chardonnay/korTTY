---
title: Terminalsitzungen
---

# Terminalsitzungen

KorTTY bietet eine Terminalschnittstelle mit Registerkarten, die mehrere gleichzeitige SSH-Verbindungen, geteilte Bildschirmlayouts und interaktive Terminalverwaltungsfunktionen unterstützt. In dieser Anleitung werden Registerkartenvorgänge, die Unterstützung mehrerer Fenster, die Anpassung des Terminals und erweiterte Sitzungsfunktionen behandelt.

## Sitzungslebenszyklus

Das folgende Diagramm zeigt die Registerkarte „Terminal“ und den Sitzungslebenszyklus, einschließlich Split-Screen- und Broadcast-Modi.

![Terminal session lifecycle](../assets/diagrams/session-lifecycle.svg)

## Arbeiten mit Tabs

Verwalten Sie mehrere SSH-Sitzungen mit diesen Registerkartenoperationen:

| Aktion | Verknüpfung |
|--------|----------|
| **New Tab** | ++ctrl+t++ (Cmd+T on macOS) — opens Schnellverbindung to start a new session |
| **Tab umbenennen** | Klicken Sie mit der rechten Maustaste auf einen Tab und wählen Sie **Tab umbenennen…**, oder verwenden Sie *Datei → Tab umbenennen…* für den aktiven Tab. Der eingegebene Name ersetzt im Tab-Titel den Verbindungsnamen oder den [Titel, den die Shell gesetzt hat](#titel-aus-der-shell); das Coding-Agent-Symbol, das Präfix `[group]` und das Suffix `(DISCONNECT)` bleiben darum herum erhalten. Lassen Sie den Namen leer oder bestätigen Sie den Namen, den der Tab von sich aus zeigt, und der Tab folgt wieder dem Titel der Shell und dem Verbindungsnamen, einschließlich späterer Änderungen daran. Solange die Shell den Tab benennt, hält die Eingabe des Verbindungsnamens diesen Namen fest. Ein Name kann bis zu 120 Zeichen lang sein; Zeilenumbrüche werden zu Leerzeichen, und Steuerzeichen sowie die unsichtbaren Bidi-Steuerzeichen, die die Lesereihenfolge von Text verändern, werden entfernt. Der Name wird mit einem [Projekt](projects.md#was-gespeichert-wird) gespeichert, und das Panel [Coding-Agents](coding-agents.md) sowie [`tab.list`](../reference/control-api.md#was-die-methoden-tun) der Steuerungs-API zeigen ihn an; **Duplizieren** öffnet die Kopie unter dem Verbindungsnamen. Es gibt keine Tastenkombination, sodass jede Taste beim Programm im Terminal bleibt. |
| **Titel aus der Shell** | Ein Terminal-Tab zeigt statt des Verbindungsnamens den Titel, den seine Shell setzt, etwa `user@host: directory`, sofern Sie den Tab nicht umbenannt haben; wenn Sie auf ihn zeigen, sehen Sie die Verbindung, zu der er gehört. Siehe [Titel aus der Shell](#titel-aus-der-shell) weiter unten; die Funktion lässt sich in den [Fenster-Einstellungen](../reference/settings/window.md#tabs) ausschalten. |
| **Tab schließen** | ++ctrl+w++ (Cmd+W auf macOS) — schließt den aktiven Tab. Sie werden nur gefragt, wenn etwas verloren gehen könnte: der Tab hat geteilte Panes oder ein Befehl läuft noch (eine lokale Shell mit einem laufenden Kindprozess, oder eine SSH-Sitzung, die nicht am Prompt steht). Ein einzelnes Terminal im Leerlauf schließt sofort. Der Schließen-Button des Tabs und **Schließen** im Dashboard stellen dieselbe Frage. Die Verbindungseinstellung *Tab ohne Nachfrage schließen* unterdrückt die Frage ganz. |
| **Andere Tabs schließen / Tabs rechts davon schließen** | Klicken Sie mit der rechten Maustaste auf einen Terminal-Tab und wählen Sie **Andere Tabs schließen** oder **Tabs rechts davon schließen**, oder verwenden Sie die gleichnamigen Einträge im Menü *Datei*, die vom aktiven Tab beliebiger Art ausgehen. **Andere Tabs schließen** schließt alle anderen Tabs des Fensters, **Tabs rechts davon schließen** die Tabs dahinter; der gewählte Tab bleibt geöffnet und wird zum aktiven Tab. Ein Eintrag ist ausgegraut, solange es nichts zu schließen gibt. Statt einer Frage pro Tab werden Sie höchstens einmal gefragt, *N Tabs schließen?*, mit der Anzahl der Tabs, die geteilte Bereiche oder einen noch laufenden Befehl haben. Wenn nur Terminals im Leerlauf und andere Tabs geschlossen werden, wird nichts gefragt, genau wie bei deren Schließen-Schaltflächen, und die Einstellung *Aktive Terminal-Fenster ohne Nachfrage schließen* auf dem Tab [Terminal-Einstellungen](../reference/settings/terminal.md) unterdrückt die Frage wie bei **Alle Tabs schließen**. Ein Datei- oder Snippet-Editor mit nicht gespeicherten Änderungen fragt weiterhin selbst nach (Speichern / Verwerfen / Abbrechen), und **Abbrechen** bei einer beliebigen Frage lässt alle Tabs geöffnet. Eine laufende KI-Chat-Anfrage oder ein laufender Schwarm in einem Tab, der geschlossen wird, stoppt wie bei seiner Schließen-Schaltfläche. Es gibt keine Tastenkombination. |
| **Geschlossenen Tab wieder öffnen** | ++ctrl+alt+shift+t++ (Cmd+Option+Shift+T auf macOS), *Datei → Geschlossenen Tab wieder öffnen* oder **Geschlossenen Tab wieder öffnen** im Rechtsklickmenü eines Terminal-Tabs holt den zuletzt geschlossenen Terminal-Tab mit einer neuen Sitzung zur selben Verbindung zurück: seine Tab-Gruppe, der Name, den Sie ihm gegeben haben, und sein Terminal-Effekt kommen zurück, seine Ausgabe und seine geteilten Bereiche nicht. *Datei → Zuletzt geschlossen* listet auf, was Sie in dieser Sitzung geschlossen haben, das Neueste zuerst; wählen Sie einen Eintrag, um ihn wieder zu öffnen, oder **Liste leeren**, um die Liste zu leeren. Jeder Schließvorgang ist ein Eintrag: ein einzelner Tab (seine Schließen-Schaltfläche, **Tab schließen** oder **Schließen** im Dashboard); alle Terminal-Tabs, die ein **Andere Tabs schließen**, **Tabs rechts davon schließen** oder **Alle Tabs schließen** geschlossen hat und die gemeinsam wieder geöffnet werden; oder die Terminal-Tabs eines Fensters, das Sie geschlossen haben, während korTTY weiterlief, angezeigt als *Fenster: …*, die in einem neuen Fenster wieder geöffnet werden oder im aktuellen, wenn dieses keine Tabs hat. Auf macOS, wo korTTY nach dem Schließen seines letzten Fensters weiterläuft, öffnen *Datei → Geschlossenen Tab wieder öffnen* und *Datei → Zuletzt geschlossen* in der Menüleiste ein neues Fenster für das, was sie wieder öffnen. Tabs, deren Sitzung von selbst endete (`exit`, ++ctrl+d++), die Tabs, die das Öffnen eines [Projekts](projects.md) ersetzt, und alles, was beim Beenden von korTTY geschlossen wird, erscheinen nicht in der Liste. Die Liste enthält die letzten 25 Schließvorgänge aller Fenster zusammen und wird nur im Arbeitsspeicher gehalten, daher ist sie weg, sobald korTTY beendet wird. Ein wieder geöffneter Tab meldet sich an wie **Verbinden** im Connection-Manager (siehe [Anmelden](connections.md#anmelden)) und verwendet die gespeicherte Verbindung mit den Änderungen, die Sie seitdem vorgenommen haben, sodass die [Serverzugriffsrichtlinie](../reference/enterprise-policy.md#server-zugriffskontrolle) erneut geprüft wird; eine Anmeldemethode, die Sie in der Schnellverbindung nur für diese eine Sitzung gewählt haben, wird nicht wiederholt. Eine Schnellverbindungs-Sitzung, die nie gespeichert wurde, wird mit den Einstellungen wieder geöffnet, die sie hatte. Die Liste behält nie einen temporären SSH-Schlüssel, daher fragt eine Sitzung, die einen verwendet hat, nach einem neuen Schlüssel. Wenn Sie eine Passwort-, Schlüssel- oder Tresorabfrage abbrechen, bleibt der Eintrag in der Liste. Die Tastenkombination funktioniert in jedem Tab, auch wenn ein Terminal den Fokus hat und die Menüleiste ausgeblendet ist; gibt es nichts wieder zu öffnen, meldet das die Statusleiste. |
| **Nächster Tab** | ++ctrl+Tab++ |
| **Vorheriger Tab** | ++ctrl+shift+Tab++ |
| **Zu einem Tab springen** | ++ctrl+1++ bis ++ctrl+8++ (++cmd++ auf macOS) wählen den ersten bis achten Tab des Fensters und ++ctrl+9++ den letzten, mit den Ziffern der oberen Reihe oder des Nummernblocks. Die Tasten funktionieren in jedem Tab, auch wenn das Terminal den Fokus hat, und eine Zahl, an deren Position kein Tab steht, bewirkt nichts. Es wird keine Ziffer in das Terminal eingegeben, auch nicht im [Broadcast-Modus](#broadcast-modus) in die anderen Bereiche. Unter Windows und Linux springt nur ++ctrl++ mit einer Ziffer, sodass ++alt-graph++-Zeichen und ++ctrl+shift+6++ weiterhin das Terminal erreichen; Details zu Tastaturbelegungen finden Sie unter [Tastaturkürzel](../reference/keyboard-shortcuts.md#allgemein). |
| **Erneut verbinden** | Klicken Sie mit der rechten Maustaste auf eine Registerkarte, den Terminalbereich oder einen Servereintrag im Dashboard. Ist die Verbindung aktiv, wird sie sofort geschlossen und wieder aufgebaut; Wenn die Verbindung getrennt wird, wird sie wiederhergestellt. Das Terminalfenster bleibt geöffnet. |
| **Tab-Gruppen** | Rechtsklicken Sie auf einen Tab, um ihn einer benannten Gruppe zuzuweisen und so die Organisation zu verbessern; **Keine Gruppe** entfernt ihn wieder aus seiner Gruppe. Dasselbe Menü bietet **Duplizieren**, das einen weiteren Tab zur selben Verbindung öffnet und sich wie der Connection-Manager anmeldet (siehe [Anmelden](connections.md#anmelden)). |
| **Tab-Farbe** | Eine Verbindung mit einer [Tab-Farbe](connections.md#tab-farbe) markiert jeden ihrer Terminal-Tabs mit einem farbigen Punkt vor dem Titel und einem 3 Pixel breiten Rahmen in dieser Farbe um das Terminal, zum Beispiel Rot für Produktionsserver. Der Rahmen umschließt alle geteilten Bereiche des Tabs und lässt sich in den [Fenster-Einstellungen](../reference/settings/window.md#tabs) ausschalten; das Ein- oder Ausschalten ändert die Größe des Terminals um 3 Pixel auf jeder Seite. Wenn Sie auf einen solchen Tab zeigen, erscheint ein Tooltip mit der Verbindung und dem Namen der Farbe, den Screenreader auch für den Punkt vorlesen. Legen Sie die Farbe im Verbindungseditor auf dem Tab *Terminal-Einstellungen* fest; Speichern im Connection-Manager aktualisiert die offenen Tabs in allen Fenstern. Eine Verbindung ohne eigene Farbe kann stattdessen die Farbe der [Umgebung](security.md#umgebungen-und-tab-farben) ihrer hinterlegten Anmeldeinformationen zeigen. Der gelbe Tab einer Verbindung im Aufbau und der dunkelrote Tab einer fehlgeschlagenen oder verlorenen Verbindung bleiben unverändert. |

### Titel aus der Shell

Viele Shells und Programme setzen mit der Escape-Sequenz OSC 0 oder OSC 2 einen Terminaltitel: Die Standard-`bash` von Debian und Ubuntu schreibt dort `user@host: directory` hinein, und Editoren oder Coding-Agents setzen die Datei oder die Aufgabe. Ein Terminal-Tab zeigt diesen Titel statt des Verbindungsnamens, wie üblich umgeben vom Coding-Agent-Symbol, dem Präfix `[group]` und dem Suffix `(DISCONNECT)`. Ein Tab mit geteilten Bereichen zeigt den Titel des Bereichs, der den Fokus hat, und wechselt, wenn Sie in einen anderen Bereich wechseln.

- Ein Name, den Sie dem Tab mit **Tab umbenennen** gegeben haben, hat immer Vorrang. Löschen Sie diesen Namen wieder, folgt der Tab erneut dem Titel der Shell.
- Wenn Sie auf einen Tab zeigen, den seine Shell benannt hat, erscheint ein Tooltip mit der Verbindung, zu der er gehört: `user@host` und der Name der Verbindung.
- Diesen Titel bestimmt der Server, daher entfernt korTTY Steuerzeichen und die unsichtbaren Bidi-Steuerzeichen, kürzt ihn auf 80 Zeichen und lässt ihn nie die Tab-Farbe, den farbigen Punkt oder den Rahmen ändern. Betrachten Sie den Titel als Hinweis; Tooltip und Tab-Farbe zeigen Ihnen, um welche Verbindung es sich handelt.
- Der Titel wird nicht mit einem [Projekt](projects.md) gespeichert und von **Geschlossenen Tab wieder öffnen** nicht übernommen. Ein erneutes Verbinden verwirft ihn, bis die neue Sitzung einen setzt, und ein Programm, das beim Beenden den vorgefundenen Titel wiederherstellt, wie es `vim` tut, bringt den Verbindungsnamen zurück, wenn vorher keiner gesetzt war.
- Das Panel [Coding-Agents](coding-agents.md) und [`tab.list`](../reference/control-api.md#was-die-methoden-tun) der Steuerungs-API melden den Titel, den der Tab anzeigt.
- Unter Windows kann die Konsole hinter einer lokalen Shell einen eigenen Titel setzen, etwa den Pfad von `cmd.exe`, wie sie es auch im Windows Terminal tut; schalten Sie die unten genannte Einstellung aus, um den Verbindungsnamen beizubehalten.

Um auf jedem Tab die Verbindungsnamen zu behalten, schalten Sie **Terminal-Tabs nach dem Titel benennen, den die Shell setzt** in den [Fenster-Einstellungen](../reference/settings/window.md#tabs) aus; die offenen Tabs aller Fenster folgen, sobald Sie speichern.

## Sicher verbinden

Interaktive SSH-Terminals teilen das Host-Schlüssel-Vertrauen mit SFTP und dem von Mosh verwendeten SSH-Bootstrap. Die erste Verbindung zu einem normalisierten Host und Port zeigt den Schlüsselalgorithmus sowie den OpenSSH-SHA-256-Fingerabdruck an, wobei **Nein** standardmäßig ausgewählt ist. Sobald Sie ihn verifizieren und akzeptieren, verbinden sich exakte Übereinstimmungen stillschweigend; ein geänderter Schlüssel wird hart blockiert, ohne automatische Wiederholung, und die Warnung bietet **Prüfen und ersetzen…** für einen Schlüssel, den Sie mit dem Server-Administrator verifiziert haben. Siehe [SSH-Hostschlüssel-Verifizierung](connections.md#ssh-hostschlusseluberprufung).

Beim Öffnen einer Verbindung mit demselben Server oder einer neu ausgewählten Verbindung in einem Split wird ein Fortschrittsdialog angezeigt, während der SSH-Handshake auf einem Worker ausgeführt wird. Die Schnittstelle reagiert weiterhin sowohl auf die Host-Tasten-Bestätigung als auch auf Eingabeaufforderungen zur interaktiven Tastaturauthentifizierung.

**Rechts teilen (neue Verbindung)** und **Unten teilen (neue Verbindung)** folgen wie die Schnellverbindung der [Serverzugriffsrichtlinie](../reference/enterprise-policy.md#server-zugriffskontrolle) Ihrer Organisation: wenn der von Ihnen gewählte Server oder dessen Jump-Server blockiert ist, zeigt korTTY die Richtliniennachricht sofort an, bevor es nach einem Passwort fragt oder die Verbindung speichert, und öffnet kein Pane. **Rechts teilen (gleicher Server)** und **Unten teilen (gleicher Server)** prüfen die Richtlinie erneut: ein Tab, der aus einer gespeicherten Verbindung geöffnet wurde, kann spätere Änderungen übernehmen; wenn dessen Server oder Jump-Server nach dem Öffnen des Tabs zu einem blockierten geändert wurde, zeigt die Aufteilung dieselbe Nachricht an und öffnet kein Pane.

Einige Fehler werden sofort abgelehnt, anstatt erneut versucht zu werden, weil ein erneuter Versuch das Ergebnis nicht ändern kann – ein geänderter Host-Schlüssel, eine fehlende oder nicht lesbare SSH-Schlüsseldatei, ein Jump-Server dessen gespeichertes Passwort nicht verwendet werden kann (z. B. wenn das Tresor-Verzeichnis gesperrt ist) oder dessen Einrichtung unvollständig ist, eine Mosh-Verbindung mit einem Jump-Server konfiguriert ist oder ein fehlender Mosh-Runtime vorliegt. Das Terminal löscht den Inhalt und zeigt sofort die Ursache an, statt durch die Wiederholungsanzahl zu arbeiten. Siehe [Sprungserver](jump-server.md#wenn-der-jump-server-nicht-verwendet-werden-kann) für die Jump-Server-Fälle und die Mosh-Einschränkung.

Der angeheftete SithTermFX-Build von KorTTY enthält auch eine überprüfte Korrektur der Begrenzung der unteren Zeile: Beim Bewegen über einen Hyperlink oder die letzte sichtbare Terminalzeile wird `TerminalTextBuffer` nicht mehr nach der nicht vorhandenen Zeile bei `line == height` gefragt.

## Verbindungsverlust und automatische Wiederherstellung der Verbindung

Wenn eine **bestehende** SSH-Verbindung verloren geht – Netzwerkabbruch, VPN-Abschaltung, Server entfernt – schließt sich der Tab nicht. Er wechselt stattdessen in einen roten Zustand für abgebrochene Verbindungen: Der Tab-Titel erhält einen `(DISCONNECT)`-Präfix, der Tab färbt sich dunkelrot, eine rote Statusleiste zeigt die Uhrzeit an, zu der die Verbindung verloren ging, und der Terminalcursor blendet sich aus, sodass eine tote Sitzung nicht mehr als lebendig erscheint. Nur ein normales Remote-Abmelden (Tipp von `exit` oder ++ctrl+d++ am Prompt) schließt den Tab.

KorTTY bemerkt einen stillen Transporttod innerhalb von etwa zehn Sekunden: Alle paar Sekunden sendet es eine SSH-Liveness-Prüfung (eine globale Anfrage, die der Server beantworten muss, dieselbe Technik wie `ServerAliveInterval` von OpenSSH) und behandelt zwei aufeinanderfolgende unbeantwortete Prüfungen als verlorene Verbindung. Das Probe aktiviert sich erst, nachdem der Server einmal geantwortet hat, sodass Server, die nie auf solche Anfragen antworten, ihre Sitzungen unberührt lassen. Dies ist unabhängig vom Keep-Alive-Heartbeat [SSH ](#ssh-keep-alive), der inaktive Verbindungen offen hält, eine unterbrochene Verbindung jedoch nicht erkennt.

Um die Sitzung wieder in derselben Registerkarte aufzunehmen, doppelklicken Sie auf die rote Statusleiste oder die rote Registerkarte oder verwenden Sie **Erneut verbinden** im Registerkarten-, Terminal- oder Dashboard-Kontextmenü. In einer geteilten Registerkarte werden die Bereiche, deren Verbindung unterbrochen wurde, einzeln geschlossen. Im letzten verbleibenden Bereich bleibt die Registerkarte geöffnet und es wird das Angebot zur erneuten Verbindung angezeigt.

Wenn **Verlorene Verbindungen automatisch wiederherstellen** aktiviert ist (**Einstellungen → Terminal**, standardmäßig aktiviert), stellt die Registerkarte die Verbindung von selbst wieder her: Versuche beginnen nach 3 Sekunden und werden nach 5, 10, 20 und 30 Sekunden unterbrochen, bis zu einem Versuch pro Minute, und die rote Statusleiste zählt bis zum nächsten Versuch herunter. Eine erfolgreiche erneute Verbindung, eine manuelle erneute Verbindung oder das Schließen der Registerkarte beendet die automatischen Versuche. Permanente Fehler – Authentifizierung, Host-Schlüssel-Überprüfung, Konfigurationsverweigerungen – stoppen sie ebenfalls, sodass niemals ein falsches Passwort gegen den Server gehämmert wird. Während a [session journal](session-journal.md) is running, its red decision bar takes precedence and no automatic attempt starts — the journal asks whether to reconnect and continue or to end with its closing summary. See [Settings → Terminal](../reference/settings/terminal.md) for the setting.

## Multi-Window-Unterstützung

Öffnen Sie zusätzliche Fenster, um Verbindungen nach Projekt oder Umgebung zu organisieren:

- **Neues Fenster**: ++ctrl+shift+n++ (Cmd+Shift+N auf macOS) öffnet ein neues korTTY-Fenster. Jedes Fenster kann seine eigenen Tabs und Verbindungen haben.
- **Registerkarten zwischen Fenstern verschieben**: Ziehen Sie eine Registerkarte aus der Registerkartenleiste und legen Sie sie auf der Registerkartenleiste eines anderen KorTTY-Fensters ab, um diese Registerkarte (und ihre Sitzung, einschließlich aller geteilten Terminals) in das andere Fenster zu verschieben.
- **Tabs neu anordnen**: Ziehen Sie einen Tab innerhalb desselben Fensters, um seine Reihenfolge zu ändern; die Registerkarte „+“ bleibt am Ende.

## Terminal-Kontextmenü

Klicken Sie mit der rechten Maustaste in ein Terminal, um dessen Kontextmenü zu öffnen; in einem geteilten Tab wirkt es auf den angeklickten Bereich. Das Menü beginnt mit den unten aufgeführten Bearbeitungsbefehlen; wenn Sie mit der rechten Maustaste auf einen Link klicken, stehen **Link öffnen** und **Linkadresse kopieren** davor, bei einem Link auf eine Datei **Datei im Snippet-Editor öffnen** und **Pfad kopieren** (siehe [Links in der Terminalausgabe](#links-in-der-terminalausgabe)):

| Eintrag | Was es tut |
|-------|--------------|
| **Kopieren** | Kopiert den ausgewählten Text in die Zwischenablage und behält die Auswahl bei. Ausgegraut, wenn nichts ausgewählt ist. |
| **Einfügen** | Sendet den Text aus der Zwischenablage an die Sitzung, genau wie das Einfügen-Tastenkürzel (siehe [Text einfügen](#text-einfugen)). |
| **Puffer löschen** | Löscht den Scrollback und den Bildschirm, behält jedoch die Prompt-Zeile bei. Während ein Vollbildprogramm wie `vim` oder `less` läuft, tut es nichts. |
| **Suchen** | Öffnet die Suchleiste oben rechts im Bereich, genauso wie **Bearbeiten → Suchen...** (++ctrl+f++, ++cmd+f++ auf macOS). Tippen Sie zum Hervorheben von Übereinstimmungen, drücken ++enter++ oder ++down++ für die nächste Übereinstimmung und ++up++ für die vorherige, sowie ++esc++ zum Schließen der Leiste. |

Darunter folgen die Einträge anderer Funktionen, in dieser Reihenfolge und einige nur dort, wo sie zutreffen: **Menüleiste anzeigen** (während die Menüleiste verborgen ist), **Im Snippet-Editor öffnen**, das **KI-Untermenü**, die Session-Journal-Screenshot- und Notizeinträge, **Thema**, **Hervorhebung** (siehe [Hervorhebung von Schlüsselwörtern](highlighting.md)), **Terminal-Effekt**, **Neu verbinden** und **Befehls-Zeitstempel anzeigen**. Das **Extras**-Untermenü am Ende enthält **Terminal teilen**, **Schriftgröße** (siehe [Schriftgröße und Zoom](#schriftgroe-und-zoom)) und **Broadcast-Modus**.

## Text einfügen

Jeder Weg, Text in ein Terminal einzufügen, läuft über korTTY: *Bearbeiten → Einfügen*, ++cmd+v++ unter macOS, ++ctrl+shift+v++ unter Windows und Linux, **Einfügen** im Rechtsklick-Menü, ein Mittelklick, der unter Linux die X11-Primärauswahl und auf den anderen Systemen die Zwischenablage einfügt, sowie Text, den Sie [auf einem Bereich ablegen](#text-ablegen). Der Text geht an den Bereich, in den Sie einfügen, und seine Zeilenumbrüche kommen als Enter an, so wie beim Tippen.

Hat das Programm im Bereich Bracketed Paste eingeschaltet, wie es bash, zsh, fish und die meisten Editoren tun, während sie auf Eingaben warten, bettet korTTY den Text in die Bracketed-Paste-Marker ein. Das Programm erhält ihn dann als einen eingefügten Block statt als getippte Tasten, sodass ein Zeilenumbruch darin nicht von sich aus einen Befehl ausführt. Bracketed-Paste-Marker im Text selbst werden zuvor entfernt, auch die 8-Bit-Form, die eine Einzelbyte-[Zeichenkodierung](connections.md#zeichenkodierung) sendet, sodass Text, der von einer Webseite oder aus einer Datei kopiert wurde, den Paste weder vorzeitig beenden noch seine restlichen Zeilen als getippte Befehle ausführen lassen kann.

Nach einer Wiederverbindung und nach einem Terminal-Reset (dem Befehl `reset` oder `ESC c` in der Ausgabe) verwendet ein Bereich kein Bracketed Paste, bis sein Programm es wieder einschaltet; bis dahin wirken eingefügte Zeilenumbrüche wie Enter. Eine Mosh-Verbindung, die sich nach einer Netzwerkunterbrechung erholt, setzt dieselbe Sitzung fort und behält den Zustand.

### Einfügeschutz

Manche Einfügungen fragen nach, bevor sie den Bereich erreichen. korTTY zeigt dann, was Sie gleich einfügen, und sendet nichts, bis Sie **Einfügen** wählen. Welche Einfügungen nachfragen, legen Sie unter *Einstellungen → Terminal → Einfügeschutz* fest (siehe [Terminal-Einstellungen](../reference/settings/terminal.md)):

- **Zeilenumbrüche**: Standardmäßig fragt eingefügter Text mit Zeilenumbruch nach, außer das Programm im Bereich verwendet Bracketed Paste, denn sonst würde jeder Zeilenumbruch wie Enter wirken und einen Befehl ausführen. Auch eine einzelne Zeile, die mit einem Zeilenumbruch endet, fragt nach, da sie sofort ausgeführt würde. Mit **Immer** fragt jeder eingefügte Text mit Zeilenumbruch nach, ob mit Bracketed Paste oder ohne; mit **Aus** fragt keiner nach.
- **Steuerzeichen**: Eingefügter Text mit Steuerzeichen wie Escape, Strg+C oder Strg+Z oder mit unsichtbaren Zeichen, die die Schreibrichtung ändern, fragt immer nach, wenn die Warnung nicht auf **Aus** steht, auch wenn das Programm Bracketed Paste verwendet. Das Terminal auf dem Server reagiert auf Strg+C, Strg+Z und Strg+S, bevor das Programm den eingefügten Text sieht, und Zeichen, die die Schreibrichtung ändern, lassen den Text anders aussehen, als er gesendet wird. Gewöhnlicher Text enthält sie nie.
- **Größe**: Eingefügter Text über 5 KiB fragt unabhängig von der Warnungseinstellung nach, weil großer eingefügter Text ein langsames Programm oder Gerät überfluten kann und schwer zu prüfen ist. Setzen Sie die Größe auf 0, um diese Prüfung abzuschalten.

![Paste confirmation](../assets/screenshots/main/paste-confirmation.png)

Der Dialog benennt den Bereich nach der Verbindung, die darin läuft – bei einem mit **Rechts teilen (neue Verbindung)** oder **Unten teilen (neue Verbindung)** geöffneten Bereich kann das ein anderer Server sein als der des Tabs –, zeigt die Zahl der Zeilen und die Größe, nennt die Gründe für die Nachfrage und gibt an, ob das Programm im Bereich Bracketed Paste verwendet. Die Vorschau zeigt den Anfang des Textes, Steuerzeichen als Symbole (etwa ␛ für Escape) und unsichtbare Zeichen als `<U+XXXX>`. Außerdem weist der Dialog darauf hin, wenn der Text aus der Mittelklick-Auswahl stammt, wenn Bracketed-Paste-Marker darin entfernt werden und wenn der Broadcast-Modus eingeschaltet ist: Eingefügter Text geht immer nur an den Bereich, in den Sie einfügen. **Kopieren** im Rechtsklick-Menü der Vorschau folgt dem [internen Zwischenablagemodus](../reference/enterprise-policy.md#interner-zwischenablagemodus) der Unternehmensrichtlinie.

**Abbrechen** ist die Standardschaltfläche und hat den Fokus, sodass ++enter++, ++space++ und ++esc++ das Einfügen verwerfen und vorausgetippte Eingaben es nie bestätigen können. Klicken Sie auf **Einfügen**, um einzufügen, oder wechseln Sie mit ++tab++ dorthin und lösen Sie die Schaltfläche mit ++space++ aus. Ein zweites Einfügen in denselben Bereich, während der Dialog offen ist, wird ignoriert, und eingefügter Text, den Sie erst bestätigen, nachdem sich der Bereich neu verbunden hat, wird verworfen, statt die neue Sitzung zu erreichen.

!!! warning "Ob Bracketed Paste aktiv ist, bestimmt der Server"
    Ob das Programm Bracketed Paste verwendet, teilt das Programm auf dem Server dem Terminal mit, und jede Ausgabe kann das behaupten: Eine präparierte Datei, die Sie mit `cat` anzeigen, oder eine Anmeldemeldung kann es in einer Shell einschalten, die es nicht beherrscht, etwa `sh` oder die Konsole vieler Netzwerkgeräte. Eingefügte Zeilenumbrüche werden dann ohne Warnung als Befehle ausgeführt. Wenn Sie auf Produktionsservern arbeiten, wählen Sie **Immer**.

### Einfügen in langsame Geräte

Switches, Router, Konsolenserver und andere Geräte hinter SSH können Eingaben verlieren, die schneller ankommen, als sie sie lesen, sodass bei einer eingefügten Konfiguration Zeichen oder ganze Zeilen fehlen. Stellen Sie **Pause nach jeder eingefügten Zeile** unter *Einstellungen → Terminal → Einfügeschutz* ein (0 bis 1.000 ms; 0, der Standard, schaltet die Pause ab), dann sendet korTTY mehrzeiligen eingefügten Text Zeile für Zeile, mit dieser Pause nach jeder Zeile. Eine einzelne Zeile geht weiterhin sofort hinaus, und eingefügter Text, der eine Bestätigung verlangt, wird Zeile für Zeile gesendet, nachdem Sie **Einfügen** gewählt haben.

Während die Zeilen gesendet werden, zeigt die untere rechte Ecke des Bereichs, wie weit das Einfügen ist, etwa *Füge Zeile 3 von 40 ein · Esc bricht ab*. Der Bereich nimmt währenddessen keine Tastatureingaben an, sodass nichts, was Sie tippen, zwischen zwei eingefügten Zeilen landet; unter macOS funktionieren ++cmd++-Tastenkürzel weiterhin, und die Suchleiste des Bereichs nimmt Ihre Eingaben weiterhin an. Drücken Sie ++esc++, um das Einfügen abzubrechen: Die restlichen Zeilen werden nicht gesendet. Verwendet das Programm im Bereich Bracketed Paste, kommen die zeilenweise gesendeten Zeilen trotzdem als ein eingefügter Block an, und der Abbruch beendet diesen Block, sodass die bereits gesendeten Zeilen in der Eingabezeile des Programms stehen bleiben, ohne ausgeführt zu werden.

Ein neues Einfügen in den Bereich wird ignoriert, bis das zeilenweise Einfügen fertig ist, und im [Broadcast-Modus](#broadcast-modus) werden die Tasten, die Sie in den anderen Bereichen tippen, nicht an ihn gesendet. Das Schließen des Bereichs, eine Wiederverbindung und das Schließen des Tabs brechen das Einfügen ab.

### Text ablegen

Ziehen Sie Text aus einer anderen Anwendung, etwa einem Browser oder Editor, oder aus einem anderen korTTY-Fenster, und legen Sie ihn auf einem Terminalbereich ab, um ihn dort einzufügen. Er landet im Bereich unter dem Mauszeiger, der nicht der Bereich sein muss, in dem Sie getippt haben, und dieser Bereich wird zum fokussierten Bereich. Abgelegter Text wird wie jeder andere eingefügte Text behandelt: Bracketed-Paste-Marker darin werden entfernt, der [Einfügeschutz](#einfugeschutz) fragt zuerst nach, wenn seine Regeln es verlangen, mit einem Hinweis im Dialog, dass der Text auf das Terminal gezogen wurde, und eine eingestellte Zeilenpause sendet ihn Zeile für Zeile. Der Text wird immer kopiert, sodass die Anwendung, aus der Sie ihn ziehen, ihn behält. Ein Bereich, der nicht verbunden ist oder noch zeilenweise einfügt, nimmt den abgelegten Text nicht an.

Abgelegte Dateien werden in SSH-Tabs dagegen per SFTP auf den Server kopiert. Ein Ziehvorgang, der Dateien zusammen mit ihrem Pfad als Text mitbringt, wie einer aus dem Finder oder Explorer, kopiert die Dateien und fügt den Pfad nie ein. Beides erfordert **Drag-and-Drop ins Terminal erlauben** unter *Einstellungen → Terminal* (standardmäßig eingeschaltet); ist die Einstellung aus, nimmt das Terminal weder Dateien noch Text an. Mit dem [internen Zwischenablagemodus](../reference/enterprise-policy.md#interner-zwischenablagemodus) der Unternehmensrichtlinie wird Text, der aus einer anderen Anwendung gezogen wird, abgelehnt, genau wie das Einfügen aus der Zwischenablage des Betriebssystems, während Text, der innerhalb von korTTY gezogen wird, weiterhin eingefügt wird.

## Links in der Terminalausgabe

Programme können mit der OSC-8-Escape-Sequenz anklickbare Links im Terminal ausgeben; GCC kann zum Beispiel eine Warnung mit ihrer Dokumentation verlinken. Um einen solchen Link zu öffnen, halten Sie ++cmd++ (macOS) bzw. ++ctrl++ (Windows und Linux) gedrückt und klicken ihn an: Er öffnet sich in Ihrem Standardbrowser, ein `mailto`-Link in Ihrem Mailprogramm. Nur `http`-, `https`-, `ftp`-, `ftps`- und `mailto`-Links öffnen sich im Browser oder Mailprogramm (`file:`-Links öffnen sich als Text im Snippet-Editor, siehe unten), und ein `mailto`-Link darf nur Empfänger (`to`, `cc`, `bcc`), `subject`, `body` und `in-reply-to` ausfüllen.

Derselbe Klick öffnet eine Web- oder E-Mail-Adresse, die ein Programm als Klartext ausgibt, etwa die Adresse in einer Logzeile, einer Compiler-Meldung oder einem `git push`-Hinweis: Eine Webadresse öffnet sich in Ihrem Standardbrowser, eine E-Mail-Adresse wie `ops@example.com` als neue Mail an diese Adresse. Ein Punkt, Komma, Anführungszeichen oder eine schließende Klammer direkt nach einer Adresse gehört nicht dazu, und eine Adresse, die in die nächsten Zeilen umbricht, wird von jeder ihrer Zeilen aus gefunden. korTTY sucht die Adresse erst, wenn Sie klicken, und zwar in dem Text, den das Terminal in diesem Moment anzeigt; so öffnet sich auch eine Adresse vollständig, die ein Programm in mehreren Teilen ausgegeben hat, und die Ausgabe wird nicht langsamer. korTTY liest höchstens 16 Zeilen über und unter dem Klick (in einem sehr breiten Terminal weniger) und lässt eine Adresse, die darüber hinausreicht, unberührt, statt sie abgeschnitten zu öffnen. Was die folgenden Absätze über das Anklicken eines Links und über Links sagen, die sich nie öffnen, gilt auch für diese Adressen. Um dies auszuschalten, deaktivieren Sie **Web-Adressen, E-Mail-Adressen und Dateipfade im Terminaltext erkennen** unter [Einstellungen → Terminal → Links](../reference/settings/terminal.md#hinweise); mit OSC 8 ausgezeichnete Links funktionieren weiterhin.

In SSH- und lokalen Shell-Tabs öffnet derselbe Klick einen Dateipfad, den ein Programm ausgibt, etwa `/var/log/syslog`, `~/notes.txt`, `./build.gradle.kts` oder das `src/main/App.java:42:7` einer Compiler-Meldung, als Text im [Snippet-Editor](snippets.md). Ein relativer Pfad braucht ein `/` und eine Dateiendung oder eine Zeilennummer, sodass Wörter wie `and/or` oder `24/7` Klartext bleiben, und Zeile und Spalte nach dem Pfad (`:42`, `:42:7`, `(42,7)`) gehören nicht zum Dateinamen. korTTY liest die Datei auf dem Rechner, auf dem die Sitzung läuft: über SFTP in einem SSH-Tab, von Ihrer Festplatte in einem lokalen Shell-Tab. Ein relativer Pfad wird zum Zeitpunkt des Klicks relativ zum aktuellen Verzeichnis der Shell aufgelöst (`~` relativ zu Ihrem Home-Verzeichnis), sodass derselbe Text nach einem `cd` eine andere Datei bezeichnen kann; der Snippet-Editor zeigt den vollständigen Pfad an, den er geöffnet hat. Es öffnet sich nur eine reguläre UTF-8-Textdatei mit höchstens 10 MB; bei einem Verzeichnis, einer fehlenden Datei, einer Binärdatei oder einer größeren Datei nennt korTTY stattdessen den Grund. Netzwerkpfade wie `\\server\share\x` oder `//server/share/x`, Gerätepfade und das Home-Verzeichnis eines anderen Benutzers (`~user/...`) öffnen sich nie, sodass ein Klick Windows nicht dazu bringen kann, sich bei einem in der Ausgabe genannten Server anzumelden. Pfad-Links folgen **Im Snippet-Editor öffnen**: Die [Unternehmensrichtlinie](../reference/enterprise-policy.md), die diesen Eintrag entfernt, schaltet auch sie ab, ihr schreibgeschützter Modus verhindert, dass die Datei auf den Server zurückgeschrieben wird, und nach `su` oder einem inneren `ssh` verweigert korTTY das Öffnen der Datei, statt sie als falscher Benutzer zu lesen. Mosh-Tabs haben keine Datei-Links.

`file:`-Links per OSC 8, wie sie `ls --hyperlink`, `eza --hyperlink` oder `rg --hyperlink-format=default` auf Dateinamen setzen, öffnen sich in SSH- und lokalen Shell-Tabs auf dieselbe Weise, aber schreibgeschützt: Das Programm, das den Link ausgegeben hat, hat die Datei hinter seinem Text gewählt, deshalb hält der Snippet-Editor **Überschreiben** und **Speichern unter...** gesperrt und lässt Sie den Text nur als Snippet speichern. Ein solcher Link öffnet sich nur, wenn er keinen Host, `localhost` oder den Host nennt, auf dem die Sitzung läuft: den Host, mit dem sich der Tab verbunden hat, den Host, den sein Prompt anzeigt, oder bei einer lokalen Shell den Namen dieses Computers (ein Kurzname wie `web01` passt zu `web01.example.com`). Ein Link auf einen anderen Host, auf eine Netzwerkfreigabe (`file:////server/share/...`) oder mit Steuerzeichen im Pfad öffnet sich nicht, und in Mosh-Tabs bleibt jeder `file:`-Link Klartext.

Ein Link öffnet sich nur bei einem einzelnen Klick mit ++cmd++ / ++ctrl++, bei dem sich die Maus zwischen Drücken und Loslassen der Taste nicht bewegt. Ein einfacher Klick auf einen Link bewirkt nichts, sodass ein Klick, der nur den Fokus in einen Bereich setzt, nie eine Seite öffnet. Ein Doppelklick auf einen Link wählt wie bei anderem Text sein Wort aus und ein Dreifachklick seine Zeile, und weder ein Doppelklick mit ++cmd++ / ++ctrl++ noch eine Auswahl, die Sie über den Bildschirm ziehen, öffnet den Link, auf dem sie endet. Auch ++ctrl+alt++ mit einem Klick öffnet keinen Link, weil Windows ++alt-graph++ als ++ctrl+alt++ meldet. Der Klick mit Modifikatortaste funktioniert auch, während ein Programm wie tmux oder vim die Maus verwendet.

Alle anderen Links bleiben Klartext und bewirken beim Anklicken nichts: `news:`-, `javascript:`- und `data:`-Links, `mailto`-Links mit einem anderen Feld (einige Mailprogramme hängen die in einem `attach`-Feld genannte lokale Datei an) sowie Links, die Leerzeichen, Steuerzeichen oder unsichtbare richtungsändernde Zeichen (Bidi-Zeichen) enthalten oder länger als 8 KB sind. Wohin ein Link zeigt, bestimmt, wer ihn ausgibt – ein Server, eine Logdatei, die Sie mit `cat` anzeigen, die Ausgabe eines Programms –, deshalb übergibt korTTY einen Link nie an den Dateiöffner des Betriebssystems, der Programme und Skripte starten würde: Eine Datei öffnet sich immer nur als Text im Snippet-Editor.

Verlinkter Text wird in den Farben gezeichnet, die das Programm dafür gewählt hat, nicht in der Standardtextfarbe des Terminals, und ist unterstrichen, solange sich die Maus darüber befindet. Auf dem Bildschirm bleiben zwei Einschränkungen: Ein Farbwechsel mitten in einem Link wird ignoriert, und Fettdruck, Kursivschrift und invertierte Darstellung werden bei verlinktem Text nicht angezeigt. [Terminalaufzeichnungen](recording.md) behalten Fettdruck und invertierte Darstellung von verlinktem Text bei.

Lassen Sie die Maus auf einem Link ruhen, um zu sehen, wohin er führt, bevor Sie ihn öffnen. Der Zeiger wird zur Hand und der Link wird unterstrichen, auch eine als Klartext ausgegebene Web- oder E-Mail-Adresse, und nach einer halben Sekunde zeigt ein Tooltip **Cmd+Klick zum Öffnen** (macOS) bzw. **Strg+Klick zum Öffnen** (Windows und Linux) und die Adresse, die der Link wirklich öffnet. Bei einer Datei zeigt der Tooltip **Cmd+Klick zum Öffnen im Snippet-Editor** bzw. **Strg+Klick zum Öffnen im Snippet-Editor** und den Pfad, bei einem `file:`-Link per OSC 8 dessen vollständiges `file://`-Ziel. Bei einem mit OSC 8 ausgezeichneten Link ist der Tooltip die einzige Stelle, die sein Ziel zeigt, denn das Programm kann dafür beliebigen Text ausgeben. Der Tooltip schreibt einen internationalen Hostnamen in seiner `xn--`-Form, sodass ein Buchstabe aus einem anderen Alphabet, der nur wie ein lateinischer aussieht, auffällt, und er zeigt Leerzeichen, Steuerzeichen und unsichtbare richtungsändernde Zeichen (Bidi-Zeichen) als `%`-Codes; eine sehr lange Adresse wird am Ende gekürzt, ihr Host aber immer vollständig angezeigt. Eine Adresse, die korTTY nicht öffnet, etwa ein E-Mail-Link mit einem `attach`-Feld oder ein `file:`-Link auf einen anderen Host, bekommt keine Hand, und ihr Tooltip sagt, dass korTTY sie nicht öffnet. Unterstreichung und Tooltip verschwinden, wenn Sie den Link mit der Maus verlassen, eine Auswahl ziehen, scrollen, tippen oder zu einem anderen Fenster wechseln, und sie folgen dem Text, wenn neue Ausgabe ihn verschiebt.

Ist der Text eines OSC-8-Links selbst eine Webadresse auf einem anderen Host als dem, den der Link öffnet, etwa `https://example.com/login`, ausgegeben für einen Link auf `https://login.example.net/` oder auf einen `file:`-Pfad, fragt ein Klick mit ++cmd++ / ++ctrl++ nach, bevor er den Link öffnet. Die Rückfrage nennt den Host, den der Text zeigt, und die Adresse, die der Link öffnet, in derselben Form wie der Tooltip, und **Abbrechen** ist die Standardschaltfläche, sodass ++enter++ den Link nicht öffnet. Hostnamen werden ohne Rücksicht auf Groß-/Kleinschreibung, einen abschließenden Punkt oder ein vorangestelltes `www.` verglichen, und ein Link, dessen Text keine Webadresse ist, etwa ein Wort oder ein Dateiname, fragt nie nach.

Statt eine Taste gedrückt zu halten, können Sie mit der rechten Maustaste auf einen Link klicken: Das Kontextmenü beginnt dann mit **Link öffnen** und **Linkadresse kopieren**. **Link öffnen** öffnet den Link genau wie ein Klick mit ++cmd++ / ++ctrl++, einschließlich der oben beschriebenen Rückfrage bei einem Link, dessen Text einen anderen Host zeigt. **Linkadresse kopieren** kopiert die Adresse in die Zwischenablage: bei einem OSC-8-Link die Adresse, die er wirklich öffnet, in der Form, die Ihr Browser erhält (ein internationaler Hostname in seiner `xn--`-Form wie im Tooltip, aber nie gekürzt), nicht den Text, den das Programm dafür ausgegeben hat; bei einer als Klartext ausgegebenen Web- oder E-Mail-Adresse die Adresse so, wie sie ausgegeben wurde, eine E-Mail-Adresse also ohne `mailto:`. Mit dem [internen Zwischenablagemodus](../reference/enterprise-policy.md#interner-zwischenablagemodus) der Unternehmensrichtlinie bleibt die Adresse innerhalb von korTTY. Bei einem Link auf eine Datei lauten die beiden Einträge **Datei im Snippet-Editor öffnen**, das sie wie ein Klick mit ++cmd++ / ++ctrl++ öffnet, und **Pfad kopieren**, das den Pfad ohne nachfolgende Zeilennummer kopiert; bei einem `file:`-Link per OSC 8 ist das der Pfad, den der Link wirklich öffnet, nicht der Text, den das Programm ausgegeben hat. Das Menü wirkt auf den Link, der sich in dem Moment unter dem Zeiger befand, als Sie die rechte Maustaste gedrückt haben, sodass neue Ausgabe, die vor dem Öffnen des Menüs eintrifft, daran nichts ändert, und es funktioniert auch, während ein Programm wie tmux oder vim die Maus verwendet. Ein Link, den korTTY nicht öffnet, etwa ein E-Mail-Link mit einem `attach`-Feld oder ein `file:`-Link auf einen anderen Host, erhält keinen der beiden Einträge.

## Schnellauswahl

Die Schnellauswahl kopiert oder öffnet ohne Maus, was das Terminal anzeigt. Drücken Sie ++cmd+shift+space++ (macOS) bzw. ++ctrl+shift+space++ (Windows und Linux) oder wählen Sie **Bearbeiten → Schnellauswahl**: korTTY legt einen Rahmen um jede Webadresse, jeden Dateipfad, jede E-Mail-Adresse, UUID, IP-Adresse (IPv4 mit optionalem Port sowie IPv6), jeden Git-Commit-Hash und jede Zahl mit vier oder mehr Ziffern, die der fokussierte Bereich anzeigt, und setzt ein Kürzel aus einem oder zwei Buchstaben über die ersten Zeichen. Tippen Sie ein Kürzel, um dessen Text in die Zwischenablage zu kopieren; darüber erscheint kurz **Kopiert**. Halten Sie ++shift++ gedrückt, während Sie den letzten Buchstaben des Kürzels tippen, um eine Webadresse in Ihrem Browser, eine E-Mail-Adresse als neue Mail oder in SSH- und lokalen Shell-Tabs einen Dateipfad im Snippet-Editor zu öffnen, genau wie ein Klick mit ++cmd++ / ++ctrl++ darauf; ++shift++ mit dem Kürzel von etwas anderem kopiert es wie das Kürzel allein.

Die Kürzel beginnen in der untersten Zeile, sodass die neueste Ausgabe die ersten erhält. Bis zu 26 verschiedene Texte erhalten je einen Buchstaben, die Grundreihe zuerst (`a`, `s`, `d`, `f`, ...); bei mehr Texten hat jedes Kürzel zwei Buchstaben, und der erste Buchstabe, den Sie tippen, blendet die Kürzel aus, die nicht mit ihm beginnen, während ++backspace++ ihn zurücknimmt. Derselbe Text an mehreren Stellen erhält ein einziges Kürzel, und höchstens 676 verschiedene Texte erhalten eines. Ein Buchstabe, mit dem kein Kürzel weitergeht, wird ignoriert. Kürzel verwenden die Tasten, nicht die Zeichen, die diese tippen, sodass die Feststelltaste nie aus einem Kopieren ein Öffnen macht.

++esc++ beendet die Schnellauswahl, ohne etwas zu tun, ebenso jede Taste, die kein Kürzelbuchstabe ist, etwa eine Pfeiltaste, ++enter++, ++space++, eine Ziffer oder ein Buchstabe mit ++ctrl++, ++alt++ oder ++cmd++. Auch ein Klick in den Bereich, Scrollen, der Wechsel zu einem anderen Tab oder Fenster, eine Größenänderung des Bereichs und eine Änderung der Schriftgröße beenden sie. ++shift++, ++ctrl++ und die anderen Modifikatortasten allein ändern nichts, und das Gedrückthalten der Tasten, die die Schnellauswahl gestartet haben, startet sie nicht erneut. Überschreibt das Programm während der Schnellauswahl einen markierten Text, verliert dieser Text seinen Rahmen und sein Kürzel, die anderen behalten ihre; ist nichts mehr übrig, endet die Schnellauswahl.

Während die Schnellauswahl läuft, erreicht nichts, was Sie tippen, das Programm im Bereich: weder die Kürzelbuchstaben noch die Zeichen, die sie tippen würden, noch der Text einer Eingabemethode für Chinesisch, Japanisch oder Koreanisch, und im [Broadcast-Modus](#broadcast-modus) auch nicht die anderen Bereiche. Kopien laufen über die Zwischenablage von korTTY, sodass der [interne Zwischenablagemodus](../reference/enterprise-policy.md#interner-zwischenablagemodus) der Unternehmensrichtlinie sie innerhalb von korTTY hält. Die Schnellauswahl liest die Zeilen, die der Bereich an seiner Scrollposition anzeigt, einschließlich des Scrollbacks, wenn Sie zurückgescrollt haben, und lässt einen Text aus, der über die oberste oder unterste Zeile hinausreicht, statt ihn abgeschnitten anzubieten. Sie hängt nicht von **Web-Adressen, E-Mail-Adressen und Dateipfade im Terminaltext erkennen** unter [Einstellungen → Terminal → Links](../reference/settings/terminal.md#hinweise) ab. Sie braucht einen Terminal-Tab: In einem Snippet-Editor- oder Dateieditor-Tab bleibt ++cmd+shift+space++ / ++ctrl+shift+space++ beim Editor, und **Bearbeiten → Schnellauswahl** ist deaktiviert. Befindet sich die Tastatur in einer Seitenleiste eines Terminal-Tabs, etwa im KI-Chat, geht die Tastenkombination zuerst an diese Seitenleiste, und **Bearbeiten → Schnellauswahl** startet die Schnellauswahl von dort aus.

## Schriftgröße und Zoom

Passen Sie die Schriftgröße des aktiven Terminals im Handumdrehen an, ohne die Verbindung erneut herzustellen:

| Verknüpfung | Aktion |
|----------|--------|
| ++alt+plus++ | Zoom ein (Schriftgröße vergrößern) |
| ++alt+minus++ | Verkleinern (Schriftgröße verringern) |
| ++alt+0++ | Zoom zurücksetzen auf gespeicherte/Standard-Schriftgröße |
| ++ctrl++ + Mausrad | Vergrößern/verkleinern Sie das Terminal (Befehlstaste + Rad unter macOS) |

Wenn Sie ++ctrl++ (oder ++cmd++ unter macOS) gedrückt halten und mit dem Mausrad über das Terminal scrollen, ändert sich die Schriftgröße – Rad nach oben vergrößert, Rad nach unten verkleinert – anstatt durch den Puffer zu scrollen. Dies ergänzt die Tastenkombinationen ++alt+plus++ / ++alt+minus++ / ++alt+0++.

**Zoom zurücksetzen** stellt die Schriftgröße und -familie wieder her, wie sie bei der Verbindung waren, als Sie den Tab geöffnet haben (oder die gespeicherten Einstellungen der Verbindung oder die globale Vorgabe). Das Kontextmenü des Terminals hat dieselben Steuerelemente: Rechtsklick → **Extras** → **Schriftgröße** → **Vergrößern**, **Verkleinern** (jeweils zwei Punkte pro Schritt) oder **Zurücksetzen**. Der Zoom-Level gilt für den aktuellen Tab – alle seine geteilten Bereiche ändern sich gemeinsam – und lässt andere Tabs unverändert.

## Hintergrundtransparenz

**Ansicht → Zoom → Hintergrundtransparenz** ist ein Schieberegler (0–100 %), der den Terminalhintergrund auf dem Desktop durchscheinen lässt, während der Text völlig undurchsichtig und scharf bleibt. Bei 0 % ist der Hintergrund einfarbig; Höhere Werte lassen mehr vom Desktop durchscheinen. Der Wert wird über Neustarts hinweg gespeichert und wiederhergestellt.

Nur der Terminal-Bereich wird transparent — die Titelzeile, Menüleiste, Statusleiste und jede Tab ohne Terminal bleiben fest, sodass das Fenster niemals zu einem durchsichtigen Loch wird.

Horizontale, vertikale und geschachtelte Split-Terminals erben den aktiven Transparengrad, einschließlich der Panes, die nach der Aktivierung der Transparenz hinzugefügt wurden. Der Eintritt in Vollbild mit ++f12++ oder in Vollbild mit nur Terminal-Modus mit ++ctrl+shift+f++ macht die Terminalfläche vorübergehend opak, ohne den gespeicherten Wert zu ändern; das Verlassen des Vollbildmodus setzt diesen Wert jedem Pane zurück.

Da ein durchsichtiges Fenster einen anderen Fensterstil verwendet, den das Betriebssystem beim Öffnen des Fensters korrigiert, wird **das Ein- oder Ausschalten der Transparenz (Überschreiten von 0 %) erst nach einem Neustart vollständig wirksam**; Die Statusleiste zeigt einen Hinweis an, wenn Sie diesen Schwellenwert überschreiten. Das Anpassen des Pegels bereits im transparenten Modus wird live angewendet. Im transparenten Modus verwendet das Fenster eine schlanke benutzerdefinierte Titelleiste (Ziehen zum Verschieben, Schaltflächen zum Minimieren/Maximieren/Schließen, Doppelklick auf den Streifen zum Maximieren, Ziehen an den Rändern zum Ändern der Größe).

Der Schieberegler befindet sich nur in der Menüleiste im Fenster (die native macOS-Menüleiste kann keinen Schieberegler hosten).

## Lokale Shell-Registerkarten

Abgesehen von SSH und Mosh kann ein Terminal-Tab eine **Lokale Shell** hosten – die eigene Shell der lokalen Maschine, die über eine Pseudoterminal-Verbindung (siehe [Lokale Shell](connections.md#lokale-shell)) geöffnet wird. Einige Terminal-Verhaltensweisen sind lokaler Shell bewusst:

- **++ctrl+d++ schließt den Tab für lokale cmd.exe/PowerShell-Sitzungen.** Diese Windows-Shells beenden sich nicht bei EOF, weshalb ++ctrl+d++ sonst keine Wirkung hätte. Für Shells der bash-Familie (Git Bash/Cygwin/WSL, macOS/Linux) und SSH hat ++ctrl+d++ seinen normalen Sinn für EOF – die Shell beendet sich und der lokale Tab wird automatisch geschlossen.
- **Bestätigung schließen** verwendet den Wortlaut „Local-Shell“ anstelle von „SSH-Verbindung beenden?“ und die Eingabeaufforderung zum Schließen des Fensters ist transportneutral („Aktive Sitzungen“), da ein Fenster SSH-, Mosh- und Local-Shell-Registerkarten mischen kann.
- **Das aktuelle Verzeichnis folgt der interaktiven Shell.** Unter macOS und Linux aktualisiert korTTY es vom lokalen Shell-Prozess; Native PowerShell- und cmd-Eingabeaufforderungen stellen absolute Windows-Pfade bereit. Nach `cd`, `pushd`, `popd` oder `Set-Location` löst **Im Snippet-Editor öffnen** einen ausgewählten Dateinamen in das aktuelle Verzeichnis und nicht in das Startverzeichnis der Registerkarte auf. Wenn das Verzeichnis nicht sicher bestimmt oder zugeordnet werden kann, stoppt korTTY mit einem Fehler, anstatt eine gleichnamige Datei aus dem falschen Verzeichnis zu öffnen.
- **Nach einer Identitätswechsel wird „Im Snippet-Editor öffnen“ grau.** Sobald die Sitzung nicht mehr als die Identität läuft, mit der der Tab geöffnet wurde – nach `su`, einem inneren `ssh` oder einem Shell-Start-`sudo` – ist die Kontextmenü-Eintrag deaktiviert, sowohl in SSH-Tabs als auch in lokalen Shell-Tabs: Die verfolgten Verzeichnisse und die Dateizugriffe gehören weiterhin der ursprünglichen Anmeldung und würden die falsche Pfadauflösung ergeben. Der Eintrag wird automatisch wieder aktiv, sobald der Prompt den ursprünglichen Benutzer anzeigt (normalerweise nach `exit`). Ein lokaler Shell-Tab, dessen konfigurierte Shell-Befehl selbst ein Remote-Client wie `ssh` oder `mosh` ist, bleibt der Eintrag für den gesamten Tab deaktiviert. Falls die Belastung trotzdem ausgelöst wird, beendet korTTY mit einem Fehler statt die falsche Pfadauflösung vorzunehmen. Die KI-Kontextmenü-Aktionen folgen der gleichen Regel: Sie bieten nicht mehr die Option, den Inhalt der ausgewählten Datei dem Chat hinzuzufügen (siehe [Eine ausgewählte Datei dem Chat hinzufügen](ai-assistant.md#eine-ausgewahlte-datei-an-den-chat-anhangen)).
- **Zwischenablage-Text bleibt in Agent-Shortcuts erhalten.** Eingetippter und eingefügter Text durchläuft denselben Terminal-Input-Filter, einschließlich bracketed paste und split UTF-8-Eingabe, sodass ein eingefügter Dateiname Teil der `agent ...`-Anfrage bleibt und Enter ihn exakt einmal dispatcht. In einer Verbindung mit einer ein-Byte-[Zeichenkodierung](connections.md#zeichenkodierung) wie ISO-8859-1 wird jedes getippte Zeichen sofort gesendet.

## Sitzungsjournal

Jede Terminal-Registerkarte kann eine behalten [Sitzungsjournal](session-journal.md): Serverausgaben und eingegebene Befehle werden in ein Capture-Log aufgenommen, eine KI komprimiert sie zu einer lesbaren Zeitleiste und Screenshots und Notizen können über die Journalleiste oder das Rechtsklickmenü des Terminals hinzugefügt werden. Journale starten automatisch für Verbindungen, die sie aktivieren, oder rückwirkend für eine laufende Sitzung über **Tools > Sitzungsjournal starten/stoppen** – der vorhandene Scrollback wird importiert. Siehe [Sitzungsjournal](session-journal.md).

## Split-Screen mit Übertragung

Teilen Sie die Terminalansicht, um mehrere Verbindungen nebeneinander anzuzeigen, und senden Sie optional Eingaben an alle Bereiche gleichzeitig.

### Vorgänge aufteilen

- **Geteilter Bereich**: Erstellen Sie über das Kontextmenü oder Tastaturkürzel horizontale oder vertikale Teilungen innerhalb einer Registerkarte.
- **Unabhängige Sitzungen**: In jedem Bereich kann eine andere SSH-Verbindung angezeigt werden.
- **Anpassbare Fensterbereiche**: Ziehen Sie die Trennlinien, um die Fenstergrößen anzupassen.
- **Pane schließen**: das × in der oberen rechten Ecke eines Pane, *Extras → Terminal teilen → Teilung schließen* im Kontextmenü oder `exit` in seiner Shell schließt dieses Pane. Das × erscheint nur, solange der Tab mehr als ein Pane hat, und **Teilung schließen** ist im letzten deaktiviert; Tippen von `exit` im letzten Pane schließt den Tab.
- **Fokussierter Bereich**: *Bearbeiten → Kopieren*, *Bearbeiten → Einfügen*, *Bearbeiten → Suchen...*, die KI-Aktionen und die Aufzeichnung des aktiven Splits wirken auf den Bereich, der den Tastaturfokus hat oder zuletzt hatte – also den, in den Sie tippen, egal wie der Fokus dorthin kam (per Klick, Mittelklick oder Sprung aus dem Coding-Agents-Panel). Schließen Sie einen anderen Bereich, bleiben sie bei diesem Bereich; wird der fokussierte Bereich selbst geschlossen, wechseln sie zum ersten verbleibenden Bereich. Das Rechtsklick-Menü des Terminals wirkt auf den Bereich, den Sie mit der rechten Maustaste angeklickt haben.
- **Zugriffsgrund einmal pro Registerkarte abgefragt**: Wenn ein Server nach einem Grund für die Verbindung fragt, wie es ein Jump-Host im CyberArk-Stil tut, fragt ein Split nicht erneut. korTTY sendet den Grund, der beim Öffnen des Tabs angegeben wurde, da ein Server, der danach fragt, eine Sitzung schließt, die mit nichts antwortet. Bei einer Aufteilung auf einen anderen Server oder bei einem Server, der etwas anderes fragt, wird ebenfalls einmal gefragt, und ein neuer Tab beginnt immer mit der Frage. Lehnt der Server die Begründung ab, etwa weil eine Ticketnummer inzwischen abgelaufen ist, verwirft korTTY diese und fragt beim nächsten Versuch erneut nach.
- **Panes verschieben**: Halten Sie ++shift+alt++ (Windows/Linux) oder ++shift+option++ (macOS) und ziehen Sie ein Pane auf ein anderes, um die Reihenfolge zu ändern. Ohne Tastenkombination wird ein Maus-Ziehen für die Textauswahl im Terminal verwendet.

### Broadcast-Modus

Wenn der **Broadcast-Modus** aktiviert ist, werden Tastatureingaben gleichzeitig an alle sichtbaren Bereiche gesendet. Dies ist nützlich, um dieselben Befehle auf mehreren Servern auszuführen.

- **Gespiegelt**: getippter Text, ++enter++, ++backspace++, ++esc++, ++tab++ und ++shift+tab++, die Pfeiltasten, ++home++ / ++end++, ++page-up++ / ++page-down++, ++insert++ / ++delete++ und ++f1++ bis ++f11++, einschließlich ihrer ++shift++, ++ctrl++ und ++alt++ Kombinationen (++f12++ schaltet den Vollbildmodus um).
- **Für jeden Bereich kodiert**: jeder Bereich erhält eine Taste so, wie sein eigenes Programm sie erwartet. Wenn ein Bereich `mc` oder `vim` ausführt, die das Terminal in Anwendungs-Pfeiltasten umschalten, kommen seine Pfeile als `ESC O A` an, während eine Shell im nächsten Bereich `ESC [ A` erhält, sodass Verlauf und Vervollständigung in beiden funktionieren.
- **Bleiben lokal**: die Scrollback-Tasten (++shift+page-up++ / ++shift+page-down++, und ++ctrl+up++ / ++ctrl+down++ unter Windows und Linux oder ++cmd+up++ / ++cmd+down++ auf macOS) scrollen nur den fokussierten Bereich. Ein Vollbildprogramm wie `vim` oder `less` hat keinen Scrollback, sodass während eines läuft im fokussierten Paneel ++shift+page-up++ / ++shift+page-down++ (und ++ctrl+up++ / ++ctrl+down++ unter Windows und Linux) an dieses Programm gehen und, wie die anderen Tasten, an jedes andere Paneel.
- **Nicht gespiegelt**: Einfügen und Snippets gehen nur an den fokussierten Bereich, und was Sie in die Suchleiste eingeben, bleibt dort.
- **Ein Bereich, der zeilenweise einfügt, wird ausgelassen**: Solange ein Bereich eingefügten Text Zeile für Zeile sendet (siehe [Einfügen in langsame Geräte](#einfugen-in-langsame-gerate)), werden die Tasten, die Sie in den anderen Bereichen tippen, nicht an ihn gesendet, sodass keine davon zwischen zwei eingefügten Zeilen landet.
- **Ein hängender Bereich friert korTTY nicht ein**: Die Tasten für die anderen Bereiche werden im Hintergrund gesendet, an jeden Bereich in der Reihenfolge, in der Sie sie getippt haben, sodass ein Server, der nicht mehr antwortet, nur seinen eigenen Bereich aufhält, während das Fenster und die anderen Bereiche weiter reagieren.

## Terminale Auswirkungen

Terminaleffekte können den sichtbaren Terminalstil und die Ausgabeanimation ändern. Effekte sind Java-Plugins, die über **Plugins > Terminaleffekte** verwaltet werden.

### Benutzerkontrollen

- **Aktuelles Terminal**: Verwenden Sie **Ansicht > Terminaleffekt** oder das Terminal-Kontextmenü, um einen Effekt für das aktive Terminal auszuwählen.
- **Schnellverbindung**: Wählen Sie den Effekt und die Geschwindigkeit, bevor Sie eine temporäre oder gespeicherte Verbindung öffnen.
- **Connection-Manager**: Speichern Sie den Effekt und die Geschwindigkeit einer gespeicherten Verbindung, damit neue Tabs sie automatisch verwenden.
- **Geschwindigkeit**: Verwenden Sie den Schieberegler für `1x` bis `10x`; Wenn das immer noch zu langsam ist, geben Sie im numerischen Geschwindigkeitsfeld einen benutzerdefinierten Wert bis zu `99x` ein.

### Plugin-Verwaltung

- Öffnen Sie **Plugins > Terminaleffekte**, um Plugins zu verwalten.
- Die Tabelle listet geladene Plugins mit aktivem Status, Namen und Beschreibung auf.
- **Deaktivieren** Sie ein Plugin, damit es installiert bleibt, aber nicht für die Aktivierung verfügbar ist.
- **Externe `.jar`-Plugins importieren**. KorTTY kopiert sie in `~/.kortty/plugins`.
- **Exportieren** Plugins, die über eine Quell-JAR verfügen. Der gebündelte MOTHER-Effekt ist exportierbar.

!!! warning
    Importierte Terminaleffekt-Plugins sind vertrauenswürdiger Java-Code und werden nicht in einer Sandbox gespeichert. Importieren Sie Plugins nur aus Quellen, denen Sie vertrauen.

Eine ausführliche Dokumentation zur Plugin-Entwicklung finden Sie unter [Terminaleffekt-Plugins](terminal-effect-plugins.md).

## SSH-Keep-Alive

Verhindern Sie, dass Verbindungen aufgrund von Inaktivität unterbrochen werden, indem Sie SSH-Keepalive-Nachrichten konfigurieren:

1. Aktivieren Sie **SSH Keep-Alive** auf der Registerkarte **Terminal** der Verbindung oder unter **Einstellungen > Terminal**.
2. Stellen Sie das Intervall ein (5 bis 600 Sekunden, Standard: 60).
3. KorTTY sendet `SSH_MSG_IGNORE`-Heartbeat-Nachrichten im konfigurierten Intervall und aktiviert TCP-Socket-Keepalive, während die Option aktiv ist.

!!! note
    Wenn ein Server, eine Firewall, ein VPN oder ein NAT-Gateway inaktive Sitzungen früher als im konfigurierten Intervall schließt, kann die Verbindung trotzdem beendet werden. Überprüfen Sie in diesem Fall die serverseitige SSH-Konfiguration und die Netzwerk-Leerlauf-Timeout-Einstellungen sowie das KorTTY-Protokoll.

## Terminalprotokollierung

Schreibt die Terminalausgabe einer Verbindung zur Prüfung und zum Debuggen in eine Datei. Dies ist unabhängig vom [Session Journal](session-journal.md): Es handelt sich um ein einfaches Transkript ohne Zusammenfassungen, Markierungen oder Screenshots, und beide können gleichzeitig ausgeführt werden.

Konfigurieren Sie es an einer beliebigen Stelle:

- **Connection-Manager > Verbindung bearbeiten > Protokollierung** für eine gespeicherte Verbindung.
- **Schnellverbindung > Terminalprotokoll** für eine einmalige Sitzung oder zum Ändern der Einstellung für die Verbindung, die Sie gerade öffnen möchten.

1. Protokollierung aktivieren.
2. Wählen Sie einen **Protokollordner**. Bleibt es leer, verwendet KorTTY `~/.kortty/terminal-logs`. Sie wählen den Ordner aus; Die Dateinamen sind KorTTYs.
3. Wählen Sie ein Protokollformat:
   - **Einfacher Text** – Eine zeitgestempelte Zeile pro Ausgabezeile.
   - **XML** – Strukturiertes XML mit Zeitstempeln.
   - **JSON** – Strukturiertes JSON mit Zeitstempeln.
4. Optionally adjust the **maximum file size** (default: 10 MB) and the **retention period** (default: 30 days), and turn off **Start a new file every day** or **Compress closed files (gzip)** — both are on by default. Schnellverbindung's Terminal log section covers enable, folder, format and compression; size limit, retention and daily rotation keep their configured or default values.

### Dateinamen

Jeder Datei wird der Name `<date>-<time>-<server>_<number>` zugewiesen, zum Beispiel `2026-08-04-14-30-12-web01_1.log.gz`. Der Datumsteil sorgt dafür, dass eine Liste von Verzeichnissen chronologisch sortiert wird, und die anhängende Nummer unterscheidet Verbindungen, die gleichzeitig offen sind – zwei Tabs auf dem gleichen Server erhalten die Namen `_1` und `_2` und schreiben nie in einander Dateien.

### Rotation, Komprimierung und Retention

Standardmäßig wird eine neue Datei täglich erstellt und immer dann erneuert, wenn die maximale Größe erreicht ist (diese Teile werden mit `.p2`, `.p3`, … nummeriert); die tägliche Rotation kann deaktiviert werden, sodass nur eine Rotation nach Größe erfolgt. Bei der Rotation wird nichts jemals überschrieben oder gelöscht.

Geschlossene Dateien werden standardmäßig komprimiert. Die aktuell geschriebene Datei bleibt immer unkomprimiert, sodass sie bei einem Absturz nicht abgeschnitten werden kann. Deaktivieren Sie **Geschlossene Dateien komprimieren (gzip)**, um fertige Dateien stattdessen als einfachen Text beizubehalten. Eine Verbindung, die keine Ausgabe erzeugt, erstellt überhaupt keine Datei.

Dateien, die älter als die Retentionsdauer sind, werden automatisch gelöscht, sobald eine Verbindung gestartet wird und nach jeder täglichen Rotation. Legen Sie die Retention auf `0` fest, um alles zu behalten. Nur die Protokolldateien von korTTY werden entfernt – alles andere bleibt unverändert, daher ist es sicher, die Einstellung auf ein Verzeichnis zu setzen, das auch für andere Zwecke verwendet wird.

### Was vor dem Schreiben entfernt wird

Erfasste Zeilen durchlaufen die gleiche Schwärzung wie das [Session Journal](session-journal.md) im Erfassungsthread, bevor irgendetwas gepuffert oder geschrieben wird: das eigene Passwort der Verbindung und alle Ersetzungsregeln, die in der Richtlinie Ihrer Organisation definiert sind. Das Geheimnis gelangt nie in die Datei, sodass hinterher nichts bereinigt werden muss.

Protokolldateien und ein Protokollordner, den KorTTY selbst erstellt hat, sind auf Besitzerrechte eingestellt, sofern das Dateisystem dies unterstützt. Ein Ordner, den Sie selbst ausgewählt haben, behält die von Ihnen erteilten Berechtigungen.

!!! warning "Redaction deckt nur das ab, was KorTTY weiß"
    Ein Passwort, das korTTY für die Verbindung speichert, wird ausgeblendet. Ein Geheimnis, das Sie selbst in einen Befehl eingeben oder das ein Programm ausgibt, wird nicht ausgeblendet – korTTY kann dies nicht erkennen. Behandeln Sie den Log-Ordner als sensibel und verwenden Sie Richtlinien zur Ersetzung von Mustern, die sich wiederholen.

## Terminalaufzeichnung

Die Terminalaufzeichnung ist als ressourcenschonende Wiedergabefunktion konzipiert. KorTTY zeichnet Terminal-Bildschirmstatusänderungen und Zeitereignisse in einer JDK/GZIP-Streaming-komprimierten `.korttyrec.jsonl.gz`-Datei pro Terminal-Tab-Sitzung auf. Ältere `.korttyrec.jsonl`-Wiedergabedateien bleiben lesbar.

### Aufzeichnungen konfigurieren

1. Um die Aufzeichnung automatisch nach jedem App-Neustart zu aktivieren, öffnen Sie **Einstellungen > Video** und aktivieren Sie **Terminalaufzeichnung nach App-Neustart aktivieren**.
2. Um die Aufzeichnung nur für diese Sitzung zu aktivieren, öffnen Sie **Tools > Video Manager...** und wählen Sie **Terminalaufzeichnung für diese App-Sitzung aktivieren**.
3. Legen Sie den **Speicherpfad** fest. Wenn die Standardeinstellung beibehalten wird, verwendet KorTTY `~/.kortty/recordings`.
4. Wählen Sie den **Standardbereich** (aktiven Split oder gesamten Tab). Aufzeichnungen sind immer KorTTY-Replay-Dateien; der Videoexport ist ein separater Schritt und erfordert `ffmpeg`.
5. Aktivieren oder deaktivieren Sie **Auto-Pause, wenn das Terminal im Leerlauf ist** und legen Sie den Leerlaufschwellenwert fest (Standard: 20 Sekunden).
6. Optional: Aktivieren Sie **Terminalfarben in neuen Aufnahmen erfassen**, wenn exportierte Videos Terminalfarben wiedergeben sollen.
7. Optional: Legen Sie den `ffmpeg`-Pfad fest und klicken Sie auf **Prüfen**. Wenn `ffmpeg` fehlt, bleibt der Videoexport deaktiviert, Wiedergabedateien bleiben jedoch verwendbar.
8. Klicken Sie auf **Speichern**.

### Aufnahme starten und stoppen

1. Öffnen oder fokussieren Sie eine SSH-Terminal-Registerkarte.
2. Wenn die Terminalaufzeichnung aktiviert ist, klicken Sie in der Terminalleiste auf **Aufzeichnung starten**, wählen Sie **Extras > Terminalaufzeichnung starten/stoppen** oder drücken Sie ++ctrl+shift+e++ (Befehl+Umschalt+E unter macOS).
3. Wenn die Registerkarte mehrere geteilte Terminals enthält, wählen Sie aus, ob nur die aktive Teilung oder die gesamte Registerkarte aufgezeichnet werden soll.
4. Klicken Sie auf **Aufzeichnung stoppen** oder drücken Sie erneut ++ctrl+shift+e++, um das aktuelle Segment zu stoppen.
5. Starten und stoppen Sie so oft wie nötig auf derselben Registerkarte. KorTTY hängt alle Segmente an dieselbe Wiedergabedatei an, bis die Registerkarte geschlossen wird.

### Exportieren Sie ein Video

1. Öffnen Sie **Tools > Video-Manager...**.
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus der Liste aus.
3. Stellen Sie sicher, dass der ffmpeg-Status besagt, dass der Videoexport aktiviert ist.
4. Klicken Sie auf **Exportieren...**.
5. In den Exportoptionen:
   - Wählen Sie **Gesamte Aufzeichnung exportieren** oder geben Sie Start-/Endzeiten im Minuten- oder `MM:SS`-Format ein.
   - Wählen Sie, ob Terminalfarben einbezogen werden sollen (nur verfügbar, wenn die Wiedergabe Farbdaten enthält).
   - Wählen Sie das Format **WebM/VP9** oder **MKV/FFV1** und dann einen Ausgabepfad.
6. Während KorTTY Frames rendert und `ffmpeg` ausführt, zeigt der Exportfortschrittsdialog die aktuelle Phase, den Fortschrittsbalken und die geschätzte verbleibende Zeit an. Beim Export wird die aufgezeichnete Terminalgeometrie verwendet, sodass große Terminalbildschirme nicht beschnitten werden.

### Aufnahmen ansehen und verwalten

1. Öffnen Sie **Tools > Video-Manager...**.
2. Wählen Sie eine `.korttyrec.jsonl.gz`-Wiedergabedatei aus.
3. Klicken Sie auf **Anzeigen**, um die Wiederholung direkt in KorTTY abzuspielen.
4. Verwenden Sie die Replay-Viewer-Timeline zum Scrubben oder geben Sie einen **Zeitsprung**-Wert ein, z. B. `5` für Minute 5 oder `5:30` für Minute 5 und 30 Sekunden.
5. Stellen Sie **Geschwindigkeit** zwischen `1x` und `20x` ein, um die Wiedergabegeschwindigkeit zu steuern.
6. Klicken Sie auf **Umbenennen...**, um die Wiedergabedatei umzubenennen.
7. Klicken Sie auf **Löschen**, um die ausgewählte Wiederholung nach der Bestätigung zu löschen.
