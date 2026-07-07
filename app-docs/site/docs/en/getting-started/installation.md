# Installation

korTTY runs on **macOS, Windows and Linux**. You can install a pre-built package or build from source.

## System requirements

| Requirement | Minimum |
| --- | --- |
| Java | 25 or higher (CI exception: the Windows ARM64 release runner currently uses Java 21) |
| Gradle | 9.x — included via the wrapper (`./gradlew`) |
| OS | macOS, Windows, Linux |
| Optional — JobScheduler Rsync | local `rsync` and `ssh` in `PATH`, or a configured `rsync` binary path |
| Optional — terminal video export | local `ffmpeg` in `PATH`, or a path configured in **Tools → Video Manager** |

## Pre-built binaries (recommended)

Ready-to-use packages are published on the [GitHub Releases page](https://github.com/chardonnay/korTTY/releases). Each asset name includes the architecture (`-x86_64` / `-aarch64` / `-arm64`). Pick the file matching your system:

=== "macOS"
    Apple Silicon only — use the `-aarch64` `.dmg`.

=== "Windows"
    Use `-x86_64` for Intel/AMD, or `-arm64` for Windows on ARM. `.exe` (portable) or `.msi` (installer).

=== "Linux"
    Use `-x86_64`, or `-aarch64` for ARM (e.g. Raspberry Pi 4, many cloud instances). Packages: `.deb`, `.rpm`, `.tar.gz`, `.zip`.

## Build from source

```bash
git clone https://github.com/chardonnay/korTTY.git
cd korTTY
./gradlew build
```

Run directly:

```bash
./gradlew run
```

### Build native packages locally

korTTY is packaged with `jpackage`; the output matches the architecture of the build machine.

| Platform | Command | Output |
| --- | --- | --- |
| macOS (.app) | `./gradlew jpackage` | `build/jpackage/korTTY.app` |
| macOS (.dmg) | `./gradlew jpackageDmg` | `build/jpackage/korTTY-<version>.dmg` |
| Windows (.exe) | `gradlew.bat jpackage` | `build\jpackage\korTTY\` |
| Windows (.msi) | `gradlew.bat jpackageMsi` | `build\jpackage\korTTY-<version>.msi` |
| Linux (AppImage) | `./gradlew jpackage` | `build/jpackage/korTTY/` |
| Linux (.deb) | `./gradlew jpackageDeb` | `build/jpackage/korTTY-<version>.deb` |
| Linux (.rpm) | `./gradlew jpackageRpm` | `build/jpackage/korTTY-<version>.rpm` |

!!! note "macOS Local Network privacy"
    When launched via the Gradle daemon, korTTY runs as a child of a background process that has no "Local Network" permission, so connecting to LAN / private-IP hosts may fail. For LAN SSH, launch the packaged `.app` (which prompts for Local Network access on first launch) or start korTTY as a child of Terminal.

[Next: First launch & master password →](first-launch.md){ .md-button }
