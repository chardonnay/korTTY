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

!!! note "Erben statt Überschreiben"
    Diese Einstellungen überschreiben die Terminal-/Editor-Standardeinstellungen nur für Snippet-Fenster. Lassen Sie ein Feld leer – oder stellen Sie die Schriftgröße auf ein `0` – um die allgemeine Einstellung zu erben [Aussehen](../appearance.md), [Farben](../colors.md) Und [Editor](../editor.md) stattdessen.

Der Snippet-Editor selbst, einschließlich seiner AI-Code-Aktionen und Sprachfelder, wird unter [Snippets](../../../features/snippets.md) beschrieben.
