# Installation

korTTY läuft auf **macOS, Windows und Linux**. Sie können ein vorgefertigtes Paket installieren
oder aus dem Quellcode erstellen.

## Systemanforderungen

| Anforderung | Minimum |
| --- | --- |
| Java | 25 oder höher (CI-Ausnahme: Der Windows ARM64 Release Runner verwendet derzeit Java 21) |
| Gradle | 9.x – über den Wrapper enthalten (`./gradlew`) |
| Betriebssystem | macOS, Windows, Linux |
| Optional – JobScheduler Rsync | lokal `rsync` und `ssh` in `PATH` oder ein konfigurierter `rsync`-Binärpfad |
| Optional – Terminal-Videoexport | lokal `ffmpeg` in `PATH` oder ein Pfad, der in **Tools → Video Manager** | konfiguriert ist

## Vorgefertigte Binärdateien (empfohlen)

Gebrauchsfertige Pakete werden auf der veröffentlicht
[GitHub-Releases-Seite](https://github.com/chardonnay/korTTY/releases). Jedes Asset
Der Name beinhaltet die Architektur (`-x86_64` / `-aarch64` / `-arm64`). Wählen Sie die Datei aus
Passend zu Ihrem System:

=== "macOS"
Nur Apple Silicon – verwenden Sie den `-aarch64` `.dmg`.

=== „Windows“
Verwenden Sie `-x86_64` für Intel/AMD oder `-arm64` für Windows auf ARM. `.exe` (tragbar)
oder `.msi` (Installateur).

=== "Linux"
Verwenden Sie `-x86_64` oder `-aarch64` für ARM (z. B. Raspberry Pi 4, viele Cloud
Instanzen). Pakete: `.deb`, `.rpm`, `.tar.gz`, `.zip`.

## Aus dem Quellcode erstellen

```bash
git clone https://github.com/chardonnay/korTTY.git
cd korTTY
./gradlew build
```

Direkt ausführen:

```bash
./gradlew run
```

### Native Pakete lokal erstellen

korTTY ist mit `jpackage` verpackt; Die Ausgabe entspricht der Architektur des
Maschine bauen.

| Plattform | Befehl | Ausgabe |
| --- | --- | --- |
| macOS (.app) | `./gradlew jpackage` | `build/jpackage/korTTY.app` |
| macOS (.dmg) | `./gradlew jpackageDmg` | `build/jpackage/korTTY-<version>.dmg` |
| Windows (.exe) | `gradlew.bat jpackage` | `build\jpackage\korTTY\` |
| Windows (.msi) | `gradlew.bat jpackageMsi` | `build\jpackage\korTTY-<version>.msi` |
| Linux (AppImage) | `./gradlew jpackage` | `build/jpackage/korTTY/` |
| Linux (.deb) | `./gradlew jpackageDeb` | `build/jpackage/korTTY-<version>.deb` |
| Linux (.rpm) | `./gradlew jpackageRpm` | `build/jpackage/korTTY-<version>.rpm` |

!!! Hinweis „Datenschutz im lokalen Netzwerk von macOS“
Wenn korTTY über den Gradle-Daemon gestartet wird, wird es als untergeordnetes Element eines Hintergrunds ausgeführt
Prozess, der keine „Lokales Netzwerk“-Berechtigung hat, also eine Verbindung zu LAN /
Private-IP-Hosts können fehlschlagen. Starten Sie für LAN-SSH das Paket `.app` (das
fordert beim ersten Start zum Zugriff auf das lokale Netzwerk auf) oder starten Sie korTTY als Kind
des Terminals.

[Weiter: Erster Start und Master-Passwort →](first-launch.md){ .md-button }
