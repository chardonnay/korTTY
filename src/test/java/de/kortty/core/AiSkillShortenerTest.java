package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.core.AiSkillShortener.Assistant;
import de.kortty.core.AiSkillShortener.Problem;
import de.kortty.core.AiSkillShortener.Proposal;
import de.kortty.core.AiSkillShortener.ShortenException;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class AiSkillShortenerTest {

    private static final String TEXT = "Use snake_case for every Python name. Keep functions short and documented.";

    private static AiSkill userSkill(String content, String... rules) {
        AiSkill skill = new AiSkill();
        skill.setId("skill-1");
        skill.setName("Python");
        skill.setContent(content);
        for (String rule : rules) {
            skill.getLearnedRules().add(AiSkillLearnedRule.create(rule, AiSkillLearnedRule.SOURCE_CHAT));
        }
        return skill;
    }

    private static AiSkill builtinSkill(String content, String... rules) {
        AiSkill skill = userSkill(content, rules);
        skill.setBuiltinId("builtin.lang.python");
        return skill;
    }

    private static Assistant replying(String reply) {
        return (systemPrompt, userPrompt) -> reply;
    }

    private static List<String> texts(AiSkill skill) {
        return skill.getLearnedRules().stream().map(AiSkillLearnedRule::getText).toList();
    }

    private static void assertRefused(AiSkill skill, String reply, Problem problem) {
        ShortenException thrown = expectThrows(ShortenException.class,
            () -> AiSkillShortener.propose(skill, replying(reply)));
        assertThat(thrown.problem()).isEqualTo(problem);
    }

    /** Records what the assistant was sent and answers with a fixed reply. */
    private static final class RecordingAssistant implements Assistant {

        private final String reply;
        private String systemPrompt;
        private String userPrompt;
        private int calls;

        RecordingAssistant(String reply) {
            this.reply = reply;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            this.calls++;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            return reply;
        }
    }

    @Test
    void thePromptCarriesOnlyTheTextAndTheNumberedRulesOfTheSkill() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.", "Prefer pytest for tests.");

        JsonObject prompt = JsonParser.parseString(AiSkillShortener.userPrompt(skill)).getAsJsonObject();

        assertThat(prompt.keySet()).containsExactly("content", "rules");
        assertThat(prompt.get("content").getAsString()).isEqualTo(TEXT);
        JsonArray rules = prompt.getAsJsonArray("rules");
        assertThat(rules.size()).isEqualTo(2);
        assertThat(rules.get(0).getAsJsonObject().get("number").getAsInt()).isEqualTo(1);
        assertThat(rules.get(1).getAsJsonObject().get("text").getAsString()).isEqualTo("Prefer pytest for tests.");
    }

    @Test
    void aBuiltInSkillsPromptHoldsNoTextOfItsOwn() {
        AiSkill skill = builtinSkill(TEXT, "Always answer in German.");

        String prompt = AiSkillShortener.userPrompt(skill);

        assertThat(prompt).doesNotContain("snake_case");
        assertThat(prompt).doesNotContain("\"content\"");
        assertThat(prompt).contains("Always answer in German.");
    }

    @Test
    void theAssistantGetsTheInstructionsAndThePromptOfTheSkillOnly() throws Exception {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        RecordingAssistant assistant = new RecordingAssistant(
            "{\"content\": \"Use snake_case. Keep functions short.\", \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1]}]}");

        AiSkillShortener.propose(skill, assistant);

        assertThat(assistant.calls).isEqualTo(1);
        assertThat(assistant.systemPrompt).isEqualTo(AiSkillShortener.SYSTEM_PROMPT);
        assertThat(assistant.userPrompt).isEqualTo(AiSkillShortener.userPrompt(skill));
    }

    @Test
    void aSkillWithNothingToShortenIsNotSentAtAll() {
        AiSkill builtin = builtinSkill(TEXT);
        RecordingAssistant assistant = new RecordingAssistant("{}");

        ShortenException thrown = expectThrows(ShortenException.class,
            () -> AiSkillShortener.propose(builtin, assistant));

        assertThat(thrown.problem()).isEqualTo(Problem.NOTHING_TO_SHORTEN);
        assertThat(assistant.calls).isEqualTo(0);
    }

    @Test
    void aBuiltInSkillKeepsItsTextAndOnlyItsRulesChange() throws Exception {
        AiSkill builtin = builtinSkill(TEXT, "Always answer in German.", "Please answer in German, always.");
        String reply = "{\"content\": \"Shorter text.\", \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1, 2]}]}";

        Proposal proposal = AiSkillShortener.propose(builtin, replying(reply));

        assertThat(proposal.textChanged()).isFalse();
        assertThat(proposal.after().getContent()).isEqualTo(TEXT);
        assertThat(texts(proposal.after())).containsExactly("Answer in German.");
    }

    @Test
    void aUserSkillWithoutRulesCanShortenItsText() throws Exception {
        AiSkill skill = userSkill(TEXT);
        String reply = "{\"content\": \"Use snake_case. Keep functions short.\"}";

        Proposal proposal = AiSkillShortener.propose(skill, replying(reply));

        assertThat(proposal.textChanged()).isTrue();
        assertThat(proposal.after().getContent()).isEqualTo("Use snake_case. Keep functions short.");
        assertThat(proposal.after().getLearnedRules()).isEmpty();
    }

    @Test
    void aMergedRuleKeepsTheIdOfTheFirstRuleItReplacesAndUnmentionedRulesStayAsTheyWere() throws Exception {
        AiSkill skill = userSkill(TEXT, "Always answer in German.", "Please answer in German, always.",
            "Prefer pytest for tests.");
        String firstId = skill.getLearnedRules().get(0).getId();
        String thirdId = skill.getLearnedRules().get(2).getId();
        String reply = "{\"content\": \"Use snake_case. Keep functions short.\","
            + " \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1, 2]}]}";

        Proposal proposal = AiSkillShortener.propose(skill, replying(reply));

        List<AiSkillLearnedRule> rules = proposal.after().getLearnedRules();
        assertThat(rules.stream().map(AiSkillLearnedRule::getId).toList()).containsExactly(firstId, thirdId).inOrder();
        assertThat(texts(proposal.after())).containsExactly("Answer in German.", "Prefer pytest for tests.").inOrder();
    }

    @Test
    void aSurvivingRuleKeepsItsIdCreationTimeAndSource() throws Exception {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        AiSkillLearnedRule original = skill.getLearnedRules().get(0);
        String reply = "{\"content\": \"Use snake_case.\", \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1]}]}";

        Proposal proposal = AiSkillShortener.propose(skill, replying(reply));

        AiSkillLearnedRule kept = proposal.after().getLearnedRules().get(0);
        assertThat(kept.getId()).isEqualTo(original.getId());
        assertThat(kept.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(kept.getSource()).isEqualTo(original.getSource());
        assertThat(kept.getText()).isEqualTo("Answer in German.");
    }

    @Test
    void theProposalChangesNoStoredSkill() throws Exception {
        AiSkill skill = userSkill(TEXT, "Always answer in German.", "Please answer in German, always.");
        String reply = "{\"content\": \"Use snake_case.\", \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1, 2]}]}";

        Proposal proposal = AiSkillShortener.propose(skill, replying(reply));

        assertThat(skill.getContent()).isEqualTo(TEXT);
        assertThat(texts(skill)).containsExactly("Always answer in German.", "Please answer in German, always.").inOrder();
        assertThat(proposal.before().getContent()).isEqualTo(TEXT);
    }

    @Test
    void anEntryWithoutTheNumbersOfItsRulesIsRefusedBecauseItWouldAddARule() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        String reply = "{\"content\": \"Use snake_case.\", \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1]},"
            + " {\"text\": \"Use tabs.\"}]}";

        assertRefused(skill, reply, Problem.MALFORMED_REPLY);
    }

    @Test
    void aRuleNumberOutsideTheListIsRefused() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        String reply = "{\"content\": \"Use snake_case.\", \"rules\": [{\"text\": \"Answer in German.\", \"from\": [2]}]}";

        assertRefused(skill, reply, Problem.MALFORMED_REPLY);
    }

    @Test
    void aRuleNumberUsedTwiceIsRefused() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.", "Prefer pytest.");
        String reply = "{\"content\": \"Use snake_case.\", \"rules\": ["
            + "{\"text\": \"Answer in German.\", \"from\": [1]}, {\"text\": \"Use pytest.\", \"from\": [1, 2]}]}";

        assertRefused(skill, reply, Problem.MALFORMED_REPLY);
    }

    @Test
    void anEmptyTextIsRefusedInsteadOfErasingTheSkill() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");

        assertRefused(skill, "{\"content\": \"  \", \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1]}]}",
            Problem.MALFORMED_REPLY);
    }

    @Test
    void aReplyThatIsNotJsonIsMalformed() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");

        assertRefused(skill, "Here is a shorter version: use snake_case.", Problem.MALFORMED_REPLY);
    }

    @Test
    void aMissingOrBlankReplyHasNoReply() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");

        assertRefused(skill, null, Problem.NO_REPLY);
        assertRefused(skill, "   ", Problem.NO_REPLY);
    }

    @Test
    void aReplyInACodeFenceIsAccepted() throws Exception {
        AiSkill skill = userSkill(TEXT);
        String reply = "Here you go:\n```json\n{\"content\": \"Use snake_case. Keep functions short.\"}\n```";

        Proposal proposal = AiSkillShortener.propose(skill, replying(reply));

        assertThat(proposal.after().getContent()).isEqualTo("Use snake_case. Keep functions short.");
    }

    @Test
    void aLongerReplyIsRefused() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        String reply = "{\"content\": \"Use snake_case for every Python name, and keep every function short, documented,"
            + " tested and reviewed by a second person before it is merged into the main branch.\","
            + " \"rules\": [{\"text\": \"Answer in German.\", \"from\": [1]}]}";

        assertRefused(skill, reply, Problem.NOT_SHORTER);
    }

    @Test
    void aReplyThatChangesNothingIsRefused() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        String reply = "{\"content\": \"" + TEXT + "\", \"rules\": [{\"text\": \"Always answer in German.\", \"from\": [1]}]}";

        assertRefused(skill, reply, Problem.NOTHING_TO_CHANGE);
    }

    @Test
    void aSecretInTheReplyIsRefusedWithTheLineAndKindButNotTheValue() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        String reply = "{\"content\": \"Use snake_case.\\npassword=hunter2hunter2; # db\", \"rules\": []}";

        ShortenException thrown = expectThrows(ShortenException.class,
            () -> AiSkillShortener.propose(skill, replying(reply)));

        assertThat(thrown.problem()).isEqualTo(Problem.SECRET);
        assertThat(thrown.getMessage()).contains("2");
        assertThat(thrown.getMessage()).doesNotContain("hunter2");
    }

    @Test
    void anAssistantFailureReachesTheCallerUnchanged() {
        AiSkill skill = userSkill(TEXT, "Always answer in German.");
        Assistant offline = (systemPrompt, userPrompt) -> {
            throw new IOException("offline");
        };

        IOException thrown = expectThrows(IOException.class, () -> AiSkillShortener.propose(skill, offline));

        assertThat(thrown).hasMessageThat().isEqualTo("offline");
    }

    @Test
    void aStoredChangeToTheTextMakesTheProposalStale() {
        AiSkill proposedFrom = userSkill(TEXT, "Always answer in German.");
        AiSkill stored = new AiSkill(proposedFrom);
        stored.setContent(TEXT + " Added meanwhile.");

        assertThat(AiSkillShortener.stillMatches(proposedFrom, stored)).isFalse();
    }

    @Test
    void aRuleAddedMeanwhileDoesNotMakeTheProposalStale() {
        AiSkill proposedFrom = userSkill(TEXT, "Always answer in German.");
        AiSkill stored = new AiSkill(proposedFrom);
        stored.getLearnedRules().add(AiSkillLearnedRule.create("Use tabs.", AiSkillLearnedRule.SOURCE_CHAT));

        assertThat(AiSkillShortener.stillMatches(proposedFrom, stored)).isTrue();
    }

    @Test
    void aRuleRemovedOrChangedMeanwhileMakesTheProposalStale() {
        AiSkill proposedFrom = userSkill(TEXT, "Always answer in German.", "Prefer pytest.");
        AiSkill removed = new AiSkill(proposedFrom);
        removed.getLearnedRules().remove(0);
        AiSkill changed = new AiSkill(proposedFrom);
        changed.getLearnedRules().get(0).setText("Always answer in English.");

        assertThat(AiSkillShortener.stillMatches(proposedFrom, removed)).isFalse();
        assertThat(AiSkillShortener.stillMatches(proposedFrom, changed)).isFalse();
    }

    @Test
    void onlyTheUsersOwnAndKorttyCreatedSkillsHaveTheirTextShortened() {
        assertThat(AiSkillShortener.shortensText(userSkill(TEXT))).isTrue();
        assertThat(AiSkillShortener.shortensText(builtinSkill(TEXT))).isFalse();
    }

    private static final String MERGE_REPLY =
        "{\"rules\": [{\"text\": \"Answer in German, use pytest.\", \"from\": [1, 2]}]}";

    @Test
    void aRuleAddedAfterTheProposalSurvivesTheSave() throws Exception {
        AiSkill skill = userSkill(TEXT, "Always answer in German.", "Prefer pytest for tests.");
        Proposal proposal = AiSkillShortener.propose(skill, replying(MERGE_REPLY));
        AiSkill stored = new AiSkill(skill);
        stored.getLearnedRules().add(AiSkillLearnedRule.create("Use tabs.", AiSkillLearnedRule.SOURCE_CHAT));
        assertThat(AiSkillShortener.stillMatches(proposal.before(), stored)).isTrue();

        AiSkill saved = AiSkillRepository.threeWayMerge(
            AiSkillShortener.mergeBase(List.of(stored), proposal),
            AiSkillShortener.savedSkills(List.of(stored), proposal),
            List.of(stored)).get(0);

        assertThat(texts(saved)).containsExactly("Answer in German, use pytest.", "Use tabs.").inOrder();
    }

    @Test
    void aFieldChangedAfterTheProposalSurvivesTheSave() throws Exception {
        AiSkill skill = userSkill(TEXT, "Always answer in German.", "Prefer pytest for tests.");
        skill.setDescription("Python rules.");
        Proposal proposal = AiSkillShortener.propose(skill, replying(MERGE_REPLY));
        AiSkill stored = new AiSkill(skill);
        stored.setDescription("Python rules for the team.");
        stored.setEnabled(false);
        assertThat(AiSkillShortener.stillMatches(proposal.before(), stored)).isTrue();

        AiSkill saved = AiSkillRepository.threeWayMerge(
            AiSkillShortener.mergeBase(List.of(stored), proposal),
            AiSkillShortener.savedSkills(List.of(stored), proposal),
            List.of(stored)).get(0);

        assertThat(saved.getDescription()).isEqualTo("Python rules for the team.");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(texts(saved)).containsExactly("Answer in German, use pytest.").inOrder();
    }

    @Test
    void aReplyThatShowsTheMaskOfAHiddenValueIsRefusedAsMalformed() {
        AiSkill skill = userSkill("Secret: rotate the keys weekly. Keep the logs short.");
        assertRefused(skill, "{\"content\": \"Secret: *** weekly. Keep the logs short.\"}", Problem.MALFORMED_REPLY);
    }

    @Test
    void aMaskTheSkillAlreadyHoldsIsNotRefused() throws Exception {
        AiSkill skill = userSkill("Use *** as the placeholder for the hidden value. Keep the output short and plain.");
        Proposal proposal = AiSkillShortener.propose(skill,
            replying("{\"content\": \"Use *** as the placeholder. Keep the output short.\"}"));
        assertThat(proposal.textChanged()).isTrue();
    }
}
