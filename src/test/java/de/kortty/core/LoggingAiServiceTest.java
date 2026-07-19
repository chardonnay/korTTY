package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Verifies the request-lifecycle logging decorator logs metadata, never content, and stays transparent. */
public class LoggingAiServiceTest {

    private static final String SECRET_PROMPT = "SECRET-PROMPT-CONTENT-9f3a1";
    private static final String SECRET_RESPONSE = "SECRET-RESPONSE-CONTENT-42";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeMethod
    public void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingAiService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterMethod
    public void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    private AiProfile profile() {
        AiProfile profile = new AiProfile();
        profile.setName("Local Coding");
        profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        profile.setEmbeddedModelId("qwen2.5-coder-7b");
        return profile;
    }

    private AiRequest request() {
        return new AiRequest(AiAction.ASK, SECRET_PROMPT, "conn", "en", SECRET_PROMPT, SECRET_PROMPT);
    }

    /** {@link AiService} is not a functional interface (two methods), so delegates are built here. */
    private interface Execute {
        AiExecutionResult run(AiRequest request) throws Exception;
    }

    private static AiService delegate(Execute execute) {
        return new AiService() {
            @Override
            public AiExecutionResult execute(AiRequest request) throws Exception {
                return execute.run(request);
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };
    }

    /**
     * A full {@link AiPromptService} + {@link AiSkillUsageTracker} delegate, mirroring every concrete
     * AI service. Records the last prompts so tests can assert delegation, and returns a fixed result
     * and skill-usage list.
     */
    private static final class PromptTrackingService implements AiPromptService, AiSkillUsageTracker {
        private final AiExecutionResult result;
        private final List<AiSkillPromptSupport.SkillUsage> usages;
        private String lastSystemPrompt;
        private String lastUserPrompt;

        PromptTrackingService(AiExecutionResult result, List<AiSkillPromptSupport.SkillUsage> usages) {
            this.result = result;
            this.usages = usages;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            return result;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = userPrompt;
            return result;
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = userPrompt;
            return result;
        }

        @Override
        public boolean testConnection() {
            return true;
        }

        @Override
        public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
            return usages;
        }
    }

    @Test
    public void wrappedPromptServiceStaysAiPromptServiceAndSkillTracker() {
        AiService wrapped = LoggingAiService.wrap(
            new PromptTrackingService(new AiExecutionResult("ok", null), List.of()),
            profile(), "qwen2.5-coder-7b");

        // Regression guard: the terminal agent, planning, workflow, guide, and job-scheduler paths all
        // check `instanceof AiPromptService`, and skill tracking checks `instanceof AiSkillUsageTracker`.
        // A decorator missing either interface makes the agent show a spurious "no AI profile" prompt.
        assertThat(wrapped).isInstanceOf(AiPromptService.class);
        assertThat(wrapped).isInstanceOf(AiSkillUsageTracker.class);
    }

