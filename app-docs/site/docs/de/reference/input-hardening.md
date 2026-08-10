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
| **Snippet-Editor → AI-Code → Vollständige Code-Analyse** | Sie klicken auf *Auswahl übernehmen*; Jede aktivierte Schutzregel wird zu einer separat verfolgten verbindlichen Anforderung, und ein unvollständiger Ersatz wird vor der Überprüfung abgelehnt |

Im Gegensatz zu den klassischen Härtungsoptionen erfolgt die Eingabe-Härtung **ausschließlich opt-in**: Das Master-Kontrollkästchen wird deaktiviert und das Bedienfeld wird minimiert, da der Wächter das Laufzeitverhalten des Skripts ändert – ein Skript, das zuvor Eingaben akzeptiert hat, beginnt, Eingaben abzulehnen, die gegen die Regeln verstoßen. Aktivieren Sie **Eingabe-Härtung (Skript-Eingaben validieren)**, um sie für die aktuelle Generierung oder Neuschreibung zu aktivieren.

Deklarative YAML/YML/Ansible-Ziele können diesen zwingenden Argument- und Dateischutz nicht erhalten. KorTTY deaktiviert das Panel für diese Ziele in den Dialogen Workflow, Swarm, **Robustheit verbessern**, **Benutzerdefinierte Verbesserung** und **Vollständige Codeanalyse**, und eine bereits gespeicherte aktivierte Standardeinstellung führt dort zu keiner wirksamen Schutzkonfiguration. Für Snippet-Verbesserungen in einer unterstützten Sprache ändert die Aktivierung der Eingabe-Härtung das Ziel auf das **vollständige Snippet**, sodass der Schutz vor allen eigentlichen Arbeiten platziert werden kann; Das Gesamtergebnis wird vor der Anwendung überprüft.

## Die fünf Wachverhaltensweisen

Wenn das Master-Kontrollkästchen aktiviert ist, steuern fünf Unteroptionen, was der Wächter erzwingt. Alle fünf sind bereits angekreuzt; Deaktivieren Sie, was Sie nicht möchten.

#### Parameter-Zulassungslisten und Längenbeschränkungen

