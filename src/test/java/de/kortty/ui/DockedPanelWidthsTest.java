package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class DockedPanelWidthsTest {

    private static final double[] PREFS = {450, 500, 420};
    private static final double[] MINS = {200, 280, 240};

    @Test
    void withoutDeficitEveryPanelKeepsItsWidth() {
        assertThat(DockedPanelWidths.shrink(PREFS, MINS, 0)).isEqualTo(PREFS);
        assertThat(DockedPanelWidths.shrink(PREFS, MINS, -500)).isEqualTo(PREFS);
    }

    @Test
    void deficitIsTakenInProportionToEachPanelsRoom() {
        // Room: 250 + 220 + 180 = 650; half of it is missing.
        double[] widths = DockedPanelWidths.shrink(PREFS, MINS, 325);
        assertThat(widths[0]).isWithin(1e-9).of(325);
        assertThat(widths[1]).isWithin(1e-9).of(390);
        assertThat(widths[2]).isWithin(1e-9).of(330);
    }

    @Test
    void neverBelowTheMinimum() {
        assertThat(DockedPanelWidths.shrink(PREFS, MINS, 5000)).isEqualTo(MINS);
    }

    @Test
    void panelAlreadyAtItsMinimumGivesNothing() {
        double[] widths = DockedPanelWidths.shrink(new double[] {200, 500}, new double[] {200, 280}, 100);
        assertThat(widths[0]).isEqualTo(200.0);
        assertThat(widths[1]).isWithin(1e-9).of(400);
    }
}
