package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * The places that show the solution card say so: the chat window and the terminal window, whose run UI also feeds the
 * classic panel and the agent designs. The swarm and job runs keep the default and never ask.
 */
class TerminalAgentTriageSurfacesSourceScanTest {

    private static final Pattern OFFERS_CHOICES =
        Pattern.compile("@Override\\s+public boolean supportsChoices\\(\\)\\s*\\{\\s*return true;\\s*\\}");

    /** The form that starts the time to answer once the card is on screen; the default form runs it at once. */
    private static final Pattern SHOWN_FORM = Pattern.compile(
        "public TerminalAgentModels\\.SolutionChoice requestSolutionChoice\\(\\s*TerminalAgentModels\\.TriageProposal \\w+,\\s*Runnable \\w+\\)");

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
        String swarm = Files.readString(Path.of("src/main/java/de/kortty/core/swarm/PerAgentRunUi.java"))
            .replace("\r\n", "\n");
        assertWithMessage("PerAgentRunUi overrides supportsChoices").that(swarm.contains("supportsChoices")).isFalse();
    }

    @Test
    void everyRunUiThatOffersChoicesStartsTheTimeToAnswerWhenTheCardIsShown() throws IOException {
        TreeSet<String> offering = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file).replace("\r\n", "\n");
                if (!OFFERS_CHOICES.matcher(source).find()) {
                    continue;
                }
                offering.add(file.getFileName().toString());
                assertWithMessage(file + " offers choices but does not override the shown-form of requestSolutionChoice")
                    .that(SHOWN_FORM.matcher(source).find()).isTrue();
            }
        }

        assertThat(offering).containsExactly("AiAgentRunTab.java", "MainWindow.java");
    }

    @Test
    void theDesignHostHandsTheShownCallbackToThePromptQueue() throws IOException {
        String host = Files.readString(Path.of("src/main/java/de/kortty/ui/AgentDesignHost.java"))
            .replace("\r\n", "\n");

        assertWithMessage("the card of a design waits in the queue with its shown callback")
            .that(host.contains("prompts.open(prompt, AgentPromptQueue.Kind.CHOICE, shown)")).isTrue();
        assertWithMessage("the classic panel gets the shown callback too")
            .that(host.contains("classicPanel.requestSolutionChoice(runId, proposal, shown)")).isTrue();
    }

    private static void assertOffersChoices(String file) throws IOException {
        String source = Files.readString(Path.of(file)).replace("\r\n", "\n");
        assertWithMessage(file + " overrides supportsChoices() to return true")
            .that(OFFERS_CHOICES.matcher(source).find()).isTrue();
    }
}
