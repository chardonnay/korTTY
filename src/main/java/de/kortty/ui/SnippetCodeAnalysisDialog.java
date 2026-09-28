package de.kortty.ui;

import de.kortty.core.ScriptLanguageMixSupport.LanguageMix;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.model.GlobalSettings;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.ScrollPane;
import javafx.stage.Window;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The rich "AI Code Review" window. Its content is a {@link SnippetAnalysisPanel} (the
 * checkbox-selectable report beside the flow diagram, plus the header, text-language and hardening
 * selectors); this class adds the window around it: Apply selected and Close, a remembered geometry
 * and the non-modal ownership. The result is the mixed set of selected items to apply.
 */
public class SnippetCodeAnalysisDialog extends ThemeAwareDialog<SnippetAnalysisPanel.ApplySelection> {

    private final SnippetAnalysisPanel panel;
    private Button applyActionButton;
    private Consumer<SnippetAnalysisPanel.ApplySelection> applyHandler;

    public SnippetCodeAnalysisDialog(
            Window owner,
            String scriptName,
            String snippetLanguage,
            SnippetAiResponseSupport.ScriptAnalysis analysis,
            Supplier<CompletableFuture<SnippetDiagramView.DiagramSource>> diagramMermaidSupplier,
            String activeProfileId,
            Consumer<String> onRerun,
            SnippetAnalysisPanel.SkillContext skillContext,
            LanguageMix languageMix,
            String codeTextLanguageCode) {

        String title = I18n.get("snippets.ai.analysis.title");
        setTitle(scriptName != null && !scriptName.isBlank() ? title + " — " + scriptName.trim() : title);
        setResizable(true);
        // Non-modal so the snippet editor stays usable while the analysis window is open.
        initModality(javafx.stage.Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        panel = new SnippetAnalysisPanel(scriptName, snippetLanguage, analysis, diagramMermaidSupplier,
            activeProfileId, onRerun, this::close, skillContext, languageMix, codeTextLanguageCode);

        // The window (or, in tab mode, the main window) can be made shorter than this stack needs.
        // A DialogPane lays ITSELF out at its content's minimum height when the scene is smaller,
        // which pushes its button bar off screen — Apply/Close then cannot be reached with the
        // mouse at all. Scrolling the content instead keeps the button bar in view at any height.
        ScrollPane contentScroll = new ScrollPane(panel);
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setMinHeight(0);

        ButtonType applyButton = new ButtonType(
            SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.analysis.applySelected"),
            ButtonBar.ButtonData.OK_DONE);
        getDialogPane().setContent(contentScroll);
        getDialogPane().getButtonTypes().addAll(applyButton, ButtonType.CLOSE);
        applyActionButton = (Button) getDialogPane().lookupButton(applyButton);
        applyActionButton.addEventFilter(ActionEvent.ACTION, event -> {
            SnippetAnalysisPanel.ApplySelection selection = panel.readSelection();
            if (selection.isEmpty()) {
                event.consume();
                return;
            }
            if (applyHandler != null) {
                event.consume();
                setApplyProcessing(true);
                try {
                    applyHandler.accept(selection);
                } catch (RuntimeException e) {
                    setApplyProcessing(false);
                    throw e;
                }
            }
        });
        getDialogPane().setPrefWidth(1160);
        getDialogPane().setPrefHeight(720);
        restoreGeometry();
        setResultConverter(buttonType -> buttonType == applyButton ? panel.readSelection() : null);

        setOnShown(event -> startDiagramIfAutoEnabled());
        setOnCloseRequest(event -> persistGeometry());
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> {
            // Read the stage bounds before disposing anything below; the window is still sized.
            persistGeometry();
            panel.dispose();
        });
    }

    /** The embedded analysis view. */
    SnippetAnalysisPanel panel() {
        return panel;
    }

    /**
     * Runs Apply selected without closing the analysis window. The host keeps this dialog open beside
     * the docked progress window until the staged workflow reaches its preview or fails.
     */
    void setApplyHandler(Consumer<SnippetAnalysisPanel.ApplySelection> applyHandler) {
        this.applyHandler = applyHandler;
    }

    void setApplyProcessing(boolean processing) {
        if (applyActionButton != null) {
            applyActionButton.setDisable(processing);
        }
    }

    Window displayWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : getOwner();
    }

    /** The AI profile this analysis was produced with; {@code null} means the default profile. */
    String activeProfileId() {
        return panel.activeProfileId();
    }

    void closeAfterApply() {
        closeDialogOrHostTab();
    }

    /** Starts the (AI-backed) diagram generation, or shows a hint when auto-generation is disabled. */
    void startDiagramIfAutoEnabled() {
        panel.startDiagramIfAutoEnabled();
    }

    /**
     * Restores the window position and size the user last left this dialog at. The stage exists
     * only once the dialog is showing, so the bounds are applied in a DIALOG_SHOWING handler while
     * the pane's preferred size covers the initial layout pass.
     */
    private void restoreGeometry() {
        GlobalSettings settings = SnippetAiDialogSupport.currentSettings();
        DialogGeometrySupport.restore(this,
            settings != null ? settings.getSnippetCodeAnalysisDialogGeometry() : null);
    }

    private void persistGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        DialogGeometrySupport.persist(this,
            (settings, geometry) -> settings.setSnippetCodeAnalysisDialogGeometry(geometry));
    }
}
