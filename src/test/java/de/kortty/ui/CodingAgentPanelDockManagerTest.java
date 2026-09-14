package de.kortty.ui;

import de.kortty.codingagent.CodingAgentPanelDefaults;
import de.kortty.ui.CodingAgentPanelDockManager.Placement;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;

class CodingAgentPanelDockManagerTest {

    @Test
    void widthBoundsComeFromThePanelDefaults() {
        assertThat(CodingAgentPanelDockManager.MIN_WIDTH).isEqualTo(CodingAgentPanelDefaults.MIN_WIDTH);
        assertThat(CodingAgentPanelDockManager.MAX_WIDTH).isEqualTo(CodingAgentPanelDefaults.MAX_WIDTH);
        assertThat(CodingAgentPanelDockManager.DEFAULT_WIDTH).isEqualTo(CodingAgentPanelDefaults.DEFAULT_WIDTH);
        assertThat(CodingAgentPanelDockManager.MIN_WIDTH).isEqualTo(280.0);
        assertThat(CodingAgentPanelDockManager.MAX_WIDTH).isEqualTo(1200.0);
        assertThat(CodingAgentPanelDockManager.DEFAULT_WIDTH).isEqualTo(380.0);
    }

    @Test
    void parsePlacementIsDefensive() {
        assertThat(CodingAgentPanelDockManager.parsePlacement("LEFT")).isEqualTo(Placement.LEFT);
        assertThat(CodingAgentPanelDockManager.parsePlacement("right")).isEqualTo(Placement.RIGHT);
        assertThat(CodingAgentPanelDockManager.parsePlacement(" hidden ")).isEqualTo(Placement.HIDDEN);
        assertThat(CodingAgentPanelDockManager.parsePlacement(null)).isEqualTo(Placement.HIDDEN);
        assertThat(CodingAgentPanelDockManager.parsePlacement("")).isEqualTo(Placement.HIDDEN);
        assertThat(CodingAgentPanelDockManager.parsePlacement("   ")).isEqualTo(Placement.HIDDEN);
        assertThat(CodingAgentPanelDockManager.parsePlacement("nonsense")).isEqualTo(Placement.HIDDEN);
        assertThat(CodingAgentPanelDockManager.parsePlacement("BOTTOM")).isEqualTo(Placement.HIDDEN);
    }

    @Test
    void clampWidthBoundsAndDefaults() {
        assertThat(CodingAgentPanelDockManager.clampWidth(50)).isEqualTo(280.0);
        assertThat(CodingAgentPanelDockManager.clampWidth(5000)).isEqualTo(1200.0);
        assertThat(CodingAgentPanelDockManager.clampWidth(400)).isEqualTo(400.0);
        assertThat(CodingAgentPanelDockManager.clampWidth(280)).isEqualTo(280.0);
        assertThat(CodingAgentPanelDockManager.clampWidth(1200)).isEqualTo(1200.0);
        assertThat(CodingAgentPanelDockManager.clampWidth(0)).isEqualTo(380.0);
        assertThat(CodingAgentPanelDockManager.clampWidth(-1)).isEqualTo(380.0);
        assertThat(CodingAgentPanelDockManager.clampWidth(Double.NaN)).isEqualTo(380.0);
    }

    @Test
    void startsHiddenWithTheDefaultWidthAndRightAsLastSide() {
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
        assertThat(manager.getPlacement()).isEqualTo(Placement.HIDDEN);
        assertThat(manager.isDocked()).isFalse();
        assertThat(manager.getPreferredWidth()).isEqualTo(380.0);
        assertThat(manager.getLastDockedSide()).isEqualTo(Placement.RIGHT);
    }

    @Test
    void preferredWidthIsClamped() {
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
        manager.setPreferredWidth(10);
        assertThat(manager.getPreferredWidth()).isEqualTo(280.0);
        manager.setPreferredWidth(9999);
        assertThat(manager.getPreferredWidth()).isEqualTo(1200.0);
        manager.setPreferredWidth(Double.NaN);
        assertThat(manager.getPreferredWidth()).isEqualTo(380.0);
        manager.setPreferredWidth(500);
        assertThat(manager.getPreferredWidth()).isEqualTo(500.0);
    }

    @Test
    void togglingTheCurrentSideHides() {
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
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
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
        manager.toggleVisible();
        assertThat(manager.getPlacement()).isEqualTo(Placement.RIGHT);
        manager.setPlacement(Placement.LEFT);
        assertThat(manager.getLastDockedSide()).isEqualTo(Placement.LEFT);
        manager.toggleVisible();
        assertThat(manager.getPlacement()).isEqualTo(Placement.HIDDEN);
        assertThat(manager.getLastDockedSide()).isEqualTo(Placement.LEFT);
        manager.toggleVisible();
        assertThat(manager.getPlacement()).isEqualTo(Placement.LEFT);
    }

    @Test
    void nullPlacementIsIgnored() {
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
        List<Placement> seen = new ArrayList<>();
        manager.addPlacementListener(seen::add);
        manager.setPlacement(null);
        assertThat(manager.getPlacement()).isEqualTo(Placement.HIDDEN);
        assertThat(seen).isEmpty();
    }

    @Test
    void listenersFireOnChangeOnlyAndCanBeRemoved() {
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
        List<Placement> seen = new ArrayList<>();
        Consumer<Placement> listener = seen::add;
        manager.addPlacementListener(listener);
        manager.setPlacement(Placement.HIDDEN);
        assertThat(seen).isEmpty();
        manager.setPlacement(Placement.LEFT);
        manager.setPlacement(Placement.LEFT);
        assertThat(seen).containsExactly(Placement.LEFT);
        manager.toggleVisible();
        assertThat(seen).containsExactly(Placement.LEFT, Placement.HIDDEN).inOrder();
        manager.removePlacementListener(listener);
        manager.setPlacement(Placement.RIGHT);
        assertThat(seen).containsExactly(Placement.LEFT, Placement.HIDDEN).inOrder();
    }

    @Test
    void aListenerMayRemoveItselfWhileBeingNotified() {
        CodingAgentPanelDockManager manager = new CodingAgentPanelDockManager();
        List<Placement> seen = new ArrayList<>();
        AtomicReference<Consumer<Placement>> self = new AtomicReference<>();
        self.set(placement -> {
            seen.add(placement);
            manager.removePlacementListener(self.get());
        });
        manager.addPlacementListener(self.get());
        manager.setPlacement(Placement.RIGHT);
        manager.setPlacement(Placement.LEFT);
        assertThat(seen).containsExactly(Placement.RIGHT);
    }
}
