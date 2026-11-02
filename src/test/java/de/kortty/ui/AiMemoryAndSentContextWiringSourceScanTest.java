package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/** The request memory is masked with the terminal's known secrets, and the sent-context rows show the learned rules. */
class AiMemoryAndSentContextWiringSourceScanTest {

    @Test
    void theRequestCreatedFromATerminalPassesItsKnownSecretsToTheMemory() throws IOException {
        String window = Files.readString(Path.of("src/main/java/de/kortty/ui/MainWindow.java")).replace("\r\n", "\n");

        assertThat(window).contains("terminalTab.getConnection() : null, null, knownSecrets);");
        assertThat(window).contains("memoryPromptSource(settings, connection, profile, false, sessionSecrets)");
    }

    @Test
    void theSentContextSkillRowShowsTheLearnedRulesAsItsTooltip() throws IOException {
        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/SentContextView.java"))
            .replace("\r\n", "\n");

        assertThat(view).contains("skill.learnedRules()");
    }
}
