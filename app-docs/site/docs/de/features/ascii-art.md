---
title: ASCII-Art
---

# ASCII-Art

Erstellen Sie ASCII-Art auf zwei Arten: Rendern Sie Text als FIGlet-Banner oder lassen Sie die KI ein Bild aus einem Betreffwort wie „Haus“ zeichnen. Beide befinden sich in einem Dialog hinter ihrer eigenen Registerkarte, geben eine zoombare Vorschau frei und können in die Zwischenablage kopiert werden, um sie in Terminal-Skripten, Anmeldebannern oder Dokumentationen zu verwenden.

## Zugriff auf das Tool

Öffnen Sie den Dialog über **Extras > ASCII Art...** in der Menüleiste oder drücken Sie ++ctrl+shift+a++ (++cmd+shift+a++ unter macOS).

## Registerkarte „Textbanner“.

### Stil

Wählen Sie im Dropdown-Menü einen FIGlet-Schriftstil aus. **Standard** und **Slant** stammen aus der jfiglet-Bibliothek; **3-D**, **Banner**, **big**, **block**, **cosmic**, **Digital**, **Lean**, **roman**, **script** und **small** sind gebündelte FIGfonts. Ein Stil, dessen Schriftartdatei nicht geladen werden kann, wird aus der Liste ausgeschlossen.

Wechseln Sie zwischen Stilen mit dem Dropdown-Menü, mit den Pfeiltasten (++left++, ++right++, ++up++, ++down++), während das Dropdown-Menü den Fokus hat, oder mit den Tasten ◀ und ▶ daneben.

### Text

Geben Sie den zu konvertierenden Text in das Feld **Text** ein oder fügen Sie ihn ein. Die mehrzeilige Eingabe wird unterstützt – jede Zeile wird einzeln konvertiert und leere Zeilen bleiben leer.

Die **Vorschau** wird während der Eingabe und bei jeder Änderung des Stils neu gerendert.

## AI Registerkarte „Bild“.

Anstelle einer Beschriftung fordert diese Registerkarte ein Modell auf, das Motiv als Bild zu zeichnen.

| Steuerung | Was es tut |
| --- | --- |
| **Thema** | Das Ding zum Zeichnen zum Beispiel `house`. Drücken ++enter++ startet die Generation. |
| **Generieren** | Fordert ein Bild für den Betreff an. |
| **AI-Profil** | Welches Profil verarbeitet diesen Lauf? Die Auswahl ist vorübergehend und ändert Ihr Standardprofil nicht. |
| **Neue Variante** | Zeichnet dasselbe Motiv mit einer anderen Behandlung neu – Betrachtungswinkel, Detaillierungsgrad, Linienstil, Szenenkontext oder Proportionen – und fordert bei jedem Wiederholungsversuch erneut etwas anderes an. |

Für die Registerkarte ist mindestens ein konfiguriertes AI-Profil und der aktivierte AI-Features-Schalter erforderlich. andernfalls bleiben die Steuerelemente deaktiviert und die Statuszeile zeigt dies an. Fehler und „Kein brauchbares Bild“-Antworten werden in derselben Statuszeile gemeldet.

!!! note
    Das Modell wird nur nach druckbarem ASCII gefragt, höchstens 60 Zeichen pro Zeile und 30 Zeilen hoch, damit ein Ergebnis in der Vorschau lesbar bleibt. Antworten werden bereinigt, bevor sie angezeigt werden: Ein umschlossener Codeblock wird entpackt, Argumentationsblöcke und Steuerzeichen werden entfernt, Tabulatoren werden zu Leerzeichen und leere Anfangs- und Endzeilen werden abgeschnitten.

## Vorschauzoom

Beide Registerkarten teilen sich eine Zoomstufe, sodass ein Banner und ein Bild immer in der gleichen Größe angezeigt werden.

| Aktion | Steuert |
| --- | --- |
| Vergrößern | ***+**-Taste, ++ctrl+plus++, oder ++ctrl++ und scrollen Sie nach oben über die Vorschau |
| Schrumpfen | **−**-Taste, ++ctrl+minus++, oder ++ctrl++ und scrollen Sie nach unten über die Vorschau |
| Zurücksetzen | **⟲**-Taste oder ++ctrl+0++ |

Der Prozentsatz zwischen den Schaltflächen zeigt den aktuellen Wert an, von 50 % bis 333 %, wobei 100 % die standardmäßige 12-Pixel-Monospace-Größe ist.

## In die Zwischenablage kopieren

**In die Zwischenablage kopieren** kopiert die Vorschau der aktuell geöffneten Registerkarte, sodass Sie das Banner von der Registerkarte „Textbanner“ und das Bild von der Registerkarte „KI-Bild“ erhalten.

## Beispiel

Der **Banner**-Stil mit dem Eingabetext „Hallo“:

```
 _   _                 
| | | | ___  _ __ ___ 
| |_| |/ _ \| '__/ _ \
|  _  | (_) | | | (_) |
|_| |_|\___/|_|  \___/ 
                       
```

## Dialogstatus

Der Dialog merkt sich seine Fensterposition, seine Größe und die Vorschau-Zoomstufe zwischen den Sitzungen.

![ASCII art generator](../assets/screenshots/tools/ascii-art.png)
