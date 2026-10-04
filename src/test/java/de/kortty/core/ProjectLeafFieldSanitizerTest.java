package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.SplitPaneState;
import de.kortty.model.WindowState;
import javafx.geometry.Orientation;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * A project file can come from someone else. A split pane's working directory and scrollback
 * reference are session-only: a shared {@code .kortty} file must not choose the directory a local
 * shell starts in, or point a pane at a file, so both are dropped from every project that is loaded
 * or saved. korTTY's own session snapshot keeps them, a scrollback reference only as a plain name.
 */
class ProjectLeafFieldSanitizerTest {

    private Path configDir;

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-project-leaf-fields");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (configDir == null || !Files.exists(configDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(configDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aProjectFileLosesTheSessionOnlyFieldsOfEveryNode() {
        SplitPaneState layout = layoutWithSessionFields("3f2a9c4e-0d1b-4c8e");

        ProjectLeafFieldSanitizer.sanitize(layout, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);

        for (SplitPaneState node : new SplitPaneState[] {layout, layout.getLeftChild(), layout.getRightChild(),
            layout.getRightChild().getLeftChild(), layout.getRightChild().getRightChild()}) {
            assertThat(node.getCurrentDirectory()).isNull();
            assertThat(node.getScrollbackRef()).isNull();
        }
        assertThat(layout.getRightChild().getRightChild().getConnectionId())
            .isEqualTo("connection-b");
        assertThat(layout.getDividerPosition()).isEqualTo(0.5);
    }

    @Test
    void aSessionSnapshotKeepsThemWithAPlainScrollbackName() {
        SplitPaneState layout = layoutWithSessionFields("3f2a9c4e-0d1b-4c8e");

        ProjectLeafFieldSanitizer.sanitize(layout, ProjectLeafFieldSanitizer.Source.SESSION_SNAPSHOT);

        SplitPaneState deepLeaf = layout.getRightChild().getRightChild();
        assertThat(deepLeaf.getCurrentDirectory()).isEqualTo("/home/me/work");
        assertThat(deepLeaf.getScrollbackRef()).isEqualTo("3f2a9c4e-0d1b-4c8e");
    }

    @Test
    void aSessionSnapshotDropsAScrollbackReferenceThatIsNotAPlainName() {
        for (String ref : new String[] {"../../.ssh/id_ed25519", "/etc/passwd", "a/b", "a.b", "name with space",
            "", "x".repeat(65), "C:\\Users\\me"}) {
            SplitPaneState layout = layoutWithSessionFields(ref);

            ProjectLeafFieldSanitizer.sanitize(layout, ProjectLeafFieldSanitizer.Source.SESSION_SNAPSHOT);

            assertThat(layout.getRightChild().getRightChild().getScrollbackRef()).isNull();
            assertThat(layout.getRightChild().getRightChild().getCurrentDirectory()).isEqualTo("/home/me/work");
        }
    }

    @Test
    void plainScrollbackNamesAreLettersDigitsAndDashes() {
        assertThat(ProjectLeafFieldSanitizer.isValidScrollbackRef("3f2a9c4e-0d1b-4c8e-9a77-2b5f6e1d0c3a")).isTrue();
        assertThat(ProjectLeafFieldSanitizer.isValidScrollbackRef("Pane1")).isTrue();
        assertThat(ProjectLeafFieldSanitizer.isValidScrollbackRef(null)).isFalse();
        assertThat(ProjectLeafFieldSanitizer.isValidScrollbackRef("")).isFalse();
        assertThat(ProjectLeafFieldSanitizer.isValidScrollbackRef("../x")).isFalse();
        assertThat(ProjectLeafFieldSanitizer.isValidScrollbackRef("x.enc")).isFalse();
    }

    @Test
    void aVeryDeepHandMadeLayoutIsWalkedWithoutRecursion() {
        SplitPaneState deep = leaf(0);
        deep.setCurrentDirectory("/tmp/prepared");
        for (int i = 0; i < 50_000; i++) {
            deep = SplitPaneState.createSplit(Orientation.VERTICAL, 0.5, deep, leaf(i + 1));
        }

        ProjectLeafFieldSanitizer.sanitize(deep, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);

        SplitPaneState first = deep;
        while (first.getLeftChild() != null) {
            first = first.getLeftChild();
        }
        assertThat(first.getCurrentDirectory()).isNull();
    }

    @Test
    void everyTabOfEveryWindowIsSanitized() {
        Project project = new Project("shared");
        for (int w = 0; w < 2; w++) {
            WindowState window = new WindowState("window-" + w);
            SessionState session = new SessionState("session-" + w, "connection-1");
            session.setSplitPaneState(layoutWithSessionFields("ref-" + w));
            window.addTab(session);
            window.addTab(new SessionState("plain-" + w, "connection-1"));
            project.addWindow(window);
        }

        ProjectLeafFieldSanitizer.sanitize(project, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);
        ProjectLeafFieldSanitizer.sanitize((Project) null, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);

        for (WindowState window : project.getWindows()) {
            SplitPaneState layout = window.getTabs().get(0).getSplitPaneState();
            assertThat(layout.getRightChild().getRightChild().getCurrentDirectory()).isNull();
            assertThat(layout.getRightChild().getRightChild().getScrollbackRef()).isNull();
        }
    }

    @Test
    void aSharedProjectFileOpensWithoutTheSessionOnlyFields() throws Exception {
        Path projectFile = configDir.resolve("shared.kortty");
        Files.writeString(projectFile, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <project>
                    <name>Shared</name>
                    <windows>
                        <window>
                            <windowId>window-1</windowId>
                            <tabs>
                                <tab>
                                    <tabType>TERMINAL</tabType>
                                    <sessionId>session-1</sessionId>
                                    <connectionId>connection-1</connectionId>
                                    <splitPaneState>
                                        <orientation>HORIZONTAL</orientation>
                                        <dividerPosition>0.5</dividerPosition>
                                        <leftChild>
                                            <widgetIndex>0</widgetIndex>
                                            <currentDirectory>/tmp/prepared-repo</currentDirectory>
                                        </leftChild>
                                        <rightChild>
                                            <widgetIndex>1</widgetIndex>
                                            <connectionId>connection-b</connectionId>
                                            <scrollbackRef>../../.ssh/id_ed25519</scrollbackRef>
                                        </rightChild>
                                    </splitPaneState>
                                </tab>
                            </tabs>
                        </window>
                    </windows>
                </project>
                """, StandardCharsets.UTF_8);
        ProjectManager projectManager = new ProjectManager(configDir);

        Project loaded = projectManager.loadProject(projectFile);

        SplitPaneState layout = loaded.getWindows().get(0).getTabs().get(0).getSplitPaneState();
        assertThat(layout.isSplit()).isTrue();
        assertThat(layout.getLeftChild().getCurrentDirectory()).isNull();
        assertThat(layout.getRightChild().getScrollbackRef()).isNull();
        assertThat(layout.getRightChild().getConnectionId()).isEqualTo("connection-b");

        // Saving it again writes neither field back.
        layout.getLeftChild().setCurrentDirectory("/tmp/again");
        Path resaved = configDir.resolve("resaved.kortty");
        projectManager.saveProject(loaded, resaved);
        String xml = Files.readString(resaved, StandardCharsets.UTF_8);
        assertThat(xml).doesNotContain("currentDirectory");
        assertThat(xml).doesNotContain("scrollbackRef");
        assertThat(xml).contains("<connectionId>connection-b</connectionId>");
    }

    /** a | (b / c), with session-only fields on every node and c on another connection. */
    private static SplitPaneState layoutWithSessionFields(String scrollbackRef) {
        SplitPaneState c = SplitPaneState.createLeaf(2, "connection-b");
        SplitPaneState layout = SplitPaneState.createSplit(Orientation.HORIZONTAL, 0.5,
            leaf(0),
            SplitPaneState.createSplit(Orientation.VERTICAL, 0.5, leaf(1), c));
        for (SplitPaneState node : new SplitPaneState[] {layout, layout.getLeftChild(), layout.getRightChild(),
            layout.getRightChild().getLeftChild(), c}) {
            node.setCurrentDirectory("/home/me/work");
            node.setScrollbackRef(scrollbackRef);
        }
        return layout;
    }

    private static SplitPaneState leaf(int index) {
        return SplitPaneState.createLeaf(index);
    }
}
