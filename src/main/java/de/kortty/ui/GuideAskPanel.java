package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GuideAskPromptSupport;
import de.kortty.core.GuideDocsRetriever;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * "Ask the manual" side panel of the {@link GuideViewer}: question field, async AI call and the
 * rendered answer with citation links plus a native sources list. All completion callbacks are
 * guarded by a request sequence and a disposed flag (the {@link GuideViewer} WebView lessons);
 * cancel is a soft cancel — the sequence is bumped and a late result is simply dropped.
 */
final class GuideAskPanel extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(GuideAskPanel.class);

    private final KorTTYApplication app;
    private final GuideAskService service;
    private final String guideLang;
    private final Consumer<String> locationNavigator;

    private final TextField questionField = new TextField();
    private final Button clearButton = new Button("✕");
    private final MenuButton historyButton = new MenuButton();
    private final Button askButton = new Button(I18n.get("guide.ask.ask"));
    private final Button cancelButton = new Button(I18n.get("guide.ask.cancel"));
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label statusLabel = new Label();
    private final HBox statusRow = new HBox(8);
    private final Button copyButton = new Button(I18n.get("guide.ask.copy"));
    private final HBox answerActions = new HBox(6);
    private final WebView answerView = new WebView();
    private final Label sourcesHeader = new Label(I18n.get("guide.ask.sources"));
    private final VBox sourcesBox = new VBox(2);
    // Session-only fallback when no settings manager is available (e.g. smoke harness).
    private final List<String> sessionHistory = new ArrayList<>();

    private String lastAnswerHtml;
    private String lastAnswerMarkdown;
    private int requestSequence;
    private boolean disposed;

    GuideAskPanel(KorTTYApplication app, String guideLang, Consumer<String> locationNavigator) {
        this.app = app;
        this.service = new GuideAskService(app);
        this.guideLang = guideLang;
        this.locationNavigator = locationNavigator;

        setSpacing(8);
        setPadding(new Insets(8));
        setMinWidth(260);
        getStyleClass().add("guide-ask-panel");

        questionField.setPromptText(I18n.get("guide.ask.placeholder"));
        questionField.setOnAction(event -> ask());
        clearButton.setTooltip(new Tooltip(I18n.get("guide.ask.clear")));
        clearButton.setOnAction(event -> {
            questionField.clear();
            questionField.requestFocus();
        });
        historyButton.setTooltip(new Tooltip(I18n.get("guide.ask.history")));
        historyButton.setOnShowing(event -> rebuildHistoryMenu());
        askButton.setOnAction(event -> ask());
        HBox questionRow = new HBox(6, questionField, clearButton, historyButton, askButton);
        HBox.setHgrow(questionField, Priority.ALWAYS);

        progress.setPrefSize(18, 18);
        cancelButton.setOnAction(event -> cancel());
        statusRow.getChildren().addAll(progress, statusLabel, cancelButton);
        setBusy(false);

        copyButton.setTooltip(new Tooltip(I18n.get("guide.ask.copy.tooltip")));
        copyButton.setOnAction(event -> copyAnswer());
        Region actionsSpacer = new Region();
        HBox.setHgrow(actionsSpacer, Priority.ALWAYS);
        answerActions.setAlignment(Pos.CENTER_RIGHT);
        answerActions.getChildren().addAll(actionsSpacer, copyButton);
        showAnswerActions(false);

        answerView.setContextMenuEnabled(false);
        VBox.setVgrow(answerView, Priority.ALWAYS);
        installCitationLinkHandler(answerView.getEngine());
        // Load the empty themed page right away — a fresh WebView renders white otherwise.
        showHtml(GuideAskPromptSupport.renderAnswerHtml(""));

        sourcesHeader.getStyleClass().add("guide-ask-sources-header");
        showSources(List.of());

        getChildren().addAll(questionRow, statusRow, answerActions, answerView,
            sourcesHeader, sourcesBox);
    }

    void focusQuestionField() {
        questionField.requestFocus();
    }

    private void ask() {
        String question = questionField.getText() != null ? questionField.getText().strip() : "";
        if (question.isEmpty() || disposed) {
            return;
        }
        recordHistory(question);
        int sequence = ++requestSequence;
        setBusy(true);
        showSources(List.of());
        CompletableFuture
            .supplyAsync(() -> service.ask(question, guideLang))
            .whenComplete((answer, error) -> Platform.runLater(() -> {
                if (disposed || sequence != requestSequence) {
                    return;
                }
                setBusy(false);
                if (error != null) {
                    showFailure(unwrap(error));
                } else if (answer.nothingRetrieved()) {
                    showMessage(I18n.get("guide.ask.notFound"));
                } else {
                    showAnswer(answer);
                }
            }));
    }

    private void cancel() {
        requestSequence++;
        setBusy(false);
    }

    /** Invalidates any in-flight request and drops the answer page. Called from the viewer. */
    void dispose() {
        disposed = true;
        requestSequence++;
        try {
            answerView.getEngine().loadContent("");
        } catch (RuntimeException e) {
            logger.debug("Guide ask panel dispose cleanup failed", e);
        }
    }

    private void setBusy(boolean busy) {
        askButton.setDisable(busy);
        questionField.setDisable(busy);
        statusLabel.setText(busy ? I18n.get("guide.ask.working") : "");
        statusRow.setVisible(busy);
        statusRow.setManaged(busy);
    }

    /** Package-private so the smoke harness can render a sample answer without an LLM call. */
    void showAnswer(GuideAskService.Answer answer) {
        lastAnswerMarkdown = answer.markdown();
        showHtml(GuideAskPromptSupport.renderAnswerHtml(answer.markdown()));
        showSources(answer.excerpts());
        showAnswerActions(true);
    }

    private void showMessage(String message) {
        lastAnswerMarkdown = null;
        showHtml(GuideAskPromptSupport.renderAnswerHtml(message));
        showSources(List.of());
        showAnswerActions(false);
    }

    // ---------------------------------------------------------------- history & clipboard

    /** Last 10 questions, newest first — persisted in the global settings when available. */
    private List<String> historyEntries() {
        GlobalSettings settings = settings();
        return settings != null ? settings.getGuideAskHistory() : sessionHistory;
    }

    private void recordHistory(String question) {
        try {
            GlobalSettings settings = settings();
            if (settings != null) {
                settings.addGuideAskHistoryEntry(question);
                app.getGlobalSettingsManager().save();
            } else {
                sessionHistory.remove(question);
                sessionHistory.add(0, question);
                while (sessionHistory.size() > 10) {
                    sessionHistory.remove(sessionHistory.size() - 1);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not persist guide ask history", e);
        }
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    private void rebuildHistoryMenu() {
        historyButton.getItems().clear();
        List<String> entries = historyEntries();
        if (entries.isEmpty()) {
            MenuItem empty = new MenuItem(I18n.get("guide.ask.history.empty"));
            empty.setDisable(true);
            historyButton.getItems().add(empty);
            return;
        }
        for (String entry : entries) {
            String label = entry.length() > 60 ? entry.substring(0, 57) + "…" : entry;
            MenuItem item = new MenuItem(label);
            item.setOnAction(event -> {
                questionField.setText(entry);
                questionField.positionCaret(entry.length());
                questionField.requestFocus();
            });
            historyButton.getItems().add(item);
        }
    }

    private void copyAnswer() {
        if (lastAnswerMarkdown == null || lastAnswerMarkdown.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(lastAnswerMarkdown);
        Clipboard.getSystemClipboard().setContent(content);
        // Brief inline confirmation on the button itself, then restore the label.
        copyButton.setText(I18n.get("guide.ask.copied"));
        copyButton.setDisable(true);
        PauseTransition restore = new PauseTransition(Duration.seconds(2));
        restore.setOnFinished(event -> {
            if (!disposed) {
                copyButton.setText(I18n.get("guide.ask.copy"));
                copyButton.setDisable(false);
            }
        });
        restore.play();
    }

    private void showAnswerActions(boolean visible) {
        answerActions.setVisible(visible);
        answerActions.setManaged(visible);
    }

    private void showFailure(Throwable error) {
        String message;
        if (error instanceof GuideAskService.AskException ask) {
            message = switch (ask.kind()) {
                case NO_PROFILE -> I18n.get("ai.error.notConfigured");
                case VAULT_LOCKED -> I18n.get("guide.ask.vaultLocked");
                case NO_INDEX -> I18n.get("guide.ask.unavailable");
                case NOT_PROMPT_SERVICE, AI_ERROR ->
                    I18n.get("guide.ask.failed", String.valueOf(ask.getMessage()));
            };
        } else {
            logger.warn("Unexpected guide ask failure", error);
            message = I18n.get("guide.ask.failed", String.valueOf(error.getMessage()));
        }
        showMessage(message);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
            ? error.getCause()
            : error;
    }

    private void showHtml(String html) {
        lastAnswerHtml = html;
        answerView.getEngine().loadContent(html);
    }

    private void showSources(List<GuideDocsRetriever.Excerpt> excerpts) {
        sourcesBox.getChildren().clear();
        boolean hasSources = !excerpts.isEmpty();
        sourcesHeader.setVisible(hasSources);
        sourcesHeader.setManaged(hasSources);
        sourcesBox.setVisible(hasSources);
        sourcesBox.setManaged(hasSources);
        for (GuideDocsRetriever.Excerpt excerpt : excerpts) {
            String label = excerpt.sectionTitle().isBlank()
                || excerpt.sectionTitle().equals(excerpt.pageTitle())
                ? excerpt.pageTitle()
                : excerpt.pageTitle() + " › " + excerpt.sectionTitle();
            Hyperlink link = new Hyperlink(label);
            link.setWrapText(true);
            link.setOnAction(event -> locationNavigator.accept(excerpt.location()));
            sourcesBox.getChildren().add(link);
        }
    }

    /**
     * Intercepts clicks on {@code kortty-guide:} citation links inside the answer page — the
     * same location-listener pattern the {@link GuideViewer} uses for mailto/http links; no
     * JS-to-Java bridge. The answer page is restored afterwards because the cancelled
     * navigation blanks the loadContent page.
     */
    private void installCitationLinkHandler(WebEngine engine) {
        engine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (disposed || newLoc == null
                || !newLoc.startsWith(GuideAskPromptSupport.GUIDE_LINK_SCHEME)) {
                return;
            }
            String location = newLoc.substring(GuideAskPromptSupport.GUIDE_LINK_SCHEME.length());
            Platform.runLater(() -> {
                if (disposed) {
                    return;
                }
                engine.getLoadWorker().cancel();
                if (lastAnswerHtml != null) {
                    engine.loadContent(lastAnswerHtml);
                }
                locationNavigator.accept(location);
            });
        });
    }
}
