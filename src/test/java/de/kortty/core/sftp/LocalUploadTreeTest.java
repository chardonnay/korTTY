package de.kortty.core.sftp;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The drop walk lists folders before their content and never follows a link into a folder. */
class LocalUploadTreeTest {

    private Path tempDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-upload-tree-test");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        try (var paths = Files.walk(tempDir)) {
            // Files.walk does not follow links, so a loop link is deleted as a link.
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            throw new SkipException("symbolic links are not available here: " + e.getMessage());
        }
    }

    private static List<String> remotes(LocalUploadTree.Result result) {
        return result.entries().stream()
            .map(entry -> (entry.directory() ? "d:" : "f:") + entry.remoteRelative())
            .toList();
    }

    @Test
    void aPlainTreeIsListedFolderFirst() throws IOException {
        Path project = Files.createDirectories(tempDir.resolve("project/src"));
        Files.writeString(project.resolve("main.sh"), "echo hi");
        Files.writeString(tempDir.resolve("project/readme.md"), "hello");
        Path single = Files.writeString(tempDir.resolve("single.txt"), "x");

        LocalUploadTree.Result result = LocalUploadTree.collect(List.of(tempDir.resolve("project"), single));

        List<String> listed = remotes(result);
        assertThat(listed).containsExactly(
            "d:project", "d:project/src", "f:project/src/main.sh", "f:project/readme.md", "f:single.txt");
        assertThat(listed.indexOf("d:project/src")).isLessThan(listed.indexOf("f:project/src/main.sh"));
        assertThat(result.skippedLinkedFolders()).isEmpty();
    }

    @Test(timeOut = 20_000)
    void aSymlinkLoopTerminates() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("dir/a"));
        Files.writeString(dir.resolve("file.txt"), "x");
        link(dir.resolve("loop"), tempDir.resolve("dir"));

        LocalUploadTree.Result result = LocalUploadTree.collect(List.of(tempDir.resolve("dir")));

        assertThat(remotes(result)).containsExactly("d:dir", "d:dir/a", "f:dir/a/file.txt");
        assertThat(result.skippedLinkedFolders()).containsExactly("dir/a/loop");
    }

    @Test
    void aSymlinkedFolderIsSkippedAndALinkedFileUploadsItsContent() throws IOException {
        Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("secret.txt"), "not part of the drop");
        Path shared = Files.writeString(tempDir.resolve("shared.conf"), "k=v");
        Path drop = Files.createDirectories(tempDir.resolve("drop"));
        link(drop.resolve("linked-dir"), elsewhere);
        link(drop.resolve("linked.conf"), shared);
        link(drop.resolve("dangling"), tempDir.resolve("missing"));

        LocalUploadTree.Result result = LocalUploadTree.collect(List.of(drop));

        assertThat(remotes(result)).containsExactly("d:drop", "f:drop/linked.conf");
        assertThat(result.skippedLinkedFolders()).containsExactly("drop/linked-dir");
        LocalUploadTree.Entry linkedFile = result.entries().get(1);
        assertThat(Files.readString(linkedFile.local())).isEqualTo("k=v");
    }

    @Test
    void aDroppedLinkToAFolderIsSkipped() throws IOException {
        Path real = Files.createDirectories(tempDir.resolve("real"));
        Files.writeString(real.resolve("a.txt"), "a");
        Path alias = tempDir.resolve("alias");
        link(alias, real);

        LocalUploadTree.Result result = LocalUploadTree.collect(List.of(alias));

        assertThat(result.entries()).isEmpty();
        assertThat(result.skippedLinkedFolders()).containsExactly("alias");
    }

    @Test
    void aFolderDroppedTogetherWithItsParentIsListedOnce() throws IOException {
        Path child = Files.createDirectories(tempDir.resolve("parent/child"));
        Files.writeString(child.resolve("c.txt"), "c");

        LocalUploadTree.Result result = LocalUploadTree.collect(List.of(tempDir.resolve("parent"), child));

        assertThat(remotes(result)).containsExactly("d:parent", "d:parent/child", "f:parent/child/c.txt");
    }
}
