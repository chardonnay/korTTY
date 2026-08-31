---
title: Sprachvereinigung
---

# Sprachvereinheitlichung

**Sprachvereinheitlichung** schreibt ein Snippet so um, dass alles in *einer* Programmiersprache geschrieben ist. Reale Admin-Skripte sind das oft nicht: ein Bash-Frame, der ein Heredoc an Perl weiterleitet, ein Inline-`awk`-Programm, ein `python3 -c`-Einzeiler. Ein solches Skript verfügt über keinen einzelnen Formatierer, keinen einzelnen Linter und keine Analyse, die über die Sprachgrenzen hinausblicken kann.

KorTTY erkennt die Mischung lokal – es ist keine KI-Anfrage erforderlich, um zu entscheiden, ob die Option angeboten wird – und fordert erst dann ein Modell auf, das Umschreiben durchzuführen.

!!! warning "Eine Neufassung ist keine Neugestaltung"
    Die Migration bewahrt beobachtbares Verhalten und nichts anderes. Alles, was nicht übertragen werden kann, wird als **Notiz** zurückgemeldet, anstatt stillschweigend gelöscht oder durch ein erfundenes Äquivalent ersetzt zu werden. Lesen Sie immer die Hinweise und die Vorher-/Nachher-Vorschau, bevor Sie sich bewerben.

## Wo es erscheint

| Where | What it does |
|-------|--------------|
| **Snippet-Editor → AI-Code → In eine Sprache migrieren…** | Öffnet den Migrationsdialog direkt und zeigt das Ergebnis als Vorher/Nachher-Vorschau an |
| **Snippet-Editor → AI-Code → Vollständige Code-Analyse** | Ein minimiertes Bedienfeld zur **Sprachvereinheitlichung**; Die Migration wird dann als **erste** Phase von *Ausgewählte anwenden* ausgeführt, sodass jede anschließende Verbesserungs- und Härtungsphase auf das migrierte Skript wirkt |
| **Snippet editor → AI Code → Security Check** | The same panel; the migration runs before the security fixes, so the fixes are written in the target language |
| **Terminal → Workflow-Skript generieren**, **KI-Schwarm** | Das Kontrollkästchen **Nur Zielsprache**, das eingebettete fremdsprachige Teile im generierten Skript von Anfang an verbietet |

## Zielsprachen

Bash, Python, Perl, Ruby, PowerShell, Windows-CMD, AppleScript, JavaScript (Node) und Groovy.

Jedes Ziel bringt seinen eigenen Shebang, seine eigene Dateierweiterung und sein eigenes Kommentarpräfix sowie die gleichen sprachspezifischen Redewendungen mit, die der Workflow-Skript-Generator verwendet. Nach einer Migration des gesamten Skripts aktualisiert KorTTY auch die **Sprache** des Snippets, seine Dateierweiterung und seine automatisch erkannten KI-Fähigkeiten, sodass das Snippet durchweg der neue Dateityp ist.

Ansible ist bewusst **kein** Ziel: Die Umwandlung eines imperativen Skripts in ein deklaratives Playbook ist eine Neumodellierung, keine Sprachmigration.

## Orchestrierungsformate

Eine Azure DevOps-Pipeline, ein GitHub Actions-Workflow, eine GitLab CI-Datei, eine Jenkins-Datei, ein Ansible-Playbook, ein Puppet-Manifest und eine Docker-Datei rufen Bash oder PowerShell **konstruktionsbedingt** auf. Die eingebettete Shell ist ihr Design und kein Defekt, daher meldet KorTTY ein solches Dokument niemals als „gemischt“ und bietet niemals an, das Dokument selbst zu migrieren. Stattdessen werden zwei engere Dinge angeboten:

#### Vereinheitlichen Sie die Skriptschritte

