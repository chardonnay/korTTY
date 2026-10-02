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
| Cursor blinkt | umschalten | — | Ein | `terminalCursorBlink` (gespiegelt in `cursorStyle`) |
| Auswahl | Farbe | RGB Hex-Farbe | #3399FF (inverses Video bis angepasst) | `selectionColor` |
| Terminalfarben aktivieren | umschalten | – | Ein | `terminalColorsEnabled` |
| Normal: Schwarz | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBlack` |
| Normal: Rot | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiRed` |
| Normal: Grün | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiGreen` |
| Normal: Gelb | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiYellow` |
| Normal: Blau | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBlue` |
| Normal: Magenta | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiMagenta` |
| Normal: Cyan | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiCyan` |
| Normal: Weiß | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiWhite` |
| Hell: Schwarz | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightBlack` |
| Hell: Rot | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightRed` |
| Hell: Grün | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightGreen` |
| Hell: Gelb | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightYellow` |
| Hell: Blau | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightBlue` |
| Hell: Magenta | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightMagenta` |
| Hell: Cyan | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightCyan` |
| Hell: Weiß | Farbe | RGB Hex-Farbe | integrierte Palette | `ansiBrightWhite` |

## Notizen

!!! note "Farbprofile"
    Mit dem **Farbprofil** können Sie ein voreingestelltes Design auswählen, das die Einstellungen für Vordergrund, Hintergrund, Cursor und Cursorform gleichzeitig anwendet. Beim Wechseln der Profile werden die einzelnen Farbsteuerungen aktualisiert. Wenn **Profil anwenden** verfügbar ist, werden die Farben auf die Standardeinstellungen des ausgewählten Themas zurückgesetzt.

!!! note "Cursor blinkt"
    **Cursor blinkt** ist Ihre eigene Präferenz und wird beibehalten, wenn Sie das Farbprofil wechseln: Ein Profil steuert die *Form* des Cursors (Block, Unterstreichung, vertikaler Balken), während der blinkende Ein-/Aus-Status so bleibt, wie Sie ihn festgelegt haben. Das Terminal liest die Flagge als Teil von `cursorStyle` (z. B. `STEADY_BLOCK`, wenn das Blinken ausgeschaltet ist), aber Ihre Auswahl wird separat als `terminalCursorBlink` gespeichert und hat Vorrang vor dem Stil, den ein Profil, ein Thema oder eine verbindungsspezifische Einstellung trägt – so kann das Wechseln von Profilen das Blinken nicht wieder einschalten und die Einstellung überlebt Neustarts. Eine vor der Existenz dieses Feldes geschriebene Einstellungsdatei funktioniert weiterhin: Die Auswahl wird dann aus dem gespeicherten `cursorStyle` ausgelesen.

!!! note "ANSI Farben"
    Die Farbpaletten **Normal** und **Hell** definieren die 16 ANSI-Farben (0–7 normal, 8–15 hell), die verwendet werden, wenn **Terminalfarben aktivieren** aktiviert ist. Jeder Satz von 8 Farben entspricht Schwarz, Rot, Grün, Gelb, Blau, Magenta, Cyan und Weiß. Wenn Terminalfarben deaktiviert sind, werden nur die konfigurierten **Textfarben** und **Hintergrund** verwendet, alle ANSI- und TrueColor-Sequenzen werden ignoriert.

    Solange Sie die Palette und die **Auswahl**-Farbe unverändert lassen, behält das Terminal seine integrierte Palette bei – die xterm-Farben oder die Windows-Konsolefarben unter Windows – und die Picker zeigen genau diese Farben. Ändern Sie eine der 16 Farben oder die Auswahlfarbe und klicken Sie **Speichern**: alle 16 Farben und die Auswahlfarbe werden sofort auf jedes offene Terminal angewendet, ohne neu verbinden zu müssen, und korTTY merkt sich, dass Sie sie angepasst haben (`ansiPaletteCustomized`). Setzen Sie jede Farbe auf ihren integrierten Wert zurück und die Auswahlfarbe auf #3399FF, kehren Sie zum Standard-Look zurück. Terminalaufzeichnungen mit [Terminalfarben in Aufnahmen erfassen](video.md) verwenden dieselben Farben wie der Bildschirm, außer dass fette Texte [Fett als helle Farbe anzeigen](terminal.md) folgen, was bisher nur Aufzeichnungen betrifft. Farbprofile ändern die ANSI-Palette nicht.

!!! note "Auswahl"
    Während die Palette und die Auswahlfarbe unverändert bleiben, wird ausgewählter Text im inversen Video dargestellt. Sobald Sie diese angepasst haben, wird die Auswahl in der **Auswahl**-Farbe mit schwarzem oder weißem Text gezeichnet, je nachdem, was leichter lesbar ist.
