package de.kortty.ui;

import javafx.beans.binding.Bindings;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * A pinned inner tab of the snippet workspace: embeds one never-shown {@link SnippetEditDialog}'s
 * pane via {@link DialogPaneAdoption}. The editor stays bound to its snippet for its whole life
 * (AI jobs, history and the "last AI change" toggle stay per snippet); closing the tab disposes it.
 *
 * <p>The title shows the snippet name, " ●" while unsaved; a small spinner marks running AI work.
 * The tab is also the editor's entry in {@link SnippetEditorRegistry}, so a snippet is edited in
 * at most one place.
 */
final class SnippetEditorTab extends Tab implements DialogPaneHost, SnippetEditorRegistry.OpenEditor {

    private static final String DIRTY_MARK = " ●";

    private final SnippetEditDialog editor;
    private final DialogPaneAdoption adoption;
    private final Runnable revealWorkspace;

    /**
     * @param afterClosed     runs once after the editor's close lifecycle (registry release etc.)
     * @param revealWorkspace brings the workspace itself forward (its window or its main-window tab)
     */
    SnippetEditorTab(SnippetEditDialog editor, Consumer<SnippetEditorTab> afterClosed, Runnable revealWorkspace) {
        this.editor = editor;
        this.revealWorkspace = revealWorkspace;
        this.adoption = new DialogPaneAdoption(editor, this,
            afterClosed != null ? () -> afterClosed.accept(this) : null, true);
        getStyleClass().add("snippet-editor-tab");
        setContent(adoption.adopt());
        textProperty().bind(Bindings.createStringBinding(
            () -> {
                String name = editor.snippetNameProperty().get();
                String label = name != null && !name.isBlank()
                    ? name.trim()
                    : I18n.get("snippets.workspace.untitled");
                return editor.unsavedChangesProperty().get() ? label + DIRTY_MARK : label;
            },
            editor.snippetNameProperty(), editor.unsavedChangesProperty()));
        ProgressIndicator busy = new ProgressIndicator();
        busy.setPrefSize(14, 14);
        busy.setMaxSize(14, 14);
        busy.setTooltip(new Tooltip(I18n.get("snippets.workspace.aiRunning.tooltip")));
        busy.visibleProperty().bind(editor.aiBusyProperty());
        busy.managedProperty().bind(editor.aiBusyProperty());
        graphicProperty().bind(Bindings.when(editor.aiBusyProperty()).then(busy).otherwise((ProgressIndicator) null));
        editor.aiBusyProperty().addListener((obs, wasBusy, isBusy) -> {
            // AI work finished while the user looked at another tab: mark it.
            if (wasBusy && !isBusy && !isSelected()) {
                if (!getStyleClass().contains("attention")) {
                    getStyleClass().add("attention");
                }
            }
        });
        selectedProperty().addListener((obs, was, is) -> {
            if (is) {
                getStyleClass().remove("attention");
            }
        });
        // SED's embedded close guard vetoes the request and closes itself once the user decided.
        setOnCloseRequest(event -> {
            if (!requestClose()) {
                event.consume();
            }
        });
        setOnClosed(event -> adoption.finishClose());
    }

    SnippetEditDialog editor() {
        return editor;
    }

    /**
     * Runs the editor's close guard (AI-running confirm, unsaved prompt). Returns {@code true} when
     * the tab is gone afterwards.
     */
    boolean requestClose() {
        if (adoption.isCloseFinished()) {
            return true;
        }
        if (adoption.requestClose()) {
            closeProgrammatically();
        }
        return adoption.isCloseFinished();
    }

    boolean isClosed() {
        return adoption.isCloseFinished();
    }

    // ---- DialogPaneHost ----

    @Override
    public void closeProgrammatically() {
        TabPane pane = getTabPane();
        if (pane != null) {
            pane.getTabs().remove(this);
        }
        adoption.finishClose();
    }

    @Override
    public boolean isAttached() {
        return getTabPane() != null && !adoption.isCloseFinished();
    }

    @Override
    public void reveal() {
        TabPane pane = getTabPane();
        if (pane != null) {
            pane.getSelectionModel().select(this);
        }
        if (revealWorkspace != null) {
            revealWorkspace.run();
        }
        editor.focusEditor();
    }

    // ---- SnippetEditorRegistry.OpenEditor ----

    @Override
    public String snippetId() {
        return editor.snippetId();
    }

    @Override
    public boolean hasUnsavedChanges() {
        return editor.hasUnsavedChanges();
    }

    @Override
    public Window ownerStage() {
        TabPane pane = getTabPane();
        return pane != null && pane.getScene() != null ? pane.getScene().getWindow() : null;
    }

    @Override
    public boolean confirmCloseFromHost() {
        return editor.confirmCloseFromHost();
    }

    @Override
    public void closeWithoutPrompt() {
        editor.closeWithoutPrompt();
    }
}
