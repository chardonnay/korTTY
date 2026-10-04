package de.kortty.ui;

import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The startup offer above a window's status line: it offers the windows and tabs of the session
 * before this start with <b>Restore</b> and <b>Dismiss</b> (see {@link SessionRestoreCoordinator}).
 * Non-modal: the window stays usable, and nothing opens until the user chooses Restore. It looks
 * like the restore bar ({@link RestoreAttentionBar}), whose style classes it shares. Hidden
 * (unmanaged) until offered. FX thread only.
 */
final class SessionRestoreOfferBar extends HBox {

    private static final Logger logger = LoggerFactory.getLogger(SessionRestoreOfferBar.class);

    static final String STYLE_CLASS = "kortty-session-restore-offer";

    private final Label text = new Label();
    private final Hyperlink restore = action(SessionRestoreCoordinator.RESTORE_KEY);
    private final Hyperlink dismiss = action(RestoreAttention.DISMISS_KEY);
    private @Nullable Runnable onRestore;
    private @Nullable Runnable onDismiss;

    SessionRestoreOfferBar() {
        super(10);
        getStyleClass().addAll(RestoreAttentionBar.STYLE_CLASS, STYLE_CLASS);
        setAlignment(Pos.CENTER_LEFT);
        text.getStyleClass().add(RestoreAttentionBar.TEXT_STYLE_CLASS);
        text.setMinWidth(0);
        text.setAccessibleRole(AccessibleRole.TEXT);
        HBox.setHgrow(text, Priority.SOMETIMES);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        restore.setOnAction(event -> run(onRestore, restore));
        dismiss.setOnAction(event -> run(onDismiss, dismiss));
        getChildren().addAll(text, spacer, restore, dismiss);
        Tooltip.install(text, new Tooltip(I18n.get(SessionRestoreCoordinator.TOOLTIP_KEY)));
        hideOffer();
    }

    /** What <b>Restore</b> does: opens the previous session. */
    void setOnRestore(@Nullable Runnable onRestore) {
        this.onRestore = onRestore;
    }

    /** What <b>Dismiss</b> does: leaves the window as it is. */
    void setOnDismiss(@Nullable Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    /** Shows the offer with {@code prompt}, which names the windows and tabs it would open. */
    void showOffer(String prompt) {
        text.setText(prompt);
        text.setAccessibleText(prompt);
        setVisible(true);
        setManaged(true);
    }

    /** Hides the offer: answered, or the previous session was restored another way. */
    void hideOffer() {
        setVisible(false);
        setManaged(false);
    }

    /** Whether the offer is showing. */
    boolean isOffering() {
        return isVisible();
    }

    /** The two actions, Restore then Dismiss; for the smoke check. */
    List<Hyperlink> actions() {
        return List.of(restore, dismiss);
    }

    private static Hyperlink action(String key) {
        Hyperlink link = new Hyperlink(I18n.get(key));
        link.setMnemonicParsing(false);
        link.getStyleClass().add(RestoreAttentionBar.ACTION_STYLE_CLASS);
        return link;
    }

    private static void run(@Nullable Runnable action, Hyperlink link) {
        link.setVisited(false);
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            logger.warn("A session restore offer action failed", e);
        }
    }
}
