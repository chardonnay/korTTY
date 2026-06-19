package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Dockable side container for the terminal AI-agent panels. Shows one outer tab per terminal widget
 * of the currently bound (active) terminal tab; each outer tab hosts that widget's
 * {@link AiAgentActivityTabsPanel} (re-parented out of its split's bottom slot and switched to its
 * stacked layout). Only the active terminal tab is shown ("spotlight"); switching tabs rebinds.
 */
public class AiAgentSidePanel extends VBox {

    private final TabPane outerTabs = new TabPane();
    private TerminalTab boundTab;

    public AiAgentSidePanel() {
        getStyleClass().add("ai-agent-side-panel");
        outerTabs.getStyleClass().add("ai-agent-side-tabpane");
        outerTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(outerTabs, Priority.ALWAYS);
        getChildren().add(outerTabs);
    }

    /** The terminal tab currently shown, or null. */
    public TerminalTab getBoundTab() {
        return boundTab;
    }

    /**
     * Shows the given terminal tab's agent panels (one outer tab per widget). Detaches the previously
     * bound tab's panels back to their split bottoms first.
     */
    public void bindToTerminalTab(TerminalTab tab) {
        unbind();
        boundTab = tab;
        if (tab == null) {
            return;
        }
        TerminalView view = tab.getTerminalView();
        if (view == null) {
            return;
        }
        // Stop the splits from hosting these panels at the bottom while they live here.
        view.setBottomPanelsDetached(true);
        List<SithTermFxWidget> widgets = view.getOrderedWidgets();
        int index = 1;
        for (SithTermFxWidget widget : widgets) {
            AiAgentActivityTabsPanel panel = view.getAgentPanelForWidget(widget);
            if (panel == null) {
                continue;
            }
            panel.setSideDocked(true);
            Tab outer = new Tab(formatTabTitle(index, null));
            outer.setClosable(false);
            outer.setContent(panel);
            outerTabs.getTabs().add(outer);
            index++;
        }
        if (!outerTabs.getTabs().isEmpty()) {
            outerTabs.getSelectionModel().select(0);
        }
    }

    /**
     * Re-binds to the currently bound tab (used after a split is opened/closed so the outer tab set
     * matches the widget set again).
     */
    public void refreshBinding() {
        if (boundTab != null) {
            bindToTerminalTab(boundTab);
        }
    }

    /** Detaches the bound tab's panels from the side container and returns them to their split bottoms. */
    public void unbind() {
        if (boundTab != null) {
            for (Tab outer : outerTabs.getTabs()) {
                if (outer.getContent() instanceof AiAgentActivityTabsPanel panel) {
                    panel.setSideDocked(false);
                    outer.setContent(null);
                }
            }
            TerminalView view = boundTab.getTerminalView();
            if (view != null) {
                // Re-attaches each widget's panel into its split's bottom slot.
                view.setBottomPanelsDetached(false);
            }
        }
        outerTabs.getTabs().clear();
        boundTab = null;
    }

    /** Outer-tab title for the {@code index}-th terminal widget (1-based), or a name when available. */
    static String formatTabTitle(int index, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return I18n.get("ai.agent.side.tabTitle", index);
    }
}
