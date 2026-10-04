package de.kortty.core.sftp.transfer;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** Free names for "keep both". */
class UniqueNamesTest {

    private static Predicate<String> taken(String... names) {
        return Set.of(names)::contains;
    }

    @Test
    void plainNamesGetTheNumberBeforeTheExtension() throws IOException {
        assertThat(UniqueNames.next("report.txt", taken())).isEqualTo("report (1).txt");
        assertThat(UniqueNames.next("README", taken())).isEqualTo("README (1)");
        assertThat(UniqueNames.next("my file.pdf", taken())).isEqualTo("my file (1).pdf");
    }

    @Test
    void dotfilesKeepTheirLeadingDot() throws IOException {
        assertThat(UniqueNames.next(".bashrc", taken())).isEqualTo(".bashrc (1)");
        assertThat(UniqueNames.next(".config.json", taken())).isEqualTo(".config (1).json");
        assertThat(UniqueNames.next("..hidden", taken())).isEqualTo("..hidden (1)");
    }

    @Test
    void doubleTarExtensionsStayTogether() throws IOException {
        assertThat(UniqueNames.next("backup.tar.gz", taken())).isEqualTo("backup (1).tar.gz");
        assertThat(UniqueNames.next("backup.TAR.xz", taken())).isEqualTo("backup (1).TAR.xz");
        assertThat(UniqueNames.next("v1.2.zip", taken())).isEqualTo("v1.2 (1).zip");
    }

    @Test
    void implausibleExtensionsBelongToTheName() throws IOException {
        assertThat(UniqueNames.next("notes.final version", taken())).isEqualTo("notes.final version (1)");
        assertThat(UniqueNames.next("trailing.", taken())).isEqualTo("trailing. (1)");
    }

    @Test
    void aTakenNumberCountsOn() throws IOException {
        assertThat(UniqueNames.next("a.txt", taken("a (1).txt"))).isEqualTo("a (2).txt");
        assertThat(UniqueNames.next("a (1).txt", taken("a (2).txt"))).isEqualTo("a (3).txt");
    }

    @Test
    void aCaseInsensitiveTakenNameIsSkipped() throws IOException {
        Predicate<String> ignoringCase = UniqueNames.among(List.of("Report (1).TXT"), true);
        assertThat(UniqueNames.next("report.txt", ignoringCase)).isEqualTo("report (2).txt");
        Predicate<String> exact = UniqueNames.among(List.of("Report (1).TXT"), false);
        assertThat(UniqueNames.next("report.txt", exact)).isEqualTo("report (1).txt");
    }

    @Test
    void givesUpAfterTheCap() {
        assertThrows(IOException.class, () -> UniqueNames.next("x.txt", name -> true));
    }

    @Test
    void localFolderPredicateSeesLinksPartsAndCaseVariants() throws IOException {
        Path folder = Files.createTempDirectory("unique-names");
        try {
            Files.writeString(folder.resolve("a (1).txt"), "x");
            Files.writeString(folder.resolve(PartFiles.partName("a (2).txt")), "x");
            try {
                Files.createSymbolicLink(folder.resolve("a (3).txt"), folder.resolve("missing"));
            } catch (UnsupportedOperationException | IOException | SecurityException e) {
                Files.writeString(folder.resolve("a (3).txt"), "x"); // no symlinks here (Windows)
            }
            Predicate<String> local = UniqueNames.inLocalFolder(folder);
            assertThat(UniqueNames.next("a.txt", local)).isEqualTo("a (4).txt");
            if (LocalNames.isCaseInsensitive(folder)) {
                assertThat(local.test("A (1).TXT")).isTrue();
            }
        } finally {
            try (var entries = Files.list(folder)) {
                for (Path entry : entries.toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(folder);
        }
    }
}