    @Test
    public void logsExecuteJsonPromptMetadataAndDelegatesWithoutLeakingContent() throws Exception {
        AiExecutionResult expected = new AiExecutionResult("{\"ok\":true}", new AiTokenUsage(30, 10, 40), null);
        PromptTrackingService inner = new PromptTrackingService(expected, List.of());
        AiPromptService service = (AiPromptService) LoggingAiService.wrap(inner, profile(), "qwen2.5-coder-7b");

        AiExecutionResult actual =
            service.executeJsonPrompt("SYSTEM-" + SECRET_PROMPT, "USER-" + SECRET_PROMPT);

        assertThat(actual).isSameInstanceAs(expected);
        // The prompts reached the delegate intact...
        assertThat(inner.lastUserPrompt).contains(SECRET_PROMPT);
        // ...but only metadata was logged, never the prompt text.
        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("AI request sent");
        assertThat(appender.list.get(0).getFormattedMessage()).contains("action=json-prompt");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("tokens=40");
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET_PROMPT);
        }
    }

    @Test
    public void logsExecutePromptOnce() throws Exception {
        AiExecutionResult expected = new AiExecutionResult("answer", null);
        AiPromptService service = (AiPromptService) LoggingAiService.wrap(
            new PromptTrackingService(expected, List.of()), profile(), "qwen2.5-coder-7b");

        service.executePrompt("sys", "user");

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("action=prompt");
    }

    @Test
    public void drainSkillUsagesDelegatesToWrappedTracker() {
        List<AiSkillPromptSupport.SkillUsage> sentinel = new java.util.ArrayList<>();
        AiSkillUsageTracker tracker = (AiSkillUsageTracker) LoggingAiService.wrap(
            new PromptTrackingService(new AiExecutionResult("ok", null), sentinel),
            profile(), "qwen2.5-coder-7b");

        assertThat(tracker.drainSkillUsages()).isSameInstanceAs(sentinel);
    }

    @Test
    public void logsSubmitAndCompletionMetadataAndReturnsDelegateResult() throws Exception {
        AiExecutionResult expected =
            new AiExecutionResult(SECRET_RESPONSE, new AiTokenUsage(120, 40, 160), "thinking...");
        AiService service = LoggingAiService.wrap(delegate(request -> expected), profile(), "qwen2.5-coder-7b");

        AiExecutionResult actual = service.execute(request());

        assertThat(actual).isSameInstanceAs(expected);
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getFormattedMessage()).contains("AI request sent");
        assertThat(events.get(0).getFormattedMessage()).contains("action=ASK");
        assertThat(events.get(0).getFormattedMessage()).contains("provider=EMBEDDED_LLAMA_CPP");
        assertThat(events.get(0).getFormattedMessage()).contains("qwen2.5-coder-7b");
        assertThat(events.get(0).getFormattedMessage()).contains("inputChars=");
        assertThat(events.get(1).getFormattedMessage()).contains("AI request done");
        assertThat(events.get(1).getFormattedMessage()).contains("tokens=160");
        assertThat(events.get(1).getFormattedMessage()).contains("reasoning=yes");
    }

    @Test
    public void neverLogsPromptOrResponseContent() throws Exception {
        AiExecutionResult result =
            new AiExecutionResult(SECRET_RESPONSE, new AiTokenUsage(10, 5, 15), SECRET_RESPONSE);
        AiService service = LoggingAiService.wrap(delegate(r -> result), profile(), "qwen2.5-coder-7b");

        service.execute(request());

        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET_PROMPT);
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET_RESPONSE);
        }
    }

    @Test
    public void logsFailureAtWarnAndRethrows() {
        RuntimeException boom = new IllegalStateException("model offline");
        AiService service = LoggingAiService.wrap(delegate(r -> {
            throw boom;
        }), profile(), "qwen2.5-coder-7b");

        Throwable thrown = expectThrows(IllegalStateException.class, () -> service.execute(request()));

        assertThat(thrown).isSameInstanceAs(boom);
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(1).getFormattedMessage()).contains("AI request failed");
        assertThat(events.get(1).getFormattedMessage()).contains("model offline");
        assertThat(events.get(1).getFormattedMessage()).doesNotContain(SECRET_PROMPT);
    }

    @Test
    public void testConnectionDelegatesWithoutLogging() {
        AiService service = LoggingAiService.wrap(new AiService() {
            @Override
            public AiExecutionResult execute(AiRequest request) {
                return new AiExecutionResult("", null);
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        }, profile(), "qwen2.5-coder-7b");

        assertThat(service.testConnection()).isTrue();
        assertThat(appender.list).isEmpty();
    }

    @Test
    public void completionWithoutUsageReportsTokensNotAvailable() throws Exception {
        AiService service = LoggingAiService.wrap(
            delegate(r -> new AiExecutionResult("ok", null)), profile(), "qwen2.5-coder-7b");

        service.execute(request());

        assertThat(appender.list.get(1).getFormattedMessage()).contains("tokens=n/a");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("reasoning=no");
    }

    @Test
    public void wrapReturnsNullWhenDelegateIsNull() {
        assertThat(LoggingAiService.wrap(null, profile(), "model")).isNull();
    }

    @Test
    public void nullProfileFallsBackToSafeLabels() throws Exception {
        AiService service = LoggingAiService.wrap(
            delegate(r -> new AiExecutionResult("ok", null)), null, null);

        service.execute(request());

        assertThat(appender.list.get(0).getFormattedMessage()).contains("provider=UNKNOWN");
        assertThat(appender.list.get(0).getFormattedMessage()).contains("model='auto'");
    }
}
