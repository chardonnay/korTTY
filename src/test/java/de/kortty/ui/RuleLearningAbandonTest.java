package de.kortty.ui;

import de.kortty.memory.RuleDetector;
import org.testng.annotations.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class RuleLearningAbandonTest {

    @Test
    void aReplyIsPassedOnWhileTheDetectionIsNotAbandoned() throws Exception {
        RuleDetector.Assistant assistant = RuleLearning.abandonable((system, user) -> "reply to " + user, new AtomicBoolean());

        assertThat(assistant.complete("system", "message")).isEqualTo("reply to message");
    }

    @Test
    void noModelCallStartsAfterTheTimeout() {
        AtomicInteger calls = new AtomicInteger();
        RuleDetector.Assistant assistant = RuleLearning.abandonable((system, user) -> {
            calls.incrementAndGet();
            return "reply";
        }, new AtomicBoolean(true));

        Throwable thrown = null;
        try {
            assistant.complete("system", "message");
        } catch (Exception e) {
            thrown = e;
        }

        assertThat(thrown).isInstanceOf(CancellationException.class);
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void aLateReplyIsRefusedSoItCannotClaimACard() {
        AtomicBoolean abandoned = new AtomicBoolean();
        RuleDetector.Assistant assistant = RuleLearning.abandonable((system, user) -> {
            abandoned.set(true);
            return "late rule";
        }, abandoned);

        Throwable thrown = null;
        try {
            assistant.complete("system", "message");
        } catch (Exception e) {
            thrown = e;
        }

        assertThat(thrown).isInstanceOf(CancellationException.class);
    }
}
