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
import javafx.scene.control.MenuItem;
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
import java.util.List;
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
 *   <li>a standalone editor (other entry points) is revealed instead of pinned a second time, and
 *       the main window's close guards ask it (veto keeps it, approval closes nothing),</li>
 *   <li>closing the outer (main-window) tab tears the nested editors down,</li>
 *   <li>the library's analysis column and filter follow the stored analyses (read off the FX
 *       thread at open, live after every change).</li>
 * </ul>
 */
public final class SnippetWorkspaceSmoke {

    private SnippetWorkspaceSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-snippet-workspace-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        // The library's analysis column reads real files from this throwaway store.
        System.setProperty(de.kortty.core.SnippetAnalysisStore.DIRECTORY_PROPERTY,
            isolatedHome.resolve("snippet-analyses").toString());
        // Unsaved-editor drafts go to real files too (crash protection is about files).
        System.setProperty(de.kortty.core.SnippetDraftStore.DIRECTORY_PROPERTY,
            isolatedHome.resolve("snippet-drafts").toString());
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
        Snippet delta = snippet("delta.sh", "echo delta\n");
        manager.addSnippet(delta);
        manager.save();
        de.kortty.core.SnippetDraftStore drafts = de.kortty.core.SnippetDraftStore.shared();
        Path draftDir = isolatedHome.resolve("snippet-drafts");

        // Stored analyses before the workspace opens: alpha has open findings, gamma a result that
        // waits for review, beta none. Flushed, so the library reads them from disk.
        de.kortty.core.SnippetAnalysisStore analyses = de.kortty.core.SnippetAnalysisStore.shared();
        analyses.addAnalysis(alpha.getId(), analysisOf(alpha, "a1", alpha.getContent()));
        analyses.addAnalysis(gamma.getId(), analysisOf(gamma, "g1", gamma.getContent())
            .withRun(de.kortty.core.SnippetAnalysisRecord.ApplyRun.started("run", 1L, null, List.of(), null)
                .withOutcome(de.kortty.core.SnippetAnalysisRecord.RunOutcome.PENDING_REVIEW, 2L)));
        analyses.flush(java.time.Duration.ofSeconds(5));

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
        AtomicReference<SnippetEditDialog> standalone = new AtomicReference<>();

        Runnable cleanup = () -> {
            SnippetWorkspaceDialog.setUnsavedPrompterForTesting(null);
            if (windowed.get() != null && windowed.get().isShowing()) {
                windowed.get().closeWithoutPrompt();
            }
            if (standalone.get() != null && standalone.get().isShowing()) {
                standalone.get().closeWithoutPrompt();
            }
            stage.hide();
            otherStage.hide();
        };

