package de.kortty.stats;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AiTokenizerType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * The dialog's model: periods, the stacked series with "others", the headline figures, growth and the hints. Every
 * report is taken on {@link #TODAY}; usages are recorded on the days the test names.
 */
public class SkillStatsReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private StatsTestClock clock;
    private SkillStatsStore store;

    @Test
    void theChartShowsTheTopEightByAutomaticUsesAndSumsTheRestAsOthers() throws IOException {
        open();
        List<AiSkill> skills = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            String id = "skill-" + index;
            skills.add(skill(id, "Skill " + index, "Text " + index));
            for (int use = 0; use < index; use++) {
                store.recordCommitted(List.of(usage(id, true, 50, "fp-" + index)));
            }
        }

        SkillStatsReport report = store.report(skills, day(30));

        assertThat(report.series()).hasSize(9);
        assertThat(report.series().get(0).skillId()).isEqualTo("skill-10");
        assertThat(report.series().get(7).skillId()).isEqualTo("skill-3");
        SkillStatsReport.Series others = report.series().get(8);
        assertThat(others.skillId()).isNull();
        assertThat(others.automatic().get(report.periods().size() - 1)).isEqualTo(3L);
    }

    @Test
    void pinnedUsesAreKeptApartFromAutomaticOnes() throws IOException {
        open();
        store.recordCommitted(List.of(usage("perl", true, 50, "fp-1"), usage("perl", false, 50, "fp-1")));
        store.recordCommitted(List.of(usage("perl", false, 50, "fp-1")));

        SkillStatsReport.Series perl = store.report(List.of(skill("perl", "Perl", "Use strict.")), day(30))
            .series().get(0);

        assertThat(perl.automatic().get(29)).isEqualTo(1L);
        assertThat(perl.pinned().get(29)).isEqualTo(2L);
    }

    @Test
    void theHeadlineFiguresSumTheRangeAndAverageTheActiveSkills() throws IOException {
        open();
        AiSkill perl = skill("perl", "Perl", "Use strict and warnings for every script.");
        AiSkill bash = skill("bash", "Bash", "Quote variables.");
        store.recordCommitted(List.of(usage("perl", true, 400, "fp-perl"), usage("bash", true, 100, "fp-bash")));

        SkillStatsReport report = store.report(List.of(perl, bash), day(30));

        assertThat(report.kpis().automaticUses()).isEqualTo(2L);
        assertThat(report.kpis().sentTokens()).isEqualTo(500L);
        int perlSize = SkillSize.of(perl, AiTokenizerType.ESTIMATE).tokens();
        int bashSize = SkillSize.of(bash, AiTokenizerType.ESTIMATE).tokens();
        assertThat(report.kpis().averageSizeTokens()).isWithin(0.001).of((perlSize + bashSize) / 2.0);
    }

    @Test
    void growthIsTakenFromTheFirstSnapshotInTheWindow() throws IOException {
        open();
        clock.setDate(TODAY.minusDays(40));
        store.recordCommitted(List.of(usage("perl", true, 100, "fp-old")));
        clock.setDate(TODAY.minusDays(12));
        store.recordCommitted(List.of(usage("perl", true, 300, "fp-new")));
        clock.setDate(TODAY);
        AiSkill perl = skill("perl", "Perl", "word ".repeat(300));

        SkillStatsReport.Row row = store.report(List.of(perl), day(30)).rows().stream()
            .filter(candidate -> candidate.skillId().equals("perl"))
            .findFirst()
            .orElseThrow();

        int current = SkillSize.of(perl, AiTokenizerType.ESTIMATE).tokens();
        double months = 12 / 30.44;
        assertThat(row.growthTokensPerMonth()).isWithin(0.001).of((current - 300) / months);
        assertThat(row.growthPercent()).isWithin(0.001).of((current - 300) * 100.0 / 300);
    }

    @Test
    void oneDayBaselineGivesNoMonthlyGrowthButKeepsThePercentage() throws IOException {
        open();
        clock.setDate(TODAY.minusDays(1));
        store.recordCommitted(List.of(usage("perl", true, 76, "fp-yesterday")));
        clock.setDate(TODAY);
        AiSkill perl = skill("perl", "Perl", "word ".repeat(117));

        SkillStatsReport.Row row = store.report(List.of(perl), day(30)).rows().stream()
            .filter(candidate -> candidate.skillId().equals("perl"))
            .findFirst()
            .orElseThrow();

        assertThat(row.growthTokensPerMonth()).isNull();
        assertThat(row.growthPercent()).isNotNull();
    }

    @Test
    void aSkillThatMoreThanDoubledWithinThirtyDaysGetsAHint() throws IOException {
        open();
        clock.setDate(TODAY.minusDays(40));
        store.recordCommitted(List.of(usage("perl", true, 100, "fp-old")));
        clock.setDate(TODAY);
        AiSkill perl = skill("perl", "Perl", "word ".repeat(300));

        List<SkillStatsReport.Hint> hints = store.report(List.of(perl), day(30)).hints();

        assertThat(hints).containsExactly(new SkillStatsReport.Hint("perl", "Perl",
            SkillStatsReport.HintKind.GREW_TWICE_IN_30_DAYS,
            SkillSize.of(perl, AiTokenizerType.ESTIMATE).tokens()));
    }

    @Test
    void aLargeSkillGetsAHintAboveAThousandTokens() throws IOException {
        open();
        AiSkill big = skill("big", "Big", "word ".repeat(1200));

        List<SkillStatsReport.Hint> hints = store.report(List.of(big), day(30)).hints();

        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).kind()).isEqualTo(SkillStatsReport.HintKind.OVER_1000_TOKENS);
    }

    @Test
    void aSkillUnusedForNinetyDaysGetsAHintAndARecentOneDoesNot() throws IOException {
        open();
        clock.setDate(TODAY.minusDays(100));
        store.recordCommitted(List.of(usage("old", true, 50, "fp-old")));
        clock.setDate(TODAY);
        store.recordCommitted(List.of(usage("recent", true, 50, "fp-recent")));
        AiSkill old = skill("old", "Old", "Short.");
        AiSkill recent = skill("recent", "Recent", "Short.");

        List<SkillStatsReport.Hint> hints = store.report(List.of(old, recent), day(30)).hints();

        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).skillId()).isEqualTo("old");
        assertThat(hints.get(0).kind()).isEqualTo(SkillStatsReport.HintKind.UNUSED_90_DAYS);
    }

    @Test
    void monthAndYearPeriodsAreComplete() throws IOException {
        open();
        store.recordCommitted(List.of(usage("perl", true, 50, "fp-1")));

        SkillStatsReport months = store.report(List.of(),
            new SkillStatsReport.Range(SkillStatsReport.Granularity.MONTH, 12));
        SkillStatsReport years = store.report(List.of(),
            new SkillStatsReport.Range(SkillStatsReport.Granularity.YEAR, SkillStatsReport.Range.ALL));

        assertThat(months.periods()).hasSize(12);
        assertThat(months.periods().get(11)).isEqualTo("2026-11");
        assertThat(months.hasUsage()).isTrue();
        assertThat(years.periods()).containsExactly("2026");
    }

    @Test
    void anEmptyStoreReportsNoUsageButTheConfiguredSkills() throws IOException {
        open();

        SkillStatsReport report = store.report(List.of(skill("perl", "Perl", "Use strict.")), day(30));

        assertThat(report.hasUsage()).isFalse();
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).current()).isTrue();
        assertThat(report.rows().get(0).automatic()).isEqualTo(0L);
        assertThat(report.kpis().averageSizeTokens()).isNotNull();
    }

    @Test
    void theHistoryHasOnePointPerDayTheLastSnapshotOfTheDay() throws IOException {
        open();
        store.recordCommitted(List.of(usage("perl", true, 100, "fp-1")));
        store.recordCommitted(List.of(usage("perl", true, 120, "fp-2")));
        store.recordCommitted(List.of(usage("perl", true, 130, "fp-3")));

        SkillStatsReport.Row row = store.report(List.of(skill("perl", "Perl", "Use strict.")), day(30)).rows().get(0);

        assertThat(row.history()).hasSize(1);
        assertThat(row.history().get(0).tokens()).isEqualTo(130);
    }

    @Test
    void growthComparesOnlySizesCountedWithTheLiveTokenizer() throws IOException {
        open();
        AiSkill perl = skill("perl", "Perl", "Use strict and warnings for every script, and quote every variable.");
        String fingerprint = SkillSize.fingerprint(perl);
        clock.setDate(TODAY.minusDays(60));
        store.recordCommitted(List.of(sized(perl, AiTokenizerType.ESTIMATE, fingerprint)));
        clock.setDate(TODAY);
        store.recordCommitted(List.of(sized(perl, AiTokenizerType.O200K_BASE, fingerprint)));

        SkillStatsReport.Row row = store.report(List.of(perl), day(90)).rows().get(0);

        assertThat(row.currentTokens()).isEqualTo(SkillSize.of(perl, AiTokenizerType.O200K_BASE).tokens());
        assertThat(row.growthPercent()).isWithin(0.001).of(0.0);
    }

    private void open() throws IOException {
        Path file = Files.createTempDirectory("kortty-stats-report-").resolve(SkillStatsStore.FILE_NAME);
        clock = new StatsTestClock(TODAY);
        store = new SkillStatsStore(file, clock);
        store.setRecordingGate(() -> true);
    }

    private static SkillStatsReport.Range day(int days) {
        return new SkillStatsReport.Range(SkillStatsReport.Granularity.DAY, days);
    }

    private static SkillUsageEvent usage(String skillId, boolean automatic, int tokens, String fingerprint) {
        return new SkillUsageEvent(skillId, skillId, automatic, SkillSurface.AGENT, tokens,
            AiTokenizerType.ESTIMATE, fingerprint);
    }

    private static SkillUsageEvent sized(AiSkill skill, AiTokenizerType tokenizer, String fingerprint) {
        return new SkillUsageEvent(skill.getId(), skill.getName(), true, SkillSurface.AGENT,
            SkillSize.of(skill, tokenizer).tokens(), tokenizer, fingerprint);
    }

    private static AiSkill skill(String id, String name, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setContent(content);
        skill.setTarget(AiSkillTarget.AGENT);
        skill.setEnabled(true);
        return skill;
    }
}
