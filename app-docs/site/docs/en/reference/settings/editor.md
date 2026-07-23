---
title: Editor
---

# Editor

Cursor appearance for korTTY's file editor tabs. Open via **Configuration → Global Settings → Editor**; stored in `~/.kortty/global-settings.xml`.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Cursor Style | dropdown | BLOCK, LINE, UNDERSCORE | BLOCK | `editorCursorStyle` |
| Cursor Color | color | — | `#FF0000` | `editorCursorColor` |

!!! note "Cursor styles"
    **BLOCK** is a wide cursor (2 px), **LINE** a thin vertical line (1 px), **UNDERSCORE** a medium width (1.5 px).

!!! note "Everything else is inherited"
    The editor uses the same font family, font size, foreground and background colors that terminals use, so those are configured on the [Appearance](appearance.md) and [Colors](colors.md) tabs rather than here. The [Snippet Editor](snippet-editor.md) tab can override them for snippet windows.