Wird nur angeboten, wenn die eigenen Skriptschritte des Dokuments nicht übereinstimmen – eine Pipeline mit den Schritten `- bash:` und `- pwsh:`, eine Jenkins-Datei, die `sh` und `bat` mischt. Nur die **Körper** dieser Schritte werden neu geschrieben. Jede zweite Zeile – Struktur, Schlüssel, Einrückung, Anzeigenamen, Bedingungen, Aufgabenaufrufe, Kommentare – muss Zeichen für Zeichen zurückgegeben werden, und der Schritttyp wird dort angepasst, wo das Format dies erfordert (`- pwsh:` statt `- bash:`). Ein Ergebnis, das etwas außerhalb der Skriptschritte geändert hat, wird **verworfen** und nicht angeboten. Die Sprache, der Dateiname und die Fähigkeiten des Snippets bleiben unverändert: Es handelt sich immer noch um dieselbe Pipeline.

#### Auf eine andere Plattform konvertieren

Durch die Auswahl einer **Zielplattform** wird das Hostdokument in das Schema einer anderen Plattform konvertiert – beispielsweise eine Jenkins-Datei in eine Azure DevOps-Pipeline. Dies wird **nie vorgeschlagen und nie vorausgewählt**: Die Auswahl beginnt bei *Unverändert* und wird nur durch eine explizite Auswahl aktiviert.

Die Konvertierung erfolgt bewusst verlustbehaftet. Die Plattformsemantik ist nicht gleichwertig, daher übernimmt KorTTY das, was ein echtes Gegenstück hat (Trigger, Agenten- oder Poolauswahl, Variablen, Geheimnisse, Abhängigkeiten, Artefakte, Bedingungen) und meldet alles andere – Genehmigungen, Umgebungen, plattformspezifische Aufgaben, Matrixsemantik, Plugin-Aufrufe – als Notizen, die Sie manuell wiederholen müssen. Ein Ergebnis, das nicht erkennbar ist, da die angeforderte Zielplattform verworfen wird.

Dockerfiles sind in beide Richtungen von der Plattformkonvertierung ausgeschlossen: Ein Image-Build-Rezept ist keine CI-Pipeline.

## Was erkannt wird

Für ein einfaches Skript sucht KorTTY nach Heredocs, die einem anderen Interpreter zugeführt werden (`perl <<'EOF'`, `python3 <<PY`, `node <<'JS'`, …), Inline-Einzeilern (`perl -e`, `python3 -c`, `node -e`, `ruby -e`, `awk '…'`) und Shell-*Programmen*, die an die Prozess-API einer anderen Sprache übergeben werden. Übereinstimmungen innerhalb von Kommentaren werden ignoriert.

`sed -e`-Ausdrücke werden absichtlich nicht gekennzeichnet: Sie sind in der Shell allgegenwärtig und würden sie als Fremdsprache behandeln, würde dazu führen, dass fast jedes Skript gemischt aussieht. Ein einzelner externer Befehlsaufruf ist ebenfalls keine eingebettete Sprache – er bleibt auch nach der Migration ein einfacher Prozessaufruf.

## Wenn ein Ergebnis abgelehnt wird

| Meldung | Ursache |
|---------|-------|
| *Die KI hat kein verwendbares Skript zurückgegeben.* | Die Antwort enthielt überhaupt kein Skript. |
| *Die KI hat ein unvollständiges Skript zurückgegeben.* | Das Ergebnis hat den größten Teil des Programms verloren oder enthält eine Auslassungsmarkierung wie „Rest unverändert“ |
| *Die KI hat das Dokument außerhalb seiner Skriptschritte geändert.* | Eine Schrittvereinheitlichung hat einen Teil des Pipeline-Gerüsts neu geschrieben. |
| *Das Ergebnis ist kein gültiges … Dokument.* | Eine Plattformkonvertierung hat das angeforderte Zielformat nicht erreicht |

In jedem Fall bleibt der Ausschnitt unberührt. In der Regel reicht ein erneuter Start mit einem stärkeren Modell oder mit der Profilauswahl im Vorschaufenster aus.
