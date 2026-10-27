---
title: Coding-Agents
---

# Coding-Agents

korTTY erkennt, wenn ein terminalbasierter Coding-Agent – **Claude Code**, **Codex**, **Gemini CLI**, **MiniMax Code**, **Qwen Code**, **OpenCode**, **Cursor Agent**, **Aider**, **Amp**, **Goose**, **Crush**, **Kiro CLI**, **Continue**, **Grok CLI**, **Jules**, **GitHub Copilot CLI**, **Grok Build** oder **Antigravity CLI** – innerhalb eines Ihrer [lokalen Shell-Tabs](terminal.md#lokale-shell-registerkarten) läuft und verfolgt, was er derzeit tut: Arbeitet, wartet auf Ihre Antwort, ist abgeschlossen oder sitzt untätig an seinem Prompt. Die Analyse erfolgt vollständig innerhalb von korTTY auf Ihrem eigenen Gerät. Was gefunden wird, wird überall angezeigt: als Symbol im Tab-Titel, als Chips und Akzente im Dashboard, in einem anziehbaren **Coding-Agents**-Panel mit schnellen Antworten und einem Prompt-Feld, als Balken in der Statusleiste, als Zähler auf der App-Icon und, wenn Sie den Pane nicht betrachten, als Desktop-Benachrichtigung. Diese Seite erklärt, was erkannt wird, wie jede dieser Oberflächen funktioniert, wie die Funktionen deaktiviert werden können und wie die Bildschirmregeln angepasst oder erweitert werden können, wenn ein Agent seine Benutzeroberfläche ändert.

![Coding agents — from the pane's screen to dashboard, panel, status strip, app badge and notifier](../assets/diagrams/coding-agents.svg)

## Was erkannt wird

Die Erkennung umfasst die achtzehn unten aufgeführten Agents, gestartet aus einer **Lokale Shell**-Registerkarte – direkt, über einen Paketmanager-Wrapper wie `npx` oder als `node`-, `bun`-, `deno`- oder `python`-Skript. SSH- und Mosh-Registerkarten werden nicht analysiert, weil der Agent dort auf dem entfernten Rechner läuft und korTTY seinen Prozess nicht sehen kann; ein Agent auf einem Server kann Ihnen trotzdem mit einer Terminal-Benachrichtigung mitteilen, dass er auf Sie wartet, siehe [Benachrichtigungen von Programmen](terminal-notifications.md#benachrichtigungen-von-programmen).

| Agent | Erkannte ausführbare Dateien |
|-------|------------------------|
| Claude Code | `claude`, `claude-code` |
| Codex | `codex` |
| Gemini CLI | `gemini` (Google erlaubt es persönlichen Google-Konten nicht mehr, sich bei Gemini CLI anzumelden; es funktioniert weiterhin mit einem kostenpflichtigen Gemini-API-Schlüssel oder einer Gemini Code Assist Standard- oder Enterprise-Lizenz — persönliche Konten wechseln zu Antigravity CLI) |
| MiniMax Code | `mcode` (der laufende Agent benennt seinen Prozess in `minimax-code` um; korTTY liest diesen Prozessnamen, wenn `node` kein Skript meldet) |
| Qwen-Code | `qwen`, `qwen-code` |
| OpenCode | `opencode` |
| Cursor-Agent | `cursor-agent`, und das gebundene `node`-Skript unter `…/cursor-agent/versions/<version>/index.js` (der Launcher führt es aus; korTTY erkennt diesen Installationspfad) |
| Aider | `aider` (ein Python-Konsolenskript, auch `python -m aider`) |
| Amp | `amp` (seine Bildschirmregeln wurden noch nicht gegen Live-Sitzungen verifiziert; Amp erscheint ab seinem ersten Arbeits- oder Genehmigungsbildschirm) |
| Goose | `goose` (Goose-CLI von Block; das Datenbank-Migrationswerkzeug gleichen Namens wird nie gemeldet) |
| Crush | `crush` |
| Kiro CLI | `kiro-cli`, `kiro-cli-chat` (seine Bildschirmregeln wurden noch nicht gegen Live-Sitzungen verifiziert; Kiros Shell-Wrapper `kiro-cli-term` wird nie als Agent gezählt) |
| Continue | `cn` (ein `node`-Skript des npm-Pakets `@continuedev/cli`) |
| Grok CLI | `grok` als `node`-Skript (Open-Source-grok-cli von Superagent, npm-Paket `@vibe-kit/grok-cli`) |
| Jules | `jules` (asynchroner Agent von Google: seine Sitzungen laufen in der Cloud, sodass korTTY das lokale `jules`-Dashboard überwacht, das sie auflistet; die Bildschirmregeln wurden noch nicht gegen Live-Sitzungen verifiziert) |
| GitHub-Copilot-CLI | `copilot` |
| Grok Build | `grok` als natives Programm (Grok Build von xAI, installiert als `~/.grok/bin/grok` oder als die `grok-<os>-<arch>`-Binärdatei, auf die es verweist; der `agent`-Alias des Installers wird nicht erkannt; sein Berechtigungsdialog wählt *always-approve* voraus, sodass **Enter** allein jeden späteren Tool-Aufruf genehmigt — ablehnen mit **↓**, **↓**, **↓**, **Enter**) |
| Antigravity CLI | `agy` (Googles Nachfolger von Gemini CLI; Homebrews `antigravity-cli` verlinkt sein `antigravity`-Binary als `agy`, und dieser Name wird ebenfalls erkannt) |

Jeder geteilte Bereich wird einzeln verfolgt, sodass eine Registerkarte mit zwei Bereichen einen arbeitenden Agent zeigen kann, während der andere auf eine Berechtigungsentscheidung wartet. Ein Bereich, in dem kein Agent läuft oder dessen Agent beendet wurde, hat einfach kein Erkennungsergebnis.

## Zustände

Ein erkannter Agent befindet sich immer in genau einem von fünf Zuständen. Die Liste ist nach Dringlichkeit geordnet: Werden mehrere Bereiche zusammengefasst, gewinnt der dringlichste Zustand.

| Zustand | Bedeutung |
|-------|---------|
| **BLOCKED** | Der Agent wartet auf Sie – ein Berechtigungsdialog (*Do you want to proceed?*), eine Fragenauswahl oder eine `(y/n)`-Abfrage steht auf dem Bildschirm. |
| **DONE** | Der Agent ist fertig. Entweder zeigt er einen expliziten Abschlussbildschirm, den eine Regel erkennt, oder – weit häufiger – er ist von WORKING zu seiner leeren Eingabeaufforderung zurückgekehrt, während Sie seinen Bereich nicht angesehen haben. KorTTY hält ihn dann als DONE (✓) markiert, bis Sie den Bereich ansehen; sobald seine Registerkarte ausgewählt und sein Bereich im vordersten Fenster fokussiert ist, wird er IDLE. |
| **WORKING** | Der Agent denkt nach, führt ein Werkzeug aus oder streamt Ausgabe – typischerweise erkennbar an seiner Spinner-Zeile und dem Hinweis *esc to interrupt*. |
| **IDLE** | Der Agent läuft und sein Eingabefeld ist leer; er wartet auf Ihre nächste Anweisung. |
| **UNKNOWN** | Der Agent-Prozess ist vorhanden, aber der Bildschirm passt zu keiner Regel, und die Regeldatei verlangt ein striktes Ergebnis statt eines Fallbacks. |

## Markierung im Dashboard

![Dashboard rows with coding-agent chips, accent bars and the rollup](../assets/screenshots/coding-agents/dashboard.png)
Öffnen Sie das [Dashboard](../getting-started/main-window.md) über **Ansicht → Dashboard anzeigen** (++ctrl+shift+d++), und jede Verbindungszeile, in der ein Coding-Agent läuft, zeigt hinter dem Protokoll-Badge einen **Chip**: das Zustandssymbol, den Kurznamen des Agents (oder den Alias, den Sie ihm gegeben haben) und – bei einem wartenden, arbeitenden oder fertigen Agent – wie lange er sich schon in diesem Zustand befindet (`✋ claude 2:14`). Ein untätiger Agent erhält einen gedämpften, nur umrandeten Chip, damit Sie trotzdem sehen, dass einer vorhanden ist. Eine Zeile, in der zusätzlich korTTYs eigener KI-Agent läuft, führt beide Markierungen in einem Chip zusammen.

Die Zeile selbst weist eine farbige **Akzentleiste** auf der linken Kante auf – gelb, während der Agent auf Ihre Antwort wartet (die Zeile wird ebenfalls gefärbt), blau, während er arbeitet, grün, sobald er fertig ist – und der Statuspunkt eines wartenden Agents pulst, solange das Fenster im Vordergrund ist und Animationen aktiviert sind. Zeilen für Gruppe und Umgebung, die über einer markierten Verbindung liegen, zeigen eine **Zusammenfassungschip** wie `✋ 1 · ⚡ 2` und übernehmen die Akzentfarbe ihres dringendsten Kindes; im Footer wird diese Zusammenfassung an die Verbindungszahl (`3 of 5 connected · ✋ 1 · ⚡ 2`) angehängt, sodass ein wartender Agent auch dann sichtbar ist, wenn seine Gruppe eingerollt ist.

Ein geteilter Tab erweitert **Pane-Zeilen** unter der Verbindung (`Pane 2 · api`, benannt nach dem Arbeitsverzeichnis des Panes, mit dem Home-Verzeichnis, wie es das Shell-Programm schreibt, `~`), jeweils pro Pane, sodass jeder Pane separat erreichbar ist. Jeder Pane wird aufgeführt, unabhängig davon, ob ein Agent in ihm erkannt wurde: ein geteilter Tab, bei dem ein Pane ein Agent ausführt und der andere nicht, ist der übliche Fall, und die Anzeige nur des Panes mit dem Agenten würde die Hälfte des Tabs verbergen. Ein Pane ohne Agent trägt kein Chip und keine Akzentuierung. Ein nicht geteilter Tab erhält keine Pane-Zeilen, da seine Verbindungszelle bereits der Pane ist. Wenn ein Agent blockiert wird, erweitert die Oberfläche **die Struktur** die Baumstruktur auf seine Zeile bei jeder Übergangsphase – ein wartender Agent wird nie innerhalb einer zusammengeklappten Gruppe versteckt – und beachtet danach *Alle einklappen*.

Das Kontextmenü einer markierten Zeile ergänzt **Bereich fokussieren**, das die Registerkarte auswählt und genau diesen Bereich fokussiert, **Im Coding-Agents-Panel anzeigen**, das das Panel andockt und den Agent auswählt, und – solange der Agent auf Sie wartet – **Enter senden**, **Esc senden** und **Unterbrechen (Strg+C)**, um den Dialog zu beantworten, ohne das Dashboard zu verlassen. Der Registerkartentitel zeigt dasselbe Symbol wie der Chip – ✋ wartend, ⚡ arbeitend, ✓ fertig, zusammengeführt mit dem Symbol von korTTYs eigenem KI-Agent, wo beides zutrifft.

## Das Coding-Agents-Panel

![The Coding Agents panel docked beside the terminal](../assets/screenshots/coding-agents/panel.png)
Das Panel listet jeden erkannten Agenten aller korTTY-Fenster an einem Ort auf. Öffnen Sie es mit **Ansehen → Coding-Agents → Links andocken / Rechts andocken**, schalten Sie es auf der zuletzt verwendeten Seite mit **Ein-/Ausblenden** (++ctrl+alt+g++), über das ⋯-Menü im Panel, über das Kontextmenü der Statusleiste oder mit *Im Coding-Agents-Panel anzeigen* im Dashboard. Standardmäßig öffnet sich die Seitenleiste rechts, wenn der erste Agent in diesem Fenster erkannt wird und verschwindet, wenn keine Agenten in irgendeinem korTTY-Fenster erkannt werden. Ein inaktiver Agent wird weiterhin erkannt, sodass das Abschließen einer Aufgabe allein die Seitenleiste nicht versteckt. Die Auswahl der linken Seite behält diese Wahl für nachfolgende automatische Öffnungen bei, auch nach einem Neustart. Eine bereits geöffnete Seitenleiste bleibt sichtbar, solange ein anderes Fenster noch Agenten hat. Ein manuelles Verstecken dauert bis zur nächsten Erkennungssitzung im eigenen Fenster an. Deaktivieren Sie **Automatisch die Coding-Agents-Seitenleiste ein- und ausblenden** unter **Einstellungen → Terminal → Coding-Agents**, um die Sichtbarkeit vollständig selbst zu steuern. Die minimale und anfängliche Breite beträgt 750 Pixel; eine größere Größe wird über Neustarts hinweg gespeichert. Agenten, die auf Sie warten, werden zuerst aufgeführt (längste Wartezeit oben), gefolgt von den arbeitenden, erledigten und inaktiven Agenten in Fenster-, Tab- und Pane-Reihenfolge; die Zeile des aktuell aktiven Panes trägt einen linken Akzent.

Jede Zeile zeigt den Statuspunkt und Namen, ein Statuschip wie `✋ Waiting for you · 2:14`, die Standortzeile (`Window 2 › api › Pane 2 · ~/proj/api`) und die letzte Bildschirmausgabe, die eine Regel übereinstimmte – das Beweisstück. Darunter befinden sich die **Schnelltasten**: **Fokussieren** bringt das Fenster nach vorn, **`YES`**, **`NO`**, **Enter** und **Esc** beantworten einen Dialog (sie sind hervorgehoben, während der Agent auf Sie wartet), **↑** und **↓** bewegen sich durch einen Fragenpicker, **Ctrl+C** unterbricht, **Erklären** öffnet ein Drawer mit der vollständigen Erklärungsdarstellung (Agent, Zustand, passende Regel, Beweis, Prozess und Zeit im Zustand), und **Umbenennen…** gibt dem Agenten einen Alias, der seinen Namen in jedem Chip, jeder Zeile und Benachrichtigung ersetzt – ein leerer Alias stellt den Agentennamen wieder her, und der Alias bleibt bei einer erneuten Verbindung erhalten, wird aber beim Schließen des Fensters verworfen. Jede Taste geht zur eigenen Terminalverbindung des Fensters, genau so, als hätten Sie sie dort getippt; die Schaltflächen sind deaktiviert, solange das Fenster nicht verbunden ist.

**`YES`** und **`NO`** senden die vollständigen Großbuchstaben-Wörter gefolgt von Enter, sodass ein Klick antwortet und absendet; **Enter** allein bestätigt, was der Agent vorausgewählt hat. Für ein Menü wie das Genehmigungsdialog von MiniMax Code folgen Sie dem vom Agenten gezeigten Footer: **↑** / **↓** wählen eine Option, **Enter** bestätigt sie und **Esc** verweigert sie. **Fokussieren** ist die Navigation innerhalb von korTTY, daher kann es unverändert bleiben, wenn dieses Fenster bereits ausgewählt ist. **Erklären** öffnet oder schließt die Erkennungsdetails der Zeile (übereinstimmende Regel, Bildnachweis, Prozess und Zeit im Zustand); es fordert den Codierungsagenten nicht auf, seine Arbeit zu erklären. Die Erklärung wird automatisch nach 60 Sekunden zusammengeklappt oder früher, wenn Sie erneut **Erklären** klicken oder die Seitenleiste ausblenden. Beide Aktionen funktionieren unabhängig vom Agentenanbieter.

Der **Prompt-Box** am unteren Rand sendet längere Texte an den in der Zielliste ausgewählten Agenten: ++enter++ sends, ++shift+enter++ inserts a line break. Eine einzelne Zeile wird gesendet, gefolgt von Enter. Ein mehrzeiliger Prompt ist in einen bracketed paste-Block eingeschlossen – so erhält der Agent ihn als einen zusammengefügten Block statt mehrere eingereichte Zeilen – aber nur, wenn dieser Agent bracketed paste aktiviert hat, was Claude Code, Codex, Gemini CLI, MiniMax Code, Qwen Code, OpenCode, Cursor Agent, Aider, Goose, Crush, GitHub Copilot CLI und Grok Build bei ihrem Prompt tun; andernfalls werden die Zeilen wie getippt gesendet. Bracketed-paste-Marker im Prompt-Text selbst (`ESC[200~`, `ESC[201~` und ihre 8-Bit-Versionen) werden vor dem Senden entfernt, sodass ein Prompt niemals den Paste frühzeitig beendet und die verbleibenden Zeilen einzeln übermittelt. Zwei Prompts werden mit einer Meldung in der Statuszeile des Panels abgelehnt, anstatt gesendet zu werden: während der Agent **Warten auf Entscheidung** ist, bleibt die Schaltfläche *Senden* deaktiviert, weil der Text in das Berechtigungsdialogfeld gelangen würde – beantworten Sie ihn zuerst mit `YES`, `NO`, Enter oder Esc; und ein Prompt, dessen erste Zeile mit dem **korTTY-eigentlichen KI-Shortcut** (der unter Einstellungen → AI konfigurierte Befehlname, `agent` standardmäßig) beginnt, wird abgelehnt, weil der Shortcut-Filter von korTTY diese Zeile an seinen eigenen KI-Agenten weiterleiten würde statt an den Coding-Agenten – formulieren Sie die Zeile um. Fehler eines Quick-Keys oder Prompts (Fenster geschlossen, nicht verbunden, Schreiben fehlgeschlagen) erscheinen für ein paar Sekunden in derselben Statuszeile, niemals als Dialog, und eine erfolgreiche Sendung bestätigt mit *Gesendet an Claude Code*.

Die Schaltfläche **Nächster wartender** in der Kopfzeile des Panels springt zum nächsten Agent, der auf Sie wartet, über alle Fenster hinweg und mit Umlauf; es ist dieselbe Aktion wie ++ctrl+alt+n++ und ein Klick auf den Statusstreifen.

## Statusstreifen

![The coding-agent status strip at the right end of the status bar](../assets/screenshots/coding-agents/status-strip.png)
Das rechte Ende der Statusleiste zeigt einen kompakten Streifen, solange mindestens ein Coding-Agent bekannt ist: bis zu drei Chips für wartende, arbeitende und fertige Agents (`✋ 1 · ⚡ 2 · ✓ 1`, Nullwerte werden weggelassen), wobei der Punkt der wartenden pulsiert, solange das Fenster vorne liegt. Ein Klick springt zum nächsten Agent, der auf Sie wartet – oder, wenn keiner wartet, zum ersten Agent in Anzeigereihenfolge –, und ein Rechtsklick bietet *Ein-/Ausblenden* für das Panel und *Nächster wartender Agent*. Der Tooltip fasst die Zähler zusammen. Der Streifen verschwindet zusammen mit der Statusleiste, wenn Sie diese unter *Ansicht* ausblenden.

## App-Symbol-Badge und Benachrichtigungen

![The app icon with the badge for three waiting agents](../assets/screenshots/coding-agents/badge-icon.png)
Die Zahl der Agents, die auf eine Entscheidung warten – über alle Fenster hinweg –, wird als **Badge am App-Symbol** angezeigt, damit Sie sie bemerken, während Sie in einer anderen Anwendung arbeiten. Wie das Badge gezeichnet wird, hängt von der Plattform ab: Unter **macOS** setzt die paketierte App das Dock-Badge; unter **Windows** zeichnet korTTY die Zahl in sein eigenes Taskleisten- und Titelleistensymbol; unter **Linux** sendet es das Launcher-Entry-Signal, das KDE Plasma, Ubuntu Dock und Dash to Dock am Launcher-Symbol darstellen – das braucht eine installierte Desktop-Datei (das deb-, rpm- oder pacman-Paket) und einen erreichbaren Sitzungsbus, mit dem korTTY direkt spricht. Eine unveränderte GNOME Shell ohne Dock-Erweiterung zeigt überhaupt keine Launcher-Zähler. Wo kein Symbol-Badge verfügbar ist – ein entpacktes Archiv, `./gradlew run`, ein nicht unterstützter Desktop –, weicht korTTY auf den **Fenstertitel** aus, der zu `(2) KorTTY` wird, solange zwei Agents warten, und bei null wieder zu `KorTTY`. Wird ein Agent blockiert, während kein korTTY-Fenster fokussiert ist, fordert die App zusätzlich einmal Aufmerksamkeit an (das Dock-Symbol hüpft, der Launcher-Eintrag wird als dringend markiert).

Eine **Desktop-Benachrichtigung** wird angezeigt, wenn ein Agent eine Entscheidung benötigt oder abgeschlossen hat, während Sie nicht auf seine Pane schauen – das heißt, während sein Tab nicht der ausgewählte Tab des vorderen Fensters ist oder seine Pane nicht die fokussierte ist. Der Titel benennt den Agenten (*Claude Code benötigt eine Entscheidung*, *Claude Code abgeschlossen*), der Text zeigt den Standort und das Arbeitsverzeichnis gefolgt von der passenden Bildschirmzeile. Ein blockierter Zustand muss einen Moment andauern, bevor er gemeldet wird, ein Agent wird höchstens alle zehn Sekunden gemeldet, derselbe Übergang wird nie zweimal gemeldet, und für die Pane, auf die Sie schauen, wird nichts angezeigt. Benachrichtigungen nutzen den eigenen Dienst des Betriebssystems: das Benachrichtigungszentrum auf **macOS**, den Tray-Ballon auf **Windows** und den Benachrichtigungsserver des Desktops auf **Linux**, auch aus dem Flatpak-Paket. Nichts verlässt diesen Computer – der Text ist die Bildschirmzeile, die korTTY bereits gelesen hat.

**Ein Klick auf eine Benachrichtigung führt Sie zum Agenten:** korTTY bringt das Fenster nach vorn, wählt den Tab aus und fokussiert das Pane des Agenten, wie die **Fokussieren**-Schaltfläche des Panels. Falls das Pane in der Zwischenzeit geschlossen wurde, hat der Klick keine Wirkung. Wo der Klick funktioniert:

| Betriebssystem | Benachrichtigung | Klick |
| --- | --- | --- |
| macOS, installiert `korTTY.app` | Wird als korTTY angezeigt, mit seinem Namen und Icon im Benachrichtigungszentrum | Springt zum Pane. Die erste Benachrichtigung fragt einmalig nach Erlaubnis; wenn Sie **Nicht zulassen** wählen, zeigt korTTY keine mehr, bis Sie sie in *Systemeinstellungen → Benachrichtigungen → korTTY* erlauben |
| macOS, gestartet mit `./gradlew run` | angezeigt als **`korTTY Notifications`**, ein nativer Helfer, der von Gradle unter Verwendung der Xcode Command Line Tools erstellt wurde | springt zum Bereich der laufenden korTTY-Instanz. Erlauben Sie **`korTTY Notifications`**, wenn macOS zunächst fragt; seine Berechtigung ist getrennt von der des installierten Programms |
| Windows 10 und 11 | Tray-Ballon, angezeigt als Toast in der Benachrichtigungszentrale | Springt zum Pane der neuesten Benachrichtigung; ein Doppelklick auf das Tray-Symbol von korTTY bewirkt dasselbe |
| Linux | Über den Benachrichtigungsserver des Desktops (GNOME, KDE Plasma und andere), gruppiert unter korTTY | Springt zum Pane, wenn der Server Aktionen unterstützt; ohne Sitzungsbus über `notify-send` aus libnotify 0.7.10 oder neuer |
| Linux, Flatpak | Über den Benachrichtigungsserver des Desktops, auf den das Paket zugreifen darf (`org.freedesktop.Notifications`) | Springt zum Pane, wenn der Server Aktionen unterstützt; wenn diese Berechtigung mit `flatpak override` entzogen wurde, über `notify-send` auf dem Host, ab libnotify 0.7.10 |

Beide Oberflächen sind standardmäßig aktiviert. Der Badge-Schalter befindet sich unter **Einstellungen → Terminal → Coding-Agents** und der Benachrichtigungs-Schalter unter **Einstellungen → Messaging → Coding-Agents** (siehe [Erkennung aktivieren](#erkennung-aktivieren)). Das Ausschalten des Badges löscht ihn sofort; das Abschalten der Benachrichtigungen stoppt neue, ohne den Badge zu berühren.

## Tastenkürzel

| Tastenkürzel | Aktion |
| --- | --- |
| ++ctrl+alt+g++ | Coding-Agents-Panel auf seiner zuletzt verwendeten Seite ein- oder ausblenden (standardmäßig rechts) |
| ++ctrl+alt+n++ | Zum nächsten Coding-Agent springen, der auf eine Entscheidung wartet, über Fenster hinweg |
| ++enter++ / ++shift+enter++ | Im Prompt-Feld des Panels: Prompt senden / Zeilenumbruch einfügen |

Verwenden Sie unter macOS ++cmd++, wo ++ctrl++ angezeigt wird.

## So funktioniert die Erkennung

KorTTY geht erst dann von einem Agent aus, wenn zwei unabhängige Beobachtungen übereinstimmen – diese *doppelte Evidenz* verhindert, dass ein zufälliges `claude` in einem Shell-Skript oder ein zitiertes Prompt-Fragment Fehlalarme auslöst:

1. **Prozessbaum.** Die lokale Shell des Bereichs hat eine bekannte Prozess-ID. KorTTY durchläuft die Nachkommen dieses Prozesses und sucht den neuesten lebenden, dessen Programmname – oder bei `node`, `bun` und `deno` das ausgeführte Skript – zu einem der unterstützten Agents gehört.
2. **Bildschirmregeln.** Der aktuell im Bereich sichtbare Text (niemals der Scrollback) wird zusammen mit dem vom Agent gesetzten Fenstertitel und der Information, ob er auf den alternativen Bildschirm gewechselt hat, gegen die Regeldatei dieses Agents geprüft. Der erste Regeltreffer bestätigt den Prozess; von da an entscheiden die Regeln über den Zustand.

Der Bildschirm wird etwa 200 ms nach der ersten Änderung eines Ausgabeschwalls neu ausgewertet – weitere Änderungen innerhalb dieses Fensters werden zusammengefasst –, sodass ein langer Ausgabestrom eine Auswertung pro 200-ms-Fenster auslöst statt einer pro Zeile und ein kurzer Schwall genau eine. Ein langsamer Heartbeat bemerkt zusätzlich, wenn der Agent beendet wird, ohne etwas auszugeben, wenn die Registerkarte die Verbindung verliert oder wenn Sie die Erkennung abschalten. Regeln werden nach Priorität ausgewertet; die erste Regel, deren Bedingungen alle zutreffen, bestimmt den Zustand. Ist der Agent-Prozess bestätigt, aber keine Regel passt, wird der Fallback-Zustand der Regeldatei gemeldet (IDLE bei den mitgelieferten Dateien).

!!! note "Datenschutz"
    Bildschirmtext wird ausschließlich innerhalb von korTTY, im Arbeitsspeicher, auf diesem Rechner analysiert. Nichts wird an einen Dienst gesendet, auf die Festplatte geschrieben oder an den KI-Assistenten weitergereicht – die Erkennung ist reine Mustererkennung gegen lokale Regeldateien. Wenn Sie lokale Shell-Bildschirme gar nicht untersuchen lassen möchten, schalten Sie die Funktion unter **Einstellungen → Terminal → Coding-Agents** ab.

## Erkennung aktivieren

Detection ist standardmäßig aktiviert. Die Erkennung, die automatische Sichtbarkeit der Seitenleiste und das App-Badge werden in **Einstellungen → Terminal** konfiguriert, Abschnitt **Coding-Agents**. Desktop-Benachrichtigungen für erkannte Agents werden separat unter **Einstellungen → Messaging → Coding-Agents** gesteuert. Änderungen wirken sich auf alle offenen Tabs aus, sobald Sie speichern – ein Neustart oder erneutes Verbinden ist nicht erforderlich.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Coding-Agents (Claude Code, Codex, Gemini CLI, MiniMax Code, Qwen Code, OpenCode, Cursor Agent, Aider, Amp, Goose, Crush, Kiro CLI, Continue, Grok CLI, Jules, GitHub Copilot CLI, Grok Build, Antigravity CLI) in lokalen Shell-Tabs erkennen | Schalter | — | Ein | `codingAgentDetectionEnabled` |
| Automatisch anzeigen und verbergen der Coding-Agent-Seitenleiste | toggle | — | On | `codingAgentPanelAutoVisibility` |
| Anzahl der auf eine Entscheidung wartenden Agents am App-Symbol anzeigen | Schalter | — | Ein | `codingAgentAppBadgeEnabled` |

Um Meldungen über wartende oder fertiggestellte Agenten zu deaktivieren, schalten Sie **Desktop-Benachrichtigung, wenn ein Coding-Agent eine Entscheidung benötigt oder fertig ist, während Sie nicht auf sein Fenster schauen** unter [Messaging-Einstellungen](../reference/settings/messaging.md) aus. Der separate Benachrichtigungsschalter deaktiviert nicht die Erkennung, die Seitenleistensteuerung oder das App-Badge.

Wenn der Erkennungsschalter deaktiviert ist, durchläuft korTTY weder den Prozessbaum noch liest es den Bildschirm eines beliebigen Bereichs und das Dashboard, die Leiste, der Streifen, das Badge und die Benachrichtigungen fallen alle still. Die Platzierung und Breite der Leiste sind keine Optionen des Einstellungsdialogs: sie folgen dem **Ansehen → Coding-Agents** Menü und werden als `codingAgentPanelPlacement` (`HIDDEN`, `LEFT` oder `RIGHT`) und `codingAgentPanelWidth` (750–1 200 Pixel, Standard 750) gespeichert. Die gewählte Seite bleibt als `codingAgentPanelSide` (`LEFT` oder `RIGHT`, zunächst `RIGHT`) erhalten, auch wenn die Leiste ausgeblendet ist.

## Eigene Regeln

Die Bildschirmregeln jedes Agents liegen in einer kleinen JSON-Datei. KorTTY liefert pro Agent eine mitgelieferte Datei und lässt Sie diese durch Ihre eigene Kopie in Ihrem Konfigurationsverzeichnis ersetzen:

```text
~/.kortty/coding-agents/claude-code.json
~/.kortty/coding-agents/codex.json
~/.kortty/coding-agents/gemini-cli.json
~/.kortty/coding-agents/minimax-code.json
~/.kortty/coding-agents/qwen-code.json
~/.kortty/coding-agents/opencode.json
~/.kortty/coding-agents/cursor-agent.json
~/.kortty/coding-agents/aider.json
~/.kortty/coding-agents/amp.json
~/.kortty/coding-agents/goose.json
~/.kortty/coding-agents/crush.json
~/.kortty/coding-agents/kiro.json
~/.kortty/coding-agents/continue.json
~/.kortty/coding-agents/grok.json
~/.kortty/coding-agents/jules.json
~/.kortty/coding-agents/copilot.json
~/.kortty/coding-agents/grok-build.json
~/.kortty/coding-agents/antigravity.json
```

Eine Überschreibung ersetzt die mitgelieferte Datei für diesen Agent **als Ganzes** – es gibt kein Zusammenführen, beginnen Sie also mit der mitgelieferten Datei und bearbeiten Sie sie. Die Datei muss eine reguläre Datei sein (symbolischen Links wird nicht gefolgt), und ihr `kind` muss zum Dateinamen passen. Eine ungültige Datei – fehlerhaftes JSON, ein unbekannter Schlüssel, ein fehlerhafter regulärer Ausdruck, eine doppelte Regel-ID oder eine Regel ohne jede Bedingung – wird mit einer Warnung im Protokoll ignoriert, und die mitgelieferten Regeln bleiben in Kraft, sodass ein Tippfehler die Erkennung nie stillschweigend abschalten kann. Regeldateien werden beim Start gelesen; starten Sie korTTY nach dem Bearbeiten einer Datei neu.

### Schema der Regeldatei

Das Schema ist strikt: Jeder hier nicht aufgeführte Schlüssel ist ein Fehler, sodass falsch geschriebene Schlüssel gemeldet statt ignoriert werden.

```json
{
"kind": "CLAUDE_CODE | CODEX | GEMINI_CLI | MINIMAX_CODE | QWEN_CODE | OPENCODE | CURSOR_AGENT | AIDER | AMP | GOOSE | CRUSH | KIRO | CONTINUE | GROK | JULES | COPILOT | GROK_BUILD | ANTIGRAVITY",
  "version": 1,
  "comment": "optional free text, e.g. the agent version the rules were written against",
  "fallbackState": "IDLE | WORKING | BLOCKED | DONE | UNKNOWN (optional, default IDLE)",
  "rules": [
    {
      "id": "unique-rule-id",
      "state": "WORKING | BLOCKED | DONE | IDLE",
      "priority": 100,
      "region": { "bottomNonEmptyLines": 8 },
      "anyLineRegex": ["Java regex, matched per line"],
      "regex": "Java regex, matched against the whole region",
      "contains": ["literal text that must all be present"],
      "notContains": ["literal text that vetoes the rule"],
      "title": "Java regex matched against the window title",
      "alternateScreen": false
    }
  ]
}
```

| Feld | Erforderlich | Bedeutung |
|-------|----------|---------|
| `kind` | ja | Der Agent, den diese Datei beschreibt; muss zum Dateinamen passen (`claude-code`, `codex`, `gemini-cli`, `minimax-code`, `qwen-code`, `opencode`, `cursor-agent`, `aider`, `amp`, `goose`, `crush`, `kiro`, `continue`, `grok`, `jules`, `copilot`, `grok-build`, `antigravity`). |
| `version` | ja | Schemaversion; derzeit immer `1`. |
| `comment` | nein | Freitext für eigene Notizen, zum Beispiel gegen welche Agent-Version die Regeln geprüft wurden. |
| `fallbackState` | nein | Zustand, der gemeldet wird, solange der Agent-Prozess vorhanden ist, aber keine Regel passt. Standard ist `IDLE`; `UNKNOWN` macht die Erkennung strenger. |
| `rules` | ja | Die Liste der Regeln, ausgewertet nach absteigender `priority`; bei Gleichstand gilt die Reihenfolge in der Datei. Darf leer sein. |
| `rules[].id` | ja | Eindeutig innerhalb der Datei; Buchstaben, Ziffern, `.`, `_` und `-`. Wird in Diagnosen angezeigt, damit Sie sehen, welche Regel gegriffen hat. |
| `rules[].state` | ja | Der Zustand, den diese Regel meldet: `WORKING`, `BLOCKED`, `DONE` oder `IDLE`. |
| `rules[].priority` | ja | Höhere Werte werden zuerst geprüft. |
| `rules[].region` | nein | `{ "bottomNonEmptyLines": N }` beschränkt die Prüfung auf die unteren N nicht leeren Bildschirmzeilen; `0` oder fehlend bedeutet den ganzen Bildschirm. |
| `rules[].anyLineRegex` | nein | Reguläre Ausdrücke, die gegen jede Zeile der Region geprüft werden; die erste passende Zeile wird zum Beleg der Regel. |
| `rules[].regex` | nein | Ein regulärer Ausdruck, der gegen die mit Zeilenumbrüchen verbundene Region geprüft wird (mehrzeilig, Punkt passt auf Zeilenumbruch). |
| `rules[].contains` | nein | Literale Teilzeichenfolgen, die **alle** in der Region vorkommen müssen. |
| `rules[].notContains` | nein | Literale Teilzeichenfolgen, deren Vorkommen irgendwo in der Region die Regel aufhebt. |
| `rules[].title` | nein | Regulärer Ausdruck, der auf den vom Agent gesetzten Fenstertitel passen muss; passt nie, solange kein Titel empfangen wurde. |
| `rules[].alternateScreen` | nein | Falls vorhanden, muss das Alternativbildschirm-Flag des Bereichs diesem Wert entsprechen. |

Eine Regel muss mindestens eines von `anyLineRegex`, `regex`, `contains`, `title` oder `alternateScreen` angeben. Alle Bedingungen einer Regel müssen zutreffen, damit sie passt.

Reguläre Ausdrücke verwenden die **Java-Syntax** und werden mit Unicode-Groß-/Kleinschreibungsfaltung kompiliert; stellen Sie `(?i)` an den Anfang eines Musters, um es unabhängig von der Groß-/Kleinschreibung zu machen. Denken Sie daran, dass JSON verdoppelte Backslashes verlangt, `\s` wird also als `"\\s"` geschrieben. Muster in `anyLineRegex` werden innerhalb einer Zeile gesucht (sie müssen nicht die ganze Zeile treffen), und `^` und `$` verankern an der Zeile. Die meisten Agents halten ihre Statuszeile und Eingabeaufforderung am unteren Bildschirmrand, daher hält eine kleine `bottomNonEmptyLines`-Region die Regeln schnell und verhindert, dass ältere Ausgabe passt.

### Beispiel: mitgelieferte Claude-Code-Regeln

```json
{
  "kind": "CLAUDE_CODE",
  "version": 1,
  "comment": "Permission dialogs (Bash, Edit, Write, MCP) and the AskUserQuestion picker are BLOCKED; a tool call showing 'Running…' or the spinner status line with 'esc to interrupt' is WORKING; the empty input prompt with '? for shortcuts' is IDLE. The onboarding menus (theme picker, login method) intentionally match no rule. Claude Code does not use the alternate screen, so no rule depends on that flag. Re-verify against live sessions after an agent update.",
  "fallbackState": "IDLE",
  "rules": [
    {
      "id": "permission-prompt",
      "state": "BLOCKED",
      "priority": 1000,
      "region": { "bottomNonEmptyLines": 20 },
      "anyLineRegex": [
        "Do you want to (proceed|make this edit|create|run|allow)",
        "Do you want to allow this connection\\?",
        "Would you like to proceed\\?"
      ],
      "regex": "^\\s*[│┃]?\\s*❯?\\s*1\\.\\s*Yes\\b",
      "notContains": ["esc to interrupt"]
    },
    {
      "id": "question-picker",
      "state": "BLOCKED",
      "priority": 950,
      "region": { "bottomNonEmptyLines": 20 },
      "anyLineRegex": ["(?i)\\bEsc to cancel\\b"],
      "regex": "(?i)Enter to (select|confirm)|Arrow keys to navigate|↑/?↓ to navigate|Review your answers",
      "notContains": ["esc to interrupt", "Enter to set as default"]
    },
    {
      "id": "working-tool-running",
      "state": "WORKING",
      "priority": 910,
      "region": { "bottomNonEmptyLines": 12 },
      "anyLineRegex": ["^\\s*⎿\\s+Running…"]
    },
    {
      "id": "working-spinner",
      "state": "WORKING",
      "priority": 900,
      "region": { "bottomNonEmptyLines": 8 },
      "anyLineRegex": [
        "(?i)esc to interrupt",
        "^\\s*[✻✳✶✽✢·\\*]\\s+\\S.*…"
      ]
    },
    {
      "id": "idle-prompt",
      "state": "IDLE",
      "priority": 100,
      "region": { "bottomNonEmptyLines": 6 },
      "anyLineRegex": ["^\\s*[│┃]?\\s*[❯>]\\s*$", "\\?\\s+for shortcuts"],
      "notContains": ["esc to interrupt"]
    }
  ]
}
```

Die beiden BLOCKED-Regeln kombinieren ein zeilenweises Muster mit einem `regex` über die gesamte Region, sodass ein Berechtigungsdialog nur gemeldet wird, wenn sowohl die Frage als auch ihre Option *1. Yes* auf dem Bildschirm stehen, und eine Fragenauswahl nur zusammen mit ihrer Navigationsfußzeile – die Onboarding-Menüs einer frischen Installation zeigen eine ähnliche Liste, aber keine der beiden Fußzeilen, und dürfen nicht als blockiert zählen. Auch das `notContains`-Veto in den BLOCKED- und IDLE-Regeln ist wichtig: Während Claude Code arbeitet, bleibt seine Statuszeile zusammen mit älterem Dialogtext auf dem Bildschirm, und das Veto verhindert, dass die höher priorisierten Prompt-Regeln auf veraltete Zeilen anspringen.

### Regeln pflegen

Agents ändern ihre Oberflächen häufig, und eine Regel, die letzten Monat gepasst hat, kann nach einem Update stillschweigend nicht mehr passen. Die mitgelieferten Regeln von KorTTY sind deshalb durch **Bildschirm-Fixtures** abgesichert: Für jeden Agent bewahrt das Repository aufgezeichnete Terminalbildschirme mit dem Zustand und der Regel auf, die sie liefern müssen, und die Testsuite schlägt fehl, sobald eine mitgelieferte Regel nicht mehr zu ihrem Fixture passt oder eine Regel gar kein Fixture hat. Wenn Ihnen ein falscher oder fehlender Zustand auffällt, ist der nützlichste Bericht der sichtbare Bildschirmtext des Bereichs in diesem Moment, der Agent samt Version und der von Ihnen erwartete Zustand – daraus werden im nächsten Release ein neues Fixture und eine Regelkorrektur. Bis dahin können Sie die Regel mit einer lokalen Überschreibungsdatei sofort für sich selbst korrigieren.

Das Repository liefert genau dafür einen Recorder mit: `scripts/capture-coding-agent-fixtures.py` führt einen Agent in einem Pseudo-Terminal aus und reicht Ihre Tastatur durch, sodass sich die Sitzung wie ein normales Terminal verhält. Drücken Sie ++f12++ in jedem interessanten Moment – beim Berechtigungsdialog, beim Spinner, bei der leeren Eingabeaufforderung –, und der sichtbare Bildschirm wird in eine Fixture-Datei geschrieben; ++ctrl+bracket-right++ beendet die Aufzeichnung. Jede Datei beginnt mit einer Zeile `#! expect state=REVIEW rule=REVIEW`: Tragen Sie dort den erwarteten Zustand und die Regel-ID ein und legen Sie die Datei unter `src/test/resources/coding-agents/<kind>/` ab (`claude-code`, `codex`, `gemini-cli`, `minimax-code`, `qwen-code`, `opencode`, `cursor-agent`, `aider`, `amp`, `goose`, `crush`, `kiro`, `continue`, `grok`, `jules`, `copilot` oder `grok-build`). Von da an sichert die Testsuite die Regel gegen diesen Bildschirm, und eine Regeländerung, die ihn brechen würde, fällt vor dem Release auf.

## Agents per Skript steuern

Alles, was das Coding-Agents-Panel tut – Agents auflisten, ihren Zustand lesen, sie anweisen, ihre Rückfragen beantworten, sie umbenennen, einen neuen in einem geteilten Bereich starten – ist auch aus einem lokalen Skript oder von einem anderen Coding-Agent aus über korTTYs [Steuerungs-API](../reference/control-api.md) und deren Client [`kortty-cli`](../reference/cli.md) erreichbar. Sie ist standardmäßig aus; die `agent.*`-Befehle brauchen einen erkannten Agent, also muss auch die Erkennung eingeschaltet sein.

## Einschränkungen und Fehlerbehebung

- **Flatpak.** Im Flatpak-Paket läuft die lokale Shell über `flatpak-spawn` auf dem Host, sodass die Prozess-ID, die korTTY hält, zum Helfer auf der Sandbox-Seite gehört und der Prozessbaum des Agents nicht sichtbar ist. In lokalen Flatpak-Shells wird kein Agent erkannt.
- **Remote-Clients als Shell-Befehl.** Eine lokale Shell-Registerkarte, deren konfigurierter Shell-Befehl selbst `ssh` oder `mosh` ist, führt den Agent auf einem anderen Rechner aus; der Prozessbaum endet beim Client und es wird kein Agent erkannt. Dasselbe gilt für jede SSH- oder Mosh-Registerkarte.
- **Ungewöhnliche Wrapper.** Ein Agent, der über einen Starter gestartet wird, den korTTY nicht kennt – ein eigenes Shell-Skript, das per `exec` in eine anders benannte Binärdatei wechselt, ein Container oder ein Terminal-Multiplexer außerhalb der Registerkarte – wird nicht identifiziert, weil der Programmname im Prozessbaum keinem bekannten Agent zugeordnet werden kann.
- **Falscher oder flackernder Zustand.** Der Bildschirm des Agents hat sich geändert, oder ein Dialog wird auf eine Weise dargestellt, die die mitgelieferten Regeln nicht abdecken. Kopieren Sie die mitgelieferte Regeldatei nach `~/.kortty/coding-agents/<agent>.json`, passen Sie das Muster an, starten Sie korTTY neu und melden Sie den Bildschirmtext, damit die mitgelieferten Regeln korrigiert werden können.
- **Gar kein Zustand, obwohl der Agent läuft.** Prüfen Sie, dass der Schalter unter **Einstellungen → Terminal → Coding-Agents** eingeschaltet ist, dass die Registerkarte eine Lokale-Shell-Registerkarte ist, und suchen Sie im Protokoll nach einer *coding-agents*-Warnung – eine ungültige Überschreibungsdatei fällt auf die mitgelieferten Regeln zurück, aber eine mitgelieferte Regeldatei, die nie zu Ihrer Agent-Version passt, lässt den Agent unbestätigt.
- **Kein Badge unter GNOME.** Eine unveränderte GNOME Shell stellt keine Launcher-Zähler dar, und korTTY kann nicht erkennen, ob eine Dock-Erweiterung installiert ist, die es täte; die Zahl wird also gesendet, kann aber unsichtbar bleiben. Auf den Fenstertitel wird nur dort ausgewichen, wo korTTY sicher weiß, dass kein Symbol-Badge existiert (keine installierte Desktop-Datei, kein erreichbarer Sitzungsbus, `./gradlew run`).
- **Keine Benachrichtigung obwohl der Agent gewartet hat.** Benachrichtigungen werden für das aktuell betrachtete Paneel unterdrückt, für einen blockierten Zustand, der weniger als einen Moment andauerte, für einen zweiten Bericht derselben Transition und innerhalb von zehn Sekunden nach dem vorherigen für dieses Paneel; unter Linux muss ein Benachrichtigungsserver auf dem Sitzungsbus laufen, oder `notify-send` muss im PATH sein; unter macOS müssen Benachrichtigungen für korTTY in *System Einstellungen → Benachrichtigungen* erlaubt sein; eine [Unternehmensrichtlinie](../reference/enterprise-policy.md#rulefeatures) kann Desktop-Benachrichtigungen ausschalten; und die Benachrichtigungseinstellung unter **Einstellungen → Messaging → Coding-Agents** muss aktiviert sein.
- **Klicken einer Benachrichtigung bewirkt nichts.** Das Terminalfenster des Agenten wurde geschlossen, seit die Benachrichtigung angezeigt wurde, korTTY wurde außerhalb seines App-Bundles ohne den Entwicklungsbenachrichtigungs-Helper gestartet (der Legacy-Fallback gehört zum Script Editor), oder der Linux-Benachrichtigungsserver unterstützt keine Aktionen und es gibt kein `notify-send` von libnotify 0.7.10 oder neuer.
- **Der Prompt wurde abgelehnt.** Ein wartender Agent muss zuerst beantwortet werden (`YES`, `NO`, Enter oder Esc), und eine erste Zeile, die mit korTTYs eigenem KI-Shortcut-Befehl beginnt, muss umformuliert werden — siehe [Das Coding-Agents-Panel](#das-coding-agents-panel).
