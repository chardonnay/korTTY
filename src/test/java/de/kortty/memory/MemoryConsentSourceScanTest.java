package de.kortty.memory;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/** Only the memory package may issue a {@link MemoryConsent}, in production code and in tests. */
class MemoryConsentSourceScanTest {

    /** Assembled so that this file does not contain the needle itself. */
    private static final String ISSUE = "new " + "MemoryConsent(";

    @Test
    void consentIsIssuedOnlyUnderTheMemoryPackage() throws IOException {
        Path memoryMain = Path.of("src/main/java/de/kortty/memory").toAbsolutePath().normalize();
        List<Path> offenders = new ArrayList<>();
        List<Path> issuers = new ArrayList<>();
        for (Path tree : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            try (Stream<Path> files = Files.walk(tree)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (!Files.readString(file).contains(ISSUE)) {
                        continue;
                    }
                    if (file.toAbsolutePath().normalize().startsWith(memoryMain)) {
                        issuers.add(file);
                    } else {
                        offenders.add(file);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(issuers).isNotEmpty();
    }

    /** Assembled so that this file does not contain the needles itself. */
    private static final String GRANT = "MemoryConsent." + "grant(";
    private static final String USER_REASON = "WriteReason." + "USER_";

    @Test
    void productionCodeNeverGrantsConsent() throws IOException {
        Path memoryMain = Path.of("src/main/java/de/kortty/memory").toAbsolutePath().normalize();
        // No production caller yet: the confirmation of a rule proposal will be the first one, and it is added here.
        assertThat(filesContaining(Path.of("src/main/java"), GRANT, memoryMain.resolve("MemoryConsent.java")))
            .isEmpty();
    }

    @Test
    void onlyTheStoreUsesTheUserActionReasons() throws IOException {
        Path store = Path.of("src/main/java/de/kortty/memory/MemoryStore.java").toAbsolutePath().normalize();
        assertThat(filesContaining(Path.of("src/main/java"), USER_REASON, store)).isEmpty();
    }

    /** The files under {@code tree} that contain {@code needle}, except {@code allowed}. */
    private static List<Path> filesContaining(Path tree, String needle, Path allowed) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tree)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.toAbsolutePath().normalize().equals(allowed)) {
                    continue;
                }
                if (Files.readString(file).contains(needle)) {
                    found.add(file);
                }
            }
        }
        return found;
    }
}
