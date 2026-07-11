# KorTTY-Pakete erstellen

korTTY kann als Java-Artefakte, als eigenständiges tragbares Anwendungs-Image oder als natives Installationsprogramm erstellt werden. Native Pakete müssen auf dem Zielbetriebssystem und der Prozessorarchitektur erstellt werden, da `jpackage`, JavaFX und die gebündelten nativen Tools plattformspezifische Dateien von der aktiven Build-Maschine auswählen.

## Wählen Sie ein Artefakt

| Ziel | Gradle-Aufgabe | Lokale Ausgabe | Java auf dem Ziel erforderlich? |
| --- | --- | --- | --- |
| Thin Application JAR | `jar` | `build/libs/korTTY-<version>.jar` | Ja, plus alle Laufzeitabhängigkeiten; Dieses JAR ist kein eigenständiges ausführbares JAR |
| Java-Distribution | `distZip` / `distTar` | `build/distributions/korTTY-<version>.zip` / `.tar` | Ja; Das Archiv enthält Abhängigkeiten und Startskripte, aber keine Java-Laufzeit |
| Eigenständiges App-Image | `jpackage` | Plattform-App-Bundle oder Verzeichnis unter `build/jpackage/` | Nein; `jpackage` enthält eine getrimmte Java-Laufzeitumgebung |
| Nativer Installer | `jpackageDmg`, `jpackageMsi`, `jpackageDeb` oder `jpackageRpm` | DMG, MSI, DEB oder RPM unter `build/jpackage/` | Nein; Das Installationsprogramm enthält das eigenständige App-Image |
| Portables natives Archiv | `jpackage`, dann der unten gezeigte Betriebssystem-Archivierungsbefehl | ZIP oder TAR mit dem gesamten App-Image | Nein; Behalten Sie das Bundle oder Verzeichnis nach der Extraktion bei |

!!! important "Ein App-Image ist keine einzelne ausführbare Datei"
    Unter macOS ist das App-Image das vollständige `korTTY.app`-Bundle, unter Windows das vollständige `korTTY`-Verzeichnis mit `korTTY.exe` und unter Linux das vollständige `korTTY`-Verzeichnis mit `bin/korTTY`. Durch das Verschieben nur des Launchers wird die Anwendung unterbrochen, da ihre Laufzeit und Bibliotheken in Geschwisterverzeichnissen verbleiben. Das Linux-Verzeichnis ist keine `.AppImage`-Datei.

## Allgemeine Voraussetzungen

Installieren Sie diese Tools, bevor Sie auf einem Betriebssystem aufbauen:

| Anforderung | Warum korTTY es braucht |
| --- | --- |
| Vollständiges **JDK 25** passend zur Ziel-CPU | Kompiliert korTTY und stellt `java`, `javac`, `jlink` und `jpackage` bereit; Legen Sie das Verzeichnis `bin` in `PATH` ab und setzen Sie `JAVA_HOME` auf dasselbe JDK |
| Git | Klont korTTY und die angeheftete SithTermFX-Quellenabhängigkeit |
| Apache Maven | Erstellt SithTermFX vor der korTTY-Kompilierung im lokalen Maven-Repository |
| `curl` | Lädt die angehefteten mosh4j-Artefakte herunter, die von gepackten Builds verwendet werden |
| Ausgehender HTTPS-Zugriff | Der Wrapper, Maven/Gradle-Abhängigkeiten, SithTermFX, mosh4j, Node.js, Formatierer, Monaco und Chat-Render-Ressourcen werden während eines sauberen ersten Builds heruntergeladen |
| Gradle Wrapper aus diesem Repository | `gradlew` und `gradlew.bat` laden die im Repository angeheftete Gradle-Version herunter und führen sie aus; Installieren oder ersetzen Sie kein System Gradle |

Node.js und MkDocs sind keine allgemeinen Voraussetzungen für eine Anwendungserstellung: Die Gradle-Aufgaben laden eine angeheftete Node.js-Laufzeitumgebung für generierte Assets herunter, und die normale Anwendungserstellung verwendet die festgeschriebenen Offline-Anleitungressourcen.

