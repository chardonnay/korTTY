package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

/**
 * The zoom slider's track is powers of two, so the fitted size sits in the middle and one step to
 * either side halves or doubles it. A linear track over 0.25..4 would put the fitted size at a
 * fifth of the way, where nobody looks for it.
 */
public class SnippetDiagramZoomTest {

    @Test
    void theFittedSizeSitsInTheMiddleAndEachStepHalvesOrDoubles() {
        assertThat(SnippetDiagramView.sliderValueForZoomFactor(1.0)).isEqualTo(0.0);
        assertThat(SnippetDiagramView.sliderValueForZoomFactor(2.0)).isWithin(1e-9).of(1.0);
        assertThat(SnippetDiagramView.sliderValueForZoomFactor(0.5)).isWithin(1e-9).of(-1.0);
        assertThat(SnippetDiagramView.zoomFactorForSliderValue(0.0)).isEqualTo(1.0);
        assertThat(SnippetDiagramView.zoomFactorForSliderValue(1.0)).isWithin(1e-9).of(2.0);
        assertThat(SnippetDiagramView.zoomFactorForSliderValue(-1.0)).isWithin(1e-9).of(0.5);
    }

    @Test
    void everyFactorSurvivesTheRoundTripThroughTheTrack() {
        for (double factor : new double[] {0.25, 0.4, 0.75, 1.0, 1.15, 1.6, 2.5, 4.0}) {
            assertThat(SnippetDiagramView.zoomFactorForSliderValue(
                SnippetDiagramView.sliderValueForZoomFactor(factor))).isWithin(1e-9).of(factor);
        }
    }

    @Test
    void aFactorPastTheEndsPinsTheKnobToTheEndItIsPinnedTo() {
        assertThat(SnippetDiagramView.sliderValueForZoomFactor(40.0)).isEqualTo(2.0);
        assertThat(SnippetDiagramView.sliderValueForZoomFactor(0.01)).isEqualTo(-2.0);
        assertThat(SnippetDiagramView.zoomFactorForSliderValue(9.0)).isWithin(1e-9).of(4.0);
        assertThat(SnippetDiagramView.zoomFactorForSliderValue(-9.0)).isWithin(1e-9).of(0.25);
    }
}
