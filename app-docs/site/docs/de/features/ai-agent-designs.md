---
title: KI-Agent-Designs
---

# KI-Agent-Designs

*Plugins → KI-Agent Design* bestimmt, wie die Ausführungen des Terminal-[KI-Agent](ai-tools.md) angezeigt werden: als Block im Terminal selbst, in einer schmalen Leiste darunter oder darüber, auf einer Schiene daneben, im klassischen Aktivitäts-Panel oder in einer Kombination davon. Eine **Kombination** wählt für jede Rolle ein Design, und Sie können eine Kombination als Standard festlegen und einzelnen Verbindungen eine andere zuweisen.

![AI Agent Design with the standard combination: the live preview shows a sample run as a block in the terminal, waiting for an approval, with the metro line and the approval row below](../assets/screenshots/plugins/ai-agent-design.png)

Öffnen Sie es über *Plugins → KI-Agent Design* oder *Ansicht → KI-Agent-Panel → Design...*. Wenn *Tool-Fenster als Tabs öffnen* aktiviert ist, wird es als Tab des Hauptfensters geöffnet. Eine Organisationsrichtlinie, die Plugins nicht zulässt, sperrt beide Einträge.

## Rollen und Designs

Die Tabelle listet jedes Design mit seiner Nummer, gruppiert nach Rolle. Ein Haken in **Aktiv** wählt das Design für seine Rolle in der angezeigten Kombination aus, sodass jede Rolle immer genau einen Haken hat.

| Rolle | Was es entscheidet | Designs, die Sie wählen können |
| --- | --- | --- |
| Verlauf | Wo die Schritte eines Laufs aufgeführt sind | **Blöcke im Terminal** (03), **Klassisches Panel** |
| Status | Eine kompakte Anzeige des aktuellen Standes der Ausführung, neben dem Verlauf | **Linienplan** (08), **Powerline-Statuszeile** (01), **Minimap-Schiene** (11), **Kopfleiste mit Schublade** (12), **Neon-Laufschrift** (18), **Keine Statusanzeige** |
| Freigabe | Wo Freigaben und Passwortanfragen beantwortet werden | **Im Verlauf** |
| Anordnung | Wo die Teile platziert werden | **Fest** |

Die anderen nummerierten Designs sind mit *(folgt)* aufgeführt und können noch nicht angekreuzt werden. Eine Kombination, die von einer neueren korTTY gespeichert wird und eines davon benennt, verwendet das Design der Standardkombination für diese Rolle.

## Kombinationen

| Kombination | Verlauf | Status |
| --- | --- | --- |
| Standard (Blöcke + Linienplan) | Blöcke im Terminal | Linienplan |
| Klassisch | Klassisches Panel | Keine Statusanzeige |

**Standard** ist die Vorgabe für jede Verbindung, die keine eigene Kombination wählt; die Vorgabe ist in der Liste mit ★ markiert. Die beiden eingebauten Kombinationen können nicht geändert werden: Wenn Sie ein anderes Design auswählen, während eine dieser angezeigt wird, erstellen Sie Ihre eigene Kopie mit dieser Änderung. **Neu** startet eine Kopie der Standardkombination, **Duplizieren** kopiert die angezeigte, **Umbenennen** und **Löschen** wirken sich auf Ihre eigenen Kombinationen aus, und **Als Standard verwenden** macht die angezeigte Kombination zur Vorgabe. Das Löschen der Vorgabe lässt **Standard** erneut als Vorgabe gelten. Jede Änderung wird sofort gespeichert.

## Vorschau

Die Vorschau ist ein echtes Terminalpaneel mit einer kurzen Shell-Sitzung, in der eine Beispielausführung abgespielt wird: Der Agent untersucht die Sitzung, analysiert sie, führt zwei Befehle aus, bittet darum, das Journal zu verkleinern, erhält die Antwort **1** und beendet sich mit seiner Antwort. Er verwendet Ihre Standardterminalfarben und Schrift, etwas kleiner. Das Auswählen einer Zeile in der Tabelle zeigt dieses Design in der aktuellen Kombination an, ohne es zu speichern; **Erneut abspielen** startet die Ausführung erneut.

![The preview after the sample run: the block in the terminal collapsed to the answer, the metro line with every station reached](../assets/screenshots/plugins/ai-agent-design-finished.png)

## Blöcke im Terminal (03)