!!! warning "Verwenden Sie genau JDK 25"
    Die gepackte Laufzeit enthält explizit `jdk.jsobject`, das JavaFX WebView benötigt und das in JDK 26 entfernt wurde. Die Entfernung wird als OpenJDK-Problem `JDK-8362628`: <https://bugs.openjdk.org/browse/JDK-8362628> verfolgt. Gradle kann seine Compiler-Toolchain unabhängig bereitstellen, aber die Paketierungsaufgaben rufen den bloßen Befehl `jpackage` von `PATH` auf; ein neueres oder älteres `jpackage` kann daher auch dann fehlschlagen, wenn bei der Kompilierung JDK 25 ausgewählt wurde. Verweisen Sie sowohl `JAVA_HOME` als auch die ersten `java`/`javac`/`jpackage`-Einträge in `PATH` auf dieselbe JDK 25-Installation.

Klonen Sie die Quelle einmal:

```bash
git clone https://github.com/chardonnay/korTTY.git
cd korTTY
```

Stellen Sie sicher, dass jeder Befehl vor dem ersten Build aufgelöst wird. `java`, `javac` und `jpackage` müssen alle die Hauptversion 25 melden, und Maven muss dasselbe `JAVA_HOME` melden.

=== "macOS / Linux"
    ```bash
    command -v java javac jpackage git mvn curl
    java -XshowSettings:properties -version 2>&1 | grep -E 'java.specification.version|os.arch'
    javac --version
    jpackage --version
    git --version
    mvn --version
    curl --version
    ./gradlew --version
    ```

=== "Windows PowerShell"
    ```powershell
    Get-Command java, javac, jpackage, git, mvn, curl
    java -XshowSettings:properties -version 2>&1 | Select-String 'java.specification.version|os.arch'
    javac --version
    jpackage --version
    git --version
    mvn --version
    curl --version
    .\gradlew.bat --version
    ```

Der erste Wrapper-Aufruf lädt Gradle herunter. Ein sauberer Anwendungsbuild klont bei Bedarf auch SithTermFX, installiert es über Maven, lädt die angehefteten Build-Eingaben herunter, kompiliert korTTY und führt die Tests aus.

### Erstellen Sie die Java-Artefakte

Verwenden Sie den folgenden Plattformbefehl, um das Thin JAR sowie die Java ZIP- und TAR-Distributionen zu kompilieren, zu testen und zu erstellen:

=== "macOS / Linux"
    ```bash
    ./gradlew clean build
    ls -l build/libs/ build/distributions/
    ```

=== "Windows PowerShell"
    ```powershell
    .\gradlew.bat clean build
    Get-ChildItem .\build\libs, .\build\distributions
    ```

Das Thin-JAR enthält korTTY-Klassen und -Ressourcen, jedoch keine Laufzeitabhängigkeiten und stellt selbst nicht die vollständige Bereitstellung bereit. Für ein Java-basiertes tragbares Layout extrahieren Sie die generierte ZIP- oder TAR-Datei und starten Sie `bin/korTTY` unter macOS/Linux oder `bin\korTTY.bat` unter Windows; Das Ziel benötigt weiterhin JDK 25. Diese Distributionen enthalten auch die nativen JavaFX-Abhängigkeiten, die auf dem Build-Host ausgewählt wurden. Erstellen Sie sie also auf demselben Betriebssystem und derselben Architektur, auf der sie ausgeführt werden.

## macOS

### Zusätzliche Software

Installieren Sie die allgemeinen Voraussetzungen und die Apple-Befehlszeilentools. Der unsignierte `.app`-Build verwendet die JDK-Tools; Für die DMG-Erstellung werden `hdiutil` und das konfigurierte benutzerdefinierte Symbol verwendet, während für die Signierung und Beglaubigung zusätzlich `codesign`, `security`, `notarytool` und `stapler` verwendet werden. Wählen Sie vor einem Release-Build eine aktuelle Xcode-Installation aus.

```bash
xcode-select -p
xcrun --find codesign
xcrun --find notarytool
xcrun --find stapler
command -v hdiutil ditto
```

Führen Sie `xcode-select --install` aus, wenn die Befehlszeilentools nicht vorhanden sind. Eine für andere Benutzer bestimmte Veröffentlichung erfordert außerdem ein Apple Developer-Konto, ein **Developer ID Application**-Zertifikat und App Store Connect API-Anmeldeinformationen für die Beglaubigung; Bei einem nicht signierten lokalen Build ist dies nicht der Fall.

