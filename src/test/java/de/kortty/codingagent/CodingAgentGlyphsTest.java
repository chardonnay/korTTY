package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.codingagent.CodingAgentGlyphs.Mark;
import de.kortty.core.AgentDashboardStatus;
import org.testng.annotations.Test;

class CodingAgentGlyphsTest {

    private static final PaneRef PANE = new PaneRef("tab-1", "terminal-a");

    private static CodingAgentEntry entry(CodingAgentKind kind, CodingAgentState state, String alias) {
        DetectionResult detection = DetectionResult.of(kind, state, "rule", "evidence");
        return new CodingAgentEntry(PANE, kind, state, detection, null, 0L, 0L, alias, false);
    }

    @Test
    void mergeFollowsTheDeclaredOrderOverEveryPair() {
        Mark[] marks = Mark.values();
        assertThat(marks).asList()
            .containsExactly(Mark.NONE, Mark.WORKING, Mark.PAUSED, Mark.DONE, Mark.BLOCKED).inOrder();
        for (Mark a : marks) {
            for (Mark b : marks) {
                Mark expected = a.ordinal() >= b.ordinal() ? a : b;
                assertThat(CodingAgentGlyphs.merge(a, b)).isEqualTo(expected);
                assertThat(CodingAgentGlyphs.merge(b, a)).isEqualTo(expected);
            }
        }
        assertThat(CodingAgentGlyphs.merge(null, null)).isEqualTo(Mark.NONE);
        assertThat(CodingAgentGlyphs.merge(null, Mark.DONE)).isEqualTo(Mark.DONE);
        assertThat(CodingAgentGlyphs.merge(Mark.WORKING, null)).isEqualTo(Mark.WORKING);
    }

    @Test
    void markGlyphs() {
        assertThat(Mark.NONE.glyph()).isEmpty();
        assertThat(Mark.WORKING.glyph()).isEqualTo("⚡");
        assertThat(Mark.PAUSED.glyph()).isEqualTo("⏸");
        assertThat(Mark.DONE.glyph()).isEqualTo("✓");
        assertThat(Mark.BLOCKED.glyph()).isEqualTo("✋");
    }

    @Test
    void stateMappings() {
        assertThat(CodingAgentGlyphs.fromCodingState(CodingAgentState.BLOCKED)).isEqualTo(Mark.BLOCKED);
        assertThat(CodingAgentGlyphs.fromCodingState(CodingAgentState.DONE)).isEqualTo(Mark.DONE);
        assertThat(CodingAgentGlyphs.fromCodingState(CodingAgentState.WORKING)).isEqualTo(Mark.WORKING);
        assertThat(CodingAgentGlyphs.fromCodingState(CodingAgentState.IDLE)).isEqualTo(Mark.NONE);
        assertThat(CodingAgentGlyphs.fromCodingState(CodingAgentState.UNKNOWN)).isEqualTo(Mark.NONE);
        assertThat(CodingAgentGlyphs.fromCodingState(null)).isEqualTo(Mark.NONE);

        assertThat(CodingAgentGlyphs.fromLegacyState(AgentDashboardStatus.State.AWAITING)).isEqualTo(Mark.BLOCKED);
        assertThat(CodingAgentGlyphs.fromLegacyState(AgentDashboardStatus.State.WORKING)).isEqualTo(Mark.WORKING);
        assertThat(CodingAgentGlyphs.fromLegacyState(AgentDashboardStatus.State.PAUSED)).isEqualTo(Mark.PAUSED);
        assertThat(CodingAgentGlyphs.fromLegacyState(AgentDashboardStatus.State.DONE)).isEqualTo(Mark.DONE);
        assertThat(CodingAgentGlyphs.fromLegacyState(AgentDashboardStatus.State.NONE)).isEqualTo(Mark.NONE);
        assertThat(CodingAgentGlyphs.fromLegacyState(null)).isEqualTo(Mark.NONE);
    }

    @Test
    void tabBadgeMergesBothSources() {
        assertThat(CodingAgentGlyphs.tabBadge(AgentDashboardStatus.State.PAUSED, CodingAgentState.WORKING)).isEqualTo("⏸");
        assertThat(CodingAgentGlyphs.tabBadge(AgentDashboardStatus.State.AWAITING, CodingAgentState.IDLE)).isEqualTo("✋");
        assertThat(CodingAgentGlyphs.tabBadge(AgentDashboardStatus.State.NONE, CodingAgentState.DONE)).isEqualTo("✓");
        assertThat(CodingAgentGlyphs.tabBadge(AgentDashboardStatus.State.DONE, CodingAgentState.BLOCKED)).isEqualTo("✋");
        assertThat(CodingAgentGlyphs.tabBadge(AgentDashboardStatus.State.WORKING, CodingAgentState.DONE)).isEqualTo("✓");
        assertThat(CodingAgentGlyphs.tabBadge(AgentDashboardStatus.State.NONE, CodingAgentState.UNKNOWN)).isEmpty();
        assertThat(CodingAgentGlyphs.tabBadge(null, null)).isEmpty();
    }

