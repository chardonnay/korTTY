---
title: KI-Gedächtnis
---

# KI-Gedächtnis

KI-Speicher ist eine kurze Liste dauerhafter Regeln, die korTTY Ihren KI-Anfragen hinzufügt, sodass eine Präferenz, die Sie einmal äußern – beispielsweise „immer Tabs in Perl verwenden" – auch in späteren Chats und Agent-Läufen gilt, ohne dass Sie sie wiederholen müssen. Die Regeln sind einfache Markdown-Dateien auf diesem Computer. Sie schreiben sie selbst, und korTTY kann Regeln vorschlagen, die es in Ihren Nachrichten bemerkt. Ein Vorschlag wird erst gespeichert, nachdem Sie auf **Speichern** klicken, und korTTY ändert oder löscht niemals Text, den Sie geschrieben haben.

![AI memory: what a prompt gets and how a rule is learned](../assets/diagrams/ai-memory-flow.svg)

## Was Speicher ist

Der Speicher enthält zwei Arten von Text. **Eigener Text** ist jeder Absatz, den Sie in einer Speicherdatei geschrieben haben. **korTTY-Einträge** sind Regeln, die korTTY gespeichert hat, nachdem Sie sie bestätigt haben. Jeder korTTY-Eintrag wird durch Kommentarzeilen abgegrenzt, die korTTY als Herkunft, den Tag der Speicherung und die Quelle angeben, und er ist auf 300 Zeichen begrenzt:

```markdown
<!-- kortty:entry id=3fa9 origin=korTTY sha256=… created=2026-11-02 source=chat -->
- Perl: declare static variables with `state`.
<!-- /kortty:entry -->
```

Der Fence speichert einen Hash des Texts des Eintrags. Wenn Sie einen Eintrag bearbeiten, entspricht sein Text nicht mehr dem Hash, sodass korTTY ihn nicht mehr als eigenen behandelt und der Eintrag zu Ihrem Text wird.

Das Gedächtnis hat zwei Bereiche:

- **Globales Memory** (`memory/MEMORY.md`) gilt für jede Verbindung.
- **Verbindungs-Memory** (`memory/connections/<id>.md`) gilt für eine gespeicherte Verbindung, neben den globalen Regeln.