### Erstellen Sie ein unsigniertes App-Image, eine tragbare ZIP- und DMG-Datei

```bash
./gradlew clean build
./gradlew jpackage
ditto -c -k --keepParent build/jpackage/korTTY.app "build/jpackage/korTTY-macOS-local-$(uname -m).zip"
./gradlew jpackageDmg
```

| Artefakt | Ausgabe |
| --- | --- |
| Eigenständiges App-Image | `build/jpackage/korTTY.app` |
| Tragbares Archiv | `build/jpackage/korTTY-macOS-local-<architecture>.zip` |
| Disk-Image | `build/jpackage/korTTY-<version>.dmg` |

Öffnen Sie das App-Image für einen lokalen Rauchtest und überprüfen Sie den DMG-Container:

```bash
open build/jpackage/korTTY.app
hdiutil verify build/jpackage/korTTY-*.dmg
```

### Build macOS für Intel (`x86_64`)

Die zuverlässigen Pfade sind ein Intel Mac mit einem x86_64 JDK 25 oder der GitHub Actions-Job des Repositorys auf `macos-15-intel`. `jpackage` führt keine Cross-Kompilierung durch, daher erstellt ein ARM-JDK, das nativ auf Apple Silicon ausgeführt wird, eine ARM-App, selbst wenn der gewünschte Dateiname `x86_64` lautet.

Validieren Sie auf einem Intel-Mac die Maschine, die Java-Laufzeit und den generierten Launcher, bevor Sie das Ergebnis verteilen:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25 -a x86_64 -F)"
export PATH="$JAVA_HOME/bin:$PATH"
test "$(uname -m)" = "x86_64"
java -XshowSettings:properties -version 2>&1 | grep 'os.arch = x86_64'
test "$(jpackage --version | cut -d. -f1)" = "25"
./gradlew --stop
./gradlew clean build
./gradlew jpackage
./gradlew jpackageDmg
lipo -archs build/jpackage/korTTY.app/Contents/MacOS/korTTY
```

Der letzte `lipo`-Befehl darf nur `x86_64` ausgeben. Führen Sie den gleichen Build auf Apple Silicon mit einem ARM-JDK durch, um die separaten `aarch64`-Artefakte zu erhalten. Das Projekt erstellt keine universelle Binärdatei.

#### Intel-Build mit GitHub-Aktionen

Der `Build Release Binaries`-Workflow enthält separate macOS-Matrixeinträge für `macos-latest`/`aarch64` und `macos-15-intel`/`x86_64`. Es installiert Temurin JDK 25, lehnt einen JDK- oder Runner-Architekturkonflikt ab, erstellt sowohl die signierte App als auch DMG, verifiziert den Launcher mit `lipo`, beglaubigt beide Ergebnisse und lädt Artefakte mit Architektursuffix hoch.

Konfigurieren Sie diese Repository-Geheimnisse, bevor Sie den Workflow ausführen:

| Geheimer | Inhalt |
| --- | --- |
| `APPLE_SIGNING_CERT_P12_BASE64` | Base64-codiertes Entwickler-ID-Anwendungszertifikat und privater Schlüssel im P12-Formular |
| `APPLE_SIGNING_CERT_PASSWORD` | Passwort zum Schutz der P12-Datei |
| `APPLE_SIGNING_IDENTITY` | Exakte Entwickler-ID Anwendungsidentität angezeigt durch `security find-identity` |
| `APPLE_API_KEY_ID` | App Store Connect API-Schlüssel-ID |
| `APPLE_ISSUER_ID` | App Store Connect-Aussteller-ID |
| `APPLE_API_KEY` | Vollständiger Inhalt des privaten API-Schlüssels |

Öffnen Sie **Aktionen → Release-Binärdateien erstellen → Workflow ausführen**, wählen Sie den Zweig oder das Tag in `ref` aus und geben Sie die numerische Anwendungsversion ein. Ein Branch-Lauf lädt die macOS-Build-Artefakte zur Überprüfung hoch; Ein veröffentlichtes GitHub-Release oder eine manuelle Ausführung, deren `ref` ein Release-Tag ist, speist auch den endgültigen Release-Upload-Job. Die Intel-Artefakte heißen `korTTY-macOS-<version>-x86_64.zip` und `korTTY-macOS-<version>-x86_64.dmg`.

!!! warning "Rosetta ist ein nicht verifizierter fortgeschrittener Pfad"
    Ein Apple Silicon-Host kann versuchsweise ein x86_64 JDK 25 unter Rosetta ausführen, dies ist jedoch nicht der verifizierte Release-Pfad des Repositorys. Installieren Sie Rosetta, wählen Sie ein Intel JDK für `JAVA_HOME` und `PATH` aus, stoppen Sie vorhandene Gradle-Daemons, führen Sie den kompletten Build in einer x86_64-Shell aus und bestätigen Sie den Launcher mit `lipo`. Testen Sie das Ergebnis vor der Verteilung auf echter Intel-Hardware; Verwenden Sie `macos-15-intel`, wenn ein reproduzierbares Release-Artefakt erforderlich ist.

```bash
softwareupdate --install-rosetta --agree-to-license
arch -x86_64 /bin/zsh
export JAVA_HOME="/path/to/x86_64-jdk-25/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -XshowSettings:properties -version 2>&1 | grep 'os.arch = x86_64'
./gradlew --stop
./gradlew --no-daemon clean build
./gradlew --no-daemon jpackage
./gradlew --no-daemon jpackageDmg
lipo -archs build/jpackage/korTTY.app/Contents/MacOS/korTTY
```

### Signieren Sie die App und DMG

Beim Signieren handelt es sich um ein Opt-In. Die Gradle-Aufgaben verwenden die folgenden Repository-Eigenschaften, um gebündelte native JNA/pty4j-Bibliotheken zu signieren und die Identität an `jpackage` zu übergeben; Ersetzen Sie diesen Fluss nicht durch eine Ad-hoc-Signatur nur für das äußere App-Bundle.

```bash
security find-identity -v -p codesigning
export KORTTY_SIGNING_IDENTITY="Developer ID Application: Your Name (TEAMID)"
./gradlew clean build
./gradlew \
  -Pkortty.macos.sign=true \
  -Pkortty.macos.signingIdentity="$KORTTY_SIGNING_IDENTITY" \
  jpackage
