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
| Flatpak bundle | `jpackage`, then `flatpak-builder` and `flatpak build-bundle` | `build/jpackage/kortty-Linux-<version>-<architecture>.flatpak` | No; install the Freedesktop runtime through Flatpak |

!!! important "An app image is not one executable file"
    On macOS the app image is the complete `korTTY.app` bundle, on Windows it is the complete `korTTY` directory containing `korTTY.exe`, and on Linux it is the complete `korTTY` directory containing `bin/korTTY`. Moving only the launcher breaks the application because its runtime and libraries remain in sibling directories. The Linux directory is not a `.AppImage` file.

## Common prerequisites

Install these tools before building on any operating system:

| Requirement | Why korTTY needs it |
| --- | --- |
| A full JDK supported by the pinned Gradle wrapper | Starts Gradle; korTTY then resolves a matching Temurin JDK 25 toolchain for compilation, `jlink` and `jpackage` |
| Git | Clones korTTY and the pinned SithTermFX source dependency |
| Apache Maven | Builds SithTermFX into the local Maven repository before korTTY compilation |
| `curl` | Downloads the pinned mosh4j artifacts used by packaged builds |
| Outbound HTTPS access | The wrapper, Maven/Gradle dependencies, SithTermFX, SHA-256-pinned mosh4j, the Monaco build toolchain, formatter browser bundles and chat-render resources are downloaded during a clean first build |
| Gradle Wrapper from this repository | `gradlew` and `gradlew.bat` download and run the repository-pinned Gradle version; do not install or substitute a system Gradle |

Node.js and MkDocs are not common prerequisites for an application build: Gradle downloads a pinned Node.js runtime into an isolated build directory only to compile Monaco, never into the application image, and the normal application build uses the committed offline guide resources. Prettier Standalone and sql-formatter are copied as compact browser bundles and execute offline in JavaFX WebView without Node.

!!! important "Packaging is pinned to the JDK 25 toolchain"
    The packaged runtime explicitly includes `jdk.jsobject`, which JavaFX WebView needs and which was removed in JDK 26. The removal is tracked as OpenJDK issue `JDK-8362628`: <https://bugs.openjdk.org/browse/JDK-8362628>. Every packaging task resolves `jpackage` from the same Gradle-selected Temurin JDK 25 toolchain as compilation, so a newer system `java` or `jpackage` cannot silently create an incompatible runtime. The selected toolchain must still match the target CPU because `jpackage` does not cross-compile.

Clone the source once:

```bash
git clone https://github.com/chardonnay/korTTY.git
cd korTTY
```

Verify the host tools and inspect the JDKs Gradle can select before the first build. The system JDK may be newer than 25, but `./gradlew javaToolchains` must list a matching JDK 25 for the target CPU after provisioning; Maven must use a JDK that can build SithTermFX.

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

The first wrapper invocation downloads Gradle. A clean application build also clones SithTermFX when necessary, installs it through Maven, downloads the pinned build inputs, compiles korTTY and runs the tests.

### Clean, target-specific package staging

`prepareJpackage` assembles `build/jpackage-input/libs` with one final Gradle `Sync`. It removes stale dependency versions and formatter trees, keeps only the current mosh4j architecture and protobuf, reuses the application's parent-loaded Bouncy Castle, excludes SithTermFX's test dependency, and replaces JNA/pty4j with JARs that retain all Java classes, services, manifests and licences but only the target native path and binary architecture. The verification task deliberately seeds obsolete files before Sync and runs a real JNA/PTY smoke against the slim JARs:

```bash
./gradlew verifyJpackageStaging slimNativeRuntimeSmoke
```

### Package-size reports and budgets

The platform-independent size reporter separates the app image, runtime, formatter/Mosh payloads, dependency JARs and compressed application-JAR resources, then writes JSON and Markdown. It understands the macOS `Contents/app` and `Contents/runtime`, Windows `app` and `runtime`, and Linux `lib/app` and `lib/runtime` layouts produced by `jpackage`. CI compares native installers with the committed release baseline, requires at least 15% reduction, applies the 190 MiB app-image and 150 MiB DMG limits, and uses a 2% regression tolerance once a new platform size is verified:

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

