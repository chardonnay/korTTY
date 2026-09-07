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
| AI Completion Shortcut | key recorder | One key, optionally with up to three modifiers (Ctrl/Cmd, Shift, Alt) | `Shift+Tab` | `snippetCompletionShortcut` |

!!! tip "Choosing the completion shortcut"
    Click the **AI Completion Shortcut** field and press the combination you want — the field records what you pressed, and **Reset** restores ++shift+tab++. A combination is one main key (a letter, digit, function key, ++tab++, ++space++, an arrow, ++enter++ and so on) with none, one, two or three modifiers, so ++tab++ alone, ++shift+tab++ and ++ctrl+alt+k++ are all valid. On macOS the ++ctrl++ modifier is the Cmd key, matching the editor's own shortcuts. The new combination applies to snippet editors opened afterwards; ++ctrl+space++ opens the list as well and cannot be reassigned. Choosing something other than ++shift+tab++ gives ++shift+tab++ back its normal editor meaning: it removes one level of indentation.

!!! note "Inheriting instead of overriding"
    These settings override the terminal/editor defaults for snippet windows only. Leave a field empty — or set the font size to `0` — to inherit the general setting from [Appearance](../appearance.md), [Colors](../colors.md) and [Editor](../editor.md) instead.

The snippet editor itself, including its AI code actions and language fields, is described under [Snippets](../../../features/snippets.md).
