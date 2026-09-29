package de.kortty.core;

import de.kortty.model.SnippetVariable;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SnippetVariableManagerTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-variable-manager-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    @Test
    void saveRoundTripsAtomicallyWithoutLeavingTempFiles() throws Exception {
        SnippetVariableManager manager = new SnippetVariableManager(tempDir);
        manager.addOrUpdate("host", "db01");
        manager.save();
        manager.addOrUpdate("user", "deploy ü");
        manager.save();

        List<String> files;
        try (var listing = Files.list(tempDir)) {
            files = listing.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(files).containsExactly("snippet-variables.xml");

        SnippetVariableManager reloaded = new SnippetVariableManager(tempDir);
        reloaded.load();
        assertThat(reloaded.getAll().stream().map(SnippetVariable::getName).toList())
            .containsExactly("host", "user").inOrder();
        assertThat(reloaded.getValue("user")).isEqualTo("deploy ü");
        assertThat(reloaded.getLoadFailureBackup()).isEmpty();
    }

    @Test
    void corruptVariablesFileIsQuarantinedAndNeverOverwritten() throws Exception {
        Path file = tempDir.resolve("snippet-variables.xml");
        String garbage = "<snippetVariables><variable><name>half";
        Files.writeString(file, garbage);
        SnippetVariableManager manager = new SnippetVariableManager(tempDir);

        manager.load(); // must not throw

        Path backup = manager.getLoadFailureBackup().orElseThrow();
        assertThat(backup.getFileName().toString()).matches("snippet-variables\\.xml\\.corrupt-\\d{8}-\\d{6}");
        assertThat(Files.readString(backup)).isEqualTo(garbage);
        assertThat(Files.exists(file)).isFalse();
        assertThat(manager.getAll()).isEmpty();

        manager.addOrUpdate("fresh", "value");
        manager.save();
        assertThat(Files.readString(backup)).isEqualTo(garbage);
        SnippetVariableManager reloaded = new SnippetVariableManager(tempDir);
        reloaded.load();
        assertThat(reloaded.getValue("fresh")).isEqualTo("value");
        assertThat(reloaded.getLoadFailureBackup()).isEmpty();
    }

    @Test
    void unreadableFileThatCannotBeMovedAsideBlocksSaving() throws Exception {
        if (Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) == null
            || "root".equals(System.getProperty("user.name"))) {
            throw new SkipException("needs POSIX directory permissions to make the rename fail");
        }
        Path file = tempDir.resolve("snippet-variables.xml");
        String garbage = "<snippetVariables><variable>";
        Files.writeString(file, garbage);
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-x------"));
        SnippetVariableManager manager = new SnippetVariableManager(tempDir);
        try {
            expectThrows(Exception.class, manager::load);
            assertThat(manager.getLoadFailureBackup()).isEmpty();

            manager.addOrUpdate("late", "value");
            expectThrows(IllegalStateException.class, manager::save);
        } finally {
            Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(Files.readString(file)).isEqualTo(garbage);
    }
}
