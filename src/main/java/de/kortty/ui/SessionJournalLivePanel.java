package de.kortty.ui;

import de.kortty.core.SessionJournalSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The dockable live view of one tab's running session journal: hosts the full journal page
 * ({@link SessionJournalViewerPane} in compact mode) — timeline cards, live log tail, right-click
 * annotate/marker/copy — plus a slim header with the instant state badge and the note, screenshot
 * and open-viewer actions. The panel stays bound to its journal across tab switches and only
 * rebinds when another tab with a live journal is selected; when the bound journal stops or its
 * tab closes, the page is kept with a state badge until a live journal takes over.
 *
 * <p>One WebView per window: the compact pane is created lazily on first dock, rebound via
 * {@link SessionJournalViewerPane#showJournal} on session change, and disposed on
 * {@link #unbind()} so a hidden panel costs nothing.</p>
 */
public class SessionJournalLivePanel extends BorderPane {

    private enum PanelState { EMPTY, LIVE, STOPPED, TAB_CLOSED }

    private final MainWindow ownerWindow;
    private final java.util.function.Consumer<SessionJournalSession> openViewerAction;

    private final Label titleLabel = new Label();
    private final Label stateBadge = new Label();
    private final Button noteButton = new Button(I18n.get("terminal.journal.note"));
    private final Button screenshotButton = new Button(I18n.get("terminal.journal.screenshot"));
    private final Button openViewerButton = new Button(I18n.get("journal.live.openViewer"));
    private final MenuButton overflowMenu = new MenuButton("⋯");
    private final Label placeholder = new Label(I18n.get("journal.live.noJournal"));
    private final StackPane centerHost = new StackPane();

    private SessionJournalViewerPane viewerPane;
    private TerminalTab boundTab;
    private SessionJournalSession boundSession;
    private PanelState state = PanelState.EMPTY;
    private String pendingThemeBg;

    public SessionJournalLivePanel(MainWindow ownerWindow,
                                   java.util.function.Consumer<SessionJournalSession> openViewerAction) {
        this.ownerWindow = ownerWindow;
        this.openViewerAction = openViewerAction;
        getStyleClass().add("journal-live-panel");
        setTop(buildHeader());
        placeholder.setWrapText(true);
        placeholder.setPadding(new Insets(12));
        centerHost.getChildren().setAll(placeholder);
        setCenter(centerHost);
        applyState(PanelState.EMPTY);
    }

    private VBox buildHeader() {
        titleLabel.setStyle("-fx-font-weight: bold;");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        stateBadge.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #9e9e9e;");

        noteButton.setTooltip(new Tooltip(I18n.get("terminal.journal.note")));
        noteButton.setOnAction(event -> {
            if (boundTab != null) {
                boundTab.addJournalNote();
            }
        });
        screenshotButton.setTooltip(new Tooltip(I18n.get("terminal.journal.screenshot")));
        screenshotButton.setOnAction(event -> {
            if (boundTab != null) {
                boundTab.takeJournalScreenshot(null);
            }
        });
        openViewerButton.setTooltip(new Tooltip(I18n.get("journal.live.openViewer")));
        openViewerButton.setOnAction(event -> {
            if (boundSession != null && openViewerAction != null) {
                openViewerAction.accept(boundSession);
            }
        });
        MenuItem refreshItem = new MenuItem(I18n.get("journal.viewer.refresh"));
        refreshItem.setOnAction(event -> {
            if (viewerPane != null) {
                viewerPane.refresh();
            }
        });
        MenuItem appearanceItem = new MenuItem(I18n.get("journal.viewer.appearance"));
        appearanceItem.setOnAction(event -> {
            if (viewerPane != null) {
                viewerPane.showAppearance(overflowMenu);
            }
        });
        overflowMenu.getItems().addAll(refreshItem, appearanceItem);

        HBox titleRow = new HBox(8, titleLabel, stateBadge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox buttonRow = new HBox(6, noteButton, screenshotButton, openViewerButton, overflowMenu);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, titleRow, buttonRow);
        header.setPadding(new Insets(8, 8, 6, 8));
        return header;
    }

    // ==== binding ====

    /**
     * Binds the panel to the given tab's live journal session. A rebind to the same session is a
     * no-op (the tab reference is refreshed); a different session rebinds the page and restarts
     * the live feed. Must be called on the FX thread.
     */
    public void bindToTab(TerminalTab tab) {
        if (tab == null || tab.getTerminalView() == null) {
            return;
        }
        SessionJournalSession session = tab.getTerminalView().getSessionJournalSession();
        if (session == null || !session.isActive()) {
            return;
        }
        if (session == boundSession && state == PanelState.LIVE) {
            boundTab = tab;
            return;
        }
        boundTab = tab;
        boundSession = session;
        String connectionName = session.getMetaSnapshot().getConnectionName();
        // Quick-Connect sessions may carry no connection name; the tab title always has one.
        titleLabel.setText(connectionName != null && !connectionName.isBlank()
            ? connectionName
            : tab.getText());
        ensureViewerPane();
        viewerPane.showJournal(session.getDirectory());
        viewerPane.attachLiveSession(session);
        applyState(PanelState.LIVE);
    }

    private void ensureViewerPane() {
        if (viewerPane != null) {
            return;
        }
        viewerPane = new SessionJournalViewerPane(ownerWindow, null,
            SessionJournalViewerPane.Mode.COMPACT, titleLabel::setText);
        if (pendingThemeBg != null) {
            viewerPane.applyTheme(pendingThemeBg, null);
        }
        centerHost.getChildren().setAll(viewerPane);
    }

    /** The bound journal stopped: keep the page, stop the feed, show the stopped badge. The
     *  change listener stays attached, so the finalize event renders the ended header. */
    public void notifyBoundJournalStopped() {
        if (viewerPane != null) {
            viewerPane.detachLiveSession();
        }
        applyState(PanelState.STOPPED);
    }

    /** The bound tab closed: keep the page until another live-journal tab is selected. */
    public void notifyBoundTabClosed() {
        if (viewerPane != null) {
            viewerPane.detachLiveSession();
        }
        boundTab = null;
        applyState(PanelState.TAB_CLOSED);
    }

    /** Shown when the panel is docked and no journal has ever been bound. */
    public void showNoJournalPlaceholder() {
        if (viewerPane == null) {
            applyState(PanelState.EMPTY);
        }
    }

    /**
     * Full detach (hide / window close): disposes the page's WebView so a hidden panel costs
     * nothing; re-showing rebuilds it with a fresh render.
     */
    public void unbind() {
        if (viewerPane != null) {
            viewerPane.dispose();
            viewerPane = null;
        }
        centerHost.getChildren().setAll(placeholder);
        boundTab = null;
        boundSession = null;
        titleLabel.setText("");
        applyState(PanelState.EMPTY);
    }

    public TerminalTab getBoundTab() {
        return boundTab;
    }

    public SessionJournalSession getBoundSession() {
        return boundSession;
    }

    private void applyState(PanelState newState) {
        state = newState;
        String badge = switch (newState) {
            case EMPTY -> "";
            case LIVE -> "● " + I18n.get("journal.live.state.live");
            case STOPPED -> I18n.get("journal.live.state.stopped");
            case TAB_CLOSED -> I18n.get("journal.live.state.tabClosed");
        };
        stateBadge.setText(badge);
        stateBadge.setStyle(newState == PanelState.LIVE
            ? "-fx-font-size: 0.8462em; -fx-text-fill: #66bb6a;"
            : "-fx-font-size: 0.8462em; -fx-text-fill: #9e9e9e;");
        boolean live = newState == PanelState.LIVE;
        noteButton.setDisable(!live || boundTab == null);
        screenshotButton.setDisable(!live || boundTab == null);
        openViewerButton.setDisable(boundSession == null);
        overflowMenu.setDisable(viewerPane == null);
    }

    /** Themed inline like the other side panels; the page keeps its own persisted scheme. */
    public void applyTheme(String bgColor, String fgColor) {
        String bg = bgColor != null && !bgColor.isEmpty() ? bgColor : "#1e1e1e";
        pendingThemeBg = bg;
        setStyle("-fx-background-color: " + bg + ";");
        if (fgColor != null && !fgColor.isEmpty()) {
            titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + fgColor + ";");
            placeholder.setStyle("-fx-text-fill: " + fgColor + ";");
        }
        if (viewerPane != null) {
            viewerPane.applyTheme(bg, fgColor);
        }
    }
}
