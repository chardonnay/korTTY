package de.kortty.core;

import de.kortty.memory.MemoryConsentFixture;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiSkillOrigin;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class AiSkillRepositoryTest {

    private static final AiSkillRepository.OwnerThread SAME_THREAD = new AiSkillRepository.OwnerThread() {
        @Override
        public boolean isCurrent() {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }
    };

    private static AiSkill skill(String id, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName("Skill " + id);
        skill.setTags(List.of(id));
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(content);
        return skill;
    }

    private static AiSkillLearnedRule rule(String id, String text) {
        AiSkillLearnedRule rule = AiSkillLearnedRule.create(text, AiSkillLearnedRule.SOURCE_CHAT);
        rule.setId(id);
        return rule;
    }

    private static AiSkill withRules(AiSkill skill, AiSkillLearnedRule... rules) {
        for (AiSkillLearnedRule rule : rules) {
            skill.getLearnedRules().add(rule);
        }
        return skill;
    }

    private static List<String> ids(List<AiSkill> skills) {
        return skills.stream().map(AiSkill::getId).toList();
    }

    private static List<String> ruleIds(AiSkill skill) {
        return skill.getLearnedRules().stream().map(AiSkillLearnedRule::getId).toList();
    }

    private static Path tempDirectory() throws Exception {
        return Files.createTempDirectory("kortty-ai-skill-repository");
    }

    /** Flushes a pending background save into the temporary directory, then removes the directory. */
    private static void cleanUp(GlobalSettingsManager manager, Path dir) throws Exception {
        manager.flushPendingSave();
        Files.deleteIfExists(dir.resolve(GlobalSettingsManager.SETTINGS_FILE));
        Files.deleteIfExists(dir);
    }

    private static AiSkillRepository repository(GlobalSettingsManager manager) {
        return new AiSkillRepository(manager, SAME_THREAD, AiSkillRepository::storableRefusal);
    }

    @Test
    void unchangedSkillTakesTheStoredVersion() {
        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "old")),
            List.of(skill("a", "old")),
            List.of(skill("a", "changed elsewhere")));

        assertThat(ids(merged)).containsExactly("a");
        assertThat(merged.get(0).getContent()).isEqualTo("changed elsewhere");
    }

    @Test
    void changedSkillTakesMyVersion() {
        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "old")),
            List.of(skill("a", "mine")),
            List.of(skill("a", "old")));

        assertThat(merged.get(0).getContent()).isEqualTo("mine");
    }

    @Test
    void skillIRemovedStaysRemovedEvenIfItChangedMeanwhile() {
        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "a"), skill("b", "b")),
            List.of(skill("a", "a")),
            List.of(skill("a", "a"), skill("b", "b changed")));

        assertThat(ids(merged)).containsExactly("a");
    }

    @Test
    void skillDeletedMeanwhileAndUnchangedByMeIsDropped() {
        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "a"), skill("b", "b")),
            List.of(skill("a", "a"), skill("b", "b")),
            List.of(skill("a", "a")));

        assertThat(ids(merged)).containsExactly("a");
    }

    @Test
    void changedSkillDeletedMeanwhileIsKeptAsIEditedIt() {
        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "old")),
            List.of(skill("a", "mine")),
            List.of());

        assertThat(ids(merged)).containsExactly("a");
        assertThat(merged.get(0).getContent()).isEqualTo("mine");
    }

    @Test
    void concurrentAdditionsAreKeptAfterMyOrder() {
        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "a"), skill("b", "b")),
            List.of(skill("b", "b"), skill("a", "a")),
            List.of(skill("a", "a"), skill("b", "b"), skill("c", "c")));

        assertThat(ids(merged)).containsExactly("b", "a", "c").inOrder();
    }

    @Test
    void learnedRuleAddedWhileIEditTheContentIsKept() {
        AiSkill editedByMe = skill("a", "new text from the editor");
        AiSkill storedMeanwhile = withRules(skill("a", "old text"), rule("r1", "Use strict."));

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "old text")),
            List.of(editedByMe),
            List.of(storedMeanwhile));

        assertThat(merged.get(0).getContent()).isEqualTo("new text from the editor");
        assertThat(ruleIds(merged.get(0))).containsExactly("r1");
    }

    @Test
    void aConcurrentContentChangeSurvivesMyRenameOfTheSkill() {
        AiSkill editedByMe = skill("a", "old text");
        editedByMe.setName("Renamed by me");
        AiSkill storedMeanwhile = skill("a", "new text from korTTY");

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "old text")),
            List.of(editedByMe),
            List.of(storedMeanwhile));

        assertThat(merged.get(0).getName()).isEqualTo("Renamed by me");
        assertThat(merged.get(0).getContent()).isEqualTo("new text from korTTY");
    }

    @Test
    void aConcurrentStateChangeSurvivesMyEditOfTheText() {
        AiSkill editedByMe = skill("a", "my new text");
        AiSkill storedMeanwhile = skill("a", "old text");
        storedMeanwhile.setEnabled(false);

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(skill("a", "old text")),
            List.of(editedByMe),
            List.of(storedMeanwhile));

        assertThat(merged.get(0).getContent()).isEqualTo("my new text");
        assertThat(merged.get(0).isEnabled()).isFalse();
    }

    @Test
    void learnedRuleIDeletedStaysDeletedWhileNewerRulesSurvive() {
        AiSkill base = withRules(skill("a", "old"), rule("r1", "Old rule."));
        AiSkill editedByMe = skill("a", "changed by me");
        AiSkill storedMeanwhile = withRules(skill("a", "old"), rule("r1", "Old rule."), rule("r2", "New rule."));

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(base), List.of(editedByMe), List.of(storedMeanwhile));

        assertThat(ruleIds(merged.get(0))).containsExactly("r2");
    }

    @Test
    void unchangedSkillTakesTheStoredRules() {
        AiSkill base = withRules(skill("a", "same"), rule("r1", "Old rule."));
        AiSkill mine = withRules(skill("a", "same"), rule("r1", "Old rule."));
        AiSkill stored = withRules(skill("a", "same"), rule("r1", "Old rule."), rule("r2", "New rule."));

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(List.of(base), List.of(mine), List.of(stored));

        assertThat(ruleIds(merged.get(0))).containsExactly("r1", "r2").inOrder();
    }

    @Test
    void mergeReturnsCopiesNotTheInputObjects() {
        AiSkill stored = skill("a", "stored");

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(List.of(skill("a", "stored")),
            List.of(skill("a", "stored")), List.of(stored));
        merged.get(0).setContent("tampered");

        assertThat(stored.getContent()).isEqualTo("stored");
    }

    @Test
    void equalityCoversOriginAndLearnedRulesNotOnlyTheEditedText() {
        AiSkill plain = skill("a", "same");
        AiSkill withRule = withRules(skill("a", "same"), rule("r1", "Rule."));
        AiSkill withOrigin = skill("a", "same");
        withOrigin.setOrigin(AiSkillOrigin.KORTTY);

        assertThat(AiSkillRepository.sameSkill(plain, skill("a", "same"))).isTrue();
        assertThat(AiSkillRepository.sameSkill(plain, withRule)).isFalse();
        assertThat(AiSkillRepository.sameSkill(plain, withOrigin)).isFalse();
    }

    @Test
    void aConcurrentLearnedRuleSurvivesMySaveWhileIEditTheSameSkillsContent() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "old text")));
            AiSkillRepository repository = repository(manager);
            List<AiSkill> base = repository.snapshot();
            List<AiSkill> editor = repository.snapshot();
            editor.get(0).setContent("text I typed");

            repository.update(skills -> {
                skills.get(0).getLearnedRules().add(rule("r1", "Learned while I typed."));
                return skills;
            }, MemoryConsentFixture.granted()).join();
            AiSkillRepository.Outcome outcome = repository.commit(base, editor);

            assertThat(outcome.changed()).isTrue();
            assertThat(outcome.refusals()).isEmpty();
            AiSkill stored = manager.getSettings().getAiSkills().get(0);
            assertThat(stored.getContent()).isEqualTo("text I typed");
            assertThat(ruleIds(stored)).containsExactly("r1");
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void commitWithoutAnyChangeReportsNoChangeAndNotifiesNobody() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "text")));
            AiSkillRepository repository = repository(manager);
            AtomicInteger notifications = new AtomicInteger();
            repository.addListener(notifications::incrementAndGet);
            List<AiSkill> base = repository.snapshot();

            AiSkillRepository.Outcome outcome = repository.commit(base, repository.snapshot());

            assertThat(outcome.changed()).isFalse();
            assertThat(notifications.get()).isEqualTo(0);
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void updateNotifiesListenersOnlyForAChange() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "text")));
            AiSkillRepository repository = repository(manager);
            AtomicInteger notifications = new AtomicInteger();
            repository.addListener(notifications::incrementAndGet);

            repository.update(skills -> skills, MemoryConsentFixture.granted()).join();
            assertThat(notifications.get()).isEqualTo(0);

            repository.update(skills -> {
                skills.get(0).getLearnedRules().add(rule("r1", "Rule."));
                return skills;
            }, MemoryConsentFixture.granted()).join();
            assertThat(notifications.get()).isEqualTo(1);

        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void updateRefusesAChangedSkillTheValidatorRejectsAndKeepsItsStoredVersion() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "old")));
            AiSkillRepository repository = new AiSkillRepository(manager, SAME_THREAD,
                candidate -> candidate.getContent().contains("password")
                    ? Optional.of("refused " + candidate.getId())
                    : Optional.empty());

            AiSkillRepository.Outcome outcome = repository.update(skills -> List.of(
                skill("a", "old"),
                skill("b", "fine"),
                skill("c", "password=hunter2")), MemoryConsentFixture.granted()).join();

            assertThat(outcome.refusals()).containsExactly("refused c");
            assertThat(ids(outcome.stored())).containsExactly("a", "b").inOrder();
            assertThat(outcome.stored().get(0).getContent()).isEqualTo("old");
            assertThat(outcome.stored().get(1).getContent()).isEqualTo("fine");
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void korttyMayNotChangeTheContentOfAStoredSkill() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "The user's own text.")));
            AiSkillRepository repository = repository(manager);

            AiSkillRepository.Outcome outcome = repository.update(skills -> {
                skills.get(0).setContent("Rewritten by korTTY.");
                return skills;
            }, MemoryConsentFixture.granted()).join();

            assertThat(outcome.changed()).isFalse();
            assertThat(outcome.refusals()).hasSize(1);
            assertThat(outcome.refusals().get(0)).contains("only add learned rules");
            assertThat(outcome.stored().get(0).getContent()).isEqualTo("The user's own text.");
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void korttyMayAddLearnedRulesToAStoredSkillAndCreateANewOne() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "text")));
            AiSkillRepository repository = repository(manager);

            AiSkillRepository.Outcome outcome = repository.update(skills -> {
                skills.get(0).getLearnedRules().add(rule("r1", "Use tabs."));
                skills.add(skill("korttyNew", "Header only."));
                return skills;
            }, MemoryConsentFixture.granted()).join();

            assertThat(outcome.refusals()).isEmpty();
            assertThat(ids(outcome.stored())).containsExactly("a", "korttyNew").inOrder();
            assertThat(ruleIds(outcome.stored().get(0))).containsExactly("r1");
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void oneConsentCoversOneUpdate() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "text")));
            AiSkillRepository repository = repository(manager);
            var consent = MemoryConsentFixture.granted();
            repository.update(skills -> skills, consent).join();

            expectThrows(java.util.concurrent.CompletionException.class,
                () -> repository.update(skills -> skills, consent).join());
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void updateNeedsTheUsersConsent() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            AiSkillRepository repository = repository(manager);

            expectThrows(NullPointerException.class, () -> repository.update(skills -> skills, null));
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void aBackgroundChangeIsSavedAndReadBackAfterARestart() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "text")));
            AiSkillRepository repository = repository(manager);

            repository.update(skills -> {
                skills.get(0).getLearnedRules().add(rule("r1", "Survives a restart."));
                return skills;
            }, MemoryConsentFixture.granted()).join();
            manager.flushPendingSave();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getAiSkills().get(0).getLearnedRules()).hasSize(1);
            assertThat(reloaded.getSettings().getAiSkills().get(0).getLearnedRules().get(0).getText())
                .isEqualTo("Survives a restart.");
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void snapshotsAreCopiesOfTheStoredSkills() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            manager.getSettings().setAiSkills(List.of(skill("a", "stored")));
            AiSkillRepository repository = repository(manager);

            repository.snapshot().get(0).setContent("changed in a copy");

            assertThat(manager.getSettings().getAiSkills().get(0).getContent()).isEqualTo("stored");
        } finally {
            cleanUp(manager, dir);
        }
    }

    @Test
    void writesRefuseToRunOffTheOwnerThread() throws Exception {
        Path dir = tempDirectory();
        GlobalSettingsManager manager = new GlobalSettingsManager(dir);
        try {
            AiSkillRepository repository = new AiSkillRepository(manager,
                new AiSkillRepository.OwnerThread() {
                    @Override
                    public boolean isCurrent() {
                        return false;
                    }

                    @Override
                    public void execute(Runnable task) {
                    }
                },
                AiSkillRepository::storableRefusal);

            expectThrows(IllegalStateException.class, () -> repository.commit(List.of(), List.of()));
        } finally {
            cleanUp(manager, dir);
        }
    }
}
