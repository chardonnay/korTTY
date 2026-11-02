# Versionshinweise

Was hat sich in der aktuellen Version geändert? Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.25.0

### KI-Memory und KI-Skills

- **KI-Memory** — korTTY verwaltet einen Speicher, den der KI-Chat und der Agent ihren Anfragen beifügen: einen globalen Speicher und einen pro Verbindung, als einfache Markdown-Dateien, die Sie lesen und bearbeiten können. Er verfügt über ein Token-Budget pro Anfrage, Ihr eigener Text wird niemals ohne Ihre Bestätigung geändert, und die von korTTY geschriebenen Einträge können zusammengeführt, archiviert und aus dem Archiv wiederhergestellt werden (Archiv anzeigen…); der Verlauf bewahrt frühere Versionen einer Speicherdatei, die Sie wiederherstellen können. Siehe [KI-Memory](../features/ai-memory.md).
- **Referenzdateien anderer Tools** — die CLAUDE.md, AGENTS.md und ähnliche Dateien anderer KI-Tools können als schreibgeschütztes Gedächtnis hinzugefügt werden, sobald Sie deren Text gelesen und genehmigt haben; eine geänderte Datei wartet erneut auf Ihre Genehmigung. Siehe [KI-Memory](../features/ai-memory.md).
- **Lösungsoptionen** — bevor eine komplexe Anfrage ausgeführt wird, kann der Agent eine empfohlene Option, Ihre eigene Idee und eine optionale dauerhafte Regel anbieten. **Komplexität einschätzen** legt fest, wann dies geschieht: Automatisch, Immer oder Nie. Siehe [Lösungsoptionen](../features/ai-tools.md#losungsoptionen).
- **Aus Gesprächen erlernte Regeln** — im Modus **Regeln vorschlagen** schlägt korTTY eine Regel vor, wenn eine Nachricht darauf hindeutet, dass etwas für später aufbewahrt werden soll; nichts wird ohne Ihre Bestätigung gespeichert, und eine gespeicherte Regel kann 30 Sekunden lang rückgängig gemacht werden. Regeln werden in den Speicher oder in ein Skill abgelegt, und korTTY kann ein neues Skill für ein Thema erstellen, das noch keines hat. Siehe [KI-Memory](../features/ai-memory.md).
- **Als Regel merken…** — speichert eine Regel, die Sie selbst schreiben, bei Ihren eigenen Chat-Nachrichten und im Prompt-Dialog des Agents, ohne KI-Aufruf.
- **Mitgesendet** — unter jeder Chat-Antwort und jedem Agentenlauf listet eine zusammengeklappte Zeile die Speichereinträge, Skills und Referenzdateien, die übermittelt wurden, maskiert wie beim Versand, mit ihren Token-Anzahlen.
- **Geheimnis-Schutz** — korTTY verweigert es, ein Passwort, einen Schlüssel oder ein Token in einem Skill, einer erlernten Regel, dem Speicher oder einer genehmigten Referenzdatei zu speichern, und markiert Skills und Speichernotizen, die bereits eines enthalten. Der Wert wird niemals angezeigt. Siehe [Geheimnisse in Skills](../reference/settings/ai-skills.md#geheimnisse-in-ki-skills).
- **Skill-Statistik** — der **Statistik**-Dialog der KI-Skills zeigt, wie oft jedes Skill verwendet wird, seine Größe und sein Wachstum, mit Hinweisen für Skills, die überprüft werden sollten. **Kürzen...** schlägt eine kürzere Version vor, die erst nach Ihrer Bestätigung gespeichert wird. Siehe [Skill-Statistik](../reference/settings/ai-skills.md#skill-statistik).
- **Importierte Skill-Updates** — ein Update aus dem Internet überschreibt niemals ein Skill, das Sie geändert haben; der Update-Dialog bietet dann an, die Version des Anbieters als Kopie zu importieren oder diese Version zu überspringen. Siehe [Externe Skills](../reference/settings/ai-skills.md#externe-skills).
- **Unternehmensrichtlinie** — die `[rule.ai-memory]` legt fest, wie das Gedächtnis verwendet werden darf, wie Regeln erlernt werden, ob korTTY Skills erstellen darf, welche Referenzdateien gelesen werden dürfen, das Token-Budget, den Triage-Modus und die Statistiken. `[[organization-memory]]` fügt Regeln hinzu, die jeder Benutzer erhält und die nicht geändert werden können. Siehe [Unternehmensrichtlinie](../reference/enterprise-policy.md#ruleai-memory).
- **Backups** — das Gedächtnis und die Skill-Statistiken sind Teil eines Backups. Siehe [Backup & Wiederherstellung](../features/backup.md#inhalt-der-sicherungsdatei).

### Coding-Agents

- **Kilo CLI** — Das Coding-Agents-Panel erkennt jetzt **Kilo** (`kilo` oder `kilocode`, npm-Paket `@kilocode/cli`), einen Fork von OpenCode: aktiv, wartet auf Ihre Antwort und inaktiv an seinem Prompt. **Reject** in seinem Berechtigungsdialog fragt zunächst, was Kilo anders tun soll, sodass das Pane im Zustand *wartet auf Sie* bleibt, bis Sie mit ++enter++ bestätigen. Siehe [Was erkannt wird](../features/coding-agents.md#was-erkannt-wird).

### Lokale Modelle

- **Echtes Compute-Backend** — die **Backend**-Spalte der Tabellen für Laufzeiten und Modelle zeigt das GPU- oder CPU-Backend, auf dem eine Laufzeit rechnet, als CPU, Metal, Vulkan oder Auto: MLX-Laufzeiten und MLX-Modelle zeigen Metal an, statt „MLX“, und bevor eine Laufzeit installiert ist, zeigt die Spalte das Backend an, das eine Installation mitbringen würde, wie z. B. „Metal (bei Installation)“. Siehe [Laufzeiten](../features/local-models.md#laufzeiten).
