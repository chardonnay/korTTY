package de.kortty.ui;

import de.kortty.model.AppDesign;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

class AppDesignStyleSupportTest {

    @Test
    void allAtlantaFxProfilesAppearFirstInTheSettingsOrder() {
        boolean reachedKorTTYDesigns = false;
        int atlantaFxProfiles = 0;
        for (AppDesign design : AppDesign.values()) {
            if (design.isAtlantaFx()) {
                assertThat(reachedKorTTYDesigns).isFalse();
                atlantaFxProfiles++;
            } else {
                reachedKorTTYDesigns = true;
            }
        }
        assertThat(atlantaFxProfiles).isEqualTo(7);
        assertThat(AppDesign.values()[0]).isEqualTo(AppDesign.ATLANTAFX_PRIMER_DARK);
    }

    @Test
    void matrixStylesheetResourceIsAvailable() {
        assertThat(AppDesignStyleSupport.getMatrixStylesheetUrl()).isNotNull();
    }

    @Test
    void holographicStylesheetResourceIsAvailable() {
        assertThat(AppDesignStyleSupport.getHolographicStylesheetUrl()).isNotNull();
    }

    @Test
    void tacticalStylesheetResourceIsAvailable() {
        assertThat(AppDesignStyleSupport.getTacticalStylesheetUrl()).isNotNull();
    }

    @Test
    void elegantStylesheetResourceIsAvailable() {
        assertThat(AppDesignStyleSupport.getElegantStylesheetUrl()).isNotNull();
    }

    @Test
    void klingonTacticalPreviewResourceIsAvailable() {
        assertThat(SettingsDialog.class.getResource("/previews/klingon-tactical-preview.png")).isNotNull();
    }

    @Test
    void matrixTerminalPreviewResourceIsAvailable() {
        assertThat(SettingsDialog.class.getResource("/previews/matrix-terminal-preview.png")).isNotNull();
    }

    @Test
    void holographicPreviewResourceIsAvailable() {
        assertThat(SettingsDialog.class.getResource("/previews/holographic-preview.png")).isNotNull();
    }

    @Test
    void elegantPreviewResourceIsAvailable() {
        assertThat(SettingsDialog.class.getResource("/previews/elegant-dark-preview.png")).isNotNull();
    }

