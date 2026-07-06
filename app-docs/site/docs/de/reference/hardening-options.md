---
title: Härtemöglichkeiten
---

# Härtungsoptionen

**Härtungsoptionen** sind eine Reihe von Techniken in Produktionsqualität, die Sie anfordern können
KI zum Einbinden in ein Skript, das sie generiert oder neu schreibt. Anstatt dasselbe zu schreiben
Anweisung („Fehlerbehandlung hinzufügen“, „Wiederausführbar machen“, „Geben Sie ihm einen `--help`“)
Jedes Mal von Hand kreuzen Sie die gewünschten Techniken an und KorTTY dreht jede einzelne
in eine präzise Regel umwandeln, die an die KI-Eingabeaufforderung angehängt wird. Das Ergebnis ist ein Skript
Das verhält sich wie etwas, das ein sorgfältiger Ingenieur liefern würde: Es versagt lautstark
Statt stillschweigend bereinigt es sich selbst, protokolliert, was es tut, und kann ausgeführt werden
wieder ohne Überraschungen.

Die gleichen elf Optionen werden überall dort verwendet, wo KorTTY ein generiert oder verbessert
Skript, sodass sie sich unabhängig von der Ausgangsposition identisch verhalten.

## Wo sie erscheinen

Das Bedienfeld **Härtungsoptionen** wird an folgenden Stellen angezeigt:

| Wo | Wie es aussieht | Wird angewendet, wenn |
|-------|--------------|--------------|
| **Terminal → Workflow-Skript generieren** (die Schaltfläche *Workflow* nach der Ausführung eines Agenten) | Reduzierbares Bedienfeld *Härtungsoptionen* (standardmäßig ausgeblendet) | Sie klicken auf *Generieren* |
| **KI-Schwarm → Multi-Server-Workflow generieren** | Gleiches zusammenklappbares Panel | Sie generieren das Multiserver-Skript |
| **Snippet-Editor → AI-Code → Robustheit verbessern** | Optionsfeld mit allen angekreuzten Kästchen | Sie bestätigen den Dialog |
| **Snippet-Editor → AI-Code → Benutzerdefinierte Verbesserung…** | Optionsfeld plus ein Freitext-Anweisungsfeld | Sie bestätigen den Dialog |
| **Snippet-Editor → AI-Code → Vollständige Code-Analyse** | Zusammenklappbares Feld *Härtungsoptionen* am unteren Rand des Fensters | Sie klicken auf *Auswahl übernehmen* |

!!! Hinweis „Wird nicht bei jeder Aktion angezeigt“
*Lesbarkeit verbessern* und *Leistung verbessern* werden bewusst **nicht** angezeigt
Härtungsoptionen – diese Aktionen sollen nah am Original bleiben
Code. Härtungsoptionen erscheinen nur dort, wo es auf die Erhöhung der Robustheit ankommt:
*Stabilität verbessern*, *Benutzerdefinierte Verbesserung*, *Vollständige Code-Analyse* und beides
Workflow-Skript-Generatoren.

Jede Option ist **standardmäßig aktiviert**. Deaktivieren Sie diejenigen, die Sie nicht möchten. Ein
Eine deaktivierte Option trägt nichts zur Eingabeaufforderung bei.

## Wie sie angewendet werden

Jede angekreuzte Option wird zu genau einer Anweisungszeile, an die KorTTY anhängt
die an die KI gesendete Anfrage (unter *Diese Härtungstechniken anwenden:* im
Snippet-Editor oder *ZUSÄTZLICHE ANFORDERUNGEN:* im Workflow-Generator). Die KI
wird dann gebeten, diese Regeln bei der Erstellung des Drehbuchs einzuhalten.

Der Wortlaut jeder Regel passt sich der Zielsprache an:

- **Imperative Skripte** – Bash, Python, Perl, Ruby, PowerShell, Windows-CMD und
AppleScript erhält die zwingende Formulierung (Flags, Traps, Exit-Codes, …).
- **Deklarative Artefakte** – Ansible-Playbooks und Snippets, deren Sprache ist
`YAML`/`YML` oder `ansible` enthält, erhalten Sie stattdessen Ansible-idiomatische Formulierungen
(`block`/`rescue`/`always`, `assert`, `vars:`, Prüfmodus, …).

Die *Idee* jeder Option ist also überall gleich, aber ein Bash-Skript erhält eine
`set -euo pipefail`-Stilregel, während ein Ansible-Playbook eine `assert` / erhält
`failed_when`-Stilregel für dasselbe Kontrollkästchen.

