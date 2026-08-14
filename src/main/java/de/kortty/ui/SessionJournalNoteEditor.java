package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.SessionJournalAiSupport;
import de.kortty.core.SessionJournalNoteTranslationSupport;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The note editing control used wherever a journal note is written: a multi-line field plus an AI
 * translation into a language picked from the drop-down. A note is often typed in whatever
 * language the operator thinks in, while the journal itself is read by someone else.
 *
 * <p>Translation runs through the journal's own AI seam, so it follows the journal AI profile and
 * never reaches an internet tool. The control degrades quietly: without an AI profile the button
 * is disabled with a tooltip, and the field stays a perfectly ordinary text area.
 */
final class SessionJournalNoteEditor extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalNoteEditor.class);

    /** Enough room to see a note as a whole rather than through a slit. */
    static final int DEFAULT_ROWS = 6;

    private final TextArea textArea = new TextArea();
    private final ComboBox<AiLanguageSupport.LanguageOption> languageCombo = new ComboBox<>();
    private final Button translateButton = new Button(I18n.get("journal.note.translate"));
    private final Label statusLabel = new Label();
    private Task<String> translationTask;

    SessionJournalNoteEditor() {
        this(DEFAULT_ROWS);
    }

    SessionJournalNoteEditor(int rows) {
        super(6);
        textArea.setPrefRowCount(Math.max(DEFAULT_ROWS, rows));
        textArea.setWrapText(true);
        textArea.setPromptText(I18n.get("journal.note.prompt"));
        VBox.setVgrow(textArea, Priority.ALWAYS);

        languageCombo.setEditable(true);
        languageCombo.setPrefWidth(220);
        // The value goes into the prompt as text ("translate into language code …") and is never
        // parsed as a locale, so a language the list does not carry can simply be typed.
        languageCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiLanguageSupport.LanguageOption option) {
                return option == null ? "" : option.label();
            }

            @Override
            public AiLanguageSupport.LanguageOption fromString(String text) {
                String typed = text == null ? "" : text.trim();
                if (typed.isEmpty()) {
                    return null;
                }
                for (AiLanguageSupport.LanguageOption option : languageCombo.getItems()) {
                    if (typed.equalsIgnoreCase(option.label()) || typed.equalsIgnoreCase(option.code())) {
                        return option;
                    }
                }
                return new AiLanguageSupport.LanguageOption(typed, typed);
            }
        });
        String remembered = AiLanguageSupport.resolveFallbackLanguageCode(rememberedLanguageCode());
        languageCombo.getItems().setAll(AiLanguageSupport.buildAvailableLanguageOptions(remembered));
        languageCombo.setValue(AiLanguageSupport.findOption(languageCombo.getItems(), remembered));

        translateButton.setOnAction(event -> runTranslation());
        statusLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        statusLabel.setWrapText(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8,
            new Label(I18n.get("journal.note.translate.language")), languageCombo,
            translateButton, spacer, statusLabel);
        controls.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(textArea, controls);
        updateTranslateAvailability();
    }

    String getText() {
        return textArea.getText();
    }

    void setText(String text) {
        textArea.setText(text != null ? text : "");
    }

    TextArea textArea() {
        return textArea;
    }

    /** True while an AI translation is in flight; a dialog should not close underneath it. */
    boolean isTranslating() {
        return translationTask != null && translationTask.isRunning();
    }

    private String rememberedLanguageCode() {
        GlobalSettings settings = settings();
        return settings != null ? settings.getSessionJournalNoteTranslationLanguage() : null;
    }

    private static GlobalSettings settings() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    /** Translation needs an AI profile the journal may use; without one the button says so. */
    private void updateTranslateAvailability() {
        boolean available;
        try {
            available = SessionJournalAiSupport.applicationInvoker().isAvailable();
        } catch (Exception e) {
            available = false;
        }
        translateButton.setDisable(!available);
        translateButton.setTooltip(available ? null : new Tooltip(I18n.get("journal.note.translate.unavailable")));
    }

    private void runTranslation() {
        if (isTranslating()) {
            return;
        }
        String text = textArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        AiLanguageSupport.LanguageOption target = languageCombo.getConverter()
            .fromString(languageCombo.getEditor().getText());
        if (target == null) {
            target = languageCombo.getValue();
        }
        if (target == null) {
            return;
        }
        String targetCode = target.code();
        rememberLanguage(targetCode);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return SessionJournalNoteTranslationSupport.translate(
                    SessionJournalAiSupport.applicationInvoker(), text, targetCode);
            }
        };
        task.setOnRunning(event -> {
            translateButton.setDisable(true);
            statusLabel.setText(I18n.get("journal.note.translate.running"));
        });
        task.setOnSucceeded(event -> {
            translateButton.setDisable(false);
            statusLabel.setText("");
            String translated = task.getValue();
            if (translated != null && !translated.isBlank()) {
                // replaceText, not setText: the change goes through the field's own edit path, so
                // the original is one Ctrl+Z away when the translation is not what was wanted.
                textArea.replaceText(0, textArea.getLength(), translated);
            }
        });
        task.setOnFailed(event -> {
            translateButton.setDisable(false);
            Throwable failure = task.getException();
            String detail = failure != null && failure.getMessage() != null
                ? failure.getMessage()
                : I18n.get("ai.result.error");
            // Never the note text itself, only the failure: notes can quote anything.
            logger.warn("Journal note translation failed: {}", detail);
            statusLabel.setText(I18n.get("journal.note.translate.failed", detail));
        });
        task.setOnCancelled(event -> {
            translateButton.setDisable(false);
            statusLabel.setText("");
        });
        translationTask = task;
        Thread runner = new Thread(task, "SessionJournal-NoteTranslate");
        runner.setDaemon(true);
        runner.start();
    }

    private void rememberLanguage(String languageCode) {
        GlobalSettings settings = settings();
        if (settings == null || languageCode == null || languageCode.isBlank()) {
            return;
        }
        settings.setSessionJournalNoteTranslationLanguage(languageCode);
        try {
            KorTTYApplication.getInstance().getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.debug("Could not persist the note translation language: {}", e.getMessage());
        }
    }

    /** Cancels a running translation; call when the hosting dialog closes. */
    void cancelTranslation() {
        Task<String> task = translationTask;
        if (task != null && task.isRunning()) {
            task.cancel(true);
        }
    }

    /** Focuses the text field once the dialog is on screen. */
    void focusText() {
        Platform.runLater(textArea::requestFocus);
    }
}
