---
title: Coding-Agents
---

# Coding-Agents

KorTTY erkennt, wenn ein terminalbasierter Coding-Agent – **Claude Code**, **Codex** oder **Gemini CLI** – in einer Ihrer [lokalen Shell-Registerkarten](terminal.md#lokale-shell-registerkarten) läuft, und verfolgt, was er gerade tut: arbeiten, auf Ihre Antwort warten oder untätig an seiner Eingabeaufforderung stehen. Die Analyse findet vollständig innerhalb von korTTY auf Ihrem eigenen Rechner statt. Diese Seite erklärt, was erkannt wird, wie die Erkennung funktioniert, wie Sie sie abschalten und wie Sie die Bildschirmregeln anpassen oder erweitern, wenn ein Agent seine Benutzeroberfläche ändert.

## Was erkannt wird

Die Erkennung umfasst die drei unten aufgeführten Agents, gestartet aus einer **Lokale Shell**-Registerkarte – direkt, über einen Paketmanager-Wrapper wie `npx` oder als `node`-, `bun`- oder `deno`-Skript. SSH- und Mosh-Registerkarten werden nicht analysiert, weil der Agent dort auf dem entfernten Rechner läuft und korTTY seinen Prozess nicht sehen kann.

| Agent | Erkannte ausführbare Dateien |
|-------|------------------------|
| Claude Code | `claude`, `claude-code` |
| Codex | `codex` |
| Gemini CLI | `gemini` |

Jeder geteilte Bereich wird einzeln verfolgt, sodass eine Registerkarte mit zwei Bereichen einen arbeitenden Agent zeigen kann, während der andere auf eine Berechtigungsentscheidung wartet. Ein Bereich, in dem kein Agent läuft oder dessen Agent beendet wurde, hat einfach kein Erkennungsergebnis.

## Zustände

Ein erkannter Agent befindet sich immer in genau einem von fünf Zuständen. Die Liste ist nach Dringlichkeit geordnet: Werden mehrere Bereiche zusammengefasst, gewinnt der dringlichste Zustand.

| Zustand | Bedeutung |
|-------|---------|
| **BLOCKED** | Der Agent wartet auf Sie – ein Berechtigungsdialog (*Do you want to proceed?*), eine Fragenauswahl oder eine `(y/n)`-Abfrage steht auf dem Bildschirm. |
| **DONE** | Der Agent hat eine Aufgabe abgeschlossen und zeigt einen expliziten Abschlussbildschirm. Reserviert für Agents, die einen solchen Bildschirm darstellen, und für das kommende Dashboard; die mitgelieferten Regeln melden ihn noch nicht. |
| **WORKING** | Der Agent denkt nach, führt ein Werkzeug aus oder streamt Ausgabe – typischerweise erkennbar an seiner Spinner-Zeile und dem Hinweis *esc to interrupt*. |
| **IDLE** | Der Agent läuft und sein Eingabefeld ist leer; er wartet auf Ihre nächste Anweisung. |
| **UNKNOWN** | Der Agent-Prozess ist vorhanden, aber der Bildschirm passt zu keiner Regel, und die Regeldatei verlangt ein striktes Ergebnis statt eines Fallbacks. |

## So funktioniert die Erkennung

KorTTY geht erst dann von einem Agent aus, wenn zwei unabhängige Beobachtungen übereinstimmen – diese *doppelte Evidenz* verhindert, dass ein zufälliges `claude` in einem Shell-Skript oder ein zitiertes Prompt-Fragment Fehlalarme auslöst:

1. **Prozessbaum.** Die lokale Shell des Bereichs hat eine bekannte Prozess-ID. KorTTY durchläuft die Nachkommen dieses Prozesses und sucht den neuesten lebenden, dessen Programmname – oder bei `node`, `bun` und `deno` das ausgeführte Skript – zu einem der unterstützten Agents gehört.
2. **Bildschirmregeln.** Der aktuell im Bereich sichtbare Text (niemals der Scrollback) wird zusammen mit dem vom Agent gesetzten Fenstertitel und der Information, ob er auf den alternativen Bildschirm gewechselt hat, gegen die Regeldatei dieses Agents geprüft. Der erste Regeltreffer bestätigt den Prozess; von da an entscheiden die Regeln über den Zustand.

Der Bildschirm wird etwa 200 ms nach der ersten Änderung eines Ausgabeschwalls neu ausgewertet – weitere Änderungen innerhalb dieses Fensters werden zusammengefasst –, sodass ein langer Ausgabestrom eine Auswertung pro 200-ms-Fenster auslöst statt einer pro Zeile und ein kurzer Schwall genau eine. Ein langsamer Heartbeat bemerkt zusätzlich, wenn der Agent beendet wird, ohne etwas auszugeben, wenn die Registerkarte die Verbindung verliert oder wenn Sie die Erkennung abschalten. Regeln werden nach Priorität ausgewertet; die erste Regel, deren Bedingungen alle zutreffen, bestimmt den Zustand. Ist der Agent-Prozess bestätigt, aber keine Regel passt, wird der Fallback-Zustand der Regeldatei gemeldet (IDLE bei den mitgelieferten Dateien).

!!! note "Datenschutz"
    Bildschirmtext wird ausschließlich innerhalb von korTTY, im Arbeitsspeicher, auf diesem Rechner analysiert. Nichts wird an einen Dienst gesendet, auf die Festplatte geschrieben oder an den KI-Assistenten weitergereicht – die Erkennung ist reine Mustererkennung gegen lokale Regeldateien. Wenn Sie lokale Shell-Bildschirme gar nicht untersuchen lassen möchten, schalten Sie die Funktion unter **Einstellungen → Terminal → Coding-Agents** ab.

## Erkennung aktivieren

Die Erkennung ist standardmäßig eingeschaltet und wird über einen einzigen Schalter unter **Einstellungen → Terminal**, Abschnitt **Coding-Agents**, gesteuert. Eine Änderung wirkt sofort auf alle offenen Registerkarten – kein Neustart und kein erneutes Verbinden nötig.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Coding-Agents (Claude Code, Codex, Gemini CLI) in lokalen Shell-Tabs erkennen | Schalter | — | Ein | `codingAgentDetectionEnabled` |

Ist der Schalter aus, durchläuft korTTY weder den Prozessbaum noch liest es den Bildschirm irgendeines Bereichs.

## Eigene Regeln

Die Bildschirmregeln jedes Agents liegen in einer kleinen JSON-Datei. KorTTY liefert pro Agent eine mitgelieferte Datei und lässt Sie diese durch Ihre eigene Kopie in Ihrem Konfigurationsverzeichnis ersetzen:

```text
~/.kortty/coding-agents/claude-code.json
~/.kortty/coding-agents/codex.json
~/.kortty/coding-agents/gemini-cli.json
```

Eine Überschreibung ersetzt die mitgelieferte Datei für diesen Agent **als Ganzes** – es gibt kein Zusammenführen, beginnen Sie also mit der mitgelieferten Datei und bearbeiten Sie sie. Die Datei muss eine reguläre Datei sein (symbolischen Links wird nicht gefolgt), und ihr `kind` muss zum Dateinamen passen. Eine ungültige Datei – fehlerhaftes JSON, ein unbekannter Schlüssel, ein fehlerhafter regulärer Ausdruck, eine doppelte Regel-ID oder eine Regel ohne jede Bedingung – wird mit einer Warnung im Protokoll ignoriert, und die mitgelieferten Regeln bleiben in Kraft, sodass ein Tippfehler die Erkennung nie stillschweigend abschalten kann. Regeldateien werden beim Start gelesen; starten Sie korTTY nach dem Bearbeiten einer Datei neu.

### Schema der Regeldatei

Das Schema ist strikt: Jeder hier nicht aufgeführte Schlüssel ist ein Fehler, sodass falsch geschriebene Schlüssel gemeldet statt ignoriert werden.

```json
{
  "kind": "CLAUDE_CODE | CODEX | GEMINI_CLI",
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
| `kind` | ja | Der Agent, den diese Datei beschreibt; muss zum Dateinamen passen (`claude-code`, `codex`, `gemini-cli`). |
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

### Regeln mit den Agents synchron halten

Agents ändern ihre Oberflächen häufig, und eine Regel, die letzten Monat gepasst hat, kann nach einem Update stillschweigend nicht mehr passen. Die mitgelieferten Regeln von KorTTY sind deshalb durch **Bildschirm-Fixtures** abgesichert: Für jeden Agent bewahrt das Repository aufgezeichnete Terminalbildschirme mit dem Zustand und der Regel auf, die sie liefern müssen, und die Testsuite schlägt fehl, sobald eine mitgelieferte Regel nicht mehr zu ihrem Fixture passt oder eine Regel gar kein Fixture hat. Wenn Ihnen ein falscher oder fehlender Zustand auffällt, ist der nützlichste Bericht der sichtbare Bildschirmtext des Bereichs in diesem Moment, der Agent samt Version und der von Ihnen erwartete Zustand – daraus werden im nächsten Release ein neues Fixture und eine Regelkorrektur. Bis dahin können Sie die Regel mit einer lokalen Überschreibungsdatei sofort für sich selbst korrigieren.

## Einschränkungen und Fehlerbehebung

- **Flatpak.** Im Flatpak-Paket läuft die lokale Shell über `flatpak-spawn` auf dem Host, sodass die Prozess-ID, die korTTY hält, zum Helfer auf der Sandbox-Seite gehört und der Prozessbaum des Agents nicht sichtbar ist. In lokalen Flatpak-Shells wird kein Agent erkannt.
- **Remote-Clients als Shell-Befehl.** Eine lokale Shell-Registerkarte, deren konfigurierter Shell-Befehl selbst `ssh` oder `mosh` ist, führt den Agent auf einem anderen Rechner aus; der Prozessbaum endet beim Client und es wird kein Agent erkannt. Dasselbe gilt für jede SSH- oder Mosh-Registerkarte.
- **Ungewöhnliche Wrapper.** Ein Agent, der über einen Starter gestartet wird, den korTTY nicht kennt – ein eigenes Shell-Skript, das per `exec` in eine anders benannte Binärdatei wechselt, ein Container oder ein Terminal-Multiplexer außerhalb der Registerkarte – wird nicht identifiziert, weil der Programmname im Prozessbaum keinem bekannten Agent zugeordnet werden kann.
- **Falscher oder flackernder Zustand.** Der Bildschirm des Agents hat sich geändert, oder ein Dialog wird auf eine Weise dargestellt, die die mitgelieferten Regeln nicht abdecken. Kopieren Sie die mitgelieferte Regeldatei nach `~/.kortty/coding-agents/<agent>.json`, passen Sie das Muster an, starten Sie korTTY neu und melden Sie den Bildschirmtext, damit die mitgelieferten Regeln korrigiert werden können.
- **Gar kein Zustand, obwohl der Agent läuft.** Prüfen Sie, dass der Schalter unter **Einstellungen → Terminal → Coding-Agents** eingeschaltet ist, dass die Registerkarte eine Lokale-Shell-Registerkarte ist, und suchen Sie im Protokoll nach einer *coding-agents*-Warnung – eine ungültige Überschreibungsdatei fällt auf die mitgelieferten Regeln zurück, aber eine mitgelieferte Regeldatei, die nie zu Ihrer Agent-Version passt, lässt den Agent unbestätigt.

## Was als Nächstes kommt

Dieses Release legt das Fundament: Die Erkennung läuft, und ihre Ergebnisse stehen innerhalb von korTTY zur Verfügung. Die nächste Stufe baut darauf den sichtbaren Teil auf – Zustandsabzeichen neben den Registerkarten und im Dashboard, ein Panel mit jedem laufenden Agent und seinem Zustand sowie Desktop-Benachrichtigungen, wenn ein Agent BLOCKED wird oder fertig ist, während seine Registerkarte nicht im Vordergrund liegt.
