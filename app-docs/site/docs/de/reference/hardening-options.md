---
title: Härtemöglichkeiten
---

# Härtungsoptionen

**Härtungsoptionen** sind eine Reihe von Techniken in Produktionsqualität, die Sie von der KI in ein von ihr generiertes oder neu geschriebenes Skript einbauen lassen können. Anstatt jedes Mal die gleiche Anweisung („Fehlerbehandlung hinzufügen“, „Wiederausführbar machen“, „Geben Sie ihm einen `--help`“) von Hand zu schreiben, kreuzen Sie die gewünschten Techniken an und KorTTY wandelt jede einzelne in eine präzise Regel um, die an die KI-Eingabeaufforderung angehängt wird. Das Ergebnis ist ein Skript, das sich wie etwas verhält, das ein sorgfältiger Ingenieur ausliefern würde: Es schlägt laut statt lautlos fehl, bereinigt sich selbst, protokolliert, was es tut, und kann ohne Überraschungen erneut ausgeführt werden.

Die gleichen elf Optionen werden überall dort verwendet, wo KorTTY ein Skript generiert oder verbessert, sodass sie sich unabhängig von der Ausgangsposition identisch verhalten.

!!! note "Nicht dasselbe wie Eingabe-Härtung"
    Härtungs-Optionen beeinflussen, *wie das Skript geschrieben wird* (Struktur, Fehlerbehandlung, Protokollierung, CLI). Das separate Panel [Eingabe-Härtung](input-hardening.md) fordert die KI stattdessen auf, einen konkreten *Eingabevalidierungs-Schutzblock* in das Skript einzubauen, der Parameter und Eingabedateien zur Laufzeit überprüft. Beide erscheinen nebeneinander und können kombiniert werden.

## Wo sie erscheinen

Das Bedienfeld **Härtungsoptionen** wird an folgenden Stellen angezeigt:

| Wo | Wie es aussieht | Wird angewendet, wenn |
|-------|--------------|--------------|
| **Terminal → Workflow-Skript generieren** (die Schaltfläche *Workflow* nach der Ausführung eines Agenten) | Reduzierbares Bedienfeld *Härtungsoptionen* (standardmäßig ausgeblendet) | Sie klicken auf *Generieren* |
| **AI Swarm → Multi-Server-Workflow generieren** | Kein Panel – die standardmäßige All-On-Auswahl wird automatisch angewendet | Sie generieren das Multi-Server-Skript |
| **Snippet-Editor → AI-Code → Robustheit verbessern** | Optionsfeld mit allen angekreuzten Kästchen | Sie bestätigen den Dialog |
| **Snippet-Editor → AI-Code → Benutzerdefinierte Verbesserung…** | Optionsfeld plus ein Freitext-Anweisungsfeld | Sie bestätigen den Dialog |
| **Snippet-Editor → AI-Code → Vollständige Code-Analyse** | Reduzierbares Bedienfeld „Härtungsoptionen“ am unteren Rand des Fensters mit einer Live-Anzahl **(N)** der aktivierten Optionen im Titel; korTTY merkt sich, ob Sie es offen oder geschlossen gelassen haben | Sie klicken auf *Auswahl übernehmen* |

!!! note "Wird nicht für jede Aktion angezeigt"
    *Lesbarkeit verbessern* und *Leistung verbessern* zeigen absichtlich **keine** Härtungsoptionen an – diese Aktionen sollen nah am Originalcode bleiben. Härtungsoptionen werden nur dort angezeigt, wo es auf das Hinzufügen von Robustheit ankommt: *Robustheit verbessern*, *Benutzerdefinierte Verbesserung*, *Vollständige Codeanalyse* und die beiden Workflow-Skriptgeneratoren.

Jede Option ist **standardmäßig aktiviert**. Deaktivieren Sie diejenigen, die Sie nicht möchten. Eine deaktivierte Option trägt nichts zur Eingabeaufforderung bei.

Unterhalb der Optionen helfen Ihnen drei Schaltflächen bei der Verwaltung der Auswahl:

- **Alle** – kreuzen Sie jede Option an.
- **Löschen** – alle Optionen deaktivieren (ohne Härtungsregeln generieren/anwenden).
- **Speichern** – aktuelle Auswahl dauerhaft speichern. Von da an wird jedes Härtungsfenster mit Ihrer gespeicherten Auswahl anstelle der allgemeinen Standardeinstellung geöffnet – Sie können also Ihre bevorzugte Härtung einmal festlegen und sie überall anwenden.

## Wie sie angewendet werden

Jede angekreuzte Option wird zu genau einer Anweisungszeile, die KorTTY an die an die KI gesendete Anfrage anhängt (unter *Diese Härtungstechniken anwenden:* im Snippet-Editor oder *ZUSÄTZLICHE ANFORDERUNGEN:* im Workflow-Generator). Die KI wird dann aufgefordert, diese Regeln bei der Erstellung des Drehbuchs einzuhalten.

