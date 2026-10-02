package de.kortty.core;

import de.kortty.model.Snippet;
import de.kortty.model.SnippetFolder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SnippetManagerFolderTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-folder-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void foldersNestAndSurviveSaveAndLoad() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        SnippetFolder tools = manager.addFolder("tools", null);
        SnippetFolder lib = manager.addFolder("lib", tools.getId());
        Snippet script = new Snippet("deploy", "#!/bin/bash\necho hi", "bash");
        script.setExecutable(Boolean.FALSE);
        script.setFileName("deploy.sh");
        manager.addSnippet(script);
        manager.moveSnippetsToFolder(List.of(script.getId()), lib.getId());
        manager.save();

        SnippetManager reloaded = new SnippetManager(tempDir);
        reloaded.load();
        Snippet loaded = reloaded.findById(script.getId()).orElseThrow();
        assertThat(loaded.getFolderId()).isEqualTo(lib.getId());
        assertThat(loaded.getExecutable()).isFalse();
        assertThat(loaded.getFileName()).isEqualTo("deploy.sh");
        assertThat(reloaded.folderPath(lib.getId())).isEqualTo("tools/lib");
        assertThat(reloaded.childFolders(null)).containsExactly(tools);
        assertThat(reloaded.snippetsInFolder(tools.getId(), true)).containsExactly(loaded);
        assertThat(reloaded.snippetsInFolder(tools.getId(), false)).isEmpty();
    }

    @Test
    void legacyFileWithoutFoldersLoadsEverySnippetAtTopLevel() throws Exception {
        Files.writeString(tempDir.resolve("snippets.xml"), """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <snippets>
                <snippet><id>s1</id><name>a</name><content>echo a</content><language>bash</language></snippet>
            </snippets>
            """, StandardCharsets.UTF_8);
        SnippetManager manager = new SnippetManager(tempDir);
        manager.load();
        Snippet snippet = manager.findById("s1").orElseThrow();
        assertThat(snippet.getFolderId()).isNull();
        assertThat(snippet.getExecutable()).isNull();
        assertThat(manager.getAllFolders()).isEmpty();
    }

    @Test
    void danglingFolderReferencesAreRepairedOnLoad() throws Exception {
        Files.writeString(tempDir.resolve("snippets.xml"), """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <snippets>
                <snippet><id>s1</id><name>a</name><content>x</content><folderId>missing</folderId></snippet>
                <folders>
                    <folder><id>f1</id><name>one</name><parentId>f2</parentId></folder>
                    <folder><id>f2</id><name>two</name><parentId>f1</parentId></folder>
                    <folder><id>f3</id><name>three</name><parentId>gone</parentId></folder>
                </folders>
            </snippets>
            """, StandardCharsets.UTF_8);
        SnippetManager manager = new SnippetManager(tempDir);
        manager.load();
        assertThat(manager.findById("s1").orElseThrow().getFolderId()).isNull();
        assertThat(manager.findFolder("f3").orElseThrow().getParentId()).isNull();
        // the f1 <-> f2 cycle is broken, so both reach the top level within two steps
        assertThat(manager.folderChain("f1").size()).isAtMost(2);
        assertThat(manager.folderChain("f2").size()).isAtMost(2);
    }

    @Test
    void folderNamesAreUniquePerParentAndValidated() {
        SnippetManager manager = new SnippetManager(tempDir);
        SnippetFolder a = manager.addFolder("a", null);
        manager.addFolder("same", a.getId());
        manager.addFolder("same", null);
        expectThrows(IllegalArgumentException.class, () -> manager.addFolder("SAME", a.getId()));
        expectThrows(IllegalArgumentException.class, () -> manager.addFolder("..", null));
        expectThrows(IllegalArgumentException.class, () -> manager.addFolder("   ", null));
        assertThat(manager.addFolder("x/y", null).getName()).isEqualTo("x-y");
    }

    @Test
    void movingAFolderIntoItselfOrADescendantIsRejected() {
        SnippetManager manager = new SnippetManager(tempDir);
        SnippetFolder a = manager.addFolder("a", null);
        SnippetFolder b = manager.addFolder("b", a.getId());
        SnippetFolder c = manager.addFolder("c", null);
        expectThrows(IllegalArgumentException.class, () -> manager.moveFolder(a.getId(), a.getId()));
        expectThrows(IllegalArgumentException.class, () -> manager.moveFolder(a.getId(), b.getId()));
        manager.moveFolder(a.getId(), c.getId());
        assertThat(manager.folderPath(b.getId())).isEqualTo("c/a/b");
    }

    @Test
    void removingAFolderKeepsOrDeletesItsContents() {
        SnippetManager manager = new SnippetManager(tempDir);
        SnippetFolder parent = manager.addFolder("parent", null);
        SnippetFolder doomed = manager.addFolder("doomed", parent.getId());
        SnippetFolder child = manager.addFolder("child", doomed.getId());
        manager.addFolder("child", parent.getId()); // forces a renamed move-up
        Snippet inDoomed = new Snippet("one", "1", "bash");
        Snippet inChild = new Snippet("two", "2", "bash");
        manager.addSnippet(inDoomed);
        manager.addSnippet(inChild);
        manager.moveSnippetsToFolder(List.of(inDoomed.getId()), doomed.getId());
        manager.moveSnippetsToFolder(List.of(inChild.getId()), child.getId());

        assertThat(manager.removeFolder(doomed.getId(), false)).isEmpty();
        assertThat(inDoomed.getFolderId()).isEqualTo(parent.getId());
        assertThat(manager.findFolder(child.getId()).orElseThrow().getParentId()).isEqualTo(parent.getId());
        assertThat(manager.findFolder(child.getId()).orElseThrow().getName()).isEqualTo("child-2");

        assertThat(manager.removeFolder(parent.getId(), true)).containsExactly(inDoomed, inChild);
        assertThat(manager.getAllSnippets()).isEmpty();
        assertThat(manager.getAllFolders()).isEmpty();
    }

    @Test
    void ensureFolderPathReusesAndCreatesSegments() {
        SnippetManager manager = new SnippetManager(tempDir);
        String deep = manager.ensureFolderPath("a/b/c", null);
        String again = manager.ensureFolderPath("A\\b/./c/", null);
        assertThat(again).isEqualTo(deep);
        assertThat(manager.folderPath(deep)).isEqualTo("a/b/c");
        assertThat(manager.getAllFolders()).hasSize(3);
    }

    @Test
    void jsonXmlAndYamlExportsCarryFolderAndExecutable() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet snippet = new Snippet("tool", "print(1)", "python");
        snippet.setExecutable(Boolean.TRUE);
        snippet.setFileName("tool_main.py");
        manager.addSnippet(snippet);
        snippet.setFolderId(manager.ensureFolderPath("ops/py", null));

        for (String format : List.of("json", "xml", "yaml")) {
            Path file = tempDir.resolve("export." + format);
            switch (format) {
                case "json" -> manager.exportToJson(file, List.of(snippet));
                case "xml" -> manager.exportToXml(file, List.of(snippet));
                default -> manager.exportToYaml(file, List.of(snippet));
            }
            SnippetManager target = new SnippetManager(tempDir.resolve("target-" + format));
            List<Snippet> imported = switch (format) {
                case "json" -> target.importFromJson(file);
                case "xml" -> target.importFromXml(file);
                default -> target.importFromYaml(file);
            };
            assertThat(imported).hasSize(1);
            Snippet copy = imported.get(0);
            assertThat(target.folderPath(copy.getFolderId())).isEqualTo("ops/py");
            assertThat(copy.getExecutable()).isTrue();
            assertThat(copy.getFileName()).isEqualTo("tool_main.py");
        }
    }
}