./gradlew \
  -Pkortty.macos.sign=true \
  -Pkortty.macos.signingIdentity="$KORTTY_SIGNING_IDENTITY" \
  jpackageDmg
```

Wenn die Identität in einem nicht standardmäßigen Schlüsselbund gespeichert ist, fügen Sie `-Pkortty.macos.signingKeychain=/absolute/path/to/keychain-db` zu beiden Paketierungsbefehlen hinzu. Unterzeichnung und Beglaubigung sind separate Vorgänge: Die Gradle-Eigenschaften unterzeichnen die Artefakte, übermitteln sie jedoch nicht an den Notardienst von Apple.

### Notariell beglaubigen und vor Ort heften

Der Release-Workflow ist die kanonische automatisierte Implementierung. Um die Beglaubigungsphase lokal zu spiegeln, speichern Sie die App Store Connect API-Anmeldeinformationen in einem Schlüsselbundprofil, übermitteln Sie eine ZIP-Datei des signierten `.app`, heften Sie das akzeptierte Ticket zusammen und übermitteln und heften Sie dann das signierte DMG.

```bash
export APPLE_API_KEY_ID="<key-id>"
export APPLE_ISSUER_ID="<issuer-id>"
export APPLE_API_KEY_PATH="/absolute/path/to/AuthKey_<key-id>.p8"

xcrun notarytool store-credentials "kortty-notary" \
  --key "$APPLE_API_KEY_PATH" \
  --key-id "$APPLE_API_KEY_ID" \
  --issuer "$APPLE_ISSUER_ID"

ditto -c -k --keepParent build/jpackage/korTTY.app build/jpackage/korTTY-notarization.zip
xcrun notarytool submit build/jpackage/korTTY-notarization.zip --keychain-profile "kortty-notary" --wait
xcrun stapler staple build/jpackage/korTTY.app
xcrun stapler validate build/jpackage/korTTY.app

DMG="$(find build/jpackage -maxdepth 1 -name 'korTTY-*.dmg' -print -quit)"
test -n "$DMG"
xcrun notarytool submit "$DMG" --keychain-profile "kortty-notary" --wait
xcrun stapler staple "$DMG"
xcrun stapler validate "$DMG"

