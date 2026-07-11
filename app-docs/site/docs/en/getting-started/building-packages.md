# Building korTTY packages

korTTY can be built as Java artifacts, a self-contained portable application image or a native installer. Native packages must be built on the target operating system and processor architecture because `jpackage`, JavaFX and the bundled native tools select platform-specific files from the active build machine.

## Choose an artifact

| Goal | Gradle task | Local output | Java required on the destination? |
| --- | --- | --- | --- |
| Thin application JAR | `jar` | `build/libs/korTTY-<version>.jar` | Yes, plus every runtime dependency; this JAR is not a stand-alone executable JAR |
| Java distribution | `distZip` / `distTar` | `build/distributions/korTTY-<version>.zip` / `.tar` | Yes; the archive includes dependencies and launch scripts but no Java runtime |
| Self-contained app image | `jpackage` | Platform app bundle or directory under `build/jpackage/` | No; `jpackage` includes a trimmed Java runtime |
| Native installer | `jpackageDmg`, `jpackageMsi`, `jpackageDeb` or `jpackageRpm` | DMG, MSI, DEB or RPM under `build/jpackage/` | No; the installer contains the self-contained app image |
| Portable native archive | `jpackage`, then the OS archive command shown below | ZIP or TAR containing the entire app image | No; keep the bundle or directory intact after extraction |

!!! important "An app image is not one executable file"
    On macOS the app image is the complete `korTTY.app` bundle, on Windows it is the complete `korTTY` directory containing `korTTY.exe`, and on Linux it is the complete `korTTY` directory containing `bin/korTTY`. Moving only the launcher breaks the application because its runtime and libraries remain in sibling directories. The Linux directory is not a `.AppImage` file.

## Common prerequisites

Install these tools before building on any operating system:

| Requirement | Why korTTY needs it |
| --- | --- |
| Full **JDK 25** matching the target CPU | Compiles korTTY and provides `java`, `javac`, `jlink` and `jpackage`; put its `bin` directory in `PATH` and set `JAVA_HOME` to the same JDK |
| Git | Clones korTTY and the pinned SithTermFX source dependency |
| Apache Maven | Builds SithTermFX into the local Maven repository before korTTY compilation |
| `curl` | Downloads the pinned mosh4j artifacts used by packaged builds |
| Outbound HTTPS access | The wrapper, Maven/Gradle dependencies, SithTermFX, mosh4j, Node.js, formatters, Monaco and chat-render resources are downloaded during a clean first build |
| Gradle Wrapper from this repository | `gradlew` and `gradlew.bat` download and run the repository-pinned Gradle version; do not install or substitute a system Gradle |

Node.js and MkDocs are not common prerequisites for an application build: the Gradle tasks download a pinned Node.js runtime for generated assets, and the normal application build uses the committed offline guide resources.

!!! warning "Use exactly JDK 25"
    The packaged runtime explicitly includes `jdk.jsobject`, which JavaFX WebView needs and which was removed in JDK 26. The removal is tracked as OpenJDK issue `JDK-8362628`: <https://bugs.openjdk.org/browse/JDK-8362628>. Gradle can provision its compiler toolchain independently, but the packaging tasks invoke the bare `jpackage` command from `PATH`; a newer or older `jpackage` can therefore fail even when compilation selected JDK 25. Point both `JAVA_HOME` and the first `java`/`javac`/`jpackage` entries in `PATH` to the same JDK 25 installation.

Clone the source once:

```bash
git clone https://github.com/chardonnay/korTTY.git
cd korTTY
```

Verify that every command resolves before the first build. `java`, `javac` and `jpackage` must all report major version 25, and Maven must report the same `JAVA_HOME`.

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

The first wrapper invocation downloads Gradle. A clean application build also clones SithTermFX when necessary, installs it through Maven, downloads the pinned build inputs, compiles korTTY and runs the tests.

### Build the Java artifacts

Use the platform command below to compile, test and create the thin JAR plus the Java ZIP and TAR distributions:

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

The thin JAR contains korTTY classes and resources but not its runtime dependencies and does not provide the complete deployment by itself. For a Java-based portable layout, extract the generated ZIP or TAR and start `bin/korTTY` on macOS/Linux or `bin\korTTY.bat` on Windows; the destination still needs JDK 25. These distributions also contain the native JavaFX dependencies selected on the build host, so build them on the same OS and architecture on which they will run.

## macOS

### Additional software

