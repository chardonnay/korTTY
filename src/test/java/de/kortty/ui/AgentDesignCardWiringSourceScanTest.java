package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * A solution card of a design keeps what was typed while another prompt takes the strip, and it is one instance per prompt.
 */
class AgentDesignCardWiringSourceScanTest {

    private static String read(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java"));
    }

    @Test
    void aCardIsCreatedOncePerPromptAndBroughtBackAsItWas() throws IOException {
        String host = read("AgentDesignHost");
        assertThat(host).contains("choiceCards.computeIfAbsent(prompt,");
        assertThat(host).contains("choiceCards.remove(closed);");
        assertThat(host).doesNotContain("approvalStrip.showChoice(prompt.proposal()");
        assertThat(host.split("approvalStrip.showChoice\\(choiceCard\\(prompt\\)\\)", -1).length - 1).isEqualTo(2);
    }

    @Test
    void theStripShowsTheCardItIsGiven() throws IOException {
        assertThat(read("AgentDesignApprovalStrip")).contains("void showChoice(SolutionChoicePane card) {");
    }

    @Test
    void thePresentersDoNotHaveAnUnusedCardAnswer() throws IOException {
        assertThat(read("AgentRunPresenter")).doesNotContain("answerChoice");
        assertThat(read("AgentDesignHost")).doesNotContain("public void answerChoice(");
    }
}