ditto -c -k --keepParent build/jpackage/korTTY.app "build/jpackage/korTTY-macOS-local-$(uname -m).zip"
```

Überprüfen Sie jede abgelehnte Einreichung mit `xcrun notarytool log <submission-id> --keychain-profile "kortty-notary"`. Überprüfen Sie vor der Veröffentlichung Gatekeeper, die eingebetteten Signaturen, die gehefteten Tickets und die Launcher-Architektur:

```bash
codesign --verify --deep --strict --verbose=2 build/jpackage/korTTY.app
spctl --assess --type execute --verbose=4 build/jpackage/korTTY.app
xcrun stapler validate build/jpackage/korTTY.app
xcrun stapler validate build/jpackage/korTTY-*.dmg
lipo -archs build/jpackage/korTTY.app/Contents/MacOS/korTTY
```

### macOS-Fehlerbehebung

- `jpackage` meldet einen Signaturidentitätsfehler: Führen Sie `security find-identity -v -p codesigning` aus, kopieren Sie die vollständige Entwickler-ID-Anwendungsidentität und bestätigen Sie, dass der ausgewählte Schlüsselbund entsperrt ist.
- `java`, `jpackage`, `uname` oder der generierte Launcher melden unterschiedliche Architekturen: Installieren Sie ein passendes JDK, aktualisieren Sie `JAVA_HOME` und `PATH`, führen Sie `./gradlew --stop` aus und erstellen Sie dann von `clean` neu.
- Notarisierung schlägt fehl: Lesen Sie das Übermittlungsprotokoll, anstatt einzelne JavaFX-Bibliotheken erneut zu signieren. Das Repository behält bewusst seine Upstream-Signaturen bei, während es die nativen Bibliotheken, die von seiner Gradle-Aufgabe abgedeckt werden, separat signiert.
- Die gepackte App kann einen LAN-Host erreichen, `./gradlew run` jedoch nicht: `.app` starten, damit macOS dem Anwendungspaket die Berechtigung für ein lokales Netzwerk erteilen kann.

### Weiterführende Literatur für macOS

- [Voraussetzungen für die Oracle-JPackage-Verpackung](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html#GUID-786E15C0-2A0C-4B2D-9739-A04F98642C23)
- [Apple: Signieren von Mac-Software mit der Entwickler-ID](https://developer.apple.com/developer-id/)
- [Apple: Beglaubigung der macOS-Software vor der Verteilung](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
- [Apple: Anpassen des Beurkundungsworkflows](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow)
- [GitHub-gehostete Runner-Referenz](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)

## Linux

### Zusätzliche Software

Für die App-Image-Aufgabe sind die allgemeinen Voraussetzungen erforderlich. Für die DEB-Erstellung ist `fakeroot` erforderlich; Für die RPM-Erstellung sind die RPM-Build-Tools erforderlich. `zip` wird nur benötigt, wenn Sie zusätzlich zum TAR.GZ-Archiv das portable ZIP benötigen.

=== "Debian / Ubuntu"
    ```bash
    sudo apt update
    sudo apt install git maven curl fakeroot zip
    ```

=== "Fedora / RHEL"
    ```bash
    sudo dnf install git maven curl rpm-build zip
    ```

Installieren und wählen Sie das vollständige JDK 25 separat aus, wenn der Verteilungsbefehl nicht genau dieses JDK bereitstellt. Erstellen Sie DEB-Pakete auf Debian/Ubuntu und RPM-Pakete auf einer RPM-basierten Distribution; Das tragbare App-Image ist für beide Familien verfügbar.

### Erstellen Sie das App-Image, tragbare Archive und Pakete

```bash
./gradlew clean build
./gradlew jpackage
tar -C build/jpackage -czf "build/jpackage/korTTY-Linux-local-$(uname -m).tar.gz" korTTY
(cd build/jpackage && zip -r "korTTY-Linux-local-$(uname -m).zip" korTTY)
```

Erstellen Sie dann das native Paket für die aktuelle Distribution:

=== "Debian / Ubuntu"
    ```bash
    ./gradlew jpackageDeb
    ```

=== "Fedora / RHEL"
    ```bash
    ./gradlew jpackageRpm
    ```

| Artefakt | Ausgabe |
| --- | --- |
| Eigenständiges App-Image | `build/jpackage/korTTY/` |
| Tragbare Archive | `build/jpackage/korTTY-Linux-local-<architecture>.tar.gz` und `.zip` |
| Debian-Paket | `build/jpackage/kortty*.deb` |
| RPM-Paket | `build/jpackage/kortty*.rpm` |

Führen Sie die Ausgaben aus und überprüfen Sie sie, ohne die Pakete zu installieren:

```bash
test -x build/jpackage/korTTY/bin/korTTY
file build/jpackage/korTTY/bin/korTTY
./build/jpackage/korTTY/bin/korTTY
tar -tzf build/jpackage/korTTY-Linux-local-*.tar.gz | head
dpkg-deb --info build/jpackage/kortty*.deb
rpm -qpi build/jpackage/kortty*.rpm
```

Verwenden Sie nur den Inspektionsbefehl, der für das von Ihnen erstellte Paket verfügbar ist. Testen Sie die Installation und Entfernung des Installationsprogramms in einer verfügbaren virtuellen Maschine oder einem Container, der der Zielverteilung entspricht, bevor Sie es veröffentlichen.

### Linux-Fehlerbehebung

- `jpackageDeb` schlägt fehl, weil `fakeroot` fehlt: Installieren Sie `fakeroot` und führen Sie es erneut aus einem sauberen Build-Verzeichnis aus.
- `jpackageRpm` kann `rpmbuild` nicht finden: Installieren Sie `rpm-build` auf RPM-Distributionen oder `rpm` auf Debian/Ubuntu und bestätigen Sie dann `command -v rpmbuild`.
- Die `jpackage`-Aufgabe ist erfolgreich, aber es wird kein `.AppImage` angezeigt: Dies wird erwartet; Überprüfen Sie `build/jpackage/korTTY/`, das eigenständige jpackage-App-Image-Verzeichnis.
- `jpackage` meldet, dass das Ziel bereits vorhanden ist: Führen Sie `./gradlew clean` aus, bevor Sie das App-Image neu erstellen.

### Weiterführende Literatur für Linux

- [Voraussetzungen für die Oracle-JPackage-Verpackung](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html#GUID-786E15C0-2A0C-4B2D-9739-A04F98642C23)
- [Debian-Anleitung: Pakete prüfen mit dpkg](https://www.debian.org/doc/manuals/debian-handbook/sect.manipulating-packages-with-dpkg.en.html)
- [Debian Fakeroot-Anleitung](https://manpages.debian.org/fakeroot/fakeroot.1.en.html)
- [RPM-Dokumentation](https://rpm.org/docs/)

## Windows

### Zusätzliche Software

Verwenden Sie ein PowerShell-Terminal mit den allgemeinen Voraussetzungen. Für die portable App-Image-Aufgabe ist kein externer Installer-Builder erforderlich, für die MSI-Erstellung ist jedoch WiX Toolset 3 oder höher erforderlich, wobei `candle.exe` und `light.exe` in `PATH` verfügbar sind. Der Release-Workflow nutzt die WiX 3-Toolchain für seine Windows-Paketierungsaufgaben.

```powershell
Get-Command java, javac, jpackage, git, mvn, curl
Get-Command candle.exe, light.exe
```

Starten Sie PowerShell neu, nachdem Sie `JAVA_HOME`, `PATH` oder die WiX-Installation geändert haben, damit der Wrapper und `jpackage` die neuen Werte sehen.

### Erstellen Sie das App-Image, portable ZIP und MSI

```powershell
.\gradlew.bat clean build
.\gradlew.bat jpackage
Compress-Archive -Path .\build\jpackage\korTTY -DestinationPath .\build\jpackage\korTTY-Windows-local.zip -Force
.\gradlew.bat jpackageMsi
```

| Artefakt | Ausgabe |
| --- | --- |
| Eigenständiges App-Image | `build\jpackage\korTTY\` |
| Windows-Launcher im App-Image | `build\jpackage\korTTY\korTTY.exe` |
| Tragbares Archiv | `build\jpackage\korTTY-Windows-local.zip` |
| Installer | `build\jpackage\korTTY-<version>.msi` |

Die ZIP-Datei ist portierbar, da sie das vollständige `korTTY`-Verzeichnis enthält. `korTTY.exe` ist ein Launcher in diesem Verzeichnis, keine eigenständige Binärdatei; Extrahieren Sie das gesamte Verzeichnis, bevor Sie es ausführen.

```powershell
Test-Path .\build\jpackage\korTTY\korTTY.exe
& .\build\jpackage\korTTY\korTTY.exe
Get-ChildItem .\build\jpackage\*.msi
Get-Item .\build\jpackage\korTTY-Windows-local.zip
```

Testen Sie die Installation, den Start, das Upgrade und die Entfernung von MSI in einer verfügbaren virtuellen Windows-Maschine, die der Zielarchitektur entspricht, bevor Sie sie veröffentlichen.

### Windows-Fehlerbehebung

- `jpackageMsi` meldet, dass WiX nicht gefunden werden kann: Bestätigen Sie, dass `Get-Command candle.exe, light.exe` in derselben PowerShell-Sitzung erfolgreich ist, und führen Sie dann `clean` und `jpackageMsi` erneut aus.
- `jpackage` fehlt, obwohl `java` funktioniert: Eine JRE oder ein unvollständiges JDK steht zuerst in `PATH`; Zeigen Sie `JAVA_HOME` und `PATH` auf das vollständige JDK 25 und führen Sie `.\gradlew.bat --stop` aus.
- Maven kann SithTermFX nicht erstellen: Bestätigen Sie, dass `mvn --version` JDK 25 meldet und dass `mvn.cmd` in `PATH` verfügbar ist.
- Das App-Image-Ziel existiert bereits: Führen Sie `.\gradlew.bat clean` aus, bevor Sie es neu erstellen.

### Weiterführende Literatur für Windows

- [Voraussetzungen für die Oracle-JPackage-Verpackung](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html#GUID-786E15C0-2A0C-4B2D-9739-A04F98642C23)
- [WiX Toolset-Dokumentation](https://wixtoolset.org/docs/)
- [WiX Toolset 3 veröffentlicht](https://github.com/wixtoolset/wix3/releases)
- [Microsoft PowerShell: Compress-Archive](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.archive/compress-archive)

## Allgemeine Fehlerbehebung und weiterführende Literatur

- Eine Paketierungsaufgabe für ein anderes Betriebssystem fehlt: Dies wird erwartet, da `build.gradle.kts` `jpackageDmg`, `jpackageMsi`, `jpackageDeb` und `jpackageRpm` nur auf den entsprechenden Hostplattformen registriert.
- Ein sauberer Build kann keine Abhängigkeiten auflösen oder ein angeheftetes Asset herunterladen: Bestätigen Sie den ausgehenden HTTPS-Zugriff und die Proxy-Konfiguration für GitHub, Maven-Repositorys, Gradle-Dienste, Node.js und die npm-Registrierung und führen Sie dann die fehlgeschlagene Gradle-Aufgabe erneut aus.
- Gradle verwendet nach den Änderungen von `JAVA_HOME` eine andere Java-Laufzeitumgebung: Stoppen Sie den Daemon mit `./gradlew --stop` oder `.\gradlew.bat --stop`, überprüfen Sie `./gradlew --version` und erstellen Sie von `clean` aus neu.
- A-Paket hat die falsche Architektur: Vergleichen Sie die Betriebssystemarchitektur mit der `os.arch`-Zeile von `java -XshowSettings:properties -version`; Installieren Sie ein natives JDK 25, das zum Ziel passt, und erstellen Sie es neu, anstatt die Ausgabe umzubenennen.

Offizielle Referenzen:

- [Oracle Packaging Tool-Anleitung für JDK 25](https://docs.oracle.com/en/java/javase/25/jpackage/)
- [Oracle jpackage-Befehlsreferenz](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html)
- [Gradle Wrapper-Anleitung](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- [Apache Maven-Installationsanleitung](https://maven.apache.org/install.html)
- [Git-Installationsanleitung](https://git-scm.com/book/en/v2/Getting-Started-Installing-Git)
- [Eclipse Temurin JDK-Downloads](https://adoptium.net/temurin/releases/?version=25)

[Zurück zur Installation →](installation.md){ .md-button }
