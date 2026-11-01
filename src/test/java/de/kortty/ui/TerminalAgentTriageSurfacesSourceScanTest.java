package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * The places that show the solution card say so: the chat window and the terminal window, whose run UI also feeds the
 * classic panel and the agent designs. The swarm and job runs keep the default and never ask.
 */
class TerminalAgentTriageSurfacesSourceScanTest {

    private static final Pattern OFFERS_CHOICES =
        Pattern.compile("@Override\\s+public boolean supportsChoices\\(\\)\\s*\\{\\s*return true;\\s*\\}");

    @Test
    void chatWindowRunsOfferTheSolutionCard() throws IOException {
        assertOffersChoices("src/main/java/de/kortty/ui/AiAgentRunTab.java");
    }

    @Test
    void terminalWindowRunsOfferTheSolutionCardInTheClassicPanelAndTheDesigns() throws IOException {
        assertOffersChoices("src/main/java/de/kortty/ui/MainWindow.java");
    }

    @Test
    void swarmAndJobRunsKeepTheDefaultAndNeverAsk() throws IOException {
        String swarm = Files.readString(Path.of("src/main/java/de/kortty/core/swarm/PerAgentRunUi.java"));
        assertWithMessage("PerAgentRunUi overrides supportsChoices").that(swarm.contains("supportsChoices")).isFalse();
    }

    private static void assertOffersChoices(String file) throws IOException {
        String source = Files.readString(Path.of(file));
        assertWithMessage(file + " overrides supportsChoices() to return true")
            .that(OFFERS_CHOICES.matcher(source).find()).isTrue();
    }
}
