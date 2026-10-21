# Installation

korTTY runs on **macOS, Windows and Linux**. The recommended installation is a self-contained package from the [GitHub Releases page](https://github.com/chardonnay/korTTY/releases); these packages include the Java runtime needed by korTTY, so users do not need to install Java separately.

## Choose the correct package

Each native asset name includes its processor architecture. Use `aarch64` or `arm64` for a native ARM package where one is listed and `x86_64` for an Intel or AMD package; Windows is the exception described below.

=== "macOS"
    Use the `aarch64` assets on Apple Silicon and the `x86_64` assets on an Intel Mac. The DMG provides the normal installation experience; the ZIP contains the complete portable `.app` bundle.

=== "Windows"
    Use the `x86_64` assets on Intel/AMD PCs and on Windows on ARM, where Windows runs them through x64 emulation. korTTY detects that emulated Windows ARM combination at startup and uses JavaFX's software renderer because the emulated Direct3D pipeline can leave windows blank; native x64 systems retain hardware rendering. korTTY does not currently publish a native Windows ARM package because the pinned OpenJFX line has no Windows ARM natives. The MSI is the installer; the ZIP contains the complete portable application directory, including `korTTY.exe` and its runtime. The executable inside that directory is not a stand-alone single-file application.

=== "Linux"
    Use the package for your architecture and distribution: DEB for Debian/Ubuntu, RPM for RPM-based distributions, Pacman `x86_64` for Arch Linux, Pacman `aarch64` for Arch Linux ARM, the Flatpak bundle for a sandboxed installation, or the ZIP/TAR archive for a portable installation. The archive contains a complete `jpackage` application-image directory; it is not a Linux `.AppImage` file.

!!! note "Flatpak bundle"
    The `.flatpak` bundle runs korTTY in a Flatpak sandbox on the Freedesktop 25.08 runtime, which comes from Flathub. Install a downloaded bundle from a host terminal with `flatpak install --user ./kortty-Linux-<version>-<architecture>.flatpak`.

!!! note "Java archives and signatures"
    The thin `korTTY-Java-<version>.jar` is not a stand-alone executable. The `.zip` and `.tar` distributions contain the dependencies and launch scripts but no Java runtime, so the destination needs a JDK 25. Linux packages, Flatpak bundles, Pacman packages and the Java archives each come with a detached GPG signature (`.sig`); the signing public key `kortty-linux-signing-public.asc` is published with the same release.

## The `kortty-cli` command

Every native package installs a second launcher, `kortty-cli`, beside the application: the client for korTTY's default-off [control API](../reference/control-api.md). It sits in the same directory as the graphical launcher — `korTTY.app/Contents/MacOS/kortty-cli` on macOS, next to `korTTY.exe` on Windows, `bin/kortty-cli` inside the Linux application directory — and only the Arch package puts it on your `PATH`, as `/usr/bin/kortty-cli`. In the Flatpak, run it as `flatpak run --command=kortty-cli io.github.chardonnay.korTTY`. `kortty-cli --version` is the one command that works without a running korTTY, so it is the quickest way to confirm the installation. See [Control CLI](../reference/cli.md) for the per-platform `PATH` setup.

## Command-line options

Administrators can encrypt a value for an [enterprise policy](../reference/enterprise-policy.md) file without starting the interface: `korTTY --encrypt-policy-value` prints a `kortty-enc:v1:` envelope for the policy key. Pass the plaintext as an argument, or omit it to type the value at the prompt.

## Optional system tools

Some features call external programs only when you use them:

| Feature | Optional requirement |
| --- | --- |
| JobScheduler Rsync | Local `rsync` and `ssh` in `PATH`, or a configured `rsync` binary path |
| Terminal video export | Local `ffmpeg` in `PATH`, or a path configured in **Tools → Video Manager** |

## Optional integrated local AI

The base korTTY package deliberately contains neither model weights nor a native llama.cpp runtime. When you enable integrated local AI, verified runtime packages from the public [korTTY llama.cpp runtime channel](https://github.com/chardonnay/kortty-llama-runtimes) are installed independently under `~/.kortty/llm/runtime/` and GGUF models are downloaded or imported under `~/.kortty/llm/models/` (or referenced from a path you choose). Each runtime index and package is checked against the public Ed25519 trust root pinned in the korTTY source. This keeps the normal SSH client installer small and lets llama.cpp receive compatible stable updates without replacing the whole application.

| Platform | Runtime packages |
| --- | --- |
| macOS arm64 / x86_64 | CPU and Metal |
| Windows x86_64 | CPU and Vulkan |
| Linux x86_64 / arm64 | CPU and Vulkan |

CUDA is not part of the first runtime matrix. Under **AI Manager > Local AI**, choose the preferred runtime backend: Auto/CPU/Metal on macOS or Auto/CPU/Vulkan on Windows/Linux. On a first installation, Auto prefers Metal on macOS and falls back to CPU when no compatible Metal package is available; on Windows and Linux it prefers the portable CPU package and falls back to Vulkan only when CPU is unavailable. Updates keep the active backend when a compatible package exists. Starting a model configured for another supported GPU backend offers to install the matching signed package. Runtime and model downloads can be several gigabytes, so verify free disk space and available RAM/VRAM before installation.

After installing korTTY, open **AI > AI Manager > Local Models > Setup assistant**. It reviews privacy, detects memory, recommends Text/Coding/embedding GGUF models, shows the repository license and exact size, verifies the immutable download, assigns roles, and runs a real local chat or embedding function test. If no runtime is active, accept the signed-runtime installation prompt, or choose **Install runtime** in the same tab before importing a GGUF. New chat models start with a 32,768-token context, or with the Hub's context length when the model was trained for less; change it under **Configure > Context size** or in the profile's **Context window**. Official packages can refresh recommendations and prompt-family mappings from a separate signed catalog; a build without that catalog trust root stays fully usable with its offline bootstrap. See [Local models with llama.cpp](../features/local-models.md) and [RAG knowledge stores](../features/rag.md).

## Build your own package

Building from source requires a complete JDK and platform packaging tools. The dedicated guide covers the thin JAR, Java ZIP/TAR distributions, self-contained portable app images and native installers for every supported operating system, including Intel macOS builds, signing and notarization.

[Build korTTY packages locally →](building-packages.md){ .md-button }

!!! note "macOS Local Network privacy"
    When launched via the Gradle daemon, korTTY runs as a child of a background process that has no Local Network permission, so connecting to LAN or private-IP hosts may fail. For LAN SSH, launch the packaged `.app`, which prompts for Local Network access on first launch, or start korTTY as a child of Terminal.

[Next: First launch & master password →](first-launch.md){ .md-button }
