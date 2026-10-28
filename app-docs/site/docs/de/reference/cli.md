---
title: Steuerungs-CLI
---

# Steuerungs-CLI

`kortty-cli` ist der Kommandozeilenclient für korTTYs [Steuerungs-API](control-api.md). Er wird in jedem korTTY-Paket als zweiter Starter neben der Anwendung selbst ausgeliefert, findet das laufende korTTY von allein, authentifiziert sich und übersetzt die Antworten der API in etwas, mit dem ein Shell-Skript oder ein Coding-Agent arbeiten kann.

Er spricht ausschließlich mit einem korTTY **auf demselben Rechner** und nur, solange dort die Steuerungs-API eingeschaltet ist. Was die API verweigert, verweigert auch die CLI.

!!! note "Schalten Sie die API zuerst ein"
    Ohne den Haken bei **Einstellungen › Terminal › Steuerungs-API** gibt es nichts, womit sich verbinden ließe, und jeder Befehl endet mit Code 3. Was Sie einschalten und was es kann, steht unter [Steuerungs-API](control-api.md).

## Wo der Befehl liegt

`kortty-cli` ist kein Skript, sondern ein echter Starter im gepackten Anwendungsabbild, der die mitgelieferte JVM startet – Java muss also nicht im `PATH` liegen.

| Paket | Pfad |
| --- | --- |
| macOS-App-Bundle | `/Applications/korTTY.app/Contents/MacOS/kortty-cli` |
| Windows-Installation | `C:\Program Files\korTTY\kortty-cli.exe` |
| Linux-Distributionen: deb / rpm | `/opt/kortty/bin/kortty-cli` |
| Arch (pacman) | `/usr/bin/kortty-cli` → `/usr/lib/kortty/bin/kortty-cli` |
| Flatpak | `flatpak run --command=kortty-cli io.github.chardonnay.korTTY` |
| Portabeler Archiv (Linux) | `<extracted directory>/korTTY/bin/kortty-cli` |
| Portabeler Archiv (Windows) | `<extracted directory>\korTTY\kortty-cli.exe` — im Stammverzeichnis des Bildes, nicht unter `bin` |
| Portabeler Archiv (macOS) | `<extracted directory>/korTTY.app/Contents/MacOS/kortty-cli` |

Nur das pacman-Paket legt den Befehl für Sie in den `PATH`. Überall sonst nehmen Sie das Verzeichnis in den `PATH` auf oder verlinken den Starter in ein Verzeichnis, das schon darin liegt:

```bash
# macOS
sudo ln -s /Applications/korTTY.app/Contents/MacOS/kortty-cli /usr/local/bin/kortty-cli

# Linux deb/rpm
sudo ln -s /opt/kortty/bin/kortty-cli /usr/local/bin/kortty-cli
```

```powershell
# Windows, for the current user
[Environment]::SetEnvironmentVariable(
    'Path', $env:Path + ';C:\Program Files\korTTY', 'User')
```

Der Starter heißt bewusst **nicht** `kortty`: dieser Name gehört dem grafischen Starter (`/usr/bin/kortty` unter Arch, `korTTY` sonst), und unter macOS und Windows, deren Dateisysteme Groß- und Kleinschreibung ignorieren, würde ein zweiter Starter namens `kortty` mit ihm kollidieren.

!!! note "Java-Distributionsarchive"
    Die Archivdateien `korTTY-Java-*.zip` und `.tar` enthalten keine Startprogramme außer dem grafischen. Von diesen starten Sie den Client über `java -cp korTTY-<version>.jar de.kortty.cli.KorttyCli <command>`.

## Aufbau eines Befehls

```bash
kortty-cli <group> <command> [options]
```

