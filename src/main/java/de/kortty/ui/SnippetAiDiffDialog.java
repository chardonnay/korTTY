package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SnippetEditorProfile;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogEvent;
import javafx.scene.input.KeyEvent;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

/**
 * Shows the original and AI-generated replacement before applying an editor change, in its own
 * window. The content is a {@link SnippetAiDiffPane} (Monaco's side-by-side diff, the summary split,
 * the finding focus picker and the per-change explanation cards); this class adds the window: Apply
 * and Cancel buttons, a remembered geometry and the non-modal ownership.
 */
public class SnippetAiDiffDialog extends ThemeAwareDialog<Boolean> {

    private final SnippetAiDiffPane pane;

    public SnippetAiDiffDialog(Window owner, String title, String summary, String originalText, String replacementText) {
        this(owner, title, summary, originalText, replacementText, null, EditorSettingsHelper.loadSnippetSettings(), null);
    }

    public SnippetAiDiffDialog(
        Window owner,
        String title,
        String summary,
        String originalText,
        String replacementText,
        String snippetLanguage,
        EditorSettingsHelper.Settings editorSettings,
        SnippetEditorProfile editorProfile) {

        setTitle(title != null && !title.isBlank() ? title : I18n.get("snippets.ai.diff.title"));
        setResizable(true);
        // Explicitly non-modal. A Dialog with an owner defaults to APPLICATION_MODAL, which froze
        // every terminal tab — and the AI-processing window next to it — for the whole review.
        // showAndWait() still works here; it blocks this call, not the other windows.
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        pane = new SnippetAiDiffPane(summary, originalText, replacementText, snippetLanguage, editorSettings, false);

        ButtonType applyButton = new ButtonType(I18n.get("snippets.ai.diff.apply"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(pane);
        getDialogPane().getButtonTypes().addAll(applyButton, ButtonType.CANCEL);
        getDialogPane().setPrefWidth(1040);
        getDialogPane().setPrefHeight(700);
        // After the designed size, so a stored geometry wins over it rather than the other way round.
        restoreGeometry();
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, pane::handleKeyboardShortcut);
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
            persistGeometry();
            pane.dispose();
        });
        addEventHandler(DialogEvent.DIALOG_SHOWN, event -> Platform.runLater(() -> {
            bringToFront();
            pane.fitSummaryHeight();
        }));
        setResultConverter(buttonType -> buttonType == applyButton);
    }

    /** The embedded review content. */
    SnippetAiDiffPane pane() {
        return pane;
    }

    private void restoreGeometry() {
        GlobalSettings settings = currentSettings();
        DialogGeometrySupport.restore(this, settings != null ? settings.getAiDiffDialogGeometry() : null);
    }

    /**
     * Stores where the reviewer left the window, plus the summary divider — but the divider only once
     * they moved it themselves, so a window that was merely resized keeps following the summary's
     * height on the next review.
     */
    private void persistGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        Double moved = pane.movedSummaryDividerPosition();
        DialogGeometrySupport.persist(this, (settings, geometry) -> {
            settings.setAiDiffDialogGeometry(geometry);
            if (moved != null) {
                settings.setAiDiffDialogSummaryDividerPosition(moved);
            }
        });
    }

    private static GlobalSettings currentSettings() {
        try {
            return KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Keeps a newly opened review window above its non-modal editor window on macOS. */
    private void bringToFront() {
        Window window = getDialogPane().getScene() != null
            ? getDialogPane().getScene().getWindow()
            : null;
        if (window == null || !window.isShowing()) {
            return;
        }
        if (window instanceof Stage stage) {
            stage.toFront();
        }
        window.requestFocus();
    }

    /**
     * Attaches per-change explanations (security-fix flow): hover annotations on the modified side plus
     * a themed HTML card panel below the diff, so the "why" is understandable even if a hover anchor
     * does not match.
     */
    public void setChangeExplanations(List<SnippetAiResponseSupport.SecurityChange> changes) {
        pane.setChangeExplanations(changes);
    }

    /**
     * Enables the transient AI-profile picker and a re-run button so this adjustment can be repeated
     * with a different profile. No-op when {@code onRerun} is {@code null} (e.g. the security-fix flow
     * that manages re-runs from its findings window instead).
     */
    public void setRerunHandler(String activeProfileId, java.util.function.Consumer<String> onRerun) {
        pane.setRerunHandler(activeProfileId, onRerun, this::close);
    }
}
