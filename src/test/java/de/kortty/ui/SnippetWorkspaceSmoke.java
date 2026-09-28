package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the unified snippet workspace (library + inner editor tabs) in a shown stage:
 * <ul>
 *   <li>browsing previews without constructing an editor,</li>
 *   <li>typing into the preview promotes it to a pinned editor and the character arrives,</li>
 *   <li>a snippet is pinned at most once — within a workspace and across two workspaces,</li>
 *   <li>Shortcut+S saves the active editor, the row refreshes and stays selected,</li>
 *   <li>Shortcut+B collapses and restores the library,</li>
 *   <li>Esc does not close a windowed workspace,</li>
 *   <li>a CANCEL answer vetoes the inner tab close and the workspace close; DISCARD closes,</li>
 *   <li>closing the outer (main-window) tab tears the nested editors down.</li>
 * </ul>
 */
public final class SnippetWorkspaceSmoke {

    private SnippetWorkspaceSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-snippet-workspace-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                run(isolatedHome, failure, done);
            } catch (Throwable error) {
                failure.compareAndSet(null, stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("SnippetWorkspaceSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SnippetWorkspaceSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SnippetWorkspaceSmoke OK");
        System.exit(0);
    }

    /** Runs FX steps one after another, each after its own delay; any throw ends the smoke. */
    private static final class Steps {
        private record Step(long delayMs, String name, Runnable action) {
        }

        private final Deque<Step> steps = new ArrayDeque<>();
        private final AtomicReference<String> failure;
        private final CountDownLatch done;
        private final Runnable cleanup;

        Steps(AtomicReference<String> failure, CountDownLatch done, Runnable cleanup) {
            this.failure = failure;
            this.done = done;
            this.cleanup = cleanup;
        }

        Steps then(long delayMs, String name, Runnable action) {
            steps.add(new Step(delayMs, name, action));
            return this;
        }

        void start() {
            next();
        }

        private void next() {
            Step step = steps.poll();
            if (step == null) {
                finish();
                return;
            }
            PauseTransition pause = new PauseTransition(Duration.millis(Math.max(1, step.delayMs())));
            pause.setOnFinished(event -> {
                try {
                    System.out.println("step: " + step.name());
                    step.action().run();
                } catch (Throwable error) {
                    failure.compareAndSet(null, "[" + step.name() + "] " + stack(error));
                    finish();
                    return;
                }
                next();
            });
            pause.play();
        }

        private void finish() {
            try {
                cleanup.run();
            } catch (Throwable ignored) {
                // best effort
            } finally {
                done.countDown();
            }
        }
    }

    private static void run(Path isolatedHome, AtomicReference<String> failure, CountDownLatch done) throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        SnippetManager manager = new SnippetManager(isolatedHome.resolve(".kortty"));
        Snippet alpha = snippet("alpha.sh", "echo alpha\n");
        Snippet beta = snippet("beta.sh", "echo beta\n");
        Snippet gamma = snippet("gamma.sh", "echo gamma\n");
        manager.addSnippet(alpha);
        manager.addSnippet(beta);
        manager.addSnippet(gamma);
        manager.save();

        TabPane mainTabs = new TabPane();
        Stage stage = new Stage();
        stage.setScene(new Scene(mainTabs, 1400, 900));
        stage.show();
        // A second "main window" for the cross-workspace dedupe check.
        TabPane otherTabs = new TabPane();
        Stage otherStage = new Stage();
        otherStage.setScene(new Scene(otherTabs, 1200, 800));
        otherStage.show();

        SnippetWorkspaceDialog workspace = new SnippetWorkspaceDialog(manager, null);
        DialogHostTab workspaceTab = DialogHostTab.host(mainTabs, SnippetWorkspaceDialog.TOOL_ID, workspace, null);
        SnippetWorkspaceDialog other = new SnippetWorkspaceDialog(manager, null);
        DialogHostTab otherTab = DialogHostTab.host(otherTabs, SnippetWorkspaceDialog.TOOL_ID, other, null);
        AtomicReference<SnippetWorkspaceDialog> windowed = new AtomicReference<>();

        Runnable cleanup = () -> {
            SnippetWorkspaceDialog.setUnsavedPrompterForTesting(null);
            if (windowed.get() != null && windowed.get().isShowing()) {
                windowed.get().closeWithoutPrompt();
            }
            stage.hide();
            otherStage.hide();
        };

        new Steps(failure, done, cleanup)
            .then(400, "hosted workspace fills its tab", () -> {
                check(workspace.isHostedInTab(), "workspace must be hosted in the main tab");
                check(workspace.openEditorCount() == 0, "no editor may exist before anything is opened");
            })
            .then(50, "browse alpha", () -> selectRow(workspace, alpha))
            .then(400, "preview shows alpha", () -> {
                check(workspace.previewTab().shownSnippet() == alpha, "preview must show alpha");
                check(workspace.editorTabPane().getTabs().contains(workspace.previewTab()), "preview tab must be open");
                check(workspace.openEditorCount() == 0, "previewing must not construct an editor");
            })
            .then(50, "browse gamma, then beta", () -> {
                selectRow(workspace, gamma);
                selectRow(workspace, beta);
            })
            .then(400, "preview reused, still no editor", () -> {
                check(workspace.previewTab().shownSnippet() == beta, "preview must follow to beta");
                check(workspace.openEditorCount() == 0, "browsing must never construct an editor");
                long previews = workspace.editorTabPane().getTabs().stream()
                    .filter(tab -> tab == workspace.previewTab()).count();
                check(previews == 1 && workspace.editorTabPane().getTabs().size() == 1,
                    "exactly one (reused) preview tab expected, tabs: " + workspace.editorTabPane().getTabs());
                check(workspace.previewTab().hasEditor(), "the preview renders through one read-only editor");
            })
            .then(50, "typing into the preview promotes it", () -> {
                MonacoEditorPane previewEditor = workspace.previewTab().editorPane();
                KeyEvent typed = new KeyEvent(KeyEvent.KEY_TYPED, "x", "", KeyCode.UNDEFINED,
                    false, false, false, false);
                Event.fireEvent(previewEditor, typed);
                check(workspace.openEditorCount() == 1, "typing must pin the previewed snippet");
                SnippetEditorTab tab = workspace.editorTabs().getFirst();
                check(tab.snippetId().equals(beta.getId()), "the pinned tab must edit beta");
                check(!workspace.editorTabPane().getTabs().contains(workspace.previewTab()),
                    "the promoted preview tab must be replaced by the editor tab");
                check(workspace.editorTabPane().getSelectionModel().getSelectedItem() == tab,
                    "the new editor tab must be selected");
                MonacoEditorPane content = field(tab.editor(), "contentArea", MonacoEditorPane.class);
                check(content.getText().startsWith("x"), "the typed character must arrive, got: " + content.getText());
                check(tab.hasUnsavedChanges(), "the promoted editor must be dirty after the typed character");
                check(tab.getText().endsWith("●"), "the dirty tab title must carry the dot: " + tab.getText());
                check(beta.getContent().equals("echo beta\n"), "nothing may be saved before the user saves");
            })
            .then(200, "opening beta again selects the same tab", () -> {
                workspace.openSnippetById(beta.getId(), true);
                check(workspace.openEditorCount() == 1, "the same snippet must never be open twice");
                check(SnippetEditorRegistry.find(beta.getId()).isPresent(), "beta must be claimed in the registry");
            })
            .then(50, "another workspace cannot pin beta", () -> {
                other.openSnippetById(beta.getId(), true);
                check(other.openEditorCount() == 0,
                    "a snippet edited in another workspace must not be pinned twice");
                other.openSnippetById(alpha.getId(), true);
                check(other.openEditorCount() == 1, "the other workspace may pin a different snippet");
                check(SnippetEditorRegistry.find(alpha.getId()).isPresent(), "alpha must be claimed");
            })
            .then(50, "the first workspace only previews alpha (open elsewhere)", () -> {
                workspace.openSnippetById(alpha.getId(), false);
                check(workspace.openEditorCount() == 1, "browsing a snippet open elsewhere must not pin it");
                workspace.openSnippetById(alpha.getId(), true);
                check(workspace.openEditorCount() == 1, "opening a snippet open elsewhere reveals, never duplicates");
                workspace.editorTabPane().getSelectionModel().select(workspace.editorTabs().getFirst());
            })
            .then(200, "Shortcut+S saves the active editor", () -> {
                SnippetEditorTab tab = workspace.editorTabs().getFirst();
                check(selectedRowId(workspace).equals(beta.getId()),
                    "the library row must follow the active tab, got " + selectedRowId(workspace));
                Node target = tab.editor().getDialogPane();
                Event.fireEvent(target, shortcut(KeyCode.S));
                check(!tab.hasUnsavedChanges(), "Shortcut+S must save the active editor");
                check(beta.getContent().startsWith("xecho beta"), "beta must hold the saved text: " + beta.getContent());
                check(!tab.getText().endsWith("●"), "the saved tab title must drop the dot: " + tab.getText());
                SnippetManager reloaded = new SnippetManager(isolatedHome.resolve(".kortty"));
                try {
                    reloaded.load();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                check(reloaded.findById(beta.getId()).map(Snippet::getContent).orElse("").startsWith("xecho beta"),
                    "the save must reach snippets.xml");
            })
            .then(300, "the row refreshed and kept its selection", () -> {
                check(selectedRowId(workspace).equals(beta.getId()),
                    "the saved row must stay selected, got " + selectedRowId(workspace));
                check(workspace.library().table().getItems().contains(beta), "beta must still be listed");
            })
            .then(50, "Shortcut+B collapses and restores the library", () -> {
                check(workspace.isLibraryVisible(), "the library starts visible");
                Event.fireEvent(workspace.getDialogPane(), shortcut(KeyCode.B));
                check(!workspace.isLibraryVisible(), "Shortcut+B must collapse the library");
                Event.fireEvent(workspace.getDialogPane(), shortcut(KeyCode.B));
                check(workspace.isLibraryVisible(), "Shortcut+B again must restore the library");
            })
            .then(50, "Esc does not close a windowed workspace", () -> {
                SnippetWorkspaceDialog window = new SnippetWorkspaceDialog(manager, null);
                window.initOwner(stage);
                windowed.set(window);
                window.show();
            })
            .then(400, "fire Esc", () -> {
                SnippetWorkspaceDialog window = windowed.get();
                check(window.isShowing(), "the windowed workspace must be showing");
                Event.fireEvent(window.getDialogPane(), key(KeyCode.ESCAPE));
                Node searchField = window.getDialogPane().lookup(".text-field");
                if (searchField != null) {
                    Event.fireEvent(searchField, key(KeyCode.ESCAPE));
                }
            })
            .then(300, "still open after Esc", () -> {
                SnippetWorkspaceDialog window = windowed.get();
                check(window.isShowing(), "Esc must never close the workspace");
                window.close();
            })
            .then(300, "a clean windowed workspace closes normally", () ->
                check(!windowed.get().isShowing(), "closing a clean workspace must not be vetoed"))
            .then(50, "CANCEL vetoes the inner tab close", () -> {
                SnippetEditorTab tab = workspace.editorTabs().getFirst();
                tab.editor().applyInitialKeystroke(0, "y");
                check(tab.hasUnsavedChanges(), "editor must be dirty again");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(editor -> SnippetEditDialog.UnsavedContentChoice.CANCEL);
                Event.fireEvent(tab, new Event(tab, tab, Tab.TAB_CLOSE_REQUEST_EVENT));
                check(workspace.editorTabs().contains(tab) && !tab.isClosed(), "CANCEL must keep the tab open");
                Event.fireEvent(workspace.getDialogPane(), shortcut(KeyCode.W));
                check(workspace.editorTabs().contains(tab), "CANCEL must veto Shortcut+W too");
                check(tab.hasUnsavedChanges(), "the edits must survive a vetoed close");
            })
            .then(50, "CANCEL vetoes the workspace close", () -> {
                check(!workspace.confirmHostedClose(), "CANCEL must veto confirmHostedClose");
                Event.fireEvent(workspaceTab, new Event(workspaceTab, workspaceTab, Tab.TAB_CLOSE_REQUEST_EVENT));
                check(mainTabs.getTabs().contains(workspaceTab), "CANCEL must keep the workspace tab open");
                check(workspace.openEditorCount() == 1, "a vetoed workspace close keeps its editors");
            })
            .then(50, "several dirty editors: one bulk answer", () -> {
                workspace.openSnippetById(gamma.getId(), true);
                check(workspace.openEditorCount() == 2, "gamma must be pinned");
                workspace.editorTabs().get(1).editor().applyInitialKeystroke(0, "z");
                int[] asked = {0};
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(editor -> {
                    asked[0]++;
                    check(editor == null, "several dirty editors must be asked about once, in bulk");
                    return SnippetEditDialog.UnsavedContentChoice.CANCEL;
                });
                check(!workspace.confirmHostedClose(), "bulk CANCEL must veto");
                check(asked[0] == 1, "exactly one bulk prompt expected, got " + asked[0]);
            })
            .then(50, "DISCARD closes the workspace tab and tears the editors down", () -> {
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(editor -> SnippetEditDialog.UnsavedContentChoice.DISCARD);
                Event.fireEvent(workspaceTab, new Event(workspaceTab, workspaceTab, Tab.TAB_CLOSE_REQUEST_EVENT));
                // The TabPane skin removes a tab whose close request was not consumed; do it the
                // same way here, then let the host's onClosed run the teardown.
                mainTabs.getTabs().remove(workspaceTab);
                if (workspaceTab.getOnClosed() != null) {
                    workspaceTab.getOnClosed().handle(new Event(Tab.CLOSED_EVENT));
                }
                check(workspace.openEditorCount() == 0, "teardown must close every nested editor");
                check(SnippetEditorRegistry.find(beta.getId()).isEmpty(), "beta must be released");
                check(SnippetEditorRegistry.find(gamma.getId()).isEmpty(), "gamma must be released");
                check(beta.getContent().startsWith("xecho beta") && !beta.getContent().startsWith("y"),
                    "discarded edits must not be saved: " + beta.getContent());
            })
            .then(50, "the other workspace tears down too", () -> {
                otherTab.closeProgrammatically();
                check(other.openEditorCount() == 0, "programmatic close must dispose the nested editors");
                check(SnippetEditorRegistry.find(alpha.getId()).isEmpty(), "alpha must be released");
            })
            .start();
    }

    private static Snippet snippet(String name, String content) {
        Snippet snippet = new Snippet();
        snippet.setName(name);
        snippet.setLanguage("bash");
        snippet.setContent(content);
        return snippet;
    }

    private static void selectRow(SnippetWorkspaceDialog workspace, Snippet snippet) {
        var selection = workspace.library().table().getSelectionModel();
        selection.clearSelection();
        selection.select(snippet);
    }

    private static String selectedRowId(SnippetWorkspaceDialog workspace) {
        Snippet selected = workspace.library().table().getSelectionModel().getSelectedItem();
        return selected != null ? selected.getId() : "<none>";
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static KeyEvent shortcut(KeyCode code) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, false, mac);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
