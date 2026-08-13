package de.kortty.ui;

import de.kortty.core.SessionJournalLogEntry;
import de.kortty.core.SessionJournalSession;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The dockable live view of one tab's running session journal: an initial backfill of the newest
 * log entries, then real-time appends streamed from the capture session. The panel stays bound to
 * its journal across tab switches and only rebinds when another tab with a live journal is
 * selected; when the bound journal stops or its tab closes, the content is kept with a state badge
 * until a live journal takes over.
 */
public class SessionJournalLivePanel extends BorderPane {

    private enum PanelState { EMPTY, LOADING, LIVE, STOPPED, TAB_CLOSED }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_ENTRIES = SessionJournalLiveFeed.DEFAULT_MAX_ENTRIES;

    private final ObservableList<SessionJournalLogEntry> entries = FXCollections.observableArrayList();
    private final FilteredList<SessionJournalLogEntry> filteredEntries = new FilteredList<>(entries);
    private final ListView<SessionJournalLogEntry> listView = new ListView<>(filteredEntries);

    private final Label titleLabel = new Label();
    private final Label stateBadge = new Label();
    private final ToggleButton followToggle = new ToggleButton(I18n.get("journal.live.follow"));
    private final Button noteButton = new Button(I18n.get("terminal.journal.note"));
    private final Button screenshotButton = new Button(I18n.get("terminal.journal.screenshot"));
    private final Button openViewerButton = new Button(I18n.get("journal.live.openViewer"));
    private final TextField filterField = new TextField();
    private final ToggleButton filterInput = new ToggleButton(I18n.get("journal.live.filter.input"));
    private final ToggleButton filterOutput = new ToggleButton(I18n.get("journal.live.filter.output"));
    private final ToggleButton filterNotes = new ToggleButton(I18n.get("journal.live.filter.notes"));
    private final Label trimHint = new Label(I18n.get("journal.live.trimmed"));
    private final Label placeholder = new Label(I18n.get("journal.live.noJournal"));

    private final java.util.function.Consumer<SessionJournalSession> openViewerAction;

    private TerminalTab boundTab;
    private SessionJournalLiveFeed feed;
    private PanelState state = PanelState.EMPTY;

    public SessionJournalLivePanel(java.util.function.Consumer<SessionJournalSession> openViewerAction) {
        this.openViewerAction = openViewerAction;
        getStyleClass().add("journal-live-panel");
        setTop(buildHeader());
        setCenter(buildList());
        setBottom(buildFooter());
        applyState(PanelState.EMPTY);
    }

