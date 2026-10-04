package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.WindowGeometry;
import de.kortty.model.WindowState;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * A project keeps every window with its bounds and the session id of its active tab. Files saved
 * before the active session id existed name the active tab by index only, and must still open.
 */
class ProjectWindowsRoundTripTest {

    private Path configDir;
    private ProjectManager projectManager;

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-project-windows");
        projectManager = new ProjectManager(configDir);
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
    void everyWindowKeepsItsBoundsTabsAndActiveTab() throws Exception {
        Project project = new Project("two windows");
        project.addWindow(window("main", 100, 80, "s-web", "s-db"));
        WindowState second = window("second", 1600, 120, "s-log");
        second.getGeometry().setMaximized(true);
        project.addWindow(second);
        Path file = configDir.resolve("projects").resolve("two-windows.kortty");

        projectManager.saveProject(project, file);
        Project loaded = projectManager.loadProject(file);

        assertThat(loaded.getWindows()).hasSize(2);
        WindowState main = loaded.getWindows().get(0);
        assertThat(main.getGeometry().getX()).isEqualTo(100.0);
        assertThat(main.getTabs()).hasSize(2);
        assertThat(main.getActiveSessionId()).isEqualTo("s-db");
        assertThat(main.getActiveTabIndex()).isEqualTo(1);
        WindowState reloadedSecond = loaded.getWindows().get(1);
        assertThat(reloadedSecond.getGeometry().getX()).isEqualTo(1600.0);
        assertThat(reloadedSecond.getGeometry().isMaximized()).isTrue();
        assertThat(reloadedSecond.getActiveSessionId()).isEqualTo("s-log");
    }

    @Test
    void aFileSavedBeforeTheActiveSessionIdStillOpens() throws Exception {
        Path file = configDir.resolve("projects").resolve("old.kortty");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <project>
                    <name>old</name>
                    <autoReconnect>true</autoReconnect>
                    <windows>
                        <window>
                            <windowId>w-1</windowId>
                            <geometry><x>10.0</x><y>20.0</y><width>900.0</width><height>600.0</height></geometry>
                            <tabs>
                                <tab><tabType>TERMINAL</tabType><sessionId>s-1</sessionId><connectionId>c-1</connectionId></tab>
                                <tab><tabType>TERMINAL</tabType><sessionId>s-2</sessionId><connectionId>c-2</connectionId></tab>
                            </tabs>
                            <activeTabIndex>1</activeTabIndex>
                        </window>
                    </windows>
                </project>
                """);

        WindowState window = projectManager.loadProject(file).getWindows().get(0);

        assertThat(window.getActiveSessionId()).isNull();
        assertThat(window.getActiveTabIndex()).isEqualTo(1);
        assertThat(window.getTabs()).hasSize(2);
        assertThat(window.getGeometry().getWidth()).isEqualTo(900.0);
    }

    private static WindowState window(String id, double x, double y, String... sessionIds) {
        WindowState window = new WindowState(id);
        window.setGeometry(new WindowGeometry(x, y, 1000, 700));
        for (String sessionId : sessionIds) {
            window.addTab(new SessionState(sessionId, "connection-" + sessionId));
        }
        String active = sessionIds[sessionIds.length - 1];
        window.setActiveSessionId(active);
        window.setActiveTabIndex(sessionIds.length - 1);
        return window;
    }
}
