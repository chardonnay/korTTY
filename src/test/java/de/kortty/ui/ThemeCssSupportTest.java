package de.kortty.ui;

import de.kortty.model.Theme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void buildAgentActivityCssUsesThemeAgentColors() {
        Theme theme = new Theme("custom", "Custom", false);
        theme.setAgentPanelBackgroundColor("#112233");
        theme.setAgentPanelBorderColor("#445566");
        theme.setAgentPanelTextColor("#ddeeff");
        theme.setAgentPanelMutedTextColor("#99aabb");
        theme.setAgentPanelAccentColor("#00cc88");
        theme.setAgentPanelErrorColor("#cc3300");

        String css = ThemeCssSupport.buildAgentActivityCss(theme);

        assertTrue(css.contains(".ai-agent-activity-panel { -fx-background-color: #112233; -fx-border-color: #445566; }"));
        assertTrue(css.contains(".ai-agent-activity-text { -fx-text-fill: #ddeeff; }"));
        assertTrue(css.contains(".ai-agent-detail { -fx-text-fill: #99aabb; }"));
        assertTrue(css.contains(".ai-agent-dot-action { -fx-background-color: #00cc88; }"));
        assertTrue(css.contains(".ai-agent-dot-error { -fx-background-color: #cc3300; }"));
        assertTrue(css.contains(".terminal-agent-busy-overlay { -fx-background-color:"));
        assertTrue(css.contains(".terminal-agent-busy-robot { -fx-text-fill: #00cc88;"));
        assertTrue(css.contains(".terminal-agent-busy-text { -fx-text-fill: #ddeeff; }"));
    }

    @Test
    void agentActivityStylesheetUrlUsesFallbackColorsWhenThemeIsNull() {
        assertNotNull(ThemeCssSupport.getAgentActivityStylesheetUrl(null));
    }

    @Test
    void themeDerivesAgentPanelColorsFromTerminalColors() {
        Theme theme = new Theme("derived", "Derived", false);
        theme.setBackgroundColor("#101820");
        theme.setForegroundColor("#f3f4f6");
        theme.setCursorColor("#2dd4bf");

        assertNotNull(theme.getAgentPanelBackgroundColor());
        assertNotNull(theme.getAgentPanelBorderColor());
        assertNotNull(theme.getAgentPanelTextColor());
        assertNotNull(theme.getAgentPanelMutedTextColor());
        assertNotNull(theme.getAgentPanelAccentColor());
        assertNotNull(theme.getAgentPanelErrorColor());
        assertNotEquals(theme.getBackgroundColor(), theme.getAgentPanelBackgroundColor());
    }
}
