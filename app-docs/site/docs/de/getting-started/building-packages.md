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
| Flatpak-Bundle | `jpackage`, dann `flatpak-builder` und `flatpak build-bundle` | `build/jpackage/kortty-Linux-<version>-<architecture>.flatpak` | Nein; Installieren Sie die Freedesktop-Laufzeitumgebung über Flatpak |

!!! important "Ein App-Image ist keine einzelne ausführbare Datei"
    Unter macOS ist das App-Image das vollständige `korTTY.app`-Bundle, unter Windows das vollständige `korTTY`-Verzeichnis mit `korTTY.exe` und unter Linux das vollständige `korTTY`-Verzeichnis mit `bin/korTTY`. Durch das Verschieben nur des Launchers wird die Anwendung unterbrochen, da ihre Laufzeit und Bibliotheken in Geschwisterverzeichnissen verbleiben. Das Linux-Verzeichnis ist keine `.AppImage`-Datei.

## Allgemeine Voraussetzungen

Installieren Sie diese Tools, bevor Sie auf einem Betriebssystem aufbauen:

| Anforderung | Warum korTTY es braucht |
| --- | --- |
| Ein vollständiges JDK, das vom angehefteten Gradle-Wrapper unterstützt wird. | Startet Gradle. korTTY löst dann eine passende Temurin JDK 25-Toolchain für die Kompilierung auf, `jlink` und `jpackage` |
| Git | Klont korTTY und die angeheftete SithTermFX-Quellenabhängigkeit |
| Apache Maven | Erstellt SithTermFX vor der korTTY-Kompilierung im lokalen Maven-Repository |
| `curl` | Lädt die angehefteten mosh4j-Artefakte herunter, die von gepackten Builds verwendet werden |
| Ausgehender HTTPS-Zugriff | Der Wrapper, Maven/Gradle-Abhängigkeiten, SithTermFX, SHA-256-pinned mosh4j, die Monaco-Build-Toolchain, Formatter-Browser-Bundles und Chat-Render-Ressourcen werden während eines sauberen ersten Builds heruntergeladen |
| Gradle Wrapper aus diesem Repository | `gradlew` und `gradlew.bat` laden die im Repository angeheftete Gradle-Version herunter und führen sie aus; Installieren oder ersetzen Sie kein System Gradle |

Node.js und MkDocs sind keine allgemeinen Voraussetzungen für einen Anwendungs-Build: Gradle lädt eine angeheftete Node.js-Laufzeit nur zum Kompilieren von Monaco in ein isoliertes Build-Verzeichnis herunter, niemals in das Anwendungs-Image, und der normale Anwendungs-Build verwendet die festgeschriebenen Offline-Guide-Ressourcen. Prettier Standalone und SQL-Formatter werden als kompakte Browser-Bundles kopiert und offline in JavaFX WebView ohne Node ausgeführt.

!!! important "Die Verpackung ist an die JDK 25-Toolchain gebunden"
    Die gepackte Laufzeit enthält explizit `jdk.jsobject`, das JavaFX WebView benötigt und das in JDK 26 entfernt wurde. Die Entfernung wird als OpenJDK-Problem `JDK-8362628`: <https://bugs.openjdk.org/browse/JDK-8362628> verfolgt. Jede Paketierungsaufgabe löst `jpackage` aus derselben von Gradle ausgewählten Temurin JDK 25-Toolchain wie bei der Kompilierung auf, sodass ein neueres System `java` oder `jpackage` nicht stillschweigend eine inkompatible Laufzeit erstellen kann. Die ausgewählte Toolchain muss weiterhin mit der Ziel-CPU übereinstimmen, da `jpackage` keine Cross-Compilierung durchführt.

Klonen Sie die Quelle einmal:

```bash
git clone https://github.com/chardonnay/korTTY.git
cd korTTY
```

Überprüfen Sie die Host-Tools und prüfen Sie die JDKs, die Gradle vor dem ersten Build auswählen kann. Das System-JDK ist möglicherweise neuer als 25, aber `./gradlew javaToolchains` muss nach der Bereitstellung ein passendes JDK 25 für die Ziel-CPU auflisten; Maven muss ein JDK verwenden, das SithTermFX erstellen kann.

=== "macOS / Linux"
    ```bash
    command -v java git mvn curl
    java -XshowSettings:properties -version 2>&1 | grep -E 'java.specification.version|os.arch'
    git --version
    mvn --version
    curl --version
    ./gradlew --version
    ./gradlew -q javaToolchains
    ```

