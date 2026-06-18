package de.kortty.ui;

import de.kortty.core.LanguageManager;
import org.testng.annotations.Test;

import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class AiAgentActivityTabsPanelTest {

    @Test
    void formatRunBreakdownOmitsZerosAndOrdersByState() {
        LanguageManager.getInstance().setLocale(Locale.ENGLISH);
        // No runs in any state -> empty breakdown.
        assertThat(AiAgentActivityTabsPanel.formatRunBreakdown(0, 0, 0, 0)).isEmpty();
        // A single category has no separator.
        assertThat(AiAgentActivityTabsPanel.formatRunBreakdown(0, 0, 0, 3)).isEqualTo("3 done");
        // The reported scenario: one still running, two finished — not just a "(3 runs)" total.
        assertThat(AiAgentActivityTabsPanel.formatRunBreakdown(0, 1, 0, 2)).isEqualTo("1 running · 2 done");
        // All categories, ordered input -> running -> paused -> done, joined by " · ".
        assertThat(AiAgentActivityTabsPanel.formatRunBreakdown(1, 2, 3, 4))
            .isEqualTo("1 awaiting input · 2 running · 3 paused · 4 done");
    }

    @Test
    void truncatesLongPromptsForTabTitle() {
        String title = AiAgentActivityTabsPanel.truncateTabTitle(
            "Please install and configure the full LAMP stack on this server");
        assertThat(title.length()).isAtMost(24);
        assertThat(title).endsWith("…");
    }

    @Test
    void keepsShortPromptsIntactAndCollapsesWhitespace() {
        assertThat(AiAgentActivityTabsPanel.truncateTabTitle("restart   nginx")).isEqualTo("restart nginx");
    }

    @Test
    void clampsPanelHeightWithinBounds() {
        // Below the minimum is raised to the minimum.
        assertThat(AiAgentActivityTabsPanel.clampPanelHeight(10.0, 0.0)).isEqualTo(130.0);
        // Constrained by the parent height minus reserved terminal space.
        assertThat(AiAgentActivityTabsPanel.clampPanelHeight(5000.0, 400.0)).isEqualTo(300.0);
    }
}
