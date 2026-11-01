package de.kortty.ui;

import de.kortty.memory.RuleProposal;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;

/**
 * The rule proposal of a finished agent run for the designs that have no activity list: a small non-modal popup in the
 * lower right corner of the main window, like the undo of a saved rule. It closes when the card is answered. FX thread.
 */
final class RuleProposalPopup {

    private static final double WIDTH = 460;
    private static final double MARGIN = 24;

    private RuleProposalPopup() {
    }

    /** Shows the card for {@code proposal} over {@code owner}; nothing is shown without a showing window. */
    static void show(@Nullable Window owner, RuleProposal proposal) {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        Popup popup = new Popup();
        popup.setAutoHide(false);
        RuleProposalCard card = new RuleProposalCard(proposal, owner, answered -> popup.hide());
        card.setPrefWidth(WIDTH);
        VBox surface = new VBox(card);
        surface.getStyleClass().add("kortty-popup-surface");
        // A raw Popup inherits no author stylesheets, so the root gets the design's base sheet, as the other popups do.
        AppDesignStyleSupport.registerApplicationBaseStyles(surface);
        AppDesignStyleSupport.applyToParent(surface);
        popup.getContent().add(surface);
        popup.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_BOTTOM_RIGHT);
        popup.show(owner, owner.getX() + owner.getWidth() - MARGIN, owner.getY() + owner.getHeight() - MARGIN);
    }
}
