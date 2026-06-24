---
title: Terminaleffekt-Plugins
---

# Terminaleffekt-Plugins

KorTTY-Terminaleffekt-Plugins sind Java-`ServiceLoader`-Plugins, die das Erscheinungsbild und das Laufzeitverhalten einer Terminalsitzung ändern können. Dabei handelt es sich um vertrauenswürdigen lokalen Code, der in der KorTTY-JVM ausgeführt wird und über eine saubere SPI-Schnittstelle integriert wird.

## Fähigkeiten

Ein Terminal-Effekt-Plugin kann:

- Anwenden von Werten für das Terminal-Erscheinungsbild wie Schriftart, Vordergrund-/Hintergrundfarben, Cursorfarbe und Cursorstil
- Fügen Sie JavaFX-Overlay-Knoten über dem Terminalbereich hinzu (z. B. Scanlines, Rauschen, Vignetten oder Linienhervorhebungen).
- Wickeln Sie den SithTermFX `TtyConnector` ein, um die Terminalausgabe zu beobachten, zu beschleunigen oder umzuwandeln, bevor der Terminalemulator sie empfängt
- Reagieren Sie auf die vom Benutzer ausgewählte Animationsgeschwindigkeit des Endeffekts

Der integrierte MOTHER-Effekt wird als gebündeltes, exportierbares Plugin-JAR implementiert und dient als Referenzimplementierung.

## Laufzeitmodell

Terminaleffekt-Plugins werden von `de.kortty.core.TerminalEffectPluginManager` in dieser Reihenfolge geladen:

1. Klassenpfad-Plugins für Anwendungen
2. Gebündelte Plugin-JARs, aufgelistet nach `bundled-plugins/terminal-effects.index`
3. Externe JARs in `~/.kortty/plugins` kopiert

Wenn zwei Plugins dieselbe Plugin-ID offenlegen, gewinnt das erste und spätere Duplikate werden ignoriert. Dies ist beabsichtigt, da die Verbindungseinstellungen die Plugin-ID beibehalten.

Externe Plugins werden jeweils aus einem JAR geladen, wobei der Anwendungsklassenlader von KorTTY als übergeordnetes Element fungiert. Abhängigkeiten, die bereits Teil von KorTTY sind, können von diesem übergeordneten Klassenlader verwendet werden. Abhängigkeiten, die nicht Teil von KorTTY sind, werden nicht automatisch von benachbarten Dateien erkannt; Vermeiden Sie sie entweder oder schattieren Sie sie in die Plugin-JAR.

![Terminal effect plugin flow](../assets/diagrams/terminal-effect-plugin-flow.svg)

!!! Warnung
Terminal-Effekt-Plugins sind vertrauenswürdiger lokaler Java-Code. KorTTY führt keine Sandbox für importierte JARs durch. Ein Plugin wird innerhalb der KorTTY-JVM mit denselben lokalen Prozessberechtigungen wie KorTTY selbst ausgeführt. Importieren Sie Plugins nur aus Quellen, denen Sie vertrauen.

## Plugin-Verwaltung in der Benutzeroberfläche

Benutzer verwalten Terminal-Effekt-Plugins über **Plugins > Terminal-Effekte**.

![Terminal Effects manager](../assets/screenshots/plugins/terminal-effects.png)

Der Dialog zeigt die geladene Plugin-Liste mit:

- Aktiver/inaktiver Zustand
- Anzeigename
- Kurze Beschreibung

Benutzer können:

- Aktivieren oder deaktivieren Sie ein Plugin
- Importieren Sie ein externes `.jar`-Plugin, das KorTTY nach `~/.kortty/plugins` kopiert
- Exportieren Sie Plugins, die über eine Quell-JAR verfügen (gebündeltes MOTHER ist exportierbar, da es von `kortty-terminal-effect-mother.jar` geladen wird)

Benutzer wählen Effekte pro Terminalsitzung aus dem Terminal-Kontextmenü oder **Ansicht > Terminaleffekt** aus. Gespeicherte Verbindungen können auch den ausgewählten Effekt und die Animationsgeschwindigkeit über Quick Connect und Connection Manager speichern. Der Geschwindigkeitsregler deckt `1x` bis `10x` ab; Das numerische Feld akzeptiert Werte bis zu `99x`.

