package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.TerminalRecordingScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.testng.annotations.Test;

class TerminalRecordingSessionTest {

    @Test
    void multipleStartStopSegmentsUseOneReplayFile() throws Exception {
        Path file = Files.createTempFile("kortty-session", ".korttyrec.jsonl");
        Files.deleteIfExists(file);
        MutableClock clock = new MutableClock();

        try (TerminalRecordingSession session = new TerminalRecordingSession(file, "prod", true, 20, clock)) {
            session.start(TerminalRecordingScope.ACTIVE_SPLIT);
            session.recordScreenSnapshot("split-1", "one\n");
            session.stop();
            session.start(TerminalRecordingScope.ACTIVE_SPLIT);
            session.recordScreenSnapshot("split-1", "two\n");
            session.stop();
        }

        String replay = Files.readString(file);
        assertThat(replay).contains("\"type\":\"session_created\"");
        assertThat(replay).contains("\"type\":\"session_closed\"");
        assertThat(countOccurrences(replay, "\"type\":\"recording_start\"")).isEqualTo(2);
        assertThat(countOccurrences(replay, "\"type\":\"recording_stop\"")).isEqualTo(2);
        assertThat(countOccurrences(replay, "\"type\":\"screen\"")).isEqualTo(2);
        Files.deleteIfExists(file);
    }

    @Test
    void autoPauseAndResumeOnScreenChange() throws Exception {
        Path file = Files.createTempFile("kortty-autopause", ".korttyrec.jsonl");
        Files.deleteIfExists(file);
        MutableClock clock = new MutableClock();

        try (TerminalRecordingSession session = new TerminalRecordingSession(file, "prod", true, 20, clock)) {
            session.start(TerminalRecordingScope.ACTIVE_SPLIT);
            clock.advanceSeconds(21);
            session.checkIdle();
            assertThat(session.getState()).isEqualTo(TerminalRecordingState.AUTO_PAUSED);

            session.recordScreenSnapshot("split-1", "new output\n");
            assertThat(session.getState()).isEqualTo(TerminalRecordingState.RECORDING);
        }

        String replay = Files.readString(file);
        assertThat(replay).contains("\"type\":\"auto_pause\"");
        assertThat(replay).contains("\"type\":\"auto_resume\"");
        Files.deleteIfExists(file);
    }

    @Test
    void recordingStartEventsPreserveSelectedScope() throws Exception {
        Path file = Files.createTempFile("kortty-scope", ".korttyrec.jsonl");
        Files.deleteIfExists(file);

        try (TerminalRecordingSession session = new TerminalRecordingSession(file, "prod", true, 20, new MutableClock())) {
            session.start(TerminalRecordingScope.ACTIVE_SPLIT);
            session.stop();
            session.start(TerminalRecordingScope.WHOLE_TAB);
            session.stop();
        }

        String replay = Files.readString(file);
        assertThat(replay).contains("\"scope\":\"ACTIVE_SPLIT\"");
        assertThat(replay).contains("\"scope\":\"WHOLE_TAB\"");
        Files.deleteIfExists(file);
    }

    @Test
    void userInputActivityDoesNotPersistInputText() throws Exception {
        Path file = Files.createTempFile("kortty-input", ".korttyrec.jsonl");
        Files.deleteIfExists(file);

        try (TerminalRecordingSession session = new TerminalRecordingSession(file, "prod", true, 20, new MutableClock())) {
            session.start(TerminalRecordingScope.ACTIVE_SPLIT);
            session.recordUserInputActivity();
            session.stop();
        }

        String replay = Files.readString(file);
        assertThat(replay).contains("\"type\":\"user_input_activity\"");
        assertThat(replay).doesNotContain("sudo");
        assertThat(replay).doesNotContain("password");
        Files.deleteIfExists(file);
    }

    @Test
    void screenSnapshotPersistsGeometryAndOptionalColorRuns() throws Exception {
        Path file = Files.createTempFile("kortty-style", ".korttyrec.jsonl");
        Files.deleteIfExists(file);

        try (TerminalRecordingSession session = new TerminalRecordingSession(file, "prod", true, 20, new MutableClock())) {
            session.start(TerminalRecordingScope.ACTIVE_SPLIT);
            session.recordScreenSnapshot(
                "split-1",
                new TerminalRecordingScreenSnapshot(
                    "red",
                    120,
                    40,
                    1600,
                    900,
                    List.of(new TerminalRecordingStyleRun(
                        0,
                        0,
                        "red",
                        "#FF0000",
                        "#000000",
                        List.of("BOLD")))));
            session.stop();
        }

        String replay = Files.readString(file);
        assertThat(replay).contains("\"columns\":120");
        assertThat(replay).contains("\"rows\":40");
        assertThat(replay).contains("\"pixelWidth\":1600");
        assertThat(replay).contains("\"styleRuns\"");
        assertThat(replay).contains("\"foreground\":\"#FF0000\"");
        assertThat(replay).contains("\"BOLD\"");
        Files.deleteIfExists(file);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-05-13T12:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