Before a Windows archive is created, the release workflow resolves the generated app image and requires an application JAR, `jvm.dll`, at least 100 MiB of plausible content and x86_64 PE headers for both launcher and JVM. Linux RPM size enforcement runs after `rpmsign`, so the measured file is the one that is distributed.

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

Every korTTY release format carries the repository's canonical MIT `LICENSE`. The RPM and pacman metadata also declare `MIT`; `scripts/check-release-licenses.py` is the CI gate that prevents application package metadata from drifting to another license. Licences belonging to the Gradle wrapper, bundled runtimes, models or other dependencies remain separate and are not rewritten as korTTY's licence.

## Build Pacman packages

Regular releases publish two Pacman packages: `x86_64` for Arch Linux and `aarch64` for Arch Linux ARM. The application images are built natively on the matching Ubuntu runners first. Both Pacman jobs then run `makepkg` in the official Arch Linux `x86_64` container because the PKGBUILD only copies that pre-built image and does not compile or execute target code.

The AArch64 job supplies a separate `makepkg.conf` with `CARCH=aarch64` and `CHOST=aarch64-unknown-linux-gnu`. A CI guard rejects AArch64 packaging if `prepare()`, `build()` or `check()` is ever added to the PKGBUILD; such a change requires a native Arch Linux ARM builder. The package also disables stripping and debug-package generation so `x86_64` tools never modify a pre-built AArch64 ELF.

Before signing, CI requires the exact package filename and checks `.PKGINFO`, the installed MIT licence, desktop entry and launcher symlink. It also uses `readelf` to verify that the native launcher and bundled JVM match the advertised architecture. The detached signature is then verified locally before the package becomes an Actions artifact.

For a controlled Pacman-only backfill, dispatch **Build Release Binaries** with scope `pacman-only`, the numeric version, the new `pacman_pkgrel` and the full commit expected behind the source tag. Use `release_tag=ci-only` first and inspect both architecture-specific Actions artifacts. Repeat with the real `v<version>` release tag only after inspection. The publishing job refuses immutable releases and filename collisions, uploads without clobbering, compares every remote GitHub digest with the local SHA-256 value and never deletes an existing or partially uploaded asset automatically.

## Build a Flatpak bundle

Flatpak bundles are built natively on Linux for `x86_64` and `aarch64`. The manifest uses application ID `io.github.chardonnay.korTTY` with the Freedesktop 25.08 runtime and packages the self-contained Linux application image produced by `jpackage`. Install Flatpak, `flatpak-builder` and `appstreamcli`, then run:

```bash
./gradlew clean build jpackage
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
flatpak install --user --noninteractive flathub org.freedesktop.Platform//25.08 org.freedesktop.Sdk//25.08
flatpak-builder --user --force-clean --disable-rofiles-fuse --repo=build/flatpak-repo --default-branch=stable build/flatpak-build package/flatpak/io.github.chardonnay.korTTY.yml
flatpak build-bundle --arch="$(flatpak --default-arch)" build/flatpak-repo "build/jpackage/kortty-Linux-<version>-$(flatpak --default-arch).flatpak" io.github.chardonnay.korTTY stable
```

The package grants network, X11, audio, GPU and host-filesystem access because an SSH terminal client must reach remote hosts, render JavaFX/WebView, use terminal media and operate on explicitly selected host files. It also grants access to `org.freedesktop.Flatpak`; local shells and their AI-agent commands are launched on the host through `flatpak-spawn --host`. Validate a local bundle by installing it, checking its architecture and running the packaged WebView smoke:

```bash
flatpak install --user ./build/jpackage/kortty-Linux-<version>-<architecture>.flatpak
flatpak info --user --show-ref io.github.chardonnay.korTTY
xvfb-run -a flatpak run io.github.chardonnay.korTTY --webview-jit-smoke
```

GitHub Actions runs this sequence on native `ubuntu-latest` and `ubuntu-24.04-arm` runners, signs both bundles with the Linux release key and uploads them beside the distro-native packages. A manual `flatpak-only` dispatch accepts a fixed source commit and a separate existing release tag so a bundle can be backfilled without moving the tag or replacing another asset.

## Build a separate llama.cpp runtime package