## SPI-Klassen

Der öffentliche SPI lebt unter `de.kortty.plugin.terminaleffects`.

### TerminalEffectPlugin

Implementieren Sie `TerminalEffectPlugin` als `ServiceLoader`-Einstiegspunkt.

```java
public interface TerminalEffectPlugin {
    String id();
    String displayName();
    default String description() { return ""; }
    TerminalEffectSession createSession(TerminalEffectContext context);
}
```

Regeln:

- `id()` muss stabil sein, da die Verbindungseinstellungen und der Status des deaktivierten Plugins bestehen bleiben
– Gültige IDs stimmen mit dem regulären Ausdruck `[a-z0-9][a-z0-9._-]{0,63}` überein
- `displayName()` darf nicht leer sein
- `description()` wird in der Plugin-Verwaltungstabelle angezeigt und sollte ein kurzer Satz sein
- Die Provider-Klasse muss von `ServiceLoader` ladbar sein; Verwenden Sie eine öffentliche Klasse mit einem öffentlichen Konstruktor ohne Argumente

### TerminalEffectSession

`createSession` gibt einen `TerminalEffectSession` pro Terminal-Tab-/Effektaktivierung zurück.

```java
public interface TerminalEffectSession extends AutoCloseable {
    default void start() {}

    default @NotNull TtyConnector wrapConnector(
            @Nullable SithTermFxWidget widget,
            @NotNull TtyConnector connector) {
        return connector;
    }

    default void stop() {}
}
```

Verantwortlichkeiten:

- Weisen Sie UI-Ressourcen in `start()` zu
- Dekorieren Sie Anschlüsse in `wrapConnector(...)`, wenn eine Ausgabesteuerung oder -filterung erforderlich ist
– Entfernen Sie Listener, stoppen Sie Zeitleisten, lösen Sie die Bindung von Eigenschaften und geben Sie Referenzen in `stop()` frei

`stop()` muss idempotent sein. Es kann aufgerufen werden, wenn ein Benutzer den Effekt deaktiviert, Effekte wechselt, einen Tab schließt oder Plugins neu lädt.

### TerminalEffectContext

`TerminalEffectContext` macht die aktive Terminalumgebung verfügbar:

| Methode | Zweck |
|--------|---------|
| `pluginId()` | Die aktive Plugin-ID |
| `terminalView()` | Die besitzende KorTTY-Terminalansicht |
| `overlayRoot()` | Ein JavaFX `StackPane` über dem Terminalbereich; Overlay-Knoten hier hinzufügen |
| `widgets()` | Aktuelle SithTermFX-Widgets für die Terminalansicht; Split-Terminals können mehr als ein Widget | haben
| `animationSpeed()` | Vom Benutzer ausgewählte Geschwindigkeit normalisiert durch `TerminalEffectAnimationSpeed` |
| `applyAppearance(TerminalEffectAppearance)` | Wendet Überschreibungen des Terminal-Erscheinungsbilds an |
| `restoreAppearance()` | Stellt das ursprüngliche Erscheinungsbild wieder her, das vor der Aktivierung des Effekts erfasst wurde |

Änderungen am JavaFX-Szenendiagramm müssen im JavaFX-Anwendungsthread ausgeführt werden. Wenn ein Rückruf von einem Terminal-Worker-Thread eingehen kann, verwenden Sie `Platform.runLater(...)` für Overlay- oder UI-Updates.

### TerminalEffectAppearance

`TerminalEffectAppearance` ist ein Datensatz mit Nullable-Feldern:

```java
public record TerminalEffectAppearance(
        @Nullable String fontFamily,
        @Nullable Integer fontSize,
        @Nullable String foregroundColor,
        @Nullable String backgroundColor,
        @Nullable String cursorColor,
        @Nullable String cursorStyle) {
}
```

Verwenden Sie nur die Felder, die Ihr Effekt benötigt. `null` lässt den aktuellen Wert unverändert.