Die Gruppen sind `pane`, `tab`, `window`, `agent`, `events`, `notify`, `raw`, `ping` und `schema`, dazu `mcp`, das kein einzelner Aufruf ist, sondern ein dauerhaft laufender Server (siehe [MCP-Clients bedienen](#mcp-clients-bedienen)). Innerhalb einer Gruppe ist der Befehlsname die API-Methode nach dem Punkt, deren Unterstriche als Bindestriche geschrieben werden – `pane.send_text` wird zu `pane send-text`. Vier Methoden ergeben sich nicht aus ihrem Namen und sind es wert, gemerkt zu werden: `api.schema` ist `schema`, `events.subscribe` ist `events`, `notification.show` ist `notify`, und alles ohne eigenen Befehl – `events.unsubscribe`, `pane.resolve` – erreichen Sie über `raw`.

Alles, was ein Befehl braucht, ist eine Option. Positionsargumente gibt es nur für die Tastennamen von `pane send-keys` und `agent send-keys` sowie für Methode und JSON von `raw`.

```bash
kortty-cli ping                                   # is a korTTY listening?
kortty-cli pane list                              # every open pane
kortty-cli pane read --focused --recent --lines 200
kortty-cli pane run --pane p1a2b3c4d --command 'make test'
kortty-cli pane wait-output --pane p1a2b3c4d --contains 'BUILD SUCCESSFUL' --timeout-ms 300000
kortty-cli raw events.unsubscribe '{"subscription":"s1"}'
```

`kortty-cli schema` gibt die vollständige maschinenlesbare Befehlsoberfläche aus – jeden Befehl, seine Optionen, seine Ergebnisform, seine Fehler und ein ausgearbeitetes Beispiel, direkt aus dem laufenden korTTY. Parameter, Ergebnisse und Fehler entstehen aus denselben Deklarationen, die der Server ausliefert, und die Zeile `cli` jedes Eintrags wird vor der Auslieferung gegen den Parser genau dieses Clients geprüft; beide beschreiben also die Version, mit der Sie gerade sprechen. Nutzen Sie sie statt zu raten, besonders aus einem Skript, das über Versionen hinweg funktionieren soll.

## Befehlsreferenz

Jede Option, die der Client akzeptiert, ist entweder eine der unten aufgeführten globalen Optionen oder gehört zu einer der Befehle in der nachfolgenden Tabelle. Eine Option, die ein Befehl nicht auflistet, wird mit Exit 2 abgelehnt, bevor etwas an korTTY gesendet wird.

### Globale Optionen

| Flag | Bedeutung |
| --- | --- |
| `--config-dir <path>` | Sucht nach `control/endpoint.json` unterhalb von `<path>` anstelle von `~/.kortty`. |
| `--timeout <ms>` | Die Client-Deadline für den gesamten Aufruf. Der Standardwert beträgt 5000 ms; ein Befehl, der auf korTTY wartet, erhält seine Wartezeit plus fünf Sekunden zusätzlich, und `events` hat keine Deadline, es sei denn, Sie geben eine an. |
| `--raw` | Gibt einfachen Text statt JSON aus, für die Befehle, die Text enthalten: `pane read` und `agent explain`. |
| `--pretty` | Indentiert das JSON anstelle einer kompakten Zeile. |
| `-q`, `--quiet` | Gibt nichts aus; der Exit-Code ist die einzige Antwort. |
| `--version` | Gibt die Client-Version und die Protokollversion aus, ohne korTTY zu kontaktieren. |
| `-h`, `--help` | Gibt die Hilfe für eine Gruppe oder einen Befehl aus, ohne korTTY zu kontaktieren. |

### Befehle

| Befehl | API method | Optionen und Hinweise |
| --- | --- | --- |
| `ping` | `ping` | Überprüft, ob korTTY antwortet. |
| `schema [--method <name>]` | `api.schema` | Druckt das maschinenlesbare API-Schema, oder einen seiner Methoden. |
| `events [--kinds a,b] [--panes p1,p2] [--include-evidence] [--count N]` | `events.subscribe` | Sendet ein Ereignis pro Zeile; siehe [Beobachten von Ereignissen](#ereignisse-beobachten). |
| `window list` | `window.list` | Listet die offenen Fenster. |
| `tab list [--window <id>]` | `tab.list` | Listet die Tabs eines Fensters oder aller Fenster. |
| `tab focus --tab <id>` | `tab.focus` | Hebt das Fenster an und wählt den Tab aus. |
| `tab create --connection <id or name> [--isolation none, process or sandbox] [--incognito] [--window <id>]` | `tab.create` | Öffnet eine gespeicherte Verbindung in einem neuen Tab; siehe [Öffnen einer Verbindung](#eine-verbindung-offnen). |
| `tab close`, `tab rename` | reserviert | Lokal abgelehnt mit Exit 2: nicht in dieser Version implementiert. |
| `pane list [--tab <id>] [--window <id>] [--local-shell-only]` | `pane.list` | Listet die offenen Panes, mit ihrer Isolation und ob sie Inkognito sind. |
| `pane current` | `pane.current` | Druckt das Pane, auf das der Benutzer schaut. |
| `pane get <selector>` | `pane.get` | Druckt ein Pane. |
| `pane focus <selector> [--no-raise]` | `pane.focus` | Fordert den Fokus für ein Pane an; `--no-raise` hebt das zugehörige Fenster nicht hervor. |
| `pane read <selector> [--visible, --recent or --detection] [--lines N] [--raw]` | `pane.read` | Liest den Bildschirm (`--visible`, Standard), die Scrollback und den Bildschirm (`--recent`, 200 Zeilen, sofern `--lines` nicht anders angibt), oder die Erkennung des Coding-Agenten. |
| `pane send-text <selector> --text <s> or --stdin [--submit] [--bracketed auto, never or always] [--allow-shortcut-conflict]` | `pane.send_text` | Gibt Text in das Pane ein. `--stdin` liest den Text von der Standardeingabe; `--submit` drückt anschließend Enter; `--bracketed` verwendet standardmäßig `auto`. |
| `pane run <selector> --command <s>` | `pane.run` | Gibt einen einzeiligen Befehl ein und drückt Eingabetaste. |
| `pane send-keys <selector> <key>...` | `pane.send_keys` | Sendet Tastennamen wie `enter`, `tab`, `up` oder `ctrl+c`; ein unbekannter Name wird abgelehnt, bevor etwas gesendet wird. |
| `pane wait-output <selector> (--regex <r> or --contains <s>) [--visible or --recent] [--lines N] [--timeout-ms N] [--poll-ms N]` | `pane.wait_output` | Wartet, bis die Ausgabe übereinstimmt. Standardmäßig durchsucht es die aktuelle Ausgabe, wartet 30 Sekunden, sofern `--timeout-ms` nichts anderes angibt, und prüft alle 150 ms, sofern `--poll-ms` nichts anderes angibt. |
| `pane split <selector> [--vertical or --horizontal] [--no-focus]` | `pane.split` | Teilt ein lokales Shell-Fenster auf und gibt das neue Fenster aus. |
| `pane close <selector>` | `pane.close` | Schließt ein geteiltes Fenster; das letzte Fenster eines Tabs wird abgelehnt. |
| `agent list [--state S] [--kind K] [--tab <id>] [--window <id>]` | `agent.list` | Listet die Codierungsagenten auf, am dringendsten zuerst. |
| `agent get <selector>` | `agent.get` | Gibt einen Agenten aus. |
| `agent explain <selector> [--raw]` | `agent.explain` | Erklärt, warum der Agent in seinem Zustand ist; `--raw` gibt nur den Text aus. |
| `agent wait <selector> --until blocked, done, working, idle or any [--timeout-ms N]` | `agent.wait` | Wartet, bis der Agent einen Zustand erreicht; die Wartezeit beträgt 60 Sekunden, es sei denn `--timeout-ms` sagt etwas anderes. |
| `agent prompt <selector> --text <s> or --stdin [--wait-until done, idle, blocked or any] [--timeout-ms N]` | `agent.prompt` | Sendet einen Prompt. Mit `--wait-until` wartet es ebenfalls, 60 Sekunden, sofern `--timeout-ms` nichts anderes sagt. |
| `agent send-keys <selector> <key>...` | `agent.send_keys` | Antwortet auf den Prompt des Agenten mit Tastennamen. |
| `agent rename <selector> --alias <s>` | `agent.rename` | Setzt einen Alias des Agenten; ein leerer Alias löscht ihn. |
| `agent start (<selector> or --split-from <selector> [--vertical or --horizontal]) --kind <k> [--command <s>] [--prompt <s>] [--wait] [--until done or idle] [--timeout-ms N]` | `agent.start` | Startet einen Coding-Agenten in einem Pane, oder in einer neuen Aufteilung eines, und wartet, bis korTTY ihn registriert hat. `--command` wird an Leerzeichen in Argumente aufgeteilt und nicht über eine Shell ausgeführt. Mit `--wait` beträgt die Standardwartezeit nach dem Prompt 300 Sekunden. |
| `notify --title <s> --body <s>` | `notification.show` | Hebt eine Desktop-Benachrichtigung hervor. |
| `mcp` | MCP über stdio | Bietet die in [MCP-Clients bedienen](#mcp-clients-bedienen) beschriebenen Tools an. |
| `raw <method> [<json>]` oder `raw <method> --stdin` | beliebige | Ruft jede Methode auf. Das optionale JSON-Objekt sind seine Parameter; mit `--stdin` ist die Standardeingabe. |

Die `--kind`-Werte von `agent list` und `agent start` sind `claude-code`, `codex`, `gemini-cli`, `minimax-code`, `qwen-code`, `opencode`, `cursor-agent`, `aider`, `amp`, `goose`, `crush`, `kiro`, `continue`, `grok`, `jules`, `copilot`, `grok-build`, `antigravity` und `cline`. Die `--state`-Werte von `agent list` sind `blocked`, `working`, `done`, `idle` und `unknown`.

`tab close` und `tab rename` werden von der API als reserviert aufgeführt; `kortty-cli` verweigert sie selbst, sodass sie nie korTTY erreichen.

## Einen Bereich auswählen

Jeder Befehl, der ein Paneel anspricht, benötigt **genau einen** Selektor, und jeder Selektor ist ein Flag. `pane list`, `pane current` und `agent list` benötigen keinen, und `agent start` akzeptiert `--split-from` anstelle eines Selektors:

| Selektor | Bedeutung |
| --- | --- |
| `--pane p1a2b3c4d` | Dieser Bereich, der unter den offenen eindeutig sein muss |
| `--pane w1:t9f3a…:p1a2b3c4d` | Die vollständige Adresse |
| `--tab t9f3a…` | Der fokussierte Bereich dieses Tabs |
| `--focused` | Der Bereich, den Sie gerade ansehen |
| `--current` | Der Bereich, **in dem** der Befehl läuft |

Keinen oder mehr als einen anzugeben ist ein Syntaxfehler (Code 2), der die vier benennt; ein bloßes `p1a2b3c4d` oder `@focused` an der Stelle einer Option ist ebenfalls ein Syntaxfehler.

`--current` löst der Client auf, nicht der Server: er geht seine eigene Prozessabstammung durch und fragt korTTY, welcher Bereich eine dieser Prozesskennungen besitzt. Deshalb funktioniert es ohne jede Vorbereitung aus einer Untershell, einer `Makefile`-Regel oder einem verschachtelten Skript, und deshalb scheitert es dort, wo es nicht funktionieren kann, mit einer klaren Meldung, statt den falschen Bereich anzusprechen.

!!! warning "`--current` braucht eine lokale Shell"
    Es vergleicht mit der Prozesskennung der lokalen Shell eines Bereichs. In einer SSH-Sitzung, in einem Container oder im Flatpak-Paket – wo lokale Shells über `flatpak-spawn` auf dem Wirtssystem laufen und korTTY deren Prozesskennungen nie sieht – gibt es nichts zu vergleichen, und `--current` kann nicht auflösen. Sprechen Sie den Bereich dort ausdrücklich an.

## Eine Verbindung öffnen

`kortty-cli tab create --connection <id|name>` öffnet eine gespeicherte oder Teamwork-Verbindung in einem neuen Tab und gibt den Tab aus. `--isolation none|process|sandbox` führt sie mit dieser [Sitzungs-Isolation](../features/session-isolation.md) statt der eigenen der Verbindung aus, `--incognito` öffnet sie als Inkognito-Sitzung, und `--window w2` wählt das Fenster. Die Mindest-Isolation der Organisation wird nie unterschritten: wer weniger verlangt, wird mit Exit-Code 3 abgelehnt. Braucht die Anmeldung eine Rückfrage, meldet die Antwort `"pending": true`, und korTTY fragt Sie, bevor es den Tab öffnet.

```bash
kortty-cli tab create --connection web-01 --isolation sandbox --incognito
```

## Warten

Befehle, die warten – `pane wait-output`, `agent wait`, `agent prompt --wait-until`, `agent start --wait` – blockieren, bis die Bedingung erfüllt ist oder die Zeit abläuft, und enden bei Zeitüberschreitung mit Code 4. Jede Wartezeit hat serverseitig eine harte Obergrenze von zehn Minuten; eine längere Anforderung wird gekappt, und die Antwort sagt es.

Anfragen einer Verbindung laufen nacheinander. Ein Skript, das auf einen Bereich warten und gleichzeitig in einem anderen etwas tun will, startet daher einfach zwei Befehle nebenläufig – jeder öffnet seine eigene Verbindung. Bis zu acht Verbindungen dürfen gleichzeitig offen sein.

## Ereignisse beobachten

`kortty-cli events` abonniert die Coding-Agent-Ereignisse von korTTY und gibt sie, sobald sie auftreten, als ein JSON-Objekt pro Zeile aus, bis der Strom beendet wird.

```bash
kortty-cli events --kinds agent.state_changed --panes p1a2b3c4d --count 1
kortty-cli events --include-evidence --timeout 60000
```

`--kinds` und `--panes` akzeptieren kommagetrennte Listen und schränken ein, was geliefert wird. Die Arten sind `agent.added`, `agent.state_changed`, `agent.seen`, `agent.alias_changed`, `agent.removed`, `agent.evidence` und `events.overflow`. Ohne `--kinds` wird jede Art außer `agent.evidence` gesendet. `agent.evidence` wird nur mit `--include-evidence` gesendet, und höchstens einmal pro Sekunde pro Paneel. `events.overflow` wird immer gesendet und meldet, wie viele Frames verloren gingen, wenn der Stream hinterherhinkt. `--count N` stoppt nach N Ereignissen. Drei Dinge beenden den Stream: `--count` wird erreicht, die `--timeout` passiert oder Sie unterbrechen ihn. Die ersten beiden beenden mit 0. Ein Interrupt wird **nicht** behandelt — es gibt absichtlich keinen Signalhandler, sodass der Prozess mit dem Shell-128 + SIGINT = 130 endet, was in einem CI-Job, der den Stream in `timeout` einwickelt, berücksichtigt werden sollte.

Ohne `--count` und ohne `--timeout` hat der Strom überhaupt keine Frist und läuft, bis korTTY endet oder Sie ihn anhalten.

## MCP-Clients bedienen

`kortty-cli mcp` macht den Client zu einem [Model Context Protocol](https://modelcontextprotocol.io/)-Server, sodass ein KI-Assistent, der MCP spricht, Ihre korTTY-Bereiche lesen kann. Er unterstützt ausschließlich den MCP-Transport über stdio: Der Host des Assistenten startet `kortty-cli mcp` als Kindprozess und tauscht mit ihm über stdin und stdout zeilenweise JSON-RPC-Nachrichten aus. Es gibt keinen Netzwerk-Listener; der Prozess erreicht korTTY über denselben lokalen Endpunkt der Steuerungs-API wie jeder andere Befehl und meldet sich als `mcp`-Client an, sodass korTTY auf jede Anfrage die [MCP-Regeln](control-api.md#mcp-clients) anwendet.

korTTY muss es erlauben: Die Steuerungs-API muss eingeschaltet sein, der separate Schalter **MCP-Server** unter **Einstellungen › Terminal › Steuerungs-API** muss eingeschaltet sein, und die [Unternehmensrichtlinie](enterprise-policy.md) darf `mcp-server` nicht verweigern. Solange eine dieser Bedingungen nicht erfüllt ist, liefert jeder Tool-Aufruf einen Fehler mit der Begründung, und der Assistent kann die Tools trotzdem auflisten.

Die meisten MCP-Hosts werden mit einem JSON-Eintrag wie diesem konfiguriert; tragen Sie in `command` den vollständigen Pfad von `kortty-cli` aus der Tabelle oben ein, falls er nicht in Ihrem `PATH` liegt:

```json
{
  "mcpServers": {
    "kortty": {
      "command": "kortty-cli",
      "args": ["mcp"]
    }
  }
}
```

Der Server bietet diese Tools an. Jedes Argument trägt den Namen des API-Parameters, den es füllt, und `pane` nimmt eine Bereichs-ID aus `pane_list` oder `@focused` entgegen:

| Tool | Ruft auf | Angeboten |
| --- | --- | --- |
| `pane_list` | `pane.list` | Immer |
| `pane_read` | `pane.read` | Immer |
| `pane_wait_output` | `pane.wait_output` | Immer |
| `tab_list` | `tab.list` | Immer |
| `agent_list` | `agent.list` | Immer |
| `pane_send_text` | `pane.send_text` | Nur solange korTTY **Schreib-Tools erlauben** als eingeschaltet meldet |
| `pane_run` | `pane.run` | Nur solange korTTY **Schreib-Tools erlauben** als eingeschaltet meldet |
| `pane_send_keys` | `pane.send_keys` | Nur solange korTTY **Schreib-Tools erlauben** als eingeschaltet meldet |

Die Lese-Tools sind als schreibgeschützt und die Schreib-Tools als destruktiv gekennzeichnet, sodass ein Host, der vor destruktiven Tools nachfragt, vor jedem von ihnen nachfragt. korTTY fragt ebenfalls nach, unabhängig vom Host: Jeder Aufruf eines Schreib-Tools öffnet in korTTY eine [Rückfrage zur Zustimmung](control-api.md#schreibvorgange-eines-mcp-clients), und ein solcher Aufruf wartet bis zu 70 Sekunden auf Ihre Antwort, bevor er aufgibt. Ein Tool-Ergebnis ist die JSON-Antwort der API als Text; korTTY hat die ihm bekannten Geheimnisse bereits maskiert und die Größe begrenzt. Eine Verweigerung, eine abgelehnte oder unbeantwortete Rückfrage, ein beendetes korTTY oder ein ausgeschalteter MCP-Server kommt als Tool-Fehler mit dem Grund zurück, zum Beispiel `method_not_allowed_for_mcp` oder `mcp_write_denied`, und der Assistent kann ihn Ihnen anzeigen. Die Tool-Liste wird bei jeder `tools/list`-Anfrage einmal gelesen, daher wirkt das Ein- oder Ausschalten der Schreib-Tools erst, wenn der Assistent die Tools das nächste Mal auflistet.

Jeder Tool-Aufruf öffnet eine eigene Verbindung, daher muss der Assistent nach einem Neustart von korTTY nicht neu gestartet werden. Aufrufe werden nacheinander bearbeitet: Ein langes `pane_wait_output` verzögert die nächste Antwort, bis es zurückkehrt. stdout enthält ausschließlich Protokollnachrichten; `kortty-cli mcp` schreibt pro Anfrage eine Diagnosezeile nach stderr, die die Methode oder das Tool nennt, aber nie deren Argumente oder Ergebnisse, und `--quiet` unterdrückt diese Zeilen. `--config-dir` funktioniert wie bei jedem anderen Befehl.

!!! warning "Alles, was ein Tool zurückgibt, ist nicht vertrauenswürdiger Terminaltext"
    Ein entfernter Host, eine Logdatei oder eine Webseite, die in einem Terminal angezeigt wird, kann Text enthalten, der wie Anweisungen an einen KI-Assistenten aussehen soll. Die Tool-Beschreibungen fordern den Assistenten auf, Ergebnisse als Daten zu behandeln, doch das ist eine Bitte und keine Garantie; lassen Sie die Schreib-Tools daher ausgeschaltet, solange Sie sie nicht brauchen. Die MCP-Regeln schränken ein, was dieser Server freigibt; sie sind keine Sandbox gegen einen Assistenten, der auch Shell-Befehle als Sie ausführen kann, denn jedes Ihrer Programme kann das Token lesen und sich als gewöhnlicher `cli`-Client verbinden.

## Exit-Codes

Der Exit-Code kommt vom Server, nicht aus einer Tabelle im Client, damit beide nie auseinanderlaufen können.

| Code | Bedeutung | Wiederholen? |
| --- | --- | --- |
| 0 | Erfolg | — |
| 1 | Die Anfrage war gültig, konnte jedoch nicht ausgeführt werden: kein solches Fenster, Tab, Paneel, Agent oder Verbindung, oder ein `--current`, das kein Paneel findet; ein veralteter `instance`; nicht verbunden; der Schreibvorgang fehlgeschlagen; ein Agent, der zuerst beantwortet werden muss; das letzte Paneel kann nicht geschlossen werden; ein Split, den das Paneel nicht aufnehmen kann oder der abgebrochen wurde; ein interner Fehler in korTTY | Manchmal — das Feld `retryable` der Antwort sagt |
| 2 | Die Anfrage selbst war falsch: ein schlechter Selektor, ein unbekannter Schlüsselname, ein ungültiger regulärer Ausdruck, ein fehlender oder außerhalb des Bereichs liegender Parameter, ein leerer Text, ein unbekannter Befehl, ein Selektor, der mehr als ein Paneel trifft, ein Verbindungsname, den zwei Verbindungen teilen, oder ein Text, dessen erste Zeile die eigene Tastenkombination von korTTY absorbieren würde | Nein |
| 3 | Abgelehnt: die Steuerungs-API ist deaktiviert, Unternehmensrichtlinien verweigern sie oder blockieren die Verbindung, das Token wurde abgelehnt, ein MCP-Client darf den Aufruf nicht tätigen oder sein Schreibvorgang wurde verweigert, oder zu viele Verbindungen sind offen | Nein, bis Sie etwas ändern; ein korTTY, das noch nicht bereit ist oder zu viele Verbindungen offen hat, lohnt sich kurz danach erneut zu versuchen |
| 4 | Eine Wartezeit ist abgelaufen | Meistens |
| 130 | `kortty-cli events` wurde unterbrochen (Strg-C). Der Datenstrom hat keine Signalbehandlung, dies ist also die 128 + SIGINT der Shell selbst | — |

Jeder Fehler zeigt eine einzeilige Diagnose auf stderr im Format `kortty-cli: <message> (<code>)`, wobei `<code>` der stabile Wire-Code ist — `not_found`, `timeout`, `blocked_by_policy` — aus dem der obige Exit-Code abgeleitet wurde. Fügen Sie `--pretty` hinzu, um das ganze JSON-RPC-Fehlerobjekt zu erhalten, das das, was ein Skript überprüfen sollte, ist:

```bash
kortty-cli pane read --focused --pretty 2>err.json || code=$(jq -r .error.data.code err.json)
```

Fehler, die der Client selbst erkennt, sind die Ausnahme: eine unparsbare Befehlszeile, ein nicht laufendes korTTY, ein `--current`, das keiner Panes entspricht, und ein abgelaufener Client-Termin, der nie auf dem Server erreicht wurde – diese Fehler tragen keine Netzwerkcode und geben auf beiden Einstellungen einen reinen `kortty-cli: <message>` aus. Entscheiden Sie sich nach dem Exit-Code für diese Fälle.

## Die Installation prüfen

`kortty-cli --version` gibt die Version aus und beendet sich mit 0. `--version` und `--help` antworten beide ohne laufendes korTTY, was `--version` zur richtigen Wahl für einen Installationscheck oder einen CI-Feuchtigkeits-Test macht.

```bash
kortty-cli --version || echo 'kortty-cli is not installed or not on PATH'
```

## Hinweise fürs Skripten

* Auf stdout steht nichts außer dem Ergebnis des Befehls, `kortty-cli pane read --focused` lässt sich also sauber weiterleiten.
* Diagnosen, Warnungen und Fehlerobjekte gehen auf stderr.
* Das Authentifizierungstoken liest der Client aus `~/.kortty/control/endpoint.json`. Es gibt bewusst weder eine Option `--token` noch eine Umgebungsvariable: ein Token auf der Kommandozeile landet in der Prozessliste und in der Shell-Historie.
* korTTY protokolliert eine Zeile pro Aktion der CLI und zeigt eine Desktop-Benachrichtigung, sobald ein Programm zum ersten Mal in einen Bereich tippt. Ihr Skript ist absichtlich nicht unsichtbar.
