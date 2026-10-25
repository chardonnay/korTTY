package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.css.StyleOrigin;
import org.testng.annotations.Test;

/**
 * AtlantaFX caps every dialog pane at 600 px. A dialog that asks for a width of its own lifts the cap unless
 * its code set the maximum itself; a dialog sized by its content keeps it, and the fit measures it at the width
 * the capped window opens at. Normal (Modena) has no cap, so nothing changes there.
 */
class DialogContentFitWidthTest {

    private static final double CAP = 600;

    @Test
    void anExplicitWidthLiftsTheThemesCap() {
        assertThat(DialogContentFit.liftsThemeCap(1120, StyleOrigin.USER_AGENT)).isTrue();
        assertThat(DialogContentFit.liftsThemeCap(760, StyleOrigin.AUTHOR)).isTrue();
        // Before the first CSS pass the theme has not set the maximum yet; it must not win afterwards either.
        assertThat(DialogContentFit.liftsThemeCap(1240, null)).isTrue();
    }

    @Test
    void contentSizedPanesAndMaximaSetInCodeKeepTheirLimit() {
        assertThat(DialogContentFit.liftsThemeCap(-1, StyleOrigin.USER_AGENT)).isFalse();
        assertThat(DialogContentFit.liftsThemeCap(0, StyleOrigin.USER_AGENT)).isFalse();
        assertThat(DialogContentFit.liftsThemeCap(900, StyleOrigin.USER)).isFalse();
    }

    @Test
    void theOpeningWidthIsThePreferredWidthWithinTheBounds() {
        // AtlantaFX: a content-sized pane opens at the cap, so the fit measures it there.
        assertThat(DialogContentFit.openingWidth(196, 1048, CAP)).isEqualTo(CAP);
        // Normal: no cap, the preferred width.
        assertThat(DialogContentFit.openingWidth(192, 1044, Double.MAX_VALUE)).isEqualTo(1044);
        // A minimum above the cap wins, as JavaFX sizes the window.
        assertThat(DialogContentFit.openingWidth(1000, 1120, CAP)).isEqualTo(1000);
        assertThat(DialogContentFit.openingWidth(102, 360, CAP)).isEqualTo(360);
    }

    @Test
    void theCapIsLiftedBeforeAndAfterTheFitAndTheFitStartsAtTheOpeningWidth() throws Exception {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/DialogContentFit.java"));
        int before = source.indexOf("honorRequestedWidth(pane);\n            Rectangle2D screen = screenFor(dialog.getOwner());");
        int fit = source.indexOf("fit(pane, screen.getWidth() * SCREEN_SHARE, screen.getHeight() * SCREEN_SHARE);", before);
        int after = source.indexOf("honorRequestedWidth(pane);", fit);

        assertThat(before).isAtLeast(0);
        assertThat(fit).isGreaterThan(before);
        assertThat(after).isGreaterThan(fit);
        assertThat(source).contains(
                "double startWidth = openingWidth(pane.minWidth(-1), pane.prefWidth(-1), pane.maxWidth(-1));");
    }
}
