---
title: Eingabe-Härtung
---

# Eingabe-Härtung

**Eingabe-Härtung** fordert die KI auf, einen *Eingabevalidierungs-Schutzblock* direkt in ein Skript einzubauen, das sie generiert oder neu schreibt. Der Wächter wird ganz oben im Skript ausgeführt, bevor mit der eigentlichen Arbeit begonnen wird, und weist fehlerhafte Eingaben auf dem Host zurück, der das Skript ausführt: falsche Parameteranzahl, verbotene Zeichen, übergroße Werte, fehlende Eingabedateien, Dateien im falschen Format und Dateien über einer konfigurierbaren Größenbeschränkung. Bei Verstößen wird das Skript mit einer klaren Meldung und einem dokumentierten Exit-Code gestoppt – es sei denn, die Ausführung wird absichtlich erzwungen.

!!! note "Der Wächter lebt im Skript, nicht in korTTY"
    korTTY führt **keine eigene Validierung** durch, wenn ein Snippet oder ein Workflow-Skript ausgeführt wird. Durch die Eingabe-Härtung wird das *Skript* geändert: Die KI schreibt die Prüfungen in den Code, sodass sie jede zukünftige Ausführung dieses Skripts schützt – auch wenn es außerhalb von korTTY, von einem Scheduler oder auf einem Remote-Host gestartet wird. Wenn Sie das Skript später bearbeiten, handelt es sich bei dem Schutz um normalen Skriptcode, den Sie lesen, anpassen oder entfernen können.

## Eingabe-Härtung vs. Härtungsoptionen

Trotz des ähnlichen Namens handelt es sich hierbei um eine andere Funktion als [Hardening options](hardening-options.md). *Härtungsoptionen* sind allgemeine Techniken in Produktionsqualität (strikter Modus, Fehlerfallen, Protokollierung, `--help`, …), die beeinflussen, *wie das Skript geschrieben wird*. Bei der *Eingabe-Härtung* geht es darum, *was das Skript akzeptiert*: Es fügt einen konkreten Schutzblock hinzu, der Parameter und Eingabedateien zur Laufzeit validiert. Die beiden Panels erscheinen nebeneinander und sind frei kombinierbar.

## Wo es erscheint

Das **Input-Hardening**-Panel wird an den gleichen Stellen angezeigt wie das klassische Hardening-Panel sowie den AI-Swarm-Generator:

| Wobei | angewendet wird, wenn |
|-------|--------------|
| **Terminal → Workflow-Skript generieren** (die Schaltfläche *Workflow* nach der Ausführung eines Agenten) | Sie klicken auf *Generieren* |
| **AI Swarm → Multi-Server-Workflow generieren** | Sie generieren das Multi-Server-Skript |
| **Snippet-Editor → AI-Code → Robustheit verbessern** | Sie bestätigen den Dialog |
| **Snippet-Editor → AI-Code → Benutzerdefinierte Verbesserung…** | Sie bestätigen den Dialog |
| **Snippet-Editor → AI-Code → Vollständige Code-Analyse** | Sie klicken auf *Auswahl anwenden* |

Im Gegensatz zu den klassischen Härtungsoptionen erfolgt die Eingabe-Härtung **ausschließlich opt-in**: Das Master-Kontrollkästchen wird deaktiviert und das Bedienfeld wird minimiert, da der Wächter das Laufzeitverhalten des Skripts ändert – ein Skript, das zuvor Eingaben akzeptiert hat, beginnt, Eingaben abzulehnen, die gegen die Regeln verstoßen. Aktivieren Sie **Eingabe-Härtung (Skript-Eingaben validieren)**, um sie für die aktuelle Generierung oder Neuschreibung zu aktivieren.

## Die fünf Wachverhaltensweisen

Wenn das Master-Kontrollkästchen aktiviert ist, steuern fünf Unteroptionen, was der Wächter erzwingt. Alle fünf sind bereits angekreuzt; Deaktivieren Sie, was Sie nicht möchten.

#### Parameter-Zulassungslisten und Längenbeschränkungen

