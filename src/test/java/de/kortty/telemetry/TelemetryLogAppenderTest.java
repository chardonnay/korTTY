package de.kortty.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import de.kortty.core.GlobalSettingsManager;
import org.testng.annotations.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.google.common.truth.Truth.assertThat;

class TelemetryLogAppenderTest {

    private static Throwable throwableWithStack(StackTraceElement... stack) {
        RuntimeException exception = new RuntimeException("secret message with /Users/someone/path");
        exception.setStackTrace(stack);
        return exception;
    }

    @Test
    void extractsFirstKorttyFrameAsSource() {
        Throwable throwable = throwableWithStack(
            new StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 100),
            new StackTraceElement("de.kortty.ui.MainWindow", "openProject", "MainWindow.java", 3474),
            new StackTraceElement("de.kortty.KorTTYApplication", "start", "KorTTYApplication.java", 154));

        String source = TelemetryLogAppender.extractSource(new ThrowableProxy(throwable));

        assertThat(source).isEqualTo("MainWindow.openProject");
    }

    @Test
    void reportsExternalForForeignStacks() {
        Throwable throwable = throwableWithStack(
            new StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 100),
            new StackTraceElement("javafx.scene.Node", "fireEvent", "Node.java", 200));

        assertThat(TelemetryLogAppender.extractSource(new ThrowableProxy(throwable))).isEqualTo("external");
    }

    @Test
    void handlesMissingThrowable() {
        assertThat(TelemetryLogAppender.extractSource(null)).isEqualTo("none");
        assertThat(TelemetryLogAppender.extractExceptionClass(null)).isEqualTo("none");
    }

    @Test
    void neverEmitsMessageText() {
        Throwable throwable = throwableWithStack(
            new StackTraceElement("de.kortty.ui.MainWindow", "openProject", "MainWindow.java", 3474));
        ThrowableProxy proxy = new ThrowableProxy(throwable);

        assertThat(TelemetryLogAppender.extractExceptionClass(proxy)).isEqualTo("java.lang.RuntimeException");
        assertThat(TelemetryLogAppender.extractSource(proxy)).doesNotContain("secret");
        assertThat(TelemetryLogAppender.extractSource(proxy)).doesNotContain("/Users");
    }

    @Test
    void appendFiltersLevelAndOwnPackage() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-appender-test");
        GlobalSettingsManager settingsManager = new GlobalSettingsManager(configDir);
        settingsManager.getSettings().setTelemetryEnabled(true);
        AptabaseClient client = new AptabaseClient(
            HttpClient.newHttpClient(), URI.create("http://127.0.0.1:9/api/v0/events"));
        TelemetryService service = new TelemetryService(
            settingsManager, configDir, client, Clock.fixed(Instant.parse("2026-07-04T10:00:00Z"), ZoneOffset.UTC));
        service.start();
        int baseline = service.queuedEventCount();
        try {
            TelemetryLogAppender appender = new TelemetryLogAppender(service);
            LoggerContext context = new LoggerContext();
            Throwable throwable = throwableWithStack(
                new StackTraceElement("de.kortty.ui.MainWindow", "openProject", "MainWindow.java", 1));

            appender.append(errorEvent(context, "de.kortty.ui.MainWindow", Level.ERROR, throwable));
            assertThat(service.queuedEventCount()).isEqualTo(baseline + 1);

            appender.append(errorEvent(context, "de.kortty.ui.MainWindow", Level.WARN, throwable));
            assertThat(service.queuedEventCount()).isEqualTo(baseline + 1);

            appender.append(errorEvent(context, "de.kortty.telemetry.TelemetryService", Level.ERROR, throwable));
            assertThat(service.queuedEventCount()).isEqualTo(baseline + 1);
        } finally {
            service.shutdown(java.time.Duration.ofMillis(100));
        }
    }

    private static LoggingEvent errorEvent(LoggerContext context, String loggerName, Level level, Throwable throwable) {
        return new LoggingEvent(
            "de.kortty.Test",
            context.getLogger(loggerName),
            level,
            "some log message",
            throwable,
            null);
    }
}