Der Wortlaut jeder Regel passt sich der Zielsprache an:

- **Imperative Skripte** – Bash, Python, Perl, Ruby, PowerShell, Windows-CMD und AppleScript erhalten die imperative Formulierung (Flags, Traps, Exit-Codes, …).
- **Deklarative Artefakte** – Ansible-Playbooks und Snippets, deren Sprache `YAML`/`YML` ist oder `ansible` enthält, erhalten stattdessen Ansible-idiomatische Formulierungen (`block`/`rescue`/`always`, `assert`, `vars:`, Prüfmodus, …).

Die *Idee* jeder Option ist also überall gleich, aber ein Bash-Skript erhält eine `set -euo pipefail`-Stilregel, während ein Ansible-Playbook eine `assert`/`failed_when`-Stilregel für genau dasselbe Kontrollkästchen erhält.

## Die beiden Gruppen

Die elf Optionen lassen sich aufgrund ihrer Auswirkung auf das Skript in zwei Gruppen einteilen:

- **Verhaltenserhaltende Härtung** (die ersten sieben) fügen nur Dokumentation, Protokollierung, Struktur und Sicherheitsnetze hinzu. Sie machen das Skript stabiler und leichter lesbar, **ohne seine eigentliche Funktion zu ändern**. Diese können bei fast jedem Drehbuch bedenkenlos aktiviert bleiben.
- **Verhaltens-/interaktive Änderungen** (die letzten vier) können den Kontrollfluss ändern oder eine Befehlszeilenschnittstelle hinzufügen – Vorbedingungs-Gates, Erkennung erneuter Ausführung, ein Probelaufmodus, Argumentanalyse. Lassen Sie sie weg, wenn Sie möchten, dass die Umschreibung so nah wie möglich am ursprünglichen Verhalten bleibt.

## Optionsreferenz

Jede Option unten listet auf, wozu sie dient und welche genaue Regel KorTTY an die KI sendet – für imperative Skripte und für Ansible-Playbooks.

### Verhaltenserhaltende Verhärtung

#### Strikter Modus (Abbruch bei Fehler)

- **Wofür es ist** – Stoppen Sie das Skript in dem Moment, in dem etwas schief geht, anstatt blind in einem halb gescheiterten Zustand fortzufahren.
- **Imperative Skripte** – Aktivieren Sie den Strict-/Abort-on-Error-Modus der Sprache (z. B. `set -euo pipefail` in Bash, `Set-StrictMode -Version Latest` mit `$ErrorActionPreference = 'Stop'` in PowerShell, `use strict; use warnings;` in Perl).
- **Ansible-Playbooks** – Validieren Sie die Voraussetzungen mit `assert`/`failed_when`, damit die Wiedergabe aufgrund eines fehlerhaften Zustands sofort fehlschlägt.

#### Fehlerfalle und Bereinigung

- **Wofür es ist** – Gewährleisten, dass ein Fehler klar gemeldet wird und dass jeder temporäre Status (temporäre Dateien, Bereitstellungen, Sperren) bereinigt wird, selbst wenn das Skript abgebrochen wird.
- **Imperative Skripte** – Fügen Sie einen Fehler-Trap-/`finally`-/`ensure`-Block hinzu, der Fehler meldet und den temporären Status bereinigt.
- **Ansible-Playbooks** – Verwenden Sie `block`/`rescue`/`always`, damit Fehler abgefangen werden und die Bereinigung immer ausgeführt wird.

#### Sinnvolle Exit-Codes

- **Wofür es ist** – Lassen Sie denjenigen, der das Skript aufruft (einen Scheduler, einen CI-Job, ein anderes Skript), sagen, *warum* es fehlgeschlagen ist, nicht nur *dass* es fehlgeschlagen ist.
- **Imperative Skripte** – Verwenden Sie unterschiedliche, dokumentierte Exit-Codes ungleich Null für unterschiedliche Fehlerklassen.
- **Ansible Playbooks** – Sorgen Sie dafür, dass fehlgeschlagene Aufgaben mit einer klaren Nachricht (`any_errors_fatal`, wo sinnvoll) das Spiel stoppen.

#### Protokollierung (`--verbose`)

- **Wofür es ist** – Machen Sie den Fortschritt des Skripts sichtbar und debuggbar, ohne die normale Ausgabe zu überladen.
- **Imperative Skripte** – Senden Sie Protokollnachrichten mit Zeitstempel an stderr und unterstützen Sie ein `--verbose`/`-v`-Flag.
- **Ansible-Playbooks** – Verwenden Sie das `debug`-Modul für die Fortschrittsausgabe (sichtbar mit `-v`).

#### Konfigurationsblock für Literale