Install the common prerequisites and Apple command-line tooling. The unsigned `.app` build uses the JDK tools; DMG creation uses `hdiutil` and the configured custom icon, while signing and notarization additionally use `codesign`, `security`, `notarytool` and `stapler`. Select a current Xcode installation before a release build.

```bash
xcode-select -p
xcrun --find codesign
xcrun --find notarytool
xcrun --find stapler
command -v hdiutil ditto
```

Run `xcode-select --install` if the command-line tools are absent. A release intended for other users also needs an Apple Developer account, a **Developer ID Application** certificate and App Store Connect API credentials for notarization; an unsigned local build does not.

### Build an unsigned app image, portable ZIP and DMG

```bash
./gradlew clean build
./gradlew jpackage
ditto -c -k --keepParent build/jpackage/korTTY.app "build/jpackage/korTTY-macOS-local-$(uname -m).zip"
./gradlew jpackageDmg
```

| Artifact | Output |
| --- | --- |
| Self-contained app image | `build/jpackage/korTTY.app` |
| Portable archive | `build/jpackage/korTTY-macOS-local-<architecture>.zip` |
| Disk image | `build/jpackage/korTTY-<version>.dmg` |

Open the app image for a local smoke test and verify the DMG container:

```bash
open build/jpackage/korTTY.app
hdiutil verify build/jpackage/korTTY-*.dmg
```

### Build macOS for Intel (`x86_64`)

The reliable paths are an Intel Mac with an x86_64 JDK 25 or the repository's GitHub Actions job on `macos-15-intel`. `jpackage` does not cross-compile, so an ARM JDK running natively on Apple Silicon creates an ARM app even if the desired filename says `x86_64`.

On an Intel Mac, validate the machine, Java runtime and generated launcher before distributing the result:

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

The final `lipo` command must print only `x86_64`. Perform the same build on Apple Silicon with an ARM JDK to obtain the separate `aarch64` artifacts; the project does not create a universal binary.

#### Intel build with GitHub Actions

The `Build Release Binaries` workflow contains separate macOS matrix entries for `macos-latest`/`aarch64` and `macos-15-intel`/`x86_64`. It installs Temurin JDK 25, rejects a JDK or runner architecture mismatch, builds both the signed app and DMG, verifies the launcher with `lipo`, notarizes both deliverables and uploads architecture-suffixed artifacts.

Configure these repository secrets before running the workflow:

| Secret | Content |
| --- | --- |
| `APPLE_SIGNING_CERT_P12_BASE64` | Base64-encoded Developer ID Application certificate and private key in P12 form |
| `APPLE_SIGNING_CERT_PASSWORD` | Password protecting the P12 file |
| `APPLE_SIGNING_IDENTITY` | Exact Developer ID Application identity shown by `security find-identity` |
| `APPLE_API_KEY_ID` | App Store Connect API key ID |
| `APPLE_ISSUER_ID` | App Store Connect issuer ID |
| `APPLE_API_KEY` | Complete private API key contents |

Open **Actions → Build Release Binaries → Run workflow**, choose the branch or tag in `ref`, and enter its numeric application version. A branch run uploads the macOS build artifacts for inspection; a published GitHub Release, or a manual run whose `ref` is a release tag, also feeds the final release-upload job. The Intel artifacts are named `korTTY-macOS-<version>-x86_64.zip` and `korTTY-macOS-<version>-x86_64.dmg`.

!!! warning "Rosetta is an unverified advanced path"
    An Apple Silicon host can experimentally run an x86_64 JDK 25 under Rosetta, but this is not the repository's verified release path. Install Rosetta, select an Intel JDK for both `JAVA_HOME` and `PATH`, stop existing Gradle daemons, run the complete build in an x86_64 shell and confirm the launcher with `lipo`. Test the result on real Intel hardware before distribution; use `macos-15-intel` when a reproducible release artifact is required.

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

### Sign the app and DMG

Signing is opt-in. The Gradle tasks use the repository properties below to sign bundled JNA/pty4j native libraries and pass the identity to `jpackage`; do not replace this flow with an ad-hoc signature over only the outer app bundle.

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

If the identity is stored in a non-default keychain, add `-Pkortty.macos.signingKeychain=/absolute/path/to/keychain-db` to both packaging commands. Signing and notarization are separate operations: the Gradle properties sign the artifacts but do not submit them to Apple's notary service.

### Notarize and staple locally

The release workflow is the canonical automated implementation. To mirror its notarization phase locally, store the App Store Connect API credentials in a keychain profile, submit a ZIP of the signed `.app`, staple the accepted ticket, then submit and staple the signed DMG.

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

