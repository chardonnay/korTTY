package de.kortty.ui;

import de.kortty.core.AiSentContext;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import org.jetbrains.annotations.Nullable;

/**
 * The "Mitgesendet" link of a KI-Agent run in the designs that have no activity list of their own. It is one compact line
 * above the designs' rows, the same in every design, and it names what the run's latest turn sent. A click opens the
 * details in a popup above the line. The line stays after the run ends and is hidden again when a new run starts.
 * FX thread only.
 */
final class AgentDesignSentLink extends HBox {

    private static final double POPUP_WIDTH = 520;
    private static final double POPUP_MAX_HEIGHT = 360;
    // A click on the link hides an open popup first (it is outside it); this window keeps that click from reopening it.
    private static final long REOPEN_GUARD_MILLIS = 250;

    private final AgentSentLinkModel model = new AgentSentLinkModel();
    private final Button link = new Button();
    private @Nullable Popup popup;
    private @Nullable ScrollPane popupBody;
    private long popupHiddenAtMillis;

    AgentDesignSentLink() {
        getStyleClass().add("ai-agent-design-strip");
        setSpacing(8);
        setAlignment(Pos.CENTER_RIGHT);
        setPadding(new Insets(2, 10, 2, 10));
        link.getStyleClass().addAll("ai-agent-design-button", "ai-agent-design-icon-button");
        // A narrow pane shortens the text with an ellipsis instead of pushing the row wider.
        link.setMinWidth(0);
        link.setFocusTraversable(false);
        link.setTooltip(new Tooltip(I18n.get("ai.memory.sent.tooltip")));
        link.setOnAction(event -> togglePopup());
        getChildren().add(link);
        hideLink();
    }

    /** Shows what {@code sent} sent as the line's text; an empty context keeps the earlier one. */
    void publish(@Nullable AiSentContext sent) {
        model.publish(sent);
        refresh();
    }

    /** Hides the line and closes its popup, when a new run starts. */
    void reset() {
        model.reset();
        closePopup();
        hideLink();
    }

    private void refresh() {
        AiSentContext context = model.context();
        if (context == null) {
            closePopup();
            hideLink();
            return;
        }
        link.setText(SentContextView.summary(context));
        showLink();
        if (popup != null && popup.isShowing() && popupBody != null) {
            popupBody.setContent(SentContextView.create(context, SentContextView.Style.AGENT, 0, true));
        }
    }

    private void togglePopup() {
        if (popup != null && popup.isShowing()) {
            closePopup();
            return;
        }
        AiSentContext context = model.context();
        if (context == null || System.currentTimeMillis() - popupHiddenAtMillis < REOPEN_GUARD_MILLIS) {
            return;
        }
        Bounds bounds = link.localToScreen(link.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        ScrollPane body = new ScrollPane(SentContextView.create(context, SentContextView.Style.AGENT, 0, true));
        body.setFitToWidth(true);
        body.setPrefViewportWidth(POPUP_WIDTH);
        body.setMaxHeight(POPUP_MAX_HEIGHT);
        VBox surface = new VBox(body);
        surface.getStyleClass().add("kortty-popup-surface");
        // A raw Popup inherits no author stylesheets, so the root gets the design's base sheet, as the other popups do.
        AppDesignStyleSupport.registerApplicationBaseStyles(surface);
        AppDesignStyleSupport.applyToParent(surface);
        Popup created = new Popup();
        created.setAutoHide(true);
        created.setOnAutoHide(event -> popupHiddenAtMillis = System.currentTimeMillis());
        created.getContent().add(surface);
        created.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_BOTTOM_RIGHT);
        created.show(link, bounds.getMaxX(), bounds.getMinY());
        popup = created;
        popupBody = body;
    }

    private void closePopup() {
        if (popup != null) {
            popup.hide();
        }
        popup = null;
        popupBody = null;
    }

    private void showLink() {
        setVisible(true);
        setManaged(true);
    }

    private void hideLink() {
        setVisible(false);
        setManaged(false);
    }
}
