package de.kortty.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import de.kortty.model.GlobalSettings;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static com.google.common.truth.Truth.assertThat;

class LoggingConfigurationTest {

    @Test
    void testRuntimeUsesConsoleOnlyLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        List<Appender<?>> appenders = new ArrayList<>();
        Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> iterator = root.iteratorForAppenders();
        iterator.forEachRemaining(appenders::add);

        assertThat(appenders.stream().anyMatch(ConsoleAppender.class::isInstance)).isTrue();
        assertThat(appenders.stream().anyMatch(FileAppender.class::isInstance)).isFalse();
    }

    @Test
    void defaultLogDirectoryIsLogsSubdirectoryOfConfigDir() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-log-default");
        try {
            assertThat(LoggingConfiguration.defaultLogDirectory(configDir))
                .isEqualTo(configDir.resolve("logs").toAbsolutePath().normalize());
            assertThat(LoggingConfiguration.resolveLogDirectory((String) null, configDir))
                .isEqualTo(configDir.resolve("logs").toAbsolutePath().normalize());
        } finally {
            Files.deleteIfExists(configDir);
        }
    }

    @Test
    void persistedLogSettingsAreReadBeforeJaxbSettingsLoad() throws Exception {
        Path configDir = Files.createTempDirectory("kortty-log-settings");
        Path customLogs = configDir.resolve("custom-logs");
        try {
            Files.writeString(configDir.resolve("global-settings.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <globalSettings>
                    <logDirectoryPath>%s</logDirectoryPath>
                    <logRetentionDays>12</logRetentionDays>
                </globalSettings>
                """.formatted(customLogs), StandardCharsets.UTF_8);

            LoggingConfiguration.PersistedLogSettings settings =
                LoggingConfiguration.readPersistedLogSettings(configDir);

            assertThat(settings.logDirectoryPath()).isEqualTo(customLogs.toString());
            assertThat(settings.logRetentionDays()).isEqualTo(12);
        } finally {
            Files.deleteIfExists(configDir.resolve("global-settings.xml"));
            Files.deleteIfExists(configDir);
        }
    }

    @Test
    void maintenanceCompressesRotatedLogsOlderThanTwentyFourHours() throws Exception {
        Path logDir = Files.createTempDirectory("kortty-log-maintenance");
        Path oldLog = logDir.resolve("kortty.2026-05-18.log");
        Path activeLog = logDir.resolve("kortty.log");
        try {
            Files.writeString(oldLog, "old log\n", StandardCharsets.UTF_8);
            Files.writeString(activeLog, "active log\n", StandardCharsets.UTF_8);
            Instant now = Instant.parse("2026-05-20T12:00:00Z");
            FileTime oldTime = FileTime.from(now.minusSeconds(25 * 60 * 60));
            Files.setLastModifiedTime(oldLog, oldTime);
            Files.setLastModifiedTime(activeLog, oldTime);

            LoggingConfiguration.maintainLogDirectory(logDir, GlobalSettings.DEFAULT_LOG_RETENTION_DAYS, now);

            Path compressed = logDir.resolve("kortty.2026-05-18.log.gz");
            assertThat(Files.exists(oldLog)).isFalse();
            assertThat(Files.exists(compressed)).isTrue();
            assertThat(Files.exists(activeLog)).isTrue();
            assertThat(readGzip(compressed)).isEqualTo("old log\n");
        } finally {
            deleteIfExists(logDir.resolve("kortty.2026-05-18.log.gz"));
            deleteIfExists(oldLog);
            deleteIfExists(activeLog);
            Files.deleteIfExists(logDir);
        }
    }

    @Test
    void maintenanceDeletesArchivesOlderThanRetentionDays() throws Exception {
        Path logDir = Files.createTempDirectory("kortty-log-retention");
        Path expiredLog = logDir.resolve("kortty.2026-05-10.log.gz");
        Path unrelated = logDir.resolve("notes.txt");
        try {
            Files.writeString(expiredLog, "expired", StandardCharsets.UTF_8);
            Files.writeString(unrelated, "keep", StandardCharsets.UTF_8);
            Instant now = Instant.parse("2026-05-20T12:00:00Z");
            Files.setLastModifiedTime(expiredLog, FileTime.from(now.minusSeconds(10 * 24 * 60 * 60)));
            Files.setLastModifiedTime(unrelated, FileTime.from(now.minusSeconds(10 * 24 * 60 * 60)));

            LoggingConfiguration.maintainLogDirectory(logDir, 7, now);

            assertThat(Files.exists(expiredLog)).isFalse();
            assertThat(Files.exists(unrelated)).isTrue();
        } finally {
            deleteIfExists(expiredLog);
            deleteIfExists(unrelated);
            Files.deleteIfExists(logDir);
        }
    }

    private static String readGzip(Path file) throws Exception {
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteIfExists(Path file) throws Exception {
        Files.deleteIfExists(file);
    }
}