    @Test
    void applyToStylesheetsAddsMatrixStylesheetOnlyOnce() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        String matrixStylesheet = AppDesignStyleSupport.getMatrixStylesheetUrl();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.MATRIX_TERMINAL);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.MATRIX_TERMINAL);

        assertThat(stylesheets).containsExactly("base.css", matrixStylesheet).inOrder();
    }

    @Test
    void applyToStylesheetsAddsHolographicStylesheetOnlyOnce() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        String holographicStylesheet = AppDesignStyleSupport.getHolographicStylesheetUrl();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.HOLOGRAPHIC_INTERFACE);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.HOLOGRAPHIC_INTERFACE);

        assertThat(stylesheets).containsExactly("base.css", holographicStylesheet).inOrder();
    }

    @Test
    void applyToStylesheetsAddsTacticalStylesheetOnlyOnce() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        String tacticalStylesheet = AppDesignStyleSupport.getTacticalStylesheetUrl();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.KLINGON_TACTICAL);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.KLINGON_TACTICAL);

        assertThat(stylesheets).containsExactly("base.css", tacticalStylesheet).inOrder();
    }

    @Test
    void applyToStylesheetsAddsElegantStylesheetOnlyOnce() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        String elegantStylesheet = AppDesignStyleSupport.getElegantStylesheetUrl();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.ELEGANT_DARK);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.ELEGANT_DARK);

        assertThat(stylesheets).containsExactly("base.css", elegantStylesheet).inOrder();
    }

    @Test
    void applyToStylesheetsReplacesOtherAppDesignStylesheets() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        String matrixStylesheet = AppDesignStyleSupport.getMatrixStylesheetUrl();
        String holographicStylesheet = AppDesignStyleSupport.getHolographicStylesheetUrl();
        String tacticalStylesheet = AppDesignStyleSupport.getTacticalStylesheetUrl();
        String elegantStylesheet = AppDesignStyleSupport.getElegantStylesheetUrl();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.MATRIX_TERMINAL);
        assertThat(stylesheets).containsExactly("base.css", matrixStylesheet).inOrder();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.HOLOGRAPHIC_INTERFACE);
        assertThat(stylesheets).containsExactly("base.css", holographicStylesheet).inOrder();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.KLINGON_TACTICAL);
        assertThat(stylesheets).containsExactly("base.css", tacticalStylesheet).inOrder();

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.ELEGANT_DARK);
        assertThat(stylesheets).containsExactly("base.css", elegantStylesheet).inOrder();
    }

    @Test
    void applyToStylesheetsRemovesCustomStylesheetsForNormalDesign() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        String matrixStylesheet = AppDesignStyleSupport.getMatrixStylesheetUrl();
        String holographicStylesheet = AppDesignStyleSupport.getHolographicStylesheetUrl();
        String tacticalStylesheet = AppDesignStyleSupport.getTacticalStylesheetUrl();
        String elegantStylesheet = AppDesignStyleSupport.getElegantStylesheetUrl();
        stylesheets.add(matrixStylesheet);
        stylesheets.add(holographicStylesheet);
        stylesheets.add(tacticalStylesheet);
        stylesheets.add(elegantStylesheet);

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.NORMAL);

        assertThat(stylesheets).containsExactly("base.css");
    }

    @Test
    void everyNonNormalDesignHasStylesheetAndPreview() {
        for (AppDesign design : AppDesign.values()) {
            if (design == AppDesign.NORMAL) {
                continue;
            }
            assertThat(AppDesignStyleSupport.stylesheetUrl(design)).isNotNull();
            String preview = AppDesignStyleSupport.previewResource(design);
            assertThat(preview).isNotNull();
            assertThat(SettingsDialog.class.getResource(preview)).isNotNull();
        }
    }

    @Test
    void applyToStylesheetsAddsNewDesignStylesheetOnlyOnce() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        String draculaStylesheet = AppDesignStyleSupport.stylesheetUrl(AppDesign.DRACULA);

        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.DRACULA);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.DRACULA);

        assertThat(stylesheets).containsExactly("base.css", draculaStylesheet).inOrder();
    }

    @Test
    void desiredUserAgentStylesheetUsesEveryAtlantaFxThemeAndModenaOtherwise() {
        assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(
                AppDesign.ATLANTAFX_PRIMER_DARK)).contains("primer-dark");
        assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(
                AppDesign.ATLANTAFX_PRIMER_LIGHT)).contains("primer-light");
        assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(
                AppDesign.ATLANTAFX_NORD_DARK)).contains("nord-dark");
        assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(
                AppDesign.ATLANTAFX_NORD_LIGHT)).contains("nord-light");
        assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(
                AppDesign.ATLANTAFX_CUPERTINO_DARK)).contains("cupertino-dark");
        assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(
                AppDesign.ATLANTAFX_CUPERTINO_LIGHT)).contains("cupertino-light");
        assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(
                AppDesign.ATLANTAFX_DRACULA)).contains("dracula");
        for (AppDesign design : AppDesign.values()) {
            if (!design.isAtlantaFx()) {
                assertThat(AppDesignStyleSupport.desiredUserAgentStylesheet(design))
                        .isEqualTo(Application.STYLESHEET_MODENA);
            }
        }
    }

    @Test
    void userAgentStylesheetUpdateIsIdempotent() {
        AtomicReference<String> current = new AtomicReference<>(Application.STYLESHEET_MODENA);
        AtomicInteger writes = new AtomicInteger();

        assertThat(AppDesignStyleSupport.updateUserAgentStylesheet(
                AppDesign.ATLANTAFX_PRIMER_DARK,
                current::get,
                value -> {
                    current.set(value);
                    writes.incrementAndGet();
                })).isTrue();
        assertThat(AppDesignStyleSupport.updateUserAgentStylesheet(
                AppDesign.ATLANTAFX_PRIMER_DARK,
                current::get,
                value -> {
                    current.set(value);
                    writes.incrementAndGet();
                })).isFalse();

        assertThat(writes.get()).isEqualTo(1);
    }

    @Test
    void registeredBaseStylesSwapBetweenAtlantaFxAndModenaWithoutDuplicates() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("other.css");
        String terminal = AppDesignStyleSupport.applicationBaseStylesheetUrl();
        String components = AppDesignStyleSupport.atlantaFxComponentsStylesheetUrl();
        stylesheets.add(terminal);

        AppDesignStyleSupport.syncApplicationBaseStylesheets(
                stylesheets, AppDesign.ATLANTAFX_CUPERTINO_LIGHT);
        AppDesignStyleSupport.syncApplicationBaseStylesheets(
                stylesheets, AppDesign.ATLANTAFX_CUPERTINO_LIGHT);
        assertThat(stylesheets).containsExactly("other.css", components).inOrder();

        AppDesignStyleSupport.syncApplicationBaseStylesheets(stylesheets, AppDesign.NORMAL);
        AppDesignStyleSupport.syncApplicationBaseStylesheets(stylesheets, AppDesign.NORMAL);
        assertThat(stylesheets).containsExactly("other.css", terminal).inOrder();
    }

    @Test
    void applyToStylesheetsDoesNotTouchAnAlreadyCorrectList() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("base.css");
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.MATRIX_TERMINAL);
        int[] changes = {0};
        stylesheets.addListener((javafx.collections.ListChangeListener<String>) change -> changes[0]++);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.MATRIX_TERMINAL);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.MATRIX_TERMINAL);
        assertThat(changes[0]).isEqualTo(0);
        AppDesignStyleSupport.applyToStylesheets(stylesheets, AppDesign.NORMAL);
        assertThat(stylesheets).containsExactly("base.css");
    }

    @Test
    void syncApplicationBaseStylesheetsDoesNotTouchAnAlreadyCorrectList() {
        ObservableList<String> stylesheets = FXCollections.observableArrayList("other.css");
        AppDesignStyleSupport.syncApplicationBaseStylesheets(stylesheets, AppDesign.NORMAL);
        int[] changes = {0};
        stylesheets.addListener((javafx.collections.ListChangeListener<String>) change -> changes[0]++);
        AppDesignStyleSupport.syncApplicationBaseStylesheets(stylesheets, AppDesign.NORMAL);
        assertThat(changes[0]).isEqualTo(0);
        AppDesignStyleSupport.syncApplicationBaseStylesheets(stylesheets, AppDesign.ATLANTAFX_PRIMER_DARK);
        assertThat(changes[0]).isGreaterThan(0);
        assertThat(stylesheets).containsExactly("other.css", AppDesignStyleSupport.atlantaFxComponentsStylesheetUrl()).inOrder();
    }

    @Test
    void forcedRestyleOnlyForShownSurfacesWithANonDefaultDesignOrScale() {
        assertThat(AppDesignStyleSupport.shouldForceRestyle(AppDesign.NORMAL, 100, true)).isFalse();
        assertThat(AppDesignStyleSupport.shouldForceRestyle(AppDesign.ATLANTAFX_PRIMER_DARK, 100, true)).isTrue();
        assertThat(AppDesignStyleSupport.shouldForceRestyle(AppDesign.NORMAL, 130, true)).isTrue();
        assertThat(AppDesignStyleSupport.shouldForceRestyle(AppDesign.ATLANTAFX_PRIMER_DARK, 130, false)).isFalse();
        assertThat(AppDesignStyleSupport.shouldForceRestyle(AppDesign.MATRIX_TERMINAL, 100, false)).isFalse();
    }

    @Test
    void styleStampSkipsOnlyAnUnchangedSurfaceUnlessForced() {
        java.util.Map<Object, Object> properties = new java.util.HashMap<>();
        AppDesignStyleSupport.StyleStamp primer = new AppDesignStyleSupport.StyleStamp(AppDesign.ATLANTAFX_PRIMER_DARK, 100, null);
        assertThat(AppDesignStyleSupport.shouldApply(properties, primer, false)).isTrue();
        assertThat(AppDesignStyleSupport.shouldApply(null, primer, false)).isTrue();
        properties.put(stampKey(properties, primer), primer);
        assertThat(AppDesignStyleSupport.shouldApply(properties, primer, false)).isFalse();
        assertThat(AppDesignStyleSupport.shouldApply(properties, primer, true)).isTrue();
        assertThat(AppDesignStyleSupport.shouldApply(properties,
            new AppDesignStyleSupport.StyleStamp(AppDesign.ATLANTAFX_PRIMER_DARK, 130, null), false)).isTrue();
        assertThat(AppDesignStyleSupport.shouldApply(properties,
            new AppDesignStyleSupport.StyleStamp(AppDesign.NORMAL, 100, "file:/theme.css"), false)).isTrue();
    }

    /** The stamp key is private; stamping through the real code path keeps the test honest. */
    private static Object stampKey(java.util.Map<Object, Object> properties, AppDesignStyleSupport.StyleStamp stamp) {
        AppDesignStyleSupport.stamp(properties, stamp);
        return properties.keySet().iterator().next();
    }
}
