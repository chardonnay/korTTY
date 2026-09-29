package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** The choices and texts of the "stored script size per analysis" setting, with and without a policy cap. */
public class SnippetAnalysisContentLimitControlTest {

    private static final long MB = 1024L * 1024;
    private static final long KB = 1024L;

    @Test
    public void withoutAPolicyEveryPresetIsOffered() {
        assertThat(SnippetAnalysisContentLimitControl.itemsFor(1 * MB, null))
            .containsExactly(0L, 256 * KB, 512 * KB, 1 * MB, 2 * MB, 5 * MB).inOrder();
    }

    @Test
    public void aCustomValueFromTheSettingsFileIsKeptAsAnExtraChoice() {
        assertThat(SnippetAnalysisContentLimitControl.itemsFor(3 * MB, null))
            .containsExactly(0L, 256 * KB, 512 * KB, 1 * MB, 2 * MB, 3 * MB, 5 * MB).inOrder();
    }

    @Test
    public void aPolicyCapRemovesEverythingAboveItAndClampsTheShownValue() {
        long cap = 1 * MB;
        long shown = SnippetAnalysisContentLimitControl.shownValue(5 * MB, cap);
        assertThat(shown).isEqualTo(cap);
        assertThat(SnippetAnalysisContentLimitControl.itemsFor(shown, cap))
            .containsExactly(0L, 256 * KB, 512 * KB, 1 * MB).inOrder();
        // a user value below the cap stays as it is
        assertThat(SnippetAnalysisContentLimitControl.shownValue(512 * KB, cap)).isEqualTo(512 * KB);
    }

    @Test
    public void aPolicyOfZeroOffersOnlyOff() {
        long shown = SnippetAnalysisContentLimitControl.shownValue(2 * MB, 0L);
        assertThat(shown).isEqualTo(0L);
        assertThat(SnippetAnalysisContentLimitControl.itemsFor(shown, 0L)).containsExactly(0L);
    }

    @Test
    public void sizesAreFormattedInKilobytesAndMegabytes() {
        assertThat(SnippetAnalysisContentLimitControl.format(256 * KB)).isEqualTo("256 KB");
        assertThat(SnippetAnalysisContentLimitControl.format(5 * MB)).isEqualTo("5 MB");
        assertThat(SnippetAnalysisContentLimitControl.format(MB + MB / 2)).isEqualTo("1.5 MB");
        assertThat(SnippetAnalysisContentLimitControl.format(0L)).isNotEmpty();
    }
}