Integrated local AI uses a native artifact that is intentionally separate from every `jpackage` app image and installer. The upstream tag, full commit, and source-archive SHA-256 are pinned in `gradle/llama-cpp-pins.properties` — the single file a runtime bump rewrites, alongside a row per shipped pin — while `build.gradle.kts` keeps the korTTY runtime revision and API-contract version. `verifyLlamaCppPin` fails closed when those values disagree, the active tag has no pin row, or the requested backend is invalid.

Install CMake and a native C/C++ toolchain. A Vulkan package additionally needs the Vulkan headers, loader, and shader compiler; Metal is available only on macOS with the Apple development tools. Then build the package for the current machine:

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

The task chain downloads only the pinned commit archive, verifies its SHA-256, extracts it, configures a Release build with curl, RPC, tests, examples, tools, and server Web UI disabled, builds `llama-server`, stages its required libraries and license, and creates a reproducible ZIP plus JSON descriptor under `build/llama-runtime/packages/`. The descriptor records the immutable runtime ID, tag/commit, API-contract version, minimum korTTY version, platform, architecture, concrete backend, package size/SHA-256, publication URL placeholder, entrypoint, and revocation flag.

!!! important "One build host cannot create the whole matrix"
    Native llama.cpp artifacts follow the same target-platform rule as `jpackage`: build each operating-system/architecture/backend combination on a matching host. The runtime workflow covers macOS arm64/x86_64 CPU+Metal, Windows x86_64 CPU+Vulkan, Linux x86_64/arm64 CPU+Vulkan. CUDA is not in the first matrix.

### Candidate detection, contract tests, and promotion

`.github/workflows/llama-runtime.yml` checks upstream tags weekly; only the newest tag is ever picked, so a tighter cadence would only pile up unreviewed candidates. A change opens a candidate PR that updates the source pin but does not publish it. A `scope` job then sizes the build per pull request: a PR that touches `gradle/llama-cpp-pins.properties`, the workflow itself, or the llama/runtime-update Java sources runs the full ten-leg native matrix, while a `build.gradle.kts`-only change runs a single Linux x86_64 CPU smoke leg — this is why the pin lives in its own file, and a manual `workflow_dispatch` build is the escape hatch that always forces the full matrix. Every built artifact must start and report its version, the Linux CPU reference job additionally verifies unauthenticated rejection, authenticated model listing, completion, sleep/wake, JSON-schema chat completion, embeddings, and two simultaneously usable sidecars, and a pinned Qdrant 1.18.2 service runs the real vector-store contract. The korTTY repository owns only candidate detection and source-side matrix validation: it has no stable-promotion action, cross-repository release token, or runtime signing private key.

