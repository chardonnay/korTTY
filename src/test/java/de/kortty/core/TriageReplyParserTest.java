package de.kortty.core;

import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class TriageReplyParserTest {

    private static final String THREE_OPTIONS = """
        "options": [
          {"title": "Restart in place", "summary": "Restart the unit", "feasibility": "High",
           "risks": ["Short downtime"], "prerequisites": ["Sudo"], "steps": ["Restart", "Check status"],
           "alternatives": ["Reload"]},
          {"title": "Blue-green", "summary": "Start a second unit first", "feasibility": "Medium",
           "risks": [], "prerequisites": [], "steps": ["Start", "Switch"], "alternatives": []},
          {"title": "By hand", "summary": "Edit the files manually", "feasibility": "Low",
           "risks": [], "prerequisites": [], "steps": ["Edit"], "alternatives": []}
        ]
        """;

    @Test
    void parsesComplexReplyWithItsReasonsAndOptionsInOrder() {
        TerminalAgentModels.TriageProposal proposal = TriageReplyParser.parse("""
            {"complexity": "complex", "reasons": ["Two hosts", "Restarts a service"], "recommended": 1,
             %s}
            """.formatted(THREE_OPTIONS));

        assertThat(proposal.complex()).isTrue();
        assertThat(proposal.reasons()).containsExactly("Two hosts", "Restarts a service").inOrder();
        assertThat(proposal.recommended()).isEqualTo(1);
        assertThat(proposal.options()).hasSize(3);
        TerminalAgentModels.PlanOption first = proposal.options().get(0);
        assertThat(first.title()).isEqualTo("Restart in place");
        assertThat(first.summary()).isEqualTo("Restart the unit");
        assertThat(first.feasibility()).isEqualTo("High");
        assertThat(first.risks()).containsExactly("Short downtime");
        assertThat(first.steps()).containsExactly("Restart", "Check status").inOrder();
        assertThat(first.alternatives()).containsExactly("Reload");
        assertThat(proposal.options().get(1).id()).isEqualTo("triage-option-2");
    }

    @Test
    void readsASimpleReplyTooBecauseTheAlwaysModeNeedsItsOptions() {
        TerminalAgentModels.TriageProposal proposal = TriageReplyParser.parse("""
            {"complexity": "Simple", "reasons": [], "recommended": 0, %s}
            """.formatted(THREE_OPTIONS));

        assertThat(proposal.complex()).isFalse();
        assertThat(proposal.reasons()).isEmpty();
        assertThat(proposal.options()).hasSize(3);
    }

    @Test
    void clampsTheRecommendedIndexIntoTheOptions() {
        assertThat(TriageReplyParser.parse(reply("simple", 9)).recommended()).isEqualTo(2);
        assertThat(TriageReplyParser.parse(reply("simple", -4)).recommended()).isEqualTo(0);
    }

    @Test
    void keepsAtMostThreeOptions() {
        TerminalAgentModels.TriageProposal proposal = TriageReplyParser.parse("""
            {"complexity": "complex", "options": [
              {"title": "A", "summary": "a"}, {"title": "B", "summary": "b"},
              {"title": "C", "summary": "c"}, {"title": "D", "summary": "d"}]}
            """);

        assertThat(proposal.options()).hasSize(3);
        assertThat(proposal.options().get(2).title()).isEqualTo("C");
    }

    @Test
    void skipsIncompleteOptionsAndKeepsTheRest() {
        TerminalAgentModels.TriageProposal proposal = TriageReplyParser.parse("""
            {"complexity": "complex", "options": [
              {"title": "No summary"},
              {"title": "A", "summary": "a"}, {"title": "B", "summary": "b"}]}
            """);

        assertThat(proposal.options()).hasSize(2);
        assertThat(proposal.options().get(0).title()).isEqualTo("A");
    }

    @Test
    void recommendedIndexFollowsTheOptionWhenAnEarlierOneIsDropped() {
        TerminalAgentModels.TriageProposal proposal = TriageReplyParser.parse("""
            {"complexity": "complex", "recommended": 1, "options": [
              {"title": "No summary"},
              {"title": "B", "summary": "b"}, {"title": "C", "summary": "c"}]}
            """);

        assertThat(proposal.options()).hasSize(2);
        assertThat(proposal.options().get(proposal.recommended()).title()).isEqualTo("B");
    }

    @Test
    void recommendedFallsBackToTheFirstOptionWhenTheRequestedOneIsDropped() {
        TerminalAgentModels.TriageProposal proposal = TriageReplyParser.parse("""
            {"complexity": "complex", "recommended": 0, "options": [
              {"title": "No summary"},
              {"title": "B", "summary": "b"}, {"title": "C", "summary": "c"}]}
            """);

        assertThat(proposal.recommended()).isEqualTo(0);
        assertThat(proposal.options().get(proposal.recommended()).title()).isEqualTo("B");
    }

    @Test
    void acceptsAFencedReply() {
        TerminalAgentModels.TriageProposal proposal = TriageReplyParser.parse(
            "```json\n{\"complexity\": \"complex\", \"options\": [{\"title\": \"A\", \"summary\": \"a\"},"
                + " {\"title\": \"B\", \"summary\": \"b\"}]}\n```");

        assertThat(proposal.options()).hasSize(2);
    }

    @Test
    void rejectsAComplexityThatIsNeitherSimpleNorComplex() {
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse(reply("maybe", 0)));
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse("{\"options\": []}"));
    }

    @Test
    void rejectsFewerThanTwoCompleteOptions() {
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse("""
            {"complexity": "complex", "options": [{"title": "Only one", "summary": "one"}]}
            """));
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse("""
            {"complexity": "simple", "options": []}
            """));
    }

    @Test
    void rejectsMalformedOrEmptyReplies() {
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse("not json at all"));
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse("{\"complexity\": \"simple\", \"options\": ["));
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse(""));
        expectThrows(RuntimeException.class, () -> TriageReplyParser.parse(null));
    }

    private static String reply(String complexity, int recommended) {
        return "{\"complexity\": \"" + complexity + "\", \"recommended\": " + recommended + ", " + THREE_OPTIONS + "}";
    }
}
