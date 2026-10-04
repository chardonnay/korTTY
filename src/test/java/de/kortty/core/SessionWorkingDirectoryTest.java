package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.Project;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import de.kortty.model.SplitPaneState;
import de.kortty.model.WindowState;
import javafx.geometry.Orientation;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A local shell restarts in the directory the session snapshot saved for it, and only there: the
 * directory is session-only (a shared project file never chooses where a shell starts), it is used
 * only while it still exists, and only by a local shell. A remote pane's directory is never replayed,
 * because a restore never types a {@code cd} into a server.
 */
class SessionWorkingDirectoryTest {

    private Path tempDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-cwd");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aSnapshotKeepsOnlyAbsoluteDirectoriesWithoutControlCharacters() {
        assertThat(SessionWorkingDirectory.forSnapshot("/home/me/src")).isEqualTo("/home/me/src");
        assertThat(SessionWorkingDirectory.forSnapshot("  /home/me/src ")).isEqualTo("/home/me/src");
        assertThat(SessionWorkingDirectory.forSnapshot("C:\\Users\\me")).isEqualTo("C:\\Users\\me");
        assertThat(SessionWorkingDirectory.forSnapshot("D:/work")).isEqualTo("D:/work");
        assertThat(SessionWorkingDirectory.forSnapshot("\\\\server\\share")).isEqualTo("\\\\server\\share");
        assertThat(SessionWorkingDirectory.forSnapshot("/" + "a".repeat(SessionWorkingDirectory.MAX_LENGTH - 1)))
            .hasLength(SessionWorkingDirectory.MAX_LENGTH);

