package de.kortty.core;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Performs a generic operating-system handoff for exported AI chat files.
 */
public class AiChatShareService {

    public ShareResult share(Path file) throws IOException {
        Path normalizedFile = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is not supported on this platform.");
        }

        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.OPEN)) {
            try {
                desktop.open(normalizedFile.toFile());
                return new ShareResult(normalizedFile, false);
            } catch (IOException openFileError) {
                Path parent = normalizedFile.getParent();
                if (parent != null) {
                    desktop.open(parent.toFile());
                    return new ShareResult(normalizedFile, true);
                }
                throw openFileError;
            }
        }

        Path parent = normalizedFile.getParent();
        if (parent == null) {
            throw new IOException("The shared file does not have a parent directory.");
        }
        if (desktop.isSupported(Desktop.Action.OPEN)) {
            desktop.open(parent.toFile());
            return new ShareResult(normalizedFile, true);
        }
        throw new IOException("No supported desktop action is available for sharing this file.");
    }

    public record ShareResult(Path file, boolean openedParentDirectory) {
    }
}
