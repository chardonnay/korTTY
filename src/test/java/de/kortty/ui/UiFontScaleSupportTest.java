package de.kortty.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The UI font scale's pure logic: the auto formula, the generated stylesheet, and the stylesheet
 * bookkeeping. None of it needs a JavaFX toolkit, so it runs on every CI platform.
 */
class UiFontScaleSupportTest {

    // --- the auto formula -------------------------------------------------------------------

    @Test
    void autoPercentStaysAtOneHundredForA1080pScreen() {
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(1080)).isEqualTo(100);
    }

    /**
     * The regression a naive implementation produces: a Retina panel reports ~1117 <em>logical</em>
     * pixels because macOS already scaled it. Multiplying by the output scale would read 2234 and
     * enlarge a UI that is already the right size.
     */
    @Test
    void autoPercentStaysAtOneHundredForARetinaLogicalHeight() {
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(1117)).isEqualTo(100);
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(1329)).isEqualTo(100);
    }

    @Test
    void autoPercentGrowsForA1440LogicalScreen() {
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(1440)).isEqualTo(110);
    }

    /** The case the feature exists for: 4K at 100% OS scale, where JavaFX really does get 2160. */
    @Test
    void autoPercentGrowsForA4kScreenAtFullOsScale() {
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(2160)).isEqualTo(125);
    }

    @Test
    void autoPercentReachesItsCapOnAVeryTallScreen() {
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(2880))
            .isEqualTo(UiFontScaleSupport.AUTO_MAX_PERCENT);
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(4320))
            .isEqualTo(UiFontScaleSupport.AUTO_MAX_PERCENT);
    }

    @Test
    void autoPercentNeverShrinksBelowOneHundred() {
        assertThat(UiFontScaleSupport.autoPercentForLogicalHeight(600))
            .isEqualTo(UiFontScaleSupport.AUTO_MIN_PERCENT);
    }

    /** Guards the step table against an edit that accidentally makes a taller screen scale less. */
    @Test
    void autoPercentIsMonotonicAcrossTheWholeRange() {
        int previous = Integer.MIN_VALUE;
        for (int height = 400; height <= 4000; height += 10) {
            int percent = UiFontScaleSupport.autoPercentForLogicalHeight(height);
            assertThat(percent).isAtLeast(previous);
            previous = percent;
        }
    }

    // --- the generated stylesheet -----------------------------------------------------------

    @Test
    void basePxIsThirteenAtOneHundredPercent() {
        assertThat(UiFontScaleSupport.basePx(100)).isWithin(0.001).of(13.0);
    }

    @Test
    void basePxScalesLinearlyWithThePercent() {
        assertThat(UiFontScaleSupport.basePx(160)).isWithin(0.001).of(20.8);
        assertThat(UiFontScaleSupport.basePx(80)).isWithin(0.001).of(10.4);
    }

    @Test
    void basePxClampsAnOutOfRangePercent() {
        assertThat(UiFontScaleSupport.basePx(5000)).isEqualTo(UiFontScaleSupport.basePx(160));
        assertThat(UiFontScaleSupport.basePx(1)).isEqualTo(UiFontScaleSupport.basePx(80));
    }

    /**
     * The three subtree roots. {@code .context-menu} is the one that is easy to forget and the most
     * visible when missing: without it a menu bar grows while its dropdown items stay put.
     */
    @Test
    void buildCssDeclaresTheThreeAnchorSelectors() {
        String css = UiFontScaleSupport.buildCss(160);

        assertThat(css).contains(".root");
        assertThat(css).contains("." + UiFontScaleSupport.SCALE_ROOT_STYLE_CLASS);
        assertThat(css).contains(".dialog-pane");
        assertThat(css).contains(".context-menu");
        assertThat(css).contains("20.80px");
    }

    /**
     * Modena declares {@code .tooltip { -fx-font-size: 0.85em }}. An author rule in px would beat
     * it and flatten the intended relative shrink, so tooltips have to scale by inheritance.
     */
    @Test
    void buildCssLeavesTooltipsToInheritance() {
        assertThat(UiFontScaleSupport.buildCss(160)).doesNotContain(".tooltip");
    }

    @Test
    void buildCssDeclaresNothingButFontSizes() {
        String css = UiFontScaleSupport.buildCss(125);

        assertThat(css.split("-fx-font-size", -1)).hasLength(4); // three declarations
        assertThat(css).doesNotContain("-fx-text-fill");
        assertThat(css).doesNotContain("-fx-background-color");
    }

    @Test
    void stylesheetUrlIsCachedPerPercent() {
        String first = UiFontScaleSupport.stylesheetUrl(125);
        String second = UiFontScaleSupport.stylesheetUrl(125);

        assertThat(first).isNotNull();
        assertThat(second).isSameInstanceAs(first);
        assertThat(UiFontScaleSupport.stylesheetUrl(110)).isNotEqualTo(first);
    }

    // --- stylesheet bookkeeping -------------------------------------------------------------

    @Test
    void applyToStylesheetsAppendsTheScaleSheetAfterTheExistingOnes() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");

        UiFontScaleSupport.applyToStylesheets(stylesheets, 125);

        assertThat(stylesheets)
            .containsExactly("base.css", UiFontScaleSupport.stylesheetUrl(125)).inOrder();
    }

    /** Without the removal, stepping through sizes would pile up one dead sheet per step. */
    @Test
    void applyToStylesheetsReplacesThePreviousScaleSheet() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");

        UiFontScaleSupport.applyToStylesheets(stylesheets, 130);
        UiFontScaleSupport.applyToStylesheets(stylesheets, 110);

        assertThat(stylesheets)
            .containsExactly("base.css", UiFontScaleSupport.stylesheetUrl(110)).inOrder();
    }

    @Test
    void applyToStylesheetsIsIdempotent() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");

        UiFontScaleSupport.applyToStylesheets(stylesheets, 125);
        UiFontScaleSupport.applyToStylesheets(stylesheets, 125);

        assertThat(stylesheets).hasSize(2);
    }

    /** The sheet is applied even at 100%, because that is what pins the base across platforms. */
    @Test
    void applyToStylesheetsStillAppliesAtOneHundredPercent() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");

        UiFontScaleSupport.applyToStylesheets(stylesheets, 100);

        assertThat(stylesheets).contains(UiFontScaleSupport.stylesheetUrl(100));
    }
}
