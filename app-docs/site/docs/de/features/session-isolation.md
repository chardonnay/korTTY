---
title: Sitzungs-Isolation
---

# Sitzungs-Isolation

korTTY kann eine Terminal-Sitzung getrennt von korTTY selbst und von den anderen Tabs ausführen. Wie weit, wählen Sie pro Verbindung, pro Ordner des Connection-Managers oder für alles unter **Konfiguration → Globale Einstellungen → Sicherheit**, und der Tab zeigt, was die Sitzung tatsächlich bekommen hat.

## Isolationsstufen

| Stufe | Wirkung |
| --- | --- |
| Keine | Die Sitzung läuft wie zuvor. |
| Eigener Prozess | Die Sitzung läuft in einem eigenen Prozess. |
| Eigener Prozess + Sandbox | Der Prozess läuft zusätzlich in der Sandbox des Betriebssystems: Er kann das Konfigurationsverzeichnis `~/.kortty` von korTTY (Verbindungen, Anmeldedaten, `master.key`, Logs, Journale), `~/.ssh`, `~/.gnupg` und die Schlüsselbunde weder lesen noch schreiben, und er darf nur in seinen Arbeitsordner, den temporären Ordner und einen eigenen Ordner schreiben, der beim Ende der Sitzung gelöscht wird. |

Was jede Verbindungsart in dieser Version bekommen kann:

| Verbindungsart | Keine | Eigener Prozess | Eigener Prozess + Sandbox |
| --- | --- | --- | --- |
| Lokale Shell | ja | ja (eine lokale Shell ist immer ein eigener Prozess) | ja |
| Mosh (nativer `mosh-client`) | ja | ja | ja: `mosh-client` läuft in der Sandbox; der kurze SSH-Start von `mosh-server` bleibt in korTTY |
| SSH | ja | noch nicht | noch nicht |
| Mosh (eingebauter Client) | ja | noch nicht | noch nicht |

Eine Verbindung, die mehr verlangt, als ihre Verbindungsart bekommen kann, läuft mit der stärksten Stufe, die möglich ist, und der Verbindungseditor sagt das unter **Isolation:**. Verlangt Ihre Organisation eine Stufe, die die Verbindungsart nicht bekommen kann, wird die Sitzung gar nicht erst geöffnet.

!!! note "Die Sandbox auf jedem Betriebssystem"
    Auf macOS verwendet korTTY `sandbox-exec` mit einem Profil, das es für jede Sitzung erzeugt. Auf Linux verwendet es bubblewrap (`bwrap`), das installiert sein muss; es braucht unprivilegierte User-Namespaces, die manche Container nicht erlauben. Auf Windows und wenn korTTY in Flatpak läuft (selbst eine Sandbox, die keine weitere darin erlaubt), gibt es noch keine Sandbox. Vor der ersten Sitzung in der Sandbox führt korTTY einen Selbsttest aus: Eine geheime Datei in seinem Konfigurationsordner muss aus der Sandbox heraus unlesbar sein, während ein harmloser Befehl weiterhin läuft. Nur eine Sandbox, die den Test besteht, gilt als verfügbar.

In einer lokalen Shell in der Sandbox zeigen die History-Datei (`HISTFILE`) und `TMPDIR` auf den eigenen Ordner der Sitzung, sodass die History der Shell die Sitzung nicht überdauert, und die Variable `KORTTY_SANDBOX` nennt die verwendete Sandbox.

## Eine Stufe wählen

- **Für eine Verbindung:** Connection-Manager → Verbindung bearbeiten → Tab **Terminal-Einstellungen** → **Sitzungs-Isolation** (unter **Terminal-Verhalten**) → **Isolation:**. **Standard verwenden** folgt dem Ordner der Verbindung oder den Einstellungen und nennt, was davon gilt.
- **Für einen Ordner:** Rechtsklick auf den Ordner im Connection-Manager → **Sitzungs-Isolation** → **Standard verwenden**, **Keine**, **Eigener Prozess** oder **Eigener Prozess + Sandbox**. Ordner darunter erben die Stufe, ebenso die Verbindungen darin, die keine eigene festlegen. Die Stufe zieht mit, wenn der Ordner umbenannt wird, und wird entfernt, wenn der Ordner gelöscht wird.
- **Für alles andere:** **Konfiguration → Globale Einstellungen → Sicherheit** → **Sitzungs-Isolation** → **Standard-Isolation:**. Der Abschnitt zeigt auch, ob dieser Computer eine funktionierende Sandbox hat.

