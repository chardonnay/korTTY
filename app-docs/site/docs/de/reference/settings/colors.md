---
title: Farben
---

# Farben

Konfigurieren Sie die Anzeigefarben des Terminals, einschließlich Text, Hintergrund, Cursor, Auswahl und der 16-Farben-ANSI-Palette. Öffnen über **Konfiguration → Globale Einstellungen → Farben**; in `~/.kortty/global-settings.xml` gespeichert.

![Colors settings tab](../../assets/screenshots/settings/colors.png)

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Farbprofil | Dropdown | Themennamen (z. B. GitHub Dark, Dracula usw.) | GitHub Dark | `themeId` |
| Textfarbe | Farbe | RGB-Hex-Farbe | #FFFFFF | `foregroundColor` |
| Hintergrund | Farbe | RGB-Hex-Farbe | #1E1E1E | `backgroundColor` |
| Cursor | Farbe | RGB-Hex-Farbe | #FFFFFF | `cursorColor` |
| Cursor blinkt | umschalten | — | Auf | `cursorStyle` |
| Auswahl | Farbe | RGB-Hex-Farbe | #3399FF | `selectionColor` |
| Terminalfarben aktivieren | umschalten | — | Auf | `terminalColorsEnabled` |
| Normal: Schwarz | Farbe | RGB-Hex-Farbe | #000000 | `ansiBlack` |
| Normal: Rot | Farbe | RGB-Hex-Farbe | #CD0000 | `ansiRed` |
| Normal: Grün | Farbe | RGB-Hex-Farbe | #00CD00 | `ansiGreen` |
| Normal: Gelb | Farbe | RGB-Hex-Farbe | #CDCD00 | `ansiYellow` |
| Normal: Blau | Farbe | RGB-Hex-Farbe | #0000EE | `ansiBlue` |
| Normal: Magenta | Farbe | RGB-Hex-Farbe | #CD00CD | `ansiMagenta` |
| Normal: Cyan | Farbe | RGB-Hex-Farbe | #00CDCD | `ansiCyan` |
| Normal: Weiß | Farbe | RGB-Hex-Farbe | #E5E5E5 | `ansiWhite` |
| Hell: Schwarz | Farbe | RGB-Hex-Farbe | #7F7F7F | `ansiBrightBlack` |
| Hell: Rot | Farbe | RGB-Hex-Farbe | #FF0000 | `ansiBrightRed` |
| Hell: Grün | Farbe | RGB-Hex-Farbe | #00FF00 | `ansiBrightGreen` |
| Hell: Gelb | Farbe | RGB-Hex-Farbe | #FFFF00 | `ansiBrightYellow` |
| Hell: Blau | Farbe | RGB-Hex-Farbe | #5C5CFF | `ansiBrightBlue` |
| Hell: Magenta | Farbe | RGB-Hex-Farbe | #FF00FF | `ansiBrightMagenta` |
| Hell: Cyan | Farbe | RGB-Hex-Farbe | #00FFFF | `ansiBrightCyan` |
| Hell: Weiß | Farbe | RGB-Hex-Farbe | #FFFFFF | `ansiBrightWhite` |

## Notizen

!!! note "Farbprofile"
    Mit dem **Farbprofil** können Sie ein voreingestelltes Design auswählen, das die Einstellungen für Vordergrund, Hintergrund, Cursor und Cursorform gleichzeitig anwendet. Beim Wechseln der Profile werden die einzelnen Farbsteuerungen aktualisiert. Wenn **Profil anwenden** verfügbar ist, werden die Farben auf die Standardeinstellungen des ausgewählten Themas zurückgesetzt.

!!! note "Cursor blinkt"
    **Cursor blinkt** ist Ihre eigene Einstellung und bleibt beim Wechseln des Farbprofils erhalten: Ein Profil steuert die *Form* des Cursors bei (Block, Unterstrich, senkrechter Balken), während der Blinkzustand so bleibt, wie Sie ihn gesetzt haben. Sie wird zusammen mit der Form in `cursorStyle` gespeichert (zum Beispiel `STEADY_BLOCK`, wenn das Blinken aus ist) und bleibt über Neustarts hinweg erhalten.

!!! note "ANSI Farben"
    Die Farbpaletten **Normal** und **Hell** definieren die 16 ANSI-Farben (0–7 normal, 8–15 hell), die verwendet werden, wenn **Terminalfarben aktivieren** aktiviert ist. Jeder Satz von 8 Farben entspricht Schwarz, Rot, Grün, Gelb, Blau, Magenta, Cyan und Weiß. Wenn Terminalfarben deaktiviert sind, werden nur die konfigurierten **Textfarben** und **Hintergrund** verwendet, alle ANSI- und TrueColor-Sequenzen werden ignoriert.