Inspect any rejected submission with `xcrun notarytool log <submission-id> --keychain-profile "kortty-notary"`. Before publishing, verify Gatekeeper, the embedded signatures, the stapled tickets and the launcher architecture:

```bash
codesign --verify --deep --strict --verbose=2 build/jpackage/korTTY.app
spctl --assess --type execute --verbose=4 build/jpackage/korTTY.app
xcrun stapler validate build/jpackage/korTTY.app
xcrun stapler validate build/jpackage/korTTY-*.dmg
lipo -archs build/jpackage/korTTY.app/Contents/MacOS/korTTY
```

### macOS troubleshooting

- `jpackage` reports a signing identity error: run `security find-identity -v -p codesigning`, copy the complete Developer ID Application identity and confirm that the selected keychain is unlocked.
- `java`, `jpackage`, `uname` or the generated launcher report different architectures: install a matching JDK, update `JAVA_HOME` and `PATH`, run `./gradlew --stop`, then rebuild from `clean`.
- Notarization fails: read the submission log instead of re-signing individual JavaFX libraries; the repository deliberately preserves their upstream signatures while separately signing the native libraries covered by its Gradle task.
- The packaged app can reach a LAN host but `./gradlew run` cannot: launch the `.app` so macOS can grant Local Network permission to the application bundle.

### Further reading for macOS

