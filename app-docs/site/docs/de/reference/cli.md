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
| Portables Archiv | `<entpacktes Verzeichnis>/bin/kortty-cli` |

Nur das pacman-Paket legt den Befehl für Sie in den `PATH`. Überall sonst nehmen Sie das Verzeichnis in den `PATH` auf oder verlinken den Starter in ein Verzeichnis, das schon darin liegt:

```bash
# macOS
sudo ln -s /Applications/korTTY.app/Contents/MacOS/kortty-cli /usr/local/bin/kortty-cli

# Linux deb/rpm
sudo ln -s /opt/kortty/bin/kortty-cli /usr/local/bin/kortty-cli
```

```powershell
# Windows, für den aktuellen Benutzer
[Environment]::SetEnvironmentVariable(
    'Path', $env:Path + ';C:\Program Files\korTTY', 'User')
```

Der Starter heißt bewusst **nicht** `kortty`: dieser Name gehört dem grafischen Starter (`/usr/bin/kortty` unter Arch, `korTTY` sonst), und unter macOS und Windows, deren Dateisysteme Groß- und Kleinschreibung ignorieren, würde ein zweiter Starter namens `kortty` mit ihm kollidieren.

!!! note "Java-Distributionsarchive"
    Die Archive `korTTY-Java-*.zip` und `.tar` enthalten außer dem grafischen keinen weiteren Starter. Aus ihnen starten Sie den Client als `java -cp korTTY-<version>.jar de.kortty.cli.KorttyCli <befehl>`.

## Aufbau eines Befehls

```bash
kortty-cli <gruppe> <befehl> [selektor] [optionen]
```

Die Gruppen entsprechen der API: `pane`, `tab`, `window`, `agent`, `events`, `notification`, dazu `ping` und `schema`. Ein Befehlsname ist die API-Methode, deren Unterstriche als Bindestriche geschrieben werden – `pane.send_text` wird zu `pane send-text`.

```bash
kortty-cli ping                                   # lauscht ein korTTY?
kortty-cli pane list                              # alle offenen Bereiche
kortty-cli pane read @focused --mode recent --lines 200
kortty-cli pane run p1a2b3c4d --command 'make test'
kortty-cli pane wait-output p1a2b3c4d --contains 'BUILD SUCCESSFUL' --timeout-ms 300000
```

`kortty-cli schema` gibt die vollständige maschinenlesbare Befehlsoberfläche aus – jeden Befehl, seine Optionen, seine Ergebnisform, seine Fehler und ein ausgearbeitetes Beispiel, direkt aus dem laufenden korTTY. Sie wird aus denselben Deklarationen erzeugt, die der Server ausliefert, ist also auf eine Weise verbindlich, wie es eine Handbuchseite nicht sein kann; nutzen Sie sie statt zu raten, besonders aus einem Skript, das über Versionen hinweg funktionieren soll.

## Einen Bereich auswählen

Überall, wo ein Bereich erwartet wird, können Sie schreiben:

| Selektor | Bedeutung |
| --- | --- |
| `p1a2b3c4d` | Dieser Bereich, der unter den offenen eindeutig sein muss |
| `w1:t9f3a…:p1a2b3c4d` | Die vollständige Adresse |
| `t9f3a…` | Der fokussierte Bereich dieses Tabs |
| `@focused` | Der Bereich, den Sie gerade ansehen |
| `--current` | Der Bereich, **in dem** der Befehl läuft |

`--current` löst der Client auf, nicht der Server: er geht seine eigene Prozessabstammung durch und fragt korTTY, welcher Bereich eine dieser Prozesskennungen besitzt. Deshalb funktioniert es ohne jede Vorbereitung aus einer Untershell, einer `Makefile`-Regel oder einem verschachtelten Skript, und deshalb scheitert es dort, wo es nicht funktionieren kann, mit einer klaren Meldung, statt den falschen Bereich anzusprechen.

!!! warning "`--current` braucht eine lokale Shell"
    Es vergleicht mit der Prozesskennung der lokalen Shell eines Bereichs. In einer SSH-Sitzung, in einem Container oder im Flatpak-Paket – wo lokale Shells über `flatpak-spawn` auf dem Wirtssystem laufen und korTTY deren Prozesskennungen nie sieht – gibt es nichts zu vergleichen, und `--current` kann nicht auflösen. Sprechen Sie den Bereich dort ausdrücklich an.

## Warten

Befehle, die warten – `pane wait-output`, `agent wait`, `agent prompt --wait-until`, `agent start --wait` – blockieren, bis die Bedingung erfüllt ist oder die Zeit abläuft, und enden bei Zeitüberschreitung mit Code 4. Jede Wartezeit hat serverseitig eine harte Obergrenze von zehn Minuten; eine längere Anforderung wird gekappt, und die Antwort sagt es.

Anfragen einer Verbindung laufen nacheinander. Ein Skript, das auf einen Bereich warten und gleichzeitig in einem anderen etwas tun will, startet daher einfach zwei Befehle nebenläufig – jeder öffnet seine eigene Verbindung. Bis zu acht Verbindungen dürfen gleichzeitig offen sein.

## Exit-Codes

Der Exit-Code kommt vom Server, nicht aus einer Tabelle im Client, damit beide nie auseinanderlaufen können.

| Code | Bedeutung | Wiederholen? |
| --- | --- | --- |
| 0 | Erfolg | — |
| 1 | Die Anfrage war gültig, konnte aber nicht ausgeführt werden: Bereich, Tab oder Agent gibt es nicht; nicht verbunden; Schreiben fehlgeschlagen; der letzte Bereich lässt sich nicht schließen | Manchmal – das Feld `retryable` der Antwort sagt es |
| 2 | Die Anfrage selbst war falsch: ungültiger Selektor, unbekannter Tastenname, ungültiger regulärer Ausdruck, fehlender oder unzulässiger Parameter, unbekannter Befehl | Nein |
| 3 | Abgelehnt: die Steuerungs-API ist aus, die Unternehmensrichtlinie verbietet sie, das Token wurde abgelehnt, korTTY ist noch nicht bereit, oder zu viele Verbindungen sind offen | Nein, solange sich nichts ändert |
| 4 | Eine Wartezeit ist abgelaufen | Meistens |

Jeder Fehlschlag gibt zusätzlich ein JSON-Fehlerobjekt auf stderr aus, das eine stabile Zeichenkette `code` trägt – ein Skript kann also auf die Ursache verzweigen statt auf den Meldungstext.

## Die Installation prüfen

`kortty-cli --version` gibt die Version aus und endet mit 0. Es ist der einzige Befehl, der **kein** laufendes korTTY braucht, und damit das Richtige für eine Installationsprüfung oder einen CI-Rauchtest.

```bash
kortty-cli --version || echo 'kortty-cli ist nicht installiert oder nicht im PATH'
```

## Hinweise fürs Skripten

* Auf stdout steht nichts außer dem Ergebnis des Befehls, `kortty-cli pane read @focused` lässt sich also sauber weiterleiten.
* Diagnosen, Warnungen und Fehlerobjekte gehen auf stderr.
* Das Authentifizierungstoken liest der Client aus `~/.kortty/control/endpoint.json`. Es gibt bewusst weder eine Option `--token` noch eine Umgebungsvariable: ein Token auf der Kommandozeile landet in der Prozessliste und in der Shell-Historie.
* korTTY protokolliert eine Zeile pro Aktion der CLI und zeigt eine Desktop-Benachrichtigung, sobald ein Programm zum ersten Mal in einen Bereich tippt. Ihr Skript ist absichtlich nicht unsichtbar.
