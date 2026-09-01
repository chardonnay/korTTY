# Installation

korTTY läuft auf **macOS, Windows und Linux**. Die empfohlene Installation ist ein eigenständiges Paket von der [GitHub Releases-Seite](https://github.com/chardonnay/korTTY/releases); Diese Pakete enthalten die von korTTY benötigte Java-Laufzeitumgebung, sodass Benutzer Java nicht separat installieren müssen.

## Wählen Sie das richtige Paket

Jeder native Asset-Name enthält seine Prozessorarchitektur. Verwenden Sie `aarch64` oder `arm64` für ein natives ARM-Paket, wo eines aufgeführt ist, und `x86_64` für ein Intel- oder AMD-Paket. Windows ist die unten beschriebene Ausnahme.

=== "macOS"
    Verwenden Sie die `aarch64`-Assets auf Apple Silicon und die `x86_64`-Assets auf einem Intel Mac. Das DMG bietet die normale Installationserfahrung; Die ZIP-Datei enthält das komplette portable `.app`-Bundle.

=== "Windows"
    Verwenden Sie die `x86_64`-Assets auf Intel/AMD-PCs und unter Windows auf ARM, wo Windows sie durch x64-Emulation ausführt. korTTY veröffentlicht derzeit kein natives Windows-ARM-Paket, da die angeheftete OpenJFX-Zeile keine nativen Windows-ARM-Pakete enthält. Das MSI ist das Installationsprogramm; Die ZIP-Datei enthält das vollständige tragbare Anwendungsverzeichnis, einschließlich `korTTY.exe` und seiner Laufzeit. Die ausführbare Datei in diesem Verzeichnis ist keine eigenständige Einzeldateianwendung.

=== "Linux"
    Verwenden Sie das Paket für Ihre Architektur und Distribution: DEB für Debian/Ubuntu, RPM für RPM-basierte Distributionen, Pacman `x86_64` für Arch Linux, Pacman `aarch64` für Arch Linux ARM oder das ZIP/TAR-Archiv für eine tragbare Installation. Das Archiv enthält ein vollständiges `jpackage`-Anwendungsbildverzeichnis; Es handelt sich nicht um eine Linux-`.AppImage`-Datei.

## Optionale Systemtools

Einige Funktionen rufen externe Programme nur auf, wenn Sie sie verwenden:

| Merkmal | Optionale Anforderung |
| --- | --- |
| JobScheduler Rsync | Lokal `rsync` und `ssh` in `PATH` oder ein konfigurierter `rsync` Binärpfad |
| Terminal-Videoexport | Lokal `ffmpeg` in `PATH` oder ein in **Tools → Video Manager** konfigurierter Pfad |

## Optional integrierte lokale KI

Das Basispaket korTTY enthält bewusst weder Modellgewichte noch eine native llama.cpp-Laufzeit. Wenn Sie die integrierte lokale KI aktivieren, werden verifizierte Laufzeitpakete aus dem öffentlichen Laufzeitkanal [korTTY llama.cpp ](https://github.com/chardonnay/kortty-llama-runtimes) unabhängig unter `~/.kortty/llm/runtime/` installiert und GGUF-Modelle werden unter `~/.kortty/llm/models/` heruntergeladen oder importiert (oder von einem von Ihnen gewählten Pfad referenziert). Jeder Laufzeitindex und jedes Laufzeitpaket wird mit dem öffentlichen Ed25519-Vertrauensstammverzeichnis verglichen, das in der korTTY-Quelle angeheftet ist. Dadurch bleibt das normale SSH-Client-Installationsprogramm klein und llama.cpp erhält kompatible stabile Updates, ohne die gesamte Anwendung zu ersetzen.

| Plattform | Laufzeitpakete |
| --- | --- |
| macOS arm64 / x86_64 | CPU und Metal |
| Windows x86_64 | CPU und Vulkan |
| Linux x86_64 / arm64 | CPU und Vulkan |

CUDA ist nicht Teil der ersten Laufzeitmatrix. Wählen Sie unter **KI-Manager > Lokale KI** das bevorzugte Laufzeit-Backend aus: Auto/CPU/Metal unter macOS oder Auto/CPU/Vulkan unter Windows/Linux. Automatisch wählt Metal bei einer ersten macOS-Installation und CPU an anderer Stelle aus und behält dann das aktive Backend für Updates bei. Das Starten eines Modells, das für ein anderes unterstütztes GPU-Backend konfiguriert ist, bietet die Möglichkeit, das passende signierte Paket zu installieren. Laufzeit- und Modell-Downloads können mehrere Gigabyte groß sein. Überprüfen Sie daher vor der Installation den freien Speicherplatz und den verfügbaren RAM/VRAM.

Öffnen Sie nach der Installation von korTTY **KI > KI-Manager > Lokale Modelle > Einrichtungsassistent**. Es überprüft den Datenschutz, erkennt Speicher, empfiehlt Text-/Codierungs-/Einbettungs-GGUF-Modelle, zeigt die Repository-Lizenz und die genaue Größe an, überprüft den unveränderlichen Download, weist Rollen zu und führt einen echten lokalen Chat oder Einbettungsfunktionstest durch. Wenn keine Laufzeit aktiv ist, akzeptieren Sie die Installationsaufforderung für die signierte Laufzeit oder wählen Sie **Laufzeit installieren** auf derselben Registerkarte aus, bevor Sie einen GGUF importieren. Die Kontextlänge des Hubs bleibt sichtbar, während neue Modelle konservativ bei 4.096 Laufzeittokens beginnen, bis Sie **Konfigurieren > Kontextgröße** ändern. Offizielle Pakete können Empfehlungen und Zuordnungen von Eingabeaufforderungsfamilien aus einem separaten signierten Katalog aktualisieren. Ein Build ohne dieses Katalog-Trust-Root bleibt mit seinem Offline-Bootstrap vollständig nutzbar. Siehe [Lokale Modelle mit llama.cpp](../features/local-models.md) Und [RAG-Wissensspeicher](../features/rag.md).

## Erstellen Sie Ihr eigenes Paket

Für die Erstellung aus dem Quellcode sind ein vollständiges JDK und Plattform-Paketierungstools erforderlich. Die spezielle Anleitung behandelt die Thin JAR-, Java ZIP/TAR-Distributionen, eigenständige tragbare App-Images und native Installationsprogramme für jedes unterstützte Betriebssystem, einschließlich Intel macOS-Builds, Signierung und Beglaubigung.

[KorTTY-Pakete lokal erstellen →](building-packages.md){ .md-button }

!!! note "macOS Local Network-Datenschutz"
    Wenn korTTY über den Gradle-Daemon gestartet wird, wird es als untergeordnetes Element eines Hintergrundprozesses ausgeführt, der keine Berechtigung für ein lokales Netzwerk hat, sodass die Verbindung zu LAN- oder privaten IP-Hosts möglicherweise fehlschlägt. Starten Sie für LAN-SSH das Paket `.app`, das beim ersten Start zum Zugriff auf das lokale Netzwerk auffordert, oder starten Sie korTTY als untergeordnetes Terminal von Terminal.

[Weiter: Erster Start und Master-Passwort →](first-launch.md){ .md-button }