=== "Windows PowerShell"
    ```powershell
    Get-Command java, git, mvn, curl
    java -XshowSettings:properties -version 2>&1 | Select-String 'java.specification.version|os.arch'
    git --version
    mvn --version
    curl --version
    .\gradlew.bat --version
    .\gradlew.bat -q javaToolchains
    ```

Der erste Wrapper-Aufruf lädt Gradle herunter. Ein sauberer Anwendungsbuild klont bei Bedarf auch SithTermFX, installiert es über Maven, lädt die angehefteten Build-Eingaben herunter, kompiliert korTTY und führt die Tests aus.

### Sauberes, zielspezifisches Paket-Staging

`prepareJpackage` setzt `build/jpackage-input/libs` mit einem letzten Gradle `Sync` zusammen. Es entfernt veraltete Abhängigkeitsversionen und Formatierungsbäume, behält nur die aktuelle mosh4j-Architektur und Protobuf bei, verwendet das übergeordnet geladene Bouncy Castle der Anwendung wieder, schließt die Testabhängigkeit von SithTermFX aus und ersetzt JNA/pty4j durch JARs, die alle Java-Klassen, Dienste, Manifeste und Lizenzen, aber nur den nativen Zielpfad und die binäre Architektur beibehalten. Die Verifizierungsaufgabe setzt absichtlich veraltete Dateien vor der Synchronisierung und führt einen echten JNA/PTY-Smoke gegen die schlanken JARs durch:

```bash
./gradlew verifyJpackageStaging slimNativeRuntimeSmoke
```

### Berichte und Budgets in Paketgröße

Der plattformunabhängige Größenreporter trennt das App-Image, die Laufzeit, Formatierer-/Mosh-Nutzlasten, Abhängigkeits-JARs und komprimierte Anwendungs-JAR-Ressourcen und schreibt dann JSON und Markdown. Es versteht die von `jpackage` erstellten macOS-Layouts `Contents/app` und `Contents/runtime`, Windows `app` und `runtime` sowie Linux `lib/app` und `lib/runtime`. CI vergleicht native Installationsprogramme mit der festgeschriebenen Release-Baseline, erfordert eine Reduzierung um mindestens 15 %, wendet die 190-MiB-App-Image- und 150-MiB-DMG-Grenzwerte an und verwendet eine Regressionstoleranz von 2 %, sobald eine neue Plattformgröße überprüft wurde:

```bash
DMG="$(find build/jpackage -maxdepth 1 -name 'korTTY-*.dmg' -print -quit)"
python3 scripts/package-size-report.py \
  --app-image build/jpackage/korTTY.app \
  --artifact "macos-aarch64-dmg=$DMG" \
  --baselines package/size-baselines.json \
  --min-app-bytes 104857600 \
  --max-app-bytes 199229440 \
  --fail-on-budget \
  --output-json build/package-size/local.json \
  --output-markdown build/package-size/local.md
```

Bevor ein Windows-Archiv erstellt wird, löst der Release-Workflow das generierte App-Image auf und erfordert eine Anwendungs-JAR, `jvm.dll`, mindestens 100 MiB plausiblen Inhalts und x86_64 PE-Header für Launcher und JVM. Die Linux-RPM-Größendurchsetzung wird nach `rpmsign` ausgeführt, sodass die gemessene Datei diejenige ist, die verteilt wird.

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

Jedes korTTY-Release-Format trägt den kanonischen MIT `LICENSE` des Repositorys. Die RPM- und Pacman-Metadaten deklarieren außerdem `MIT`; `scripts/check-release-licenses.py` ist das CI-Gate, das verhindert, dass Metadaten von Anwendungspaketen in eine andere Lizenz wandern. Lizenzen, die zum Gradle-Wrapper, gebündelten Laufzeiten, Modellen oder anderen Abhängigkeiten gehören, bleiben separat und werden nicht als korTTY-Lizenz umgeschrieben.

## Erstellen Sie Pacman-Pakete

Regelmäßige Veröffentlichungen veröffentlichen zwei Pacman-Pakete: `x86_64` für Arch Linux und `aarch64` für Arch Linux ARM. Die Anwendungsbilder werden zunächst nativ auf den passenden Ubuntu-Läufern erstellt. Beide Pacman-Jobs führen dann `makepkg` im offiziellen Arch Linux `x86_64`-Container aus, da PKGBUILD nur das vorgefertigte Image kopiert und keinen Zielcode kompiliert oder ausführt.

