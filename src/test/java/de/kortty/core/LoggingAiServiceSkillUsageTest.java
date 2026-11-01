package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.stats.SkillStatsReport;
import de.kortty.stats.SkillStatsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The request path of the statistics: the skill block is built inside the delegate call, on the calling thread, which the
 * {@link LoggingAiService} scope covers. Successful requests are counted once; failed ones and previews are not.
 */
public class LoggingAiServiceSkillUsageTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private SkillStatsStore store;

    @BeforeMethod
    void recordIntoATemporaryStore() throws IOException {
        Path file = Files.createTempDirectory("kortty-logging-usage-").resolve(SkillStatsStore.FILE_NAME);
        store = new SkillStatsStore(file, CLOCK);
        store.setRecordingGate(() -> true);
        SkillStatsStore.replaceInstanceForTesting(store);
    }

    @AfterMethod
    void restoreClosedStore() throws IOException {
        Path file = Files.createTempDirectory("kortty-logging-usage-off-").resolve(SkillStatsStore.FILE_NAME);
        SkillStatsStore.replaceInstanceForTesting(new SkillStatsStore(file, CLOCK));
    }

    @Test
    void theSkillBlockBuiltOnTheCallingThreadDuringASuccessfulCallIsCounted() throws Exception {
        AiSkillPromptSupport support = support(List.of(skill("perl", "Perl")), false);

        wrapped((skills, system, user) -> {
            skills.appendAgentSkills(system, user);
            return new AiExecutionResult("ok", null);
        }, support).executePrompt("base", "task");

        assertThat(row("perl").automatic()).isEqualTo(1L);
    }

    @Test
    void aRetryInsideOneRequestCountsTheSkillOnce() throws Exception {
        AiSkillPromptSupport support = support(List.of(skill("perl", "Perl")), false);

        wrapped((skills, system, user) -> {
            skills.appendAgentSkills(system, user);
            skills.appendAgentSkills(system, user);
            return new AiExecutionResult("ok", null);
        }, support).executePrompt("base", "task");

        assertThat(row("perl").automatic()).isEqualTo(1L);
    }

    @Test
    void aFailedRequestCountsNothing() {
        AiSkillPromptSupport support = support(List.of(skill("perl", "Perl")), false);

        expectThrows(IOException.class, () -> wrapped((skills, system, user) -> {
            skills.appendAgentSkills(system, user);
            throw new IOException("the provider rejected the request");
        }, support).executePrompt("base", "task"));

        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    @Test
    void aSkillPinnedToTheConnectionIsCountedAsPinned() throws Exception {
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false, List.of(skill("perl", "Perl")),
            java.util.Set.of("perl"), null, null);

        wrapped((skills, system, user) -> {
            skills.appendAgentSkills(system, user);
            return new AiExecutionResult("ok", null);
        }, support).executePrompt("base", "task");

        assertThat(row("perl").automatic()).isEqualTo(0L);
        assertThat(row("perl").pinned()).isEqualTo(1L);
    }

    @Test
    void aNestedRequestOfTheDelegateCountsItsSkillsOnceThroughTheOuterRequest() throws Exception {
        AiSkillPromptSupport outerSupport = support(List.of(skill("perl", "Perl")), false);
        AiSkillPromptSupport innerSupport = support(List.of(skill("bash", "Bash")), false);
        AiPromptService inner = wrapped((skills, system, user) -> {
            skills.appendAgentSkills(system, user);
            return new AiExecutionResult("picked", null);
        }, innerSupport);

        wrapped((skills, system, user) -> {
            skills.appendAgentSkills(system, user);
            inner.executePrompt("classify", "task");
            return new AiExecutionResult("ok", null);
        }, outerSupport).executePrompt("base", "task");

        assertThat(row("perl").automatic()).isEqualTo(1L);
        assertThat(row("bash").automatic()).isEqualTo(1L);
    }

    @Test
    void aPreviewOfTheSkillBlockIsNotCounted() throws Exception {
        AiSkillPromptSupport support = support(List.of(skill("perl", "Perl")), false);

        wrapped((skills, system, user) -> {
            skills.buildAgentSkillBlock(system, user);
            skills.buildChatSkillBlock();
            return new AiExecutionResult("ok", null);
        }, support).executePrompt("base", "task");

        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    private AiPromptService wrapped(Script script, AiSkillPromptSupport support) {
        return (AiPromptService) LoggingAiService.wrap(new ScriptedService(script, support), profile(),
            "qwen2.5-coder-7b", AiReasoningEffort.MEDIUM);
    }

    private SkillStatsReport.Row row(String skillId) {
        return store.report(List.of(), range()).rows().stream()
            .filter(row -> row.skillId().equals(skillId))
            .findFirst()
            .orElseThrow();
    }

    private static SkillStatsReport.Range range() {
        return new SkillStatsReport.Range(SkillStatsReport.Granularity.DAY, 30);
    }

    private static AiSkillPromptSupport support(List<AiSkill> skills, boolean autoDetection) {
        return new AiSkillPromptSupport(true, autoDetection, skills);
    }

    private static AiSkill skill(String id, String name) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setContent("Follow the " + name + " conventions.");
        skill.setTarget(AiSkillTarget.AGENT);
        skill.setEnabled(true);
        return skill;
    }

    private static AiProfile profile() {
        AiProfile profile = new AiProfile();
        profile.setName("Local Coding");
        profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        profile.setEmbeddedModelId("qwen2.5-coder-7b");
        return profile;
    }

    /** What a prompt does while it is sent: it may build skill blocks with the support it was given. */
    @FunctionalInterface
    private interface Script {
        AiExecutionResult run(AiSkillPromptSupport skills, String system, String user) throws Exception;
    }

    private static final class ScriptedService implements AiPromptService {
        private final Script script;
        private final AiSkillPromptSupport support;

        ScriptedService(Script script, AiSkillPromptSupport support) {
            this.script = script;
            this.support = support;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) throws Exception {
            return script.run(support, "", "");
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
            return script.run(support, systemPrompt, userPrompt);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
            return script.run(support, systemPrompt, userPrompt);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
