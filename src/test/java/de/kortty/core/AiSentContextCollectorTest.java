package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** Each request collects what it sends on its own thread, so overlapping requests never share a summary. */
class AiSentContextCollectorTest {

    private static final int OVERLAP_ROUNDS = 25;

    private static MemoryBlock memory(String text) {
        return new MemoryBlock(text, 5, List.of(), 0);
    }

    private static AiSentContext.Skill skill(String name, boolean automatic) {
        return new AiSentContext.Skill(name, name, automatic, 3, 1);
    }

    private static AiSkill aSkill(String id, String name, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(content);
        return skill;
    }

    @Test
    void reportsOutsideAnOpenRequestAreDropped() {
        AiSentContextCollector.reportMemory(memory("<kortty_user_memory>x</kortty_user_memory>"));
        AiSentContextCollector.reportSkill(skill("Skill", true));

        assertThat(AiSentContextCollector.isActive()).isFalse();
        try (AiSentContextCollector.Scope scope = AiSentContextCollector.open()) {
            assertThat(scope.summary().isEmpty()).isTrue();
        }
    }

    @Test
    void captureReturnsTheAnswerWithWhatTheRequestSent() throws Exception {
        AiExecutionResult answer = new AiExecutionResult("done", null);

        AiSentContextCollector.Reply reply = AiSentContextCollector.capture(() -> {
            AiSentContextCollector.reportMemory(memory("<kortty_user_memory>Use tabs.</kortty_user_memory>"));
            AiSentContextCollector.reportSkill(skill("Shell Style", true));
            return answer;
        });

        assertThat(reply.result()).isSameInstanceAs(answer);
        assertThat(reply.sent().memory().text()).contains("Use tabs.");
        assertThat(reply.sent().skills()).containsExactly(skill("Shell Style", true));
        assertThat(AiSentContextCollector.isActive()).isFalse();
    }

    @Test
    void aSkillReportedTwiceInOneRequestCountsOnce() throws Exception {
        AiSentContextCollector.Reply reply = AiSentContextCollector.capture(() -> {
            AiSentContextCollector.reportSkill(skill("Same", true));
            AiSentContextCollector.reportSkill(skill("Same", true));
            return new AiExecutionResult("ok", null);
        });

        assertThat(reply.sent().skills()).hasSize(1);
    }

    @Test
    void aReplyWithoutAResultKeepsAnEmptyContextWhenNothingWasSent() throws Exception {
        AiSentContextCollector.Reply reply = AiSentContextCollector.capture(() -> null);

        assertThat(reply.result()).isNull();
        assertThat(reply.sent().isEmpty()).isTrue();
    }

    @Test
    void aFailedRequestLeavesNoScopeBehind() {
        assertThrows(AiSentContextCollector.Failed.class, () -> AiSentContextCollector.capture(() -> {
            AiSentContextCollector.reportSkill(skill("Lost", true));
            throw new IllegalStateException("provider failed");
        }));

        assertThat(AiSentContextCollector.isActive()).isFalse();
    }

    @Test
    void aFailedRequestCarriesWhatItWasBuiltWith() throws Exception {
        AiSentContextCollector.Failed failure = null;
        try {
            AiSentContextCollector.capture(() -> {
                AiSentContextCollector.reportMemory(memory("<kortty_user_memory>Use tabs.</kortty_user_memory>"));
                AiSentContextCollector.reportSkill(skill("Shell Style", true));
                throw new IllegalStateException("timeout");
            });
        } catch (AiSentContextCollector.Failed caught) {
            failure = caught;
        }

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("timeout");
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
        assertThat(failure.sent().memory().text()).contains("Use tabs.");
        assertThat(failure.sent().skills()).containsExactly(skill("Shell Style", true));
        assertThat(AiSentContextCollector.isActive()).isFalse();
    }

