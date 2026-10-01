package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalConfig;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalAiPreflightTest {

    private static final class TestInvoker implements SessionJournalAiSupport.AiInvoker {
        final AtomicInteger tests = new AtomicInteger();
        final AiProfile profile;
        volatile Exception failure;

        TestInvoker(String id) {
            profile = new AiProfile();
            profile.setId(id);
            profile.setName("Profile " + id);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) {
            throw new AssertionError("the connection test must not run a summary");
        }

        @Override
        public void testConnection() throws Exception {
            tests.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public AiProfile profile() {
            return profile;
        }
    }

    private Instant now;

    @BeforeMethod
    void setUp() {
        SessionJournalAiPreflight.forgetSuccesses();
        now = Instant.parse("2026-10-01T12:00:00Z");
        SessionJournalAiPreflight.setClock(new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        });
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() {
        SessionJournalAiPreflight.setClock(null);
        SessionJournalAiPreflight.forgetSuccesses();
    }

    @Test
    void aFailureCarriesTheProfileAndTheReason() {
        TestInvoker invoker = new TestInvoker("p-fail");
        invoker.failure = new IllegalStateException("Tavily API key must be configured");

        SessionJournalAiPreflight.Result result = SessionJournalAiPreflight.check(invoker);

        assertThat(result.ok()).isFalse();
        assertThat(result.profileName()).isEqualTo("Profile p-fail");
        assertThat(result.message()).contains("Tavily API key");
    }

    @Test
    void aFailureIsNotRememberedSoTheNextTestAsksAgain() {
        TestInvoker invoker = new TestInvoker("p-retry");
        invoker.failure = new java.io.IOException("connection refused");
        assertThat(SessionJournalAiPreflight.check(invoker).ok()).isFalse();

        invoker.failure = null;

        assertThat(SessionJournalAiPreflight.check(invoker).ok()).isTrue();
        assertThat(invoker.tests.get()).isEqualTo(2);
    }

    @Test
    void aSuccessIsRememberedForAWhilePerProfile() {
        TestInvoker invoker = new TestInvoker("p-ok");
        assertThat(SessionJournalAiPreflight.check(invoker).ok()).isTrue();
        assertThat(SessionJournalAiPreflight.check(invoker).ok()).isTrue();
        assertThat(invoker.tests.get()).isEqualTo(1);

        now = now.plus(SessionJournalAiPreflight.SUCCESS_TTL).plus(Duration.ofSeconds(1));

        assertThat(SessionJournalAiPreflight.check(invoker).ok()).isTrue();
        assertThat(invoker.tests.get()).isEqualTo(2);
    }

    @Test
    void withoutAnInvokerTheTestFails() {
        assertThat(SessionJournalAiPreflight.check(null).ok()).isFalse();
    }

    @Test
    void theDefaultTestOfAnUnavailableInvokerFails() {
        SessionJournalAiSupport.AiInvoker unavailable = new SessionJournalAiSupport.AiInvoker() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public AiExecutionResult execute(String systemPrompt, String userPrompt) {
                return null;
            }
        };

        assertThat(SessionJournalAiPreflight.check(unavailable).ok()).isFalse();
    }

    @Test
    void theTestOnlyMattersWhenTheJournalWouldCallTheAi() {
        GlobalSettings settings = new GlobalSettings();
        SessionJournalConfig config = new SessionJournalConfig();
        config.setAiSummariesEnabled(true);
        settings.setSessionJournalAiSummariesEnabled(true);
        assertThat(SessionJournalAiPreflight.aiWouldRun(config, settings)).isTrue();

        config.setAiSummariesEnabled(false);
        assertThat(SessionJournalAiPreflight.aiWouldRun(config, settings)).isFalse();

        config.setAiSummariesEnabled(true);
        settings.setSessionJournalAiSummariesEnabled(false);
        assertThat(SessionJournalAiPreflight.aiWouldRun(config, settings)).isFalse();
    }
}
