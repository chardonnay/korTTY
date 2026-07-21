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
| Chat color profile | dropdown | Automatic (theme), Original, Paper, Midnight, Cyberpunk, Retrowave, Forest, Ocean, Terminal, GPT, Cute | Automatic (theme) | `chatColorProfileId` |

The `◀` and `▶` buttons next to the dropdown step backward and forward through the designs (wrapping around at the ends). When a design other than **Default** is selected, a preview image of that design is shown below the controls; the **Default** design has no preview and shows a short note in its place.

**Enable design animations** doubles as a reduce-motion switch: turning it off stops the animated design effects while leaving the colors in place.

**Chat color profile** themes the AI chat and swarm surfaces. The default **Automatic (theme)** derives its palette from the active terminal theme's agent-panel colors; the other profiles are fixed palettes.

!!! note
    App Design applies only to korTTY's application windows and dialogs. Terminal sessions and the file editor retain their own independent color settings (configured via the Colors or Themes tabs).

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