Der AArch64-Job liefert einen separaten `makepkg.conf` mit `CARCH=aarch64` und `CHOST=aarch64-unknown-linux-gnu`. Ein CI-Guard lehnt die AArch64-Verpackung ab, wenn `prepare()`, `build()` oder `check()` jemals zum PKGBUILD hinzugefügt wird; Für eine solche Änderung ist ein nativer Arch Linux ARM-Builder erforderlich. Das Paket deaktiviert außerdem das Strippen und die Debug-Paketgenerierung, sodass `x86_64`-Tools niemals ein vorgefertigtes AArch64-ELF ändern.

Vor dem Signieren benötigt CI den genauen Paketdateinamen und überprüft `.PKGINFO`, die installierte MIT-Lizenz, den Desktop-Eintrag und den Launcher-Symlink. Außerdem wird `readelf` verwendet, um zu überprüfen, ob der native Launcher, der gebündelte Java-Launcher und die gebündelte JVM alle mit der angekündigten Architektur übereinstimmen. Die abgetrennte Signatur wird dann lokal überprüft, bevor das Paket zu einem Aktionsartefakt wird.

Für einen kontrollierten Pacman-Backfill senden Sie **Build Release Binaries** mit dem Geltungsbereich `pacman-only`, der numerischen Version, dem neuen `pacman_pkgrel` und dem vollständigen Commit, das hinter dem Quell-Tag erwartet wird. Verwenden Sie zuerst `release_tag=ci-only` und überprüfen Sie beide architekturspezifischen Aktionsartefakte. Wiederholen Sie den Vorgang mit dem echten `v<version>`-Freigabeetikett erst nach der Inspektion. Der Veröffentlichungsjob lehnt unveränderliche Veröffentlichungen und Dateinamenkollisionen ab, lädt ohne Überlastung hoch, vergleicht jeden Remote-GitHub-Digest mit dem lokalen SHA-256-Wert und löscht niemals ein vorhandenes oder teilweise hochgeladenes Asset automatisch.

## Erstellen Sie ein Flatpak-Bundle

Flatpak-Bundles werden nativ unter Linux für `x86_64` und `aarch64` erstellt. Das Manifest verwendet die Anwendungs-ID `io.github.chardonnay.korTTY` mit der Freedesktop 25.08-Laufzeit und verpackt das eigenständige Linux-Anwendungsimage, das von `jpackage` erstellt wurde. Installieren Sie Flatpak, `flatpak-builder` und `appstreamcli` und führen Sie dann Folgendes aus:

```bash
./gradlew clean build jpackage
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
flatpak install --user --noninteractive flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
flatpak-builder --user --force-clean --disable-rofiles-fuse --repo=build/flatpak-repo --default-branch=stable build/flatpak-build package/flatpak/io.github.chardonnay.korTTY.yml
flatpak build-bundle --arch="$(flatpak --default-arch)" build/flatpak-repo "build/jpackage/kortty-Linux-<version>-$(flatpak --default-arch).flatpak" io.github.chardonnay.korTTY stable
```

Das Paket gewährt Netzwerk-, X11-, Audio-, GPU- und Host-Dateisystemzugriff, da ein SSH-Terminal-Client Remote-Hosts erreichen, JavaFX/WebView rendern, Terminalmedien verwenden und explizit ausgewählte Hostdateien bearbeiten muss. Es gewährt auch Zugriff auf `org.freedesktop.Flatpak`; Lokale Shells und ihre AI-Agent-Befehle werden über `flatpak-spawn --host` auf dem Host gestartet. Validieren Sie ein lokales Bundle, indem Sie es installieren, seine Architektur überprüfen und den gepackten WebView Smoke ausführen:

```bash
flatpak install --user ./build/jpackage/kortty-Linux-<version>-<architecture>.flatpak
flatpak info --user --show-ref io.github.chardonnay.korTTY
xvfb-run -a flatpak run io.github.chardonnay.korTTY --webview-jit-smoke
```

