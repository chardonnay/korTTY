---
title: Snippet-Editor
---

# Snippet-Editor

Schriftart-, Farb- und Cursor-Überschreibungen für den Snippet-Manager und das Dialogfeld „Snippet bearbeiten“. Öffnen über **Konfiguration → Globale Einstellungen → Snippet-Editor**; in `~/.kortty/global-settings.xml` gespeichert.

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

!!! tip "Auswahl des Tastenkürzels für die Vervollständigung"
    Klicken Sie auf das Feld **Tastenkürzel KI-Vervollständigung** und drücken Sie die gewünschte Kombination – das Feld zeichnet auf, was Sie gedrückt haben, und **Reset** stellt ++shift+tab++ wieder her. Eine Kombination ist eine Haupttaste (ein Buchstabe, eine Ziffer, eine Funktionstaste, ++tab++, ++space++, ein Pfeil, ++enter++ usw.) mit keinem, einem, zwei oder drei Modifikatoren, sodass ++tab++ allein, ++shift+tab++ und ++ctrl+alt+k++ alle gültig sind. Unter macOS ist der Modifikator ++ctrl++ die Cmd-Taste und entspricht den eigenen Tastenkombinationen des Editors. Die neue Kombination gilt für danach geöffnete Snippet-Editoren; ++ctrl+space++ öffnet die Liste ebenfalls und kann nicht neu zugewiesen werden. Wenn Sie etwas anderes als ++shift+tab++ auswählen, erhält ++shift+tab++ wieder seine normale Editorbedeutung: Es wird eine Einrückungsebene entfernt.

!!! note "Vorgewärmter Editor"
    Wenn **Einen vorgewärmten Editor bereithalten** aktiviert ist, startet korTTY etwa zwei Sekunden nach dem Öffnen des ersten Snippet-Editors einer Sitzung einen Ersatzeditor im Hintergrund, sodass der nächste Snippet-Bearbeitungsdialog oder die nächste Snippet-Manager-Vorschau mit einem fertigen Editor startet, anstatt Monaco zu laden, während Sie warten. Der Ersatz wird erst erstellt, nachdem Sie einen Editor einmal verwendet haben, und er hält im Leerlauf eine WebKit-Seite im Speicher – schalten Sie die Option auf Computern mit wenig RAM aus, um einen kurzen Start pro Öffnung für diesen Speicher einzutauschen.

!!! note "Erben statt Überschreiben"
    Diese Einstellungen überschreiben die Terminal-/Editor-Standardeinstellungen nur für Snippet-Fenster. Lassen Sie ein Feld leer – oder stellen Sie die Schriftgröße auf ein `0` – um die allgemeine Einstellung zu erben [Aussehen](../appearance.md), [Farben](../colors.md) Und [Editor](../editor.md) stattdessen.

Der Snippet-Editor selbst, einschließlich seiner AI-Code-Aktionen und Sprachfelder, wird unter [Snippets](../../../features/snippets.md) beschrieben.
