---
title: Aussehen, Themen und Schriftart
---

# Aussehen, Themen und Schriftart

Die visuelle Konfiguration von korTTY umfasst drei Registerkarten: **Darstellung** steuert das UI-Design auf App-Ebene, **Schriftart** konfiguriert die Schriftart und -größe des Terminals und **Themen** verwaltet wiederverwendbare Terminal-Farbprofile. Alle Einstellungen bleiben bis `~/.kortty/global-settings.xml` bestehen.

## Registerkarte „Darstellung“.

![Appearance settings tab](../../assets/screenshots/settings/appearance.png)

Passen Sie den visuellen Stil des Anwendungsfensters und des Dialogs an.

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| App-Design | Dropdown | Standard, Matrix-Terminal, holographische Schnittstelle, klingonische Taktik, elegante Dunkelheit | Standard | `appDesign` |

Mit den Schaltflächen `◀` und `▶` neben dem Dropdown-Menü können Sie durch die Designs vor- und zurückblättern (an den Enden umlaufend). Wenn ein anderes Design als **Standard** ausgewählt wird, wird unterhalb der Steuerelemente ein Vorschaubild dieses Designs angezeigt. Das **Standarddesign** hat keine Vorschau und zeigt stattdessen eine kurze Notiz an.

!!! note
    App Design gilt nur für die Anwendungsfenster und Dialoge von korTTY. Terminalsitzungen und der Dateieditor behalten ihre eigenen unabhängigen Farbeinstellungen (konfiguriert über die Registerkarten „Farben“ oder „Themen“).

## Registerkarte „Schriftart“.

![Font settings tab](../../assets/screenshots/settings/font.png)

Legen Sie die Schriftart und -größe des Terminals und Editors fest.

| Einstellung | Geben Sie | ein Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Schriftfamilie | Dropdown | Gängige Monospace-Schriftarten (Monaco, Courier New, Menlo, Fira Code usw.) und alle Systemschriftarten | Monospaced | `defaultTerminalSettings.fontFamily` |
| Schriftgröße | Nummer | 8–72 pt | 12 | `defaultTerminalSettings.fontSize` |

!!! note
    Auf der Registerkarte „Schriftart“ werden globale Standardeinstellungen für alle neuen Terminalverbindungen festgelegt. Einzelne Verbindungen können diese Werte über ihre eigenen Verbindungseinstellungen überschreiben. Während Sie die Steuerelemente anpassen, wird eine Live-Vorschau angezeigt.

## Registerkarte „Themen“.

![Themes settings tab](../../assets/screenshots/settings/themes.png)

Erstellen, bearbeiten und verwalten Sie Terminal-Farbthemen. Themen definieren Farben (Text, Hintergrund, Cursor, ANSI) und Stil für alle Terminalsitzungen.

### Themenverwaltung

| Aktion | Beschreibung |
| --- | --- |
| **Hinzufügen** | Erstellen Sie ein neues benutzerdefiniertes Farbthema |
| **Bearbeiten** | Ändern Sie das ausgewählte Design (nur benutzerdefinierte Designs; integrierte Designs können nicht direkt bearbeitet werden) |
| **Duplikat** | Kopieren Sie das ausgewählte Design als neues benutzerdefiniertes Design |
| **Löschen** | Entfernen Sie ein benutzerdefiniertes Design (integrierte Designs können nicht gelöscht werden) |

### Theme-Optionen

| Einstellung | Geben Sie | ein Standard | Gespeichert als |
| --- | --- | --- | --- |
| Option „Schriftart anwenden“ | umschalten | Aus | `applyThemeFonts` |

!!! note
    Wenn die Option „Schriftart anwenden“ aktiviert ist, wird bei der Auswahl eines Themas aus der Dropdown-Liste „Farbprofil“ auf der Registerkarte „Farben“ auch die Schriftfamilie und -größe dieses Themas angewendet. Wenn die Option deaktiviert ist, werden nur die Farben des Themas angewendet.

### Integrierte vs. benutzerdefinierte Designs

korTTY enthält eine Reihe integrierter Farbthemen (z. B. Standard, Dunkelmodus, Solarisiertes Licht). Benutzerdefinierte Designs werden in `~/.kortty/themes/` als einzelne XML-Dateien gespeichert. Alle Themen werden in der Farbprofilauswahl der Registerkarte „Farben“ und in verbindungsspezifischen Themen-Dropdown-Menüs angezeigt.

!!! warning
    Integrierte Designs können nicht bearbeitet oder gelöscht werden. Um ein integriertes Design zu ändern, duplizieren Sie es zunächst und bearbeiten Sie dann die benutzerdefinierte Kopie.

### Theme-Vorschau

Eine Live-Theme-Vorschau zeigt die Farben des Themes an. In der Vorschau werden Vordergrund-, Hintergrund-, Cursor- und ANSI-Farbfelder angezeigt, sodass Sie ein Design bewerten können, bevor Sie es anwenden.

## Kreuztabellenintegration

- **Registerkarte „Farben“**: Wählen Sie über das Dropdown-Menü „Farbprofil“ ein Thema aus. Wenn auf der Registerkarte „Themen“ die Option „Schriftart anwenden“ aktiviert ist, wird auch die Schriftart des ausgewählten Themas angewendet.
- **Verbindungseinstellungen**: Jede einzelne SSH-Verbindung kann die globale Schriftart und das globale Design über ihre eigenen Einstellungen überschreiben (zugänglich im Verbindungsmanager oder in Quick Connect).
- **Terminal-Emulation**: Schrift- und Designfarben wirken sich nur auf den Terminalinhalt aus; Der Stil der Anwendungsoberfläche (Menüleiste, Dialoge, Registerkarten) wird durch die App-Designeinstellung der Registerkarte „Darstellung“ bestimmt.