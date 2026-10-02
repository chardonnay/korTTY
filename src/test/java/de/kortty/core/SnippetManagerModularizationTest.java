package de.kortty.core;

import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import de.kortty.model.Snippet;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class SnippetManagerModularizationTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-modularize-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void theScriptBecomesTheEntryPointOfANewFolder() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        String parent = manager.ensureFolderPath("ops", null);
        Snippet original = new Snippet("backup tool", "#!/usr/bin/env python3\nprint('all in one')\n", "python");
        original.setTags(new java.util.ArrayList<>(List.of("ops")));
        manager.addSnippet(original);
        original.setFolderId(parent);
        manager.addSnippet(new Snippet("helpers.py", "taken", "python")); // forces a prefixed module name

        ModularizationPlan plan = new ModularizationPlan(true, "split", List.of(
            new ModuleFile("main.py", "CLI", List.of("main"), true, true),
            new ModuleFile("lib/helpers.py", "helpers", List.of("load"), false, false),
            new ModuleFile("lib/__init__.py", "package", List.of(), false, false)));
        SnippetManager.ModularizationResult result = manager.applyModularization(original.getId(), plan, Map.of(
            "main.py", "#!/usr/bin/env python3\nfrom lib.helpers import load\nload()\n",
            "lib/helpers.py", "def load():\n    print('x')\n",
            "lib/__init__.py", "\n"));
        manager.save();

        assertThat(manager.folderPath(result.folderId())).isEqualTo("ops/backup-tool");
        assertThat(result.entry()).isSameInstanceAs(original);
        assertThat(original.getContent()).contains("from lib.helpers import load");
        assertThat(original.getFolderId()).isEqualTo(result.folderId());
        assertThat(SnippetExecutableSupport.fileNameOf(original)).isEqualTo("main.py");
        assertThat(SnippetExecutableSupport.isExecutable(original)).isTrue();
        assertThat(original.getHistory().stream().map(h -> h.getContent()).toList())
            .contains("#!/usr/bin/env python3\nprint('all in one')\n");

        assertThat(result.modules()).hasSize(2);
        Snippet helpers = result.modules().stream()
            .filter(s -> SnippetExecutableSupport.fileNameOf(s).equals("helpers.py")).findFirst().orElseThrow();
        assertThat(helpers.getName()).isEqualTo("ops/backup-tool/lib/helpers.py");
        assertThat(SnippetExecutableSupport.isExecutable(helpers)).isFalse();
        assertThat(helpers.getTags()).containsAtLeast("ops", "module");
        assertThat(manager.folderPath(helpers.getFolderId())).isEqualTo("ops/backup-tool/lib");

        SnippetFolderLayout layout = SnippetFolderLayout.ofFolder(manager, result.folderId(), true);
        assertThat(layout.entries().stream().map(SnippetFolderLayout.Entry::relativePath).toList())
            .containsExactly("backup-tool/main.py", "backup-tool/lib/__init__.py", "backup-tool/lib/helpers.py");
    }

    @Test
    void writingExistingFilesReplacesContentAndKeepsHistory() {
        SnippetManager manager = new SnippetManager(tempDir);
        String root = manager.ensureFolderPath("proj", null);
        Snippet run = new Snippet("run", "echo old\n", "bash");
        manager.addSnippet(run);
        run.setFolderId(root);
        SnippetManager.FolderWriteResult result = manager.writeFolderFiles(root, List.of(
            new SnippetManager.FolderFileWrite("run.sh", "echo new\n", null, null, run.getId()),
            new SnippetManager.FolderFileWrite("lib/util.sh", "util() { :; }\n", false, "utils", null)), run);
        assertThat(result.updated()).containsExactly(run);
        assertThat(run.getContent()).isEqualTo("echo new\n");
        assertThat(run.getFileName()).isNull(); // "run" + bash already gives run.sh
        assertThat(run.getHistory().getFirst().getContent()).isEqualTo("echo old\n");
        Snippet util = result.created().getFirst();
        assertThat(util.getLanguage()).isEqualTo("bash");
        assertThat(util.getExecutable()).isFalse();
        assertThat(util.getDescription()).isEqualTo("utils");
    }
}
