package de.kortty.model;

import org.testng.annotations.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static com.google.common.truth.Truth.assertThat;

class AutomationJournalConfigTest {

    private static final OffsetDateTime END = OffsetDateTime.parse("2026-09-30T10:00:00+02:00");

    @Test
    void daysRetentionCountsFromTheEndOfTheRun() {
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setRetentionDays(14);

        assertThat(config.computeExpiry(END, null)).isEqualTo(END.plusDays(14));
    }

    @Test
    void fixedDateExpiresAtTheEndOfThatDay() {
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setRetentionMode(AutomationJournalRetentionMode.FIXED_DATE);
        config.setExpiryDate("2026-12-31");

        assertThat(config.computeExpiry(END, null)).isEqualTo(
            LocalDate.of(2027, 1, 1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime());
    }

    @Test
    void invalidFixedDateFallsBackToDays() {
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setRetentionMode(AutomationJournalRetentionMode.FIXED_DATE);
        config.setExpiryDate("not a date");

        assertThat(config.computeExpiry(END, null)).isEqualTo(END.plusDays(14));
    }

    @Test
    void noRetentionNeverExpiresUnlessAnAdminCapsIt() {
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setRetentionMode(AutomationJournalRetentionMode.NONE);

        assertThat(config.computeExpiry(END, null)).isNull();
        assertThat(config.computeExpiry(END, 30)).isEqualTo(END.plusDays(30));
    }

    @Test
    void adminCapShortensALongerUserRetention() {
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setRetentionDays(90);

        assertThat(config.computeExpiry(END, 7)).isEqualTo(END.plusDays(7));
        assertThat(config.computeExpiry(END, 365)).isEqualTo(END.plusDays(90));
    }

    @Test
    void effectiveLimitTreatsZeroAsUnlimited() {
        assertThat(AutomationJournalConfig.effectiveLimit(0, null)).isEqualTo(0);
        assertThat(AutomationJournalConfig.effectiveLimit(10, null)).isEqualTo(10);
        assertThat(AutomationJournalConfig.effectiveLimit(0, 5)).isEqualTo(5);
        assertThat(AutomationJournalConfig.effectiveLimit(10, 5)).isEqualTo(5);
        assertThat(AutomationJournalConfig.effectiveLimit(3, 5)).isEqualTo(3);
    }

    @Test
    void modesDecideKeepingAndSummarizing() {
        assertThat(AutomationJournalKeepMode.ONLY_ON_FAILURE.keeps(AutomationRunStatus.SUCCESS)).isFalse();
        assertThat(AutomationJournalKeepMode.ONLY_ON_FAILURE.keeps(AutomationRunStatus.BLOCKED)).isTrue();
        assertThat(AutomationJournalKeepMode.ALWAYS.keeps(AutomationRunStatus.SUCCESS)).isTrue();
        assertThat(AutomationJournalAiMode.ON_FAILURE.summarizes(AutomationRunStatus.SUCCESS)).isFalse();
        assertThat(AutomationJournalAiMode.ON_FAILURE.summarizes(AutomationRunStatus.CANCELLED)).isTrue();
        assertThat(AutomationJournalAiMode.OFF.summarizes(AutomationRunStatus.FAILED)).isFalse();
    }

    @Test
    void copyConstructorCopiesEveryField() {
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setEnabled(true);
        config.setCaptureInput(false);
        config.setAiMode(AutomationJournalAiMode.ON_FAILURE);
        config.setAiProfileId("local");
        config.setKeepMode(AutomationJournalKeepMode.ONLY_ON_FAILURE);
        config.setRetentionMode(AutomationJournalRetentionMode.FIXED_DATE);
        config.setRetentionDays(3);
        config.setExpiryDate("2027-01-01");
        config.setMaxJournals(5);
        config.setMaxStorageMb(100);
        config.setDedupEnabled(false);

        AutomationJournalConfig copy = new AutomationJournalConfig(config);

        assertThat(copy).isNotSameInstanceAs(config);
        assertThat(copy.isEnabled()).isTrue();
        assertThat(copy.isCaptureInput()).isFalse();
        assertThat(copy.getAiMode()).isEqualTo(AutomationJournalAiMode.ON_FAILURE);
        assertThat(copy.getAiProfileId()).isEqualTo("local");
        assertThat(copy.getKeepMode()).isEqualTo(AutomationJournalKeepMode.ONLY_ON_FAILURE);
        assertThat(copy.getRetentionMode()).isEqualTo(AutomationJournalRetentionMode.FIXED_DATE);
        assertThat(copy.getRetentionDays()).isEqualTo(3);
        assertThat(copy.getExpiryDate()).isEqualTo("2027-01-01");
        assertThat(copy.getMaxJournals()).isEqualTo(5);
        assertThat(copy.getMaxStorageMb()).isEqualTo(100);
        assertThat(copy.isDedupEnabled()).isFalse();
    }
}