GitHub Actions führt diese Sequenz auf nativen `ubuntu-latest`- und `ubuntu-24.04-arm`-Läufern aus, signiert beide Bundles mit dem Linux-Release-Schlüssel und lädt sie neben den Distributionspaketen hoch. Ein manueller `flatpak-only`-Versand akzeptiert einen festen Quell-Commit und ein separates vorhandenes Release-Tag, sodass ein Bundle aufgefüllt werden kann, ohne das Tag zu verschieben oder ein anderes Asset zu ersetzen.

## Erstellen Sie ein separates llama.cpp-Laufzeitpaket

Die integrierte lokale KI verwendet ein natives Artefakt, das absichtlich von jedem `jpackage`-App-Image und -Installationsprogramm getrennt ist. Das Upstream-Tag, das vollständige Commit und das Quellarchiv SHA-256 sind in `gradle/llama-cpp-pins.properties` angeheftet – der einzelnen Datei, die ein Runtime-Bump neu schreibt, zusammen mit einer Zeile pro ausgeliefertem Pin –, während `build.gradle.kts` die korTTY-Laufzeitrevision und die API-Vertragsversion beibehält. `verifyLlamaCppPin` schlägt fehl, wenn diese Werte nicht übereinstimmen, das aktive Tag keine Pin-Zeile hat oder das angeforderte Backend ungültig ist.

Installieren Sie CMake und eine native C/C++-Toolchain. Ein Vulkan-Paket benötigt zusätzlich die Vulkan-Header, den Loader und den Shader-Compiler; Metal ist nur auf macOS mit den Apple-Entwicklungstools verfügbar. Erstellen Sie dann das Paket für die aktuelle Maschine:

=== "CPU"
    ```bash
    ./gradlew generateLlamaRuntimeManifest -Pllama.backend=CPU
    ```

=== "macOS Metal"
    ```bash
    ./gradlew generateLlamaRuntimeManifest -Pllama.backend=METAL
    ```

=== "Windows/Linux Vulkan"
    ```bash
    ./gradlew generateLlamaRuntimeManifest -Pllama.backend=VULKAN
    ```

Die Task-Kette lädt nur das angeheftete Commit-Archiv herunter, überprüft dessen SHA-256, extrahiert es, konfiguriert einen Release-Build mit deaktiviertem Curl, RPC, Tests, Beispielen, Tools und Server-Web-UI, erstellt `llama-server`, stellt die erforderlichen Bibliotheken und Lizenzen bereit und erstellt einen reproduzierbaren ZIP- und JSON-Deskriptor unter `build/llama-runtime/packages/`. Der Deskriptor zeichnet die unveränderliche Laufzeit-ID, das Tag/Commit, die API-Vertragsversion, die korTTY-Mindestversion, die Plattform, die Architektur, das konkrete Backend, die Paketgröße/SHA-256, den Platzhalter für die Veröffentlichungs-URL, den Einstiegspunkt und das Widerrufsflag auf.

!!! important "Ein Build-Host kann nicht die gesamte Matrix erstellen"
    Native llama.cpp-Artefakte folgen der gleichen Zielplattformregel wie `jpackage`: Erstellen Sie jede Kombination aus Betriebssystem, Architektur und Backend auf einem passenden Host. Der Laufzeitworkflow umfasst macOS arm64/x86_64 CPU+Metal, Windows x86_64 CPU+Vulkan, Linux x86_64/arm64 CPU+Vulkan. CUDA ist nicht in der ersten Matrix.

### Kandidatenerkennung, Vertragstests und Beförderung

`.github/workflows/llama-runtime.yml` überprüft wöchentlich Upstream-Tags; Es wird immer nur das neueste Tag ausgewählt, eine engere Kadenz würde also nur zu einer Anhäufung ungeprüfter Kandidaten führen. Eine Änderung öffnet einen Kandidaten-PR, der den Quell-Pin aktualisiert, ihn aber nicht veröffentlicht. Ein `scope`-Job passt dann die Größe des Builds pro Pull-Anfrage an: Ein PR, der `gradle/llama-cpp-pins.properties`, den Workflow selbst oder die Lama/Runtime-Update-Java-Quellen berührt, führt die vollständige native Matrix mit zehn Beinen aus, während eine reine `build.gradle.kts`-Änderung ein einzelnes Linux x86_64-CPU-Rauchbein ausführt – aus diesem Grund befindet sich der Pin in einer eigenen Datei, und ein manueller `workflow_dispatch`-Build ist der Ausweg, der immer die vollständige Matrix erzwingt. Jedes erstellte Artefakt muss starten und seine Version melden, der Linux-CPU-Referenzjob überprüft außerdem nicht authentifizierte Ablehnung, authentifizierte Modellauflistung, Abschluss, Schlaf/Wach, JSON-Schema-Chat-Vervollständigung, Einbettungen und zwei gleichzeitig verwendbare Sidecars, und ein angehefteter Qdrant 1.18.2-Dienst führt den echten Vektorspeichervertrag aus. Das korTTY-Repository verfügt nur über Kandidatenerkennung und quellseitige Matrixvalidierung: Es verfügt über keine Stable-Promotion-Aktion, kein Repository-übergreifendes Freigabetoken und keinen privaten Signaturschlüssel zur Laufzeit.

