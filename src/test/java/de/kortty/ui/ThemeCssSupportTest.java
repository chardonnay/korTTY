package de.kortty.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeCssSupportTest {

    @Test
    void buildCssContainsDialogAndMenuBarThemeRules() {
        String css = ThemeCssSupport.buildCss("#101820", "#f3f4f6");

        assertTrue(css.contains(".menu-bar { -fx-background-color:"));
        assertTrue(css.contains(".dialog-pane { -fx-background-color:"));
        assertTrue(css.contains(".button:default { -fx-background-color: #0066cc; -fx-text-fill: #ffffff; }"));
        assertTrue(css.contains(".root { -fx-background-color: #101820; }"));
        assertTrue(css.contains(".label { -fx-text-fill: #f3f4f6; }"));
    }
}