- [Oracle jpackage packaging prerequisites](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html#GUID-786E15C0-2A0C-4B2D-9739-A04F98642C23)
- [Apple: Signing Mac software with Developer ID](https://developer.apple.com/developer-id/)
- [Apple: Notarizing macOS software before distribution](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
- [Apple: Customizing the notarization workflow](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow)
- [GitHub-hosted runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)

## Linux

### Additional software

The app-image task needs the common prerequisites. DEB creation needs `fakeroot`; RPM creation needs the RPM build tools; `zip` is needed only when you want the portable ZIP in addition to the TAR.GZ archive.

=== "Debian / Ubuntu"
    ```bash
    sudo apt update
    sudo apt install git maven curl fakeroot zip
    ```

=== "Fedora / RHEL"
    ```bash
    sudo dnf install git maven curl rpm-build zip
    ```

Install and select the full JDK 25 separately if the distribution command does not provide that exact JDK. Build DEB packages on Debian/Ubuntu and RPM packages on an RPM-based distribution; the portable app image is available on either family.

### Build the app image, portable archives and packages

```bash
./gradlew clean build
./gradlew jpackage
tar -C build/jpackage -czf "build/jpackage/korTTY-Linux-local-$(uname -m).tar.gz" korTTY
(cd build/jpackage && zip -r "korTTY-Linux-local-$(uname -m).zip" korTTY)
```

Then create the native package for the current distribution:

=== "Debian / Ubuntu"
    ```bash
    ./gradlew jpackageDeb
    ```

=== "Fedora / RHEL"
    ```bash
    ./gradlew jpackageRpm
    ```

| Artifact | Output |
| --- | --- |
| Self-contained app image | `build/jpackage/korTTY/` |
| Portable archives | `build/jpackage/korTTY-Linux-local-<architecture>.tar.gz` and `.zip` |
| Debian package | `build/jpackage/kortty*.deb` |
| RPM package | `build/jpackage/kortty*.rpm` |

Run and inspect the outputs without installing the packages:

```bash
test -x build/jpackage/korTTY/bin/korTTY
file build/jpackage/korTTY/bin/korTTY
./build/jpackage/korTTY/bin/korTTY
tar -tzf build/jpackage/korTTY-Linux-local-*.tar.gz | head
dpkg-deb --info build/jpackage/kortty*.deb
rpm -qpi build/jpackage/kortty*.rpm
```

Use only the inspection command available for the package you built. Test installer installation and removal in a disposable virtual machine or container matching the target distribution before publishing it.

### Linux troubleshooting

- `jpackageDeb` fails with a missing `fakeroot`: install `fakeroot` and rerun from a clean build directory.
- `jpackageRpm` cannot find `rpmbuild`: install `rpm-build` on RPM distributions or `rpm` on Debian/Ubuntu, then confirm `command -v rpmbuild`.
- The `jpackage` task succeeds but no `.AppImage` appears: this is expected; inspect `build/jpackage/korTTY/`, which is the self-contained jpackage app-image directory.
- `jpackage` reports that the destination already exists: run `./gradlew clean` before rebuilding the app image.

### Further reading for Linux

- [Oracle jpackage packaging prerequisites](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html#GUID-786E15C0-2A0C-4B2D-9739-A04F98642C23)
- [Debian Handbook: inspecting packages with dpkg](https://www.debian.org/doc/manuals/debian-handbook/sect.manipulating-packages-with-dpkg.en.html)
- [Debian fakeroot manual](https://manpages.debian.org/fakeroot/fakeroot.1.en.html)
- [RPM documentation](https://rpm.org/docs/)

## Windows

### Additional software

Use a PowerShell terminal with the common prerequisites. The portable app-image task needs no external installer builder, but MSI creation requires WiX Toolset 3 or later with `candle.exe` and `light.exe` available in `PATH`. The release workflow uses the WiX 3 toolchain for its Windows packaging jobs.

```powershell
Get-Command java, javac, jpackage, git, mvn, curl
Get-Command candle.exe, light.exe
```

Restart PowerShell after changing `JAVA_HOME`, `PATH` or the WiX installation so the wrapper and `jpackage` see the new values.

### Build the app image, portable ZIP and MSI

```powershell
.\gradlew.bat clean build
.\gradlew.bat jpackage
Compress-Archive -Path .\build\jpackage\korTTY -DestinationPath .\build\jpackage\korTTY-Windows-local.zip -Force
.\gradlew.bat jpackageMsi
```

| Artifact | Output |
| --- | --- |
| Self-contained app image | `build\jpackage\korTTY\` |
| Windows launcher inside the app image | `build\jpackage\korTTY\korTTY.exe` |
| Portable archive | `build\jpackage\korTTY-Windows-local.zip` |
| Installer | `build\jpackage\korTTY-<version>.msi` |

The ZIP is portable because it contains the complete `korTTY` directory. `korTTY.exe` is a launcher within that directory, not a stand-alone binary; extract the entire directory before running it.

```powershell
Test-Path .\build\jpackage\korTTY\korTTY.exe
& .\build\jpackage\korTTY\korTTY.exe
Get-ChildItem .\build\jpackage\*.msi
Get-Item .\build\jpackage\korTTY-Windows-local.zip
```

Test MSI installation, launch, upgrade and removal in a disposable Windows virtual machine matching the target architecture before publishing it.

### Windows troubleshooting

- `jpackageMsi` reports that WiX cannot be found: confirm that `Get-Command candle.exe, light.exe` succeeds in the same PowerShell session, then rerun `clean` and `jpackageMsi`.
- `jpackage` is missing even though `java` works: a JRE or incomplete JDK is first in `PATH`; point `JAVA_HOME` and `PATH` to the full JDK 25 and run `.\gradlew.bat --stop`.
- Maven cannot build SithTermFX: confirm that `mvn --version` reports JDK 25 and that `mvn.cmd` is available in `PATH`.
- The app-image destination already exists: run `.\gradlew.bat clean` before rebuilding.

### Further reading for Windows

- [Oracle jpackage packaging prerequisites](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html#GUID-786E15C0-2A0C-4B2D-9739-A04F98642C23)
- [WiX Toolset documentation](https://wixtoolset.org/docs/)
- [WiX Toolset 3 releases](https://github.com/wixtoolset/wix3/releases)
- [Microsoft PowerShell: Compress-Archive](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.archive/compress-archive)

## Common troubleshooting and further reading

- A packaging task for another operating system is absent: this is expected because `build.gradle.kts` registers `jpackageDmg`, `jpackageMsi`, `jpackageDeb` and `jpackageRpm` only on their matching host platforms.
- A clean build cannot resolve dependencies or download a pinned asset: confirm outbound HTTPS access and proxy configuration for GitHub, Maven repositories, Gradle services, Node.js and the npm registry, then rerun the failed Gradle task.
- Gradle uses a different Java runtime after `JAVA_HOME` changes: stop the daemon with `./gradlew --stop` or `.\gradlew.bat --stop`, verify `./gradlew --version`, and rebuild from `clean`.
- A package has the wrong architecture: compare the OS architecture with the `os.arch` line from `java -XshowSettings:properties -version`; install a native JDK 25 matching the target and rebuild rather than renaming the output.

Official references:

- [Oracle Packaging Tool User's Guide for JDK 25](https://docs.oracle.com/en/java/javase/25/jpackage/)
- [Oracle jpackage command reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html)
- [Gradle Wrapper user guide](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- [Apache Maven installation guide](https://maven.apache.org/install.html)
- [Git installation guide](https://git-scm.com/book/en/v2/Getting-Started-Installing-Git)
- [Eclipse Temurin JDK downloads](https://adoptium.net/temurin/releases/?version=25)

[Back to installation →](installation.md){ .md-button }
