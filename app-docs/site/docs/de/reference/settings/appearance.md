---
title: Aussehen, Themen und Schriftart
---

# Aussehen, Themen und Schriftart

Die visuelle Konfiguration von korTTY umfasst drei Registerkarten: **Darstellung** steuert das UI-Design auf App-Ebene, **Schriftart** konfiguriert die Schriftart und -größe des Terminals und **Themen** verwaltet wiederverwendbare Terminal-Farbprofile. Alle Einstellungen bleiben bis `~/.kortty/global-settings.xml` bestehen.

## Registerkarte „Aussehen“.

![Appearance settings tab](../../assets/screenshots/settings/appearance.png)

Passen Sie den visuellen Stil des Anwendungsfensters und des Dialogs an.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| App-Design | Dropdown-Liste | Standard, Matrix-Terminal, holografische Schnittstelle, Klingon Tactical, Elegant Dark, Amber CRT, Synthwave '84, Gruvbox Retro, Nord Arctic, Dracula | Standard | `appDesign` |
| Designanimationen aktivieren | umschalten | Ein, Aus | Ein | `appDesignAnimationsEnabled` |
| UI font size | number | 80–160 % in steps of 5 | 100 % | `uiFontScalePercent` |
| An Bildschirmauflösung anpassen | umschalten | Ein, Aus | Ein (Neuinstallation) | `uiFontScaleAuto` |
| Chat-Farbprofil | Dropdown-Liste | Automatisch (Thema), Original, Papier, Mitternacht, Cyberpunk, Retrowave, Wald, Ozean, Terminal, GPT, Niedlich | Automatisch (Thema) | `chatColorProfileId` |

The `◀` and `▶` buttons next to the dropdown step backward and forward through the designs (wrapping around at the ends). When a design other than **Default** is selected, a preview image of that design is shown below the controls; the **Default** design has no preview and shows a short note in its place.

**Designanimationen aktivieren** fungiert gleichzeitig als Schalter zur Bewegungsreduzierung: Wenn Sie ihn ausschalten, werden die animierten Designeffekte gestoppt, während die Farben an Ort und Stelle bleiben.

**Chat-Farbprofil** thematisiert die KI-Chat- und Schwarmoberflächen. Das standardmäßige **Automatic (Theme)** leitet seine Palette von den Agent-Panel-Farben des aktiven Terminal-Themes ab; Die anderen Profile sind feste Paletten.

!!! note
    App Design gilt nur für die Anwendungsfenster und Dialoge von korTTY. Terminalsitzungen und der Dateieditor behalten ihre eigenen unabhängigen Farbeinstellungen (konfiguriert über die Registerkarten „Farben“ oder „Themen“).

### UI-Schriftgröße

**UI font size** scales korTTY's own interface — the menu bar and its dropdowns, dialogs, form labels, buttons, tab titles, the dashboard, the file browser and the status bar. It takes effect the moment you save; no restart is needed, and windows that are already open resize with it. The main window's first-launch size grows along with it, and dialogs that remember their size are re-measured after a change rather than reopening at a size that no longer fits their text.

Es berührt bewusst **keine** Oberflächen, die bereits über eine eigene Größenkontrolle verfügen, sodass die beiden nie gegeneinander antreten:

