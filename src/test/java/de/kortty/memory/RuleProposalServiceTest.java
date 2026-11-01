package de.kortty.memory;

import de.kortty.core.AiSkillMarkdownCodec;
import de.kortty.core.AiSkillRepository;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.core.BuiltinAiSkillSupport;
import de.kortty.core.BuiltinAiSkillSupport.BuiltinSkillState;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.memory.SavedRule.UndoOutcome;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiSkillOrigin;
import de.kortty.policy.UserMemoryMode;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class RuleProposalServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String USER_TEXT = "Meine eigenen Notizen.\n\nNoch eine Zeile.\n";
    private static final BuiltinAiSkillCatalog CATALOG = BuiltinAiSkillCatalog.load();

    private Path root;
    private SteppingClock clock;
    private MemoryStore store;
    private MemoryService memory;
    private GlobalSettingsManager settings;
    private AiSkillRepository skills;
    private RuleTargetPolicy policy;
    private RuleProposalService service;

    @BeforeMethod
    void createServices() throws IOException {
        root = Files.createTempDirectory("kortty-rule-service-");
        clock = new SteppingClock(NOW);
        store = new MemoryStore(root.resolve("memory"), clock);
        memory = new MemoryService(store, name -> null);
        memory.setAiMemoryAllowedSupplier(() -> true);
        memory.setUserMemoryModeSupplier(() -> UserMemoryMode.EDITABLE);
        Files.createDirectories(root.resolve("config"));
        settings = new GlobalSettingsManager(root.resolve("config"));
        skills = AiSkillRepository.onCallingThread(settings);
        policy = RuleTargetPolicy.unrestricted();
        service = new RuleProposalService(memory, skills, () -> policy, clock, Runnable::run);
    }

    @AfterMethod(alwaysRun = true)
    void deleteFiles() throws IOException {
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
    void memoryAppendLeavesTheUserTextUntouched() throws Exception {
        writeGlobalMemory(USER_TEXT);
        String userBefore = MemoryFormat.userSegments(USER_TEXT);

        SavedRule saved = confirm("Antworte immer kurz", RuleTarget.global());

        String after = readGlobalMemory();
        assertThat(MemoryFormat.userSegments(after)).isEqualTo(userBefore);
        assertThat(after).contains("Meine eigenen Notizen.");
        List<MemoryDocument.KorttyEntry> entries = MemoryFormat.parse(after).entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).body()).isEqualTo("- Antworte immer kurz");
        assertThat(entries.get(0).source()).isEqualTo("chat");
        assertThat(saved.target()).isEqualTo(RuleTarget.global());
        assertThat(saved.text()).isEqualTo("Antworte immer kurz");
    }

    @Test
    void connectionRuleGoesToThatConnectionsFile() throws Exception {
        confirm("Auf dieser Verbindung zuerst Snapshot anlegen", RuleTarget.connection("conn-1"));

        Path file = store.fileOf(MemoryScope.connection("conn-1"));
        assertThat(MemoryFormat.parse(Files.readString(file)).entries()).hasSize(1);
        assertThat(Files.exists(store.fileOf(MemoryScope.global()))).isFalse();
    }

    @Test
    void aSecretIsRefusedAndNothingIsWritten() throws Exception {
        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Passwort ist hunter2hunter2",
            RuleSource.CHAT), "Passwort ist hunter2hunter2", RuleTarget.global()));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.SECRET);
        assertThat(refusal.userMessage()).doesNotContain("hunter2hunter2");
        assertThat(Files.exists(store.fileOf(MemoryScope.global()))).isFalse();
    }

    @Test
    void textOverTheLimitIsRefusedAndTheLimitItselfIsAccepted() throws Exception {
        String tooLong = "a".repeat(RuleProposalService.MAX_RULE_CHARACTERS + 1);
        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of(tooLong, RuleSource.CHAT), tooLong,
            RuleTarget.global()));
        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.TOO_LONG);
        assertThat(Files.exists(store.fileOf(MemoryScope.global()))).isFalse();

        String atLimit = "b".repeat(RuleProposalService.MAX_RULE_CHARACTERS);
        assertThat(service.confirm(RuleProposal.of(atLimit, RuleSource.CHAT), atLimit, RuleTarget.global()).get())
            .isNotNull();
    }

    @Test
    void anEmptyRuleIsRefused() throws Exception {
        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("  ", RuleSource.CHAT), "   \n ",
            RuleTarget.global()));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.EMPTY);
    }

    @Test
    void aReadOnlyMemoryIsRefusedBeforeAnyWrite() throws Exception {
        policy = new RuleTargetPolicy(false, true, 1500);

        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Antworte kurz", RuleSource.CHAT),
            "Antworte kurz", RuleTarget.global()));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.READ_ONLY);
        assertThat(Files.exists(store.fileOf(MemoryScope.global()))).isFalse();
    }

    @Test
    void theStoreAlsoRefusesWhenThePolicyViewWasStale() throws Exception {
        memory.setUserMemoryModeSupplier(() -> UserMemoryMode.READ_ONLY);

        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Antworte kurz", RuleSource.CHAT),
            "Antworte kurz", RuleTarget.global()));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.READ_ONLY);
        assertThat(Files.exists(store.fileOf(MemoryScope.global()))).isFalse();
    }

    @Test
    void theSameRuleTwiceInOneMemoryIsRefusedTheSecondTime() throws Exception {
        confirm("Antworte immer kurz", RuleTarget.global());

        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Antworte immer kurz", RuleSource.CHAT),
            "Antworte immer kurz", RuleTarget.global()));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.DUPLICATE);
    }

    @Test
    void skillTargetsAreRefusedWhenSkillCreationIsNotAllowed() throws Exception {
        AiSkill deploy = addUserSkill("Deploy notes", "Keep the steps short.");
        policy = new RuleTargetPolicy(true, false, 1500);

        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Deploy: testen", RuleSource.CHAT),
            "Deploy: testen", RuleTarget.skill(deploy.getId())));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.SKILL_CREATION_NOT_ALLOWED);
    }

    @Test
    void noTargetIsRefused() throws Exception {
        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Antworte kurz", RuleSource.CHAT),
            "Antworte kurz", RuleTarget.none()));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.NO_TARGET);
    }

    @Test
    void learnedRuleIsAddedToAUserSkillWithoutChangingItsContent() throws Exception {
        AiSkill deploy = addUserSkill("Deploy notes", "# Deploy\n\nKeep the steps short.");

        SavedRule saved = service.confirm(RuleProposal.of("Deploy: Immer erst im Staging testen", RuleSource.CHAT),
            "Deploy: Immer erst im Staging testen", RuleTarget.skill(deploy.getId())).get();

        AiSkill stored = storedSkill(deploy.getId());
        assertThat(stored.getContent()).isEqualTo("# Deploy\n\nKeep the steps short.");
        assertThat(stored.getName()).isEqualTo("Deploy notes");
        assertThat(stored.getTags()).containsExactly("deploy");
        assertThat(stored.getLearnedRules()).hasSize(1);
        AiSkillLearnedRule rule = stored.getLearnedRules().get(0);
        assertThat(rule.getText()).isEqualTo("Deploy: Immer erst im Staging testen");
        assertThat(rule.getSource()).isEqualTo(AiSkillLearnedRule.SOURCE_CHAT);
        assertThat(rule.isAcknowledged()).isFalse();
        assertThat(saved.target()).isEqualTo(RuleTarget.skill(deploy.getId()));
    }

    @Test
    void aDuplicateLearnedRuleIsRefusedForTheSameSkill() throws Exception {
        AiSkill deploy = addUserSkill("Deploy notes", "# Deploy");
        service.confirm(RuleProposal.of("Deploy: testen", RuleSource.CHAT), "Deploy: testen",
            RuleTarget.skill(deploy.getId())).get();

        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Deploy: testen", RuleSource.CHAT),
            "Deploy: testen", RuleTarget.skill(deploy.getId())));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.DUPLICATE);
        assertThat(storedSkill(deploy.getId()).getLearnedRules()).hasSize(1);
    }

    @Test
    void aMissingSkillIsRefused() throws Exception {
        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Deploy: testen", RuleSource.CHAT),
            "Deploy: testen", RuleTarget.skill("no-such-skill")));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.SKILL_MISSING);
    }

    @Test
    void newKorttySkillHasTheKorttyFieldsTagsAndTheRule() throws Exception {
        SavedRule saved = service.confirm(RuleProposal.of("Elixir: Module mit @moduledoc dokumentieren",
                RuleSource.CHAT), "Elixir: Module mit @moduledoc dokumentieren",
            RuleTarget.newSkill("elixir", "Elixir (korTTY)")).get();

        AiSkill created = storedSkillNamed("Elixir (korTTY)");
        assertThat(created.getOrigin()).isEqualTo(AiSkillOrigin.KORTTY);
        assertThat(created.isCreatedByKortty()).isTrue();
        assertThat(created.getCreatedAt()).isEqualTo("2026-11-02");
        assertThat(created.isOriginAcknowledged()).isFalse();
        assertThat(created.getTags()).containsExactly("elixir");
        assertThat(created.getTarget().name()).isEqualTo("BOTH");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.isBuiltin()).isFalse();
        assertThat(created.getContent()).contains("Elixir");
        assertThat(created.getLearnedRules()).hasSize(1);
        assertThat(created.getLearnedRules().get(0).getText()).isEqualTo("Elixir: Module mit @moduledoc dokumentieren");
        assertThat(saved.target()).isEqualTo(RuleTarget.newSkill("elixir", "Elixir (korTTY)"));
    }

    @Test
    void aNewSkillForABuiltInTopicIsRefused() throws Exception {
        RuleSaveException refusal = refusalOf(service.confirm(RuleProposal.of("Perl: state", RuleSource.CHAT),
            "Perl: state", RuleTarget.newSkill("perl", "Perl (korTTY)")));

        assertThat(refusal.reason()).isEqualTo(RuleSaveException.Reason.SKILL_EXISTS);
        assertThat(skills.snapshot()).isEmpty();
    }

    @Test
    void builtInPerlSkillKeepsItsUnmodifiedStatusAfterALearnedRule() throws Exception {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        settings.getSettings().setAiSkills(List.of(perl));

        service.confirm(RuleProposal.of("Perl: statische Variablen nur mit state deklarieren", RuleSource.CHAT),
            "Perl: statische Variablen nur mit state deklarieren", RuleTarget.skill(perl.getId())).get();

        AiSkill stored = storedSkill(perl.getId());
        assertThat(stored.getLearnedRules()).hasSize(1);
        assertThat(stored.getContent()).isEqualTo(perl.getContent());
        assertThat(BuiltinAiSkillSupport.statusOf(stored, skills.snapshot(), CATALOG).state())
            .isEqualTo(BuiltinSkillState.BUILTIN_UNMODIFIED);
    }

    @Test
    void aLearnedRuleOfABuiltInSurvivesAResetToTheDeliveredText() throws Exception {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        settings.getSettings().setAiSkills(List.of(perl));
        service.confirm(RuleProposal.of("Perl: state verwenden", RuleSource.CHAT), "Perl: state verwenden",
            RuleTarget.skill(perl.getId())).get();

        AiSkill stored = storedSkill(perl.getId());
        assertThat(BuiltinAiSkillSupport.reset(stored)).isTrue();

        assertThat(stored.getLearnedRules()).hasSize(1);
        assertThat(stored.getLearnedRules().get(0).getText()).isEqualTo("Perl: state verwenden");
    }

    @Test
    void undoRemovesTheMemoryEntryAgainAndKeepsTheUserText() throws Exception {
        writeGlobalMemory(USER_TEXT);
        String userBefore = MemoryFormat.userSegments(USER_TEXT);
        SavedRule saved = confirm("Antworte immer kurz", RuleTarget.global());

        UndoOutcome outcome = saved.undo().get();

        assertThat(outcome).isEqualTo(UndoOutcome.UNDONE);
        String after = readGlobalMemory();
        assertThat(MemoryFormat.parse(after).entries()).isEmpty();
        assertThat(MemoryFormat.userSegments(after)).isEqualTo(userBefore);
        assertThat(Files.readString(store.memoryDir().resolve("archive.md"))).contains("Antworte immer kurz");
    }

    @Test
    void undoRemovesOnlyTheLearnedRuleFromAUserSkill() throws Exception {
        AiSkill deploy = addUserSkill("Deploy notes", "# Deploy");
        service.confirm(RuleProposal.of("Deploy: erste Regel", RuleSource.CHAT), "Deploy: erste Regel",
            RuleTarget.skill(deploy.getId())).get();
        SavedRule second = service.confirm(RuleProposal.of("Deploy: zweite Regel", RuleSource.CHAT),
            "Deploy: zweite Regel", RuleTarget.skill(deploy.getId())).get();

        assertThat(second.undo().get()).isEqualTo(UndoOutcome.UNDONE);

        AiSkill stored = storedSkill(deploy.getId());
        assertThat(stored.getContent()).isEqualTo("# Deploy");
        assertThat(stored.getLearnedRules()).hasSize(1);
        assertThat(stored.getLearnedRules().get(0).getText()).isEqualTo("Deploy: erste Regel");
    }

    @Test
    void undoRemovesAJustCreatedKorttySkill() throws Exception {
        SavedRule saved = service.confirm(RuleProposal.of("Elixir: Module dokumentieren", RuleSource.CHAT),
            "Elixir: Module dokumentieren", RuleTarget.newSkill("elixir", "Elixir (korTTY)")).get();

        assertThat(saved.undo().get()).isEqualTo(UndoOutcome.UNDONE);

        assertThat(skills.snapshot()).isEmpty();
    }

    @Test
    void undoAfterTheUserChangedTheNewSkillKeepsTheSkill() throws Exception {
        SavedRule saved = service.confirm(RuleProposal.of("Elixir: Module dokumentieren", RuleSource.CHAT),
            "Elixir: Module dokumentieren", RuleTarget.newSkill("elixir", "Elixir (korTTY)")).get();
        List<AiSkill> base = skills.snapshot();
        List<AiSkill> edited = skills.snapshot();
        edited.get(0).setContent("# Elixir\n\nMy own notes.");
        skills.commit(base, edited);

        assertThat(saved.undo().get()).isEqualTo(UndoOutcome.RULE_REMOVED_SKILL_KEPT);

        AiSkill kept = storedSkillNamed("Elixir (korTTY)");
        assertThat(kept.getContent()).isEqualTo("# Elixir\n\nMy own notes.");
        assertThat(kept.getLearnedRules()).isEmpty();
    }

    @Test
    void undoAfterThirtySecondsReportsExpiredAndKeepsTheRule() throws Exception {
        SavedRule saved = confirm("Antworte immer kurz", RuleTarget.global());
        clock.advance(RuleProposalService.UNDO_WINDOW);

        assertThat(saved.undoable()).isFalse();
        assertThat(saved.undo().get()).isEqualTo(UndoOutcome.EXPIRED);
        assertThat(MemoryFormat.parse(readGlobalMemory()).entries()).hasSize(1);
    }

    @Test
    void undoInsideTheWindowStillWorksAtTwentyNineSeconds() throws Exception {
        SavedRule saved = confirm("Antworte immer kurz", RuleTarget.global());
        clock.advance(Duration.ofSeconds(29));

        assertThat(saved.undoable()).isTrue();
        assertThat(saved.undo().get()).isEqualTo(UndoOutcome.UNDONE);
    }

    @Test
    void undoWorksOnlyOnce() throws Exception {
        SavedRule saved = confirm("Antworte immer kurz", RuleTarget.global());

        assertThat(saved.undo().get()).isEqualTo(UndoOutcome.UNDONE);
        assertThat(saved.undo().get()).isEqualTo(UndoOutcome.ALREADY_UNDONE);
    }

    @Test
    void undoReportsNotFoundWhenTheUserEditedTheEntryAway() throws Exception {
        SavedRule saved = confirm("Antworte immer kurz", RuleTarget.global());
        String edited = readGlobalMemory().replace("Antworte immer kurz", "Antworte immer lang");
        Files.writeString(store.fileOf(MemoryScope.global()), edited);

        assertThat(saved.undo().get()).isEqualTo(UndoOutcome.NOT_FOUND);
        assertThat(readGlobalMemory()).contains("Antworte immer lang");
    }

    private SavedRule confirm(String text, RuleTarget target) throws Exception {
        return service.confirm(RuleProposal.of(text, RuleSource.CHAT), text, target).get();
    }

    private static RuleSaveException refusalOf(CompletableFuture<SavedRule> future) throws InterruptedException {
        ExecutionException failure = expectThrows(ExecutionException.class, future::get);
        return (RuleSaveException) failure.getCause();
    }

    private void writeGlobalMemory(String text) throws IOException {
        Files.createDirectories(store.memoryDir());
        Files.writeString(store.fileOf(MemoryScope.global()), text);
    }

    private String readGlobalMemory() throws IOException {
        return Files.readString(store.fileOf(MemoryScope.global()));
    }

    private AiSkill addUserSkill(String name, String content) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setTags(List.of("deploy"));
        skill.setContent(content);
        List<AiSkill> all = new ArrayList<>(skills.snapshot());
        all.add(skill);
        settings.getSettings().setAiSkills(all);
        return skill;
    }

    private AiSkill storedSkill(String id) {
        return skills.snapshot().stream()
            .filter(skill -> id.equals(skill.getId()))
            .findFirst()
            .orElseThrow();
    }

    private AiSkill storedSkillNamed(String name) {
        Optional<AiSkill> found = skills.snapshot().stream()
            .filter(skill -> name.equals(skill.getName()))
            .findFirst();
        return found.orElseThrow();
    }

    private static AiSkill storedBuiltin(String builtinId) {
        AiSkillMarkdownCodec.BundledAiSkill shipped = CATALOG.byId(builtinId).orElseThrow();
        AiSkill skill = new AiSkill(shipped.skill());
        skill.setBuiltinBaseline(BuiltinAiSkillSupport.baselineOf(shipped));
        return skill;
    }
}
