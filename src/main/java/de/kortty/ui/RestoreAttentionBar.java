package de.kortty.ui;

import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.AccessibleRole;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The restore bar above a window's status line: after a project opened, it says how many tabs did
 * not open yet and why, and offers <b>Connect…</b>, <b>Unlock Vault…</b>, <b>Details</b> (every
 * waiting tab; choosing one that waits for the user connects just that tab) and <b>Dismiss</b>. It
 * never blocks the window, unlike the dialog per tab it replaces. Hidden (unmanaged) while no tab
 * waits. The texts and decisions come from {@link RestoreAttention}; the actions from MainWindow.
 * FX thread only.
 */
final class RestoreAttentionBar extends HBox {

    private static final Logger logger = LoggerFactory.getLogger(RestoreAttentionBar.class);

    static final String STYLE_CLASS = "kortty-restore-attention";
    static final String TEXT_STYLE_CLASS = "kortty-restore-attention-text";
    static final String ACTION_STYLE_CLASS = "kortty-restore-attention-action";

    /**
     * One line of <b>Details</b>.
     *
     * @param action what choosing it does, or {@code null} for a tab the user cannot bring back from here
     */
    record Line(String text, @Nullable Runnable action) {
    }

    private final Label text = new Label();
    private final Hyperlink connect = action(RestoreAttention.CONNECT_KEY);
    private final Hyperlink unlock = action(RestoreAttention.UNLOCK_KEY);
    private final Hyperlink details = action(RestoreAttention.DETAILS_KEY);
    private final Hyperlink dismiss = action(RestoreAttention.DISMISS_KEY);
    private final ContextMenu detailsMenu = new ContextMenu();
    private @Nullable Runnable onConnect;
    private @Nullable Runnable onUnlock;
    private @Nullable Runnable onDismiss;

    RestoreAttentionBar() {
        super(10);
        getStyleClass().add(STYLE_CLASS);
        setAlignment(Pos.CENTER_LEFT);
        text.getStyleClass().add(TEXT_STYLE_CLASS);
        text.setMinWidth(0);
        text.setAccessibleRole(AccessibleRole.TEXT);
        HBox.setHgrow(text, Priority.SOMETIMES);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        connect.setOnAction(event -> run(onConnect, connect));
        unlock.setOnAction(event -> run(onUnlock, unlock));
        dismiss.setOnAction(event -> run(onDismiss, dismiss));
        details.setOnAction(event -> {
            details.setVisited(false);
            if (!detailsMenu.getItems().isEmpty()) {
                detailsMenu.show(details, Side.TOP, 0, 0);
            }
        });
        getChildren().addAll(text, spacer, connect, unlock, details, dismiss);
        Tooltip.install(text, new Tooltip(I18n.get(RestoreAttention.TOOLTIP_KEY)));
        hideBar();
    }

    /** What <b>Connect…</b> does: asks for the waiting tabs one connection after the other. */
    void setOnConnect(@Nullable Runnable onConnect) {
        this.onConnect = onConnect;
    }

    /** What <b>Unlock Vault…</b> does. */
    void setOnUnlock(@Nullable Runnable onUnlock) {
        this.onUnlock = onUnlock;
    }

    /** What <b>Dismiss</b> does: forgets the waiting tabs. */
    void setOnDismiss(@Nullable Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    /**
     * Shows the bar.
     *
     * @param offerConnect whether some tab waits for a password, a key or the vault
     * @param offerUnlock  whether some tab waits for the vault and it is locked
     * @param busy         whether a Connect… is still asking; the actions are greyed out meanwhile
     */
    void showBar(String summary, boolean offerConnect, boolean offerUnlock, List<Line> lines, boolean busy) {
        text.setText(summary);
        text.setAccessibleText(summary);
        setOffered(connect, offerConnect);
        setOffered(unlock, offerUnlock);
        detailsMenu.getItems().clear();
        for (Line line : lines) {
            MenuItem item = new MenuItem(line.text());
            // Plain text: a tab name from a project file never becomes a mnemonic.
            item.setMnemonicParsing(false);
            Runnable action = line.action();
            if (action == null) {
                item.setDisable(true);
            } else {
                item.setOnAction(event -> run(action, null));
            }
            detailsMenu.getItems().add(item);
        }
        setOffered(details, !lines.isEmpty());
        for (Hyperlink link : List.of(connect, unlock, details, dismiss)) {
            link.setDisable(busy);
        }
        setVisible(true);
        setManaged(true);
    }

    /** Hides the bar: no tab waits. */
    void hideBar() {
        detailsMenu.hide();
        detailsMenu.getItems().clear();
        setVisible(false);
        setManaged(false);
    }

    private static Hyperlink action(String key) {
        Hyperlink link = new Hyperlink(I18n.get(key));
        link.setMnemonicParsing(false);
        link.getStyleClass().add(ACTION_STYLE_CLASS);
        return link;
    }

    private static void setOffered(Hyperlink link, boolean offered) {
        link.setVisible(offered);
        link.setManaged(offered);
    }

    private static void run(@Nullable Runnable action, @Nullable Hyperlink link) {
        if (link != null) {
            link.setVisited(false);
        }
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            logger.warn("A restore bar action failed", e);
        }
    }
}
