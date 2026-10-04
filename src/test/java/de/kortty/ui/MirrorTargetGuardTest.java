package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.codingagent.CodingAgentState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.testng.annotations.Test;

/**
 * Broadcast mode leaves out a pane that is pacing a paste, one an AI agent run drives and one whose
 * coding agent waits for a decision (a mirrored {@code y} and Enter would approve it); every other
 * pane, also one with a coding agent that works, is done or idle, keeps getting the keys. The guard
 * is pure and checked here; that every tab installs it is read from the source (CRLF-safe), because
 * building a tab needs a JavaFX toolkit.
 */
class MirrorTargetGuardTest {

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    @Test
    void aPaneInNoneOfTheStatesKeepsGettingTheKeys() {
        assertThat(MirrorTargetGuard.holds(false, false, null)).isFalse();
        for (CodingAgentState state : CodingAgentState.values()) {
            assertWithMessage("coding agent %s", state)
                .that(MirrorTargetGuard.holds(false, false, state))
                .isEqualTo(state == CodingAgentState.BLOCKED);
        }
    }

    @Test
    void eachStateAloneHoldsThePane() {
        assertThat(MirrorTargetGuard.holds(true, false, null)).isTrue();
        assertThat(MirrorTargetGuard.holds(false, true, null)).isTrue();
        assertThat(MirrorTargetGuard.holds(false, false, CodingAgentState.BLOCKED)).isTrue();
        assertThat(MirrorTargetGuard.holds(false, true, CodingAgentState.WORKING)).isTrue();
    }

    @Test
    void theSplitPaneGuardAcceptsOnlyThePanesNoStateHolds() {
        Map<String, CodingAgentState> agents = Map.of(
            "claude-asks", CodingAgentState.BLOCKED,
            "claude-works", CodingAgentState.WORKING,
            "codex-done", CodingAgentState.DONE);
        Predicate<String> guard = MirrorTargetGuard.accepting(
            "pacing"::equals, "agent-run"::equals, agents::get);

        List<String> accepted = new ArrayList<>();
        for (String pane : List.of("shell", "pacing", "agent-run", "claude-asks", "claude-works", "codex-done")) {
            if (guard.test(pane)) {
                accepted.add(pane);
            }
        }
        assertThat(accepted).containsExactly("shell", "claude-works", "codex-done").inOrder();
    }

    @Test
    void theQuestionsStopAtTheFirstThatHoldsThePane() {
        List<String> asked = new ArrayList<>();
        Predicate<String> guard = MirrorTargetGuard.accepting(
            pane -> {
                asked.add("pacing");
                return true;
            },
            pane -> {
                asked.add("agent run");
                return false;
            },
            pane -> {
                asked.add("coding agent");
                return null;
            });

        assertThat(guard.test("pane")).isFalse();
        assertThat(asked).containsExactly("pacing");
    }

    @Test
    void everyTabGuardsItsPanesThisWay() throws IOException {
        String view = Files.readString(TERMINAL_VIEW, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(view).contains("splitPane.setMirrorTargetGuard(MirrorTargetGuard.accepting(\n"
            + "            pastePacer::isPacing, this::hasTerminalAgentRuns, this::codingAgentStateOf));");
        String state = body(view, "private @Nullable CodingAgentState codingAgentStateOf(@Nullable SithTermFxWidget widget) {");
        assertWithMessage("the monitor's latest detection, not the registry a pulse later")
            .that(state).contains("DetectionResult detection = monitor.current();");
        assertThat(state).contains("return detection != null && detection.agentDetected() ? detection.state() : null;");
    }

    /** The text from {@code signature} to the first line that closes a member at four spaces. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("member %s", signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }
}
