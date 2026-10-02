package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless harness for the snippet folder features: the multi-file preview (tabs and the
 * directory tree follow each other, the tree can be hidden, excluded files are not accepted) and
 * the library's folder tree. Writes snapshots to {@code build/smoke/}. Run via the
 * {@code snippetFoldersSmoke} Gradle task. Exit 0 = OK.
 */
public final class SnippetFoldersSmoke {

    private SnippetFoldersSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Path out = Files.createDirectories(Path.of("build/smoke"));
        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                previewFollowsSelectionAndHonoursExclusions(out);
                folderTreeShowsTheLibrary(out);
                projectTabShowsTheDiagramBesideTheReport(out, failure, done);
            } catch (Throwable t) {
                failure.set(t);
                done.countDown();
            }
        });
        if (!done.await(60, TimeUnit.SECONDS)) {
            System.err.println("SnippetFoldersSmoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            failure.get().printStackTrace();
            System.exit(1);
        }
        System.out.println("SnippetFoldersSmoke OK");
        Platform.exit();
        System.exit(0);
    }

    private static void previewFollowsSelectionAndHonoursExclusions(Path out) throws Exception {
        List<SnippetMultiFilePreview.FileChange> files = List.of(
            new SnippetMultiFilePreview.FileChange("main.py", "print(1)\n", "from lib.util import run\nrun()\n", true, "s1"),
            new SnippetMultiFilePreview.FileChange("lib/util.py", null, "def run():\n    print(1)\n", false, null),
            new SnippetMultiFilePreview.FileChange("lib/__init__.py", null, "", false, null),
            new SnippetMultiFilePreview.FileChange("README.md", "docs\n", "docs\n", false, "s2"));
        SnippetMultiFilePreview preview = new SnippetMultiFilePreview("Split into a package.", files, null);
        Stage stage = new Stage();
        stage.setScene(new Scene(preview, 1100, 700));
        stage.show();

        TabPane tabs = (TabPane) preview.lookup("#" + SnippetMultiFilePreview.TABS_ID);
        TreeView<?> tree = (TreeView<?>) preview.lookup("#" + SnippetMultiFilePreview.TREE_ID);
        check(tabs.getTabs().size() == 4, "one tab per file");
        check("main.py".equals(preview.selectedPath()), "the first changed file is selected first");
        check("main.py".equals(preview.treeSelectedPath()), "the tree follows the first selection");

        tabs.getSelectionModel().select(1);
        check("lib/util.py".equals(preview.treeSelectedPath()), "selecting a tab selects its tree node");
        preview.selectPath("README.md");
        check("README.md".equals(preview.selectedPath()), "selecting a path selects its tab");

        check(preview.includedChanges().size() == 3, "unchanged files are never accepted");
        preview.setIncluded("lib/util.py", false);
        check(preview.includedChanges().stream().noneMatch(f -> f.path().equals("lib/util.py")), "excluded file is dropped");
        preview.setIncluded("README.md", true);
        check(preview.includedChanges().size() == 2, "an unchanged file cannot be included");

        check(preview.isTreeShown(), "tree is shown by default");
        snapshot(preview, out.resolve("multi-file-preview.png"));
        preview.setTreeShown(false);
        check(!preview.isTreeShown() && tree.getScene() == null, "the tree can be hidden");
        preview.setTreeShown(true);
        check(preview.isTreeShown(), "the tree can be shown again");
        preview.dispose();
        stage.close();
    }

    private static void folderTreeShowsTheLibrary(Path out) throws Exception {
        Path config = Files.createTempDirectory("kortty-folders-smoke");
        SnippetManager manager = new SnippetManager(config);
        String tools = manager.ensureFolderPath("tools", null);
        String lib = manager.ensureFolderPath("tools/lib", null);
        manager.ensureFolderPath("notes", null);
        Snippet run = new Snippet("run", "echo", "bash");
        manager.addSnippet(run);
        run.setFolderId(tools);
        Snippet util = new Snippet("util", "x", "bash");
        manager.addSnippet(util);
        util.setFolderId(lib);

        int[] selectionChanges = {0};
        SnippetFolderTreePane pane = new SnippetFolderTreePane(manager, new SnippetFolderTreePane.Actions() {
            @Override public void selectionChanged() { selectionChanges[0]++; }
            @Override public void moveSnippets(List<String> ids, String folderId) { }
            @Override public boolean persist() { return true; }
            @Override public void exportFolder(String folderId) { }
            @Override public void copyFolderToTerminal(String folderId) { }
            @Override public void analyzeFolder(String folderId) { }
            @Override public void exportFolderReports(String folderId) { }
            @Override public boolean canCopyToTerminal() { return false; }
            @Override public javafx.stage.Window ownerWindow() { return null; }
        });
        Stage stage = new Stage();
        stage.setScene(new Scene(pane, 360, 420));
        stage.show();
        check(pane.folderIdsInOrder().equals(List.of(tools, lib, manager.ensureFolderPath("notes", null))),
            "the tree lists every folder, sub-folders below their parent");
        pane.selectFolder(lib);
        check(lib.equals(pane.selectedNode().folderId()), "selectFolder expands and selects the folder");
        check(pane.folderIdsInOrder().contains(lib), "the expanded tree shows the sub-folder");
        check(selectionChanges[0] > 0, "selection changes re-filter the table");
        manager.moveFolder(lib, null);
        pane.refresh();
        check(lib.equals(pane.selectedNode().folderId()), "refresh keeps the selection");
        snapshot(pane, out.resolve("snippet-folder-tree.png"));
        stage.close();
    }

    private static void projectTabShowsTheDiagramBesideTheReport(Path out, AtomicReference<Throwable> failure,
                                                                 CountDownLatch done) throws Exception {
        SnippetManager manager = new SnippetManager(Files.createTempDirectory("kortty-project-tab-smoke"));
        String folder = manager.ensureFolderPath("server_performance", null);
        Snippet one = new Snippet("load.pl", "#!/usr/bin/perl\nprint 1;\n", "perl");
        manager.addSnippet(one);
        one.setFolderId(folder);
        de.kortty.core.SnippetAnalysisStore store = new de.kortty.core.SnippetAnalysisStore(null, id -> false, () -> 5);
        de.kortty.core.SnippetAnalysisRecord record = de.kortty.core.SnippetAnalysisRecord.fromAnalysis("r1",
            de.kortty.core.SnippetProjectAiSupport.folderKey(folder),
            new de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis("Two Perl scripts.", List.of(), List.of(
                new de.kortty.core.SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "high",
                    "[load.pl] Quote the command", "detail", "recommendation", 2))),
            de.kortty.core.SnippetAnalysisRecord.Source.of("x", "project", "en", "en", "server_performance"),
            null, de.kortty.core.SnippetAnalysisRecord.Purpose.ANALYSIS, null, System.currentTimeMillis());
        store.addAnalysis(de.kortty.core.SnippetProjectAiSupport.folderKey(folder), record);
        SnippetProjectAnalysisTab tab = new SnippetProjectAnalysisTab(manager, folder, store, () -> null,
            () -> null, null, () -> { });
        TabPane pane = new TabPane(tab);
        Stage stage = new Stage();
        stage.setScene(new Scene(pane, 1600, 900));
        stage.show();
        javafx.animation.PauseTransition settle = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
        settle.setOnFinished(event -> {
            try {
                javafx.scene.control.SplitPane split = (javafx.scene.control.SplitPane) pane.lookupAll(".split-pane")
                    .stream().filter(node -> node instanceof javafx.scene.control.SplitPane)
                    .findFirst().orElseThrow();
                check(split.getOrientation() == javafx.geometry.Orientation.HORIZONTAL,
                    "the folder analysis shows the diagram beside the report");
                check(split.getItems().size() == 2, "the diagram is shown by default");
                snapshot(pane, out.resolve("project-analysis-tab.png"));
                javafx.scene.control.ToggleButton toggle = (javafx.scene.control.ToggleButton)
                    pane.lookup("#" + SnippetProjectAnalysisTab.DIAGRAM_TOGGLE_ID);
                toggle.setSelected(false);
                check(split.getItems().size() == 1, "the diagram can be hidden");
                toggle.setSelected(true);
                check(split.getItems().size() == 2, "the diagram can be shown again");
                tab.dispose();
                stage.close();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        settle.play();
    }

    private static void snapshot(javafx.scene.Node node, Path file) throws Exception {
        var image = node.snapshot(null, null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "ok   " : "FAIL ") + message);
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
