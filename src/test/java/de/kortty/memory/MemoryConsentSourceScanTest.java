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

    /** Only the consent class itself builds one; its grant method is the single place that issues consent. */
    @Test
    void consentIsConstructedOnlyByTheConsentClass() throws IOException {
        Path consent = Path.of("src/main/java/de/kortty/memory/MemoryConsent.java").toAbsolutePath().normalize();
        assertThat(filesContaining(Path.of("src/main/java"), ISSUE, consent)).isEmpty();
        assertThat(filesContaining(Path.of("src/test/java"), ISSUE, consent)).isEmpty();
        assertThat(Files.readString(consent).replace("\r\n", "\n")).contains(ISSUE);
    }

    /** Assembled so that this file does not contain the needles itself. */
    private static final String GRANT = "MemoryConsent." + "grant(";
    private static final String USER_REASON = "WriteReason." + "USER_";

    /**
     * The only production files that may grant consent: the consent class itself, the rule service's confirmation and
     * the memory cleanup's confirmed changes, which grant it at the moment the user confirms.
     */
    @Test
    void productionCodeGrantsConsentOnlyForARuleConfirmation() throws IOException {
        Path memoryMain = Path.of("src/main/java/de/kortty/memory").toAbsolutePath().normalize();
        assertThat(filesContaining(Path.of("src/main/java"), GRANT,
            memoryMain.resolve("MemoryConsent.java"), memoryMain.resolve("RuleProposalService.java"),
            memoryMain.resolve("MemoryCleanupService.java")))
            .isEmpty();
    }

    /** The store, and the user-action facade that the Memory tab calls for the user's own saves, restores and clears. */
    @Test
    void onlyTheStoreUsesTheUserActionReasons() throws IOException {
        Path store = Path.of("src/main/java/de/kortty/memory/MemoryStore.java").toAbsolutePath().normalize();
        Path userActions = Path.of("src/main/java/de/kortty/memory/MemoryUserActions.java").toAbsolutePath().normalize();
        assertThat(filesContaining(Path.of("src/main/java"), USER_REASON, store, userActions)).isEmpty();
    }

    /** The files under {@code tree} that contain {@code needle}, except the {@code allowed} ones. */
    private static List<Path> filesContaining(Path tree, String needle, Path... allowed) throws IOException {
        List<Path> found = new ArrayList<>();
        List<Path> exempt = List.of(allowed);
        try (Stream<Path> files = Files.walk(tree)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (exempt.contains(file.toAbsolutePath().normalize())) {
                    continue;
                }
                if (Files.readString(file).replace("\r\n", "\n").contains(needle)) {
                    found.add(file);
                }
            }
        }
        return found;
    }
}
