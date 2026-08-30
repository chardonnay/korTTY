# Versionshinweise

Was hat sich in der aktuellen Version geändert? Die Version, für die diese Anleitung erstellt wurde, wird in der Fußzeile angezeigt.

## v2.13.1

### Snippets

- **Vier neue KI-Diagrammtypen** – neben dem Flussdiagramm mit logischer Struktur kann der Snippet-Editor jetzt **Sequenz**-, **Zustands**-, **Klassen**- und **ER**-Diagramme generieren. Jede Familie verwendet ihren eigenen kompakten, sicherheitsbeschränkten Mermaid-Dialekt und ihre eigene integrierte Qualitätsfähigkeit und rendert wie zuvor im gebündelten Offline-Mermaid-Renderer. Siehe [Mermaid-Diagramme](../features/snippets.md#mermaid-diagramme).
- **Mehrere Diagramme pro Snippet** – ein Snippet speichert jetzt eine beliebige Anzahl von Diagrammen. Das Diagrammfenster listet sie mit Familie, Titel und Linienbereich auf, bietet **Neues Diagramm** mit einer Typauswahl und kann ein ausgewähltes Diagramm **löschen**; **Neu erzeugen** behält die Familie und den Umfang jedes Diagramms bei.
- **Diagramm aus einer Auswahl** – Wählen Sie einen Teil eines Skripts aus und wählen Sie einen Diagrammtyp aus dem neuen Kontext-Untermenü **Diagramm generieren** des Editors aus, um nur diese Linien grafisch darzustellen. Das Diagramm merkt sich den Zeilenbereich, seine Codereferenzen zeigen auf die echten Snippet-Zeilen und bei der Neugenerierung werden dieselben Zeilen erneut gelesen.

### AI-Assistent

- **[Vollständige Codeanalyse](../features/snippets.md#ai-codeaktionen) schlägt bei einigen lokalen Reasoning-Modellen nicht mehr fehl** – wenn korTTY ein streng strukturiertes Ergebnis von einem lokalen Server anfordert, geben bestimmte Reasoning-Modelle ihre vollständige Antwort im Reasoning-Kanal zurück und lassen die eigentliche Antwort leer. korTTY betrachtete dies als leere Antwort und verwarf eine fertige Analyse nach minutenlanger Arbeit. korTTY erkennt nun eine solche Antwort und nutzt die bereits enthaltene Analyse. Nur wenn dieser Text unbrauchbar ist, wird ein zweites Mal gefragt, ohne das strikte Format. Dies wurde bei den neueren Qwen-Reasoning-Modellen in LM Studio beobachtet und wirkte sich auf **Vollständige Codeanalyse** und **Auswahl übernehmen** im Snippet-Editor aus. Modelle, die eine normale Antwort zurückgeben, sind davon nicht betroffen.

### Terminal

- **Öffnen im Snippet-Editor löst nach einem Benutzerwechsel nicht mehr den falschen Pfad auf** – nach einem Identitätswechsel innerhalb einer Sitzung, beispielsweise mit `su - root` oder einem in eine lokale Shell eingegebenen `ssh`, löste der Kontextmenüeintrag einen ausgewählten Dateinamen anhand der Verzeichnisse des ursprünglichen Logins auf und lud nichts oder eine falsche gleichnamige Datei. Der Eintrag ist jetzt ausgegraut, während die Sitzung unter einer anderen Identität ausgeführt wird, und wird von selbst wieder aktiviert, sobald in der Eingabeaufforderung wieder der ursprüngliche Benutzer angezeigt wird. Siehe [Lokale Shell-Registerkarten](../features/terminal.md#lokale-shell-registerkarten).
- **Ein Split fragt nicht mehr erneut nach dem Grund der Verbindung** – wenn ein Server einen Grund für den Vorgang wünscht, wie es ein Jump-Host im CyberArk-Stil tut, öffnete jeder Split eines Tabs diesen Dialog erneut, obwohl der Grund beim Öffnen des Tabs angegeben wurde. korTTY sendet nun die bereits in diesem Tab angegebene Antwort. Es wird trotzdem gesendet und nicht übersprungen, da ein Server, der nach einem Grund fragt, eine Sitzung schließt, die mit nichts antwortet. Bei einer Aufteilung auf einen anderen Server oder bei einem Server, der etwas anderes fragt, wird ebenfalls einmal gefragt, und ein neuer Tab beginnt immer mit der Frage. Verweigert der Server die Begründung, weil beispielsweise eine Ticketnummer inzwischen abgelaufen ist, verwirft korTTY diese und fragt erneut nach. Siehe [Split-Screen mit Broadcast](../features/terminal.md#vorgange-aufteilen).

!!! note "Frühere Versionen"
    Hier wird nur die aktuelle Version aufgeführt, daher bleibt der Anleitung in jeder Sprache, in die er übersetzt wurde, kurz. Jede Version ist auf der [GitHub-Release-Seite ](https://github.com/chardonnay/korTTY/releases); Die kuratierten Notizen für frühere Versionen werden im Repository in `app-docs/release-notes-archive.md` und `app-docs/RELEASE_NOTES.adoc` aufbewahrt.
