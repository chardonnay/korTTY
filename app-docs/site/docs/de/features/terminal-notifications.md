---
title: Terminal-Benachrichtigungen
---

# Terminal-Benachrichtigungen

Ein Programm im Terminal kann Ihre Aufmerksamkeit verlangen, während Sie anderswo arbeiten: Ein Build-Skript läutet die Glocke, wenn es fertig ist, ein Prompt läutet sie, wenn er auf eine Eingabe wartet, ein Coding-Agent auf einem Server bittet um eine Benachrichtigung, wenn er Ihre Antwort braucht, und in einer für die [Shell-Integration](shell-integration.md) eingerichteten Shell teilt ein langer Befehl korTTY mit, wann er beendet ist. korTTY markiert den Tab, aus dem eine solche Anfrage kommt, sodass Sie es in der Tab-Leiste sehen, und kann zusätzlich eine Desktop-Benachrichtigung anzeigen. Es spielt nie einen Ton ab.

Ein Programm kann auch darum bitten, Text in Ihre Zwischenablage zu legen. korTTY erlaubt das nur, wenn Sie es einschalten, und die Statusleiste meldet es jedes Mal; siehe [Programme, die in die Zwischenablage kopieren](#programme-die-in-die-zwischenablage-kopieren-osc-52).

## Wann ein Tab als angesehen gilt

korTTY bittet nur in Tabs um Ihre Aufmerksamkeit, die Sie gerade nicht ansehen. Ein Tab gilt als angesehen, solange er der ausgewählte Tab des vordersten Fensters ist, also des Fensters, das den Tastaturfokus hat und nicht minimiert ist. Welcher Bereich eines Tabs mit [geteilten Bereichen](terminal.md#split-screen-mit-ubertragung) den Fokus hat, spielt keine Rolle: Der Tab gilt als Ganzes als angesehen. Ein nicht ausgewählter Tab, die Tabs eines korTTY-Fensters, das hinter einem anderen liegt, und alle Tabs, solange eine andere Anwendung im Vordergrund ist, gelten als nicht angesehen.

## Die Terminalglocke

Programme läuten die Terminalglocke mit dem Steuerzeichen BEL (++ctrl+g++): `bash` und `zsh` läuten sie, wenn eine ++tab++-Vervollständigung nichts findet, `less` und `vim`, wenn Sie versuchen, über das Ende einer Datei hinauszugehen, und ein Skript kann sie mit `printf '\a'` läuten, wenn ein langer Job endet. Die Glocke funktioniert in lokalen Shells und SSH-Sitzungen gleichermaßen, in jeder Terminalemulation, die korTTY anbietet, und braucht keine Einrichtung auf dem Server.

### Glockenmarkierung am Tab

Wenn die Glocke in einem Tab läutet, den Sie gerade nicht ansehen, zeigt der Tab 🔔 in seinem Titel, nach dem Coding-Agent-Symbol und vor dem Präfix `[group]`, zum Beispiel `⚡ 🔔 [Ops] web-01`. Wenn Sie auf den Tab zeigen, steht in seinem Tooltip *Die Glocke hat geläutet, während Sie diesen Tab nicht angesehen haben.*, sodass die Markierung auch in Worten erklärt ist.

Die Markierung verschwindet, sobald Sie den Tab ansehen: Wählen Sie ihn aus oder holen Sie sein Fenster in den Vordergrund. Eine Glocke in dem Tab, den Sie gerade ansehen, hinterlässt keine Markierung. Die Markierung ist immer aktiv und macht kein Geräusch, daher hat sie keine eigene Einstellung. Viele Glockensignale kurz hintereinander, etwa von `cat` auf eine Binärdatei, zählen als eines.

### Desktop-Benachrichtigung für die Glocke

Ist **Desktop-Benachrichtigung, wenn die Glocke in einem Tab läutet, den Sie gerade nicht ansehen** in *Einstellungen → Terminal → Benachrichtigungen* eingeschaltet (siehe [Terminal-Einstellungen](../reference/settings/terminal.md#hinweise)), zeigt eine Glocke in einem Tab, den Sie gerade nicht ansehen, zusätzlich eine Desktop-Benachrichtigung über den Benachrichtigungsdienst des Betriebssystems: die Mitteilungszentrale auf macOS, eine Sprechblase im Infobereich unter Windows und `notify-send` unter Linux.

- Der Titel der Benachrichtigung ist `korTTY · ` und der Name des Tabs, ihr Text besagt, dass ein Programm die Glocke geläutet hat. Sie enthält nie Terminalausgabe. Ein Name, den die Shell für den Tab gesetzt hat, wird zuvor von Steuer- und Bidi-Zeichen bereinigt.
- Ein Bereich zeigt höchstens alle 10 Sekunden eine Glocken-Benachrichtigung; weitere Glockensignale in dieser Zeit halten nur die Markierung am Tab. Jeder Bereich eines geteilten Tabs zählt für sich.
- Die Einstellung ist standardmäßig ausgeschaltet, weil Shells bei jeder erfolglosen ++tab++-Vervollständigung die Glocke läuten. Eine Änderung gilt für die offenen Tabs, sobald Sie speichern.
- Ein Bereich, in dem korTTY einen [Coding-Agent](coding-agents.md) erkannt hat, erhält keine Glocken-Benachrichtigung, solange **Desktop-Benachrichtigung, wenn ein Coding-Agent eine Entscheidung braucht oder fertig wird, während Sie seinen Bereich nicht ansehen** eingeschaltet ist: Agents läuten die Glocke, wenn sie auf Sie warten, und ihre eigene Benachrichtigung sagt das bereits, mit mehr Details. Der Tab erhält trotzdem seine Markierung.
- Ein Klick auf die Benachrichtigung holt den Tab nicht nach vorn; die Benachrichtigungsdienste, die korTTY verwendet, können den Klick nicht zurückmelden. Achten Sie stattdessen auf das 🔔 in der Tab-Leiste.

## Lange laufende Befehle

In einer für die [Shell-Integration](shell-integration.md#setting-it-up) eingerichteten Shell markiert die Shell, wann jeder Befehl beginnt und wann er endet, mit seinem Exit-Status. Wenn ein Befehl, der mindestens die Mindestlaufzeit lief, standardmäßig 30 Sekunden, in einem Tab endet, den Sie gerade nicht ansehen, markiert korTTY diesen Tab mit 🔔 und zeigt eine Desktop-Benachrichtigung, zum Beispiel `korTTY · web-01` mit dem Text *Befehl fehlgeschlagen (Exit-Status 1) nach 2 Min. 14 Sek.* Starten Sie einen Build oder ein Upgrade, wechseln Sie zu einem anderen Tab oder einer anderen Anwendung, und korTTY meldet sich, sobald der Befehl fertig ist.

- Die Laufzeit zählt vom Drücken von ++enter++ bis zum Ende des Befehls, ohne die Zeit, die Sie zum Eintippen gebraucht haben. Ein Befehl, der mit Exit-Status 0 endet, meldet *Befehl beendet*, jeder andere Status *Befehl fehlgeschlagen*, und eine Shell, die keinen Status meldet, erhält *Befehl beendet nach …* ohne Status.
- Die Benachrichtigung enthält nie den Befehl selbst und auch nichts, was er ausgegeben hat: Befehlszeilen können Passwörter und Token enthalten, und eine Benachrichtigung kann auf dem Sperrbildschirm erscheinen. Wenn Sie auf den markierten Tab zeigen, erscheint derselbe Text in seinem Tooltip.
- Ein Befehl, der in dem Tab endet, den Sie gerade ansehen, löst nichts aus, ebenso wenig ein Befehl, der kürzer als die Mindestlaufzeit lief. Der Tab erhält seine Markierung auch bei ausgeschalteter Benachrichtigung.
- Ein Tab zeigt höchstens alle 10 Sekunden eine solche Benachrichtigung. Wenn [Broadcast](terminal.md#split-screen-mit-ubertragung) denselben Befehl in mehrere Bereiche eines Tabs getippt hat und sie kurz nacheinander fertig werden, erhalten Sie eine Benachrichtigung, und der Tab bleibt markiert, bis Sie ihn ansehen.
- Befehle, die der [KI-Agent](ai-assistant.md#ai-agent-und-ki-planung) von korTTY in einem Bereich ausführt, markieren weder den Tab noch lösen sie eine Benachrichtigung aus: Der Lauf des Agenten meldet seine eigenen Befehle in seinem Aktivitätspanel.
- Ein [Coding-Agent](coding-agents.md) wie Claude Code ist für die Shell selbst ein Befehl; wenn er in einem Tab endet, den Sie gerade nicht ansehen, wird das daher wie bei jedem anderen Befehl gemeldet.
- Ohne Shell-Integration ändert sich nichts: korTTY schließt nicht aus Pausen in der Ausgabe darauf, dass ein Befehl beendet ist. Shells ohne die Markierungen, `tmux`, `screen` und Mosh-Verbindungen erhalten keine Benachrichtigungen über lange Befehle; siehe die [Einschränkungen der Shell-Integration](shell-integration.md#einschrankungen).

*Einstellungen → Terminal → Benachrichtigungen* hat **Desktop-Benachrichtigung, wenn ein lang laufender Befehl in einem Tab endet, den Sie gerade nicht ansehen**, standardmäßig eingeschaltet, und **Mindestlaufzeit eines Befehls:**, von 1 bis 3.600 Sekunden. Beide sind ausgegraut, solange die Shell-Integration ausgeschaltet ist, und eine Änderung gilt für die offenen Tabs, sobald Sie speichern. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md#hinweise).

## Benachrichtigungen von Programmen

Ein Programm kann das Terminal mit einer Escape-Sequenz um eine Desktop-Benachrichtigung bitten: `OSC 9` (die iTerm2-Form, `ESC ] 9 ; text BEL`) oder `OSC 777` (die Form von urxvt und foot, `ESC ] 777 ; notify ; title ; body BEL`). Coding-Agents wie Claude Code oder Codex, die auf einem Server laufen, können damit mitteilen, dass sie auf Ihre Antwort warten oder fertig sind, und Ihre eigenen Skripte ebenso. Viele Agents und Tools haben eine Einstellung, die ihre Benachrichtigungen auf diesem Weg sendet, oft iTerm2-Stil genannt; korTTY versteht beide Formen in lokalen Shells und SSH-Sitzungen gleichermaßen, ohne dass auf dem Server etwas installiert werden muss. Zum Ausprobieren führen Sie Folgendes aus und wechseln innerhalb von fünf Sekunden zu einem anderen Tab:

```bash
sleep 5; printf '\e]777;notify;%s;%s\a' 'Backup' 'Finished without errors'
```

Kommt eine solche Anfrage aus einem Tab, den Sie gerade nicht ansehen, markiert korTTY den Tab mit 🔔, und wenn Sie auf den Tab zeigen, steht in seinem Tooltip *Ein Programm in diesem Tab hat eine Benachrichtigung gesendet:* mit dem Text. Eine Desktop-Benachrichtigung mit dem Titel `korTTY · ` und dem Namen des Tabs zeigt den Text unter diesem Titel, zum Beispiel *Backup: Finished without errors*, sodass sie immer angibt, aus welchem Tab sie kommt, und nie als Meldung einer anderen Anwendung durchgeht.

- Das Programm bestimmt jedes Zeichen des Textes, daher bereinigt korTTY ihn zuerst: Steuerzeichen, Zeilenumbrüche und die unsichtbaren Zeichen, die die Leserichtung ändern, werden entfernt, der Titel wird auf 80 Zeichen gekürzt und der Text auf 200. Die Sequenz selbst zeigt das Terminal nie an.
- Ein Bereich zeigt höchstens alle 5 Sekunden eine solche Benachrichtigung; was ein Programm in dieser Zeit anfordert, hält nur die Markierung am Tab und wird verworfen, sodass ein Programm, das Benachrichtigungen in einer Schleife ausgibt, Ihren Desktop nicht überfluten kann.
- Eine Anfrage in dem Tab, den Sie gerade ansehen, löst nichts aus.
- Ein Bereich, in dem korTTY einen [Coding-Agent](coding-agents.md) erkannt hat, erhält keine Benachrichtigung dieser Art, solange **Desktop-Benachrichtigung, wenn ein Coding-Agent eine Entscheidung braucht oder fertig wird, während Sie seinen Bereich nicht ansehen** eingeschaltet ist, weil die eigene Benachrichtigung des Agents das bereits sagt. korTTY erkennt Agents nur in lokalen Shell-Tabs, daher benachrichtigt ein Agent auf einem Server immer über seine eigene Anfrage.
- `OSC 9` mit einer Zahl am Anfang, etwa die Fortschrittsmeldung `ESC ] 9 ; 4 ; 1 ; 50 BEL` von ConEmu und Windows Terminal, ist keine Benachrichtigung und wird ignoriert. Andere `OSC 777`-Befehle sind ebenfalls keine Benachrichtigungen und bleiben unberührt.
- Diese Funktion braucht keine [Shell-Integration](shell-integration.md), und das Ausschalten der Shell-Integration stoppt sie nicht.
- In `tmux` oder `screen` kommen die Anfragen meist nicht an: Der Multiplexer reicht sie nicht weiter, und korTTY entpackt die Passthrough-Sequenzen von tmux nicht. Mosh-Verbindungen übertragen sie nie.
- Das eigene Log von korTTY zeichnet den Text nie auf; der Tooltip des Tabs und die Benachrichtigung sind die einzigen Stellen, an denen korTTY ihn zeigt. Ein [Terminalprotokoll](terminal.md#terminalprotokollierung) oder [Sitzungsjournal](session-journal.md) des Bereichs lässt Escape-Sequenzen weg, sodass der Text auch dort nicht ankommt, außer wenn ein Programm die seltene 8-Bit-Form der Sequenz verwendet.

*Einstellungen → Terminal → Benachrichtigungen* hat **Desktop-Benachrichtigung, wenn ein Programm in einem Tab, den Sie gerade nicht ansehen, eine anfordert (OSC 9, OSC 777)**, standardmäßig eingeschaltet. Ausgeschaltet markiert eine Anfrage nur den Tab. Eine Änderung gilt für die offenen Tabs, sobald Sie speichern. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md#hinweise).

!!! note "Datenschutz"
    Eine Desktop-Benachrichtigung zeigt den Namen des Tabs, der ein Servername sein kann, und je nach den Einstellungen Ihres Betriebssystems kann sie auf dem Sperrbildschirm erscheinen; die Benachrichtigung eines Programms zeigt außerdem den Text, den das Programm gesendet hat. Schalten Sie die Desktop-Benachrichtigungen aus, die Sie nicht möchten, oder die Benachrichtigungen für korTTY im Betriebssystem; die Markierung am Tab bleibt innerhalb von korTTY.

## Programme, die in die Zwischenablage kopieren (OSC 52)

Programme wie vim, Neovim und tmux können mit der Escape-Sequenz OSC 52 (`ESC ] 52 ; c ; <base64 text> BEL`) Text in die Zwischenablage des Terminals kopieren, in dem sie laufen. Über SSH ist das für sie der einzige Weg, die Zwischenablage des Computers vor Ihnen zu erreichen: Sie kopieren in vim auf dem Server, und der Text liegt in Ihrer Zwischenablage. korTTY erlaubt das nur, wenn Sie **Programmen im Terminal erlauben, Text in die Zwischenablage zu kopieren (OSC 52)** in *Einstellungen → Terminal* einschalten; standardmäßig ist es ausgeschaltet. Zum Ausprobieren schalten Sie es ein und führen Folgendes aus:

```bash
printf '\e]52;c;%s\a' "$(printf 'copied from the server' | base64)"
```

- Jedes Mal, wenn ein Programm kopiert, meldet die Statusleiste seines Fensters das und nennt den Tab, zum Beispiel *Ein Programm im Tab „web-01“ hat 22 Zeichen in die Zwischenablage kopiert (OSC 52).* Solange die Einstellung ausgeschaltet ist, meldet die Statusleiste, dass ein Programm es versucht hat, und die Zwischenablage bleibt, wie sie war.
- Programme können die Zwischenablage nur beschreiben, nie lesen: korTTY beantwortet die OSC-52-Abfrage nie, sodass nichts, was Sie anderswo kopiert haben, ein Programm im Terminal erreicht. Der eigene Einfügebefehl eines Programms, der die Zwischenablage auf diesem Weg liest, etwa das OSC-52-Einfügen von Neovim, erhält daher nichts; fügen Sie stattdessen mit dem Einfügen-Tastenkürzel von korTTY ein, das den [Einfügeschutz](terminal.md#einfugeschutz) durchläuft.
- Ein Schreibvorgang kann bis zu 256 KiB Text übertragen. Ein größerer Schreibvorgang, einer, der kein gültiges Base64 ist, oder einer, dessen Text kein UTF-8 ist, ändert nichts, und die Statusleiste meldet das. Zeilenumbrüche im Base64, wie `base64` ohne `-w0` sie schreibt, sind kein Problem.
- Jede Auswahl, die ein Programm nennt (`c`, `p`, `q`, `s`, die Cut-Buffer `0` bis `7` oder keine), landet in derselben Zwischenablage; unter Linux ist das die Zwischenablage, nicht die primäre Auswahl. Kopiert ein Programm mehrmals kurz hintereinander, enthält die Zwischenablage am Ende den letzten Text.
- Mit dem [internen Zwischenablagemodus](../reference/enterprise-policy.md#interner-zwischenablagemodus) der Unternehmensrichtlinie landet, was Programme kopieren, in der internen Zwischenablage von korTTY und erreicht nie die Zwischenablage des Betriebssystems.
- Diese Funktion braucht keine [Shell-Integration](shell-integration.md) und funktioniert in lokalen Shells und SSH-Sitzungen gleichermaßen. Innerhalb von `tmux` reicht `set -g set-clipboard on` in `~/.tmux.conf` die Kopien von tmux und der Programme darin an korTTY weiter. Mosh-Verbindungen übertragen die Sequenz möglicherweise nicht.
- Außer der Statusleiste weist Sie nichts darauf hin, dass sich die Zwischenablage geändert hat. Text, den ein Programm dort abgelegt hat und den Sie in korTTY einfügen, durchläuft weiterhin den Einfügeschutz, der bei Zeilenumbrüchen und Steuerzeichen nachfragt, andere Anwendungen fügen ihn aber unverändert ein. Lassen Sie die Einstellung ausgeschaltet, solange Sie auf Servern arbeiten, deren Programmen Sie nicht vertrauen.
- Das eigene Log von korTTY zeichnet nur auf, wie viele Bytes ein Programm kopiert hat, nie den Text. Ein [Terminalprotokoll](terminal.md#terminalprotokollierung) oder [Sitzungsjournal](session-journal.md) des Bereichs lässt Escape-Sequenzen weg, sodass der kopierte Text auch dort nicht ankommt, außer wenn ein Programm die seltene 8-Bit-Form der Sequenz verwendet: Dann enthält die Datei den Text Base64-kodiert.

Die Einstellung wird bei jedem Schreibvorgang gelesen, sodass eine Änderung für offene Tabs gilt, sobald Sie speichern. Siehe [Terminal-Einstellungen](../reference/settings/terminal.md#hinweise).
