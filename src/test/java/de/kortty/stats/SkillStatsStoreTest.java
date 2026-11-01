package de.kortty.stats;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiSkill;
import de.kortty.model.AiTokenizerType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.List;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The statistics file: retention, snapshots, the gate, corrupt files, owner-only writes and reset. */
public class SkillStatsStoreTest {

    private static final String SKILL = "perl";

    private Path directory;
    private Path file;
    private StatsTestClock clock;

    @BeforeMethod
    void newDirectory() throws IOException {
        directory = Files.createTempDirectory("kortty-skill-stats-");
        file = directory.resolve(SkillStatsStore.FILE_NAME);
        clock = new StatsTestClock(LocalDate.of(2026, 11, 1));
    }

    @Test
    void dailyBucketsFoldIntoMonthsAndOldMonthsIntoYearsWhileYearsStay() throws IOException {
        SkillStatsStore store = openRecording();
        clock.setDate(LocalDate.of(2019, 3, 5));
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        assertThat(store.flush()).isTrue();

        clock.setDate(LocalDate.of(2026, 11, 1));
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        assertThat(store.flush()).isTrue();

        JsonObject record = skillRecord();
        assertThat(record.getAsJsonObject("daily").keySet()).containsExactly("2026-11-01");
        assertThat(record.getAsJsonObject("monthly").keySet()).isEmpty();
        assertThat(record.getAsJsonObject("yearly").keySet()).containsExactly("2019");
        assertThat(record.getAsJsonObject("yearly").getAsJsonObject("2019").get("automatic").getAsInt()).isEqualTo(1);
    }

    @Test
    void dailyBucketsOlderThanFourHundredDaysBecomeMonthly() throws IOException {
        SkillStatsStore store = openRecording();
        clock.setDate(LocalDate.of(2025, 9, 1));
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        clock.setDate(LocalDate.of(2026, 11, 1));
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        store.flush();

        JsonObject record = skillRecord();
        assertThat(record.getAsJsonObject("daily").keySet()).containsExactly("2026-11-01");
        assertThat(record.getAsJsonObject("monthly").keySet()).containsExactly("2025-09");
    }

    @Test
    void aSnapshotIsTakenOnlyWhenTheFingerprintChanges() throws IOException {
        SkillStatsStore store = openRecording();
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        store.recordCommitted(List.of(usage(true, 120, "fp-2")));

        store.flush();
        assertThat(skillRecord().getAsJsonArray("snapshots").size()).isEqualTo(2);
        assertThat(history(store)).hasSize(1);
        assertThat(history(store).get(0).tokens()).isEqualTo(120);
    }

    @Test
    void snapshotsOlderThanNinetyDaysKeepOnePerIsoWeek() throws IOException {
        SkillStatsStore store = openRecording();
        clock.setDate(LocalDate.of(2026, 1, 5));   // Monday, ISO week 2
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        clock.setDate(LocalDate.of(2026, 1, 6));   // Tuesday, same week
        store.recordCommitted(List.of(usage(true, 110, "fp-2")));
        clock.setDate(LocalDate.of(2026, 1, 12));  // Monday, ISO week 3
        store.recordCommitted(List.of(usage(true, 130, "fp-3")));
        clock.setDate(LocalDate.of(2026, 6, 1));
        store.recordCommitted(List.of(usage(true, 140, "fp-4")));
        store.flush();

        JsonArray snapshots = skillRecord().getAsJsonArray("snapshots");
        assertThat(snapshots.size()).isEqualTo(3);
        assertThat(snapshots.get(0).getAsJsonObject().get("date").getAsString()).isEqualTo("2026-01-06");
        assertThat(snapshots.get(1).getAsJsonObject().get("date").getAsString()).isEqualTo("2026-01-12");
        assertThat(snapshots.get(2).getAsJsonObject().get("date").getAsString()).isEqualTo("2026-06-01");
    }

