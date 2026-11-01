package de.kortty.memory.external;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Files and folders for the reference memory tests, each under a temporary directory the test deletes. */
final class ExternalMemoryFixtures {

    private ExternalMemoryFixtures() {
    }

    static Path tempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