    @Test
    void aNestedScopeReportsOnlyToItself() {
        try (AiSentContextCollector.Scope outer = AiSentContextCollector.open()) {
            AiSentContextCollector.reportSkill(skill("Outer", true));
            try (AiSentContextCollector.Scope inner = AiSentContextCollector.open()) {
                AiSentContextCollector.reportSkill(skill("Inner", true));
                assertThat(inner.summary().skills()).containsExactly(skill("Inner", true));
            }
            assertThat(outer.summary().skills()).containsExactly(skill("Outer", true));
        }
    }

    @Test
    void overlappingRequestsEachKeepTheirOwnSummary() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < OVERLAP_ROUNDS; round++) {
                CyclicBarrier bothReported = new CyclicBarrier(2);
                Future<AiSentContextCollector.Reply> first = pool.submit(() -> AiSentContextCollector.capture(
                    () -> sendAndWait("<kortty_user_memory>First request.</kortty_user_memory>", "First", bothReported)));
                Future<AiSentContextCollector.Reply> second = pool.submit(() -> AiSentContextCollector.capture(
                    () -> sendAndWait("<kortty_user_memory>Second request.</kortty_user_memory>", "Second", bothReported)));

                AiSentContextCollector.Reply firstReply = first.get(10, TimeUnit.SECONDS);
                AiSentContextCollector.Reply secondReply = second.get(10, TimeUnit.SECONDS);

                assertThat(firstReply.sent().memory().text()).contains("First request.");
                assertThat(firstReply.sent().memory().text()).doesNotContain("Second request.");
                assertThat(firstReply.sent().skills()).containsExactly(skill("First", true));
                assertThat(secondReply.sent().memory().text()).contains("Second request.");
                assertThat(secondReply.sent().memory().text()).doesNotContain("First request.");
                assertThat(secondReply.sent().skills()).containsExactly(skill("Second", true));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Reports what one request sends, then waits until the other request has reported too, so both are open at once. */
    private static AiExecutionResult sendAndWait(String memoryText, String skillName, CyclicBarrier bothReported)
        throws Exception {
        AiSentContextCollector.reportMemory(memory(memoryText));
        AiSentContextCollector.reportSkill(skill(skillName, true));
        bothReported.await(10, TimeUnit.SECONDS);
        return new AiExecutionResult(skillName, null);
    }

    @Test
    void theSkillsAPromptSupportBuildsAreReportedToTheRequestThatBuildsThem() throws Exception {
        AiSkill shell = aSkill("shell-id", "Shell Style", "Use short shell examples.");
        AiSkill pinned = aSkill("pinned-id", "Pinned Style", "Prefer tabs.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false, List.of(shell, pinned), Set.of("pinned-id"));
        AiRequest request = new AiRequest(AiAction.ASK, "code", "box", "en", "question");

        AiSentContextCollector.Reply reply = AiSentContextCollector.capture(() -> {
            support.appendChatSkills("base", request);
            return new AiExecutionResult("ok", null);
        });

        assertThat(reply.sent().skills()).hasSize(2);
        assertThat(reply.sent().skills()).containsExactly(
            new AiSentContext.Skill("shell-id", "Shell Style", true, reply.sent().skills().get(0).tokens(), 1),
            new AiSentContext.Skill("pinned-id", "Pinned Style", false, reply.sent().skills().get(1).tokens(), 1));
        assertThat(reply.sent().skills().get(0).tokens()).isGreaterThan(0);
        assertThat(reply.sent().skills().get(1).tokens()).isGreaterThan(0);
    }

    @Test
    void aPreviewBuildsNoSummary() {
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false,
            List.of(aSkill("shell-id", "Shell Style", "Use short shell examples.")), Set.of());

        support.buildChatSkillBlock(new AiRequest(AiAction.ASK, "code", "box", "en", "question"));

        assertThat(AiSentContextCollector.isActive()).isFalse();
        try (AiSentContextCollector.Scope scope = AiSentContextCollector.open()) {
            assertThat(scope.summary().isEmpty()).isTrue();
        }
    }
}
