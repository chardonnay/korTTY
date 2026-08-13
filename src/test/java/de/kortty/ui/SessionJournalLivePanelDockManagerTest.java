package de.kortty.ui;

import de.kortty.ui.SessionJournalLivePanelDockManager.Placement;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalLivePanelDockManagerTest {

    @Test
    void parsePlacementIsDefensive() {
        assertThat(SessionJournalLivePanelDockManager.parsePlacement("LEFT")).isEqualTo(Placement.LEFT);
        assertThat(SessionJournalLivePanelDockManager.parsePlacement("right")).isEqualTo(Placement.RIGHT);
        assertThat(SessionJournalLivePanelDockManager.parsePlacement(" hidden ")).isEqualTo(Placement.HIDDEN);
        assertThat(SessionJournalLivePanelDockManager.parsePlacement(null)).isEqualTo(Placement.HIDDEN);
        assertThat(SessionJournalLivePanelDockManager.parsePlacement("nonsense")).isEqualTo(Placement.HIDDEN);
    }

    @Test
    void clampWidthBoundsAndDefaults() {
        assertThat(SessionJournalLivePanelDockManager.clampWidth(50))
            .isEqualTo(SessionJournalLivePanelDockManager.MIN_WIDTH);
        assertThat(SessionJournalLivePanelDockManager.clampWidth(5000))
            .isEqualTo(SessionJournalLivePanelDockManager.MAX_WIDTH);
        assertThat(SessionJournalLivePanelDockManager.clampWidth(400)).isEqualTo(400.0);
        assertThat(SessionJournalLivePanelDockManager.clampWidth(0))
            .isEqualTo(SessionJournalLivePanelDockManager.DEFAULT_WIDTH);
        assertThat(SessionJournalLivePanelDockManager.clampWidth(Double.NaN))
            .isEqualTo(SessionJournalLivePanelDockManager.DEFAULT_WIDTH);
    }

    @Test
    void togglingTheCurrentSideHides() {
        SessionJournalLivePanelDockManager manager = new SessionJournalLivePanelDockManager();
        manager.toggle(Placement.LEFT);
        assertThat(manager.getPlacement()).isEqualTo(Placement.LEFT);
        assertThat(manager.isDocked()).isTrue();
        manager.toggle(Placement.LEFT);
        assertThat(manager.getPlacement()).isEqualTo(Placement.HIDDEN);
        assertThat(manager.isDocked()).isFalse();
        manager.toggle(Placement.LEFT);
        manager.toggle(Placement.RIGHT);
        assertThat(manager.getPlacement()).isEqualTo(Placement.RIGHT);
    }

    @Test
    void toggleVisibleReopensOnTheLastDockedSide() {
        SessionJournalLivePanelDockManager manager = new SessionJournalLivePanelDockManager();
        manager.toggleVisible();
        assertThat(manager.getPlacement()).isEqualTo(Placement.RIGHT);
        manager.setPlacement(Placement.LEFT);
        manager.toggleVisible();
        assertThat(manager.getPlacement()).isEqualTo(Placement.HIDDEN);
        manager.toggleVisible();
        assertThat(manager.getPlacement()).isEqualTo(Placement.LEFT);
    }

    @Test
    void listenersFireOnChangeOnlyAndCanBeRemoved() {
        SessionJournalLivePanelDockManager manager = new SessionJournalLivePanelDockManager();
        List<Placement> seen = new ArrayList<>();
        java.util.function.Consumer<Placement> listener = seen::add;
        manager.addPlacementListener(listener);
        manager.setPlacement(Placement.HIDDEN);
        assertThat(seen).isEmpty();
        manager.setPlacement(Placement.LEFT);
        manager.setPlacement(Placement.LEFT);
        assertThat(seen).containsExactly(Placement.LEFT);
        manager.removePlacementListener(listener);
        manager.setPlacement(Placement.RIGHT);
        assertThat(seen).containsExactly(Placement.LEFT);
    }
}
