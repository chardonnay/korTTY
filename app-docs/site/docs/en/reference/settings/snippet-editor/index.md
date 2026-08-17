---
title: Snippet Editor
---

# Snippet Editor

Font, color and cursor overrides for the Snippet Manager and the Snippet Edit dialog. Open via **Configuration → Global Settings → Snippet Editor**; stored in `~/.kortty/global-settings.xml`.

![Snippet Editor settings tab](../../../assets/screenshots/settings/snippet-editor.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Font Family | dropdown | Empty (inherit) or an installed monospace family | empty (inherit) | `snippetFontFamily` |
| Font Size | number | 0–72 (0 = inherit) | 0 (inherit) | `snippetFontSize` |
| Foreground Color | color | — | inherit (picker shows `#d4d4d4`) | `snippetForegroundColor` |
| Background Color | color | — | inherit (picker shows `#1e1e1e`) | `snippetBackgroundColor` |
| Cursor Style | dropdown | empty, BLOCK, LINE, UNDERSCORE | empty (inherit) | `snippetCursorStyle` |
| Cursor Color | color | — | inherit (picker shows `#FF0000`) | `snippetCursorColor` |

!!! note "Inheriting instead of overriding"
    These settings override the terminal/editor defaults for snippet windows only. Leave a field empty — or set the font size to `0` — to inherit the general setting from [Appearance](../appearance.md), [Colors](../colors.md) and [Editor](../editor.md) instead.

The snippet editor itself, including its AI code actions and language fields, is described under [Snippets](../../../features/snippets.md).
