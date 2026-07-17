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