Beide Dateien befinden sich im korTTY-Konfigurationsordner, `~/.kortty/memory/`. Genehmigte Referenzdateien anderer KI-Tools sind eine dritte Quelle, beschrieben in [Weitere Speicherdateien](#weitere-speicherdateien).

## Wo Speicher verwendet wird

Die Tabelle listet die Funktionen auf, die Speicher senden, und was jede davon erhält.

| Funktion | Gesendeter Speicher | Bereich |
| --- | --- | --- |
| KI-Chat (Fragen, Zusammenfassungen, Problemlösung und die anderen Chat-Aktionen) | Ja | Global, plus die Verbindung, zu der die Anfrage gehört |
| KI-Agent und KI-Planung, einschließlich der Komplexitätsprüfung | Ja | Global, plus die Verbindung des Terminal-Tabs oder die Verbindung, die ein Swarm-Agent oder ein Job steuert |
| Swarm-Zusammenfassung, die die Ergebnisse der Agenten kombiniert | Ja | Nur global |
| Geplante Jobs | Ja | Global, plus die Verbindung des Jobs, wenn er eine hat |
| Workflow-Generator | Ja | Nur global |
| Aktionen mit einer strikten JSON-Antwort, wie die Snippet-Analyse-, -Überprüfungs-, -Vervollständigungs- und -Verbesserungsaktionen | Nein | Keine |
| Snippet-Beschreibung und Auswahlkorrektur, Auswahlübersetzung, Mermaid-Diagramme und KI-ASCII-Artbilder | Nein | Keine |

Der Aufruf zur Regelerkennung erhält kein Gedächtnis, und die Gedächtnisbereinigung sendet nur die Einträge, mit denen sie arbeitet (siehe [Bereinigung und Archiv](#aufraumen-und-archivieren)).

## Der Memory-Tab

Öffnen Sie **KI > KI-Manager > Memory**. Der Tab zeigt die Schalter, jeweils einen Bereich, das Budget mit dem, was gesendet wird, die Einträge oder das rohe Markdown der Datei des Bereichs sowie die Aufräum- und Historie-Aktionen.

![The Memory tab of the KI-Manager](../assets/screenshots/ai/ai-memory-tab.png)

| Steuerung | Beschriftung | Wirkung |
| --- | --- | --- |
| Schalter | `Use memory` | Schaltet Ihr eigenes Gedächtnis ein oder aus. Aus bedeutet, dass Ihre Gedächtnisdateien und Ihre Referenzdateien nicht gesendet werden und nichts gelernt wird. Organisationsregeln folgen weiterhin der Richtlinie. Ihre Organisation kann den Schalter sperren. |
| Lernen | `Learning`, mit `Suggest rules` oder `Off` | `Suggest rules` lässt korTTY nach einer Nachricht, die darum bittet, etwas aufzubewahren, eine Regel vorschlagen. `Off` schlägt keine Regeln vor. |
| Budget | `Token budget` | Die Anzahl der Speicher-Token, die ein Request mitführen darf: 500 bis 4000 in Schritten von 100, Standardwert 1500. Eine von Ihrer Organisation festgelegte Obergrenze wird unter dem Feld angezeigt. |
| Bereich | `Scope`, mit `Global (all connections)` oder einer Verbindung | Wählt die Datei aus, die der Tab anzeigt und die seine Schaltflächen ändern. |
| Ansichten | `Entries` und `Markdown` | `Entries` listet eine Zeile pro Absatz oder korTTY-Eintrag, mit `Edit` und `Delete`. `Markdown` zeigt die Rohdatei in einem Editor, mit `Save file`. |
| Budgetleiste | `{0} of {1} tokens · {2} not sent` | Wie viel des Budgets dieser Bereich ausfüllt und wie viele Einträge nicht gepasst haben. Die Leiste wird bei 80 Prozent gelb und bei 100 Prozent rot. |
| Aufräumen | `Clean up...` | Führt korTTY-Einträge zusammen oder archiviert ältere. Siehe [Aufräumen und Archivieren](#aufraumen-und-archivieren). |
| Archiv | `Show archive…` | Listet die archivierten korTTY-Einträge und ermöglicht es Ihnen, sie wiederherzustellen oder zu löschen. |
| Verlauf | `History...` | Listet die früheren Versionen der Datei und stellt eine davon nach einer Bestätigung wieder her. |
| Leeren | `Clear...` | Leert die Datei, nachdem Sie einen Diff bestätigt haben. Die vorherige Version bleibt im Verlauf. |
| Neu laden | `Reload` | Liest die Datei erneut von der Festplatte. |
| Speichern | `Save` | Speichert die Schalter, den Lernmodus und das Budget. |

Jede Zeile in der Einträge-Ansicht zeigt ein Abzeichen und einen Status. Das Abzeichen lautet `Your text` oder `korTTY` mit dem Datum und der Quelle des Eintrags. Der Status lautet `sent`, wenn die Zeile Teil des Blocks der nächsten Anfrage ist, und `not sent`, wenn das Budget sie auslässt.

Warnungen erscheinen über den Zeilen. `From your organization` listet die Organisationsregeln auf, die Sie lesen, aber nicht ändern können. Ein Richtlinien-Banner erklärt, wann die Richtlinie den Speicher abschaltet oder schreibgeschützt macht, und ein Banner nennt die Umgebungsvariable, wenn der [Kill-Schalter](#not-aus) gesetzt ist. Ein Geheimnis-Banner nennt die Zeile einer Datei, die ein Geheimnis enthalten kann (siehe [Geheimnisschutz](#geheimnis-schutz)).

### Globaler und Verbindungsspeicher

Das globale Memory gilt für alle Verbindungen. Das Verbindungs-Memory gilt für eine gespeicherte Verbindung, beispielsweise für Regeln zu einem Server. Um es zu bearbeiten, öffnen Sie den Verbindungs-Editor, wechseln Sie zum **KI**-Tab und wählen Sie **Verbindungs-Memory bearbeiten...**. Die Schaltfläche ist verfügbar, sobald die Verbindung gespeichert wurde, da ihre Memory-Datei nach der stabilen ID der Verbindung benannt ist. Das Memory öffnet sich in einem eigenen Fenster mit dem auf diese Verbindung gesetzten Geltungsbereich, und seine Einstellungen werden gespeichert, wenn das Fenster geschlossen wird.

Wenn eine Anfrage Memory enthält, gelten zuerst die Organisationsregeln, dann das Verbindungs-Memory, dann das globale Memory und zuletzt die Referenzdateien.

## Eigener Text ist geschützt

korTTY ändert eine Speicherdatei nur, wenn Sie es dazu auffordern, oder wenn Sie eine von ihm vorgeschlagene Änderung bestätigen. Jede Änderung, die korTTY eigenständig vornimmt, benötigt eine eigene Bestätigung, die nur diese eine Änderung abdeckt. Eine Änderung wird abgelehnt, wenn sie einen von Ihnen geschriebenen Absatz verändern würde, wenn die Datei in der Zwischenzeit außerhalb von korTTY geändert wurde oder wenn der neue Text ein Geheimnis enthält.

**Leeren**, die Bereinigung und das Kürzen zeigen zuerst eine Diff-Ansicht der Änderung, und es wird nichts gespeichert, bis Sie die Schaltfläche in diesem Diff-Dialog drücken.

Jede Änderung speichert zuerst die vorherige Datei in `memory/history`. korTTY behält bis zu 100 Versionen pro Bereich und entfernt Versionen, die älter als 365 Tage sind. **Verlauf...** listet diese auf und zeigt die Abweichung zur aktuellen Datei. Das Wiederherstellen einer Version verlangt eine Bestätigung, und der ersetzte Text bleibt im Verlauf, damit Sie darauf zurückkehren können.

Wenn sich die Datei außerhalb von korTTY ändert, während Sie die Markdown-Ansicht bearbeiten, bleiben Ihre ungespeicherten Änderungen erhalten und die Ansicht weist darauf hin, dass sich die Datei geändert hat. Laden Sie die Datei neu, um den neuen Text zu sehen.

## Budget und was zuerst gesendet wird

Jede Anfrage enthält einen Speicherblock von höchstens dem Token-Budget, das standardmäßig 1500 Tokens beträgt. Innerhalb dieses Budgets fügt korTTY die Teile in dieser Reihenfolge hinzu:

1. Organisationsregeln, immer vollständig. Sie werden nicht für das Budget gekürzt, lassen aber weniger Platz für alles andere.
2. Verbindungs-Memory: Ihre Absätze in Datei-Reihenfolge, dann die korTTY-Einträge, neueste zuerst.
3. Globales Memory: dieselbe Reihenfolge wie das Verbindungs-Memory.
4. Genehmigte Referenzdateien, innerhalb ihrer eigenen Obergrenze von insgesamt 1000 Tokens (siehe [Weitere Speicherdateien](#weitere-speicherdateien)).

Ein Absatz oder Eintrag wird entweder vollständig gesendet oder weggelassen; korTTY unterbricht Text nie in der Mitte. Ein Element, das nicht passt, wird übersprungen, und ein kleineres Element danach kann weiterhin gesendet werden. Der Block teilt dem Modell mit, wie viele Einträge weggelassen wurden, und die Budgetleiste sowie die Zeile **Mitgesendet** zeigen dieselbe Anzahl.

Eine Speicherdatei hat eine weiche Grenze von zweimal dem Budget, in Tokens geschätzt. Über dieser Grenze lehnt korTTY es ab, weiteren Text hinzuzufügen, und bittet Sie, die Datei zunächst aufzuräumen. Eine Datei hat außerdem eine harte Grenze von 64 KB. Wenn Ihre Organisation eine Obergrenze für das Budget festlegt, stoppt das Feld im Memory-Tab bei dieser Obergrenze.

## Aufräumen und Archivieren

**Aufräumen…** bietet drei Schritte. Jeder hat seine eigene Bestätigung.

- **korTTY-Einträge zusammenfassen** bittet das Standard-KI-Profil, die korTTY-Einträge in eine Liste ohne Duplikate zusammenzufassen. Nur die korTTY-Einträge werden an das KI-Profil übermittelt, und Sie sehen das Ergebnis als Diff, bevor etwas gespeichert wird. Ihr eigener Text bleibt unverändert. Dieser Schritt benötigt ein Standard-KI-Profil sowie einen entsperrten Master-Passwort-Tresor, wenn der API-Schlüssel dieses Profils dort gespeichert ist.
- **Alte korTTY-Einträge archivieren** listet die korTTY-Einträge auf, die älter als 180 Tage sind. Sie wählen aus, welche davon verschoben werden sollen. Einträge, die Sie nicht anhaken, bleiben in der Datei.
- **Schlage auch Kürzungen meiner Absätze vor** bittet die KI um kürzere Versionen Ihrer Absätze. Der Vorschlag wird als separate Differenz angezeigt, er ändert Ihren eigenen Text und wird erst gespeichert, nachdem Sie auf **Übernehmen** klicken. Er wird niemals mit der Zusammenführung kombiniert.

Wenn die KI-Antwort nicht verwendet werden kann oder nicht mit den gesendeten Einträgen übereinstimmt, wird nichts geändert und der Tab gibt dies an.

**Archiv anzeigen…** listet die archivierten Einträge auf, neueste zuerst. **Wiederherstellen** setzt die ausgewählten Einträge nach einer Bestätigung am Ende der Datei wieder ein und prüft sie wie jede andere korTTY-Änderung. **Endgültig löschen…** entfernt die ausgewählten Einträge endgültig aus dem Archiv, was nicht rückgängig gemacht werden kann. Das Archiv wird in `memory/archive.md` gespeichert.

## Weitere Speicherdateien

Andere KI-Tools verwenden eigene Regeldateien, zum Beispiel `CLAUDE.md`, `AGENTS.md`, Gemini- oder Qwen-Anweisungen, Kiro-Steuerungsdateien, Cline-Regeln, GitHub-Copilot-Anweisungen oder Cursor-Regeln. korTTY kann genehmigte Kopien dieser Dateien als Referenzmaterial lesen. Sie sind schreibgeschützt: korTTY schreibt niemals in sie und löscht sie niemals.

Im Bereich **Weitere Memory-Dateien** des Memory-Tabs wählen Sie **Suchen...**, um in den Orten zu suchen, die die bekannten Tools verwenden, oder **Datei wählen...**, um eine Datei selbst auszuwählen. **Suchen...** durchsucht Ihren Home-Ordner und, wenn Sie einen auswählen, einen Projektordner, den Sie benennen. Nur Markdown-Dateien (`.md` und `.mdc`) können hinzugefügt werden. Es wird nichts gelesen, bis Sie eine Datei auswählen.

Der Freigabe-Dialog zeigt den vollständigen Text der Datei an, bevor Sie ihn freigeben. Unsichtbare Zeichen werden entfernt, der Pfad und das Tool werden angezeigt, und eine Warnung erscheint, wenn sich die Datei in einem Git-Repository befindet, da ein Repository Dateien enthalten kann, die von anderen Personen stammen. Drücken Sie **Freigeben und senden**, um den Text genau so zu bestätigen, wie er angezeigt wird.

Jede genehmigte Datei verfügt über einen **Senden**-Schalter und einen Status:

| Status | Bedeutung |
| --- | --- |
| `active` | Die Datei wird mit dem von Ihnen freigegebenen Text gesendet. |
| `changed` | Die Datei wurde nach Ihrer Freigabe geändert, daher wird sie nicht gesendet. Drücken Sie **Erneut freigeben...** und prüfen Sie den neuen Text, um ihn zu senden. |
| `missing` | Die Datei existiert nicht mehr. |
| `refused` | korTTY lehnt die Datei ab, weil ihr Text möglicherweise ein Geheimnis enthält oder weil ihr Speicherort nicht erlaubt ist. Der Grund wird angezeigt. |
| `off` | Der **Senden**-Schalter ist aus. |

Die Grenzen sind wie folgt:

- korTTY liest eine Datei von bis zu 1 MB; eine größere Datei wird abgelehnt.
- Jede Datei fügt einer Anfrage höchstens 16 KB hinzu, und eine längere Datei wird an einer Absatz- oder Zeilengrenze gekürzt.
- Alle genehmigten Dateien fügen einer Anfrage zusammen höchstens 1000 Tokens hinzu.
- Der Text wird in einem Block gesendet, der als Referenzdaten markiert ist, sodass er die korTTY-Regeln nicht überschreiben kann.
- Eine Datei muss sich in Ihrem Home-Ordner, im Projektordner, den Sie ausgewählt haben, oder in einem Ordner befinden, den Ihre Organisation zulässt, und sie darf sich nicht auf einem Netzlaufwerk befinden.
- korTTY liest nur Dateien auf diesem Computer, niemals Dateien auf einem Remote-Host, den Sie über SSH erreichen.

Für ein **Lokale CLI** KI-Profil sendet korTTY die Referenzdateien nicht, die die CLI selbst liest, so werden sie nicht doppelt gesendet. Die Tabelle umfasst die CLIs von Claude Code, Codex, Gemini CLI, OpenCode, Kilo, Qwen Code, Kiro, Cursor agent und Cline.

## Die Triage-Karte

Bevor ein interaktiver Agent-Lauf startet, kann korTTY die Anfrage bewerten und Lösungsoptionen anbieten. Die Einstellung lautet **Komplexität einschätzen** in **KI > KI-Manager > Allgemein**, im Abschnitt Terminal-Agent.

| Modus | Verhalten |
| --- | --- |
| `Automatic` | Vor jeder qualifizierten Ausführung bewertet ein kleiner KI-Aufruf die Anfrage. Nur Anfragen, die komplex erscheinen, erhalten die Optionskarte. |
| `Always` | Jede qualifizierte Ausführung zeigt zuerst die Optionskarte. |
| `Never` | Anfragen werden so ausgeführt, wie sie sind, ohne Prüfung und ohne Karte. |

Ihre Organisation kann diesen Modus festlegen und ihn sperren.

Berechtigte Ausführungen sind interaktive KI-Agent-Ausführungen: im klassischen KI-Agent-Panel, im Ausführungs-Tab des Chat-Fensters und im Host eines Agent-Designs. Eine Ask-Anfrage, die ohne Ausführung von Befehlen antwortet, eine Ausführung mit einem bereits akzeptierten Plan und eine Ausführung in einer Richtlinie, die kein Planen erlaubt, werden nicht geprüft. Swarm-Ausführungen und geplante Jobs erhalten die Karte nie.

Bei **Automatisch** und **Immer** führt jede berechtigte Ausführung zunächst einen zusätzlichen kleinen KI-Aufruf aus, der das Gedächtnis der Ausführung mitführt. Dies verursacht eine zusätzliche Anfrage pro Ausführung. Der Aufruf hat eine Zeitbegrenzung von zwei Minuten. Wenn er fehlschlägt oder keine verwertbare Antwort liefert, setzt die Ausführung wie angefordert fort, und das Aktivitätsprotokoll vermerkt dies.

![Solution options card for a complex request](../assets/screenshots/ai/ai-triage-card.png)

Die Karte besteht aus diesen Teilen:

- eine Kopfzeile, die angibt, ob die Anfrage komplex erscheint, samt den Gründen für diese Einschätzung;
- eine Karte pro Option, zwei oder drei davon, wobei die empfohlene Option zuerst steht und mit **Empfohlen** markiert ist; jede Option zeigt ihre Machbarkeit, und ihr Abschnitt **Einzelheiten** mit den Risiken, Voraussetzungen, Schritten und Alternativen öffnet sich, wenn Sie diese Option auswählen;
- **Eigene Idee**, mit einem Textfeld für Ihren eigenen Ansatz oder eine Anweisung;
- eine optionale **Dauerhafte Regel**, mit dem Text der Regel und einer **Speichern in**-Auswahl.

Die **Speichern in**-Auswahl bietet **Globales Memory**, **Diese Verbindung**, ein vorhandenes Skill oder ein neues Skill. Eine Regel wird nur gespeichert, wenn Sie auf **Mit Auswahl fortfahren** klicken. Eine Regel, die ein Geheimnis enthält, wird abgelehnt, und die Karte bleibt mit der abgelehnten Zeile geöffnet.

| Button | Ergebnis |
| --- | --- |
| `Proceed with selection` | Führt die gewählte Option als Plan für diesen Lauf aus. Wird **Eigene Idee** ausgewählt, ist Ihr Text der Plan; andernfalls wird Ihr Text der gewählten Option als Anweisung hinzugefügt. Speichert die dauerhafte Regel, wenn Sie eine geschrieben haben. |
| `Run as requested` | Führt die Anfrage so aus, wie Sie sie geschrieben haben. Text, den Sie im Ideenfeld eingegeben haben, wird als Anweisung hinzugefügt. |
| `Cancel` | Beendet den Lauf, bevor etwas ausgeführt wird. |

Wenn Sie innerhalb von 10 Minuten nach dem Erscheinen der Karte nicht antworten, wird die Anfrage wie gewünscht ausgeführt.

## Regellernen

Bei **Lernen** auf **Regeln vorschlagen** eingestellt sucht korTTY in Ihren eigenen Nachrichten nach einer dauerhaften Regel, nachdem die Antwort oder der Lauf abgeschlossen ist. Die Prüfung erfolgt in Schritten, und bei den meisten Nachrichten wird früh gestoppt.

1. Ein lokaler Filter sucht nach einem dieser mehrwörtigen Hinweise, auf Deutsch und auf Englisch: `ab jetzt`, `ab sofort`, `in zukunft`, `künftig`, `zukünftig`, `merk dir`, `merke dir`, `verwende immer`, `benutze immer`, `nutze immer`, `from now on`, `remember that`, `always use`, `never use`, `in the future`, `going forward` und `make sure to always`. Ein einzelnes Wort wie `immer` zählt nie.
2. Nachrichten länger als 2000 Zeichen, Nachrichten, die überwiegend aus Code und Fragen bestehen, werden übersprungen. Eine höfliche Anfrage, die mit einem Fragezeichen endet, ist nach wie vor eine Anfrage.
3. Eine Nachricht mit einem möglichen Geheimnis wird niemals irgendwohin gesendet.
4. Andernfalls entscheidet ein kleiner KI-Aufruf, ob die Nachricht nach einer dauerhaften Regel fragt. Der Aufruf verwendet das Profil des Chats oder der Ausführung, die Nachricht wird für dieses Profil maskiert, und der Aufruf verfügt über kein Gedächtnis, keine KI-Skills und keine Internet-Tools.

Die Grenzen betragen höchstens 10 dieser Aufrufe und 3 Vorschlagskarten pro Tag, höchstens eine Karte pro Antwort oder Ausführung, und eine Erkennung, die länger als 90 Sekunden dauert, wird verworfen. Eine Regel, die Sie aufgeschoben oder abgelehnt haben, wird für dieselbe Nachricht nicht erneut vorgeschlagen.

![Rule proposal card under a chat answer](../assets/screenshots/ai/ai-rule-proposal.png)

Die Vorschlagskarte erscheint unter der Chat-Antwort oder in der Aktivitätsliste des Agent-Laufs; bei anderen Agent-Designs erscheint sie im Popup des Designs. Sie zeigt diese Teile:

- die Regel als editierbarer Text mit maximal 300 Zeichen, mit einem Zähler;
- das Ziel und eine Vorschau, wo die Regel gespeichert wird;
- **Speichern**, **Nicht jetzt**, die nach 7 Tagen erneut fragt, und **Nie für diese Regel**.

Nichts wird gespeichert, bis Sie auf **Speichern** klicken. Danach erscheint für 30 Sekunden eine Benachrichtigung mit einer **Rückgängig**-Schaltfläche, die die Regel wieder entfernt.

Sie können auch selbst eine Regel hinzufügen. Das Menü Ihrer eigenen Chatnachricht und der Agent-Prompt-Dialog bieten **Als Regel merken…**, die dieselbe Karte für den gewählten Text öffnet. Dafür wird kein KI-Aufruf ausgeführt.

### Wohin eine Regel gehört

korTTY schlägt für jede bestätigte Regel ein Ziel vor, und die erste passende Regel gewinnt:

1. Eine Regel, die die aktive Verbindung oder deren Host benennt, wird in den Speicher dieser Verbindung aufgenommen.
2. Eine Regel zu einem Thema mit einer eingebauten KI-Skill, wie z. B. Perl, wird in die erlernten Regeln dieser KI-Skill aufgenommen. Eine KI-Skill, die Sie mit demselben Thema getaggt haben, wird gegenüber der eingebauten KI-Skill bevorzugt.
3. Eine Regel zu einer bekannten Sprache oder einem Tool, das noch keinen Skill besitzt, erstellt einen neuen korTTY-Skill, der mit dieser Regel beginnt.
4. Jede andere Regel wird in den globalen Speicher aufgenommen.

Regeln werden nur dann in einen Skill aufgenommen, wenn **korTTY darf Skills erstellen und gelernte Regeln darin speichern** aktiviert ist in **KI > KI-Manager > KI-Skills**. Zum Beispiel, wenn Sie "Ab jetzt statische Variablen in Perl mit `state` deklarieren" schreiben, wird der integrierte Perl-Skill als Ziel vorgeschlagen, und wenn Sie auf **Speichern** klicken, wird die Regel als eine seiner gelernten Regeln gespeichert. Der integrierte Text des Skills bleibt unverändert.

Ein neuer Skill trägt das Badge **NEU · von korTTY erstellt**, bis Sie ihn in der Skill-Liste auswählen, und dann lautet das Badge **korTTY**. Ein Skill mit gelernten Regeln zeigt ein **+N Regel(n)**-Chip. Seine gelernten Regeln werden im Abschnitt **Gelernte Regeln** aufgelistet, wo Sie sie bearbeiten oder löschen können. Gelernte Regeln überstehen ein Zurücksetzen oder ein automatisches Update eines integrierten Skills. Sie werden zusammen mit dem Skill gesendet, wann immer der Skill für eine Anfrage ausgewählt wird, solange das Gedächtnis aktiviert ist. Zur Größe und Verwendung von Skills siehe [KI-Skills](../reference/settings/ai-skills.md).

## Mitgesendet

Unter jeder Chat-Antwort und in jedem Agentenlauf beginnt eine eingeklappte Zeile mit **Mitgesendet:**, gefolgt von den Zählungen und den Tokens dessen, was gesendet wurde: Gedächtnis, Skills und Dateien.

![The Sent along row, expanded](../assets/screenshots/ai/ai-sent-context.png)

Im aufgefalteten Zustand listet die Zeile die Speichereinträge mit ihrer Herkunft (Organisation, global oder Verbindung), die Skills (automatisch oder angeheftet, und gekürzt, sofern dies zutrifft) sowie die Referenzdateien, jeweils mit ihren Tokens. Die Texte sind die maskierten Kopien, die der Anbieter erhalten hat, und der Tooltip eines Eintrags zeigt dessen Text an. Falls Einträge nicht in das Budget passten, gibt die Zeile an, wie viele. Der Design-Host anderer Agent-Designs verlinkt auf dieselben Informationen.

## Geheimnis-Schutz

korTTY verweigert es, ein Geheimnis in etwas zu speichern, das die KI zurückliest: KI-Skills, erlernte Regeln, Speicherdateien und genehmigte Referenzdateien. Die Wächterfunktion prüft vier Arten von Text, und die erste Übereinstimmung verweigert den gesamten Text:

- ein Token in einem bekannten Format, wie ein privater Schlüssel oder ein Anbieter-Token;
- die Zuweisung eines Passworts oder Tokens mit einem festen Wert, z. B. `db_password=…`;
- ein im Fließtext genanntes Passwort oder Token, z. B. "Das Passwort lautet …";
- eine lange, zufällig wirkende Zeichenfolge und, solange der Tresor entsperrt ist, ein Passwort, das Sie in korTTY gespeichert haben.

Die Ablehnung benennt die Zeile und die Art des Geheimnisses. Sie zeigt niemals den Wert an. Eine Datei, die bereits ein Geheimnis enthält, wird nur markiert, nicht verändert: Ihr Skill erhält in der KI-Skills-Liste ein Abzeichen mit dem Namen **mögliches Geheimnis**, und der Memory-Tab zeigt ein Banner mit der Zeile. Bevor eine Anfrage korTTY verlässt, ersetzt die ausgehende Maskierung auch die Werte von Passwort-Hinweisen im Fließtext, ausgenommen die integrierten lokalen Modelle.

!!! warning "Restrisiko"
    Die Wächterfunktion kann ein Passwort nicht erkennen, das kein Schlüsselwort enthält, kein bekanntes Format aufweist und nicht im Safe gespeichert ist. Schreiben Sie keine Passwörter, Schlüssel oder Tokens in den Speicher, in KI-Skills oder in Referenzdateien, und bewahren Sie diese im Safe oder in Ihrem Passwort-Manager auf.

## Not-Aus

Setzen Sie die Umgebungsvariable `KORTTY_AI_MEMORY` auf `off`, um Ihren Speicher für eine Sitzung auszuschalten, beispielsweise für Support-Sitzungen, Demonstrationen oder automatisierte Tests. Mit diesem Wert sendet korTTY keine Ihrer Speicherdateien, weder globale, Verbindungs- noch Referenzdateien, lernt keine Regeln und schlägt keine Regeln zum Speichern vor. Der Memory-Tab zeigt ein Banner, das die Variable benennt. Sie können die Dateien im Memory-Tab weiterhin selbst öffnen und bearbeiten.

Organisationsregeln werden nicht durch die Variable abgedeckt. Sie folgen der Unternehmensrichtlinie, die sie senden kann, selbst wenn Ihr eigener Speicher deaktiviert ist.

## Richtlinie und Dateien

Ihre Organisation steuert das Gedächtnis mit dem `ai-memory`-Feature, der `[rule.ai-memory]`-Tabelle und den `[[organization-memory]]`-Blöcken. Die Tabelle umfasst den Gedächtnismodus des Benutzers, das Lernen, die Skill-Erstellung, die Referenzdateien, das Budgetlimit, den Triage-Modus und die Skill-Statistiken. Die Organisationsregeln sind schreibgeschützt und stehen an erster Stelle in jedem Prompt. Siehe [Unternehmensrichtlinie](../reference/enterprise-policy.md) für jeden Schlüssel.

Die Speicherdateien befinden sich in `~/.kortty/memory/`: `MEMORY.md`, das `connections`-Verzeichnis, `history`, `archive.md`, `state.json`, `learning.json` und `external.json`. Die Skill-Statistik befindet sich in `~/.kortty/stats/`. korTTY legt diese Dateien nur für den Besitzer an, und sie sind Teil einer Sicherung. Eine Wiederherstellung fügt nur die Speicherdateien hinzu, die auf dem Computer noch nicht existieren, so dass sie Ihre aktuellen Speicherdaten niemals ersetzt, und sie stellt die Liste der genehmigten Referenzdateien niemals wieder her; genehmigen Sie diese auf dem neuen Computer erneut. Für die vollständige Liste der Dateien siehe [Konfigurationsdateien](../reference/config-files.md) und [Sicherung](backup.md).

## Verwandte Seiten

- [KI-Assistenz](ai-assistant.md) beschreibt den Chat und die KI-Profile, die das Gedächtnis übermitteln.
- [KI-Agent-Designs](ai-agent-designs.md) und [KI-Schwarm](ai-swarm.md) beschreiben die Läufe, die die Lösungsoptionen und die Zeile „Entlang gesendet" anzeigen.
- [KI-Skills](../reference/settings/ai-skills.md) beschreibt die Statistiken und erlernten Regeln.
- [KI-Einstellungen](../reference/settings/ai.md) beschreibt den Tab Allgemein, in dem der Triage-Modus festgelegt wird.
