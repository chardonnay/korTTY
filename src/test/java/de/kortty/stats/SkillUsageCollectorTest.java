package de.kortty.stats;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.AiTokenizerType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The per-request collection: one count per successful logical request, nothing for failed or nested-failed ones. */
public class SkillUsageCollectorTest {

    private StatsTestClock clock;
    private SkillStatsStore store;

    @BeforeMethod
    void useRecordingStore() throws Exception {
        clock = new StatsTestClock(LocalDate.of(2026, 11, 1));
        Path file = Files.createTempDirectory("kortty-usage-collector-").resolve(SkillStatsStore.FILE_NAME);
        store = new SkillStatsStore(file, clock);
        store.setRecordingGate(() -> true);
        SkillStatsStore.replaceInstanceForTesting(store);
    }

    @AfterMethod
    void restoreDisabledStore() throws Exception {
        Path file = Files.createTempDirectory("kortty-usage-collector-off-").resolve(SkillStatsStore.FILE_NAME);
        SkillStatsStore.replaceInstanceForTesting(new SkillStatsStore(file, clock));
    }

    @Test
    void aRetriedSkillCountsOnceInOneRequest() {
        try (SkillUsageCollector.Scope request = SkillUsageCollector.open()) {
            SkillUsageCollector.report(event("perl", true));
            SkillUsageCollector.report(event("perl", true));
            request.commit();
        }

        assertThat(usesOf("perl")).isEqualTo(1);
    }

    @Test
    void aFailedRequestCommitsNothing() {
        try (SkillUsageCollector.Scope request = SkillUsageCollector.open()) {
            SkillUsageCollector.report(event("perl", true));
            // No commit: the request failed or was stopped.
        }

        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    @Test
    void aSuccessfulNestedRequestCommitsOnceThroughTheOuterScope() {
        try (SkillUsageCollector.Scope outer = SkillUsageCollector.open()) {
            SkillUsageCollector.report(event("perl", true));
            try (SkillUsageCollector.Scope inner = SkillUsageCollector.open()) {
                SkillUsageCollector.report(event("classifier-pick", true));
                inner.commit();
            }
            outer.commit();
        }

        assertThat(usesOf("perl")).isEqualTo(1);
        assertThat(usesOf("classifier-pick")).isEqualTo(1);
    }

    @Test
    void aFailedNestedRequestKeepsTheUsageOfTheOuterRequest() {
        try (SkillUsageCollector.Scope outer = SkillUsageCollector.open()) {
            SkillUsageCollector.report(event("perl", true));
            try (SkillUsageCollector.Scope inner = SkillUsageCollector.open()) {
                SkillUsageCollector.report(event("classifier-pick", true));
                // The nested call failed: its skills are dropped, the caller carries on.
            }
            outer.commit();
        }

        assertThat(usesOf("perl")).isEqualTo(1);
        assertThat(usesOf("classifier-pick")).isEqualTo(0);
    }

    @Test
    void aFailedOuterRequestDropsTheSuccessfulNestedUsage() {
        try (SkillUsageCollector.Scope outer = SkillUsageCollector.open()) {
            try (SkillUsageCollector.Scope inner = SkillUsageCollector.open()) {
                SkillUsageCollector.report(event("perl", true));
                inner.commit();
            }
            // The outer request never commits.
        }

        assertThat(usesOf("perl")).isEqualTo(0);
    }

    @Test
    void automaticAndPinnedUsesAreCountedApart() {
        try (SkillUsageCollector.Scope request = SkillUsageCollector.open()) {
            SkillUsageCollector.report(event("perl", true));
            SkillUsageCollector.report(event("bash", false));
            request.commit();
        }

        SkillStatsReport.Row perl = row("perl");
        SkillStatsReport.Row bash = row("bash");
        assertThat(perl.automatic()).isEqualTo(1);
        assertThat(perl.pinned()).isEqualTo(0);
        assertThat(bash.automatic()).isEqualTo(0);
        assertThat(bash.pinned()).isEqualTo(1);
    }

    @Test
    void nothingIsCollectedWhileRecordingIsOff() {
        store.setRecordingGate(() -> false);
        try (SkillUsageCollector.Scope request = SkillUsageCollector.open()) {
            assertThat(SkillUsageCollector.isActive()).isFalse();
            SkillUsageCollector.report(event("perl", true));
            request.commit();
        }

        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    @Test
    void reportingOutsideAScopeIsANoOp() {
        assertThat(SkillUsageCollector.isActive()).isFalse();
        SkillUsageCollector.report(event("perl", true));

        assertThat(store.report(List.of(), range()).hasUsage()).isFalse();
    }

    @Test
    void aRepairRoundInsideOneDecisionCountsTheSkillOnce() {
        try (SkillUsageCollector.Scope decision = SkillUsageCollector.open()) {
            try (SkillUsageCollector.Scope first = SkillUsageCollector.open()) {
                SkillUsageCollector.report(event("perl", true));
                first.commit();
            }
            try (SkillUsageCollector.Scope repair = SkillUsageCollector.open()) {
                SkillUsageCollector.report(event("perl", true));
                repair.commit();
            }
            decision.commit();
        }

        assertThat(usesOf("perl")).isEqualTo(1);
    }

    private int usesOf(String skillId) {
        return (int) (row(skillId).automatic() + row(skillId).pinned());
    }

    private SkillStatsReport.Row row(String skillId) {
        return store.report(List.of(), range()).rows().stream()
            .filter(row -> row.skillId().equals(skillId))
            .findFirst()
            .orElseGet(() -> new SkillStatsReport.Row(skillId, null, false, false, 0, 0, 0, null, null, null, null,
                null, 0, List.of()));
    }

    private static SkillStatsReport.Range range() {
        return new SkillStatsReport.Range(SkillStatsReport.Granularity.DAY, 30);
    }

    private static SkillUsageEvent event(String skillId, boolean automatic) {
        return new SkillUsageEvent(skillId, skillId + " skill", automatic, SkillSurface.AGENT, 120,
            AiTokenizerType.ESTIMATE, "fp-" + skillId);
    }
}
