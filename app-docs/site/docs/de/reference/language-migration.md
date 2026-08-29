---
title: Sprachvereinheitlichung
---

# Sprachvereinheitlichung

Die **Sprachvereinheitlichung** schreibt ein Snippet so um, dass es vollständig in *einer* Programmiersprache vorliegt. Reale Admin-Skripte sind das oft nicht: ein Bash-Rahmen, der per Heredoc Perl aufruft, ein inline eingebettetes `awk`-Programm, ein `python3 -c`-Einzeiler. Für ein solches Skript gibt es keinen einheitlichen Formatter, keinen Linter und keine Analyse, die über die Sprachgrenze hinwegsieht.

korTTY erkennt die Mischung lokal — es braucht keine KI-Anfrage, um zu entscheiden, ob die Option überhaupt angeboten wird — und lässt erst danach ein Modell die Umschreibung durchführen.

!!! warning "Eine Umschreibung ist keine Neumodellierung"
    Die Migration erhält das beobachtbare Verhalten und sonst nichts. Alles, was sich nicht übertragen lässt, wird als **Hinweis** zurückgemeldet statt stillschweigend weggelassen oder durch ein erfundenes Äquivalent ersetzt. Lesen Sie immer die Hinweise und die Vorher/Nachher-Vorschau, bevor Sie anwenden.

## Wo es erscheint

| Wo | Was passiert |
|----|--------------|
| **Snippet-Editor → KI-Code → In eine Sprache migrieren…** | Öffnet den Migrationsdialog direkt und zeigt das Ergebnis als Vorher/Nachher-Vorschau |
| **Snippet-Editor → KI-Code → Vollständige Code-Analyse** | Ein eingeklapptes Panel **Sprachvereinheitlichung**; die Migration läuft dann als **erste** Stufe von *Ausgewählte anwenden*, damit alle Verbesserungs- und Härtungsstufen danach auf dem migrierten Skript arbeiten |
| **Snippet-Editor → KI-Code → Security Check** | Dasselbe Panel; die Migration läuft vor den Security-Fixes, damit die Fixes in der Zielsprache geschrieben werden |
| **Terminal → Workflow-Skript erzeugen**, **KI-Schwarm** | Die Checkbox **Nur die Zielsprache verwenden**, die eingebettete Fremdsprach-Anteile im erzeugten Skript von vornherein verbietet |

## Zielsprachen

Bash, Python, Perl, Ruby, PowerShell, Windows-CMD, AppleScript, JavaScript (Node) und Groovy.

Jede Zielsprache bringt ihren eigenen Shebang, ihre Dateiendung und ihr Kommentarpräfix mit, dazu dieselben sprachspezifischen Idiome, die auch der Workflow-Skript-Generator verwendet. Nach einer Ganzskript-Migration zieht korTTY außerdem die **Sprache** des Snippets, seine Dateiendung und die automatisch erkannten KI-Skills nach, damit das Snippet durchgängig die neue Art von Datei ist.

Ansible ist bewusst **kein** Ziel: Ein imperatives Skript in ein deklaratives Playbook zu überführen ist eine Neumodellierung, keine Sprachmigration.

## Orchestrierungs-Formate

Eine Azure-DevOps-Pipeline, ein GitHub-Actions-Workflow, eine GitLab-CI-Datei, ein Jenkinsfile, ein Ansible-Playbook, ein Puppet-Manifest und ein Dockerfile rufen **per Bauart** zusätzlich Bash oder PowerShell auf. Eingebettete Shell ist dort Konstruktionsprinzip, kein Mangel — korTTY meldet ein solches Dokument deshalb nie als „gemischt" und bietet nie an, das Dokument selbst zu migrieren. Stattdessen gibt es zwei engere Angebote:

#### Die Skript-Schritte vereinheitlichen

