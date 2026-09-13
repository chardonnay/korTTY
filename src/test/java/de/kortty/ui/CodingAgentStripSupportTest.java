package de.kortty.ui;

import de.kortty.codingagent.AgentSummary;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.PaneRef;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class CodingAgentStripSupportTest {

    private static final BiFunction<String, Object[], String> ENGLISH = (key, args) -> switch (key) {
        case "codingAgent.state.blocked" -> "Waiting for you";
        case "codingAgent.state.working" -> "Working";
        case "codingAgent.state.done" -> "Done";
        case "codingAgent.state.idle" -> "Idle";
        case "codingAgent.state.unknown" -> "Unknown";
        default -> throw new AssertionError("unexpected key " + key);
    };

    private static final BiFunction<String, Object[], String> IDENTITY = (key, args) -> key;

    // ---- countText ----

    @Test
    void countTextIsIdentityCachedForSmallCounts() {
        for (int count = 0; count <= 99; count++) {
            String first = CodingAgentStripSupport.countText(count);
            String second = CodingAgentStripSupport.countText(count);
            assertThat(first).isEqualTo(Integer.toString(count));
            assertWithMessage("countText(%s) must return the cached instance", count).that(second).isSameInstanceAs(first);
        }
    }

    @Test
    void countTextBeyondTheCacheStillFormatsAndClampsNegatives() {
        assertThat(CodingAgentStripSupport.countText(100)).isEqualTo("100");
        assertThat(CodingAgentStripSupport.countText(1234)).isEqualTo("1234");
        assertThat(CodingAgentStripSupport.countText(-3)).isEqualTo("0");
        assertThat(CodingAgentStripSupport.countText(-3)).isSameInstanceAs(CodingAgentStripSupport.countText(0));
    }

    // ---- stripText ----

    @Test
    void stripTextListsBlockedWorkingDoneInOrderAndOmitsZeros() {
        assertThat(CodingAgentStripSupport.stripText(new AgentSummary(1, 2, 1, 0, 4))).isEqualTo("✋ 1 · ⚡ 2 · ✓ 1");
        assertThat(CodingAgentStripSupport.stripText(new AgentSummary(0, 2, 1, 0, 3))).isEqualTo("⚡ 2 · ✓ 1");
        assertThat(CodingAgentStripSupport.stripText(new AgentSummary(3, 0, 0, 1, 4))).isEqualTo("✋ 3");
        assertThat(CodingAgentStripSupport.stripText(new AgentSummary(0, 0, 5, 0, 5))).isEqualTo("✓ 5");
        assertThat(CodingAgentStripSupport.stripText(new AgentSummary(0, 0, 0, 2, 2))).isEmpty();
    }

    @Test
    void stripTextIsEmptyForEmptyAndNullSummaries() {
        assertThat(CodingAgentStripSupport.stripText(AgentSummary.EMPTY)).isEmpty();
        assertThat(CodingAgentStripSupport.stripText(null)).isEmpty();
    }

    // ---- colours ----

    @DataProvider
    Object[][] colours() {
        return new Object[][] {
            {CodingAgentState.BLOCKED, false, "#f59e0b"},
            {CodingAgentState.BLOCKED, true, "#b45309"},
            {CodingAgentState.WORKING, false, "#60a5fa"},
            {CodingAgentState.WORKING, true, "#1d4ed8"},
            {CodingAgentState.DONE, false, "#34d399"},
            {CodingAgentState.DONE, true, "#15803d"},
            {CodingAgentState.IDLE, false, "#8a8a8a"},
            {CodingAgentState.IDLE, true, "#6b7280"},
            {CodingAgentState.UNKNOWN, false, "#8a8a8a"},
            {CodingAgentState.UNKNOWN, true, "#6b7280"},
            {null, false, "#8a8a8a"},
            {null, true, "#6b7280"},
        };
    }

    @Test(dataProvider = "colours")
    void colorHexTable(CodingAgentState state, boolean light, String expected) {
        assertThat(CodingAgentStripSupport.colorHex(state, light)).isEqualTo(expected);
    }

    @Test
    void chipTextIsDarkOnDarkChromeChipsAndWhiteOnLightChromeChips() {
        assertThat(CodingAgentStripSupport.chipTextHex(false)).isEqualTo("#0b1220");
        assertThat(CodingAgentStripSupport.chipTextHex(true)).isEqualTo("#ffffff");
    }

    @Test
    void isLightRecognisesHexColoursAndTreatsGarbageAsDark() {
        assertThat(CodingAgentStripSupport.isLight("#ffffff")).isTrue();
        assertThat(CodingAgentStripSupport.isLight("#FFFFFF")).isTrue();
        assertThat(CodingAgentStripSupport.isLight("#fff")).isTrue();
        assertThat(CodingAgentStripSupport.isLight("#f5f5f5")).isTrue();
        assertThat(CodingAgentStripSupport.isLight("#ffffffff")).isTrue();
        assertThat(CodingAgentStripSupport.isLight(" #eeeeee ")).isTrue();
        assertThat(CodingAgentStripSupport.isLight("#000000")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("#000")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("#1e1e1e")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("#2d2d2d")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("#282a36")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("garbage")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("#zzzzzz")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("#12345")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("white")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("rgb(255,255,255)")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("")).isFalse();
        assertThat(CodingAgentStripSupport.isLight("   ")).isFalse();
        assertThat(CodingAgentStripSupport.isLight(null)).isFalse();
    }

    // ---- shouldPulse ----

    @Test
    void shouldPulseOnlyWhenEveryGateIsOpen() {
        AgentSummary blocked = new AgentSummary(1, 0, 0, 0, 1);
        AgentSummary working = new AgentSummary(0, 2, 1, 0, 3);
        List<String> failures = new ArrayList<>();
        for (boolean active : new boolean[] {true, false}) {
            for (boolean animations : new boolean[] {true, false}) {
                for (boolean showing : new boolean[] {true, false}) {
                    boolean expected = active && animations && showing;
                    if (CodingAgentStripSupport.shouldPulse(blocked, active, animations, showing) != expected) {
                        failures.add("blocked active=" + active + " animations=" + animations + " showing=" + showing);
                    }
                    if (CodingAgentStripSupport.shouldPulse(working, active, animations, showing)) {
                        failures.add("working active=" + active + " animations=" + animations + " showing=" + showing);
                    }
                    if (CodingAgentStripSupport.shouldPulse(AgentSummary.EMPTY, active, animations, showing)) {
                        failures.add("empty active=" + active + " animations=" + animations + " showing=" + showing);
                    }
                    if (CodingAgentStripSupport.shouldPulse(null, active, animations, showing)) {
                        failures.add("null active=" + active + " animations=" + animations + " showing=" + showing);
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
        assertThat(CodingAgentStripSupport.shouldPulse(blocked, true, true, true)).isTrue();
    }

    // ---- rowStateText ----

    @Test
    void rowStateTextCombinesGlyphLabelAndDuration() {
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.BLOCKED), 134, ENGLISH))
            .isEqualTo("✋ Waiting for you · 2:14");
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.WORKING), 5, ENGLISH))
            .isEqualTo("⚡ Working · 0:05");
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.DONE), 3661, ENGLISH))
            .isEqualTo("✓ Done · 1:01:01");
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.IDLE), 0, ENGLISH))
            .isEqualTo("· Idle · 0:00");
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.UNKNOWN), -7, ENGLISH))
            .isEqualTo("· Unknown · 0:00");
    }

    @Test
    void rowStateTextAsksForTheStateKeys() {
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.BLOCKED), 134, IDENTITY))
            .isEqualTo("✋ codingAgent.state.blocked · 2:14");
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.WORKING), 0, IDENTITY))
            .isEqualTo("⚡ codingAgent.state.working · 0:00");
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.DONE), 59, IDENTITY))
            .isEqualTo("✓ codingAgent.state.done · 0:59");
        assertThat(CodingAgentPanel.rowStateText(entry(CodingAgentState.IDLE), 60, IDENTITY))
            .isEqualTo("· codingAgent.state.idle · 1:00");
        assertThat(CodingAgentPanel.rowStateText(null, 1, IDENTITY))
            .isEqualTo("· codingAgent.state.unknown · 0:01");
    }

    private static CodingAgentEntry entry(CodingAgentState state) {
        DetectionResult detection = DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "rule", "evidence");
        return new CodingAgentEntry(new PaneRef("tab", "pane"), CodingAgentKind.CLAUDE_CODE, state, detection, null,
            1_000L, 1_000L, null, false);
    }
}
