# Verbindungen

korTTY verwaltet SSH-, Mosh- und **Local-Shell**-Verbindungen über drei Einstiegspunkte: die **Schnellverbindung**, den **Verbindungsmanager** und gespeicherte **Projekte**.

![Connection flow](../assets/diagrams/connection-flow.svg)

## Schnellverbindung

Öffnen mit ++ctrl+k++ (oder **Verbindungen → Schnellverbindung…**). Geben Sie Host, Port, Benutzernamen und Authentifizierung ein und stellen Sie eine Verbindung her, ohne zu speichern. Häufig verwendete Verbindungen werden als Schnellschaltflächen angezeigt. Eine Live-Suche filtert sie. Gespeicherte Verbindungen können aus einem Dropdown-Menü mit einem eigenen Suchfeld ausgewählt werden, das nach Name, Host oder [Tag](#tags) filtert (`*` funktioniert als Platzhalter); Das Tag einer gespeicherten Verbindung wird neben ihrem Namen (🏷) im Dropdown-Menü und in den QuickInfos der Schnellschaltflächen angezeigt.

## Verbindungsmanager

**Verbindungen → Verbindungen verwalten…** öffnet einen durchsuchbaren Baum gespeicherter Verbindungen (optional gruppiert); Das Suchfeld durchsucht Name, Host, IP-Adresse oder [Tag](#tags), mit `*` als Platzhalter. Von hier aus können Sie Verbindungen erstellen, bearbeiten, duplizieren, löschen, markieren, importieren und exportieren.

## Verbindung erstellen/bearbeiten

Der Verbindungseditor verfügt über folgende Registerkarten:

| Registerkarte | Inhalt |
| --- | --- |
| Verbindung | Host, Port, Benutzername, Protokoll (SSH / Mosh / Local Shell), Authentifizierung (Passwort / Schlüssel / Tastatur-interaktiv), **Host-Schlüsselüberprüfung** (Standard verwenden / überprüfen / nicht überprüfen), Gruppen-/Ordnerzuweisung und optionales Freitext-[Tag](#tags). Für **Local Shell**-Verbindungen sind Host, Port, Benutzername und Authentifizierung nicht erforderlich und deaktiviert. |
| Terminaleinstellungen | Farben pro Verbindung, Schriftart, ANSI/TrueColor-Behandlung, Terminaleffekt |
| SSH-Tunnel | Lokale / Remote- / dynamische Portweiterleitung |
| Jump Server | Bastion-Host-Verkettung |
| Terminalprotokollierung | Schreibt die Terminalausgabe dieser Verbindung in eine Datei – Ordner, Format, tägliche Rotation, Komprimierung und Aufbewahrung. Siehe [Terminalprotokollierung](terminal.md#terminalprotokollierung). |
| Journal | Pro Verbindung [Sitzungsjournal](session-journal.md): Aktivieren Sie das Journaling für diese Verbindung und konfigurieren Sie das Capture-Log und die KI-Zusammenfassung |
| Fenstergeometrie | Gespeicherte Größe/Position für diese Verbindung |
| KI | KI-Standardeinstellungen pro Verbindung: die [KI-Profil](ai-assistant.md) und KI-Fähigkeiten, die von Terminal-KI-Funktionen auf dieser Verbindung verwendet werden |

## Tags

Jede gespeicherte Verbindung kann ein optionales Freitext-**Tag** tragen – eine Bezeichnung wie `prod`, `staging` oder einen Kundennamen – unabhängig von der Gruppen-/Ordnerhierarchie. Legen Sie es auf der Registerkarte *Verbindung* des Verbindungseditors (neben der Gruppe) oder gesammelt im Verbindungsmanager fest. Tags werden mit der Verbindung in `connections.xml` gespeichert und überstehen das Duplizieren, Exportieren und Importieren.

- **Sichtbar** – markierte Verbindungen zeigen ein 🏷-Symbol nach ihrem Namen im Verbindungsmanager-Baum und im Dropdown-Menü „Gespeicherte Verbindungen“ der Schnellverbindung; Das Tag erscheint auch im Tooltip der häufig verwendeten Schnellschaltflächen.
- **Durchsuchbar** – Die Verbindungsmanager-Suche (Registerkarten „Lokal“ und „Teamwork“) und die Suche nach gespeicherten Verbindungen der Schnellverbindung berücksichtigen Tags ebenso wie Namen und Hosts.
- **Massenzuweisung/-entfernung** – Wählen Sie einen oder mehrere Server aus und wählen Sie **Tag zuordnen** aus dem Kontextmenü, um sie in einem Schritt zu taggen. Die Eingabeaufforderung ist vorab ausgefüllt, wenn alle ausgewählten Verbindungen bereits dasselbe Tag haben. Durch das Löschen dieses vorab ausgefüllten Werts wird das Tag entfernt. **Tag entfernen** löscht das Tag und ist nur aktiviert, solange die Auswahl mindestens eine markierte Verbindung enthält. Die gleichen zwei Einträge im Kontextmenü eines Ordners gelten für jede Verbindung in diesem Ordner, einschließlich Unterordnern.
- **Nach Tag exportieren** – Sobald mindestens ein Tag vorhanden ist, bietet das Exportdialogfeld des Verbindungsmanagers die Option **Zu exportierende Verbindungen**: Behalten Sie die vorab ausgewählten Verbindungen bei oder exportieren Sie **alle Verbindungen mit diesen Tags** – wählen Sie ein oder mehrere Tags aus der Liste aus, die Verbindungsanzahl des Headers folgt der Auswahl live und die Schaltfläche „Exportieren“ bleibt deaktiviert, solange nichts übereinstimmt.

## Protokolle

=== "SSH"
    Standard-SSH über Apache MINA SSHD. Unterstützt Passwort-, Public-Key- und Tastatur-interaktive Authentifizierung, Keep-Alive und anklickbare OSC 8-Hyperlinks.

=== "Mosh"
    Roaming, latenzfreundlicher Mosh-Transport (mosh4j). Das Mosh-Backend ist in nativen Builds gebündelt; Bestehende Verbindungen benötigen keine Migration.

=== "Lokale Shell"
    Öffnet die Shell des **lokalen Rechners** in einer Terminal-Registerkarte (kein Netzwerk) über ein pty4j-gestütztes Pseudo-Terminal. Host, Port, Benutzername und Authentifizierung sind nicht erforderlich. Siehe [Local Shell](#lokale-shell) unten.

## SSH-Hostschlüsselüberprüfung

Interactive Terminal- und SFTP-Verbindungen verwenden denselben TOFU-Hostschlüsselspeicher (Trust-on-First-Use). Mosh verwendet es auch für den SSH-Bootstrap. Die Vertrauenswürdigkeit wird durch den normalisierten Hostnamen und Port bestimmt, sodass verschiedene gespeicherte Verbindungen zum selben Endpunkt eine gemeinsame Entscheidung treffen.

Bei der ersten Verbindung zeigt korTTY den Schlüsselalgorithmus und den OpenSSH SHA-256-Fingerabdruck an. Überprüfen Sie diesen Fingerabdruck beim Serveradministrator, bevor Sie **Ja** auswählen. **Nein** ist die sichere Standardeinstellung. Ein passender Schlüssel wird bei späteren Verbindungen stillschweigend akzeptiert. Wenn der Server einen anderen Schlüssel vorlegt, blockiert korTTY die Verbindung hart, zeigt die erwarteten und angebotenen Fingerabdrücke an und versucht es nicht erneut, da eine Wiederholung des Versuchs einen möglichen Man-in-the-Middle-Angriff nicht auflösen kann.

Die Erstverwendungsaufforderung kann für Hosts deaktiviert werden, bei denen sie nicht erwünscht ist – legen Sie die **Hostschlüsselüberprüfung** auf der Registerkarte *Verbindung* des Verbindungseditors oder in der Schnellverbindung (**Standard verwenden** / **Überprüfen** / **Nicht überprüfen**), pro Gruppe über das Gruppenkontextmenü des Verbindungsmanagers oder global unter **Einstellungen → Terminal** fest. Die Lockerung betrifft nur „Neu akzeptieren“: Ein unbekannter Schlüssel wird ohne Aufforderung gepinnt, aber ein Schlüssel, der sich von einem unterscheidet, der bereits für diesen Host gepinnt ist, wird immer noch fest blockiert. Siehe [Lockere Hostschlüsselüberprüfung](security.md#lockere-uberprufung-des-hostschlussels).

Die interaktiven Pins werden atomar in `~/.kortty/ssh-host-keys.properties` gespeichert, mit prozessübergreifender Sperrung, sodass zwei korTTY-Fenster die Entscheidungen des anderen nicht überschreiben können. Diese endpunktbasierten Pins sind von den verbindungs-ID-basierten Pins getrennt, die von unbeaufsichtigten JobScheduler-SSH-, SFTP- und Rsync-Jobs verwendet werden.

Wenn eine neue geteilte Verbindung geöffnet wird, wird der SSH-Handshake auf einem Worker ausgeführt, während ein Fortschrittsdialog dafür sorgt, dass die JavaFX-Schnittstelle reagiert. Dadurch können sowohl die Hostschlüsselbestätigung als auch die interaktive Tastaturauthentifizierung abgeschlossen werden, ohne dass die Benutzeroberfläche blockiert wird.

## Lokale Shell

Eine **Lokale Shell**-Verbindung erzeugt ein lokales Pseudo-Terminal (PTY) auf Ihrem eigenen Computer, anstatt eine Verbindung zu einem Remote-Host herzustellen. Es ist sowohl in der **Schnellverbindung** als auch im **Connection Manager** auswählbar; Für diese Verbindungen sind Host, Port, Benutzername und Authentifizierung nicht erforderlich (und in den Dialogen deaktiviert), und es wird keine Passwortabfrage angezeigt.

### Eine Shell auswählen

| Plattform | Optionen |
| --- | --- |
| Windows | **PowerShell** (Standard) oder **cmd.exe**. **Git Bash**, **Cygwin** und **WSL** werden ebenfalls als Voreinstellungen angeboten – allerdings nur, wenn sie tatsächlich installiert sind (Git Bash/Cygwin werden über ihre üblichen Installationsorte / `PATH` erkannt; WSL erscheint nur, wenn `wsl.exe` vorhanden und mindestens eine Distribution installiert ist). |
| macOS / Linux | Standardmäßig Ihr `$SHELL` (Rückfall auf `/bin/zsh` oder `/bin/bash`). |

Ein Freiformfeld **Benutzerdefinierter Befehl** akzeptiert jede ausführbare Datei mit Argumenten (z. B. `pwsh.exe`, `wsl.exe -d Ubuntu`, ein Git-Bash-Pfad) und ein optionales **Startverzeichnis** kann festgelegt werden. Der Befehlsparser erkennt Anführungszeichen, sodass Shell-Pfade, die Leerzeichen enthalten – wie `"C:\Program Files\Git\bin\bash.exe"` – korrekt gestartet werden.

### Terminalfunktionen in lokalen Shells

Die Terminalprotokollierung und -aufzeichnung sowie die AI-Eingabe-/Daten-Hooks funktionieren für lokale Shells über eine gemeinsam genutzte `ObservableTtyConnector`-Schnittstelle. Eingegebene und eingefügte Agentenanforderungen verwenden denselben Eingabepfad auf Byteebene, und Terminaldateiaktionen sowie lokale Agentenausführungen folgen dem aktuellen Verzeichnis der interaktiven Shell. macOS/Linux verwenden das lokale Prozessverzeichnis; Native PowerShell und cmd verwenden absolute Eingabeaufforderungspfade. WSL, Git Bash, Cygwin und benutzerdefinierte Befehle eignen sich am besten, wenn sich ihr Shell-Pfad-Namespace vom Host-Dateisystem unterscheidet und ein nicht zuordenbares Verzeichnis einen expliziten Fehler anstelle eines Fallbacks auf eine falsche Datei erzeugt. Funktionen, die von einem SSH-Kanal abhängen, bleiben nur SSH.

!!! note "AI Agent in lokalen Shells"
    Der **AI Agent** und **AI Planning** laufen auch in lokalen Shells unter Windows, macOS und Linux – siehe [AI Assistant](ai-assistant.md#ai-agent-und-ki-planung).

## Tunnels und Sprungserver

- **SSH-Tunnel** – Ports über die Verbindung weiterleiten: **lokal** (`-L`), **remote** (`-R`) oder **dynamisch / SOCKS** (`-D`).
- **Jump-Server (Bastion)** – Leiten Sie die Verbindung über einen Zwischenhost weiter; Sowohl SSH-Terminal- als auch SFTP-Sitzungen springen darüber. Siehe [Jump Server](jump-server.md).

![Jump server flow](../assets/diagrams/jump-server-flow.svg)

## Import von anderen Clients

**Verbindungen → Importieren…** liest Verbindungsdateien von **MTPuTTY**, **MobaXterm** und **PuTTY Connection Manager**, mit Gruppenfilterung und Anmeldeinformationsverarbeitung.

!!! note "Mehr folgt"
    Diese Seite ist Teil des Scaffolded-Anleitungs. Als nächstes wird die vollständige Funktionsbibliothek – SFTP, Snippets, JobScheduler, KI-Assistent und -Tools, Terminalaufzeichnung, Sicherheit und die vollständigen Einstellungstabellen – ausgefüllt.