Eine Verbindung aus einer geteilten Teamwork-Datei kann die Isolation nur strenger machen als Ihr Ordner oder die Einstellungen, nie lockerer.

## Was der Tab anzeigt

![Tabs mit gefülltem Schild und Spion, mit Schild-Umriss und ohne Markierung](../assets/screenshots/main/session-isolation-tabs.png)

| Tab-Symbol | Bedeutung |
| --- | --- |
| kein Schild | Die Sitzung ist nicht isoliert. |
| Schild-Umriss (blau) | Eigener Prozess. |
| Gefülltes Schild (grün) | Eigener Prozess innerhalb einer verifizierten Sandbox; der Tooltip nennt die Sandbox (`sandbox-exec` oder `bubblewrap`). |
| Schild mit Ausrufezeichen (bernsteinfarben) | Eine Sandbox wurde angefordert, ist aber nicht aktiv, zum Beispiel weil `bwrap` fehlt; die Sitzung läuft im eigenen Prozess, und der Tooltip sagt, warum. |
| Spion (violett) | Eine Inkognito-Sitzung (siehe unten). |

Bei geteilten Bereichen zeigt das Schild den am wenigsten isolierten Bereich, nur eine fehlende Sandbox zeigt immer das Warnzeichen; der Tooltip des Tabs zählt dann, wie viele Bereiche in einer Sandbox laufen, im eigenen Prozess, ohne die angeforderte Sandbox und ohne Isolation. Screenreader lesen dieselben Texte.

## Strenger Terminal-Modus

**Strenger Terminal-Modus:** im selben Abschnitt verwirft Escape-Sequenzen, die ein feindlicher Server missbrauchen könnte, bevor das Terminal sie sieht: Zugriff auf die Zwischenablage (OSC 52), Links, die keine `http`- oder `https`-Links sind (OSC 8), Fenstertitel über 256 Zeichen oder mit Steuerzeichen, jede OSC-Sequenz über 4 KiB sowie Gerätesteuerungs-Sequenzen (DCS), Application Program Commands, Privacy Messages und Start-of-String-Sequenzen. Alles andere, auch Farben, Markierungen der Shell-Integration und das Arbeitsverzeichnis, das eine Shell meldet, bleibt unverändert. **Automatisch (an in einer Sandbox)** ist die Vorgabe; **An** und **Aus** legen es für die Verbindung fest. Eine Teamwork-Verbindung kann ihn einschalten, aber nicht ausschalten.

## Inkognito-Sitzungen

Eine Inkognito-Sitzung hinterlässt keine Spur in korTTY: kein Terminal-Log, kein Session-Journal, keine Aufzeichnung, keinen Eintrag unter **Datei → Zuletzt geschlossen**, nichts von ihrem Bildschirm in einem gespeicherten Projekt und keinen Platz in der Sitzung, die korTTY nach einem Neustart wiederherstellt. Ihr Tab zeigt einen Spion. Inkognito funktioniert mit jeder Isolationsstufe, mit oder ohne Sandbox.

- **Jede Sitzung einer Verbindung:** Haken Sie **Inkognito-Sitzung** im Abschnitt **Sitzungs-Isolation** des Verbindungseditors an. Eine Verbindung aus einer geteilten Teamwork-Datei kann nicht inkognito gemacht werden.
- **Eine Sitzung:** **Datei → Neue Inkognito-Sitzung…** öffnet die Schnellverbindung; der Tab, den sie öffnet, ist inkognito, egal was die Verbindung festlegt.

!!! warning "Was Inkognito nicht abdeckt"
    Inkognito betrifft, was korTTY aufzeichnet. Der Server, die eigene Historie einer Remote-Shell und alles, was ein Programm in der Sitzung schreibt, sind davon nicht betroffen. Wenn Ihre Organisation ein Sitzungsjournal für jede Sitzung erzwingt, wird auch eine Inkognito-Sitzung journalisiert, und eine Organisation kann Inkognito-Sitzungen ganz untersagen.

## Organisationsrichtlinie

Eine Unternehmensrichtlinie kann mit `[rule.isolation] minimum` eine Mindest-Isolation verlangen und mit `incognito-sessions = "deny"` in `[rule.features]` Inkognito-Sitzungen verbieten; siehe [Unternehmensrichtlinie](../reference/enterprise-policy.md). Die Stufen unter dem Minimum sind dann überall ausgegraut, und eine Sitzung, die das Minimum nicht bekommen kann, wird mit einer Meldung abgelehnt, statt mit weniger geöffnet zu werden.