Der Lauf erscheint als Block direkt über der Promptzeile des Shells. Sein Header nennt den Agenten und Ihre Frage, zeigt die vergangene Zeit sowie die gemeldeten Tokens; darunter gibt es eine Zeile pro Schritt mit dessen Ergebnis, und der laufende Schritt zeigt einen kleinen Spinner. korTTY schreibt den Block direkt in das Terminal und sendet nichts an den Server, und die Promptzeile, in der Sie tippen, wird nie neu geschrieben, sodass Sie den nächsten Befehl eingeben können, während der Agent arbeitet.

- ++ctrl+r++ zeigt oder verbirgt die Details der Schritte, ++esc++ oder ++ctrl+c++ bricht den Lauf ab.
- Eine Genehmigung listet die geplanten Befehle mit den Tasten **1** (einmal erlauben), **2** (immer erlauben) und **3** (abbrechen); während sie geöffnet ist, beantworten diese Ziffern die Genehmigung und erreichen die Shell nicht.
- Wenn die Ausführung endet, wird der Block zu einer Überschrift mit ✓ (oder *abgebrochen*, *fehlgeschlagen*), der Anzahl der Schritte, der Zeit und den Tokens zusammengeklappt, gefolgt von der Antwort. Der Block bleibt dann im Scrollback wie jede andere Ausgabe und wird in das Sitzungsjournal aufgenommen.
- Die Antwort ist Teil des Blocks, daher gilt die Option **Endergebnis auch im Terminal anzeigen** der Ausführungsdialog nur für Kombinationen ohne Blöcke.
- Solange ein Vollbildprogramm wie `less` oder `vim` geöffnet ist, wartet der Block und holt anschließend auf. Ein Block, der während der Ausführung vom Bildschirm gescrollt wurde, setzt in einem neuen Block über dem Prompt fort.

## Linienplan (08)

Eine schmale Leiste unter dem Terminal zeigt die Ausführung als Metro-Linie: eine Station pro Befehl, Analyse und Frage sowie eine **Antwort**-Station am Ende. Erreichte Stationen sind ausgefüllt, die aktuelle pulsiert, eine Station, die auf Ihre Eingabe wartet, leuchtet amberfarben und ein Licht bewegt sich entlang der Linie, während die Ausführung fortschreitet; wenn es mehr Stationen gibt als passen, werden die ältesten zusammengefasst als *+N*. Die Leiste zeigt außerdem die Frage, die Tokens und die Zeit an und verfügt über Schaltflächen zum Pausieren, Abbrechen und Ausblenden.

Die Leiste blendet sich einige Sekunden nach dem Ende der letzten Ausführung aus; die Verzögerung wird im Dialog festgelegt. *Ansicht → KI-Agent-Panel → Statusleiste ein- oder ausblenden* blendet sie wieder ein oder aus.

## Weitere Statusanzeigen

Wie der **Linienplan** (08) folgt jede Anzeige hier der Terminal-Ausführung, die auf Eingabe wartet, oder andernfalls deren neuester noch laufender Ausführung, und blendet nach dem Ende der letzten Ausführung aus. *Ansicht → KI-Agent-Panel → Statusleiste ein- oder ausblenden* blendet eine davon wieder ein oder aus. Eine Anzeige oberhalb oder neben dem Terminal nimmt dem Terminal Platz weg, während sie angezeigt wird.

![The four status displays beside the blocks in the terminal, each waiting for an approval: the Powerline status line with its steps unfolded, the top bar with its drawer, the minimap rail with a lens and the neon ticker](../assets/screenshots/plugins/ai-agent-status-designs.png)

- **Powerline-Statuszeile (01)** — Eine Zeile unter dem Terminal im Powerline-Stil: der Agent, der Schritt, an dem er arbeitet (mit Spinner), wie viele Befehle er ausgeführt hat, die Tokens und die Zeit. ▴ faltet die Schritte der Ausführung über die untersten Terminalzeilen auf und ▾ faltet sie wieder zusammen; eine offene Genehmigung faltet sie von selbst auf.
- **Kopfleiste mit Schublade (12)** — Eine Leiste über dem Terminal, die nicht im Weg des Prompts und der neuesten Ausgabe steht. Eine schimmernde Linie zeigt an, dass der Agent arbeitet, und wird grün oder rot, wenn die Ausführung endet; darunter die Anzahl der Befehle und der aktuelle Schritt. ▾ klappt die Schritte über die ältere Ausgabe.
- **Minimap-Schiene (11)** — Eine schmale Schiene am rechten Rand des Terminals mit einem farbigen Segment pro Schritt: blau für Befehle, violett für Analysen, bernsteinfarben für Fragen, rot für Fehler. Der laufende Schritt leuchtet. Beim Zeigen auf ein Segment wird eine Linse mit dem Schritt, seinem Ergebnis und den ersten Zeilen seiner Details angezeigt. Die Schiene hat keine Schaltflächen; ++esc++ bricht die Ausführung ab.
- **Neon-Laufschrift (18)** — Eine Leiste im Stil einer Fallblattanzeige unterhalb des Terminals im Neon-Look des korTTY-Logos: Jeder neue Schritt rollt in sein Fenster, eine offene Frage leuchtet bernsteinfarben mit ihren Tasten, und das Licht links pulsiert, während der Agent arbeitet. Bei einem hellen Theme verwendet die Leiste die Farben dieses Themes.

