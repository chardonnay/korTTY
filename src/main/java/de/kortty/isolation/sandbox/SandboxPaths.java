package de.kortty.isolation.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Path spellings a sandbox rule must cover. Pure apart from resolving symbolic links. */
final class SandboxPaths {

    private SandboxPaths() {
    }

    /**
     * {@code path} as given (made absolute and normalized) and, when it exists and differs, its real path.
     * On macOS {@code /tmp} and {@code /var} are links into {@code /private}, and the sandbox matches the
     * resolved path, so a rule for only the spelling in an environment variable would not apply.
     */
    static List<String> variants(Path path) {
        Set<String> result = new LinkedHashSet<>();
        Path absolute = path.toAbsolutePath().normalize();
        result.add(absolute.toString());
        if (Files.exists(absolute)) {
            try {
                result.add(absolute.toRealPath().toString());
            } catch (IOException ignored) {
                // The spelling as given is all we have.
            }
        }
        return List.copyOf(result);
    }
}
