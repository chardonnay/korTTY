package de.kortty.ui;

import de.kortty.model.AppDesign;
import de.kortty.model.Theme;
import javafx.collections.FXCollections;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class ThemeCssSupportTest {

    @Test
    void buildCssContainsDialogAndMenuBarThemeRules() {
        String css = ThemeCssSupport.buildCss("#101820", "#f3f4f6");

        assertThat(css.contains(".menu-bar { -fx-background-color:")).isTrue();
        assertThat(css.contains(".dialog-pane { -fx-background-color:")).isTrue();
        assertThat(css.contains(".button:default { -fx-background-color: #0066cc; -fx-text-fill: #ffffff; }")).isTrue();
        assertThat(css.contains(".root { -fx-background-color: #101820; }")).isTrue();
        assertThat(css.contains(".label { -fx-text-fill: #f3f4f6; }")).isTrue();
        assertThat(css.contains("-fx-prompt-text-fill:")).isTrue();
        assertThat(css.contains(
            ".ai-manager-primary-tab:selected { -fx-border-color: #0066cc; -fx-border-width: 0 0 3 0; }"))
            .isTrue();
        assertThat(css.contains(".swarm-composer-input {")).isTrue();
        assertThat(css.contains(".swarm-composer-input .content {")).isTrue();
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

        assertThat(css.contains(".ai-agent-activity-panel { -fx-background-color: #112233; -fx-border-color: #445566; }")).isTrue();
        assertThat(css.contains(".ai-agent-prompt-viewer { -fx-background-color:")).isTrue();
        assertThat(css.contains(".ai-agent-prompt-viewer .scroll-pane")).isTrue();
        assertThat(css.contains(".ai-agent-activity-text { -fx-text-fill: #ddeeff; }")).isTrue();
        assertThat(css.contains(".ai-agent-detail { -fx-text-fill: #99aabb; }")).isTrue();
        assertThat(css.contains(".ai-agent-marker-input { -fx-text-fill: #00cc88; }")).isTrue();
        assertThat(css.contains(".ai-agent-marker-error { -fx-text-fill: #cc3300; }")).isTrue();
        assertThat(css.contains(".ai-agent-marker-running { -fx-effect: dropshadow(gaussian, #00cc88")).isTrue();
    }

    @Test
    void agentActivityStylesheetUrlUsesFallbackColorsWhenThemeIsNull() {
        assertThat(ThemeCssSupport.getAgentActivityStylesheetUrl(null)).isNotNull();
    }

    @Test
    void themeDerivesAgentPanelColorsFromTerminalColors() {
        Theme theme = new Theme("derived", "Derived", false);
        theme.setBackgroundColor("#101820");
        theme.setForegroundColor("#f3f4f6");
        theme.setCursorColor("#2dd4bf");

        assertThat(theme.getAgentPanelBackgroundColor()).isNotNull();
        assertThat(theme.getAgentPanelBorderColor()).isNotNull();
        assertThat(theme.getAgentPanelTextColor()).isNotNull();
        assertThat(theme.getAgentPanelMutedTextColor()).isNotNull();
        assertThat(theme.getAgentPanelAccentColor()).isNotNull();
        assertThat(theme.getAgentPanelErrorColor()).isNotNull();
        assertThat(theme.getAgentPanelBackgroundColor()).isNotEqualTo(theme.getBackgroundColor());
    }

    @Test
    void dynamicChromeStylesheetIsRemovedForAtlantaFxAndRestoredForNormal() {
        ThemeCssSupport.ThemeColors colors =
                new ThemeCssSupport.ThemeColors("#101820", "#f3f4f6");
        String dynamic = ThemeCssSupport.getDynamicStylesheetUrl(colors);
        var stylesheets = FXCollections.observableArrayList("base.css", dynamic, dynamic);

        ThemeCssSupport.reconcileDynamicStylesheets(
                stylesheets, AppDesign.ATLANTAFX_PRIMER_DARK, colors);
        assertThat(stylesheets).containsExactly("base.css");

        ThemeCssSupport.reconcileDynamicStylesheets(stylesheets, AppDesign.NORMAL, colors);
        ThemeCssSupport.reconcileDynamicStylesheets(stylesheets, AppDesign.NORMAL, colors);
        assertThat(stylesheets).containsExactly("base.css", dynamic).inOrder();
    }
}