Die stabile Veröffentlichung gehört zum öffentlichen Repository [chardonnay/kortty-llama-runtimes](https://github.com/chardonnay/kortty-llama-runtimes). Sein expliziter, von Menschen gesteuerter Workflow akzeptiert das exakt überprüfte korTTY-Quell-Commit, baut die native Matrix neu auf, führt Authentifizierung, Chat/Vervollständigung, Einbettungen, JSON-Schema, Sleep/Wake und Parallel-Sidecar-Rauchtests für jedes Paket aus und führt den angehefteten Qdrant-Vertrag separat aus. Nach diesen Prüfungen gelangt es in die geschützte `llama-runtime-signing`-Umgebung, überprüft, ob `LLAMA_RUNTIME_ED25519_PRIVATE_KEY_PEM` mit dem veröffentlichten Vertrauensstamm übereinstimmt, überprüft und erweitert den kumulativen signierten Index und erstellt eine unveränderliche Nur-Laufzeit-Version mit dem bereichsbezogenen `github.token` dieses Repositorys; Es ist kein Repository-übergreifendes persönliches Zugriffstoken erforderlich. Eine bestehende Laufzeit-ID wird niemals überschrieben. Die reguläre Werbung ist auf einmal alle sieben Tage beschränkt, wobei ein geprüfter Sicherheits- oder Modellunterstützungsgrund diesen Rhythmus außer Kraft setzen kann. Das Anwendungsinstallationsprogramm bleibt daher unverändert, während eine kompatible native Laufzeit unabhängig hochgestuft oder zurückgezogen werden kann.

## Veröffentlichen Sie den Modell- und Eingabeaufforderungskatalog

Der überprüfte kanonische Katalog ist `ai-catalog/model-prompt-catalog-v1.json`. Es verwendet das strikte Schema v1 für Empfehlungen für lokale Modelle und Zuordnungen von Modellnamen zu Voreinstellungen. Anwendungscode und Prompt-Vertragstext sind nicht Teil dieser unabhängig veröffentlichten Nutzlast.

`.github/workflows/ai-catalog-release.yml` ist nur manuell, akzeptiert Sendungen nur von `main` und verfügt über keine Vorschau oder einen automatischen Promotion-Pfad. Für den Versand sind genau das überprüfte `catalogVersion` und ein Release-Repository erforderlich, standardmäßig `chardonnay/kortty-ai-catalog`. Vor dem Signieren wird überprüft, ob die positive Katalogsequenz unbedingt größer als die zuletzt veröffentlichte Sequenz ist, und es werden die autorisierenden Java-Schema-/Vertrauenskettentests ausgeführt. Die `ai-catalog-signing`-GitHub-Umgebung muss über erforderliche Prüfer verfügen, bevor Produktionsgeheimnisse konfiguriert werden. Darin prüft der Workflow, ob `KORTTY_AI_CATALOG_PUBLIC_KEY` mit dem privaten Signaturgeheimnis Ed25519 übereinstimmt, signiert die unveränderten JSON-Bytes, überprüft die Signatur und veröffentlicht unveränderliches `model-prompt-catalog-v1.json` plus `model-prompt-catalog-v1.sig`. Ein vorhandenes Versions-Tag oder Release wird abgelehnt. Konfigurieren Sie `AI_CATALOG_ED25519_PRIVATE_KEY_PEM` und `AI_CATALOG_RELEASE_TOKEN` nur als umgebungsbezogene Hochstufungsgeheimnisse.

### Betten Sie die lokalen KI-Vertrauenswurzeln ein

Jede Anwendungsversion, die Laufzeiten installieren oder den Katalog aktualisieren kann, muss beide Ed25519 **öffentlichen** Verifizierungsschlüssel einbetten. Der Vertrauensstamm des Laufzeitkanals ist öffentlich, überprüfbar und in `config/trust/llama-runtime-ed25519-public.pem` verankert, sodass normale lokale Builds denselben Schlüssel wie offizielle Pakete verwenden. Der Katalogvertrauensstamm wird separat bereitgestellt. Geben Sie niemals einen der signierenden privaten Schlüssel für einen Anwendungsbuild an.

| Kanal | Quellstandard | Umgebungsvariable | Gradle-Eigenschaft |
| --- | --- | --- | --- |
| llama.cpp-Laufzeit | `config/trust/llama-runtime-ed25519-public.pem` | Optional `KORTTY_LLAMA_RUNTIME_PUBLIC_KEY`; muss genau mit dem angepinnten Schlüssel übereinstimmen | Optional `kortty.llamaRuntimePublicKey`; muss genau mit dem angepinnten Schlüssel übereinstimmen |
| Modell-/Sofortkatalog | Keine | `KORTTY_AI_CATALOG_PUBLIC_KEY` | `kortty.aiCatalogPublicKey` |

Die generierten Ressourcen speichern die festen Kanal-URLs und öffentlichen Vertrauensstämme mit der Anwendung:

=== "macOS / Linux"
    ```bash
    export KORTTY_AI_CATALOG_PUBLIC_KEY="$(cat ai-catalog-signing-public.pem)"
    ./gradlew generateLlamaRuntimeReleaseConfig generateAiCatalogReleaseConfig build
    ```

=== "Windows PowerShell"
    ```powershell
    $env:KORTTY_AI_CATALOG_PUBLIC_KEY = Get-Content .\ai-catalog-signing-public.pem -Raw
    .\gradlew.bat generateLlamaRuntimeReleaseConfig generateAiCatalogReleaseConfig build
    ```

Der offizielle Anwendungsfreigabe-Workflow liest beide Namen aus GitHub Actions-Repository-Variablen, lehnt fehlende Werte oder privates Schlüsselmaterial ab und lässt Gradle die X.509 Ed25519-Schlüssel vor dem Verpacken validieren. Die Laufzeitvariable ist eine redundante CI-Identitätsprüfung und muss denselben öffentlichen Schlüssel wie das verfolgte PEM kodieren. Eine andere Überschreibung lässt den Build fehlschlagen, anstatt den Vertrauensstamm zu ersetzen. Wenn der angeheftete Laufzeitschlüssel oder die generierte Release-Konfiguration fehlt oder fehlerhaft ist, schlägt die Installation/Aktualisierung der Laufzeit vor dem Abrufen des Index fehl. Ohne den Katalogschlüssel stellt korTTY keine Kataloganfrage, ignoriert etwaige Caches und verwendet seinen integrierten Bootstrap.

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

Führen Sie `xcode-select --install` aus, wenn die Befehlszeilentools nicht vorhanden sind. Eine für andere Benutzer bestimmte Veröffentlichung erfordert außerdem ein Apple Developer-Konto, ein **Developer ID Application**-Zertifikat und App Wissensspeicher Connect API-Anmeldeinformationen für die Beglaubigung; Bei einem nicht signierten lokalen Build ist dies nicht der Fall.

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

`jpackageDmg` packt das von `jpackage` erzeugte App-Image – es erstellt kein zweites – führen Sie also die beiden in dieser Reihenfolge aus; Die Aufgabe schlägt mit einer expliziten Meldung fehl, wenn `build/jpackage/korTTY.app` fehlt. Bevor die DMG fertig ist, wird die darin enthaltene App gemountet und überprüft: Ihre Signatur muss überprüft werden, ihre Nutzlast muss mit dem App-Image auf der Festplatte übereinstimmen und ihre Signaturberechtigung muss dieselbe sein. jpackage signiert die Kopie, die es in die DMG einfügt, immer neu (es tauscht `Contents/app/.jpackage.xml` gegen einen `.package`-Marker aus, wodurch das Siegel gebrochen wird), und ohne Identität greift es auf eine Ad-hoc-Signatur zurück, die der Notar von Apple ablehnt – diese Prüfung erkennt diesen und jeden anderen Signaturfehler vor der Einreichung einer Beglaubigung und nicht erst nach Ablauf einer solchen.

Öffnen Sie das App-Image für einen lokalen Rauchtest und überprüfen Sie den DMG-Container:

```bash
open build/jpackage/korTTY.app
hdiutil verify build/jpackage/korTTY-*.dmg
./gradlew verifyMacDmgAppImage
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
| `APPLE_API_KEY_ID` | App Wissensspeicher Connect API-Schlüssel-ID |
| `APPLE_ISSUER_ID` | App Wissensspeicher Connect-Aussteller-ID |
| `APPLE_API_KEY` | Vollständiger Inhalt des privaten API-Schlüssels |

Öffnen Sie **Aktionen → Release-Binärdateien erstellen → Workflow ausführen**, wählen Sie den Zweig oder das Tag in `ref` aus und geben Sie die numerische Anwendungsversion ein. Ein Branch-Lauf lädt die macOS-Build-Artefakte zur Überprüfung hoch; Ein veröffentlichtes GitHub-Release oder eine manuelle Ausführung, deren `ref` ein Release-Tag ist, speist auch den endgültigen Release-Upload-Job. Die Intel-Artefakte heißen `korTTY-macOS-<version>-x86_64.zip` und `korTTY-macOS-<version>-x86_64.dmg`.

!!! warning "Rosetta ist ein nicht verifizierter fortgeschrittener Pfad"
    Ein Apple Silicon-Host kann versuchsweise ein x86_64 JDK 25 unter Rosetta ausführen, dies ist jedoch nicht der verifizierte Release-Pfad des Repositorys. Installieren Sie Rosetta, wählen Sie ein Intel JDK für `JAVA_HOME` und `PATH` aus, stoppen Sie vorhandene Gradle-Daemons, führen Sie den kompletten Build in einer x86_64-Shell aus und bestätigen Sie den Launcher mit `lipo`. Testen Sie das Ergebnis vor der Verteilung auf echter Intel-Hardware. Verwenden Sie `macos-15-intel`, wenn ein reproduzierbares Release-Artefakt erforderlich ist.

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

Der Release-Workflow ist die kanonische automatisierte Implementierung. Um die Beglaubigungsphase lokal zu spiegeln, speichern Sie die App Wissensspeicher Connect API-Anmeldeinformationen in einem Schlüsselbundprofil, übermitteln Sie eine ZIP-Datei des signierten `.app`, heften Sie das akzeptierte Ticket zusammen und übermitteln und heften Sie dann das signierte DMG.

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

Für die App-Image-Aufgabe sind die allgemeinen Voraussetzungen erforderlich. Für die DEB-Erstellung ist `fakeroot` erforderlich. Für die RPM-Erstellung sind die RPM-Build-Tools erforderlich. `zip` wird nur benötigt, wenn Sie zusätzlich zum TAR.GZ-Archiv das portable ZIP benötigen.

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

Der Release-Workflow veröffentlicht derzeit nur das validierte x86_64-Windows-Paket. OpenJFX stellt keine nativen Windows ARM-Artefakte für die angeheftete JavaFX-Zeile bereit, daher verwendet Windows-on-ARM dieses x86_64-Paket über die Windows-Emulation; Das früher falsch beschriftete ARM-Archiv wird nicht mehr produziert oder als Größenbasis verwendet. Native Windows ARM-Paketierung erfordert einen separaten JavaFX/JDK-Paketierungstrack und eine native WebView/PTY-Validierung.

```powershell
Test-Path .\build\jpackage\korTTY\korTTY.exe
& .\build\jpackage\korTTY\korTTY.exe
Get-ChildItem .\build\jpackage\*.msi
Get-Item .\build\jpackage\korTTY-Windows-local.zip
```

Testen Sie die Installation, den Start, das Upgrade und die Entfernung von MSI in einer verfügbaren virtuellen Windows-Maschine, die der Zielarchitektur entspricht, bevor Sie sie veröffentlichen.

### Windows-Fehlerbehebung

- `jpackageMsi` meldet, dass WiX nicht gefunden werden kann: Bestätigen Sie, dass `Get-Command candle.exe, light.exe` in derselben PowerShell-Sitzung erfolgreich ist, und führen Sie dann `clean` und `jpackageMsi` erneut aus.
- Gradle kann das Paket-JDK nicht auflösen: Führen Sie `.\gradlew.bat -q javaToolchains` aus, erlauben Sie Toolchain-Downloads oder installieren Sie ein natives JDK 25 und stoppen Sie dann vorhandene Daemons mit `.\gradlew.bat --stop`.
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
