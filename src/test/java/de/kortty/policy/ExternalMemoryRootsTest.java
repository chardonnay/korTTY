package de.kortty.policy;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class ExternalMemoryRootsTest {

    private Path base;

    @BeforeMethod
    void createBase() throws IOException {
        base = Files.createTempDirectory("kortty-external-roots-");
    }

    @AfterMethod(alwaysRun = true)
    void deleteBase() throws IOException {
        if (base == null || !Files.exists(base)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(base)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aFolderGivenThroughALinkAllowsTheFilesOfItsRealPath() throws IOException {
        Path real = Files.createDirectories(base.resolve("real"));
        Path link = base.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException noLinks) {
            throw new SkipException("This system does not create symbolic links here.");
        }
        Path file = Files.writeString(real.resolve("AGENTS.md"), "Use tabs.");
        ExternalMemoryRoots roots = ExternalMemoryRoots.of(List.of(link.toString()));

        assertThat(roots.allows(file.toRealPath())).isTrue();
        assertThat(roots.allows(link.resolve("AGENTS.md"))).isTrue();
        assertThat(roots.allows(base.resolve("elsewhere.md"))).isFalse();
    }

    @Test
    void aFolderThatDoesNotExistYetIsComparedByItsPath() throws IOException {
        Path folder = base.resolve("later");
        ExternalMemoryRoots roots = ExternalMemoryRoots.of(List.of(folder.toString()));
        Files.createDirectories(folder);

        assertThat(roots.allows(Files.writeString(folder.resolve("CLAUDE.md"), "x").toRealPath())).isTrue();
        assertThat(roots.allows(base.resolve("other").resolve("CLAUDE.md"))).isFalse();
    }
}
