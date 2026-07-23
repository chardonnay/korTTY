---
title: Editor
---

# Editor

Cursordarstellung für die Dateieditor-Registerkarten von korTTY. Öffnen über **Konfiguration → Globale Einstellungen → Editor**; in `~/.kortty/global-settings.xml` gespeichert.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Cursorstil | Dropdown-Liste | BLOCK, ZEILE, UNTERstrich | BLOCK | `editorCursorStyle` |
| Cursorfarbe | Farbe | – | `#FF0000` | `editorCursorColor` |

!!! note "Cursor-Stile"
    **BLOCK** ist ein breiter Cursor (2 px), **LINE** eine dünne vertikale Linie (1 px), **UNDERSCORE** eine mittlere Breite (1,5 px).

!!! note "Alles andere wird vererbt"
    Der Editor verwendet dieselbe Schriftfamilie, Schriftgröße sowie dieselben Vordergrund- und Hintergrundfarben wie Terminals, sodass diese auf dem konfiguriert werden [Aussehen](appearance.md) Und [Farben](colors.md) Tabs statt hier. Der [Snippet-Editor](snippet-editor.md) Tab kann sie für Snippet-Fenster überschreiben.