        for (String rejected : new String[] {null, "", "   ", "src", "./src", "../etc", "~/src", "C:relative",
            "/tmp/a\nrm -rf ~", "/tmp/\u001b]0;x\u0007", "/tmp/a\rb",
            "/" + "a".repeat(SessionWorkingDirectory.MAX_LENGTH)}) {
            assertWithMessage("kept: %s", rejected).that(SessionWorkingDirectory.forSnapshot(rejected)).isNull();
        }
    }

    @Test
    void onlyALocalShellStartsInTheSavedDirectoryAndOnlyWhileItExists() {
        String existing = tempDir.toAbsolutePath().toString();

        assertThat(SessionWorkingDirectory.startDirectory(ConnectionProtocol.LOCAL_SHELL, existing, Files::isDirectory))
            .isEqualTo(existing);
        assertWithMessage("a directory removed since is ignored")
            .that(SessionWorkingDirectory.startDirectory(ConnectionProtocol.LOCAL_SHELL,
                tempDir.resolve("gone").toAbsolutePath().toString(), Files::isDirectory))
            .isNull();
        assertWithMessage("a file is no directory")
            .that(SessionWorkingDirectory.startDirectory(ConnectionProtocol.LOCAL_SHELL, existing, path -> false))
            .isNull();
        assertThat(SessionWorkingDirectory.startDirectory(ConnectionProtocol.LOCAL_SHELL, null, Files::isDirectory))
            .isNull();
        assertThat(SessionWorkingDirectory.startDirectory(ConnectionProtocol.LOCAL_SHELL, "relative", path -> true))
            .isNull();
        for (ConnectionProtocol remote : ConnectionProtocol.values()) {
            if (remote == ConnectionProtocol.LOCAL_SHELL) {
                continue;
            }
            assertWithMessage("a %s pane never gets a directory, so nothing is replayed as cd", remote)
                .that(SessionWorkingDirectory.startDirectory(remote, existing, path -> true))
                .isNull();
        }
        assertThat(SessionWorkingDirectory.startDirectory(null, existing, path -> true)).isNull();
    }

    @Test
    void aProjectFileLosesTheTabDirectoryAndASessionSnapshotKeepsAValidOne() {
        Project project = projectWithDirectories("/home/me/work", "/home/me/left");
        ProjectLeafFieldSanitizer.sanitize(project, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);
        SessionState tab = project.getWindows().get(0).getTabs().get(0);
        assertThat(tab.getCurrentDirectory()).isNull();
        assertThat(tab.getSplitPaneState().getLeftChild().getCurrentDirectory()).isNull();

        Project snapshot = projectWithDirectories("/home/me/work", "/home/me/left");
        ProjectLeafFieldSanitizer.sanitize(snapshot, ProjectLeafFieldSanitizer.Source.SESSION_SNAPSHOT);
        SessionState kept = snapshot.getWindows().get(0).getTabs().get(0);
        assertThat(kept.getCurrentDirectory()).isEqualTo("/home/me/work");
        assertThat(kept.getSplitPaneState().getLeftChild().getCurrentDirectory()).isEqualTo("/home/me/left");

        Project tampered = projectWithDirectories("relative/dir", "/tmp/a\u0007b");
        ProjectLeafFieldSanitizer.sanitize(tampered, ProjectLeafFieldSanitizer.Source.SESSION_SNAPSHOT);
        SessionState cleaned = tampered.getWindows().get(0).getTabs().get(0);
        assertThat(cleaned.getCurrentDirectory()).isNull();
        assertThat(cleaned.getSplitPaneState().getLeftChild().getCurrentDirectory()).isNull();
    }

    @Test
    void aSplitLayoutCopyIsIndependentOfTheLayoutItCameFrom() {
        SplitPaneState left = SplitPaneState.createLeaf(0);
        left.setCurrentDirectory("/home/me/left");
        left.setScrollbackRef("ref-1");
        SplitPaneState right = SplitPaneState.createLeaf(1, "server-b");
        SplitPaneState original = SplitPaneState.createSplit(Orientation.VERTICAL, 0.3, left, right);

        SplitPaneState copy = original.deepCopy();
        ProjectLeafFieldSanitizer.sanitize(copy, ProjectLeafFieldSanitizer.Source.PROJECT_FILE);

        assertWithMessage("saving a project strips a copy, never the layout a restored tab still rebuilds")
            .that(left.getCurrentDirectory()).isEqualTo("/home/me/left");
        assertThat(left.getScrollbackRef()).isEqualTo("ref-1");
        assertThat(copy.getLeftChild()).isNotSameInstanceAs(left);
        assertThat(copy.getLeftChild().getCurrentDirectory()).isNull();
        assertThat(copy.getOrientation()).isEqualTo("VERTICAL");
        assertThat(copy.getDividerPosition()).isEqualTo(0.3);
        assertThat(copy.getRightChild().getConnectionId()).isEqualTo("server-b");
        assertThat(copy.getRightChild().getWidgetIndex()).isEqualTo(1);
    }

    @Test
    void aVeryDeepLayoutIsCopiedWithoutRecursion() {
        SplitPaneState deep = SplitPaneState.createLeaf(0);
        deep.setCurrentDirectory("/tmp/first");
        for (int i = 0; i < 50_000; i++) {
            deep = SplitPaneState.createSplit(Orientation.VERTICAL, 0.5, deep, SplitPaneState.createLeaf(i + 1));
        }

        SplitPaneState copy = deep.deepCopy();

        SplitPaneState first = copy;
        while (first.getLeftChild() != null) {
            first = first.getLeftChild();
        }
        assertThat(first.getCurrentDirectory()).isEqualTo("/tmp/first");
    }

    @Test
    void theLocalShellStartsInTheRestoredDirectoryOnlyWhileItExists() throws IOException {
        Path configured = Files.createDirectory(tempDir.resolve("configured"));
        Path restored = Files.createDirectory(tempDir.resolve("restored"));
        ServerConnection connection = new ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellWorkingDirectory(configured.toString());
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        String configuredPath = new File(configured.toString()).getAbsolutePath();

        assertThat(connector.restoredOrConfiguredDirectory()).isEqualTo(configuredPath);

        connector.setRestoredStartDirectory(restored.toAbsolutePath().toString());
        assertThat(connector.restoredOrConfiguredDirectory()).isEqualTo(new File(restored.toString()).getAbsolutePath());

        Files.delete(restored);
        assertWithMessage("a restored directory deleted since falls back to the connection's")
            .that(connector.restoredOrConfiguredDirectory())
            .isEqualTo(configuredPath);

        connector.setRestoredStartDirectory("relative/elsewhere");
        assertThat(connector.restoredOrConfiguredDirectory()).isEqualTo(configuredPath);
    }

    @Test
    void aSubmittedCdTellsTheListenerAndARestoreTypesNothing() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/core/LocalShellTtyConnector.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        int accepted = source.indexOf("if (directoryChangeTracker.accept(bytesToWrite)) {");
        assertThat(accepted).isAtLeast(0);
        assertThat(source.substring(accepted, accepted + 200)).contains("notifyWorkingDirectoryMayHaveChanged();");
        assertWithMessage("the restored directory is where the shell starts, not a command sent to it")
            .that(source).contains("String workingDirectory = restoredOrConfiguredDirectory();");
        assertThat(source).doesNotContain("write(\"cd ");
    }

    private static Project projectWithDirectories(String tabDirectory, String leftDirectory) {
        SplitPaneState left = SplitPaneState.createLeaf(0);
        left.setCurrentDirectory(leftDirectory);
        SplitPaneState right = SplitPaneState.createLeaf(1);
        SessionState tab = new SessionState("session-1", "connection-1");
        tab.setCurrentDirectory(tabDirectory);
        tab.setSplitPaneState(SplitPaneState.createSplit(Orientation.HORIZONTAL, 0.5, left, right));
        WindowState window = new WindowState("window-1");
        window.addTab(tab);
        Project project = new Project("p");
        project.addWindow(window);
        return project;
    }
}
