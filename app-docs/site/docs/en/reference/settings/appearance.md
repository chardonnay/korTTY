---
title: Appearance, themes & font
---

# Appearance, themes & font

korTTY's visual configuration spans three tabs: **Appearance** controls the app-level UI design, **Font** configures terminal typeface and size, and **Themes** manages reusable terminal color profiles. All settings persist to `~/.kortty/global-settings.xml`.

## Appearance tab

![Appearance settings tab](../../assets/screenshots/settings/appearance.png)

Customize the application window and dialog visual style.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| App Design | dropdown | Default, Matrix Terminal, Holographic Interface, Klingon Tactical, Elegant Dark, Amber CRT, Synthwave '84, Gruvbox Retro, Nord Arctic, Dracula | Default | `appDesign` |
| Enable design animations | toggle | On, Off | On | `appDesignAnimationsEnabled` |
| UI font size | number | 80–160 % in steps of 5 | 100 % | `uiFontScalePercent` |
| Match display resolution | toggle | On, Off | On (new installations) | `uiFontScaleAuto` |
| Chat color profile | dropdown | Automatic (theme), Original, Paper, Midnight, Cyberpunk, Retrowave, Forest, Ocean, Terminal, GPT, Cute | Automatic (theme) | `chatColorProfileId` |

The `◀` and `▶` buttons next to the dropdown step backward and forward through the designs (wrapping around at the ends). When a design other than **Default** is selected, a preview image of that design is shown below the controls; the **Default** design has no preview and shows a short note in its place.

**Enable design animations** doubles as a reduce-motion switch: turning it off stops the animated design effects while leaving the colors in place.

**Chat color profile** themes the AI chat and swarm surfaces. The default **Automatic (theme)** derives its palette from the active terminal theme's agent-panel colors; the other profiles are fixed palettes.

!!! note
    App Design applies only to korTTY's application windows and dialogs. Terminal sessions and the file editor retain their own independent color settings (configured via the Colors or Themes tabs).

### UI font size

**UI font size** scales korTTY's own interface — the menu bar and its dropdowns, dialogs, form labels, buttons, tab titles, the dashboard, the file browser and the status bar. It takes effect the moment you save; no restart is needed, and windows that are already open resize with it. The main window's first-launch size grows along with it, and dialogs that remember their size are re-measured after a change rather than reopening at a size that no longer fits their text.

It deliberately does **not** touch surfaces that already have their own size control, so the two never fight each other:

