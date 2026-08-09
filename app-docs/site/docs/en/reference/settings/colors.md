---
title: Colors
---

# Colors

Configure terminal display colors including text, background, cursor, selection, and the 16-color ANSI palette. Open via **Configuration → Global Settings → Colors**; stored in `~/.kortty/global-settings.xml`.

![Colors settings tab](../../assets/screenshots/settings/colors.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Color Profile | dropdown | Theme names (e.g., GitHub Dark, Dracula, etc.) | GitHub Dark | `themeId` |
| Text Color | color | RGB hex color | #FFFFFF | `foregroundColor` |
| Background | color | RGB hex color | #1E1E1E | `backgroundColor` |
| Cursor | color | RGB hex color | #FFFFFF | `cursorColor` |
| Cursor blinks | toggle | — | On | `cursorStyle` |
| Selection | color | RGB hex color | #3399FF | `selectionColor` |
| Enable terminal colors | toggle | — | On | `terminalColorsEnabled` |
| Normal: Black | color | RGB hex color | #000000 | `ansiBlack` |
| Normal: Red | color | RGB hex color | #CD0000 | `ansiRed` |
| Normal: Green | color | RGB hex color | #00CD00 | `ansiGreen` |
| Normal: Yellow | color | RGB hex color | #CDCD00 | `ansiYellow` |
| Normal: Blue | color | RGB hex color | #0000EE | `ansiBlue` |
| Normal: Magenta | color | RGB hex color | #CD00CD | `ansiMagenta` |
| Normal: Cyan | color | RGB hex color | #00CDCD | `ansiCyan` |
| Normal: White | color | RGB hex color | #E5E5E5 | `ansiWhite` |
| Bright: Black | color | RGB hex color | #7F7F7F | `ansiBrightBlack` |
| Bright: Red | color | RGB hex color | #FF0000 | `ansiBrightRed` |
| Bright: Green | color | RGB hex color | #00FF00 | `ansiBrightGreen` |
| Bright: Yellow | color | RGB hex color | #FFFF00 | `ansiBrightYellow` |
| Bright: Blue | color | RGB hex color | #5C5CFF | `ansiBrightBlue` |
| Bright: Magenta | color | RGB hex color | #FF00FF | `ansiBrightMagenta` |
| Bright: Cyan | color | RGB hex color | #00FFFF | `ansiBrightCyan` |
| Bright: White | color | RGB hex color | #FFFFFF | `ansiBrightWhite` |

## Notes

!!! note "Color Profiles"
    **Color Profile** allows you to select a preset theme that applies foreground, background, cursor, and cursor shape settings at once. Switching profiles will update the individual color controls. If **Apply Profile** is available, it will reset colors to the selected theme's defaults.

!!! note "Cursor blinks"
    **Cursor blinks** is your own preference and is kept when you switch color profiles: a profile contributes the cursor *shape* (block, underline, vertical bar), while the blinking on/off state stays as you set it. It is stored together with the shape in `cursorStyle` (for example `STEADY_BLOCK` when blinking is off) and is remembered across restarts.

!!! note "ANSI Colors"
    The **Normal** and **Bright** color palettes define the 16 ANSI colors (0–7 normal, 8–15 bright) used when **Enable terminal colors** is on. Each set of 8 colors corresponds to black, red, green, yellow, blue, magenta, cyan, and white. When terminal colors are disabled, only the configured **Text Color** and **Background** are used, ignoring all ANSI and TrueColor sequences.
