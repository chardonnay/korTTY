package de.kortty.core;

import de.kortty.model.Snippet;
import de.kortty.model.SnippetDiagram;
import net.lingala.zip4j.ZipFile;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class SnippetManagerTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-manager-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to delete temp path " + path, e);
                    }
                });
        }
    }

    @Test
    void saveLoadAndSearchPreserveSnippetDescription() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet snippet = new Snippet("cleanup.sh", "echo ok", "bash");
        snippet.setDescription("Bereinigt alte Logdateien");
        manager.addSnippet(snippet);
        manager.save();

        SnippetManager reloaded = new SnippetManager(tempDir);
        reloaded.load();

        Snippet loaded = reloaded.getAllSnippets().getFirst();
        assertThat(loaded.getDescription()).isEqualTo("Bereinigt alte Logdateien");
        assertThat(reloaded.search("Logdateien")).isEqualTo(List.of(loaded));
    }

    @Test
    void saveLoadPreservesSnippetDiagrams() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet snippet = new Snippet("deploy.sh", "echo deploy", "bash");
        SnippetDiagram diagram = new SnippetDiagram();
        diagram.setTitle("Deploy flow");
        diagram.setPlantUmlSource("@startuml\nstart\n:Deploy;\nstop\n@enduml");
        diagram.setSourceContentSha256(SnippetDiagramSupport.contentHash(snippet.getContent()));
        diagram.setCustomInstructions("activity");
        snippet.setDiagrams(List.of(diagram));
        manager.addSnippet(snippet);
        manager.save();

        SnippetManager reloaded = new SnippetManager(tempDir);
        reloaded.load();

        Snippet loaded = reloaded.getAllSnippets().getFirst();
        assertThat(loaded.getDiagrams()).hasSize(1);
        assertThat(loaded.getDiagrams().getFirst().getTitle()).isEqualTo("Deploy flow");
        assertThat(loaded.getDiagrams().getFirst().getCustomInstructions()).isEqualTo("activity");
    }

    @Test
    void exportToPlainTextDirectoryUsesSnippetNamesAndKeepsExportsInsideTargetDirectory() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet first = new Snippet("backup_script.sh", "echo first", "bash");
        Snippet duplicate = new Snippet("backup_script.sh", "echo duplicate", "bash");
        Snippet unsafe = new Snippet("../secret.pl", "print \"secret\\n\";", "perl");

        Path exportDirectory = tempDir.resolve("plain-export");
        List<Path> exportedFiles = manager.exportToPlainTextDirectory(
                exportDirectory,
                List.of(first, duplicate, unsafe)
        );

        Path normalizedExportDirectory = exportDirectory.toAbsolutePath().normalize();
        assertThat(exportedFiles).hasSize(3);
        assertThat(exportedFiles.get(0).getFileName().toString()).isEqualTo("backup_script.sh");
        assertThat(exportedFiles.get(1).getFileName().toString()).isEqualTo("backup_script (2).sh");
        assertThat(exportedFiles.get(2).startsWith(normalizedExportDirectory)).isTrue();
        assertThat(exportedFiles.get(2).getFileName().toString()).doesNotContain("/");
        assertThat(Files.readString(exportedFiles.get(0))).isEqualTo("echo first");
        assertThat(Files.readString(exportedFiles.get(1))).isEqualTo("echo duplicate");
        assertThat(Files.readString(exportedFiles.get(2))).isEqualTo("print \"secret\\n\";");
        assertThat(Files.exists(tempDir.resolve("secret.pl"))).isFalse();
    }

    @Test
    void exportToPlainTextDirectoryDoesNotOverwriteExistingFiles() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet snippet = new Snippet("deploy.sh", "echo new", "bash");
        Path exportDirectory = tempDir.resolve("plain-export");
        Files.createDirectories(exportDirectory);
        Files.writeString(exportDirectory.resolve("deploy.sh"), "existing");

        List<Path> exportedFiles = manager.exportToPlainTextDirectory(exportDirectory, List.of(snippet));

        assertThat(exportedFiles).hasSize(1);
        assertThat(exportedFiles.getFirst().getFileName().toString()).isEqualTo("deploy (2).sh");
        assertThat(Files.readString(exportDirectory.resolve("deploy.sh"))).isEqualTo("existing");
        assertThat(Files.readString(exportedFiles.getFirst())).isEqualTo("echo new");
    }

    @Test
    void exportScriptsToZipUsesSelectedExtensionForEverySnippet() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet first = new Snippet("backup_script.sh", "echo first", "bash");
        Snippet second = new Snippet("cleanup.py", "print('cleanup')", "python");
        Path zipPath = tempDir.resolve("scripts.zip");

        List<String> entryNames = manager.exportScriptsToZip(zipPath, List.of(first, second), "txt", null);

        assertThat(entryNames).containsExactly("backup_script.txt", "cleanup.txt").inOrder();
        Path extractDirectory = tempDir.resolve("extract");
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            assertThat(zipFile.isEncrypted()).isFalse();
            zipFile.extractAll(extractDirectory.toString());
        }
        assertThat(Files.readString(extractDirectory.resolve("backup_script.txt"))).isEqualTo("echo first");
        assertThat(Files.readString(extractDirectory.resolve("cleanup.txt"))).isEqualTo("print('cleanup')");
    }

    @Test
    void exportScriptsToZipPasswordEncryptsArchive() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet snippet = new Snippet("deploy.sh", "echo deploy", "bash");
        Path zipPath = tempDir.resolve("scripts.zip");
        char[] password = "correct horse battery staple".toCharArray();

        List<String> entryNames = manager.exportScriptsToZip(zipPath, List.of(snippet), null, password);

        assertThat(entryNames).containsExactly("deploy.sh");
        Path extractDirectory = tempDir.resolve("extract-encrypted");
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            assertThat(zipFile.isEncrypted()).isTrue();
            zipFile.setPassword(password);
            zipFile.extractAll(extractDirectory.toString());
        }
        assertThat(Files.readString(extractDirectory.resolve("deploy.sh"))).isEqualTo("echo deploy");
    }
}
