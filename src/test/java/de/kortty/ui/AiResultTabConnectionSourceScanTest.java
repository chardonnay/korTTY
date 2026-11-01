package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * A chat opened from a terminal tab carries that connection's memory into its follow-ups, its flowchart and its title.
 * The chat is a JavaFX control, so the wiring is pinned in the source rather than run.
 */
class AiResultTabConnectionSourceScanTest {

    @Test
    void followUpsFlowchartAndTitleBuildTheirServiceWithTheChatsConnection() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/AiResultTab.java"));

        assertThat(count(source, "createAiServiceForProfile(selectedProfile, sourceConnection)")).isEqualTo(2);
        assertThat(count(source, "createAiServiceForProfile(profile, sourceConnection)")).isEqualTo(1);
    }

    @Test
    void chatTabsAreBoundToTheConnectionOfTheirTerminalTab() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/MainWindow.java"));

        assertThat(count(source, "resultTab.setSourceConnection(connection);")).isEqualTo(2);
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            found++;
        }
        return found;
    }
}
