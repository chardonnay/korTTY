package de.kortty.ui;

import javafx.scene.control.Tab;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * The main window's close guards for unsaved work: the snippet workspace and standalone snippet
 * editors, in a main-window tab or in their own window, and file editor tabs. Every method only
 * asks (and saves when the user chooses Save); none of them closes anything, so a caller that
 * aborts after an approval — a later prompt was cancelled — leaves every editor open and intact.
 *
 * <p>Contract: FX thread only.
 */
final class HostedCloseGuards {

    private HostedCloseGuards() {
    }

    /**
     * Asks every tab in {@code tabs} that guards its close, in tab order: a {@link DialogHostTab}
     * whose dialog has unsaved work, or a tab that is a {@link HostedCloseGuard} itself (a
     * {@link FileEditorTab} with unsaved changes). A tab that has to ask is selected first
     * ({@code select}) so the user sees what the prompt is about. Stops at the first veto.
     *
     * @return {@code true} when every guarded tab may be disposed
     */
    static boolean confirmTabs(List<? extends Tab> tabs, Consumer<Tab> select) {
        for (Tab tab : new ArrayList<>(tabs)) {
            if (!tab.isClosable() || !needsCloseConfirmation(tab)) {
                continue;
            }
            if (select != null) {
                select.accept(tab);
            }
            if (!confirmTab(tab)) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the tab's own close button would ask about unsaved work, for a path that bypasses the
     * tab's close request (Cmd/Ctrl+W, File → Close Tab). Tabs that guard nothing may close.
     *
     * @return {@code true} when {@code tab} may be disposed
     */
    static boolean confirmTab(Tab tab) {
        if (tab instanceof DialogHostTab hostTab) {
            return hostTab.confirmClose();
        }
        if (tab instanceof HostedCloseGuard guard) {
            return guard.confirmHostedClose();
        }
        return true;
    }

    /** Whether {@link #confirmTab(Tab)} would ask anything right now; never prompts. */
    static boolean needsCloseConfirmation(Tab tab) {
        if (tab instanceof DialogHostTab hostTab) {
            return hostTab.needsCloseConfirmation();
        }
        return tab instanceof HostedCloseGuard guard && guard.needsCloseConfirmation();
    }

    /**
     * Asks each editor about its unsaved (or running) work; stops at the first veto. The editors
     * bring themselves forward before they ask.
     */
    static boolean confirmEditors(List<SnippetEditorRegistry.OpenEditor> editors) {
        for (SnippetEditorRegistry.OpenEditor editor : editors) {
            if (!editor.confirmCloseFromHost()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Standalone snippet editors in their own window whose owner chain reaches {@code ancestor}
     * (they close with it). Editors in a main-window tab are left out: that tab's guard asks.
     */
    static List<SnippetEditorRegistry.OpenEditor> standaloneEditorsOwnedBy(Window ancestor) {
        List<SnippetEditorRegistry.OpenEditor> owned = new ArrayList<>();
        for (SnippetEditorRegistry.OpenEditor editor : windowedStandaloneEditors()) {
            if (ancestor != null && isOwnedBy(editor.ownerStage(), ancestor)) {
                owned.add(editor);
            }
        }
        return owned;
    }

    /**
     * Standalone snippet editors in their own window that none of {@code windows} owns — e.g. the
     * swarm "save as snippet" editor, which has no owner at all. Only quitting the application
     * closes them, so only the quit path asks them.
     */
    static List<SnippetEditorRegistry.OpenEditor> standaloneEditorsOutside(Collection<? extends Window> windows) {
        List<SnippetEditorRegistry.OpenEditor> outside = new ArrayList<>();
        for (SnippetEditorRegistry.OpenEditor editor : windowedStandaloneEditors()) {
            boolean owned = false;
            for (Window window : windows) {
                if (isOwnedBy(editor.ownerStage(), window)) {
                    owned = true;
                    break;
                }
            }
            if (!owned) {
                outside.add(editor);
            }
        }
        return outside;
    }

    /** Whether {@code window} is {@code ancestor} or (transitively) owned by it. */
    static boolean isOwnedBy(Window window, Window ancestor) {
        Window current = window;
        // Owner chains are short; the bound only guards against a cycle a broken window could report.
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current == ancestor) {
                return true;
            }
            if (current instanceof Stage stage) {
                current = stage.getOwner();
            } else if (current instanceof PopupWindow popup) {
                current = popup.getOwnerWindow();
            } else {
                return false;
            }
        }
        return false;
    }

    private static List<SnippetEditorRegistry.OpenEditor> windowedStandaloneEditors() {
        List<SnippetEditorRegistry.OpenEditor> editors = new ArrayList<>();
        for (SnippetEditorRegistry.OpenEditor editor : SnippetEditorRegistry.all()) {
            if (editor instanceof SnippetEditDialog.StandaloneRegistration standalone
                && !standalone.isHostedInMainTab()) {
                editors.add(editor);
            }
        }
        return editors;
    }
}
