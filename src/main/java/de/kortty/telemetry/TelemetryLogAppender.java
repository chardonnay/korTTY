package de.kortty.telemetry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds ERROR-level log events into the "most frequent errors" metric.
 * Only exception class, first {@code de.kortty} frame, and logger name are
 * reported — never the log or exception message (messages can contain
 * hostnames and paths). Rate limiting lives in {@link TelemetryService}.
 */
public final class TelemetryLogAppender extends AppenderBase<ILoggingEvent> {

    static final String APPENDER_NAME = "KORTTY-TELEMETRY";
    private static final String OWN_PACKAGE_PREFIX = "de.kortty.telemetry";

    private final TelemetryService service;

    TelemetryLogAppender(TelemetryService service) {
        this.service = service;
        setName(APPENDER_NAME);
    }

    /** Idempotent; must be called again after every Logback {@code context.reset()}. */
    void attachToRootLogger() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
            return;
        }
        ch.qos.logback.classic.Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(APPENDER_NAME);
        setContext(loggerContext);
        if (!isStarted()) {
            start();
        }
        root.addAppender(this);
    }

    static void detachFromRootLogger() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
            return;
        }
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(APPENDER_NAME);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null || !event.getLevel().isGreaterOrEqual(Level.ERROR)) {
            return;
        }
        String loggerName = event.getLoggerName();
        if (loggerName != null && loggerName.startsWith(OWN_PACKAGE_PREFIX)) {
            return; // never report the telemetry pipeline's own logging
        }
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        service.trackError(extractExceptionClass(throwableProxy), extractSource(throwableProxy), loggerName);
    }

    static String extractExceptionClass(IThrowableProxy throwableProxy) {
        if (throwableProxy == null || throwableProxy.getClassName() == null) {
            return "none";
        }
        return throwableProxy.getClassName();
    }

    /** First {@code de.kortty.*} frame as {@code SimpleClass.method}, or "external"/"none". */
    static String extractSource(IThrowableProxy throwableProxy) {
        if (throwableProxy == null) {
            return "none";
        }
        StackTraceElementProxy[] stack = throwableProxy.getStackTraceElementProxyArray();
        if (stack != null) {
            for (StackTraceElementProxy elementProxy : stack) {
                StackTraceElement element = elementProxy != null ? elementProxy.getStackTraceElement() : null;
                String className = element != null ? element.getClassName() : null;
                if (className != null && className.startsWith("de.kortty.")) {
                    String simpleName = className.substring(className.lastIndexOf('.') + 1);
                    return simpleName + "." + element.getMethodName();
                }
            }
        }
        return "external";
    }
}