    @Test
    void glyphPerState() {
        assertThat(CodingAgentGlyphs.glyph(CodingAgentState.BLOCKED)).isEqualTo(CodingAgentGlyphs.BLOCKED_GLYPH);
        assertThat(CodingAgentGlyphs.glyph(CodingAgentState.WORKING)).isEqualTo(CodingAgentGlyphs.WORKING_GLYPH);
        assertThat(CodingAgentGlyphs.glyph(CodingAgentState.DONE)).isEqualTo(CodingAgentGlyphs.DONE_GLYPH);
        assertThat(CodingAgentGlyphs.glyph(CodingAgentState.IDLE)).isEqualTo(CodingAgentGlyphs.IDLE_GLYPH);
        assertThat(CodingAgentGlyphs.glyph(CodingAgentState.UNKNOWN)).isEqualTo(CodingAgentGlyphs.IDLE_GLYPH);
        assertThat(CodingAgentGlyphs.glyph(null)).isEqualTo(CodingAgentGlyphs.IDLE_GLYPH);
    }

    @Test
    void rollupTextOmitsZerosAndJoinsWithTheSeparator() {
        assertThat(CodingAgentGlyphs.rollupText(1, 2, 1)).isEqualTo("✋ 1 · ⚡ 2 · ✓ 1");
        assertThat(CodingAgentGlyphs.rollupText(1, 0, 3)).isEqualTo("✋ 1 · ✓ 3");
        assertThat(CodingAgentGlyphs.rollupText(0, 2, 0)).isEqualTo("⚡ 2");
        assertThat(CodingAgentGlyphs.rollupText(0, 0, 0)).isEmpty();
        assertThat(CodingAgentGlyphs.rollupText(-1, 0, 0)).isEmpty();
        assertThat(CodingAgentGlyphs.SEPARATOR).isEqualTo(" · ");
    }

    @Test
    void summaryText() {
        assertThat(CodingAgentGlyphs.summaryText(AgentSummary.EMPTY)).isEmpty();
        assertThat(CodingAgentGlyphs.summaryText(null)).isEmpty();
        assertThat(CodingAgentGlyphs.summaryText(new AgentSummary(1, 2, 0, 4, 7))).isEqualTo("✋ 1 · ⚡ 2");
        assertThat(CodingAgentGlyphs.summaryText(new AgentSummary(0, 0, 0, 2, 2))).isEmpty();
    }

    @Test
    void chipTextUsesGlyphAndShortNameOrAlias() {
        assertThat(CodingAgentGlyphs.chipText(entry(CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, null)))
            .isEqualTo("✋ claude");
        assertThat(CodingAgentGlyphs.chipText(entry(CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, "api")))
            .isEqualTo("✋ api");
        assertThat(CodingAgentGlyphs.chipText(entry(CodingAgentKind.CLAUDE_CODE, CodingAgentState.IDLE, null)))
            .isEqualTo("· claude");
        assertThat(CodingAgentGlyphs.chipText(entry(CodingAgentKind.CODEX, CodingAgentState.WORKING, " ")))
            .isEqualTo("⚡ codex");
        assertThat(CodingAgentGlyphs.chipText(entry(CodingAgentKind.GEMINI_CLI, CodingAgentState.DONE, null)))
            .isEqualTo("✓ gemini");
        assertThat(CodingAgentGlyphs.chipText(null)).isEmpty();
    }

    @Test
    void shortNames() {
        assertThat(CodingAgentGlyphs.shortName(CodingAgentKind.CLAUDE_CODE)).isEqualTo("claude");
        assertThat(CodingAgentGlyphs.shortName(CodingAgentKind.CODEX)).isEqualTo("codex");
        assertThat(CodingAgentGlyphs.shortName(CodingAgentKind.GEMINI_CLI)).isEqualTo("gemini");
        assertThat(CodingAgentGlyphs.shortName(CodingAgentKind.UNKNOWN)).isEqualTo("agent");
        assertThat(CodingAgentGlyphs.shortName(null)).isEqualTo("agent");
    }

    @Test
    void evidenceLineTakesTheFirstNonBlankLineTrimmedAndTruncated() {
        DetectionResult detection = DetectionResult.of(CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, "rule",
            "  \n   Do you want to proceed?   \nsecond line");
        assertThat(CodingAgentGlyphs.evidenceLine(detection, 80)).isEqualTo("Do you want to proceed?");
        assertThat(CodingAgentGlyphs.evidenceLine(detection, 10)).isEqualTo("Do you wa…");
        assertThat(CodingAgentGlyphs.evidenceLine(detection, 10)).hasLength(10);
        assertThat(CodingAgentGlyphs.evidenceLine(detection, 23)).isEqualTo("Do you want to proceed?");
        assertThat(CodingAgentGlyphs.evidenceLine(detection, 1)).isEqualTo("…");
        assertThat(CodingAgentGlyphs.evidenceLine(detection, 0)).isEqualTo("Do you want to proceed?");
        assertThat(CodingAgentGlyphs.evidenceLine(null, 80)).isEmpty();
        assertThat(CodingAgentGlyphs.evidenceLine(DetectionResult.NONE, 80)).isEmpty();
        DetectionResult blank = DetectionResult.of(CodingAgentKind.CODEX, CodingAgentState.IDLE, null, " \n \n");
        assertThat(CodingAgentGlyphs.evidenceLine(blank, 80)).isEmpty();
        DetectionResult crlf = DetectionResult.of(CodingAgentKind.CODEX, CodingAgentState.IDLE, null, "first\r\nsecond");
        assertThat(CodingAgentGlyphs.evidenceLine(crlf, 80)).isEqualTo("first");
    }
}