## Die beiden Gruppen

Die elf Optionen lassen sich aufgrund ihrer Auswirkung auf das Skript in zwei Gruppen einteilen:

- **Verhaltenserhaltende Härtung** (die ersten sieben) fügt nur Dokumentation hinzu,
Holzeinschlag, Struktur und Sicherheitsnetze. Sie machen das Skript stabiler und einfacher
lesen, **ohne zu ändern, was es tatsächlich tut**. Diese können bedenkenlos belassen werden
für fast jedes Skript.
- **Verhaltens-/interaktive Änderungen** (die letzten vier) können den Kontrollfluss ändern
oder fügen Sie eine Befehlszeilenschnittstelle hinzu – Vorbedingungs-Gates, Erkennung erneuter Ausführung usw
Probelaufmodus, Argumentanalyse. Lassen Sie sie weg, wenn Sie möchten, dass die Umschreibung erhalten bleibt
so nah wie möglich am ursprünglichen Verhalten.

## Optionsreferenz

Jede Option unten listet den Zweck und die genaue Regel auf, die KorTTY an die sendet
KI – für zwingende Skripte und für Ansible-Playbooks.

### Verhaltenserhaltende Verhärtung

#### Strikter Modus (Abbruch bei Fehler)

- **Wofür es ist** – Stoppen Sie das Skript, sobald etwas schief geht
Blindes Weitermachen in einem halb gescheiterten Zustand.
- **Imperative Skripte** – Aktivieren Sie den Strict-/Abort-on-Error-Modus der Sprache (für
Beispiel `set -euo pipefail` in Bash, `Set-StrictMode -Version Latest` mit
`$ErrorActionPreference = 'Stop'` in PowerShell, `use strict; use warnings;` in
Perl).
- **Ansible-Playbooks** – Überprüfen Sie die Voraussetzungen mit `assert`/`failed_when`
Schlechter Zustand schlägt das Spiel sofort fehl.

#### Fehlerfalle und Bereinigung

- **Wofür es ist** – Gewährleisten, dass ein Fehler klar und deutlich gemeldet wird
Der temporäre Status (temporäre Dateien, Bereitstellungen, Sperren) wird auch dann bereinigt, wenn das Skript ausgeführt wird
bricht ab.
- **Imperative Skripte** – Fügen Sie einen Fehler-Trap / `finally` / `ensure`-Block hinzu
Meldet Fehler und bereinigt den temporären Zustand.
- **Ansible-Playbooks** – Verwenden Sie `block`/`rescue`/`always`, damit Fehler abgefangen und behoben werden
Die Bereinigung läuft immer.

#### Sinnvolle Exit-Codes

- **Wofür es ist** – Wer auch immer das Skript aufruft (einen Scheduler, einen CI-Job,
ein anderes Skript) erklären, *warum* es fehlgeschlagen ist, nicht nur, *dass* es fehlgeschlagen ist.
- **Imperative Skripte** – Verwenden Sie eindeutige, dokumentierte Exit-Codes ungleich Null für
unterschiedliche Fehlerklassen.
- **Ansible Playbooks** – Stoppen Sie fehlgeschlagene Aufgaben mit einer klaren Botschaft
(`any_errors_fatal`, wo sinnvoll).

#### Protokollierung (`--verbose`)

- **Wofür es ist** – Machen Sie den Fortschritt des Skripts sichtbar und debuggbar, ohne dass es nötig ist
überladene normale Ausgabe.
- **Imperative Skripte** – Senden Sie zeitgestempelte Protokollnachrichten an stderr und unterstützen Sie a
`--verbose`/`-v`-Flag.
- **Ansible-Playbooks** – Verwenden Sie das `debug`-Modul für die Fortschrittsausgabe (sichtbar mit
`-v`).

#### Konfigurationsblock für Literale

- **Wofür es ist** – Sammeln Sie die Werte, die Sie am wahrscheinlichsten ändern werden (Pfade,
Hostnamen, Paketnamen) an einem offensichtlichen Ort, anstatt sie zu verstreuen
durch das Drehbuch.
- **Imperative Skripte** – Heben Sie alle Literale (Pfade, Hosts, Pakete) in ein
deutlich kommentierter Konfigurationsblock oben.
- **Ansible-Playbooks** – Heben Sie alle Literale oben in einen `vars:`-Block.

#### Abschließende Zusammenfassung

