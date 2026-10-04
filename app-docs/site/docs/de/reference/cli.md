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
| Linux deb / rpm | `/opt/kortty/bin/kortty-cli` |
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

## Einen Bereich auswählen

Jeder `pane`- und `agent`-Befehl nimmt **genau einen** Selektor, und jeder Selektor ist eine Option:

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

## Warten

Befehle, die warten – `pane wait-output`, `agent wait`, `agent prompt --wait-until`, `agent start --wait` – blockieren, bis die Bedingung erfüllt ist oder die Zeit abläuft, und enden bei Zeitüberschreitung mit Code 4. Jede Wartezeit hat serverseitig eine harte Obergrenze von zehn Minuten; eine längere Anforderung wird gekappt, und die Antwort sagt es.

Anfragen einer Verbindung laufen nacheinander. Ein Skript, das auf einen Bereich warten und gleichzeitig in einem anderen etwas tun will, startet daher einfach zwei Befehle nebenläufig – jeder öffnet seine eigene Verbindung. Bis zu acht Verbindungen dürfen gleichzeitig offen sein.

## Ereignisse beobachten

`kortty-cli events` abonniert die Coding-Agent-Ereignisse von korTTY und gibt sie, sobald sie auftreten, als ein JSON-Objekt pro Zeile aus, bis der Strom beendet wird.

```bash
kortty-cli events --kinds agent.state_changed --panes p1a2b3c4d --count 1
kortty-cli events --include-evidence --timeout 60000
```

`--kinds` und `--panes` nehmen kommagetrennte Listen und schränken die Zustellung ein; `--include-evidence` ergänzt jedes Ereignis um die Belege des Erkenners; `--count N` hält nach N Ereignissen an. Drei Dinge beenden den Strom: `--count` ist erreicht, die Frist aus `--timeout` läuft ab, oder Sie unterbrechen ihn. Die ersten beiden enden mit 0. Eine Unterbrechung wird **nicht** behandelt – es gibt bewusst keine Signalbehandlung –, der Prozess endet also mit der 128 + SIGINT = 130 der Shell, was ein CI-Job einplanen sollte, der den Strom in `timeout` einpackt.

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
| 1 | Die Anfrage war gültig, konnte aber nicht ausgeführt werden: Bereich, Tab oder Agent gibt es nicht; nicht verbunden; Schreiben fehlgeschlagen; der letzte Bereich lässt sich nicht schließen | Manchmal – das Feld `retryable` der Antwort sagt es |
| 2 | Die Anfrage selbst war falsch: ungültiger Selektor, unbekannter Tastenname, ungültiger regulärer Ausdruck, fehlender oder unzulässiger Parameter, unbekannter Befehl | Nein |
| 3 | Abgelehnt: die Steuerungs-API ist aus, die Unternehmensrichtlinie verbietet sie, das Token wurde abgelehnt, korTTY ist noch nicht bereit, oder zu viele Verbindungen sind offen | Nein, solange sich nichts ändert |
| 4 | Eine Wartezeit ist abgelaufen | Meistens |
| 130 | `kortty-cli events` wurde unterbrochen (Strg-C). Der Datenstrom hat keine Signalbehandlung, dies ist also die 128 + SIGINT der Shell selbst | — |

Jeder Fehler zeigt eine einzeilige Diagnose auf stderr im Format `kortty-cli: <message> (<code>)`, wobei `<code>` der stabile Wire-Code ist — `not_found`, `timeout`, `blocked_by_policy` — aus dem der obige Exit-Code abgeleitet wurde. Fügen Sie `--pretty` hinzu, um das ganze JSON-RPC-Fehlerobjekt zu erhalten, das das, was ein Skript überprüfen sollte, ist:

```bash
kortty-cli pane read --focused --pretty 2>err.json || code=$(jq -r .error.data.code err.json)
```

Fehler, die der Client selbst erkennt, sind die Ausnahme: eine unparsbare Befehlszeile, ein nicht laufendes korTTY, ein `--current`, das keiner Panes entspricht, und ein abgelaufener Client-Termin, der nie auf dem Server erreicht wurde – diese Fehler tragen keine Netzwerkcode und geben auf beiden Einstellungen einen reinen `kortty-cli: <message>` aus. Entscheiden Sie sich nach dem Exit-Code für diese Fälle.

## Die Installation prüfen

`kortty-cli --version` gibt die Version aus und endet mit 0. Es ist der einzige Befehl, der **kein** laufendes korTTY braucht, und damit das Richtige für eine Installationsprüfung oder einen CI-Rauchtest.

```bash
kortty-cli --version || echo 'kortty-cli is not installed or not on PATH'
```

## Hinweise fürs Skripten

* Auf stdout steht nichts außer dem Ergebnis des Befehls, `kortty-cli pane read --focused` lässt sich also sauber weiterleiten.
* Diagnosen, Warnungen und Fehlerobjekte gehen auf stderr.
* Das Authentifizierungstoken liest der Client aus `~/.kortty/control/endpoint.json`. Es gibt bewusst weder eine Option `--token` noch eine Umgebungsvariable: ein Token auf der Kommandozeile landet in der Prozessliste und in der Shell-Historie.
* korTTY protokolliert eine Zeile pro Aktion der CLI und zeigt eine Desktop-Benachrichtigung, sobald ein Programm zum ersten Mal in einen Bereich tippt. Ihr Skript ist absichtlich nicht unsichtbar.
