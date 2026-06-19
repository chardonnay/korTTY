package de.kortty.ui;

import de.kortty.ui.AiAgentPanelDockManager.Placement;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiAgentPanelDockManagerTest {

    @Test
    void parsePlacementIsDefensive() {
        assertThat(AiAgentPanelDockManager.parsePlacement("LEFT")).isEqualTo(Placement.LEFT);
        assertThat(AiAgentPanelDockManager.parsePlacement("right")).isEqualTo(Placement.RIGHT);
        assertThat(AiAgentPanelDockManager.parsePlacement(" bottom ")).isEqualTo(Placement.BOTTOM);
        assertThat(AiAgentPanelDockManager.parsePlacement(null)).isEqualTo(Placement.BOTTOM);
        assertThat(AiAgentPanelDockManager.parsePlacement("nonsense")).isEqualTo(Placement.BOTTOM);
    }

    @Test
    void clampWidthBoundsAndDefaults() {
        assertThat(AiAgentPanelDockManager.clampWidth(50)).isEqualTo(AiAgentPanelDockManager.MIN_WIDTH);
        assertThat(AiAgentPanelDockManager.clampWidth(5000)).isEqualTo(AiAgentPanelDockManager.MAX_WIDTH);
        assertThat(AiAgentPanelDockManager.clampWidth(350)).isEqualTo(350.0);
        assertThat(AiAgentPanelDockManager.clampWidth(0)).isEqualTo(AiAgentPanelDockManager.DEFAULT_WIDTH);
        assertThat(AiAgentPanelDockManager.clampWidth(Double.NaN)).isEqualTo(AiAgentPanelDockManager.DEFAULT_WIDTH);
    }
}