- **Wofür es ist** – Schließen Sie mit einem kurzen Bericht ab, damit der Bediener es auf einen Blick erkennen kann
was ist passiert.
- **Imperative Skripte** – Drucken Sie eine abschließende Zusammenfassung dessen, was getan wurde (mit
Erfolg/Misserfolg zählt).
- **Ansible-Playbooks** – Beenden Sie mit einer `debug`-Zusammenfassung dessen, was sich geändert hat.

#### Style-Guide / Linter reinigen

- **Wofür es ist** – Produzieren Sie Code, der den Standard-Linter der Sprache übersteht
Es liest sich einheitlich und vermeidet übliche Fußfeuer.
- **Imperative Skripte** – Befolgen Sie den Sprach-Styleguide und behalten Sie ihn bei
linter-clean (zum Beispiel ShellCheck-clean für Bash).
- **Ansible-Playbooks** – Befolgen Sie die `ansible-lint`-Konventionen und verwenden Sie sie
vollqualifizierte Modulnamen.

### Verhaltens-/interaktive Änderungen

#### Vorbedingungsprüfungen

- **Wofür es ist** – Versagen Sie schnell, bevor Sie irgendetwas in der Umgebung berühren
ist nicht bereit – ein fehlender Befehl, unzureichende Berechtigungen oder kein Netzwerk.
- **Imperative Skripte** – Überprüfen Sie vor der Arbeit die erforderlichen Befehle und Berechtigungen
(root/sudo) und Konnektivität.
- **Ansible-Playbooks** – `pre_tasks`/`assert`-Prüfungen für erforderliche Berechtigungen hinzufügen,
Pakete und Konnektivität vor jeder Änderung.

#### Idempotenz (abgeschlossene Schritte überspringen)

- **Wofür es ist** – Machen Sie das Skript sicher, um es ein zweites Mal auszuführen: Schritte, die es sind
Bereits erledigte Schritte werden erkannt und übersprungen, anstatt wiederholt zu werden oder einen Fehler zu verursachen.
- **Imperative Skripte** – Erkennen Sie bereits abgeschlossene Schritte und überspringen Sie sie, damit die
Das Skript kann sicher erneut ausgeführt werden.
- **Ansible-Playbooks** – Stellen Sie sicher, dass das Playbook vollständig idempotent ist (sicheres erneutes Ausführen;
verlassen sich auf Modul-Idempotenz und `creates`/`removes`).

#### Abgesicherter Modus (`--dry-run` + bestätigen)

- **Wofür es ist** – Lassen Sie den Bediener in der Vorschau sehen, worauf das Skript verzichten würde
Nehmen Sie keine Änderungen vor und bitten Sie um Bestätigung, bevor Sie etwas Zerstörerisches tun.
- **Imperative Skripte** – Unterstützen Sie ein `--dry-run`-Flag, das beabsichtigte Aktionen ausgibt
ohne auszuführen, und vor destruktiven Vorgängen bestätigen (unterdrückbar mit).
`--yes`).
- **Ansible-Playbooks** – Unterstützt den Prüfmodus (`--check`) und den Schutz destruktiv
Aufgaben, sodass ein Probelauf keine Änderungen vornimmt.

#### `--help` & Argumentanalyse

- **Wofür es ist** – Verwandeln Sie das Skript in ein richtiges Befehlszeilentool mit
dokumentierte, überschreibbare Eingaben statt fest codierter Werte.
- **Imperative Skripte** – Stellen Sie eine `--help`/Nutzungsnachricht bereit und analysieren Sie die Befehlszeile
Argumente für die konfigurierbaren Werte.
- **Ansible-Playbooks** – Dokumentieren Sie alle Variablen und wie Sie sie überschreiben können
`--extra-vars` oben in der Datei.

## Tipps

- Beginnen Sie mit den Standardeinstellungen (alle aktiviert) für ein Wegwerf- oder persönliches Skript – das
Die verhaltenserhaltende Gruppe kostet Sie nichts und die interaktive Gruppe macht Spaß
das Skript freundlicher.
- Für eine Umschreibung, bei der Sie den kleinstmöglichen Unterschied wünschen, deaktivieren Sie die vier
Verhaltensoptionen und behalten Sie nur die verhaltenserhaltende Gruppe.
- Die Optionen sind unabhängig – Sie können jede beliebige Kombination ankreuzen. KorTTY sendet nur
Regeln für die angekreuzten Kästchen.
