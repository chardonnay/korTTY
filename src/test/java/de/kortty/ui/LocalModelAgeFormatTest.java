package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * How the model table renders a publication age. The unit is staggered on purpose: "847 days"
 * makes the reader do the division, and telling a two-year-old architecture from a two-month-old
 * one is what the column is for.
 */
class LocalModelAgeFormatTest {

    @Test
    void recentModelsAreShownInDays() {
        assertThat(LocalModelManagerPane.formatAgeInDays(1)).contains("1");
        assertThat(LocalModelManagerPane.formatAgeInDays(30)).contains("30");
        assertThat(LocalModelManagerPane.formatAgeInDays(89)).contains("89");
    }

    @Test
    void olderModelsSwitchToMonthsAndThenYears() {
        // 90 days is the first month-rendered value, 730 the first year-rendered one.
        assertThat(LocalModelManagerPane.formatAgeInDays(90)).contains("3");
        assertThat(LocalModelManagerPane.formatAgeInDays(365)).contains("12");
        assertThat(LocalModelManagerPane.formatAgeInDays(730)).contains("2");
        assertThat(LocalModelManagerPane.formatAgeInDays(1095)).contains("3");
    }

    @Test
    void theBoundariesChangeUnitExactlyOnce() {
        assertThat(LocalModelManagerPane.formatAgeInDays(89))
            .isNotEqualTo(LocalModelManagerPane.formatAgeInDays(90));
        assertThat(LocalModelManagerPane.formatAgeInDays(729))
            .isNotEqualTo(LocalModelManagerPane.formatAgeInDays(730));
    }

    /** An unknown age must read as unknown, never as "0 days old". */
    @Test
    void anUnknownAgeRendersAsADash() {
        assertThat(LocalModelManagerPane.formatAgeInDays(-1)).isEqualTo("—");
        assertThat(LocalModelManagerPane.formatAgeInDays(0))
            .isNotEqualTo(LocalModelManagerPane.formatAgeInDays(-1));
    }
}