        new Steps(failure, done, cleanup)
            .then(400, "hosted workspace fills its tab", () -> {
                check(workspace.isHostedInTab(), "workspace must be hosted in the main tab");
                check(workspace.openEditorCount() == 0, "no editor may exist before anything is opened");
            })
            .then(600, "the analysis column shows the stored analyses", () -> {
                SnippetLibraryPane library = workspace.library();
                check(library.table().getColumns().stream()
                        .anyMatch(column -> SnippetLibraryPane.ANALYSIS_COLUMN_ID.equals(column.getId())),
                    "the library must have the analysis status column");
                de.kortty.core.SnippetAnalysisOverview.Status alphaStatus = library.analysisStatus(alpha);
                check(alphaStatus.kind() == de.kortty.core.SnippetAnalysisOverview.Kind.OPEN_FINDINGS
                        && alphaStatus.openFindings() == 2 && !alphaStatus.stale(),
                    "alpha must show two open findings, got " + alphaStatus);
                check(SnippetLibraryPane.analysisStatusText(alphaStatus).startsWith("\u26A0"),
                    "open findings are marked with the warning sign");
                check(library.analysisStatus(gamma).kind() == de.kortty.core.SnippetAnalysisOverview.Kind.REVIEW_PENDING,
                    "gamma must show the pending review, got " + library.analysisStatus(gamma));
                check(!library.analysisStatus(beta).hasAnalysis(), "beta has no analysis");
            })
            .then(50, "the analysis filter narrows the library", () -> {
                SnippetLibraryPane library = workspace.library();
                library.analysisFilter().setValue(de.kortty.core.SnippetAnalysisOverview.Filter.OPEN_FINDINGS);
                // gamma's pending result has not been applied either, so its findings are still open.
                check(new java.util.HashSet<>(library.table().getItems()).equals(java.util.Set.of(alpha, gamma)),
                    "Open findings must list alpha and gamma, got " + library.table().getItems());
                library.analysisFilter().setValue(de.kortty.core.SnippetAnalysisOverview.Filter.REVIEW_PENDING);
                check(library.table().getItems().equals(List.of(gamma)),
                    "Review pending must list gamma only, got " + library.table().getItems());
                library.analysisFilter().setValue(de.kortty.core.SnippetAnalysisOverview.Filter.STALE);
                check(library.table().getItems().isEmpty(), "nothing is stale yet");
                // A new analysis of beta that describes older content arrives while the filter is on.
                analyses.addAnalysis(beta.getId(), analysisOf(beta, "b1", "echo older beta\n"));
                check(library.table().getItems().equals(List.of(beta)),
                    "the stale filter must pick up beta's new analysis live, got " + library.table().getItems());
                analyses.discardAll(beta.getId());
                check(library.table().getItems().isEmpty(), "discarding beta's analyses must drop it from the filter");
                library.analysisFilter().setValue(de.kortty.core.SnippetAnalysisOverview.Filter.ALL);
                check(library.table().getItems().size() == 4, "All must list every snippet again");
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
            .then(50, "open gamma in a standalone editor", () -> {
                SnippetEditDialog editor = new SnippetEditDialog(gamma, List.of());
                editor.initOwner(otherStage);
                standalone.set(editor);
                editor.showNonBlocking(null);
            })
            .then(800, "the workspace reveals the standalone editor instead of pinning gamma", () -> {
                SnippetEditDialog editor = standalone.get();
                check(editor.isShowing(), "the standalone editor must be showing");
                SnippetEditorRegistry.OpenEditor entry = SnippetEditorRegistry.find(gamma.getId()).orElse(null);
                check(entry instanceof SnippetEditDialog.StandaloneRegistration registration
                        && registration.editor() == editor,
                    "the standalone editor must claim gamma in the registry, got " + entry);
                int before = workspace.openEditorCount();
                workspace.openSnippetById(gamma.getId(), true);
                check(workspace.openEditorCount() == before,
                    "a snippet open in a standalone editor must not be pinned a second time");
                check(HostedCloseGuards.standaloneEditorsOwnedBy(otherStage).contains(entry),
                    "closing its owner window must guard the standalone editor");
                check(!HostedCloseGuards.standaloneEditorsOwnedBy(stage).contains(entry),
                    "another window must not guard it");
                check(HostedCloseGuards.standaloneEditorsOutside(List.of(stage)).contains(entry)
                        && HostedCloseGuards.standaloneEditorsOutside(List.of(stage, otherStage)).isEmpty(),
                    "quit must ask editors no closing window owns, and only those");

                editor.applyInitialKeystroke(0, "q");
                check(entry.hasUnsavedChanges(), "the standalone editor must be dirty");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(prompted -> SnippetEditDialog.UnsavedContentChoice.CANCEL);
                check(!HostedCloseGuards.confirmEditors(HostedCloseGuards.standaloneEditorsOwnedBy(otherStage)),
                    "CANCEL must veto the window close");
                check(editor.isShowing() && entry.hasUnsavedChanges(), "a veto must keep the editor and its edits");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(prompted -> SnippetEditDialog.UnsavedContentChoice.DISCARD);
                check(HostedCloseGuards.confirmEditors(HostedCloseGuards.standaloneEditorsOwnedBy(otherStage)),
                    "DISCARD must approve the window close");
                check(editor.isShowing(), "an approval alone must not close the editor");
                entry.closeWithoutPrompt();
            })
            .then(300, "closing the standalone editor releases gamma", () -> {
                check(!standalone.get().isShowing(), "closeWithoutPrompt must close the standalone editor");
                check(SnippetEditorRegistry.find(gamma.getId()).isEmpty(), "gamma must be released");
                check(SnippetEditorRegistry.all().stream()
                        .noneMatch(editor -> editor instanceof SnippetEditDialog.StandaloneRegistration),
                    "the standalone editor must leave the registry");
                check(gamma.getContent().equals("echo gamma\n"), "discarded edits must not be saved: " + gamma.getContent());
            })
            .then(50, "a standalone editor hosted as a main tab drops the scene-wide undo accelerator", () -> {
                SnippetEditDialog windowedEditor = new SnippetEditDialog(alpha, List.of());
                MenuItem windowedUndo = field(windowedEditor, "undoItem", MenuItem.class);
                check(windowedUndo.getAccelerator() != null, "a windowed editor keeps its Shortcut+Z accelerator");
                SnippetEditDialog tabEditor = new SnippetEditDialog(alpha, List.of());
                DialogHostTab hostTab = DialogHostTab.host(mainTabs, null, tabEditor, null);
                MenuItem tabUndo = field(tabEditor, "undoItem", MenuItem.class);
                check(tabUndo.getAccelerator() == null,
                    "a tab-hosted editor must not register Shortcut+Z on the main window's scene");
                check(!hostTab.needsCloseConfirmation() && hostTab.confirmClose(),
                    "a clean hosted editor never asks the main window's close guard");
                hostTab.closeProgrammatically();
                mainTabs.getSelectionModel().select(workspaceTab);
            })
            .then(50, "draft autosave: typing writes a draft, saving removes it", () -> {
                workspace.openSnippetById(delta.getId(), true);
                SnippetEditorTab deltaTab = workspace.editorTabs().stream()
                    .filter(t -> t.snippetId().equals(delta.getId())).findFirst().orElseThrow();
                SnippetEditDialog editor = deltaTab.editor();
                check(editor.draftAutosave() != null, "a snippet editor keeps drafts");
                editor.applyInitialKeystroke(0, "d");
                editor.draftAutosave().flushForTesting();
                drafts.flush(5_000);
                Path file = draftDir.resolve(delta.getId() + ".json");
                check(Files.exists(file), "the unsaved edit must be written as a draft: " + file);
                check(drafts.load(delta.getId()).join().orElseThrow().content().startsWith("decho delta"),
                    "the draft must hold the edited content");
                check(editor.saveFromHost(), "saving must work");
                drafts.flush(5_000);
                check(!Files.exists(file), "a successful save must delete the draft");
                editor.applyInitialKeystroke(0, "e");
                editor.draftAutosave().flushForTesting();
                drafts.flush(5_000);
                check(Files.exists(file), "new edits write a new draft");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(e -> SnippetEditDialog.UnsavedContentChoice.DISCARD);
                check(deltaTab.requestClose(), "DISCARD closes the tab");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(null);
                drafts.flush(5_000);
                check(!Files.exists(file), "an explicit discard must delete the draft");
            })
            .then(50, "a draft left behind by a crash is offered, never applied", () -> {
                drafts.save(de.kortty.core.SnippetDraftStore.SnippetDraft.of(delta.getId(), 1_700_000_000_000L, false,
                    "delta.sh", "bash", "", "", "", "echo restored\n",
                    de.kortty.core.SnippetDiagramSupport.contentHash(delta.getContent())));
                drafts.flush(5_000);
                workspace.openSnippetById(delta.getId(), true);
            })
            .then(400, "the banner restores the draft into the editor", () -> {
                SnippetEditorTab deltaTab = workspace.editorTabs().stream()
                    .filter(t -> t.snippetId().equals(delta.getId())).findFirst().orElseThrow();
                SnippetEditDialog editor = deltaTab.editor();
                check(editor.draftAutosave().isOffering(), "the older draft must be offered");
                check(editor.draftAutosave().bannerForTesting() != null
                        && editor.draftAutosave().bannerForTesting().getScene() != null,
                    "the banner must be shown in the editor");
                check(!deltaTab.hasUnsavedChanges(), "an offered draft must never be applied by itself");
                check(delta.getContent().startsWith("decho delta"), "the saved snippet stays as it was");
                editor.draftAutosave().restoreOffered();
                check(deltaTab.hasUnsavedChanges(), "a restored draft marks the tab dirty");
                check(!editor.draftAutosave().isOffering(), "the banner goes after Restore");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(e -> SnippetEditDialog.UnsavedContentChoice.DISCARD);
                check(deltaTab.requestClose(), "DISCARD closes the tab");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(null);
                drafts.flush(5_000);
                check(drafts.load(delta.getId()).join().isEmpty(), "discarding the restored draft deletes it");
            })
            .then(50, "a draft of a never-saved snippet is offered by the next workspace", () -> {
                drafts.save(de.kortty.core.SnippetDraftStore.SnippetDraft.of("never-saved-1", 1_700_000_000_000L,
                    true, "orphan.sh", "bash", "", "", "", "echo orphan\n", ""));
                drafts.flush(5_000);
                SnippetWorkspaceDialog third = new SnippetWorkspaceDialog(manager, null);
                DialogHostTab thirdTab = DialogHostTab.host(otherTabs, null, third, null);
                thirdTab.getProperties().put("smoke.workspace", third);
                otherTabs.getProperties().put("smoke.thirdTab", thirdTab);
            })
            .then(400, "Restore opens the orphan draft in a new editor", () -> {
                DialogHostTab thirdTab = (DialogHostTab) otherTabs.getProperties().get("smoke.thirdTab");
                SnippetWorkspaceDialog third = (SnippetWorkspaceDialog) thirdTab.getProperties().get("smoke.workspace");
                check(third.orphanDraftBanner() != null, "the workspace must offer the unsaved new snippet");
                javafx.scene.control.Button restore = (javafx.scene.control.Button)
                    third.orphanDraftBanner().lookup("#" + SnippetDraftAutosave.RESTORE_ID);
                restore.fire();
                check(third.orphanDraftBanner() == null, "the banner goes after Restore");
                check(third.openEditorCount() == 1, "one new editor per orphan draft");
                SnippetEditDialog restored = third.editorTabs().getFirst().editor();
                check("orphan.sh".equals(restored.snippetNameProperty().get()), "the draft's name is restored");
                check(third.editorTabs().getFirst().hasUnsavedChanges(), "the restored new snippet is unsaved");
                drafts.flush(5_000);
                check(drafts.load("never-saved-1").join().isEmpty(), "the orphan's old draft file goes");
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(e -> SnippetEditDialog.UnsavedContentChoice.DISCARD);
                thirdTab.closeProgrammatically();
                SnippetWorkspaceDialog.setUnsavedPrompterForTesting(null);
                otherTabs.getTabs().remove(thirdTab);
            })
            .then(50, "save as new takes the stored analysis along", () -> {
                SnippetEditorTab alphaTab = other.editorTabs().stream()
                    .filter(t -> t.snippetId().equals(alpha.getId())).findFirst().orElseThrow();
                other.editorTabPane().getSelectionModel().select(alphaTab);
                check(analyses.cached(alpha.getId()) != null && !analyses.cached(alpha.getId()).isEmpty(),
                    "alpha's analysis must be loaded by its editor");
                field(alphaTab.editor(), "nameField", javafx.scene.control.TextField.class).setText("alpha-copy.sh");
                int[] asked = {-1};
                SnippetEditDialog.setAnalysisCopyPrompterForTesting(count -> {
                    asked[0] = count;
                    return true;
                });
                try {
                    other.saveActiveEditorAsNew();
                } finally {
                    SnippetEditDialog.setAnalysisCopyPrompterForTesting(null);
                }
                check(asked[0] == 1, "save as new must offer the one stored analysis, asked with " + asked[0]);
                Snippet copy = manager.getAllSnippets().stream()
                    .filter(candidate -> "alpha-copy.sh".equals(candidate.getName())).findFirst().orElseThrow();
                check(analyses.cached(copy.getId()) != null && !analyses.cached(copy.getId()).isEmpty(),
                    "the new snippet must get a copy of the analysis");
                check(!analyses.cached(alpha.getId()).isEmpty(), "the original keeps its analysis");
                check(other.editorTabs().stream().anyMatch(t -> t.snippetId().equals(copy.getId())),
                    "the copy replaces the original in the tab");
            })
            .then(50, "quick open finds a snippet by a fuzzy name and pins it", () -> {
                Event.fireEvent(workspace.getDialogPane(), shortcut(KeyCode.P));
                SnippetQuickOpenPopup popup = workspace.quickOpenPopup();
                check(popup != null && popup.isShowing(), "Shortcut+P must open quick open");
                check(popup.list().getItems().size() == manager.getAllSnippets().size(),
                    "an empty query lists every snippet");
                popup.field().setText("gma");
                check(!popup.list().getItems().isEmpty() && popup.list().getItems().getFirst() == gamma,
                    "\"gma\" must rank gamma first, got " + popup.list().getItems());
                popup.choose();
                check(!popup.isShowing(), "choosing closes quick open");
                SnippetEditorTab active = (SnippetEditorTab) workspace.editorTabPane().getSelectionModel().getSelectedItem();
                check(active != null && active.snippetId().equals(gamma.getId()), "the chosen snippet is pinned and active");
            })
            .then(50, "batch export: the library exports the selected snippets' analyses", () -> {
                var selection = workspace.library().table().getSelectionModel();
                selection.clearSelection();
                selection.select(alpha);
                selection.select(beta);
                selection.select(gamma);
                check(workspace.library().table().getContextMenu().getItems().stream()
                        .anyMatch(item -> SnippetLibraryPane.BATCH_EXPORT_ITEM_ID.equals(item.getId())),
                    "the library's context menu offers the batch export");
                SnippetAnalysisBatchExportDialog dialog = workspace.library().exportAnalysisReports();
                check(dialog != null, "a selection opens the batch export");
                workspace.getDialogPane().getProperties().put("smoke.batch", dialog);
            })
            .then(400, "batch export writes one combined HTML page and names the skipped snippet", () -> {
                SnippetAnalysisBatchExportDialog dialog =
                    (SnippetAnalysisBatchExportDialog) workspace.getDialogPane().getProperties().get("smoke.batch");
                check(dialog.isLoaded(), "the stored analyses must be looked up");
                Path target = isolatedHome.resolve("batch-export.html");
                AtomicReference<de.kortty.core.SnippetAnalysisBatchExport.Result> finished = new AtomicReference<>();
                dialog.setOnFinishedForTesting(finished::set);
                dialog.exportTo(target, de.kortty.core.SnippetAnalysisExportService.Format.HTML,
                    de.kortty.core.SnippetAnalysisBatchExport.Packaging.COMBINED, false);
                check(dialog.isRunning(), "the export runs in the background");
                workspace.getDialogPane().getProperties().put("smoke.batchResult", finished);
                workspace.getDialogPane().getProperties().put("smoke.batchTarget", target);
            })
            .then(1500, "batch export result", () -> {
                SnippetAnalysisBatchExportDialog dialog =
                    (SnippetAnalysisBatchExportDialog) workspace.getDialogPane().getProperties().get("smoke.batch");
                @SuppressWarnings("unchecked")
                AtomicReference<de.kortty.core.SnippetAnalysisBatchExport.Result> finished =
                    (AtomicReference<de.kortty.core.SnippetAnalysisBatchExport.Result>)
                        workspace.getDialogPane().getProperties().get("smoke.batchResult");
                Path target = (Path) workspace.getDialogPane().getProperties().get("smoke.batchTarget");
                check(!dialog.isRunning() && finished.get() != null, "the export must finish: " + dialog.resultText());
                check(finished.get().exported() == 2, "alpha and gamma have analyses, got " + finished.get().exported());
                check(finished.get().skipped().size() == 1
                        && finished.get().skipped().getFirst().snippetName().equals("beta.sh"),
                    "beta has no analysis and is skipped");
                String html;
                try {
                    html = Files.readString(target);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                check(html.contains("alpha.sh") && html.contains("gamma.sh") && html.contains("beta.sh"),
                    "the page lists both reports and the skipped snippet");
                check(!dialog.resultText().isBlank(), "the dialog reports the result");
                dialog.close();
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

    /** An analysis of {@code analysedContent} with two findings (SEC-1 and the dependency D1). */
    private static de.kortty.core.SnippetAnalysisRecord analysisOf(Snippet snippet, String id, String analysedContent) {
        de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis analysis =
            new de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis("Prints a word.",
                List.of(new de.kortty.core.SnippetAiResponseSupport.ScriptDependency(
                    "D1", "echo", "builtin", "output", "")),
                List.of(new de.kortty.core.SnippetAiResponseSupport.ScriptImprovement(
                    "SEC-1", "security", "low", "Quote the word", "", "", 1)));
        return de.kortty.core.SnippetAnalysisRecord.fromAnalysis(id, snippet.getId(), analysis,
            de.kortty.core.SnippetAnalysisRecord.Source.of(analysedContent, "bash", "en", "en", snippet.getName()),
            de.kortty.core.SnippetAnalysisRecord.Provenance.EMPTY,
            de.kortty.core.SnippetAnalysisRecord.Purpose.ANALYSIS, null, System.currentTimeMillis());
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
