package de.kortty.ui;

import de.kortty.core.SessionJournalAskService;
import de.kortty.core.SessionJournalVisitedStore;
import de.kortty.model.SessionJournalMeta;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Chat-shaped Q&amp;A side panel of the journal viewer: the user asks about this journal, the
 * {@link SessionJournalAskService} answers from the curated document plus internal log search,
 * follow-ups keep the conversation. Modeled on {@link GuideAskPanel}: soft cancel via a bumped
 * request sequence, a disposed flag guarding every callback, no hard interruption.
 */
final class SessionJournalAskPanel extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalAskPanel.class);
    private static final double MIN_WIDTH_PX = 260;
    private static final int CHAT_FONT_SIZE = 13;

    private final SessionJournalAskService service;
    private final SessionJournalMeta meta;
    /** Navigates the viewer to a curated entry (search hit / citation). */
    private final Consumer<String> entryNavigator;
    /** Navigates the viewer's log panel to a capture-log seq. */
    private final LongConsumer seqNavigator;
    /** Persists an answer as an AGENT journal entry (question, answer markdown); may be null. */
    private final java.util.function.BiConsumer<String, String> noteSaver;

    private final TextField questionField = new TextField();
    private final Button askButton = new Button(I18n.get("journal.ask.ask"));
    private final Button cancelButton = new Button(I18n.get("journal.ask.cancel"));
    private final Button newConversationButton = new Button(I18n.get("journal.ask.newConversation"));
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final HBox statusRow = new HBox(8);
    private final VBox messagesBox = new VBox(10);
    private final ScrollPane messagesScroll = new ScrollPane(messagesBox);

    private final List<SessionJournalAskService.Exchange> transcript = new ArrayList<>();
    private int requestSequence;
    private boolean disposed;
    private volatile int cancelSequenceSnapshot;

    SessionJournalAskPanel(SessionJournalMeta meta,
                           SessionJournalAskService service,
                           Consumer<String> entryNavigator,
                           LongConsumer seqNavigator,
                           java.util.function.BiConsumer<String, String> noteSaver) {
        this.meta = meta;
        this.service = service;
        this.entryNavigator = entryNavigator;
        this.seqNavigator = seqNavigator;
        this.noteSaver = noteSaver;

        setSpacing(8);
        setPadding(new Insets(8));
        setMinWidth(MIN_WIDTH_PX);

        Label title = new Label(I18n.get("journal.ask.title"));
        title.setStyle("-fx-font-weight: bold;");
        newConversationButton.setTooltip(new Tooltip(I18n.get("journal.ask.newConversation.tooltip")));
        newConversationButton.setOnAction(event -> resetConversation());
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(6, title, titleSpacer, newConversationButton);

        messagesBox.setPadding(new Insets(4));
        messagesScroll.setFitToWidth(true);
        VBox.setVgrow(messagesScroll, Priority.ALWAYS);

        questionField.setPromptText(I18n.get("journal.ask.placeholder"));
        questionField.setOnAction(event -> ask());
        askButton.setOnAction(event -> ask());
        HBox questionRow = new HBox(6, questionField, askButton);
        HBox.setHgrow(questionField, Priority.ALWAYS);
        questionField.setMinWidth(0);
        askButton.setMinWidth(Region.USE_PREF_SIZE);

        progress.setPrefSize(18, 18);
        cancelButton.setOnAction(event -> cancel());
        statusRow.getChildren().addAll(progress, statusLabel, cancelButton);
        setBusy(false);

        getChildren().addAll(titleRow, messagesScroll, statusRow, questionRow);
    }

    void focusQuestionField() {
        questionField.requestFocus();
    }

    private void ask() {
        String question = questionField.getText() != null ? questionField.getText().strip() : "";
        if (question.isEmpty() || disposed) {
            return;
        }
        questionField.clear();
        appendUserMessage(question);
        // Question text is never sent to telemetry — only that a journal Q&A ran.
        de.kortty.telemetry.Telemetry.track(
            de.kortty.telemetry.TelemetryEvents.JOURNAL_AI_ASK, java.util.Map.of());
        int sequence = ++requestSequence;
        cancelSequenceSnapshot = sequence;
        setBusy(true);
        List<SessionJournalAskService.Exchange> transcriptSnapshot = List.copyOf(transcript);
        String languageCode = de.kortty.core.LanguageManager.getInstance().getCurrentLanguageCode();
        CompletableFuture
            .supplyAsync(() -> service.ask(meta, question, transcriptSnapshot, languageCode,
                () -> cancelSequenceSnapshot != sequence))
            .whenComplete((answer, error) -> Platform.runLater(() -> {
                if (disposed || sequence != requestSequence) {
                    return;
                }
                setBusy(false);
                if (error != null || answer == null) {
                    logger.warn("Journal ask failed", error);
                    appendNotice(I18n.get("journal.ask.error"));
                    return;
                }
                showAnswer(question, answer);
            }));
    }

    private void showAnswer(String question, SessionJournalAskService.Answer answer) {
        String markdown = answer.markdown();
        if (markdown != null && !markdown.isBlank()) {
            transcript.add(new SessionJournalAskService.Exchange(question, markdown));
            AiChatRenderSupport.renderInto(messagesBox, true, markdown, CHAT_FONT_SIZE);
            if (noteSaver != null) {
                messagesBox.getChildren().add(saveNoteRow(question, markdown));
            }
        } else {
            appendNotice(I18n.get("journal.ask.noAnswer"));
        }
        if (answer.warning() != null && !answer.warning().isBlank()) {
            appendNotice(answer.warning());
        }
        if (!answer.sources().isEmpty()) {
            messagesBox.getChildren().add(sourcesBlock(answer.sources()));
        }
        if (!answer.logEvidence().isEmpty()) {
            messagesBox.getChildren().add(evidenceBlock(answer.logEvidence()));
        }
        scrollToBottom();
    }

    private void appendUserMessage(String question) {
        AiChatRenderSupport.renderInto(messagesBox, false, question, CHAT_FONT_SIZE);
        scrollToBottom();
    }

    private void appendNotice(String text) {
        Label notice = new Label(text);
        notice.setWrapText(true);
        notice.getStyleClass().add("journal-ask-notice");
        notice.setStyle("-fx-opacity: 0.75; -fx-font-style: italic;");
        messagesBox.getChildren().add(notice);
        scrollToBottom();
    }

    /** "Sources" hyperlinks jumping to the cited entries in the timeline. */
    private VBox sourcesBlock(List<SessionJournalAskService.Source> sources) {
        Label header = new Label(I18n.get("journal.ask.sources"));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 0.9em;");
        VBox box = new VBox(2, header);
        for (SessionJournalAskService.Source source : sources) {
            String label = "[" + source.ordinal() + "] " + source.title();
            Hyperlink link = new Hyperlink(label);
            link.setWrapText(true);
            link.setOnAction(event -> {
                if (source.entryId() != null) {
                    SessionJournalVisitedStore.shared().markVisited(
                        SessionJournalVisitedStore.entryKey(journalId(), source.entryId()));
                    entryNavigator.accept(source.entryId());
                }
            });
            box.getChildren().add(link);
        }
        return box;
    }

    /** Per-term match counts with clickable sample hits jumping into the log panel. */
    private VBox evidenceBlock(List<SessionJournalAskService.LogEvidence> evidence) {
        Label header = new Label(I18n.get("journal.ask.logEvidence"));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 0.9em;");
        VBox box = new VBox(2, header);
        for (SessionJournalAskService.LogEvidence item : evidence) {
            Label termLabel = new Label(I18n.get("journal.ask.logEvidence.term",
                item.term(), String.valueOf(item.totalMatches())));
            termLabel.setWrapText(true);
            box.getChildren().add(termLabel);
            for (var hit : item.hits().subList(0, Math.min(item.hits().size(), 10))) {
                String snippet = hit.snippet().length() > 90
                    ? hit.snippet().substring(0, 90) + "…" : hit.snippet();
                Hyperlink link = new Hyperlink("  " + snippet);
                link.setWrapText(true);
                link.setOnAction(event -> {
                    SessionJournalVisitedStore.shared().markVisited(
                        SessionJournalVisitedStore.seqKey(journalId(), hit.seq()));
                    seqNavigator.accept(hit.seq());
                });
                box.getChildren().add(link);
            }
        }
        return box;
    }

    /** "Save as note": persists this Q&A as an AGENT entry in the journal timeline. */
    private HBox saveNoteRow(String question, String markdown) {
        Button saveButton = new Button(I18n.get("journal.ask.saveNote"));
        saveButton.setTooltip(new Tooltip(I18n.get("journal.ask.saveNote.tooltip")));
        saveButton.setOnAction(event -> {
            saveButton.setDisable(true);
            saveButton.setText(I18n.get("journal.ask.saveNote.saved"));
            noteSaver.accept(question, markdown);
        });
        HBox row = new HBox(saveButton);
        row.setPadding(new Insets(0, 0, 4, 0));
        return row;
    }

    private String journalId() {
        return meta.getJournalId() != null ? meta.getJournalId() : String.valueOf(meta.getDirectory());
    }

    private void resetConversation() {
        requestSequence++;
        transcript.clear();
        messagesBox.getChildren().clear();
        setBusy(false);
        questionField.requestFocus();
    }

    private void cancel() {
        requestSequence++;
        cancelSequenceSnapshot = -1; // the running service call sees cancelled == true
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        askButton.setDisable(busy);
        questionField.setDisable(busy);
        statusLabel.setText(busy ? I18n.get("journal.ask.working") : "");
        statusRow.setVisible(busy);
        statusRow.setManaged(busy);
    }

    /** Invalidates any in-flight request. Called from the viewer's dispose path. */
    void dispose() {
        disposed = true;
        requestSequence++;
        cancelSequenceSnapshot = -1;
    }

    private void scrollToBottom() {
        Platform.runLater(() -> messagesScroll.setVvalue(1.0));
    }
}
