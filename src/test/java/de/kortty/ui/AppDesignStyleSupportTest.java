package de.kortty.ui;

import de.kortty.model.AppDesign;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AppDesignStyleSupportTest {

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
}