    @Test
    void recordingOffRecordsNothingAndWritesNoFile() {
        SkillStatsStore store = new SkillStatsStore(file, clock);
        store.setRecordingGate(() -> false);

        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        store.observeSkills(List.of(skill()));

        assertThat(store.flush()).isFalse();
        assertThat(Files.exists(file)).isFalse();
        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    @Test
    void aGateThatThrowsIsClosed() {
        SkillStatsStore store = new SkillStatsStore(file, clock);
        store.setRecordingGate(() -> {
            throw new IllegalStateException("settings are not loaded");
        });

        assertThat(store.isRecordingAllowed()).isFalse();
    }

    @Test
    void aCorruptFileIsMovedAsideAndTheStatisticsStartEmpty() throws IOException {
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        SkillStatsStore store = new SkillStatsStore(file, clock);

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(directory.resolve(SkillStatsStore.FILE_NAME + ".broken"))).isTrue();
        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    @Test
    void theFileIsWrittenOwnerOnly() throws IOException {
        SkillStatsStore store = openRecording();
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        store.flush();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void resetDeletesTheRecordedStatistics() throws IOException {
        SkillStatsStore store = openRecording();
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        store.flush();

        store.reset();

        assertThat(Files.exists(file)).isFalse();
        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    @Test
    void aMissingFileStartsEmptyAndTheFirstFlushCreatesIt() throws IOException {
        SkillStatsStore store = openRecording();
        assertThat(Files.exists(file)).isFalse();

        store.recordCommitted(List.of(usage(true, 100, "fp-1")));

        assertThat(store.flush()).isTrue();
        assertThat(Files.readString(file)).contains(SKILL);
    }

    @Test
    void aSizeCountedWithAnotherTokenizerGetsItsOwnSnapshot() throws IOException {
        SkillStatsStore store = openRecording();
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));
        store.recordCommitted(List.of(usage(AiTokenizerType.O200K_BASE, true, 170, "fp-1")));
        store.flush();

        assertThat(skillRecord().getAsJsonArray("snapshots").size()).isEqualTo(2);
        assertThat(history(store)).hasSize(1);
        assertThat(history(store).get(0).tokens()).isEqualTo(170);
    }

    @Test
    void alternatingTokenizersKeepOneSnapshotPerTokenizerAndDay() throws IOException {
        SkillStatsStore store = openRecording();
        for (int request = 0; request < 4; request++) {
            store.recordCommitted(List.of(usage(true, 100, "fp-1")));
            store.recordCommitted(List.of(usage(AiTokenizerType.O200K_BASE, true, 170, "fp-1")));
        }
        store.flush();

        assertThat(skillRecord().getAsJsonArray("snapshots").size()).isEqualTo(2);
    }

    @Test
    void aSentSizeWithoutTheLearnedRulesIsNotRecordedAsASnapshotOfTheSkill() throws IOException {
        SkillStatsStore store = openRecording();
        store.recordCommitted(List.of(new SkillUsageEvent(SKILL, "Perl", true, SkillSurface.CHAT, 138,
            AiTokenizerType.ESTIMATE, "fp-with-rules", false)));
        store.flush();

        assertThat(skillRecord().getAsJsonArray("snapshots").size()).isEqualTo(0);
        assertThat(history(store)).isEmpty();
    }

    @Test
    void theSizeCheckTakesTheCompleteSkillAfterAContentOnlySend() throws IOException {
        SkillStatsStore store = openRecording();
        store.recordCommitted(List.of(new SkillUsageEvent(SKILL, "Perl", true, SkillSurface.CHAT, 138,
            AiTokenizerType.ESTIMATE, "fp-with-rules", false)));
        AiSkill complete = skill();
        complete.getLearnedRules().add(de.kortty.model.AiSkillLearnedRule.create("Use strict.", "chat"));
        store.observeSkills(List.of(complete));
        store.flush();

        int completeTokens = de.kortty.stats.SkillSize.of(complete, AiTokenizerType.ESTIMATE).tokens();
        assertThat(history(store)).hasSize(1);
        assertThat(history(store).get(0).tokens()).isEqualTo(completeTokens);
    }

    @Test
    void anUnreadableFileIsKeptAndNeverOverwritten() throws IOException {
        // A directory where the file should be cannot be read as text, but it is not damaged content.
        Files.createDirectory(file);

        SkillStatsStore store = openRecording();
        store.recordCommitted(List.of(usage(true, 100, "fp-1")));

        assertThat(store.flush()).isFalse();
        assertThat(Files.isDirectory(file)).isTrue();
        assertThat(Files.exists(directory.resolve(SkillStatsStore.FILE_NAME + ".broken"))).isFalse();
    }

    @Test
    void aSecondCorruptFileDoesNotReplaceAnEarlierBackup() throws IOException {
        Path backup = directory.resolve(SkillStatsStore.FILE_NAME + ".broken");
        Files.writeString(backup, "first backup", StandardCharsets.UTF_8);
        Files.writeString(file, "{ second damaged file", StandardCharsets.UTF_8);

        new SkillStatsStore(file, clock);

        assertThat(Files.readString(backup)).isEqualTo("first backup");
        assertThat(Files.exists(file)).isFalse();
    }

    private SkillStatsStore openRecording() {
        SkillStatsStore store = new SkillStatsStore(file, clock);
        store.setRecordingGate(() -> true);
        return store;
    }

    private JsonObject skillRecord() throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        return root.getAsJsonObject("skills").getAsJsonObject(SKILL);
    }

    private static List<SkillStatsReport.Point> history(SkillStatsStore store) {
        return store.report(List.of(), range()).rows().stream()
            .filter(row -> row.skillId().equals(SKILL))
            .findFirst()
            .orElseThrow()
            .history();
    }

    private static SkillStatsReport.Range range() {
        return new SkillStatsReport.Range(SkillStatsReport.Granularity.YEAR, SkillStatsReport.Range.ALL);
    }

    private static SkillUsageEvent usage(boolean automatic, int tokens, String fingerprint) {
        return new SkillUsageEvent(SKILL, "Perl", automatic, SkillSurface.CHAT, tokens,
            AiTokenizerType.ESTIMATE, fingerprint);
    }

    private static SkillUsageEvent usage(AiTokenizerType tokenizer, boolean automatic, int tokens, String fingerprint) {
        return new SkillUsageEvent(SKILL, "Perl", automatic, SkillSurface.CHAT, tokens, tokenizer, fingerprint);
    }

    private static AiSkill skill() {
        AiSkill skill = new AiSkill();
        skill.setId(SKILL);
        skill.setName("Perl");
        skill.setContent("Use strict and warnings.");
        return skill;
    }
}
