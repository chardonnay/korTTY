package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import java.time.LocalDate;
import org.testng.annotations.Test;

/** The statistics text: period labels, signed growth and the unknown values. */
public class AiSkillStatisticsFormatTest {

    @Test
    void periodLabelsAreUniqueWithinARangeOfYears() {
        assertThat(AiSkillStatisticsFormat.periodLabel("2026-11-01")).isEqualTo("01.11.26");
        assertThat(AiSkillStatisticsFormat.periodLabel("2025-11-01")).isNotEqualTo(
            AiSkillStatisticsFormat.periodLabel("2026-11-01"));
        assertThat(AiSkillStatisticsFormat.periodLabel("2026-10")).isEqualTo("10.2026");
        assertThat(AiSkillStatisticsFormat.periodLabel("2026")).isEqualTo("2026");
    }

    @Test
    void growthIsSignedAndRoundedToWholeTokens() {
        assertThat(AiSkillStatisticsFormat.perMonth(12.4)).isEqualTo("+12");
        assertThat(AiSkillStatisticsFormat.perMonth(-7.6)).isEqualTo("-8");
        assertThat(AiSkillStatisticsFormat.perMonth(null)).isEqualTo(AiSkillStatisticsFormat.NONE);
        assertThat(AiSkillStatisticsFormat.percent(50.0)).isEqualTo("+50.0 %");
        assertThat(AiSkillStatisticsFormat.percent(Double.NEGATIVE_INFINITY)).isEqualTo(AiSkillStatisticsFormat.NONE);
    }

    @Test
    void unknownSizesSortBelowEveryRealOne() {
        assertThat(AiSkillStatisticsFormat.sortKey(null)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(AiSkillStatisticsFormat.sortKey(3.0)).isEqualTo(3.0);
        assertThat(AiSkillStatisticsFormat.tokens(null)).isEqualTo(AiSkillStatisticsFormat.NONE);
        assertThat(AiSkillStatisticsFormat.date(null)).isEqualTo(AiSkillStatisticsFormat.NONE);
        assertThat(AiSkillStatisticsFormat.date(LocalDate.of(2026, 11, 1))).isEqualTo("01.11.2026");
    }
}