Jeder Parameter, mit dem das Skript aufgerufen wird, wird validiert: die genaue erwartete Parameteranzahl, eine Zeichenzulassungsliste pro Parameter, die davon abgeleitet wird, wie das Skript den Wert tatsächlich verwendet (eine Zahl, ein Dateipfad, ein Hostname, ein Schlüsselwort, freier Text) und eine maximale Länge pro Parameter. Steuerzeichen, NUL-Bytes und Shell-Metazeichen (`;`, `|`, `&`, `` ` ``, `$`, `\`, `<KTPH007>`, eingebettete Zeilenumbrüche) werden für jeden Parameter abgelehnt, der sie nicht rechtmäßig benötigt, sodass überlange oder in böser Absicht erstellte Werte abgelehnt statt verarbeitet werden.

#### Überprüfung des Eingabedateiformats

Jeder Parameter, den das Skript als Eingabedateipfad verwendet, wird vor der ersten Verwendung überprüft: Die Datei muss vorhanden und lesbar sein, und ihr Inhaltsformat muss mit dem übereinstimmen, was das Skript verarbeiten kann. Ein Textverarbeitungsskript lehnt Binärdateien ab – der Wächter durchsucht die ersten Bytes nach NUL-Bytes und konsultiert `file --mime-type` zusätzlich nur dort, wo dieser Befehl vorhanden ist, und greift auf die integrierte Prüfung zurück, wenn dies nicht der Fall ist.

#### Max. Größe der Eingabedatei (Skriptvariable)

Der Wächter definiert in seinem Konfigurationsabschnitt eine `KORTTY_MAX_FILE_SIZE`-Variable (in Bytes) und lehnt jede Eingabedatei über diesem Grenzwert ab. Die **Max. Der Spinner „Dateigröße**“ legt den generierten Standard fest – 10 MB, sofern Sie ihn nicht ändern – und da es sich bei dem Grenzwert um eine gewöhnliche Skriptvariable handelt, kann der Skriptautor ihn später erhöhen oder verringern, indem er einfach diese Zeile im Skript bearbeitet.

#### Sicherheitswarnungen für stderr und Skriptprotokoll

Jeder Verstoß und jede erzwungene Umgehung wird als zeitgestempelte Sicherheitswarnzeile, beginnend mit `SECURITY:`, auf stderr gemeldet. Wenn das Skript eine eigene Protokolldatei schreibt, fügt der Wächter auch dort dieselbe Warnzeile hinzu, sodass das Protokoll des Skripts eine vollständige Sicherheitsspur enthält.

#### KORTTY_FORCE=1 Überschreibung

Blockieren ist die Standardeinstellung, aber eine Ausführung kann erzwungen werden: Wenn die Umgebungsvariable `KORTTY_FORCE` auf `1` gesetzt ist, stuft der Wächter jeden Verstoß auf eine Warnung herab und fährt fort. Jeder einzelne Verstoß wird weiterhin gemeldet, plus eine zusätzliche Warnung, dass die Durchsetzung umgangen wurde – eine erzwungene Flucht hinterlässt immer eine vollständige Spur.

## Exit-Codes

Wenn der Guard eine Ausführung blockiert (und `KORTTY_FORCE` nicht gesetzt ist), verwendet er eindeutige, dokumentierte Exit-Codes, damit Aufrufer erkennen können, *warum* die Eingabe abgelehnt wurde:

| Exit-Code | Bedeutung |
|-----------|---------|
| `64` | Ein Parameter hat gegen die Regeln verstoßen (Anzahl, Zeichen, Länge) |
| `65` | Eine Eingabedatei hat die Format- oder Größenprüfung nicht bestanden |
| `66` | Eine Eingabedatei fehlt oder ist nicht lesbar |

## Sprachbewusstsein

Der Wächter wird nur mit den eigenen integrierten Funktionen und der Standardbibliothek jeder Sprache implementiert – er hängt nie von Tools ab, die auf dem Zielhost fehlen könnten, und optionale Hilfsprogramme (wie der Befehl `file`) degradieren sanft zu integrierten Prüfungen. Bash Guards verwenden `$#`, Mustervergleich, `wc -c` und einen NUL-Byte-Scan; Python-Guards verwenden `sys.argv`, `re`, `os.path.getsize` und einen binären Lesevorgang; Perl-Wächter verwenden `@ARGV`, entfernen Regex-Zulassungslisten und den Taint-Modus (`-T`), wenn das Skript dies zulässt; Ruby Guards verwenden `ARGV`, `Regexp` und `File.binread`. Andere Skriptsprachen erhalten einen generischen Schutz, der aus ihren nativen Argument-, String- und Dateifunktionen aufgebaut ist. Deklarative Ansible-Playbooks akzeptieren auf diese Weise keine Positionsparameter, sodass die Eingabe-Härtung für sie nicht gilt.

## Verwaltung der Auswahl

Unterhalb der Unteroptionen aktivieren oder deaktivieren **Alle** und **Löschen** jede Unteroption, und **Speichern** speichert den Hauptschalter, den Unteroptionssatz und die Größenbeschränkung als Ihre Standardeinstellungen – dann wird jedes Eingabe-Härtungsfeld mit dieser Auswahl geöffnet. Der Paneltitel im Fenster „Vollständige Codeanalyse“ zeigt eine **Live-Zählung** der effektiv aktiven Unteroptionen an, die `0` beträgt, wenn der Hauptschalter ausgeschaltet ist.
