package de.kortty.memory;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class LearningStateTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private Path dir;
    private SteppingClock clock;

    @BeforeMethod
    void createDirectory() throws IOException {
        dir = Files.createTempDirectory("kortty-learning-");
        clock = new SteppingClock(NOW);
    }

    @AfterMethod(alwaysRun = true)
    void deleteDirectory() throws IOException {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aRejectedRuleStaysRejectedAcrossInstances() {
        new LearningState(dir, clock).reject("Antworte kurz");

        LearningState reopened = new LearningState(dir, clock);
        assertThat(reopened.isRejected("antworte   KURZ")).isTrue();
        assertThat(reopened.isRejected("Antworte lang")).isFalse();
    }

    @Test
    void ruleHashIgnoresCaseAndRepeatedSpaceButNotOtherCharacters() {
        assertThat(LearningState.ruleHash("A  b\tc")).isEqualTo(LearningState.ruleHash("a b c"));
        assertThat(LearningState.ruleHash("a b c.")).isNotEqualTo(LearningState.ruleHash("a b c"));
    }

    @Test
    void aSnoozeLastsSevenDays() {
        LearningState state = new LearningState(dir, clock);
        state.snooze("Nicht jetzt");

        clock.advance(Duration.ofDays(6));
        assertThat(state.isSnoozed("Nicht jetzt")).isTrue();

        clock.advance(Duration.ofDays(2));
        assertThat(state.isSnoozed("Nicht jetzt")).isFalse();
    }

    @Test
    void rejectingARuleEndsItsSnooze() {
        LearningState state = new LearningState(dir, clock);
        state.snooze("Nicht jetzt");

        state.reject("Nicht jetzt");

        assertThat(state.isSnoozed("Nicht jetzt")).isFalse();
        assertThat(state.isRejected("Nicht jetzt")).isTrue();
    }

    @Test
    void aRejectedMessageIsRememberedAcrossInstances() {
        LearningState state = new LearningState(dir, clock);
        state.rememberMessage("Verwende immer Tabs.", "Ab jetzt immer Tabs verwenden.");
        state.reject("Verwende immer Tabs.");

        LearningState reopened = new LearningState(dir, clock);
        assertThat(reopened.isRejectedMessage("ab   jetzt immer Tabs verwenden.")).isTrue();
        assertThat(reopened.isRejectedMessage("Ab jetzt immer Leerzeichen verwenden.")).isFalse();
    }

    @Test
    void aSnoozedMessageIsPostponedForTheSnoozeOnly() {
        LearningState state = new LearningState(dir, clock);
        state.rememberMessage("Verwende immer Tabs.", "Ab jetzt immer Tabs verwenden.");
        state.snooze("Verwende immer Tabs.");

        assertThat(state.isSnoozedMessage("Ab jetzt immer Tabs verwenden.")).isTrue();
        clock.advance(LearningState.SNOOZE.plusDays(1));
        assertThat(state.isSnoozedMessage("Ab jetzt immer Tabs verwenden.")).isFalse();
    }

    @Test
    void detectionCallsAreCappedAtTenADayAndResetTheNextDay() {
        LearningState state = new LearningState(dir, clock);
        for (int call = 0; call < LearningState.MAX_DETECTION_CALLS_PER_DAY; call++) {
            assertThat(state.tryUseDetectionCall()).isTrue();
        }

        assertThat(state.tryUseDetectionCall()).isFalse();
        assertThat(state.detectionCallsToday()).isEqualTo(LearningState.MAX_DETECTION_CALLS_PER_DAY);

        clock.advance(Duration.ofDays(1));
        assertThat(state.tryUseDetectionCall()).isTrue();
        assertThat(state.detectionCallsToday()).isEqualTo(1);
    }

    @Test
    void proposalCardsAreOneAPerRunAndThreeADay() {
        LearningState state = new LearningState(dir, clock);

        assertThat(state.claimProposalCard("run-1")).isTrue();
        assertThat(state.claimProposalCard("run-1")).isFalse();
        assertThat(state.claimProposalCard("run-2")).isTrue();
        assertThat(state.claimProposalCard("run-3")).isTrue();
        assertThat(state.claimProposalCard("run-4")).isFalse();
        assertThat(state.proposalCardsToday()).isEqualTo(LearningState.MAX_PROPOSAL_CARDS_PER_DAY);

        clock.advance(Duration.ofDays(1));
        assertThat(state.claimProposalCard("run-4")).isTrue();
    }

    @Test
    void counterWritesSurviveANewInstance() {
        new LearningState(dir, clock).tryUseDetectionCall();
        new LearningState(dir, clock).claimProposalCard("run-1");

        LearningState reopened = new LearningState(dir, clock);
        assertThat(reopened.detectionCallsToday()).isEqualTo(1);
        assertThat(reopened.proposalCardsToday()).isEqualTo(1);
    }

    @Test
    void theStateFileIsOwnerOnly() throws IOException {
        new LearningState(dir, clock).reject("Antworte kurz");

        Path file = dir.resolve(LearningState.FILE_NAME);
        assertThat(Files.exists(file)).isTrue();
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file)).doesNotContain(PosixFilePermission.GROUP_READ);
            assertThat(Files.getPosixFilePermissions(file)).doesNotContain(PosixFilePermission.OTHERS_READ);
        }
    }

    @Test
    void anUnreadableFileStartsEmptyAndIsReplacedByTheNextChange() throws IOException {
        Files.writeString(dir.resolve(LearningState.FILE_NAME), "{ not json");

        LearningState state = new LearningState(dir, clock);
        assertThat(state.isRejected("Antworte kurz")).isFalse();
        assertThat(state.tryUseDetectionCall()).isTrue();

        assertThat(new LearningState(dir, clock).detectionCallsToday()).isEqualTo(1);
    }

    @Test
    void entriesThatDoNotParseAreDroppedOnLoad() throws IOException {
        Files.writeString(dir.resolve(LearningState.FILE_NAME),
            "{\"snoozed\":{\"abc\":\"not a time\"},\"days\":{\"yesterday\":{\"detections\":3}}}");

        LearningState state = new LearningState(dir, clock);
        assertThat(state.isSnoozed("anything")).isFalse();
        assertThat(state.tryUseDetectionCall()).isTrue();
    }
}
