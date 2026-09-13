package de.kortty.ui;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * File-based checks of the dashboard stylesheets: every stylesheet with a {@code .dashboard-view}
 * block declares the five coding-agent tokens, the two base stylesheets carry the agent rules and
 * declare them before the selection rule (equal specificity, so order decides), and
 * filebrowser.css (which only mentions the block in a comment) stays untouched.
 */
class DashboardCssTokensTest {

    private static final List<String> TOKEN_STYLESHEETS = List.of(
        "terminal.css",
        "atlantafx-kortty-components.css",
        "atlantafx-primer-dark.css",
        "amber-crt.css",
        "dracula.css",
        "elegant.css",
        "gruvbox.css",
        "holographic.css",
        "matrix-terminal.css",
        "nord.css",
        "synthwave.css",
        "tactical.css");

    private static final List<String> TOKENS = List.of(
        "-kortty-dash-agent-blocked:",
        "-kortty-dash-agent-working:",
        "-kortty-dash-agent-done:",
        "-kortty-dash-agent-blocked-tint:",
        "-kortty-dash-agent-chip-fg:");

    private static final List<String> RULE_SELECTORS = List.of(
        ".dashboard-tree .tree-cell.dashboard-row-agent-blocked {",
        ".dashboard-tree .tree-cell.dashboard-row-agent-working {",
        ".dashboard-tree .tree-cell.dashboard-row-agent-done {",
        ".dashboard-agent-chip {",
        ".dashboard-agent-chip .label {",
        ".dashboard-agent-chip-blocked {",
        ".dashboard-agent-chip-working {",
        ".dashboard-agent-chip-done {",
        ".dashboard-agent-chip-idle {",
        ".dashboard-agent-chip-idle .label {",
        ".dashboard-rollup-chip {");

    private static final String SELECTED_RULE = ".dashboard-tree .tree-cell:selected {";

    @DataProvider(name = "tokenStylesheets")
    Object[][] tokenStylesheets() {
        return TOKEN_STYLESHEETS.stream().map(name -> new Object[] {name}).toArray(Object[][]::new);
    }

    @Test(dataProvider = "tokenStylesheets")
    void dashboardViewBlockDeclaresAllFiveAgentTokens(String stylesheet) throws IOException {
        String css = read(stylesheet);
        int blockStart = css.indexOf(".dashboard-view {");
        assertWithMessage("%s has a .dashboard-view block", stylesheet).that(blockStart).isAtLeast(0);
        int blockEnd = css.indexOf('}', blockStart);
        assertThat(blockEnd).isGreaterThan(blockStart);
        String block = css.substring(blockStart, blockEnd);
        for (String token : TOKENS) {
            assertWithMessage("%s declares %s in .dashboard-view", stylesheet, token).that(block).contains(token);
        }
    }

    @Test
    void exactlyTwelveStylesheetsCarryTheTokens() {
        assertThat(TOKEN_STYLESHEETS).hasSize(12);
        assertThat(TOKEN_STYLESHEETS).containsNoDuplicates();
    }

    @Test
    void baseStylesheetsDeclareTheAgentRulesBeforeTheSelectionRule() throws IOException {
        for (String stylesheet : List.of("terminal.css", "atlantafx-kortty-components.css")) {
            String css = read(stylesheet);
            int selected = css.indexOf(SELECTED_RULE);
            assertWithMessage("%s has the selection rule", stylesheet).that(selected).isAtLeast(0);
            for (String selector : RULE_SELECTORS) {
                int first = css.indexOf(selector);
                assertWithMessage("%s declares %s", stylesheet, selector).that(first).isAtLeast(0);
                assertWithMessage("%s declares %s before the :selected rule", stylesheet, selector)
                    .that(first).isLessThan(selected);
            }
        }
    }

    @Test
    void agentRulesReferenceTokensNotLiterals() throws IOException {
        for (String stylesheet : List.of("terminal.css", "atlantafx-kortty-components.css")) {
            String css = read(stylesheet);
            int start = css.indexOf(RULE_SELECTORS.get(0));
            int end = css.indexOf(SELECTED_RULE);
            String rules = css.substring(start, end);
            assertThat(rules).contains("-kortty-dash-agent-blocked-tint;");
            assertThat(rules).contains("-kortty-dash-agent-chip-fg;");
            assertThat(rules).doesNotContainMatch("#[0-9a-fA-F]{3,6}");
        }
    }

    @Test
    void fileBrowserStylesheetDeclaresNoAgentToken() throws IOException {
        String css = read("filebrowser.css");
        assertThat(css).doesNotContain("-kortty-dash-agent-");
    }

    private static String read(String stylesheet) throws IOException {
        try (InputStream in = DashboardCssTokensTest.class.getResourceAsStream("/styles/" + stylesheet)) {
            assertWithMessage("stylesheet %s on the classpath", stylesheet).that(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