Die aktuelle Implementierung übergibt Farbwerte als Zeichenfolgen, beispielsweise `#19FF4C`. Der Cursorstil ist ebenfalls eine Zeichenfolge. Der MOTHER-Effekt verwendet `BLINK_BLOCK`. Verwenden Sie Werte wieder, die bereits von den Terminaleinstellungen von KorTTY akzeptiert wurden, anstatt neue Namen zu erfinden.

### TerminalEffectConnectorWrapper

Wenn ein Plugin einen Connector umschließt, implementieren Sie `TerminalEffectConnectorWrapper` und delegieren Sie alle `TtyConnector`-Methoden an den umschlossenen Connector, es sei denn, der Effekt ändert diese Methode absichtlich.

```java
public interface TerminalEffectConnectorWrapper extends TtyConnector {
    TtyConnector delegate();
}
```

KorTTY verwendet diese Markierung, um Effekt-Konnektoren zu entpacken, bevor ein anderer Wrapper angewendet wird oder bevor auf den zugrunde liegenden SSH-Konnektor zugegriffen wird. Ohne sie kann es beim erneuten Herstellen einer Verbindung, beim Hochladen per Drag-and-Drop, bei der Nachverfolgung des aktuellen Verzeichnisses durch einen KI-Agenten oder bei zukünftigen Funktionen auf Connector-Ebene dazu kommen, dass der falsche Connector angezeigt wird.

## Minimales Plugin-Beispiel

Verzeichnislayout:

```text
example-terminal-effect/
├── build.gradle.kts
└── src/main
    ├── java/com/example/kortty/effects/AmberTerminalEffectPlugin.java
    └── resources/META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin
```

ServiceLoader-Deskriptor (`META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin`):

```text
com.example.kortty.effects.AmberTerminalEffectPlugin
```

Beispielimplementierung:

```java
package com.example.kortty.effects;

import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;

public final class AmberTerminalEffectPlugin implements TerminalEffectPlugin {

    @Override
    public String id() {
        return "amber";
    }

    @Override
    public String displayName() {
        return "Amber";
    }

    @Override
    public String description() {
        return "Amber monochrome terminal colors.";
    }

    @Override
    public TerminalEffectSession createSession(TerminalEffectContext context) {
        return new TerminalEffectSession() {
            @Override
            public void start() {
                context.applyAppearance(new TerminalEffectAppearance(
                        "Monospaced",
                        null,
                        "#FFB000",
                        "#050200",
                        "#FFD37A",
                        "BLINK_BLOCK"));
            }
        };
    }
}
```

In diesem Beispiel ändert sich nur das Erscheinungsbild. Es ist kein Overlay oder Connector-Wrapper erforderlich.

## Bauen und Verpacken

Dieses Repository definiert derzeit kein separat veröffentlichtes Terminaleffekt-SDK-Artefakt. Der Plugin-Code muss mit den KorTTY-Klassen kompiliert werden, die `de.kortty.plugin.terminaleffects.*` enthalten, und mit denselben öffentlichen Bibliotheken, die das Plugin direkt importiert, wie z. B. SithTermFX oder JavaFX.

Für die Entwicklung innerhalb dieses Repositorys verwenden Sie das MOTHER-Source-Set-Muster in `build.gradle.kts`:

- Fügen Sie Plugin-Quellen einem separaten Quellsatz hinzu
– Fügen Sie `sourceSets.main.output.classesDirs` und `configurations.compileClasspath` in den Kompilierungsklassenpfad dieses Quellsatzes ein
- Packen Sie nur die Plugin-Quellsatzausgabe in eine Plugin-JAR
- Fügen Sie den ServiceLoader-Deskriptor in die Plugin-JAR ein

Die MUTTER-Aufgabe ist das konkrete, erprobte Beispiel:

```bash
./gradlew motherTerminalEffectPluginJar
jar tf build/terminal-effect-plugins/kortty-terminal-effect-mother.jar
```

Das generierte JAR muss Folgendes enthalten:

```text
META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin
de/kortty/plugin/terminaleffects/mother/MotherTerminalEffectPlugin.class
...
```