| Surface | Where its size is set instead |
| --- | --- |
| Terminal sessions | The **Font** tab, plus the View menu's zoom shortcuts |
| File editor | The **Editor** tab |
| AI chat, agent plan and activity panels | Their own `A-` / `A+` buttons |
| The manual (**Help → Manual**) | The `A-` / `A+` buttons in its own window — see [below](#manual-text-size) |
| Session journal page | The journal viewer's appearance popover |

Two further parts of the interface keep their size at every setting: the macOS application menu (the one in the system menu bar), because macOS draws it rather than korTTY, and the AI swarm status strip, whose labels are painted onto a canvas together with hand-computed positions.

**Match display resolution** derives the size from the primary display instead, and disables the number field while it is on. A new installation starts with it **on**, so korTTY fits itself to the screen before anyone visits this tab; updating an existing installation never changes it — whatever you had stays. The value comes from the display's *logical* height — the resolution the operating system reports after its own scaling:

| Logical screen height | UI font size |
| --- | --- |
| Below 1400 px | 100 % |
| 1400–1799 px | 110 % |
| 1800–2299 px | 125 % |
| 2300 px and above | 140 % |

This is why a Retina MacBook stays at 100 %: macOS already scales it, so it reports roughly 1100 logical pixels and needs no help. The case the option exists for is a 4K or 5K display running at 100 % operating-system scaling, where korTTY really does get the full 2160 or 2880 pixels to draw into.

!!! note
    Automatic is a suggestion, not a measurement. JavaFX cannot report a display's physical size, so korTTY cannot tell a 27-inch 4K panel from a 32-inch one at the same resolution, even though they need different sizes. If the result does not suit you, turn the option off and set the percentage yourself — your manual value is remembered while automatic is on.

Automatic tops out at 140 % and the manual setting at 160 %. The ceiling is deliberate: a handful of dialogs size themselves from fixed pixel widths, and beyond that point their text starts to crowd. korTTY re-reads the display when you save the settings, so after changing your monitor setup, open Settings and save again (or restart) to pick up the new resolution.

### Manual text size

The manual (**Help → Manual**, ++f1++) has its own text size. Three buttons at the top left of its window control it: `A-`, the current percentage, and `A+`. The percentage is also the reset control. Click it to return to 100 %. There are keyboard shortcuts for all three: ++cmd+plus++, ++cmd+minus++, ++cmd+0++. On Windows and Linux they are ++ctrl+plus++, ++ctrl+minus++, ++ctrl+0++. They are registered on the manual's own window, so they never collide with the terminal zoom in the main window.

The range is 70–250 % in steps of 10, wider than the interface setting because the manual is a reflowing document rather than a layout built from fixed widths. The size is remembered across restarts, applies to the AI search answers next to the manual as well, and stays put as you navigate between pages. It scales the page as a whole — text, images and diagrams together — the way a browser's zoom does.

!!! note
    This is separate from **UI font size** above, which governs the buttons and window chrome *around* the manual. If the manual's own toolbar looks too small, that is the setting to change.

## Font tab

![Font settings tab](../../assets/screenshots/settings/font.png)

Set the terminal and editor typeface and size.

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Font Family | dropdown | Common monospace fonts (Monaco, Courier New, Menlo, Fira Code, etc.) and all system fonts | Monospaced | `defaultTerminalSettings.fontFamily` |
| Font Size | number | 8–72 pt | 14 | `defaultTerminalSettings.fontSize` |

!!! note
    The Font tab sets global defaults for all new terminal connections. Individual connections can override these values via their own connection settings. A live preview is shown as you adjust the controls.

## Themes tab

![Themes settings tab](../../assets/screenshots/settings/themes.png)

Create, edit, and manage terminal color themes. Themes define colors (text, background, cursor, ANSI) and styling for all terminal sessions.

### Theme management

| Action | Description |
| --- | --- |
| **Add** | Create a new custom color theme |
| **Edit** | Modify the selected theme. The button stays enabled for built-in themes but does nothing when clicked — duplicate the theme first |
| **Duplicate** | Copy the selected theme as a new custom theme |
| **Delete** | Remove a custom theme (built-in themes cannot be deleted) |

### Theme options

| Setting | Type | Default | Stored as |
| --- | --- | --- | --- |
| When enabled, applying a theme also changes font family and size. | toggle | Off | `applyThemeFonts` |

!!! note
    When this option is enabled, selecting a theme from the Colors tab's color profile dropdown will also apply that theme's font family and size. When disabled, only the theme's colors are applied.

### Built-in vs. custom themes

korTTY includes a set of built-in color themes (e.g., Default, Dark Mode, Solarized Light). Built-in and custom themes are stored together in a single file, `~/.kortty/themes.xml`. All themes appear in the Colors tab's color profile selector and in connection-specific theme dropdowns.

!!! warning
    Built-in themes cannot be edited or deleted. To modify a built-in theme, duplicate it first, then edit the custom copy.

### Theme preview

A live theme preview displays the theme's colors. The preview shows six swatches — foreground, background and cursor, plus the AI agent panel's background, run and error colors — so you can evaluate a theme before applying it.

## Cross-tab integration

- **Colors tab**: Choose a theme via the "Color Profile" dropdown; if the Themes tab's font option is enabled, the selected theme's font is also applied.
- **Connection settings**: Each individual SSH connection can override the global font and theme via its own settings (accessible in Connection Manager or Quick Connect).
- **Terminal emulation**: Font and theme colors affect only the terminal content; the application UI style (menu bar, dialogs, tabs) is determined by the Appearance tab's app design setting.