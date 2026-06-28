---
title: ASCII-Kunstbanner
---

# ASCII-Kunstbanner

Generieren Sie ASCII-Kunsttextbanner mit FIGlet-Schriftarten mit mehreren Stiloptionen. Mit dem Dialogfeld „ASCII-Art-Banner“ können Sie eine Vorschau Ihres Texts in verschiedenen Stilen anzeigen und das Ergebnis in Ihre Zwischenablage kopieren, um es in Terminal-Skripten, Kopfzeilen oder Dokumentationen zu verwenden.

## Zugriff auf das Tool

Öffnen Sie den ASCII-Art-Banner-Generator über **Extras > ASCII-Art-Banner** in der Menüleiste.

## Verwenden des Dialogs

Der Dialog enthält vier Hauptabschnitte:

### Stilauswahl

Wählen Sie über das Dropdown-Menü aus über 11 verfügbaren FIGlet-Schriftstilen:

- **Standard** – Klassische Blockbuchstaben (Standard-Jfiglet-Stil)
- **Schräge** – kursive Blockschrift
- **3-D**, **Banner**, **groß**, **Block**, **kosmisch**, **Digital**, **lean**, **roman**, **script**, **small** – Zusätzliche gebündelte Stile für vielfältige visuelle Effekte

Navigieren Sie zwischen Stilen mit:

- Das Dropdown-Menü direkt
- Pfeiltasten (++left++, ++right++, ++up++, ++down++), wenn das Kombinationsfeld den Fokus hat
- Die Navigationsschaltflächen (◀ und ▶) neben dem Dropdown

### Eingabe

Geben oder fügen Sie den Text, den Sie konvertieren möchten, in das Feld **Eingabe** ein. Mehrzeiliger Text wird unterstützt – jede Zeile wird separat konvertiert.

### Vorschau

Im Bereich **Ausgabe** wird Ihr Text in Echtzeit angezeigt, während Sie:

- Ändern Sie den ausgewählten Schriftstil
- Geben Sie Ihren Eingabetext ein oder bearbeiten Sie ihn

Die Ausgabe verwendet eine Monospace-Schriftart für eine genaue ASCII-Grafikwiedergabe.

### In die Zwischenablage kopieren

Klicken Sie auf **In die Zwischenablage kopieren**, um die generierte ASCII-Grafik in die Zwischenablage Ihres Systems zu kopieren. Sie können es dann in Terminalskripte, Dokumentation, Konfigurationsdateien oder jeden anderen Textkontext einfügen.

## Beispiel

Mit dem „Banner“-Stil und dem Eingabetext „Hallo“:

```
 _   _                 
| | | | ___  _ __ ___ 
| |_| |/ _ \| '__/ _ \
|  _  | (_) | | | (_) |
|_| |_|\___/|_|  \___/ 
                       
```

## Dialogstatus

Das Dialogfeld „ASCII-Art-Banner“ merkt sich zwischen den Sitzungen seine Fensterposition, Größe und den ausgewählten Schriftstil.

![ASCII Art banner generator](../assets/screenshots/tools/ascii-art.png)
