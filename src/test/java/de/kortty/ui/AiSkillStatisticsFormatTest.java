package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import java.time.LocalDate;
import java.util.Locale;
import org.testng.annotations.Test;

/**
 * The statistics text: period labels, signed growth, the unknown values, and the application language's number, percent and
 * date formats (the locale is passed explicitly here, so the test does not depend on the machine).
 */
public class AiSkillStatisticsFormatTest {

    @Test
    void periodLabelsAreUniqueWithinARangeOfYears() {
        assertThat(AiSkillStatisticsFormat.periodLabel("2026-11-01", Locale.GERMANY)).isEqualTo("01.11.26");
        assertThat(AiSkillStatisticsFormat.periodLabel("2025-11-01", Locale.GERMANY)).isNotEqualTo(
            AiSkillStatisticsFormat.periodLabel("2026-11-01", Locale.GERMANY));
        assertThat(AiSkillStatisticsFormat.periodLabel("2026-10", Locale.GERMANY)).contains("2026");
        assertThat(AiSkillStatisticsFormat.periodLabel("2026", Locale.GERMANY)).isEqualTo("2026");
    }

    @Test
    void growthIsSignedAndRoundedToWholeTokens() {
        assertThat(AiSkillStatisticsFormat.perMonth(12.4)).isEqualTo("+12");
        assertThat(AiSkillStatisticsFormat.perMonth(-7.6)).isEqualTo("-8");
        assertThat(AiSkillStatisticsFormat.perMonth(null)).isEqualTo(AiSkillStatisticsFormat.NONE);
        assertThat(AiSkillStatisticsFormat.percent(50.0, Locale.US)).isEqualTo("+50.0 %");
        assertThat(AiSkillStatisticsFormat.percent(-7.0, Locale.US)).isEqualTo("-7.0 %");
        assertThat(AiSkillStatisticsFormat.percent(Double.NEGATIVE_INFINITY, Locale.US)).isEqualTo(AiSkillStatisticsFormat.NONE);
    }

    @Test
    void percentagesUseTheDecimalSeparatorOfTheLanguage() {
        assertThat(AiSkillStatisticsFormat.percent(50.0, Locale.GERMANY)).isEqualTo("+50,0 %");
        assertThat(AiSkillStatisticsFormat.percent(50.0, Locale.US)).isEqualTo("+50.0 %");
    }

    @Test
    void countsUseTheGroupingOfTheLanguageNotOfTheMachine() {
        assertThat(AiSkillStatisticsFormat.number(1234L, Locale.GERMANY)).isEqualTo("1.234");
        assertThat(AiSkillStatisticsFormat.number(1234L, Locale.US)).isEqualTo("1,234");
    }

    @Test
    void unknownSizesSortBelowEveryRealOne() {
        assertThat(AiSkillStatisticsFormat.sortKey(null)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(AiSkillStatisticsFormat.sortKey(3.0)).isEqualTo(3.0);
        assertThat(AiSkillStatisticsFormat.tokens(null)).isEqualTo(AiSkillStatisticsFormat.NONE);
        assertThat(AiSkillStatisticsFormat.date(null, Locale.GERMANY)).isEqualTo(AiSkillStatisticsFormat.NONE);
        assertThat(AiSkillStatisticsFormat.date(LocalDate.of(2026, 11, 1), Locale.GERMANY)).isEqualTo("01.11.2026");
    }
}
