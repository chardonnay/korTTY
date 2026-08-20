package de.kortty.ui;

import de.kortty.core.SessionJournalAskService;
import de.kortty.core.SessionJournalCrossSearchService;
import de.kortty.core.SessionJournalVisitedStore;
import de.kortty.model.SessionJournalMeta;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The manager's cross-journal AI search: a question over all (or the selected) journals, the
 * AI's summary as a small chat with follow-ups, and a hit tree whose leaves jump straight to
 * the place in the journal. Visited hits are marked via {@link SessionJournalVisitedStore} so a
 * longer investigation stays orientable. Soft cancel via request sequence, GuideAskPanel-style.
 */
final class SessionJournalSearchPanel extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalSearchPanel.class);
    private static final int CHAT_FONT_SIZE = 13;
    private static final DateTimeFormatter HIT_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss");

    /** What the panel needs from the manager dialog. */
    interface Host {

        /** Every journal currently listed (unfiltered). */
        List<SessionJournalMeta> allJournals();

        /** The table selection, for the "search selection only" scope. */
        List<SessionJournalMeta> selectedJournals();

        /** Shows per-journal hit counts in the table (extra column + row highlight). */
        void showHitCounts(Map<Path, Long> counts);

        /** Removes the hit column and highlight again. */
        void clearHitCounts();

        /** Opens the journal and jumps to the hit target; a null target just opens it. */
        void openHit(SessionJournalMeta meta, SessionJournalCrossSearchService.HitTarget target);
    }

    private final SessionJournalCrossSearchService service;
    private final Host host;

    private final TextField questionField = new TextField();
    private final Button askButton = new Button(I18n.get("journal.search.ask"));
    private final Button cancelButton = new Button(I18n.get("journal.ask.cancel"));
    private final CheckBox selectionOnlyCheck = new CheckBox(I18n.get("journal.search.selectionOnly"));
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final HBox statusRow = new HBox(8);
    private final VBox messagesBox = new VBox(10);
    private final ScrollPane messagesScroll = new ScrollPane(messagesBox);
    private final Label totalLabel = new Label();
    private final TreeView<Object> hitTree = new TreeView<>();

    private final List<SessionJournalAskService.Exchange> transcript = new ArrayList<>();
    private int requestSequence;
    private boolean disposed;
    private volatile int cancelSequenceSnapshot;

    SessionJournalSearchPanel(SessionJournalCrossSearchService service, Host host) {
        this.service = service;
        this.host = host;

        setSpacing(6);
        setPadding(new Insets(6, 0, 0, 0));

        questionField.setPromptText(I18n.get("journal.search.placeholder"));
        questionField.setOnAction(event -> ask());
        askButton.setOnAction(event -> ask());
        selectionOnlyCheck.setTooltip(new Tooltip(I18n.get("journal.search.selectionOnly.tooltip")));
        HBox questionRow = new HBox(6, questionField, askButton, selectionOnlyCheck);
        questionRow.setStyle("-fx-alignment: center-left;");
        HBox.setHgrow(questionField, Priority.ALWAYS);
        questionField.setMinWidth(0);
        askButton.setMinWidth(Region.USE_PREF_SIZE);

        progress.setPrefSize(18, 18);
        cancelButton.setOnAction(event -> cancel());
        statusRow.getChildren().addAll(progress, statusLabel, cancelButton);
        setBusy(false);

        messagesBox.setPadding(new Insets(4));
        messagesScroll.setFitToWidth(true);

        hitTree.setShowRoot(false);
        hitTree.setRoot(new TreeItem<>());
        hitTree.setCellFactory(tree -> new HitCell()); // TreeView has no placeholder API

        VBox hitsBox = new VBox(4, totalLabel, hitTree);
        VBox.setVgrow(hitTree, Priority.ALWAYS);
        SplitPane content = new SplitPane(messagesScroll, hitsBox);
        content.setOrientation(Orientation.HORIZONTAL);
        content.setDividerPositions(0.5);
        content.setPrefHeight(240);
        VBox.setVgrow(content, Priority.ALWAYS);

        getChildren().addAll(questionRow, statusRow, content);
    }

    void focusQuestionField() {
        questionField.requestFocus();
    }

    /** Invalidates any in-flight request; the manager calls this when the panel is hidden. */
    void dispose() {
        disposed = true;
        requestSequence++;
        cancelSequenceSnapshot = -1;
    }

    private void ask() {
        String question = questionField.getText() != null ? questionField.getText().strip() : "";
        if (question.isEmpty() || disposed) {
            return;
        }
        List<SessionJournalMeta> scope = selectionOnlyCheck.isSelected()
            ? host.selectedJournals()
            : host.allJournals();
        if (scope.isEmpty()) {
            return;
        }
        questionField.clear();
        AiChatRenderSupport.renderInto(messagesBox, false, question, CHAT_FONT_SIZE);
        scrollToBottom();
        // Question text is never sent to telemetry — only that a search ran and its scope size.
        de.kortty.telemetry.Telemetry.track(
            de.kortty.telemetry.TelemetryEvents.JOURNAL_AI_CROSS_SEARCH,
            java.util.Map.of("journals", bucket(scope.size())));
        int sequence = ++requestSequence;
        cancelSequenceSnapshot = sequence;
        setBusy(true);
        List<SessionJournalAskService.Exchange> transcriptSnapshot = List.copyOf(transcript);
        String languageCode = de.kortty.core.LanguageManager.getInstance().getCurrentLanguageCode();
        CompletableFuture
            .supplyAsync(() -> service.search(scope, question, transcriptSnapshot, languageCode,
                () -> cancelSequenceSnapshot != sequence))
            .whenComplete((result, error) -> Platform.runLater(() -> {
                if (disposed || sequence != requestSequence) {
                    return;
                }
                setBusy(false);
                if (error != null || result == null) {
                    logger.warn("Journal cross search failed", error);
                    appendNotice(I18n.get("journal.ask.error"));
                    return;
                }
                showResult(question, result);
            }));
    }

    private void showResult(String question, SessionJournalCrossSearchService.Result result) {
        if (result.answerMarkdown() != null && !result.answerMarkdown().isBlank()) {
            transcript.add(new SessionJournalAskService.Exchange(question, result.answerMarkdown()));
            AiChatRenderSupport.renderInto(messagesBox, true, result.answerMarkdown(), CHAT_FONT_SIZE);
        }
        if (result.warning() != null && !result.warning().isBlank()) {
            appendNotice(result.warning());
        }
        if (result.journals().isEmpty() && (result.answerMarkdown() == null
            || result.answerMarkdown().isBlank())) {
            appendNotice(I18n.get("journal.search.noHits"));
        }
        scrollToBottom();

        totalLabel.setText(I18n.get("journal.search.totalHits",
            String.valueOf(result.totalHits()), String.valueOf(result.journals().size())));
        TreeItem<Object> root = new TreeItem<>();
        Map<Path, Long> counts = new HashMap<>();
        for (SessionJournalCrossSearchService.JournalHits journal : result.journals()) {
            TreeItem<Object> journalItem = new TreeItem<>(journal);
            journalItem.setExpanded(result.journals().size() <= 3);
            long shownLogHits = 0;
            for (SessionJournalCrossSearchService.Hit hit : journal.hits()) {
                journalItem.getChildren().add(new TreeItem<>(new HitRow(journal.meta(), hit)));
                if (hit.target() instanceof SessionJournalCrossSearchService.LogTarget) {
                    shownLogHits++;
                }
            }
            // The leaves are curated (deduplicated, capped); the exact remainder is one line.
            if (journal.totalLogMatches() > shownLogHits) {
                journalItem.getChildren().add(new TreeItem<>(I18n.get("journal.search.moreInLog",
                    String.valueOf(journal.totalLogMatches() - shownLogHits))));
            }
            root.getChildren().add(journalItem);
            if (journal.meta().getDirectory() != null) {
                counts.put(journal.meta().getDirectory().toAbsolutePath().normalize(),
                    journalTotal(journal));
            }
        }
        hitTree.setRoot(root);
        if (counts.isEmpty()) {
            host.clearHitCounts();
        } else {
            host.showHitCounts(counts);
        }
    }

    private static long journalTotal(SessionJournalCrossSearchService.JournalHits journal) {
        long curated = journal.hits().stream()
            .filter(h -> h.target() instanceof SessionJournalCrossSearchService.EntryTarget)
            .count();
        return curated + journal.totalLogMatches();
    }

    private void appendNotice(String text) {
        Label notice = new Label(text);
        notice.setWrapText(true);
        notice.setStyle("-fx-opacity: 0.75; -fx-font-style: italic;");
        messagesBox.getChildren().add(notice);
    }

    private void cancel() {
        requestSequence++;
        cancelSequenceSnapshot = -1;
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        askButton.setDisable(busy);
        questionField.setDisable(busy);
        statusLabel.setText(busy ? I18n.get("journal.search.working") : "");
        statusRow.setVisible(busy);
        statusRow.setManaged(busy);
    }

    private void scrollToBottom() {
        Platform.runLater(() -> messagesScroll.setVvalue(1.0));
    }

    private static String bucket(int size) {
        if (size <= 10) {
            return "1-10";
        }
        if (size <= 50) {
            return "11-50";
        }
        if (size <= 200) {
            return "51-200";
        }
        return "200+";
    }

    /** One hit leaf: which journal it belongs to plus the hit itself. */
    private record HitRow(SessionJournalMeta meta, SessionJournalCrossSearchService.Hit hit) {

        String visitedKey() {
            String journalId = meta.getJournalId() != null
                ? meta.getJournalId() : String.valueOf(meta.getDirectory());
            if (hit.target() instanceof SessionJournalCrossSearchService.EntryTarget entry) {
                return SessionJournalVisitedStore.entryKey(journalId, entry.entryId());
            }
            SessionJournalCrossSearchService.LogTarget log =
                (SessionJournalCrossSearchService.LogTarget) hit.target();
            return SessionJournalVisitedStore.seqKey(journalId, log.seq());
        }
    }

    /** Journal nodes bold with counts; hit leaves hyperlink-like, muted once visited. */
    private final class HitCell extends TreeCell<Object> {

        HitCell() {
            setOnMouseClicked(event -> {
                if (getItem() instanceof HitRow row) {
                    SessionJournalVisitedStore.shared().markVisited(row.visitedKey());
                    updateItem(getItem(), false); // repaint this cell as visited right away
                    host.openHit(row.meta(), row.hit().target());
                    return;
                }
                // The journal node itself opens on double-click — the single click keeps
                // doing what tree nodes do (select, expand via the arrow).
                if (event.getClickCount() == 2
                    && getItem() instanceof SessionJournalCrossSearchService.JournalHits journal) {
                    host.openHit(journal.meta(), null);
                }
            });
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setStyle("");
                setTooltip(null);
                return;
            }
            if (item instanceof SessionJournalCrossSearchService.JournalHits journal) {
                String title = journal.meta().getTitle() != null
                    ? journal.meta().getTitle() : String.valueOf(journal.meta().getConnectionName());
                long total = journalTotal(journal);
                // "0 hits" would read like a bug — this journal is here because the AI relates
                // it to the question, only no literal search string matched a position.
                setText(title + " — " + (total > 0
                    ? I18n.get("journal.search.journalHits", String.valueOf(total))
                    : I18n.get("journal.search.noExactHits")));
                setStyle("-fx-font-weight: bold;");
                String openHint = I18n.get("journal.search.openHint");
                setTooltip(new Tooltip(journal.aiReason() != null && !journal.aiReason().isBlank()
                    ? journal.aiReason() + "\n" + openHint
                    : openHint));
                return;
            }
            if (item instanceof String info) {
                setText(info);
                setStyle("-fx-opacity: 0.6; -fx-font-style: italic;");
                setTooltip(null);
                return;
            }
            if (item instanceof HitRow row) {
                StringBuilder text = new StringBuilder(96);
                if (row.hit().timestamp() != null) {
                    text.append('[').append(row.hit().timestamp()
                        .atZoneSameInstant(ZoneId.systemDefault()).format(HIT_TIME)).append("] ");
                }
                String snippet = row.hit().snippet() != null ? row.hit().snippet() : "";
                text.append(snippet.length() > 120 ? snippet.substring(0, 120) + "…" : snippet);
                if (row.hit().occurrences() > 1) {
                    text.append(" ×").append(row.hit().occurrences());
                }
                boolean visited = SessionJournalVisitedStore.shared().isVisited(row.visitedKey());
                setText((visited ? "✓ " : "") + text);
                setStyle(visited
                    ? "-fx-opacity: 0.6;"
                    : "-fx-underline: true;");
                setTooltip(null);
            }
        }
    }
}