Wird nur angeboten, wenn die Skript-Schritte des Dokuments untereinander uneinheitlich sind — eine Pipeline mit `- bash:`- **und** `- pwsh:`-Schritten, ein Jenkinsfile mit `sh` und `bat`. Umgeschrieben werden ausschließlich die **Rümpfe** dieser Schritte. Jede andere Zeile — Struktur, Schlüssel, Einrückung, Anzeigenamen, Bedingungen, Task-Aufrufe, Kommentare — muss zeichengleich zurückkommen; der Schritt-Typ wird angepasst, wo das Format es verlangt (`- pwsh:` statt `- bash:`). Ein Ergebnis, das außerhalb der Skript-Schritte etwas verändert hat, wird **verworfen** und gar nicht erst angeboten. Sprache, Dateiname und Skills des Snippets bleiben unverändert: Es ist weiterhin dieselbe Pipeline.

#### In eine andere Plattform konvertieren

Über die Auswahl **Ziel-Plattform** wird das Host-Dokument in das Schema einer anderen Plattform überführt — etwa ein Jenkinsfile in eine Azure-DevOps-Pipeline. Das wird **nie vorgeschlagen und nie vorbelegt**: Die Auswahl steht auf *Unverändert*, und nur eine ausdrückliche Wahl aktiviert sie.

Die Konvertierung ist bewusst verlustbehaftet. Plattform-Semantik ist nicht deckungsgleich; korTTY überträgt daher, wofür es eine echte Entsprechung gibt (Trigger, Agent- bzw. Pool-Auswahl, Variablen, Secrets, Abhängigkeiten, Artefakte, Bedingungen), und meldet alles andere — Approvals, Environments, plattformeigene Tasks, Matrix-Semantik, Plugin-Aufrufe — als Hinweise, die Sie von Hand nachziehen müssen. Ein Ergebnis, das nicht als die gewünschte Ziel-Plattform erkennbar ist, wird verworfen.

Dockerfiles sind von der Plattform-Konvertierung in beide Richtungen ausgenommen: Ein Image-Bauplan ist keine CI-Pipeline.

## Was erkannt wird

Bei einem einfachen Skript sucht korTTY nach Heredocs, die an einen anderen Interpreter gehen (`perl <<'EOF'`, `python3 <<PY`, `node <<'JS'`, …), nach Inline-Einzeilern (`perl -e`, `python3 -c`, `node -e`, `ruby -e`, `awk '…'`) und nach Shell-*Programmen*, die an die Prozess-API einer anderen Sprache übergeben werden. Treffer innerhalb von Kommentaren werden ignoriert.

`sed -e`-Ausdrücke werden bewusst nicht gemeldet: Sie sind in Shell-Skripten allgegenwärtig, und sie als Fremdsprache zu werten würde nahezu jedes Skript gemischt erscheinen lassen. Ein einzelner Aufruf eines externen Programms ist ebenfalls keine eingebettete Sprache — er bleibt auch nach der Migration ein gewöhnlicher Prozessaufruf.

## Wenn ein Ergebnis abgelehnt wird

| Meldung | Ursache |
|---------|---------|
| *Die KI hat kein verwendbares Skript geliefert.* | Die Antwort enthielt überhaupt kein Skript |
| *Die KI hat ein unvollständiges Skript geliefert.* | Das Ergebnis hat den Großteil des Programms verloren oder enthält einen Auslassungsmarker wie „Rest unverändert" |
| *Die KI hat das Dokument außerhalb der Skript-Schritte verändert.* | Eine Schritt-Vereinheitlichung hat am Pipeline-Gerüst geschrieben |
| *Das Ergebnis ist kein gültiges …-Dokument.* | Eine Plattform-Konvertierung hat das gewünschte Zielformat nicht erreicht |

In allen Fällen bleibt das Snippet unangetastet. Ein erneuter Lauf mit einem stärkeren Modell oder über die Profilauswahl im Vorschaufenster genügt meist.
