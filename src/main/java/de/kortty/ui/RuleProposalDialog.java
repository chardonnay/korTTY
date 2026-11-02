package de.kortty.ui;

import de.kortty.memory.RuleProposal;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;

/** "Als Regel merken": the rule card in a small owned dialog, prefilled with the selected text. No AI call is made. */
final class RuleProposalDialog extends ThemeAwareDialog<Void> {

    private RuleProposalDialog(@Nullable Window owner, RuleProposal proposal) {
        if (owner != null) {
            initOwner(owner);
        }
        setTitle(I18n.get("ai.learn.dialog.title"));
        setResizable(true);
        RuleProposalCard card = new RuleProposalCard(proposal, owner, answered -> close());
        card.setPrefWidth(480);
        getDialogPane().setContent(card);
        getDialogPane().getButtonTypes().add(new ButtonType(I18n.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
    }

    /** Opens the dialog without blocking; the card answers it, and the window's close button leaves it unchanged. */
    static void show(@Nullable Window owner, RuleProposal proposal) {
        new RuleProposalDialog(owner, proposal).show();
    }
}
