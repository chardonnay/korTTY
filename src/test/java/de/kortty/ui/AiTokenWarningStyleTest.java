package de.kortty.ui;

import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.ThemeManager;
import de.kortty.model.AppDesign;
import de.kortty.model.Theme;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.paint.Color;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The AI profile list is drawn on whatever chrome the user picked, so its token-budget colours have
 * to survive every shipped surface. A hard-coded near-black for unremarkable profiles used to make
 * the AI Manager's profile list unreadable on every dark design.
 */
class AiTokenWarningStyleTest {

    /** WCAG AA for normal-size text. */
    private static final double MIN_CONTRAST = 4.5;

    @Test
    void unremarkableProfilesKeepTheSurfaceTextColour() {
        assertThat(AiTokenWarningStyle.listCellStyle(AiTokenWarningLevel.NONE, "#0d1117")).isEmpty();
        assertThat(AiTokenWarningStyle.listCellStyle(AiTokenWarningLevel.NONE, "#ffffff")).isEmpty();
        assertThat(AiTokenWarningStyle.listCellStyle(null, "#0d1117")).isEmpty();
    }

    @Test
    void warningColoursStayReadableOnEveryShippedSurface() throws Exception {
        for (String background : shippedBackgrounds()) {
            for (AiTokenWarningLevel level : List.of(AiTokenWarningLevel.YELLOW, AiTokenWarningLevel.RED)) {
                String style = AiTokenWarningStyle.listCellStyle(level, background);
                String fill = textFill(style);
                assertWithMessage("%s text %s on %s", level, fill, background)
                    .that(contrastRatio(fill, background))
                    .isAtLeast(MIN_CONTRAST);
            }
        }
    }

    @Test
    void anUnknownSurfaceFallsBackToTheDarkChromeColours() {
        assertThat(AiTokenWarningStyle.listCellStyle(AiTokenWarningLevel.RED, null))
            .isEqualTo(AiTokenWarningStyle.listCellStyle(AiTokenWarningLevel.RED, "#000000"));
        assertThat(AiTokenWarningStyle.listCellStyle(AiTokenWarningLevel.YELLOW, "not-a-colour"))
            .isEqualTo(AiTokenWarningStyle.listCellStyle(AiTokenWarningLevel.YELLOW, "#000000"));
    }

    /** Every background the profile list can be painted on: the app designs and the built-in themes. */
    private static List<String> shippedBackgrounds() throws Exception {
        List<String> backgrounds = new ArrayList<>();
        for (AppDesign design : AppDesign.values()) {
            if (design != AppDesign.NORMAL) {
                backgrounds.add(AppDesignStyleSupport.backgroundColor(design));
            }
        }
        Path configDir = Files.createTempDirectory("kortty-theme-contrast");
        ThemeManager themeManager = new ThemeManager(configDir);
        themeManager.load();
        for (Theme theme : themeManager.getThemes()) {
            backgrounds.add(theme.getBackgroundColor());
        }
        return backgrounds;
    }

    private static String textFill(String style) {
        assertThat(style).startsWith("-fx-text-fill: ");
        return style.substring("-fx-text-fill: ".length(), style.length() - 1);
    }

    private static double contrastRatio(String foreground, String background) {
        double lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
        double darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(String hex) {
        Color color = Color.web(hex);
        return 0.2126 * channel(color.getRed())
            + 0.7152 * channel(color.getGreen())
            + 0.0722 * channel(color.getBlue());
    }

    private static double channel(double value) {
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
