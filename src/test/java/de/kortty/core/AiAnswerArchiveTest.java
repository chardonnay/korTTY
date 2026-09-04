package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.testng.annotations.Test;

public class AiAnswerArchiveTest {

    @Test
    void archivesAnswersAndKeepsOnlyTheNewest() throws Exception {
        Path directory = Files.createTempDirectory("ai-answers");
        try {
            Path first = AiAnswerArchive.save(directory, AiAction.APPLY_SNIPPET_IMPROVEMENTS, "unusable first/attempt", "{broken");
            assertThat(first).isNotNull();
            assertThat(first.getFileName().toString()).endsWith("-apply-snippet-improvements-unusable-first-attempt.txt");
            assertThat(Files.readString(first)).isEqualTo("{broken");
            assertThat(AiAnswerArchive.save(directory, AiAction.APPLY_SNIPPET_IMPROVEMENTS, "empty", "")).isNull();

            for (int index = 0; index < AiAnswerArchive.MAX_FILES + 5; index++) {
                AiAnswerArchive.save(directory, AiAction.ANALYZE_SNIPPET_CODE, "n" + index, "answer " + index);
            }
            try (Stream<Path> files = Files.list(directory)) {
                assertThat(files.count()).isEqualTo(AiAnswerArchive.MAX_FILES);
            }
            assertThat(Files.exists(first)).isFalse();
        } finally {
            try (Stream<Path> files = Files.list(directory)) {
                files.forEach(path -> path.toFile().delete());
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void archivedAnswersFollowTheLogRetention() throws Exception {
        Path logDirectory = Files.createTempDirectory("kortty-logs");
        Path archive = logDirectory.resolve(AiAnswerArchive.DIRECTORY_NAME);
        try {
            Path old = AiAnswerArchive.save(archive, AiAction.APPLY_SNIPPET_IMPROVEMENTS, "old", "{old");
            Path fresh = AiAnswerArchive.save(archive, AiAction.APPLY_SNIPPET_IMPROVEMENTS, "fresh", "{fresh");
            java.time.Instant now = java.time.Instant.now();
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(now.minus(java.time.Duration.ofDays(10))));

            LoggingConfiguration.maintainLogDirectory(logDirectory, 7, now, false);

            assertThat(Files.exists(old)).isFalse();
            assertThat(Files.exists(fresh)).isTrue();
            // Unlimited retention keeps them; the count cap alone bounds the archive then.
            Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.from(now.minus(java.time.Duration.ofDays(400))));
            LoggingConfiguration.maintainLogDirectory(logDirectory, 0, now, false);
            assertThat(Files.exists(fresh)).isTrue();
        } finally {
            try (Stream<Path> files = Files.walk(logDirectory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void archiveIsOffOutsideTheRunningApplicationUnlessSwitchedOn() throws Exception {
        String previousSwitch = System.getProperty(AiAnswerArchive.ENABLED_PROPERTY);
        String previousLogDir = System.getProperty(LoggingConfiguration.LOG_DIR_PROPERTY);
        Path logDirectory = Files.createTempDirectory("kortty-logs");
        try {
            System.setProperty(LoggingConfiguration.LOG_DIR_PROPERTY, logDirectory.toString());
            System.clearProperty(AiAnswerArchive.ENABLED_PROPERTY);
            // Not enabled by an application start-up (the test JVM never runs one): nothing is written.
            assertThat(AiAnswerArchive.save(AiAction.ANALYZE_SNIPPET_CODE, "x", "{y")).isNull();
            assertThat(Files.exists(logDirectory.resolve(AiAnswerArchive.DIRECTORY_NAME))).isFalse();
            System.setProperty(AiAnswerArchive.ENABLED_PROPERTY, "on");
            Path written = AiAnswerArchive.save(AiAction.ANALYZE_SNIPPET_CODE, "x", "{y");
            assertThat(written).isNotNull();
            assertThat(written.getParent()).isEqualTo(logDirectory.resolve(AiAnswerArchive.DIRECTORY_NAME));
            System.setProperty(AiAnswerArchive.ENABLED_PROPERTY, "off");
            assertThat(AiAnswerArchive.save(AiAction.ANALYZE_SNIPPET_CODE, "x", "{y")).isNull();
        } finally {
            if (previousSwitch != null) {
                System.setProperty(AiAnswerArchive.ENABLED_PROPERTY, previousSwitch);
            } else {
                System.clearProperty(AiAnswerArchive.ENABLED_PROPERTY);
            }
            if (previousLogDir != null) {
                System.setProperty(LoggingConfiguration.LOG_DIR_PROPERTY, previousLogDir);
            } else {
                System.clearProperty(LoggingConfiguration.LOG_DIR_PROPERTY);
            }
            try (Stream<Path> files = Files.walk(logDirectory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void defaultDirectoryFollowsTheConfiguredLogDirectory() {
        String previous = System.getProperty(LoggingConfiguration.LOG_DIR_PROPERTY);
        try {
            System.setProperty(LoggingConfiguration.LOG_DIR_PROPERTY, "/tmp/kortty-logs-test");
            assertThat(AiAnswerArchive.defaultDirectory()).isEqualTo(Path.of("/tmp/kortty-logs-test", "ai-answers"));
        } finally {
            if (previous != null) {
                System.setProperty(LoggingConfiguration.LOG_DIR_PROPERTY, previous);
            } else {
                System.clearProperty(LoggingConfiguration.LOG_DIR_PROPERTY);
            }
        }
    }
}
