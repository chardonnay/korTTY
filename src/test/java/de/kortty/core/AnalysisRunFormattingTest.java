package de.kortty.core;

import org.testng.annotations.Test;

import java.text.NumberFormat;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class AnalysisRunFormattingTest {

    @Test
    void formatsDurationAsMinutesUntilItPassesAnHour() {
        assertThat(AnalysisRunFormatting.formatDuration(0)).isEqualTo("00:00");
        assertThat(AnalysisRunFormatting.formatDuration(9)).isEqualTo("00:09");
        assertThat(AnalysisRunFormatting.formatDuration(605)).isEqualTo("10:05");
        assertThat(AnalysisRunFormatting.formatDuration(3_600)).isEqualTo("1:00:00");
        assertThat(AnalysisRunFormatting.formatDuration(3_661)).isEqualTo("1:01:01");
        assertThat(AnalysisRunFormatting.formatDuration(-5)).isEqualTo("00:00");
    }

    @Test
    void tokenSummaryGroupsNumbersForTheRequestedLocale() {
        AiTokenUsage usage = new AiTokenUsage(1_204, 388, 1_592);

        String german = AnalysisRunFormatting.tokenSummary(usage, Locale.GERMANY);
        assertThat(german).contains("1.204");
        assertThat(german).contains("1.592");

        String english = AnalysisRunFormatting.tokenSummary(usage, Locale.US);
        assertThat(english).contains("1,204");
        assertThat(english).contains("388");
        assertThat(english).contains("1,592");

        // The locale-less overload follows the machine, exactly like the progress window always did.
        assertThat(AnalysisRunFormatting.tokenSummary(usage))
            .contains(NumberFormat.getIntegerInstance().format(1_204));
    }

    @Test
    void missingUsageIsReportedAsNotReportedRatherThanZero() {
        String missing = AnalysisRunFormatting.tokenSummary(null, Locale.US);
        assertThat(missing).doesNotContain("0");
        assertThat(missing).isNotEmpty();
        assertThat(AnalysisRunFormatting.tokenSummary(null)).isEqualTo(missing);
    }
}