Für ein Out-of-Tree-Plugin gilt die gleiche Regel: Erstellen Sie eine importierbare JAR-Datei, die Ihre Plugin-Klassen und `META-INF/services/de.kortty.plugin.terminaleffects.TerminalEffectPlugin` enthält. Wenn Sie Bibliotheken verwenden, die KorTTY nicht bereits ausliefert, schattieren Sie sie in dasselbe JAR oder entfernen Sie diese Abhängigkeit.

## Richtlinien für das Connector-Wrapping

Verwenden Sie `wrapConnector(...)` nur, wenn der Effekt Terminal-I/O beobachten, beschleunigen oder umwandeln muss. Bei rein visuellen Überlagerungen und Erscheinungsbildänderungen sollte das Umwickeln von Anschlüssen vermieden werden.

Beim Einpacken:

- Bewahren Sie den `TtyConnector`-Vertrag auf
- Delegieren Sie `write`, `resize`, `waitFor`, `ready`, `getName`, `isConnected` und `close`, es sei denn, der Effekt hat einen bestimmten Grund, sie zu ändern
- Behandeln Sie `InterruptedException`, indem Sie das Interrupt-Flag wiederherstellen und in eine entsprechende geprüfte Ausnahme konvertieren, wenn die Methode `TtyConnector` dies erfordert
- Vermeiden Sie unbegrenztes Puffern, da die Terminalausgabe groß sein kann
- Vermeiden Sie die Verzögerung von Steuersequenzen wie ANSI CSI/OSC-Befehlen, es sei denn, der Effekt ändert absichtlich das Verhalten des Terminalprotokolls
- Implementieren Sie `TerminalEffectConnectorWrapper`, damit KorTTY auf den echten Connector entpacken kann

Der `MotherPacedTtyConnector` von MOTHER ist die aktuelle Referenz für die Ausgabesteuerung. Es teilt die Terminalausgabe in unmittelbare Steuersequenzen und getaktete sichtbare Zeichen auf, nutzt die Animationsgeschwindigkeit des Benutzers und umgeht die Taktung für die Ausgabe mit hohem Volumen, damit das Terminal reaktionsfähig bleibt.

## Overlay-Richtlinien

Overlays sollten JavaFX-Knoten sein, die zu `context.overlayRoot()` hinzugefügt werden.

Gutes Overlay-Verhalten:

- Stellen Sie `setMouseTransparent(true)` so ein, dass das Terminal weiterhin Mauseingaben empfängt
- Legen Sie `setManaged(false)` fest, wenn das Overlay das Layout nicht beeinträchtigen soll
- Binden Sie Breite und Höhe an die Overlay-Wurzel
- Stoppen Sie Zeitleisten/Animationen und lösen Sie die Bindung von Eigenschaften in `stop()`
- Zeichnen Sie so günstig, dass Sie es immer wieder neu bemalen können
- Entfernen Sie beim Herunterfahren den Overlay-Knoten von `overlayRoot()`

Entscheiden Sie bei geteilten Terminals, ob das Overlay die gesamte Terminal-Registerkarte abdecken oder einzelne SithTermFX-Widgets verfolgen soll. `context.widgets()` kann mehr als ein Widget zurückgeben.

## Animationsgeschwindigkeit

Die Animationsgeschwindigkeit ist eine gemeinsame Benutzereinstellung für Terminaleffekte:

- Minimum: `1x`
- Slider-Maximum: `10x`
- Numerisches Maximum: `99x`
– Ungültige, nicht endliche oder nicht positive Werte werden auf `1x` normalisiert

Verwenden Sie `context.animationSpeed()`, wenn Sie das Effekt-Timing berechnen. Die von MOTHER verwendete Konvention lautet:

```java
long scaledDelayMillis = Math.max(1L, Math.round(baseDelayMillis / context.animationSpeed()));
```

Behalten Sie keinen separaten Geschwindigkeitswert im Plugin bei. KorTTY speichert die Verbindungs-/Sitzungsgeschwindigkeit und leitet den normalisierten Wert über `TerminalEffectContext` weiter.

## Import, Export und Persistenz

**Externe Importe:**

