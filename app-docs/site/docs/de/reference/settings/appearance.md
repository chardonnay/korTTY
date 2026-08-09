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
| App Design | Dropdown | Standard, Matrix Terminal, Holographische Schnittstelle, Klingon Tactical, Elegant Dark, Amber CRT, Synthwave '84, Gruvbox Retro, Nord Arctic, Dracula | Standard | `appDesign` |
| Designanimationen aktivieren | umschalten | Ein, Aus | Ein | `appDesignAnimationsEnabled` |
| Chat-Farbprofil | Dropdown-Liste | Automatisch (Thema), Original, Papier, Mitternacht, Cyberpunk, Retrowave, Wald, Ozean, Terminal, GPT, Niedlich | Automatisch (Thema) | `chatColorProfileId` |

Mit den Schaltflächen `◀` und `▶` neben dem Dropdown-Menü können Sie durch die Designs vor- und zurückblättern (an den Enden umlaufend). Wenn ein anderes Design als **Standard** ausgewählt wird, wird unterhalb der Steuerelemente ein Vorschaubild dieses Designs angezeigt. Das **Standarddesign** hat keine Vorschau und zeigt stattdessen eine kurze Notiz an.

**Designanimationen aktivieren** dient gleichzeitig als Schalter zur Bewegungsreduzierung: Wenn Sie ihn ausschalten, werden die animierten Designeffekte gestoppt, während die Farben an Ort und Stelle bleiben.

**Chat-Farbprofil** thematisiert die KI-Chat- und Schwarmoberflächen. Das standardmäßige **Automatic (Theme)** leitet seine Palette von den Agent-Panel-Farben des aktiven Terminal-Themes ab; Die anderen Profile sind feste Paletten.

!!! note
    App Design gilt nur für die Anwendungsfenster und Dialoge von korTTY. Terminalsitzungen und der Dateieditor behalten ihre eigenen unabhängigen Farbeinstellungen (konfiguriert über die Registerkarten „Farben“ oder „Themen“).

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