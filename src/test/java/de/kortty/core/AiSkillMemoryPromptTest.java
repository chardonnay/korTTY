package de.kortty.core;

import de.kortty.memory.MemoryBlockBuilder;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiPromptPreset;
import de.kortty.model.AiProfile;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/** Memory in the chat prompt: placement, switches, action rule, redaction, selection and the token preview. */
class AiSkillMemoryPromptTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String TOKEN = "AKIAIOSFODNN7EXAMPLE";
    private static final String RULE = "Always answer in short sentences.";

    private Path root;
    private MemoryStore store;
    private MemoryService service;

    @BeforeMethod
    void useTemporaryMemory() throws IOException {
        root = Files.createTempDirectory("kortty-skill-memory-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
        service = new MemoryService(store, name -> null);
        service.setAiMemoryAllowedSupplier(() -> true);
        service.setUserMemoryModeSupplier(() -> de.kortty.policy.UserMemoryMode.EDITABLE);
        MemoryService.replaceInstanceForTesting(service);
    }

    /** Resets the application's service to the lazily created default, the state before the test. */
    @AfterMethod(alwaysRun = true)
    void restoreMemory() throws IOException {
        MemoryService.replaceInstanceForTesting(null);
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void chatPromptCarriesSkillsThenMemoryThenRagAndPreset() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of(skill("Shell", "Use short examples.")));

        String prompt = AiPromptPipeline.appendAfterSkills(
            support.appendChatSkills("Base rules.", askWithRag()), askWithRag());

        assertThat(prompt).startsWith("Base rules.");
        assertThat(prompt.indexOf("User-defined KorTTY AI skills"))
            .isLessThan(prompt.indexOf(MemoryBlockBuilder.OPEN_TAG));
        assertThat(prompt.indexOf(MemoryBlockBuilder.OPEN_TAG)).isLessThan(prompt.indexOf("<retrieved_context>"));
        assertThat(prompt.indexOf("<retrieved_context>")).isLessThan(prompt.indexOf("<kortty_model_preset>"));
        assertThat(prompt).contains(RULE);
    }

    @Test
    void theFullChatPromptIsByteStableAndCarriesTheMemoryOnce() throws IOException {
        writeGlobalMemory(RULE);
        AiProfile profile = cloudProfile();
        List<AiSkill> skills = List.of(skill("Shell", "Use short examples."));

        String first = AiPromptPipeline.appendAfterSkills(
            supportWithMemory(profile, skills).appendChatSkills("Base rules.", askWithRag()), askWithRag());
        String second = AiPromptPipeline.appendAfterSkills(
            supportWithMemory(profile, skills).appendChatSkills("Base rules.", askWithRag()), askWithRag());

        assertThat(second).isEqualTo(first);
        assertThat(count(first, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
    }

    @Test
    void memoryIsSentWhenSkillsAreSwitchedOff() throws IOException {
        writeGlobalMemory(RULE);
        AiSkill skill = skill("Shell", "Use short examples.");
        AiSkillPromptSupport globallyOff = new AiSkillPromptSupport(false, false, List.of(skill), Set.of(),
            memorySource(cloudProfile()), cloudProfile());

        String prompt = globallyOff.appendChatSkills("Base rules.", ask());

        assertThat(prompt).contains(RULE);
        assertThat(prompt).doesNotContain("Use short examples.");
    }

    @Test
    void memoryIsSentWhenTheRequestTurnsSkillsOff() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of(skill("Shell", "Use short examples.")));
        AiRequest withoutSkills = new AiRequest(AiAction.ASK, "selected", "box", "en", "question", null, false,
            AiPromptPreset.GENERIC, null, null, null, null, null);

        String prompt = support.appendChatSkills("Base rules.", withoutSkills);

        assertThat(prompt).contains(RULE);
        assertThat(prompt).doesNotContain("Use short examples.");
    }

    @Test
    void skillsAreSentWhenMemoryIsSwitchedOff() throws IOException {
        writeGlobalMemory(RULE);
        service.setEnabledSupplier(() -> false);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of(skill("Shell", "Use short examples.")));

        String prompt = support.appendChatSkills("Base rules.", ask());

        assertThat(prompt).contains("Use short examples.");
        assertThat(prompt).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
    }

    @Test
    void skillsAreSentWithoutAMemorySource() {
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false,
            List.of(skill("Shell", "Use short examples.")), Set.of(), null, cloudProfile());

        String prompt = support.appendChatSkills("Base rules.", ask());

        assertThat(prompt).contains("Use short examples.");
        assertThat(prompt).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
        assertThat(support.lastMemoryBlock().isEmpty()).isTrue();
    }

    @Test
    void memoryIsWithheldFromActionsThatMustKeepTheirShape() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of());

        for (AiAction action : List.of(AiAction.CORRECT_SNIPPET_SELECTION_TEXT,
            AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT, AiAction.GENERATE_SNIPPET_MERMAID,
            AiAction.APPLY_SNIPPET_IMPROVEMENTS)) {
            AiRequest request = new AiRequest(action, "code", "box", "en");
            assertThat(support.appendChatSkills("Base rules.", request)).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
            assertThat(support.buildChatMemoryBlock(request)).isEmpty();
        }
        assertThat(support.lastMemoryBlock().isEmpty()).isTrue();
    }

    @Test
    void classifierVariantCarriesMemoryToo() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of(skill("Shell", "Use short examples.")));

        String prompt = support.appendChatSkills("Base rules.", ask(),
            (context, skills) -> List.of());

        assertThat(prompt).contains(RULE);
    }

    @Test
    void lastMemoryBlockIsTheBlockOfTheLastSentChatRequest() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of());

        support.appendChatSkills("Base rules.", ask());
        assertThat(support.lastMemoryBlock().text()).contains(RULE);

        writeGlobalMemory(RULE + "\nSecond rule.");
        support.appendChatSkills("Base rules.", ask());
        assertThat(support.lastMemoryBlock().text()).contains("Second rule.");

        // A withheld action does not replace the block of the last request that used memory.
        support.appendChatSkills("Base rules.", new AiRequest(AiAction.TRANSLATE_SNIPPET_SELECTION_TEXT,
            "code", "box", "en"));
        assertThat(support.lastMemoryBlock().text()).contains("Second rule.");
    }

    @Test
    void agentPromptsNeverGetMemory() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of(skill("Shell", "Use short examples.")));

        assertThat(support.appendAgentSkills("Agent rules.")).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
        assertThat(support.appendAgentSkills("Agent rules.", "list files")).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
        assertThat(support.appendAgentSkills("Agent rules.", "list files",
            (context, skills) -> List.of())).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
        assertThat(support.buildAgentSkillBlock()).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
    }

    @Test
    void aSkillTokenIsMaskedForACloudProfile() {
        AiSkill skill = skill("Keys", "Use the key " + TOKEN + " in the demo.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false, List.of(skill), Set.of(), null,
            cloudProfile());

        String prompt = support.appendChatSkills("Base rules.", ask());

        assertThat(prompt).contains("Use the key");
        assertThat(prompt).doesNotContain(TOKEN.substring(4));
    }

    @Test
    void anAgentSkillTokenIsMaskedForACloudProfile() {
        AiSkill skill = skill("Keys", "Use the key " + TOKEN + " in the demo.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false, List.of(skill), Set.of(), null,
            cloudProfile());

        String prompt = support.appendAgentSkills("Base rules.", "use the key");

        assertThat(prompt).contains("Use the key");
        assertThat(prompt).doesNotContain(TOKEN.substring(4));
    }

    @Test
    void aSkillTokenIsKeptForAnEmbeddedProfile() {
        AiProfile embedded = new AiProfile();
        embedded.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        AiSkill skill = skill("Keys", "Use the key " + TOKEN + " in the demo.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false, List.of(skill), Set.of(), null, embedded);

        assertThat(support.appendChatSkills("Base rules.", ask())).contains(TOKEN);
    }

    @Test
    void aMemoryTokenIsMaskedForACloudProfile() {
        service.setOrganizationMemorySupplier(() -> List.of(new OrganizationRule("Keys", "Staging key " + TOKEN + ".")));
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of());

        String prompt = support.appendChatSkills("Base rules.", ask());

        assertThat(prompt).contains(MemoryBlockBuilder.OPEN_TAG);
        assertThat(prompt).contains("Staging key");
        assertThat(prompt).doesNotContain(TOKEN.substring(4));
    }

    @Test
    void theSelectorIgnoresMemoryWords() {
        AiSkill perl = skill("Perl Style", "Use strict Perl.");
        perl.setTags(List.of("perl"));
        perl.setDescription("Perl guidance.");
        AiSkillRelevanceSelector selector = new AiSkillRelevanceSelector(true, true, List.of(perl));
        String memoryOnly = "Base prompt.\n" + MemoryBlockBuilder.OPEN_TAG + "\nAlways use perl for scripts.\n"
            + MemoryBlockBuilder.CLOSE_TAG;

        assertThat(selector.selectAgentSkillsLocal(memoryOnly, "list the files")).isEmpty();
        // Control: the same words in the request itself do select the skill.
        assertThat(selector.selectAgentSkillsLocal("Base prompt.", "write perl for the files")).hasSize(1);
    }

    @Test
    void theTokenPreviewCountsTheMemoryOfTheConnection() throws IOException {
        writeGlobalMemory(RULE);
        store.write(MemoryScope.connection("conn-1"), store.read(MemoryScope.connection("conn-1")).sha256(),
            "Host note: the database lives on port 5433.\n", MemoryStore.WriteReason.USER_SAVE, null);
        AiRequest request = ask();
        AiProfile profile = cloudProfile();

        long withoutMemory = AiTokenCounter.countRequestTokens(request, AiTokenizerType.ESTIMATE,
            AiSkillPromptSupport.fromSettings(null, null, profile, null));
        long globalOnly = AiTokenCounter.countRequestTokens(request, AiTokenizerType.ESTIMATE,
            AiSkillPromptSupport.fromSettings(null, null, profile, memorySource(profile)));
        long withConnection = AiTokenCounter.countRequestTokens(request, AiTokenizerType.ESTIMATE,
            AiSkillPromptSupport.fromSettings(null, null, profile, new AiMemoryPromptSource("conn-1", profile, 1500)));

        assertThat(globalOnly).isGreaterThan(withoutMemory);
        assertThat(withConnection).isGreaterThan(globalOnly);
    }

    @Test
    void theTokenPreviewDoesNotCountAsASend() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of());

        AiTokenCounter.countRequestTokens(ask(), AiTokenizerType.ESTIMATE, support);

        assertThat(support.lastMemoryBlock().isEmpty()).isTrue();
        assertThat(support.buildChatMemoryBlock(ask())).contains(RULE);
    }

    @Test
    void theMemoryIsReadWhenThePromptIsBuilt() throws IOException {
        writeGlobalMemory(RULE);
        AiSkillPromptSupport support = supportWithMemory(cloudProfile(), List.of());
        assertThat(support.appendChatSkills("Base rules.", ask())).contains(RULE);

        writeGlobalMemory("Edited rule.");

        String next = support.appendChatSkills("Base rules.", ask());
        assertThat(next).contains("Edited rule.");
        assertThat(next).doesNotContain(RULE);
    }

    @Test
    void theSnippetPickerCarriesTheMemoryOfItsConnection() throws IOException {
        writeGlobalMemory(RULE);
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        AiSkillPromptSupport picker = AiSkillPromptSupport.fromSettingsForSnippetSelection(
            settings, List.of(), List.of(), cloudProfile(), memorySource(cloudProfile()));

        assertThat(picker.appendChatSkills("Base rules.", ask())).contains(RULE);
    }

    @Test
    void theSnippetPickerWithoutAMemorySourceSendsNoMemory() throws IOException {
        writeGlobalMemory(RULE);
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        AiSkillPromptSupport picker = AiSkillPromptSupport.fromSettingsForSnippetSelection(
            settings, List.of(), List.of(), cloudProfile());

        assertThat(picker.appendChatSkills("Base rules.", ask())).doesNotContain(MemoryBlockBuilder.OPEN_TAG);
    }

    private void writeGlobalMemory(String text) throws IOException {
        MemoryStore.Snapshot current = store.read(MemoryScope.global());
        store.write(MemoryScope.global(), current.sha256(), text + "\n", MemoryStore.WriteReason.USER_SAVE, null);
    }

    private AiMemoryPromptSource memorySource(AiProfile profile) {
        return new AiMemoryPromptSource(null, profile, 1500);
    }

    private AiSkillPromptSupport supportWithMemory(AiProfile profile, List<AiSkill> skills) {
        return new AiSkillPromptSupport(true, false, skills, Set.of(), memorySource(profile), profile);
    }

    private static AiProfile cloudProfile() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        return profile;
    }

    private static AiSkill skill(String name, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(name.toLowerCase(java.util.Locale.ROOT));
        skill.setName(name);
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(content);
        return skill;
    }

    private static AiRequest ask() {
        return new AiRequest(AiAction.ASK, "selected", "box", "en", "question");
    }

    private static AiRequest askWithRag() {
        return new AiRequest(AiAction.ASK, "selected", "box", "en", "question", null, true,
            AiPromptPreset.QWEN, "<retrieved_context>\nA fact.\n</retrieved_context>", null, null, null, null);
    }

    private static int count(String text, String part) {
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }
}