- Der Benutzer wählt einen `.jar` unter **Plugins > Terminaleffekte > Importieren...** aus.
- KorTTY kopiert es in `~/.kortty/plugins`
- Der Plugin-Manager lädt Plugins sofort neu

**Exporte:**

– Ein Plugin ist exportierbar, wenn es aus einer echten Quell-JAR geladen wurde
- Das gebündelte MOTHER ist exportierbar, da KorTTY sein gebündeltes JAR vor dem Laden nach `~/.kortty/bundled-plugins/terminal-effects` kopiert
- Anwendungsklassenpfad-Plugins ohne Quell-JAR können nicht exportiert werden

**Beharrlichkeit:**

- Deaktivierte Plugin-IDs werden in `~/.kortty/terminal-effect-plugins.disabled` gespeichert
- Gespeicherte Verbindungen und wiederhergestellte Sitzungen speichern die ausgewählte Terminal-Effekt-Plugin-ID und die Animationsgeschwindigkeit
– Wenn eine gespeicherte Plugin-ID nicht verfügbar oder deaktiviert ist, kann KorTTY sie nicht aktivieren und protokolliert das Problem

## Kompatibilitäts- und Sicherheitscheckliste

Vor dem Versand einer Plugin-JAR:

- Verwenden Sie eine stabile Plugin-ID in Kleinbuchstaben, die zu `[a-z0-9][a-z0-9._-]{0,63}` passt
- Halten Sie `displayName()` und `description()` nicht leer und für den Benutzer lesbar
– Fügen Sie für jede Anbieterklasse genau einen ServiceLoader-Deskriptor hinzu
– Behalten Sie JavaFX-Mutationen im JavaFX-Anwendungsthread bei
- Sorgen Sie dafür, dass `stop()` mehrmals aufgerufen werden kann
– Entfernen Sie Listener, Zeitleisten, Bindungen und Overlay-Knoten in `stop()`
- Vermeiden Sie das Blockieren des JavaFX-Anwendungsthreads
– Vermeiden Sie das Erzeugen nicht verwalteter Threads mit langer Laufzeit
- Halten Sie die Connector-Wrapper transparent und implementieren Sie `TerminalEffectConnectorWrapper`
– Vermeiden Sie die Protokollierung von Geheimnissen oder rohen Terminalausgaben, es sei denn, der Benutzer hat dieses Verhalten ausdrücklich gewählt
- Testen Sie mit schneller Ausgabe, großer Ausgabe, ANSI-Farbausgabe, geteilten Terminals, erneuter Verbindung, Deaktivierung/Aktivierung des Plugins und Herunterfahren der Anwendung

## Manuelle Validierung

Bauen und prüfen:

```bash
./gradlew motherTerminalEffectPluginJar
jar tf build/terminal-effect-plugins/kortty-terminal-effect-mother.jar
```

Führen Sie KorTTY aus:

```bash
./gradlew run
```

Plugin-Verwaltung validieren:

1. Öffnen Sie **Plugins > Terminaleffekte**
2. Bestätigen Sie, dass das Plugin mit Namen und Beschreibung angezeigt wird
3. Deaktivieren und aktivieren Sie es
4. Exportieren Sie es und überprüfen Sie das exportierte JAR mit `jar tf`
5. Importieren Sie das exportierte JAR in eine saubere KorTTY-Konfiguration oder einen anderen Build

Sitzungsverhalten validieren:

1. Aktivieren Sie den Effekt über das Terminal-Kontextmenü oder **Ansicht > Terminaleffekt**
2. Wählen Sie es in Quick Connect und in einem gespeicherten Connection Manager-Eintrag aus
3. Ändern Sie die Animationsgeschwindigkeit mit dem Schieberegler und dem numerischen Feld
4. Führen Sie Befehle aus, die eine langsame, schnelle, farbige und große Ausgabe erzeugen
5. Schließen Sie die Lasche wieder an und schließen Sie sie
6. Überprüfen Sie `~/.kortty/kortty.log` auf Plugin-Ladewarnungen, doppelte IDs, ungültige Anbieter oder Stopp-/Bereinigungsfehler
