---
title: Snippet-Editor
---

# Snippet-Editor

Schriftart, Farbe und Cursor-Übernahme für den Snippet-Manager und das Snippet-Edit-Fenster, sowie wie viele volle Code-Analysen korTTY pro Snippet speichert und wie groß der Text eines Skripts sein darf, um mit ihnen gespeichert zu werden. Öffnen über **Konfiguration → Globale Einstellungen → Snippet-Editor**; gespeichert in `~/.kortty/global-settings.xml`.

![Snippet Editor settings tab](../../../assets/screenshots/settings/snippet-editor.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Schriftartenfamilie | Dropdown-Liste | Leer (übernehmen) oder eine installierte Monospace-Familie | leer (übernehmen) | `snippetFontFamily` |
| Schriftgröße | Nummer | 0–72 (0 = erben) | 0 (erben) | `snippetFontSize` |
| Vordergrundfarbe | Farbe | – | erben (Auswahl zeigt `#d4d4d4`) | `snippetForegroundColor` |
| Hintergrundfarbe | Farbe | – | erben (Auswahl zeigt `#1e1e1e`) | `snippetBackgroundColor` |
| Cursorstil | Dropdown | leer, BLOCK, ZEILE, UNDERSCORE | leer (erben) | `snippetCursorStyle` |
| Cursorfarbe | Farbe | – | erben (Auswahl zeigt `#FF0000`) | `snippetCursorColor` |
| Tastenkürzel KI-Vervollständigung | Tastenrekorder | Eine Taste, optional mit bis zu drei Modifikatoren (Strg/Befehl, Umschalt, Alt) | `Shift+Tab` | `snippetCompletionShortcut` |
| Halten Sie einen vorgewärmten Editor bereit. | Kontrollkästchen | ein/aus | ein | `snippetEditorPrewarmEnabled` |
| Gespeicherte Analysen pro Snippet | Nummer | 1–20 | 5 | `snippetAnalysisHistoryMaxSize` |
| Gespeicherte Skriptgröße pro Analyse | Dropdown-Kontrolle | Aus, 256 KB, 512 KB, 1 MB, 2 MB, 5 MB | 1 MB | `snippetAnalysisMaxStoredContentBytes` |

!!! tip "Auswahl des Tastenkürzels für die Vervollständigung"
    Klicken Sie auf das Feld **Tastenkürzel KI-Vervollständigung** und drücken Sie die gewünschte Kombination – das Feld zeichnet auf, was Sie gedrückt haben, und **Reset** stellt ++shift+tab++ wieder her. Eine Kombination ist eine Haupttaste (ein Buchstabe, eine Ziffer, eine Funktionstaste, ++tab++, ++space++, ein Pfeil, ++enter++ usw.) mit keinem, einem, zwei oder drei Modifikatoren, sodass ++tab++ allein, ++shift+tab++ und ++ctrl+alt+k++ alle gültig sind. Unter macOS ist der Modifikator ++ctrl++ die Cmd-Taste und entspricht den eigenen Tastenkombinationen des Editors. Die neue Kombination gilt für danach geöffnete Snippet-Editoren; ++ctrl+space++ öffnet die Liste ebenfalls und kann nicht neu zugewiesen werden. Wenn Sie etwas anderes als ++shift+tab++ auswählen, erhält ++shift+tab++ wieder seine normale Editorbedeutung: Es wird eine Einrückungsebene entfernt.

!!! note "Vorgewärmter Editor"
    Wenn **Einen vorgewärmten Editor bereithalten** aktiviert ist, startet korTTY etwa zwei Sekunden nach dem Öffnen des ersten Snippet-Editors einer Sitzung einen Ersatzeditor im Hintergrund, sodass der nächste Snippet-Bearbeitungsdialog oder die nächste Snippet-Manager-Vorschau mit einem fertigen Editor startet, anstatt Monaco zu laden, während Sie warten. Der Ersatz wird erst erstellt, nachdem Sie einen Editor einmal verwendet haben, und er hält im Leerlauf eine WebKit-Seite im Speicher – schalten Sie die Option auf Computern mit wenig RAM aus, um einen kurzen Start pro Öffnung für diesen Speicher einzutauschen.

!!! note "Gespeicherte Analysen pro Snippet"
    Wie viele [Volltextanalyse-](../../../features/snippets.md#vollstandige-code-analyse) korTTY pro Snippet speichert. Wenn eine neue Analyse eines Snippets eintritt und die Historie den Grenzwert überschreitet, werden die ältesten Analysen entfernt; Analysen, die an einer Stelle fixiert sind, Analysen mit einem Ergebnis, das der Überprüfung bedarf, und Analysen mit einem unterbrochenen Anwenden werden immer beibehalten, sodass die Historie mehr als den Grenzwert enthalten kann. Das Verringern des Werts führt nicht sofort zu einer Löschung: Die Einstellungen-Tabelle zeigt, wie viele ältere, nicht fixierte Analysen die nächste Analyse jedes Snippets entfernen würde, und sie werden erst dann gelöscht, wenn diese Analyse eintritt. Um Analysen jetzt zu entfernen, verwenden Sie **Verwerfen** oder **Alle löschen** im Analysepanel.

!!! note "Gespeicherte Skriptgröße pro Analyse"
    Der größte Skripttext – gemessen in Bytes von UTF-8 – der von korTTY gespeichert wird [Vollständige Code-Analyse](../../../features/snippets.md#gespeicherter-skripttext)Jede Analyse kann mehrere Kopien des Skripts enthalten (der analysierten Text, der Text, aus dem die Anwendung gestartet wurde, das vorgeschlagene Ergebnis und der angenommene Text); jede Kopie bis zu dieser Größe wird vollständig gespeichert, identische Kopien nur einmal. Der Standardwert beträgt **1 MB**, der maximale Wert **5 MB**, und **Aus** speichert den Skripttext überhaupt nicht – die Erkenntnisse, das Diagramm und die Berichte bleiben jedoch. Ein größerer Skripttext wird weiterhin analysiert und kann weiterhin in der Editor-Ansicht angewendet und überprüft werden; nachfolgend fehlen lediglich die gespeicherten Kopien, sodass **Änderungen ansehen**, der Code-Vorschau eines älteren Eintrags, der plain-text Skriptexport, der Berichtsabschnitt, **Zwischenstand wiederherstellen** sowie die Fortsetzung einer unterbrochenen Anwendung nach einem Neustart nicht für diesen verfügbar sind und das Panel erklärt, warum. Die Reduzierung des Werts löscht nichts: Text, der bereits gespeichert wurde, bleibt erhalten, und der neue Grenzwert gilt ab diesem Zeitpunkt für zukünftige Analysen. Ein höherer Wert verbraucht mehr Plattenplatz. `~/.kortty/snippet-analyses/`Eine Datei, die sich über 60 MB erweitert, entfernt zuerst den gespeicherten Text der ältesten Analysen. Ein Administrator kann den Wert begrenzen oder das Speichern des Skripttextes verbieten. [Unternehmensrichtlinie](../../enterprise-policy.md#gespeicherter-skripttext-der-analysen)Die Dropdown-Liste bietet dann nur das, was die Richtlinie erlaubt, und zeigt dies unten an. Wenn die Richtlinie das Speichern von Skripttext verboten hat, ist die Dropdown-Liste gesperrt.

!!! note "Erben statt Überschreiben"
    Diese Einstellungen überschreiben die Terminal-/Editor-Standardeinstellungen nur für Snippet-Fenster. Lassen Sie ein Feld leer – oder stellen Sie die Schriftgröße auf ein `0` – um die allgemeine Einstellung zu erben [Aussehen](../appearance.md), [Farben](../colors.md) Und [Editor](../editor.md) stattdessen.

Der Snippet-Editor selbst, einschließlich seiner KI-Code-Aktionen und Sprachfelder, wird unter [Snippets](../../../features/snippets.md) beschrieben.
