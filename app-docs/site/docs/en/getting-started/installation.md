# Installation

korTTY runs on **macOS, Windows and Linux**. The recommended installation is a self-contained package from the [GitHub Releases page](https://github.com/chardonnay/korTTY/releases); these packages include the Java runtime needed by korTTY, so users do not need to install Java separately.

## Choose the correct package

Each native asset name includes its processor architecture. Use `aarch64` or `arm64` for an ARM system and `x86_64` for an Intel or AMD system.

=== "macOS"
    Use the `aarch64` assets on Apple Silicon and the `x86_64` assets on an Intel Mac. The DMG provides the normal installation experience; the ZIP contains the complete portable `.app` bundle.

=== "Windows"
    Use the `x86_64` assets on a standard Intel or AMD PC and the `arm64` assets on Windows on ARM. The MSI is the installer; the ZIP contains the complete portable application directory, including `korTTY.exe` and its runtime. The executable inside that directory is not a stand-alone single-file application.

=== "Linux"
    Use the package for your architecture and distribution: DEB for Debian/Ubuntu, RPM for RPM-based distributions, or the ZIP/TAR archive for a portable installation. The archive contains a complete `jpackage` application-image directory; it is not a Linux `.AppImage` file.

## Optional system tools

Some features call external programs only when you use them:

| Feature | Optional requirement |
| --- | --- |
| JobScheduler Rsync | Local `rsync` and `ssh` in `PATH`, or a configured `rsync` binary path |
| Terminal video export | Local `ffmpeg` in `PATH`, or a path configured in **Tools → Video Manager** |

## Build your own package

Building from source requires a complete JDK and platform packaging tools. The dedicated guide covers the thin JAR, Java ZIP/TAR distributions, self-contained portable app images and native installers for every supported operating system, including Intel macOS builds, signing and notarization.

[Build korTTY packages locally →](building-packages.md){ .md-button }

!!! note "macOS Local Network privacy"
    When launched via the Gradle daemon, korTTY runs as a child of a background process that has no Local Network permission, so connecting to LAN or private-IP hosts may fail. For LAN SSH, launch the packaged `.app`, which prompts for Local Network access on first launch, or start korTTY as a child of Terminal.

[Next: First launch & master password →](first-launch.md){ .md-button }
