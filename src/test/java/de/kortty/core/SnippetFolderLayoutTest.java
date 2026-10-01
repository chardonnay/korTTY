package de.kortty.core;

import de.kortty.model.Snippet;
import de.kortty.model.SnippetFolder;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class SnippetFolderLayoutTest {

    Path tempDir;
    SnippetManager manager;
    SnippetFolder tools;
    SnippetFolder lib;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-layout-test");
        manager = new SnippetManager(tempDir.resolve("config"));
        tools = manager.addFolder("tools", null);
        lib = manager.addFolder("lib", tools.getId());
        manager.addFolder("empty", tools.getId());
        add("main", "#!/usr/bin/env python3\nprint(1)\r\n", "python", tools.getId());
        add("helpers", "x = 1\n", "python", lib.getId()).setExecutable(Boolean.FALSE);
        add("README", "docs", "markdown", tools.getId());
        add("outside", "echo", "bash", null);
    }

    @AfterMethod
    void tearDown() throws IOException {
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private Snippet add(String name, String content, String language, String folderId) {
        Snippet snippet = new Snippet(name, content, language);
        manager.addSnippet(snippet);
        snippet.setFolderId(folderId);
        return snippet;
    }

    private static Map<String, Integer> modes(SnippetFolderLayout layout) {
        Map<String, Integer> result = new HashMap<>();
        layout.entries().forEach(entry -> result.put(entry.relativePath(), entry.mode()));
        return result;
    }

    @Test
    void aFolderCopiedIntoADirectoryKeepsItsOwnNameAndEmptySubFolders() {
        SnippetFolderLayout layout = SnippetFolderLayout.ofFolder(manager, tools.getId(), true);
        assertThat(layout.directories()).containsExactly("tools", "tools/empty", "tools/lib").inOrder();
        assertThat(modes(layout)).containsExactly(
            "tools/main.py", 0755,
            "tools/README.md", 0644,
            "tools/lib/helpers.py", 0644);
    }

    @Test
    void aFolderExportedOnItsOwnIsRelativeToIt() {
        SnippetFolderLayout layout = SnippetFolderLayout.ofFolder(manager, tools.getId(), false);
        assertThat(layout.directories()).containsExactly("empty", "lib").inOrder();
        assertThat(modes(layout).keySet()).containsExactly("main.py", "README.md", "lib/helpers.py");
    }

    @Test
    void sameFileNamesInOneDirectoryGetASuffix() {
        Snippet clash = new Snippet("main.py", "print(2)", "python");
        manager.addSnippet(clash);
        clash.setFolderId(tools.getId());
        SnippetFolderLayout layout = SnippetFolderLayout.ofFolder(manager, tools.getId(), false);
        assertThat(modes(layout).keySet()).containsAtLeast("main.py", "main-2.py");
    }

    @Test
    void directoryExportWritesLfContentAndPosixModes() throws IOException {
        Path out = tempDir.resolve("out");
        SnippetManager.exportLayoutToDirectory(out, SnippetFolderLayout.ofFolder(manager, tools.getId(), true));
        assertThat(Files.readString(out.resolve("tools/main.py"))).isEqualTo("#!/usr/bin/env python3\nprint(1)\n");
        assertThat(Files.isDirectory(out.resolve("tools/empty"))).isTrue();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("tools/main.py"))))
                .isEqualTo("rwxr-xr-x");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve("tools/lib/helpers.py"))))
                .isEqualTo("rw-r--r--");
        }
    }

    @Test
    void zipExportStoresUnixModes() throws IOException {
        Path zip = tempDir.resolve("tools.zip");
        List<String> names = SnippetManager.exportLayoutToZip(zip,
            SnippetFolderLayout.ofFolder(manager, tools.getId(), true), null);
        assertThat(names).hasSize(3);
        try (ZipFile file = ZipFile.builder().setPath(zip).get()) {
            ZipArchiveEntry main = file.getEntry("tools/main.py");
            ZipArchiveEntry helpers = file.getEntry("tools/lib/helpers.py");
            assertThat(main.getUnixMode() & 0777).isEqualTo(0755);
            assertThat(helpers.getUnixMode() & 0777).isEqualTo(0644);
            assertThat(file.getEntry("tools/empty/")).isNotNull();
        }
    }

    @Test
    void passwordZipKeepsTheExecutableBitOnPosixSystems() throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Path zip = tempDir.resolve("tools-secret.zip");
        SnippetManager.exportLayoutToZip(zip, SnippetFolderLayout.ofFolder(manager, tools.getId(), true),
            "secret".toCharArray());
        try (net.lingala.zip4j.ZipFile file = new net.lingala.zip4j.ZipFile(zip.toFile(), "secret".toCharArray())) {
            assertThat(file.getFileHeader("tools/main.py").isEncrypted()).isTrue();
            Path out = tempDir.resolve("unzipped");
            file.extractAll(out.toString());
            assertThat(Files.readString(out.resolve("tools/lib/helpers.py"))).isEqualTo("x = 1\n");
            assertThat(Files.isExecutable(out.resolve("tools/main.py"))).isTrue();
        }
    }
}