| Oberfläche | Wo ihre Größe stattdessen eingestellt wird |
| --- | --- |
| Terminalsitzungen | Der Tab **Schrift** sowie die Zoom-Verknüpfungen des Menüs „Ansicht“ |
| Dateieditor | Der Tab **Editor** |
| KI-Chat-, Agentenplan- und Aktivitätspanels | Ihre eigenen `A-` / `A+`-Schaltflächen |
| Die Anleitung (**Hilfe → Anleitung**) | Der `A-` / `A+` Schaltflächen in einem eigenen Fenster – siehe [unten](#textgroe-der-anleitung) |
| Sitzungsjournalseite | Das Popover für das Erscheinungsbild des Journalbetrachters |

Zwei weitere Teile der Benutzeroberfläche behalten bei jeder Einstellung ihre Größe: das macOS-Anwendungsmenü (das in der Systemmenüleiste), da macOS es anstelle von korTTY zeichnet, und der KI-Schwarm-Statusstreifen, dessen Beschriftungen zusammen mit handberechneten Positionen auf einen Canvas gezeichnet werden.

**Match display resolution** derives the size from the primary display instead, and disables the number field while it is on. A new installation starts with it **on**, so korTTY fits itself to the screen before anyone visits this tab; updating an existing installation never changes it — whatever you had stays. The value comes from the display's *logical* height — the resolution the operating system reports after its own scaling:

| Logische Bildschirmhöhe | UI-Schriftgröße |
| --- | --- |
| Unter 1400 px | 100 % |
| 1400–1799 px | 110 % |
| 1800–2299 px | 125 % |
| 2300 px und höher | 140 % |

Aus diesem Grund bleibt ein Retina MacBook bei 100 %: macOS skaliert es bereits, sodass es etwa 1100 logische Pixel meldet und keine Hilfe benötigt. Die Option besteht für einen 4K- oder 5K-Bildschirm mit 100 % Betriebssystemskalierung, wobei korTTY tatsächlich die vollen 2160 oder 2880 Pixel zum Zeichnen erhält.

!!! note
    Automatic is a suggestion, not a measurement. JavaFX cannot report a display's physical size, so korTTY cannot tell a 27-inch 4K panel from a 32-inch one at the same resolution, even though they need different sizes. If the result does not suit you, turn the option off and set the percentage yourself — your manual value is remembered while automatic is on.

Die Automatik erreicht die Höchstgrenze bei 140 %, die manuelle Einstellung bei 160 %. Die Obergrenze ist absichtlich festgelegt: Eine Handvoll Dialoge richten sich nach festen Pixelbreiten, und jenseits dieses Punktes beginnt der Text zu drängen. korTTY liest die Anzeige erneut, wenn Sie die Einstellungen speichern. Öffnen Sie also nach dem Ändern Ihrer Monitoreinstellungen die Einstellungen und speichern Sie sie erneut (oder starten Sie neu), um die neue Auflösung zu übernehmen.

### Textgröße der Anleitung

Die Anleitung (**Hilfe → Anleitung**, ++f1++) hat eine eigene Textgröße. Drei Schaltflächen oben links im Fenster steuern es: `A-`, der aktuelle Prozentsatz und `A+`. Der Prozentsatz ist auch die Rücksetzsteuerung. Klicken Sie darauf, um zu 100 % zurückzukehren. Für alle drei gibt es Tastaturkürzel: ++cmd+plus++, ++cmd+minus++, ++cmd+0++. Unter Windows und Linux sind dies ++ctrl+plus++, ++ctrl+minus++, ++ctrl+0++. Sie werden im eigenen Fenster der Anleitung registriert, sodass sie niemals mit dem Terminalzoom im Hauptfenster kollidieren.

Der Bereich beträgt 70–250 % in 10er-Schritten und ist breiter als die Schnittstelleneinstellung, da es sich bei der Anleitung um ein umfließendes Dokument und nicht um ein Layout mit festen Breiten handelt. Die Größe wird bei Neustarts gespeichert, gilt auch für die KI-Suchantworten neben der Anleitung und bleibt beim Navigieren zwischen den Seiten erhalten. Es skaliert die Seite als Ganzes – Text, Bilder und Diagramme zusammen – so, wie es der Zoom eines Browsers tut.

!!! note
    Dies ist unabhängig von der **UI-Schriftgröße** oben, die die Schaltflächen und das Fensterchrom *um* die Anleitung regelt. Wenn die Symbolleiste der Anleitungs zu klein erscheint, müssen Sie diese Einstellung ändern.

## Registerkarte „Schriftart“.

![Font settings tab](../../assets/screenshots/settings/font.png)

Legen Sie die Schriftart und -größe des Terminals und Editors fest.

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Schriftartenfamilie | Dropdown-Liste | Gängige Festbreitenschriften (Monaco, Courier New, Menlo, Fira Code usw.) und alle Systemschriftarten | Monospaced | `defaultTerminalSettings.fontFamily` |
| Schriftgröße | Nummer | 8–72 pt | 14 | `defaultTerminalSettings.fontSize` |

!!! note
    Auf der Registerkarte „Schriftart“ werden globale Standardeinstellungen für alle neuen Terminalverbindungen festgelegt. Einzelne Verbindungen können diese Werte über ihre eigenen Verbindungseinstellungen überschreiben. Während Sie die Steuerelemente anpassen, wird eine Live-Vorschau angezeigt.

## Registerkarte „Themen“.

![Themes settings tab](../../assets/screenshots/settings/themes.png)

Erstellen, bearbeiten und verwalten Sie Terminal-Farbthemen. Themen definieren Farben (Text, Hintergrund, Cursor, ANSI) und Stil für alle Terminalsitzungen.

### Themenverwaltung

| Aktion | Beschreibung |
| --- | --- |
| **Hinzufügen** | Erstellen Sie ein neues benutzerdefiniertes Farbthema |
| **Bearbeiten** | Das ausgewählte Thema ändern. Die Schaltfläche bleibt für integrierte Designs aktiviert, führt aber beim Klicken nichts aus – duplizieren Sie zuerst das Design |
| **Duplizieren** | Kopieren Sie das ausgewählte Design als neues benutzerdefiniertes Design |
| **Löschen** | Entfernen Sie ein benutzerdefiniertes Design (integrierte Designs können nicht gelöscht werden) |

### Theme-Optionen

| Einstellung | Typ | Standard | Gespeichert als |
| --- | --- | --- | --- |
| Wenn diese Option aktiviert ist, ändert sich beim Anwenden eines Designs auch die Schriftfamilie und -größe. | umschalten | Aus | `applyThemeFonts` |

!!! note
    Wenn diese Option aktiviert ist, wird bei der Auswahl eines Themas aus der Dropdown-Liste „Farbprofil“ der Registerkarte „Farben“ auch die Schriftfamilie und -größe dieses Themas angewendet. Wenn die Option deaktiviert ist, werden nur die Farben des Themas angewendet.

### Eingebaute vs. benutzerdefinierte Designs

korTTY enthält eine Reihe integrierter Farbthemen (z. B. Standard, Dunkelmodus, Solarisiertes Licht). Integrierte und benutzerdefinierte Designs werden zusammen in einer einzigen Datei, `~/.kortty/themes.xml`, gespeichert. Alle Themen werden in der Farbprofilauswahl der Registerkarte „Farben“ und in verbindungsspezifischen Themen-Dropdown-Menüs angezeigt.

!!! warning
    Integrierte Designs können nicht bearbeitet oder gelöscht werden. Um ein integriertes Design zu ändern, duplizieren Sie es zunächst und bearbeiten Sie dann die benutzerdefinierte Kopie.

### Theme-Vorschau

Eine Live-Theme-Vorschau zeigt die Farben des Themes an. Die Vorschau zeigt sechs Farbfelder – Vordergrund, Hintergrund und Cursor sowie die Hintergrund-, Ausführungs- und Fehlerfarben des AI-Agent-Panels – sodass Sie ein Design bewerten können, bevor Sie es anwenden.

## Cross-Tab-Integration

- **Registerkarte „Farben“**: Wählen Sie über das Dropdown-Menü „Farbprofil“ ein Thema aus. Wenn die Schriftartoption der Registerkarte „Themen“ aktiviert ist, wird auch die Schriftart des ausgewählten Designs angewendet.
- **Verbindungseinstellungen**: Jede einzelne SSH-Verbindung kann die globale Schriftart und das globale Design über ihre eigenen Einstellungen überschreiben (zugänglich im Verbindungsmanager oder in der Schnellverbindung).
- **Terminal-Emulation**: Schrift- und Designfarben wirken sich nur auf den Terminalinhalt aus; Der Stil der Anwendungsoberfläche (Menüleiste, Dialoge, Registerkarten) wird durch die App-Designeinstellung der Registerkarte „Darstellung“ bestimmt.