- **Wofür es ist** – Sammeln Sie die Werte, die Sie am wahrscheinlichsten ändern (Pfade, Hostnamen, Paketnamen), an einer offensichtlichen Stelle, anstatt sie über das Skript zu verteilen.
- **Imperative Skripte** – Heben Sie alle Literale (Pfade, Hosts, Pakete) in einen klar kommentierten Konfigurationsblock oben.
- **Ansible-Playbooks** – Heben Sie alle Literale oben in einen `vars:`-Block.

#### Abschließende Zusammenfassung

- **Wofür es ist** – Schließen Sie mit einem kurzen Bericht ab, damit der Bediener auf einen Blick sehen kann, was passiert ist.
- **Imperative Skripte** – Drucken Sie eine abschließende Zusammenfassung dessen, was getan wurde (mit Erfolgs-/Misserfolgszählung).
- **Ansible-Playbooks** – Beenden Sie mit einer `debug`-Zusammenfassung dessen, was sich geändert hat.

#### Style-Guide / Linter reinigen

- **Wofür es ist** – Erstellen Sie Code, der den Standard-Linter der Sprache überwindet, sodass er konsistent liest und häufige Fehler vermeidet.
- **Imperative Skripte** – Befolgen Sie den Sprach-Styleguide und halten Sie ihn sauber (z. B. ShellCheck-clean für Bash).
- **Ansible-Playbooks** – Befolgen Sie die `ansible-lint`-Konventionen und verwenden Sie vollständig qualifizierte Modulnamen.

### Verhaltens-/interaktive Änderungen

#### Vorbedingungsprüfungen

- **Wofür es ist** – Machen Sie schnell einen Fehler, bevor Sie irgendetwas anfassen, wenn die Umgebung nicht bereit ist – ein fehlender Befehl, unzureichende Berechtigungen oder kein Netzwerk.
- **Imperative Skripte** – Überprüfen Sie vor der Arbeit die erforderlichen Befehle, Berechtigungen (root/sudo) und Konnektivität.
- **Ansible-Playbooks** – Fügen Sie vor jeder Änderung `pre_tasks`/`assert`-Prüfungen für erforderliche Berechtigungen, Pakete und Konnektivität hinzu.

#### Idempotenz (abgeschlossene Schritte überspringen)

- **Wofür es ist** – Machen Sie das Skript sicher für die zweite Ausführung: Schritte, die bereits ausgeführt wurden, werden erkannt und übersprungen, anstatt wiederholt zu werden oder einen Fehler zu verursachen.
- **Imperative Skripte** – Erkennen Sie bereits abgeschlossene Schritte und überspringen Sie sie, damit das Skript sicher erneut ausgeführt werden kann.
- **Ansible-Playbooks** – Stellen Sie sicher, dass das Playbook vollständig idempotent ist (sichere erneute Ausführung; verlassen Sie sich auf Modul-Idempotenz und `creates`/`removes`).

#### Abgesicherter Modus (`--dry-run` + bestätigen)

- **Wofür es ist** – Lassen Sie den Bediener eine Vorschau dessen anzeigen, was das Skript tun würde, ohne irgendwelche Änderungen vorzunehmen, und bitten Sie um Bestätigung, bevor etwas destruktives geschieht.
- **Imperative Skripte** – Unterstützen Sie ein `--dry-run`-Flag, das beabsichtigte Aktionen ohne Ausführung ausgibt und vor destruktiven Vorgängen bestätigt (unterdrückbar mit `--yes`).
- **Ansible-Playbooks** – Unterstützt den Prüfmodus (`--check`) und schützt destruktive Aufgaben, sodass ein Probelauf keine Änderungen vornimmt.

#### `--help` & Argumentanalyse

- **Wofür es ist** – Verwandeln Sie das Skript in ein richtiges Befehlszeilentool mit dokumentierten, überschreibbaren Eingaben anstelle von fest codierten Werten.
- **Imperative Skripte** – Stellen Sie eine `--help`/usage-Nachricht bereit und analysieren Sie Befehlszeilenargumente für die konfigurierbaren Werte.
- **Ansible-Playbooks** – Dokumentieren Sie alle Variablen und wie Sie sie über `--extra-vars` am Anfang der Datei überschreiben können.

## Tipps

- Beginnen Sie mit den Standardeinstellungen (alle aktiviert) für ein wegwerfbares oder persönliches Skript – die verhaltenserhaltende Gruppe kostet Sie nichts und die interaktive Gruppe macht das Skript benutzerfreundlicher.
- Für eine Umschreibung, bei der Sie den kleinstmöglichen Unterschied wünschen, deaktivieren Sie die vier Verhaltensoptionen und behalten Sie nur die verhaltenserhaltende Gruppe bei.
- Die Optionen sind unabhängig – Sie können jede beliebige Kombination ankreuzen. KorTTY sendet nur Regeln für die angekreuzten Kästchen.