## Genehmigungen und Passwörter

Bei Blöcken im Terminal öffnet eine Genehmigung oder eine Passwortanfrage zusätzlich eine Zeile unter dem Terminal. Bei einer Genehmigung werden die Befehle und die Schaltflächen **1 Einmal erlauben**, **2 Immer erlauben** und **3 Abbrechen** angezeigt. Bei einem Passwort gibt es ein maskiertes Feld, **Für diese SSH-Sitzung verwenden** und **OK**; ++enter++ bestätigt und ++esc++ bricht ab. Das klassische Panel behält seine eigenen Prompts.

## Optionen

| Option | Wirkung |
| --- | --- |
| Statusleiste nach einem Lauf ausblenden, nach (Sekunden, 0 = beibehalten) | Wie lange die Statusanzeige nach dem letzten Lauf verbleibt; 0 behält sie bei, bis Sie sie ausblenden. Standard: 6 Sekunden. |
| Animationen reduzieren | Schaltet die pulsierende Station und das bewegte Licht des **Linienplans**, die Spinner, das Schimmern der **Kopfleiste mit Schublade**, das Rollen und Leuchten der **Neon-Laufschrift** und die Linsenanimation der **Minimap-Schiene** aus. |

## Ein Design pro Verbindung

Der **KI** Tab des [Verbindungseditors](connections.md#verbindung-erstellenbearbeiten) enthält eine Liste mit **KI-Agent Design**: **Standard verwenden** (mit dem Namen des aktuellen Standards) oder jede Kombination. Die Auswahl gilt ab dem nächsten Lauf dieser Verbindung. Da Ihre eigenen Kombinationen in den Einstellungen dieses Computers gespeichert sind, ist die Auswahl nicht Teil einer exportierten Verbindung; eine Verbindung, deren Kombination gelöscht wurde, zeigt **Gespeicherte Kombination (derzeit nicht verfügbar)** und verwendet den Standard.

## Farben

Die Designs übernehmen ihre Farben aus den **Terminal-KI-Agent-Fenster** Farben des Terminal-Themas der Verbindung, die der Theme-Editor auf dem [Themes Tab](../reference/settings/appearance.md#themes-tab) anzeigt. Ein Thema, dessen Cursor weiß oder grau ist, erhält einen blauen Akzent, ein helleres auf dunklen Themen und ein dunkleres auf hellen Themen; der Hintergrund des Panels folgt dem eigenen Hintergrund des Terminals mit nur einer dezenten Tönung. Agentenpanel-Farben, die korTTY für ein Thema vor dem blauen Akzent abgeleitet hat, werden erneut einmal abgeleitet; Farben, die Sie selbst ausgewählt haben, bleiben erhalten. Grün bleibt für abgeschlossene Schritte.

## An den Rand angedockt

*Ansicht → KI-Agent-Panel → Links andocken* oder *Rechts andocken* zeigt das klassische Panel im Seitendock; Blöcke erscheinen weiterhin im Terminal, und die Metro-Linie bewegt sich in das Dock mit dem Panel.

## Gespeicherte Einstellungen

| Datei | Element | Bedeutung |
| --- | --- | --- |
| `global-settings.xml` | `aiAgentDesignDefaultId` | Die Standardkombination; fehlt für **Standard** |
| `global-settings.xml` | `aiAgentDesignCombinations` | Ihre eigenen Kombinationen, jeweils ein `combination`-Element |
| `global-settings.xml` | `aiAgentDesignAutoHideSeconds` | Die Statusleistenverzögerung; fehlt für 6 Sekunden |
| `global-settings.xml` | `aiAgentDesignReducedAnimations` | Animationen reduzieren |
| `connections.xml` | `aiAgentDesignId` | Die eigene Kombination der Verbindung; bei der Standardverbindung nicht vorhanden |
