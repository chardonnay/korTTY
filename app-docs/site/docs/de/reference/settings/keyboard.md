---
title: Tastatur
---

# Tastatur

Auf dieser Registerkarte wählen Sie eigene Tastaturkürzel für die Befehle von korTTY: Sie geben einem Menübefehl andere Tasten, einem Befehl ohne Kürzel eines oder entfernen ein Kürzel, das Sie immer wieder versehentlich drücken. Öffnen über **Konfiguration → Globale Einstellungen → Tastatur**; in `~/.kortty/global-settings.xml` gespeichert.

![Keyboard settings tab](../../assets/screenshots/settings/keyboard.png)

| Einstellung | Typ | Werte | Standard | Gespeichert als |
| --- | --- | --- | --- | --- |
| Kürzel eines Befehls | Tastenkombination | eine Taste mit ++ctrl++, ++alt++ oder ++cmd++, ++f1++ bis ++f12++ allein oder keines | das Kürzel, das sein Menü anzeigt | `keyBindingOverrides` (ein `binding` pro geändertem Befehl) |

Die Liste enthält jeden Befehl der Menüleiste des Fensters in Menüreihenfolge, auch die ohne Kürzel. Ihre Spalten sind die **Aktion**, das **Menü**, in dem sie steht (ihr Menüpfad, wie ihn die [Befehlspalette](../../features/command-palette.md) anzeigt), das **Kürzel** und ein **Status**: leer für einen Befehl mit seinem Standardkürzel, **Geändert** oder **Entfernt** für einen, den Sie geändert haben, **Fest** für ein Kürzel, das sich nicht ändern lässt, **Auch belegt von …** für einen [Konflikt](#konflikte) und **Hier nicht verwendet** für ein gespeichertes Kürzel, das dieser Computer [nicht akzeptiert](#kurzel-von-einem-anderen-computer-oder-einer-anderen-version). Geben Sie einen Teil des Befehlsnamens, seines Menüs oder seiner Tasten in **Nach Aktion, Menü oder Kürzel filtern** ein, um ihn zu finden, zum Beispiel `dashboard`, `ansicht` oder `shift+cmd+d`, und aktivieren Sie **Nur geänderte**, um nur die Befehle aufzulisten, die Sie geändert haben.

## Kürzel ändern

1. Wählen Sie den Befehl in der Liste aus.
2. Klicken Sie in das Kürzelfeld unter der Liste und drücken Sie die neue Kombination. Eine Modifikatortaste allein gedrückt zu halten, zeichnet nichts auf; drücken Sie also ++ctrl++, ++alt++, ++shift++ oder ++cmd++ zusammen mit der Taste.
3. Klicken Sie auf **Speichern**.

Das Feld übernimmt den Tastendruck als neues Kürzel des Befehls, und die Liste zeigt es als **Geändert** an; eine Kombination, die korTTY nicht verwenden kann, wird mit dem Grund unter dem Feld [abgelehnt](#kurzel-die-kortty-ablehnt), und der Befehl behält sein Kürzel. Während das Feld aufzeichnet, beendet ++esc++ die Aufzeichnung ohne Änderung, und ++tab++ verlässt das Feld. **Kürzel entfernen** lässt den Befehl ohne Taste, **Auf Standard zurücksetzen** gibt ihm sein eigenes Kürzel zurück, und **Alle zurücksetzen** setzt jeden Befehl auf sein Standardkürzel zurück. Das Standardkürzel im Feld zu drücken, wirkt wie **Auf Standard zurücksetzen**. Die Zeile unter dem Feld zeigt das Standardkürzel des ausgewählten Befehls.

Das Speichern übernimmt die Kürzel sofort und ohne Neustart in jedem offenen Fenster: Die Menüs zeigen die neuen Tasten an, ebenso die Befehlspalette. Ein von Ihnen gewähltes Kürzel funktioniert auch, während ein Terminal den Fokus hat: korTTY führt den Befehl aus, statt die Tasten an die Shell weiterzugeben, und das Zeichen, das sie eingeben würden, wird vom Terminal ferngehalten, sodass es weder die Shell noch im Broadcast-Modus die anderen Bereiche erreicht. Eine Funktionstaste, die Sie einem Befehl zuweisen, erreicht deshalb die Programme im Terminal wie `mc` oder `htop` nicht mehr.

## Kürzel, die korTTY ablehnt

korTTY nimmt weder der Shell noch dem System eine Taste weg, deshalb werden diese Kombinationen beim Aufzeichnen abgelehnt:

| Kombination | Grund |
| --- | --- |
| ++ctrl++ mit einem Buchstaben (++ctrl+l++, ++ctrl+f++, ++ctrl+d++, ++ctrl+p++, ++ctrl+r++, ++ctrl+c++, ++ctrl+v++ und jeder andere Buchstabe; unter macOS die Control-Taste, nicht ++cmd++), ++ctrl+space++, ++ctrl++ mit `[`, `]`, `\` oder `/`, ++ctrl+shift+6++, ++ctrl+shift+2++ und ++ctrl+shift+minus++ | Sie senden Steuerzeichen, die die Shell und die Programme im Terminal verwenden; ++ctrl+shift+6++ ist die Cisco-Break-Sequenz |
| ++ctrl++ oder ++alt++ mit ++left++, ++right++, ++backspace++ oder ++delete++ | Sie springen am Shell-Prompt wortweise oder löschen ein Wort |
| ++alt++ mit einem Buchstaben, einer Ziffer oder einem Satzzeichen unter Windows und Linux | korTTY sendet sie als Meta-Taste an die Shell |
| ++ctrl+alt++ mit einer Zeichentaste unter Windows, ++option++ mit einer Zeichentaste unter macOS | Sie geben auf vielen Tastaturbelegungen ein Zeichen ein: Unter Windows kommt ++alt-graph++ als ++ctrl+alt++ an |
| ++cmd+c++, ++cmd+v++, ++cmd+k++, ++cmd+f++, ++cmd+up++ und ++cmd+down++ unter macOS, ++ctrl+shift+c++, ++ctrl+shift+v++, ++ctrl+up++ und ++ctrl+down++ unter Windows und Linux, ++shift+page-up++ und ++shift+page-down++ überall | Die eigenen Tasten des Terminals zum Kopieren, Einfügen, Leeren, Suchen und Blättern |
| ++cmd+tab++, ++cmd+space++, ++cmd+h++, ++cmd+shift+q++, ++cmd+option+d++, ++ctrl+up++, ++ctrl+down++ und die Bildschirmfoto-Tasten unter macOS, ++alt+tab++, ++alt+f4++, ++alt+space++ und ++ctrl+esc++ unter Windows, ++alt+tab++, ++alt+f4++, ++ctrl+alt+delete++ und ++ctrl+alt+f1++ bis ++ctrl+alt+f12++ unter Linux | Das Betriebssystem behält sie für sich |
| Eine Taste ohne ++ctrl++, ++alt++ oder ++cmd++, außer ++f1++ bis ++f12++ | Sie würde Text eingeben |
| Ein [festes Kürzel](#feste-kurzel) | korTTY behält es für seinen Befehl |

**Menüleiste anzeigen** kann sein Kürzel nicht verlieren: Bei ausgeblendeter Menüleiste ist es der Weg der Tastatur zurück zu den Menüs. Es kann aber ein anderes bekommen.

## Feste Kürzel

Einige Kürzel sind grau mit dem Status **Fest** aufgeführt und lassen sich nicht ändern, weil nicht nur das Menü sie verarbeitet: **Ausschneiden**, **Kopieren** und **Einfügen** sind auch die Zwischenablage-Tasten des Terminals und jedes Textfelds, **Vorheriger Prompt** und **Nächster Prompt** sind Tasten jedes Terminalbereichs, und die Zoom-Tasten sowie ++f12++ (**Vollbild**) richten sich nach Ihrer Tastaturbelegung. **Nächster Tab** und **Vorheriger Tab** (++ctrl+tab++ und ++ctrl+shift+tab++) sowie die Tasten zum Springen zu einem Tab (++cmd+1++ bis ++cmd+9++ unter macOS, ++ctrl+1++ bis ++ctrl+9++ unter Windows und Linux) haben keinen Menüeintrag und sind ebenfalls fest. Was jedes davon bewirkt, steht unter [Tastaturkürzel](../keyboard-shortcuts.md).

## Konflikte

Ein Kürzel, das bereits ein anderer Befehl verwendet, wird trotzdem übernommen, sodass Sie die Kürzel zweier Befehle in zwei Schritten tauschen können. Bis jedes Kürzel nur noch von einem Befehl verwendet wird, zeigen beide Zeilen **Auch belegt von …** in Rot, eine Zeile über der Liste nennt die mehrfach belegten Kürzel, und die Registerkarte **Tastatur** zeigt eine rote Zahl. **Speichern** lässt den Dialog dann geöffnet und zeigt die Registerkarte Tastatur mit dem ersten Konflikt ausgewählt: Ändern oder entfernen Sie von jedem Paar ein Kürzel, um zu speichern.

## Kürzel von einem anderen Computer oder einer anderen Version

Ein Kürzel wird für alle Plattformen zugleich gespeichert: Eines, das Sie auf einem Mac mit ++cmd++ aufzeichnen, ist unter Windows und Linux dasselbe Kürzel mit ++ctrl++. Enthält eine Einstellungsdatei ein Kürzel, das dieser Computer ablehnt, etwa ++cmd+l++ von einem Mac, das unter Linux ++ctrl+l++ ist und der Shell gehört, zeigt die Zeile des Befehls **Hier nicht verwendet** und den Grund an, der Befehl behält hier sein Standardkürzel, und das gespeicherte Kürzel bleibt für den Mac in der Datei. Kürzel von Befehlen, die diese Version von korTTY nicht kennt, weil sie aus einer neueren Version stammen, bleiben ebenfalls erhalten, und ein Hinweis über der Liste nennt sie. Eine Einstellungsdatei aus einer Version ohne diese Registerkarte öffnet sich mit allen Standardkürzeln.

In `global-settings.xml` ist jeder geänderte Befehl ein Eintrag wie `<binding>menu.view.commandPalette=Shortcut+Alt+P</binding>` oder `=none` für ein entferntes Kürzel, innerhalb von `<keyBindingOverrides>`. Der Name vor `=` ist die ID des Befehls; `Shortcut` steht unter macOS für ++cmd++ und unter Windows und Linux für ++ctrl++, während `Ctrl` auf jeder Plattform die Control-Taste selbst ist. Ein Eintrag, den korTTY nicht lesen kann, wird ignoriert.

!!! note
    Das Kürzel der KI-Vervollständigung im Snippet-Editor steht nicht auf dieser Registerkarte; Sie legen es auf der Registerkarte [Snippet-Editor](snippet-editor/index.md) fest.