    private VBox buildHeader() {
        titleLabel.setStyle("-fx-font-weight: bold;");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        stateBadge.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #9e9e9e;");

        followToggle.setSelected(true);
        followToggle.setTooltip(new Tooltip(I18n.get("journal.live.follow")));
        followToggle.setOnAction(event -> {
            if (followToggle.isSelected()) {
                scrollToEnd();
            }
        });
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
            if (feed != null && openViewerAction != null) {
                openViewerAction.accept(feed.getSession());
            }
        });

        HBox titleRow = new HBox(8, titleLabel, stateBadge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox buttonRow = new HBox(6, followToggle, noteButton, screenshotButton, openViewerButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        filterField.setPromptText(I18n.get("journal.live.filter.prompt"));
        HBox.setHgrow(filterField, Priority.ALWAYS);
        filterInput.setSelected(true);
        filterOutput.setSelected(true);
        filterNotes.setSelected(true);
        filterField.textProperty().addListener((obs, oldText, newText) -> updateFilter());
        filterInput.setOnAction(event -> updateFilter());
        filterOutput.setOnAction(event -> updateFilter());
        filterNotes.setOnAction(event -> updateFilter());
        HBox filterRow = new HBox(6, filterField, filterInput, filterOutput, filterNotes);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, titleRow, buttonRow, filterRow);
        header.setPadding(new Insets(8, 8, 6, 8));
        return header;
    }

    private ListView<SessionJournalLogEntry> buildList() {
        listView.setPlaceholder(placeholder);
        placeholder.setWrapText(true);
        placeholder.setPadding(new Insets(12));
        listView.setStyle("-fx-font-family: 'Menlo', 'Consolas', monospace; -fx-font-size: 0.9231em;");
        listView.setCellFactory(view -> createCell());
        // Scrolling up is the natural "let me read" gesture — it pauses following; the toggle resumes.
        listView.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() > 0 && followToggle.isSelected()) {
                followToggle.setSelected(false);
            }
        });
        return listView;
    }

    private ListCell<SessionJournalLogEntry> createCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(SessionJournalLogEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                    setContextMenu(null);
                    return;
                }
                setText(renderText(entry));
                setStyle(cellStyle(entry));
                setContextMenu(buildEntryContextMenu(entry));
            }
        };
    }

    private static String renderText(SessionJournalLogEntry entry) {
        String time = entry.timestamp() != null
            ? TIME_FORMAT.format(entry.timestamp().atZoneSameInstant(ZoneId.systemDefault()))
            : "";
        String body = switch (entry.kind()) {
            case IN -> entry.redacted() ? "❯ •••" : "❯ " + entry.text();
            case SCREENSHOT -> "📷 " + (entry.file() != null ? entry.file() : "");
            case NOTE -> "✎ " + entry.text();
            default -> entry.text();
        };
        return time + "  " + body;
    }

    private static String cellStyle(SessionJournalLogEntry entry) {
        String color = switch (entry.kind()) {
            case IN -> "#4fc3f7";
            case NOTE -> "#ffcc66";
            case SCREENSHOT -> "#ce93d8";
            case SEED -> "#9e9e9e";
            default -> "#d4d4d4";
        };
        String style = "-fx-background-color: transparent; -fx-text-fill: " + color + ";"
            + " -fx-padding: 1 8 1 8;";
        if (entry.kind() == SessionJournalLogEntry.Kind.IN && !entry.redacted()) {
            style += " -fx-font-weight: bold;";
        }
        if (entry.partial()) {
            style += " -fx-opacity: 0.6;";
        }
        return style;
    }

    private ContextMenu buildEntryContextMenu(SessionJournalLogEntry entry) {
        MenuItem copy = new MenuItem(I18n.get("journal.live.context.copy"));
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(entry.text() != null ? entry.text() : "");
            Clipboard.getSystemClipboard().setContent(content);
        });
        MenuItem noteHere = new MenuItem(I18n.get("journal.live.context.noteHere"));
        noteHere.setDisable(boundTab == null || state != PanelState.LIVE);
        noteHere.setOnAction(event -> {
            if (boundTab != null && entry.timestamp() != null) {
                String reference = "[" + TIME_FORMAT.format(
                    entry.timestamp().atZoneSameInstant(ZoneId.systemDefault())) + "] ";
                boundTab.addJournalNote(reference);
            }
        });
        return new ContextMenu(copy, noteHere);
    }

    private Region buildFooter() {
        trimHint.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #9e9e9e;");
        trimHint.setWrapText(true);
        trimHint.setPadding(new Insets(4, 8, 6, 8));
        trimHint.setVisible(false);
        trimHint.setManaged(false);
        return trimHint;
    }

    private void updateFilter() {
        String query = filterField.getText() != null
            ? filterField.getText().strip().toLowerCase(Locale.ROOT)
            : "";
        boolean input = filterInput.isSelected();
        boolean output = filterOutput.isSelected();
        boolean notes = filterNotes.isSelected();
        filteredEntries.setPredicate(entry -> {
            boolean kindVisible = switch (entry.kind()) {
                case IN -> input;
                case OUT, SEED -> output;
                case NOTE, SCREENSHOT -> notes;
            };
            if (!kindVisible) {
                return false;
            }
            if (query.isEmpty()) {
                return true;
            }
            String text = entry.text() != null ? entry.text() : "";
            return text.toLowerCase(Locale.ROOT).contains(query);
        });
        if (followToggle.isSelected()) {
            scrollToEnd();
        }
    }

    // ==== binding ====

    /**
     * Binds the panel to the given tab's live journal session. A rebind to the same session is a
     * no-op (the tab reference is refreshed); a different session restarts the feed with a fresh
     * backfill. Must be called on the FX thread.
     */
    public void bindToTab(TerminalTab tab) {
        if (tab == null || tab.getTerminalView() == null) {
            return;
        }
        SessionJournalSession session = tab.getTerminalView().getSessionJournalSession();
        if (session == null || !session.isActive()) {
            return;
        }
        if (feed != null && !feed.isStopped() && feed.getSession() == session) {
            boundTab = tab;
            return;
        }
        stopFeed();
        boundTab = tab;
        entries.clear();
        trimHint.setVisible(false);
        trimHint.setManaged(false);
        String connectionName = session.getMetaSnapshot().getConnectionName();
        // Quick-Connect sessions may carry no connection name; the tab title always has one.
        titleLabel.setText(connectionName != null && !connectionName.isBlank()
            ? connectionName
            : tab.getText());
        applyState(PanelState.LOADING);
        SessionJournalLiveFeed newFeed = new SessionJournalLiveFeed(
            session,
            MAX_ENTRIES,
            SessionJournalLiveFeed.DEFAULT_COALESCE_MILLIS,
            Platform::runLater,
            this::onBackfill,
            this::onLiveBatch);
        feed = newFeed;
        newFeed.start();
    }

    private void onBackfill(List<SessionJournalLogEntry> backfill) {
        entries.setAll(backfill);
        applyState(PanelState.LIVE);
        scrollToEnd();
    }

    private void onLiveBatch(List<SessionJournalLogEntry> batch) {
        entries.addAll(batch);
        int overflow = entries.size() - MAX_ENTRIES;
        if (overflow > 0) {
            entries.remove(0, overflow);
            trimHint.setVisible(true);
            trimHint.setManaged(true);
        }
        if (followToggle.isSelected()) {
            scrollToEnd();
        }
    }

    /** The bound journal stopped: keep the content, freeze the feed, show the stopped badge. */
    public void notifyBoundJournalStopped() {
        stopFeed();
        applyState(PanelState.STOPPED);
    }

    /** The bound tab closed: keep the content until another live-journal tab is selected. */
    public void notifyBoundTabClosed() {
        stopFeed();
        boundTab = null;
        applyState(PanelState.TAB_CLOSED);
    }

    /** Shown when the panel is docked and no journal has ever been bound. */
    public void showNoJournalPlaceholder() {
        if (feed == null) {
            applyState(PanelState.EMPTY);
        }
    }

    /** Full detach: feed, tab reference and content (used on hide and window close). */
    public void unbind() {
        stopFeed();
        boundTab = null;
        entries.clear();
        titleLabel.setText("");
        trimHint.setVisible(false);
        trimHint.setManaged(false);
        applyState(PanelState.EMPTY);
    }

    public TerminalTab getBoundTab() {
        return boundTab;
    }

    public SessionJournalSession getBoundSession() {
        return feed != null ? feed.getSession() : null;
    }

    private void stopFeed() {
        if (feed != null) {
            feed.stop();
        }
    }

    private void applyState(PanelState newState) {
        state = newState;
        String badge = switch (newState) {
            case EMPTY -> "";
            case LOADING -> I18n.get("journal.live.loading");
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
        openViewerButton.setDisable(feed == null);
    }

    private void scrollToEnd() {
        if (!filteredEntries.isEmpty()) {
            listView.scrollTo(filteredEntries.size() - 1);
        }
    }

    /** Themed inline like the AI side panel; called from MainWindow's theme application. */
    public void applyTheme(String bgColor, String fgColor) {
        String bg = bgColor != null && !bgColor.isEmpty() ? bgColor : "#1e1e1e";
        setStyle("-fx-background-color: " + bg + ";");
        listView.setStyle("-fx-font-family: 'Menlo', 'Consolas', monospace; -fx-font-size: 0.9231em;"
            + " -fx-background-color: " + bg + "; -fx-control-inner-background: " + bg + ";");
        if (fgColor != null && !fgColor.isEmpty()) {
            titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + fgColor + ";");
            placeholder.setStyle("-fx-text-fill: " + fgColor + ";");
        }
    }
}
