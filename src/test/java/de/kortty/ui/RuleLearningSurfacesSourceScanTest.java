package de.kortty.ui;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Where the rule detection starts: only in the interactive chat answer and the interactive agent runs, and only after the
 * answer is shown or the run has ended. The swarm and the scheduled jobs never learn.
 */
class RuleLearningSurfacesSourceScanTest {

    private static final Path SOURCES = Path.of("src/main/java/de/kortty");
    private static final List<String> STARTS = List.of("RuleLearning.afterChatAnswer(", "RuleLearning.afterAgentRun(",
        "new RuleDetector(");
    private static final Set<String> STARTING_FILES = Set.of("AiResultTab.java", "AiAgentRunTab.java", "MainWindow.java",
        "RuleLearning.java");

    @Test
    void onlyTheInteractiveSurfacesStartADetection() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                boolean starts = STARTS.stream().anyMatch(source::contains);
                assertWithMessage(file + " starts a rule detection")
                    .that(!starts || STARTING_FILES.contains(file.getFileName().toString())).isTrue();
            }
        }
    }

    @Test
    void swarmAndJobRunsNeverMentionTheLearning() throws IOException {
        for (Path file : List.of(
            Path.of("src/main/java/de/kortty/core/swarm/PerAgentRunUi.java"),
            Path.of("src/main/java/de/kortty/core/swarm/SwarmOrchestrator.java"))) {
            String source = Files.readString(file);
            assertWithMessage(file + " mentions the rule learning").that(source.contains("RuleLearning")).isFalse();
            assertWithMessage(file + " mentions the rule detector").that(source.contains("RuleDetector")).isFalse();
        }
    }

    @Test
    void theChatOffersTheCardOnlyAfterTheAnswerIsShown() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/AiResultTab.java"));
        assertWithMessage("the call sites of offerRuleLearning").that(count(source, "offerRuleLearning(")).isEqualTo(3);

        int shownAt = indexAfter(source,
            "private void showResult(String content, String reasoning, List<AiWebToolCall> webToolCalls, AiSentContext sent) {",
            "appendAssistantMessage(content, reasoning, webToolCalls, sent);");
        int offeredAt = indexAfter(source,
            "private void showResult(String content, String reasoning, List<AiWebToolCall> webToolCalls, AiSentContext sent) {",
            "offerRuleLearning(");
        assertWithMessage("the card is offered after the answer is appended").that(offeredAt).isGreaterThan(shownAt);

        int followUpAnswer = source.indexOf("appendAssistantMessage(\n                result != null ? result.content()");
        int followUpOffer = source.indexOf("offerRuleLearning(selectedProfile);");
        assertWithMessage("the follow-up answer is appended before the offer").that(followUpAnswer).isGreaterThan(0);
        assertWithMessage("the follow-up offer comes after the answer").that(followUpOffer).isGreaterThan(followUpAnswer);
    }

    @Test
    void theAgentSurfacesStartTheDetectionOnlyForARunThatEnded() throws IOException {
        assertEndsBeforeLearning("src/main/java/de/kortty/ui/AiAgentRunTab.java");
        assertEndsBeforeLearning("src/main/java/de/kortty/ui/MainWindow.java");
    }

    private static void assertEndsBeforeLearning(String file) throws IOException {
        String source = Files.readString(Path.of(file));
        int learning = source.indexOf("RuleLearning.afterAgentRun(");
        assertWithMessage(file + " starts the detection of an agent run").that(learning).isGreaterThan(0);
        String before = source.substring(Math.max(0, learning - 400), learning);
        assertWithMessage(file + " learns from a cancelled run")
            .that(before.contains("Phase.CANCELLED")).isTrue();
    }

    private static int count(String source, String text) {
        int count = 0;
        for (int at = source.indexOf(text); at >= 0; at = source.indexOf(text, at + text.length())) {
            count++;
        }
        return count;
    }

    private static int indexAfter(String source, String methodHeader, String text) {
        int start = source.indexOf(methodHeader);
        assertWithMessage("method " + methodHeader).that(start).isAtLeast(0);
        return source.indexOf(text, start);
    }
}