Stable publication belongs to the public [chardonnay/kortty-llama-runtimes](https://github.com/chardonnay/kortty-llama-runtimes) repository. Its explicit human-dispatched workflow accepts the exact reviewed korTTY source commit, rebuilds the native matrix, runs authentication, chat/completion, embeddings, JSON-schema, sleep/wake, and parallel-sidecar smoke tests on every package, and runs the pinned Qdrant contract separately. After those checks it enters the protected `llama-runtime-signing` environment, verifies that `LLAMA_RUNTIME_ED25519_PRIVATE_KEY_PEM` matches the published trust root, verifies and extends the cumulative signed index, and creates an immutable runtime-only release with that repository's scoped `github.token`; no cross-repository personal access token is required. An existing runtime ID is never overwritten. Regular promotion is limited to once every seven days, while an audited security or model-support reason can override that cadence. The application installer therefore stays unchanged while a compatible native runtime can be promoted or withdrawn independently.

## Publish the model and prompt catalog

The reviewed canonical catalog is `ai-catalog/model-prompt-catalog-v1.json`. It uses strict schema v1 for local-model recommendations and model-name-to-preset mappings; application code and prompt-contract text are not part of this independently released payload.

`.github/workflows/ai-catalog-release.yml` is manual-only, accepts dispatches only from `main`, and has no preview or automatic promotion path. Its dispatch requires the exact reviewed `catalogVersion` and a release repository, defaulting to `chardonnay/kortty-ai-catalog`. Before signing, it verifies that the positive catalog sequence is strictly greater than the latest published sequence and runs the authoritative Java schema/trust-chain tests. The `ai-catalog-signing` GitHub environment must have required reviewers before production secrets are configured; inside it, the workflow checks that `KORTTY_AI_CATALOG_PUBLIC_KEY` matches the Ed25519 private signing secret, signs the unchanged JSON bytes, verifies the signature, and publishes immutable `model-prompt-catalog-v1.json` plus `model-prompt-catalog-v1.sig`. An existing version tag or release is refused. Configure `AI_CATALOG_ED25519_PRIVATE_KEY_PEM` and `AI_CATALOG_RELEASE_TOKEN` only as environment-scoped promotion secrets.

### Embed the local-AI trust roots

Every application release that may install runtimes or refresh the catalog must embed both Ed25519 **public** verification keys. The runtime-channel trust root is public, auditable, and pinned in `config/trust/llama-runtime-ed25519-public.pem`, so normal local builds use the same key as official packages. The catalog trust root is supplied separately. Never provide either signing private key to an application build.

| Channel | Source default | Environment variable | Gradle property |
| --- | --- | --- | --- |
| llama.cpp runtime | `config/trust/llama-runtime-ed25519-public.pem` | Optional `KORTTY_LLAMA_RUNTIME_PUBLIC_KEY`; must match the pinned key exactly | Optional `kortty.llamaRuntimePublicKey`; must match the pinned key exactly |
| Model/prompt catalog | None | `KORTTY_AI_CATALOG_PUBLIC_KEY` | `kortty.aiCatalogPublicKey` |

The generated resources store the fixed channel URLs and public trust roots with the application:

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

The official application-release workflow reads both names from GitHub Actions repository variables, rejects missing values or private-key material, and lets Gradle validate the X.509 Ed25519 keys before packaging. The runtime variable is a redundant CI identity check and must encode the same public key as the tracked PEM; a different override fails the build instead of replacing the trust root. If the pinned runtime key or generated release configuration is missing or malformed, runtime installation/update fails closed before fetching the index. Without the catalog key, korTTY makes no catalog request, ignores any cache, and uses its built-in bootstrap.

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

`jpackageDmg` packages the app image `jpackage` produced — it does not build a second one — so run the two in that order; the task fails with an explicit message when `build/jpackage/korTTY.app` is missing. Before the DMG is finished, the app inside it is mounted and checked: its signature must verify, its payload must match the app image on disk, and its signing authority must be the same one. jpackage always re-signs the copy it places in the DMG (it swaps `Contents/app/.jpackage.xml` for a `.package` marker, which breaks the seal), and without an identity it falls back to an ad-hoc signature that Apple's notary rejects — this check is what catches that, and any other signature defect, before a notarization submit rather than minutes into one.

Open the app image for a local smoke test and verify the DMG container:

```bash
open build/jpackage/korTTY.app
hdiutil verify build/jpackage/korTTY-*.dmg
./gradlew verifyMacDmgAppImage
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

The release workflow currently publishes the validated x86_64 Windows package only. OpenJFX does not provide native Windows ARM artifacts for the pinned JavaFX line, so Windows-on-ARM uses this x86_64 package through Windows emulation; the former mislabeled ARM archive is no longer produced or used as a size baseline. Native Windows ARM packaging requires a separate JavaFX/JDK packaging track and native WebView/PTY validation.

```powershell
Test-Path .\build\jpackage\korTTY\korTTY.exe
& .\build\jpackage\korTTY\korTTY.exe
Get-ChildItem .\build\jpackage\*.msi
Get-Item .\build\jpackage\korTTY-Windows-local.zip
```

Test MSI installation, launch, upgrade and removal in a disposable Windows virtual machine matching the target architecture before publishing it.

### Windows troubleshooting

- `jpackageMsi` reports that WiX cannot be found: confirm that `Get-Command candle.exe, light.exe` succeeds in the same PowerShell session, then rerun `clean` and `jpackageMsi`.
- Gradle cannot resolve the packaging JDK: run `.\gradlew.bat -q javaToolchains`, allow toolchain downloads or install a native JDK 25, then stop existing daemons with `.\gradlew.bat --stop`.
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