Jeder Parameter, mit dem das Skript aufgerufen wird, wird validiert: die genaue erwartete Parameteranzahl, eine Zeichenzulassungsliste pro Parameter, die davon abgeleitet wird, wie das Skript den Wert tatsächlich verwendet (eine Zahl, ein Dateipfad, ein Hostname, ein Schlüsselwort, freier Text) und eine maximale Länge pro Parameter. Steuerzeichen, NUL-Bytes und Shell-Metazeichen (`;`, `|`, `&`, `` ` ``, `$`, `\`, `<`, `>`, eingebettete Zeilenumbrüche) werden für jeden Parameter abgelehnt, der sie nicht rechtmäßig benötigt, sodass überlange oder in böser Absicht erstellte Werte abgelehnt statt verarbeitet werden.

#### Überprüfung des Eingabedateiformats

Jeder Parameter, den das Skript als Eingabedateipfad verwendet, wird vor der ersten Verwendung überprüft: Die Datei muss vorhanden und lesbar sein, und ihr Inhaltsformat muss mit dem übereinstimmen, was das Skript verarbeiten kann. Ein Textverarbeitungsskript lehnt Binärdateien ab – der Wächter durchsucht die ersten Bytes nach NUL-Bytes und konsultiert `file --mime-type` zusätzlich nur dort, wo dieser Befehl vorhanden ist, und greift auf die integrierte Prüfung zurück, wenn dies nicht der Fall ist.

#### Max. Größe der Eingabedatei (Skriptvariable)

Der Wächter definiert in seinem Konfigurationsabschnitt eine `MAX_FILE_SIZE`-Variable (in Bytes). Wenn `MAX_FILE_SIZE` größer als `0` ist, ermittelt der Wächter die Dateigröße aus Metadaten, bevor ein Vorgang den Dateiinhalt liest – einschließlich des ersten Inhaltsscans der Formatprüfung. Eine größere Datei wird mit dem Exit-Code `65` abgelehnt und ihr Inhalt wird nicht gelesen. Wenn keine reine Metadaten-Größenabfrage verfügbar ist, lehnt der Wächter die Datei ab, ohne sie zu lesen. `0` bedeutet unbegrenzt und überspringt die Größenprüfung vollständig. Der Größenauswahlknopf im Bedienfeld akzeptiert `0`–`1024` MB und legt den generierten Standardwert auf 10 MB fest, sofern Sie ihn nicht ändern.

#### Sicherheitswarnungen für stderr und Skriptprotokoll

Jeder Verstoß wird als zeitgestempelte Sicherheitswarnzeile gemeldet, beginnend mit `SECURITY:` auf stderr. Wenn auch **FORCE=1 Override** ausgewählt ist, wird jede erzwungene Umgehung auf die gleiche Weise gemeldet. Wenn das Skript seine eigene Protokolldatei schreibt, fügt der Wächter auch dort dieselbe Warnzeile an, sodass das Protokoll des Skripts eine vollständige Sicherheitsspur enthält.

#### FORCE=1-Überschreibung

Blockieren ist die Standardeinstellung, aber eine Ausführung kann erzwungen werden: Wenn die Umgebungsvariable `FORCE` auf `1` gesetzt ist, stuft der Wächter jeden Verstoß auf eine Warnung herab und fährt fort. Jeder einzelne Verstoß wird weiterhin gemeldet, plus eine zusätzliche Warnung, dass die Durchsetzung umgangen wurde – eine erzwungene Flucht hinterlässt immer eine vollständige Spur.

## Exit-Codes

Wenn der Guard eine Ausführung blockiert (und `FORCE` nicht gesetzt ist), verwendet er eindeutige, dokumentierte Exit-Codes, damit Aufrufer erkennen können, *warum* die Eingabe abgelehnt wurde. Die Eingabeaufforderung benennt nur Codes, die durch die ausgewählten Prüfungen erstellt wurden: Nur-Format- und Nur-Größen-Auswahlen beschreiben jeweils nur ihren eigenen `65`-Fall, und `66` ist nur bei **Eingabedateiformatprüfungen** enthalten.

| Exit-Code | Bedeutung |
|-----------|---------|
| `64` | Ein Parameter hat gegen die Regeln verstoßen (Anzahl, Zeichen, Länge) |
| `65` | Eine Eingabedatei hat die Format- oder Größenprüfung nicht bestanden |
| `66` | Eine Eingabedatei fehlt oder ist nicht lesbar |

## Sprachbewusstsein

Der Wächter nutzt die eigenen integrierten Funktionen und Standardbibliotheken jeder Sprache, sofern verfügbar, und prüft optionale Plattformtools, bevor er sie verwendet. Bash Guards verwenden `$#`, Mustervergleich, GNU `stat -c %s` oder BSD/macOS `stat -f %z` für eine reine Metadaten-Größenprüfung und einen NUL-Byte-Scan erst, nachdem diese Größenprüfung bestanden wurde; Wenn keines der `stat`-Formulare funktioniert, lehnt der Wächter die Datei ab, ohne sie zu lesen. Python-Guards verwenden `sys.argv`, `re`, `os.path.getsize` und einen binären Lesevorgang; Perl-Wächter verwenden `@ARGV`, das Entfernen von Regex-Zulassungslisten, den Taint-Modus (`-T`), sofern das Skript dies zulässt, und den Nur-Metadaten-Operator `-s`; Ruby Guards verwenden `ARGV`, `Regexp`, `File.size` und `File.binread`. Andere Skriptsprachen erhalten allgemeine Anleitungen nur für die nativen Argument-, Datei-, Protokollierungs- oder Umgebungsfunktionen, die für die ausgewählten Unteroptionen erforderlich sind. Deklarative YAML/YML/Ansible-Ziele sind deaktiviert, da sie auf diese Weise keine Positionsparameter annehmen.

## Verwaltung der Auswahl

Unterhalb der Unteroptionen aktivieren oder deaktivieren **Alle** und **Löschen** jede Unteroption, und **Speichern** speichert den Hauptschalter, den Unteroptionssatz und die Größenbeschränkung als Ihre Standardeinstellungen – dann wird jedes Eingabe-Härtungsfeld mit dieser Auswahl geöffnet. Der Paneltitel im Fenster „Vollständige Codeanalyse“ zeigt eine **Live-Zählung** der effektiv aktiven Unteroptionen an, die `0` beträgt, wenn der Hauptschalter ausgeschaltet ist.
