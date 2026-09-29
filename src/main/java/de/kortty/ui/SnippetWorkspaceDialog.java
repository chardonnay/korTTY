package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Snippet Manager: one window (or one main-window tool tab) with the snippet library on the
 * left and the snippets being worked on as inner tabs on the right.
 *
 * <ul>
 *   <li>Selecting a row shows it in a single, reused read-only preview tab — no editor is built
 *       and no AI work starts while browsing.</li>
 *   <li>Double-click, Enter, Edit or typing into the preview pins the snippet: a full
 *       {@link SnippetEditDialog} whose pane is embedded in a {@link SnippetEditorTab}. Each
 *       snippet is open at most once across all workspaces ({@link SnippetEditorRegistry}).</li>
 *   <li>Shortcut+S saves the active editor, Shortcut+W closes the active inner tab (asking about
 *       unsaved changes), Esc never closes the workspace.</li>
 *   <li>Closing the workspace asks once for all unsaved editors: Save all / Discard all / Cancel.</li>
 * </ul>
 */
public final class SnippetWorkspaceDialog extends ThemeAwareDialog<Void> implements HostedCloseGuard {

    private static final Logger logger = LoggerFactory.getLogger(SnippetWorkspaceDialog.class);

    /** Tool-tab dedupe key (one workspace per main window in tab mode). */
    static final String TOOL_ID = "snippets";

    private static final KeyCombination SAVE_SHORTCUT = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination CLOSE_TAB_SHORTCUT = new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination TOGGLE_LIBRARY_SHORTCUT = new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN);
    /** Quick open; free in the main window (only Shortcut+Shift+P and Shortcut+Alt+P are taken there). */
    static final KeyCombination QUICK_OPEN_SHORTCUT = new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN);
    /** At most this many editor tabs are reopened from the last session (each is a Monaco editor). */
    static final int MAX_RESTORED_TABS = 12;
    /** The code keeps at least this width beside an opened analysis panel, else the library folds away. */
    static final double MIN_EDITOR_WIDTH_BESIDE_PANEL = 640;
    private static final double MIN_WINDOW_WIDTH = 980;
    private static final double MIN_WINDOW_HEIGHT = 560;

    /** Test seam: answers every unsaved-changes prompt of the workspace (single and bulk). */
    private static Function<SnippetEditDialog, SnippetEditDialog.UnsavedContentChoice> unsavedPrompter;

    private final SnippetManager snippetManager;
    /** The main window the workspace was opened from; {@code null} in render smokes (no AI, no insert). */
    private final MainWindow ownerWindow;
    private final SnippetLibraryPane library;
    private final TabPane editorTabPane = new TabPane();
    private final SnippetPreviewTab previewTab;
    private final Label emptyLabel = new Label(I18n.get("snippets.workspace.empty"));
    private final Label statusLabel = new Label();
    private final Button saveButton;
    private final Button saveAsNewButton;
    private final Button closeTabButton;
    private final SplitPane splitPane;
    /** Action bar + (banners) + inner tabs; banners are inserted below the action bar. */
    private final VBox editorArea;
    /** The "unsaved new snippets" banner, while shown. */
    private Region orphanDraftBanner;
    private boolean orphanDraftsChecked;
    private boolean sessionTabsRestored;
    /** Set while the last session's tabs are reopened: tab changes then are not remembered. */
    private boolean restoringTabs;
    /** The library was folded away for an analysis panel (not by the user); it comes back with the panel. */
    private boolean autoCollapsedLibrary;
    private boolean autoTogglingLibrary;
    /** The user toggled the library while an analysis panel was open: no more automatic folding. */
    private boolean libraryChosenManually;
    private SnippetQuickOpenPopup quickOpen;
    private final Button quickOpenButton = new Button("\u2315");
    /** Hides the library to give the editor the full width (focus mode); Shortcut+B. */
    private final ToggleButton libraryToggle = new ToggleButton("\u2630");
    /** The divider position to restore when the collapsed library comes back. */
    private double libraryDividerPosition;
    private final Consumer<SnippetManager.Change> changeListener = this::onSnippetsChanged;
    private final SnippetEditorEmbedding embedding = new SnippetEditorEmbedding() {
        @Override
        public SnippetManager snippetManager() {
            return snippetManager;
        }

        @Override
        public void snippetPersisted(SnippetEditDialog editor, Snippet saved, boolean created) {
            onEditorSaved(editor, saved, created);
        }

        @Override
        public void analysisPanelShown(SnippetEditDialog editor, double panelWidth) {
            onAnalysisPanelShown(editor, panelWidth);
        }

        @Override
        public void analysisPanelHidden(SnippetEditDialog editor) {
            onAnalysisPanelHidden();
        }
    };
    private boolean listenerRegistered;
    private volatile boolean externalRefreshScheduled;
    /** Set only right before an approved close, so an aborted close never leaves it behind. */
    private boolean closeApproved;
    private boolean tornDown;
    /** Set while the workspace itself moves the row selection / tab selection in sync. */
    private boolean syncingSelection;
    private Runnable onTornDown;

    /**
     * @param ownerWindow the main window this workspace belongs to; {@code null} is accepted (render
     *                    smokes): AI actions and inserting into a terminal are then unavailable
     */
    public SnippetWorkspaceDialog(SnippetManager snippetManager, MainWindow ownerWindow) {
        this.snippetManager = snippetManager;
        this.ownerWindow = ownerWindow;

        setTitle(I18n.get("snippets.title"));
        setResizable(true);
        initModality(Modality.NONE);

        library = new SnippetLibraryPane(snippetManager, new LibraryHost());
        previewTab = new SnippetPreviewTab(this::promotePreview, this::revealElsewhere);

        editorTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        editorTabPane.getStyleClass().add("snippet-workspace-tabs");
        editorTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            onTabSelected(newTab);
            rememberOpenTabs();
        });
        editorTabPane.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
            updateEmptyState();
            updateActionBar();
            updateTitle();
            rememberOpenTabs();
        });

        emptyLabel.setWrapText(true);
        emptyLabel.setStyle("-fx-opacity: 0.7;");
        emptyLabel.setMouseTransparent(true);
        StackPane editorStack = new StackPane(editorTabPane, emptyLabel);
        StackPane.setAlignment(emptyLabel, Pos.CENTER);
        VBox.setVgrow(editorStack, Priority.ALWAYS);

        saveButton = new Button("💾 " + I18n.get("dialog.save"));
        saveButton.setTooltip(new Tooltip(I18n.get("snippets.workspace.save.tooltip")));
        saveButton.setOnAction(e -> saveActiveEditor());
        saveAsNewButton = new Button(I18n.get("snippets.saveAsNew"));
        saveAsNewButton.setTooltip(new Tooltip(I18n.get("snippets.workspace.saveAsNew.tooltip")));
        saveAsNewButton.setOnAction(e -> saveActiveEditorAsNew());
        closeTabButton = new Button("✕ " + I18n.get("snippets.workspace.closeTab"));
        closeTabButton.setOnAction(e -> closeActiveTab());
        statusLabel.setStyle("-fx-opacity: 0.8;");
        statusLabel.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        libraryToggle.setSelected(true);
        libraryToggle.setTooltip(new Tooltip(I18n.get("snippets.workspace.toggleLibrary.tooltip")));
        libraryToggle.selectedProperty().addListener((obs, was, visible) -> {
            if (!autoTogglingLibrary) {
                autoCollapsedLibrary = false;
                libraryChosenManually = true;
            }
            setLibraryVisible(visible);
        });
        quickOpenButton.setId("snippet-workspace-quick-open");
        quickOpenButton.setTooltip(new Tooltip(I18n.get("snippets.workspace.quickOpen.tooltip")));
        quickOpenButton.setOnAction(e -> showQuickOpen());
        HBox actionBar = new HBox(8, libraryToggle, quickOpenButton, saveButton, saveAsNewButton, closeTabButton,
            spacer, statusLabel);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.setPadding(new Insets(8, 10, 6, 10));
        actionBar.getStyleClass().add("snippet-workspace-action-bar");

        editorArea = new VBox(0, actionBar, editorStack);
        editorArea.setMinWidth(0);

        splitPane = new SplitPane(library, editorArea);
        SplitPane.setResizableWithParent(library, false);
        // A window that gets narrower while an analysis panel is open may leave the code too little
        // room: check again once the new width is laid out.
        splitPane.widthProperty().addListener((obs, oldWidth, newWidth) ->
            Platform.runLater(this::refoldLibraryForAnalysisPanel));
        double divider = loadDividerPosition();
        libraryDividerPosition = divider;
        splitPane.setDividerPositions(divider);
        Platform.runLater(() -> splitPane.setDividerPositions(divider));

        getDialogPane().setContent(splitPane);
        getDialogPane().setPrefWidth(1280);
        getDialogPane().setPrefHeight(820);
        installGeometryPersistence();
        // The window X, Ctrl+Q and the outer tab's × need a cancel-type button to be allowed to
        // close; the button itself stays invisible and is NOT a cancel button, so Esc (which fires
        // the cancel button) never closes the workspace, and there is no default button for Enter.
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Node closeButton = getDialogPane().lookupButton(ButtonType.CLOSE);
        if (closeButton instanceof Button button) {
            button.setCancelButton(false);
            button.setDefaultButton(false);
            button.setVisible(false);
            button.setManaged(false);
            button.setDisable(true);
        }
        Node buttonBar = getDialogPane().lookup(".button-bar");
        if (buttonBar != null) {
            buttonBar.setVisible(false);
            buttonBar.setManaged(false);
            if (buttonBar instanceof Region region) {
                region.setMinHeight(0);
                region.setPrefHeight(0);
                region.setMaxHeight(0);
            }
        }
        setResultConverter(buttonType -> null);

        installShortcuts();
        setOnCloseRequest(event -> {
            if (closeApproved || tornDown) {
                return;
            }
            if (!confirmHostedClose()) {
                event.consume();
            }
        });
        addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            Window window = hostWindow();
            if (window instanceof Stage stage) {
                stage.setMinWidth(MIN_WINDOW_WIDTH);
                stage.setMinHeight(MIN_WINDOW_HEIGHT);
            }
            subscribe();
            Platform.runLater(library::focusSearch);
            checkOrphanDrafts();
            restoreSessionTabs();
        });
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> tearDown());

        updateEmptyState();
        updateActionBar();
    }

    @Override
    protected void onHostedAttached() {
        subscribe();
        Platform.runLater(library::focusSearch);
        checkOrphanDrafts();
        restoreSessionTabs();
    }

    // ---- public / package API -------------------------------------------------------------

    /** Focuses the library's search field (Shortcut+Shift+S on an open workspace). */
    public void focusSearch() {
        library.focusSearch();
    }

    /**
     * Shows the snippet with {@code snippetId}: pinned in an editor tab, or in the preview.
     * Unknown ids are ignored.
     */
    void openSnippetById(String snippetId, boolean pin) {
        Optional<Snippet> snippet = snippetManager.findById(snippetId);
        if (snippet.isEmpty()) {
            return;
        }
        library.selectWithoutPreview(snippetId);
        if (pin) {
            openSnippet(snippet.get(), null);
        } else {
            previewSnippet(snippet.get());
        }
    }

    /** Opens a new, empty snippet in a pinned editor tab. */
    void openNewSnippet() {
        pin(null, null, -1);
    }

    /** Closes the workspace without asking (the caller already confirmed via {@link #confirmHostedClose()}). */
    void closeWithoutPrompt() {
        closeApproved = true;
        closeDialogOrHostTab();
    }

    /** Runs once after the workspace was torn down (the main window forgets its instance). */
    void setOnTornDown(Runnable onTornDown) {
        this.onTornDown = onTornDown;
    }

    /**
     * Asks about everything that would be lost: running AI work (once), then unsaved editors — the
     * editor's own prompt for one, a single Save all / Discard all / Cancel for several. Saves when
     * asked to, never closes and keeps no approval state.
     */
    @Override
    public boolean confirmHostedClose() {
        List<SnippetEditorTab> tabs = editorTabs();
        if (tabs.stream().anyMatch(tab -> tab.editor().isAiWorkRunning()) && !confirmCloseWhileAiRunning()) {
            return false;
        }
        List<SnippetEditorTab> dirty = tabs.stream().filter(SnippetEditorTab::hasUnsavedChanges).toList();
        if (dirty.isEmpty()) {
            return true;
        }
        if (dirty.size() == 1) {
            SnippetEditorTab only = dirty.getFirst();
            editorTabPane.getSelectionModel().select(only);
            return only.editor().confirmCloseFromHost();
        }
        SnippetEditDialog.UnsavedContentChoice choice = unsavedPrompter != null
            ? unsavedPrompter.apply(null)
            : promptBulkUnsaved(dirty);
        if (choice == null || choice == SnippetEditDialog.UnsavedContentChoice.CANCEL) {
            return false;
        }
        if (choice == SnippetEditDialog.UnsavedContentChoice.SAVE) {
            for (SnippetEditorTab tab : dirty) {
                if (!tab.editor().saveFromHost()) {
                    editorTabPane.getSelectionModel().select(tab);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean needsCloseConfirmation() {
        return editorTabs().stream().anyMatch(tab -> tab.hasUnsavedChanges() || tab.editor().isAiWorkRunning());
    }

    /**
     * Test seam: answers the unsaved-changes prompts (a single editor's and the bulk one, which is
     * asked with a {@code null} editor) instead of showing alerts; {@code null} restores the alerts.
     */
    static void setUnsavedPrompterForTesting(
        Function<SnippetEditDialog, SnippetEditDialog.UnsavedContentChoice> prompter) {
        unsavedPrompter = prompter;
        SnippetEditDialog.setHostUnsavedPrompterForTesting(prompter);
    }

    List<SnippetEditorTab> editorTabs() {
        List<SnippetEditorTab> tabs = new ArrayList<>();
        for (Tab tab : editorTabPane.getTabs()) {
            if (tab instanceof SnippetEditorTab editorTab) {
                tabs.add(editorTab);
            }
        }
        return tabs;
    }

    int openEditorCount() {
        return editorTabs().size();
    }

    SnippetPreviewTab previewTab() {
        return previewTab;
    }

    TabPane editorTabPane() {
        return editorTabPane;
    }

    SnippetLibraryPane library() {
        return library;
    }

    boolean isLibraryVisible() {
        return splitPane.getItems().contains(library);
    }

    // ---- browsing, pinning, dedupe ----------------------------------------------------------

    private void previewSnippet(Snippet snippet) {
        if (snippet == null || snippet.getId() == null) {
            return;
        }
        SnippetEditorTabPolicy.Decision decision = SnippetEditorTabPolicy.onSingleClick(
            openTabs(), snippet.getId(), isOpenElsewhere(snippet.getId()));
        if (decision instanceof SnippetEditorTabPolicy.SelectExisting) {
            findEditorTab(snippet.getId()).ifPresent(this::selectTabQuietly);
        } else if (decision instanceof SnippetEditorTabPolicy.ShowInPreview show) {
            showPreview(snippet, show.openElsewhere());
        }
    }

    private void showPreview(Snippet snippet, boolean openElsewhere) {
        if (!editorTabPane.getTabs().contains(previewTab)) {
            editorTabPane.getTabs().add(previewTab);
        }
        previewTab.show(snippet, openElsewhere);
        selectTabQuietly(previewTab);
    }

    private void openSnippet(Snippet snippet, SnippetPreviewTab.PromoteRequest request) {
        if (snippet == null || snippet.getId() == null) {
            return;
        }
        String id = snippet.getId();
        SnippetEditorTabPolicy.Decision decision = SnippetEditorTabPolicy.onOpen(
            openTabs(), id, snippet.isPolicyManaged(), isOpenElsewhere(id));
        switch (decision) {
            case SnippetEditorTabPolicy.SelectExisting ignored -> findEditorTab(id).ifPresent(tab -> {
                editorTabPane.getSelectionModel().select(tab);
                tab.editor().focusEditor();
            });
            case SnippetEditorTabPolicy.RevealElsewhere ignored ->
                SnippetEditorRegistry.find(id).ifPresent(SnippetEditorRegistry.OpenEditor::reveal);
            case SnippetEditorTabPolicy.Refuse ignored -> showPreview(snippet, false);
            case SnippetEditorTabPolicy.Promote ignored -> pin(snippet, request,
                editorTabPane.getTabs().indexOf(previewTab));
            case SnippetEditorTabPolicy.OpenPinned ignored -> pin(snippet, request, -1);
            case SnippetEditorTabPolicy.ShowInPreview show -> showPreview(snippet, show.openElsewhere());
        }
    }

    private void promotePreview(SnippetPreviewTab.PromoteRequest request) {
        openSnippet(request.snippet(), request);
    }

    private void revealElsewhere(String snippetId) {
        SnippetEditorRegistry.find(snippetId)
            .filter(editor -> !ownsEditor(editor))
            .ifPresent(SnippetEditorRegistry.OpenEditor::reveal);
    }

    /**
     * Opens {@code snippet} (or a new snippet for {@code null}) in a pinned editor tab at
     * {@code index} ({@code -1} = append; the preview's index replaces the preview).
     */
    private void pin(Snippet snippet, SnippetPreviewTab.PromoteRequest request, int index) {
        SnippetEditDialog editor = new SnippetEditDialog(snippet, categoryNames(), createAiAssist(), embedding);
        Window host = hostWindow();
        if (host != null) {
            // Alerts and child windows of the editor belong to the window that shows the workspace;
            // the editor also resolves the live scene window first (tabs can be dragged away).
            editor.initOwner(host);
        }
        SnippetEditorTab tab = new SnippetEditorTab(editor, this::onEditorTabClosed, this::revealDialogOrHost);
        tab.setContextMenu(buildTabContextMenu(tab));
        if (!SnippetEditorRegistry.claim(editor.snippetId(), tab)) {
            // Lost a race with another workspace (cannot happen on one FX thread, kept defensive).
            tab.closeWithoutPrompt();
            SnippetEditorRegistry.find(editor.snippetId()).ifPresent(SnippetEditorRegistry.OpenEditor::reveal);
            return;
        }
        editor.unsavedChangesProperty().addListener((obs, was, is) -> {
            updateActionBar();
            updateTitle();
        });
        editor.savableProperty().addListener((obs, was, is) -> updateActionBar());

        List<Tab> tabs = editorTabPane.getTabs();
        boolean replacePreview = index >= 0 && index < tabs.size() && tabs.get(index) == previewTab;
        if (replacePreview) {
            tabs.set(index, tab);
            previewTab.show(null, false);
        } else {
            tabs.add(tab);
        }
        editorTabPane.getSelectionModel().select(tab);
        if (request != null && request.typed() != null) {
            editor.applyInitialKeystroke(request.caret(), request.typed());
        }
        Platform.runLater(editor::focusEditor);
    }

    private SnippetEditDialog.AiAssist createAiAssist() {
        // Fresh per editor: runtime options (forced skills, code-text language) stay per snippet.
        return SnippetAiAssistFactory.create(this::resolveMainWindow);
    }

    private ContextMenu buildTabContextMenu(SnippetEditorTab tab) {
        MenuItem close = new MenuItem(I18n.get("snippets.workspace.tab.close"));
        close.setOnAction(e -> tab.requestClose());
        MenuItem closeOthers = new MenuItem(I18n.get("snippets.workspace.tab.closeOthers"));
        closeOthers.setOnAction(e -> closeTabs(tab));
        MenuItem closeAll = new MenuItem(I18n.get("snippets.workspace.tab.closeAll"));
        closeAll.setOnAction(e -> closeTabs(null));
        MenuItem reveal = new MenuItem(I18n.get("snippets.workspace.tab.revealInList"));
        reveal.setOnAction(e -> library.selectWithoutPreview(tab.snippetId()));
        return new ContextMenu(close, closeOthers, closeAll, new SeparatorMenuItem(), reveal);
    }

    /** Closes every inner tab except {@code keep}, asking per editor; stops at the first Cancel. */
    private void closeTabs(Tab keep) {
        for (Tab tab : new ArrayList<>(editorTabPane.getTabs())) {
            if (tab == keep) {
                continue;
            }
            if (tab instanceof SnippetEditorTab editorTab) {
                if (!editorTab.requestClose()) {
                    return;
                }
            } else if (tab == previewTab) {
                editorTabPane.getTabs().remove(previewTab);
            }
        }
    }

    private void onEditorTabClosed(SnippetEditorTab tab) {
        SnippetEditorRegistry.release(tab);
        updateActionBar();
        updateTitle();
    }

    private List<SnippetEditorTabPolicy.OpenTab> openTabs() {
        List<SnippetEditorTabPolicy.OpenTab> open = new ArrayList<>();
        for (SnippetEditorTab tab : editorTabs()) {
            open.add(new SnippetEditorTabPolicy.OpenTab(tab.snippetId(), SnippetEditorTabPolicy.Kind.PINNED));
        }
        Snippet shown = previewTab.shownSnippet();
        if (shown != null && shown.getId() != null && editorTabPane.getTabs().contains(previewTab)) {
            open.add(new SnippetEditorTabPolicy.OpenTab(shown.getId(), SnippetEditorTabPolicy.Kind.PREVIEW));
        }
        return open;
    }

    private Optional<SnippetEditorTab> findEditorTab(String snippetId) {
        return editorTabs().stream().filter(tab -> snippetId.equals(tab.snippetId())).findFirst();
    }

    private boolean ownsEditor(SnippetEditorRegistry.OpenEditor editor) {
        return editor instanceof SnippetEditorTab tab && editorTabPane.getTabs().contains(tab);
    }

    /** Another workspace (another main window) already edits {@code snippetId}. */
    private boolean isOpenElsewhere(String snippetId) {
        return SnippetEditorRegistry.find(snippetId).filter(editor -> !ownsEditor(editor)).isPresent();
    }

    private void selectTabQuietly(Tab tab) {
        syncingSelection = true;
        try {
            editorTabPane.getSelectionModel().select(tab);
        } finally {
            syncingSelection = false;
        }
    }

    private void onTabSelected(Tab tab) {
        updateActionBar();
        if (tab instanceof SnippetEditorTab) {
            Platform.runLater(this::refoldLibraryForAnalysisPanel);
        }
        if (syncingSelection || tab == null) {
            return;
        }
        // Follow the active tab in the list without re-previewing.
        String id = null;
        if (tab instanceof SnippetEditorTab editorTab && editorTab.editor().persistedSnippet() != null) {
            id = editorTab.snippetId();
        } else if (tab == previewTab && previewTab.shownSnippet() != null) {
            id = previewTab.shownSnippet().getId();
        }
        if (id != null) {
            library.selectWithoutPreview(id);
        }
    }

    // ---- actions ------------------------------------------------------------------------------

    private SnippetEditorTab activeEditorTab() {
        return editorTabPane.getSelectionModel().getSelectedItem() instanceof SnippetEditorTab tab ? tab : null;
    }

    /** Shortcut+S / Save: saves the active editor when it has unsaved changes. */
    boolean saveActiveEditor() {
        SnippetEditorTab tab = activeEditorTab();
        if (tab == null || !tab.hasUnsavedChanges()) {
            return false;
        }
        return tab.editor().saveFromHost();
    }

    void saveActiveEditorAsNew() {
        SnippetEditorTab tab = activeEditorTab();
        if (tab == null) {
            return;
        }
        int index = editorTabPane.getTabs().indexOf(tab);
        String originalId = tab.snippetId();
        Snippet copy = tab.editor().saveAsNewFromHost();
        if (copy == null) {
            return;
        }
        tab.editor().offerAnalysisCopy(originalId, copy);
        library.refresh(true);
        library.refreshCategoryFilter();
        // The copy took the edits; the original keeps its saved state and makes room for the copy.
        tab.closeWithoutPrompt();
        pin(copy, null, -1);
        SnippetEditorTab copyTab = activeEditorTab();
        if (copyTab != null && index >= 0 && index < editorTabPane.getTabs().size()) {
            editorTabPane.getTabs().remove(copyTab);
            editorTabPane.getTabs().add(index, copyTab);
            editorTabPane.getSelectionModel().select(copyTab);
        }
        library.selectWithoutPreview(copy.getId());
        setStatus(I18n.get("snippets.workspace.saved", copy.getName()));
    }

    /** Shortcut+W / Close tab: closes the active inner tab (the editor asks about unsaved changes). */
    private void closeActiveTab() {
        Tab tab = editorTabPane.getSelectionModel().getSelectedItem();
        if (tab instanceof SnippetEditorTab editorTab) {
            editorTab.requestClose();
        } else if (tab == previewTab) {
            editorTabPane.getTabs().remove(previewTab);
        }
    }

    private void onEditorSaved(SnippetEditDialog editor, Snippet saved, boolean created) {
        findEditorTab(editor.snippetId()).ifPresent(tab -> SnippetEditorRegistry.claim(saved.getId(), tab));
        library.refresh(created);
        library.refreshCategoryFilter();
        library.selectWithoutPreview(saved.getId());
        List<String> categories = categoryNames();
        for (SnippetEditorTab tab : editorTabs()) {
            if (tab.editor() != editor) {
                tab.editor().updateCategoryChoices(categories);
            }
        }
        updateActionBar();
        updateTitle();
        setStatus(I18n.get("snippets.workspace.saved", saved.getName()));
    }

    private void installShortcuts() {
        // A filter on the workspace pane sees keys before the embedded editors and before the
        // window's menu accelerators (those only run for unconsumed events).
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (SAVE_SHORTCUT.match(event)) {
                if (activeEditorTab() != null) {
                    event.consume();
                    saveActiveEditor();
                }
            } else if (TOGGLE_LIBRARY_SHORTCUT.match(event)) {
                event.consume();
                libraryToggle.setSelected(!libraryToggle.isSelected());
            } else if (QUICK_OPEN_SHORTCUT.match(event)) {
                event.consume();
                showQuickOpen();
            } else if (CLOSE_TAB_SHORTCUT.match(event)) {
                if (editorTabPane.getSelectionModel().getSelectedItem() != null) {
                    event.consume();
                    closeActiveTab();
                } else if (!isHostedInTab()) {
                    event.consume();
                    close();
                }
                // Tab mode without inner tabs: let the main window close the workspace tab.
            }
        });
        // The dialog window closes on an unconsumed Esc. A handler (not a filter) lets the focused
        // control use Esc first — clearing the search, closing Monaco's suggest list — and only
        // stops what would bubble up to the window.
        getDialogPane().addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
            }
        });
    }

    /** Collapses or restores the library column, keeping its width for the way back. */
    private void setLibraryVisible(boolean visible) {
        boolean shown = splitPane.getItems().contains(library);
        if (visible && !shown) {
            splitPane.getItems().addFirst(library);
            splitPane.setDividerPositions(libraryDividerPosition);
        } else if (!visible && shown) {
            if (!splitPane.getDividers().isEmpty()) {
                libraryDividerPosition = splitPane.getDividers().getFirst().getPosition();
            }
            splitPane.getItems().remove(library);
        }
        if (libraryToggle.isSelected() != visible) {
            libraryToggle.setSelected(visible);
        }
    }

    // ---- quick open ------------------------------------------------------------------------------

    /** Shortcut+P: fuzzy search over snippet names and tags; the chosen one opens in an editor tab. */
    void showQuickOpen() {
        if (tornDown) {
            return;
        }
        if (quickOpen == null) {
            quickOpen = new SnippetQuickOpenPopup(
                () -> new ArrayList<>(snippetManager.getAllSnippets()),
                snippet -> {
                    library.selectWithoutPreview(snippet.getId());
                    openSnippet(snippet, null);
                });
        }
        quickOpen.show(editorArea);
    }

    SnippetQuickOpenPopup quickOpenPopup() {
        return quickOpen;
    }

    // ---- last session's tabs -----------------------------------------------------------------------

    /**
     * Reopens the editor tabs (and the active one) of the last session once per workspace, after the
     * caller had the chance to open a snippet of its own (which then stays selected). Deleted,
     * admin-managed and elsewhere-open snippets are skipped.
     */
    private void restoreSessionTabs() {
        if (sessionTabsRestored || tornDown) {
            return;
        }
        sessionTabsRestored = true;
        GlobalSettings settings = currentSettings();
        if (settings == null) {
            return;
        }
        List<String> stored = List.copyOf(settings.getSnippetWorkspaceOpenTabs());
        String active = settings.getSnippetWorkspaceActiveTab();
        if (stored.isEmpty()) {
            return;
        }
        Platform.runLater(() -> {
            if (tornDown) {
                return;
            }
            Tab selectedBefore = editorTabPane.getSelectionModel().getSelectedItem();
            boolean callerOpenedSomething = !editorTabs().isEmpty();
            List<String> ids = restorableTabIds(stored, id -> snippetManager.findById(id)
                .filter(snippet -> !snippet.isPolicyManaged()).isPresent()
                && findEditorTab(id).isEmpty() && !isOpenElsewhere(id), MAX_RESTORED_TABS);
            restoringTabs = true;
            try {
                for (String id : ids) {
                    snippetManager.findById(id).ifPresent(snippet -> pin(snippet, null, -1));
                }
                if (callerOpenedSomething && selectedBefore != null) {
                    editorTabPane.getSelectionModel().select(selectedBefore);
                } else if (active != null) {
                    findEditorTab(active).ifPresent(tab -> editorTabPane.getSelectionModel().select(tab));
                }
            } finally {
                restoringTabs = false;
            }
            rememberOpenTabs();
        });
    }

    /** The stored ids that can be reopened: in order, without blanks and duplicates, at most {@code max}. */
    static List<String> restorableTabIds(List<String> stored, java.util.function.Predicate<String> restorable, int max) {
        List<String> ids = new ArrayList<>();
        if (stored == null) {
            return ids;
        }
        for (String id : stored) {
            if (ids.size() >= max) {
                break;
            }
            if (id != null && !id.isBlank() && !ids.contains(id) && restorable.test(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Remembers the saved snippets open as editor tabs and the active one (not while tearing down). */
    private void rememberOpenTabs() {
        if (tornDown || restoringTabs) {
            return;
        }
        GlobalSettings settings = currentSettings();
        if (settings == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (SnippetEditorTab tab : editorTabs()) {
            Snippet persisted = tab.editor().persistedSnippet();
            if (persisted != null && persisted.getId() != null) {
                ids.add(persisted.getId());
            }
        }
        SnippetEditorTab active = activeEditorTab();
        String activeId = active != null && active.editor().persistedSnippet() != null ? active.snippetId() : null;
        if (ids.equals(settings.getSnippetWorkspaceOpenTabs())
                && java.util.Objects.equals(activeId, settings.getSnippetWorkspaceActiveTab())) {
            return;
        }
        settings.setSnippetWorkspaceOpenTabs(ids);
        settings.setSnippetWorkspaceActiveTab(activeId);
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            if (app != null && app.getGlobalSettingsManager() != null) {
                app.getGlobalSettingsManager().scheduleSave();
            }
        } catch (Exception e) {
            logger.debug("Could not remember the snippet workspace tabs", e);
        }
    }

    // ---- library and analysis panel ------------------------------------------------------------------

    /** An editor opened its analysis panel: fold the library away when the code would get too narrow. */
    private void onAnalysisPanelShown(SnippetEditDialog editor, double panelWidth) {
        // A panel opened anew: an earlier manual library choice was about the previous one.
        libraryChosenManually = false;
        foldLibraryIfCodeTooNarrow(editor, panelWidth);
    }

    /** The window was resized or another tab came up: re-check the active editor's panel. */
    private void refoldLibraryForAnalysisPanel() {
        SnippetEditorTab tab = activeEditorTab();
        if (tab == null || !tab.editor().isAnalysisSidePanelShown()) {
            return;
        }
        foldLibraryIfCodeTooNarrow(tab.editor(), tab.editor().analysisSidePanelPreferredWidth());
    }

    /**
     * Folds the library away when the code of {@code editor} would keep less than
     * {@link #MIN_EDITOR_WIDTH_BESIDE_PANEL} beside its panel — measured on the laid-out widths. A
     * library the user toggled by hand while the panel is open stays as it is.
     */
    private void foldLibraryIfCodeTooNarrow(SnippetEditDialog editor, double panelWidth) {
        if (tornDown || !isLibraryVisible() || libraryChosenManually || editor == null) {
            return;
        }
        // Measure the current geometry, not the one of the last pulse (a window resized in the
        // same event would otherwise be judged by its old width).
        if (splitPane.getScene() != null && splitPane.getScene().getRoot() != null) {
            splitPane.getScene().getRoot().layout();
        }
        double workbenchWidth = editor.workbenchWidth();
        if (workbenchWidth <= 0) {
            workbenchWidth = editorArea.getWidth();
        }
        if (shouldAutoCollapseLibrary(workbenchWidth, panelWidth)) {
            autoTogglingLibrary = true;
            try {
                setLibraryVisible(false);
            } finally {
                autoTogglingLibrary = false;
            }
            autoCollapsedLibrary = true;
            setStatus(I18n.get("snippets.workspace.library.autoCollapsed"));
        }
    }

    /** The panel closed again: a library folded away for it comes back (a manual toggle is kept). */
    private void onAnalysisPanelHidden() {
        libraryChosenManually = false;
        if (tornDown || !autoCollapsedLibrary || isLibraryVisible()) {
            return;
        }
        autoTogglingLibrary = true;
        try {
            setLibraryVisible(true);
        } finally {
            autoTogglingLibrary = false;
        }
        autoCollapsedLibrary = false;
    }

    /**
     * Whether the library should fold away for an analysis panel that asks for {@code panelWidth}:
     * in an editor row {@code workbenchWidth} wide (what the library leaves over) the code would
     * keep less than {@link #MIN_EDITOR_WIDTH_BESIDE_PANEL}. An unknown (not laid out) width never
     * collapses anything.
     */
    static boolean shouldAutoCollapseLibrary(double workbenchWidth, double panelWidth) {
        if (workbenchWidth <= 0 || panelWidth <= 0) {
            return false;
        }
        return SnippetEditorWorkbench.codeWidth(workbenchWidth, panelWidth, SnippetAnalysisController.MIN_PANEL_WIDTH)
            < MIN_EDITOR_WIDTH_BESIDE_PANEL;
    }

    boolean isLibraryAutoCollapsed() {
        return autoCollapsedLibrary;
    }

    // ---- drafts of never-saved snippets -----------------------------------------------------------

    /**
     * Once per workspace: drafts whose snippet does not exist (a new snippet that was never saved
     * before a crash) and that no open editor owns are offered in a banner — never restored by
     * themselves. Drafts of existing snippets are offered by their editor when it opens.
     */
    private void checkOrphanDrafts() {
        if (orphanDraftsChecked || tornDown) {
            return;
        }
        orphanDraftsChecked = true;
        de.kortty.core.SnippetDraftStore.shared().loadAll().whenComplete((drafts, error) -> Platform.runLater(() -> {
            if (error != null || drafts == null || tornDown) {
                return;
            }
            List<de.kortty.core.SnippetDraftStore.SnippetDraft> orphans =
                orphanDrafts(drafts, id -> snippetManager.findById(id).isPresent(), SnippetDraftAutosave.liveIds());
            if (!orphans.isEmpty()) {
                showOrphanDraftBanner(orphans);
            }
        }));
    }

    /** The drafts whose snippet does not exist and that no open editor owns, newest first. */
    static List<de.kortty.core.SnippetDraftStore.SnippetDraft> orphanDrafts(
            List<de.kortty.core.SnippetDraftStore.SnippetDraft> drafts,
            java.util.function.Predicate<String> snippetExists, java.util.Set<String> liveIds) {
        if (drafts == null) {
            return List.of();
        }
        return drafts.stream()
            .filter(draft -> draft != null && !draft.snippetId().isBlank())
            .filter(draft -> !snippetExists.test(draft.snippetId()))
            .filter(draft -> liveIds == null || !liveIds.contains(draft.snippetId()))
            .sorted(Comparator.comparingLong(de.kortty.core.SnippetDraftStore.SnippetDraft::savedAt).reversed())
            .toList();
    }

    private void showOrphanDraftBanner(List<de.kortty.core.SnippetDraftStore.SnippetDraft> orphans) {
        hideOrphanDraftBanner();
        Label label = new Label(I18n.get("snippets.draft.orphans.banner", orphans.size(),
            SnippetDraftAutosave.formatTime(orphans.getFirst().savedAt())));
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(label, Priority.ALWAYS);
        Button restore = new Button(I18n.get("snippets.draft.restore"));
        restore.setId(SnippetDraftAutosave.RESTORE_ID);
        restore.setOnAction(event -> restoreOrphanDrafts(orphans));
        Button discard = new Button(I18n.get("snippets.draft.discard"));
        discard.setId(SnippetDraftAutosave.DISCARD_ID);
        discard.setOnAction(event -> {
            hideOrphanDraftBanner();
            orphans.forEach(draft -> de.kortty.core.SnippetDraftStore.shared().delete(draft.snippetId()));
        });
        HBox row = new HBox(8, label, restore, discard);
        row.setId("snippet-workspace-draft-banner");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-background-color: rgba(245,158,11,0.14); -fx-border-color: rgba(245,158,11,0.55);"
            + " -fx-border-radius: 6; -fx-background-radius: 6;");
        VBox.setMargin(row, new Insets(0, 10, 6, 10));
        editorArea.getChildren().add(1, row);
        orphanDraftBanner = row;
    }

    private void hideOrphanDraftBanner() {
        if (orphanDraftBanner != null) {
            editorArea.getChildren().remove(orphanDraftBanner);
            orphanDraftBanner = null;
        }
    }

    /** Opens one new editor per draft, filled from it; the old draft file goes (the editor keeps its own). */
    private void restoreOrphanDrafts(List<de.kortty.core.SnippetDraftStore.SnippetDraft> orphans) {
        hideOrphanDraftBanner();
        for (de.kortty.core.SnippetDraftStore.SnippetDraft draft : orphans) {
            pin(null, null, -1);
            SnippetEditorTab tab = activeEditorTab();
            if (tab != null) {
                tab.editor().restoreDraft(draft);
            }
            de.kortty.core.SnippetDraftStore.shared().delete(draft.snippetId());
        }
    }

    /** The draft banner of the workspace (tests). */
    Region orphanDraftBanner() {
        return orphanDraftBanner;
    }

    // ---- close & teardown -----------------------------------------------------------------------

    private SnippetEditDialog.UnsavedContentChoice promptBulkUnsaved(List<SnippetEditorTab> dirty) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("snippets.title"));
        alert.setHeaderText(I18n.get("snippets.workspace.unsaved.header", dirty.size()));
        String names = dirty.stream()
            .map(tab -> {
                String name = tab.editor().snippetNameProperty().get();
                return "• " + (name != null && !name.isBlank() ? name.trim() : I18n.get("snippets.workspace.untitled"));
            })
            .collect(Collectors.joining("\n"));
        alert.setContentText(I18n.get("snippets.workspace.unsaved.content") + "\n\n" + names);
        ButtonType saveAll = new ButtonType(I18n.get("snippets.workspace.unsaved.saveAll"), ButtonBar.ButtonData.YES);
        ButtonType discardAll = new ButtonType(I18n.get("snippets.workspace.unsaved.discardAll"), ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType(I18n.get("editor.close.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(saveAll, discardAll, cancel);
        initAlertOwner(alert);
        ButtonType answer = alert.showAndWait().orElse(cancel);
        if (answer == saveAll) {
            return SnippetEditDialog.UnsavedContentChoice.SAVE;
        }
        return answer == discardAll
            ? SnippetEditDialog.UnsavedContentChoice.DISCARD
            : SnippetEditDialog.UnsavedContentChoice.CANCEL;
    }

    private boolean confirmCloseWhileAiRunning() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("snippets.title"));
        alert.setHeaderText(I18n.get("snippets.workspace.close.aiRunning.header"));
        alert.setContentText(I18n.get("snippets.workspace.close.aiRunning.content"));
        initAlertOwner(alert);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void initAlertOwner(Alert alert) {
        Window owner = hostWindow();
        if (owner != null) {
            alert.initOwner(owner);
            alert.initModality(Modality.WINDOW_MODAL);
        }
    }

    /** Runs exactly once, on {@code DIALOG_HIDDEN} (window closed, host tab closed or disposed). */
    private void tearDown() {
        if (tornDown) {
            return;
        }
        tornDown = true;
        if (quickOpen != null) {
            quickOpen.hide();
        }
        unsubscribe();
        persistGeometry();
        persistDividerPosition();
        library.dispose();
        // Each editor fires its own DIALOG_HIDDEN: AI work cancelled, Monaco disposed, registry released.
        for (SnippetEditorTab tab : editorTabs()) {
            tab.closeWithoutPrompt();
        }
        editorTabPane.getTabs().clear();
        previewTab.dispose();
        if (onTornDown != null) {
            onTornDown.run();
        }
    }

    // ---- window geometry ------------------------------------------------------------------------

    /**
     * Remembers where the user put the workspace window and how large they made it, under the
     * Snippet Manager's own setting (so a size stored by the old manager window carries over).
     * Written again shortly after every move or resize, not only on close, so quitting korTTY
     * or a crash keeps it too. A hosted workspace shares the main window and stores nothing.
     */
    private void installGeometryPersistence() {
        // Marks the geometry as handled here, which turns the class-keyed automatic persistence off.
        // Its DIALOG_SHOWN handler also starts the live tracking (which ignores a maximized or
        // minimized window, so the last normal bounds are what a later close stores).
        DialogGeometrySupport.restore(this, (WindowGeometry) null);
        addEventHandler(DialogEvent.DIALOG_SHOWING, event -> {
            WindowGeometry stored = storedGeometry();
            if (stored != null && !isHostedInTab() && DialogGeometrySupport.uiFontScaleMatchesStoredGeometry()) {
                // The window is sized from the dialog pane's preferred size when it is mapped, and
                // that overrides Dialog.setWidth/setHeight (the width always came back as the
                // designed 1280): the stored size therefore has to become the preferred size.
                getDialogPane().setPrefWidth(stored.getWidth());
                getDialogPane().setPrefHeight(stored.getHeight());
            }
        });
        addEventHandler(DialogEvent.DIALOG_SHOWN, event -> {
            WindowGeometry stored = storedGeometry();
            if (!(hostWindow() instanceof Stage stage) || isHostedInTab()) {
                return;
            }
            if (stored != null) {
                // Belt and braces after the window exists: JavaFX centres the dialog over its owner
                // only after SHOWING, so the position can only be applied here.
                if (DialogGeometrySupport.uiFontScaleMatchesStoredGeometry()) {
                    stage.setWidth(stored.getWidth());
                    stage.setHeight(stored.getHeight());
                }
                stage.setX(stored.getX());
                stage.setY(stored.getY());
            }
            geometrySaveDelay.setOnFinished(done -> persistGeometry());
            javafx.beans.InvalidationListener changed = obs -> {
                if (!tornDown) {
                    geometrySaveDelay.playFromStart();
                }
            };
            stage.xProperty().addListener(changed);
            stage.yProperty().addListener(changed);
            stage.widthProperty().addListener(changed);
            stage.heightProperty().addListener(changed);
        });
    }

    private final javafx.animation.PauseTransition geometrySaveDelay =
        new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));

    private static WindowGeometry storedGeometry() {
        GlobalSettings settings = currentSettings();
        return settings != null
            ? usableStoredGeometry(settings.getSnippetManagerGeometry(), DialogGeometrySupport.visualScreenBounds())
            : null;
    }

    /**
     * The stored geometry when it is worth restoring: reachable on one of the attached screens
     * (see {@link DialogGeometrySupport#sanitize}) and not below the workspace's own minimum,
     * which only a corrupt or foreign value can be. {@code null} lets the window open at its
     * designed size, centred over its owner.
     */
    static WindowGeometry usableStoredGeometry(WindowGeometry stored, List<javafx.geometry.Rectangle2D> screens) {
        WindowGeometry usable = DialogGeometrySupport.sanitize(stored, screens);
        if (usable == null || usable.getWidth() < MIN_WINDOW_WIDTH || usable.getHeight() < MIN_WINDOW_HEIGHT) {
            return null;
        }
        return usable;
    }

    private void persistGeometry() {
        geometrySaveDelay.stop();
        if (isHostedInTab()) {
            return;
        }
        DialogGeometrySupport.persist(this, (settings, geometry) -> settings.setSnippetManagerGeometry(geometry));
    }

    // ---- change events --------------------------------------------------------------------------

    private void subscribe() {
        if (!listenerRegistered && !tornDown) {
            snippetManager.addChangeListener(changeListener);
            listenerRegistered = true;
        }
    }

    private void unsubscribe() {
        if (listenerRegistered) {
            snippetManager.removeChangeListener(changeListener);
            listenerRegistered = false;
        }
    }

    /**
     * Runs on the saver's thread. Coalesces bursts into one FX refresh that keeps the row order,
     * selection and scroll position, refreshes every editor's category choices and re-renders the
     * preview (saves elsewhere mutate the shared Snippet objects in place).
     */
    private void onSnippetsChanged(SnippetManager.Change change) {
        if (externalRefreshScheduled) {
            return;
        }
        externalRefreshScheduled = true;
        Platform.runLater(() -> {
            externalRefreshScheduled = false;
            if (tornDown || !listenerRegistered) {
                return;
            }
            library.refresh(false);
            library.refreshCategoryFilter();
            List<String> categories = categoryNames();
            for (SnippetEditorTab tab : editorTabs()) {
                tab.editor().updateCategoryChoices(categories);
            }
            Snippet shown = previewTab.shownSnippet();
            if (shown != null) {
                Optional<Snippet> fresh = snippetManager.findById(shown.getId());
                if (fresh.isEmpty()) {
                    previewTab.clearIfShowing(shown.getId());
                } else {
                    previewTab.reloadIfShowing(fresh.get(), isOpenElsewhere(shown.getId()));
                }
            }
        });
    }

    // ---- state & helpers ------------------------------------------------------------------------

    private void updateEmptyState() {
        boolean empty = editorTabPane.getTabs().isEmpty();
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
    }

    private void updateActionBar() {
        SnippetEditorTab tab = activeEditorTab();
        boolean hasEditor = tab != null;
        boolean savable = hasEditor && tab.editor().savableProperty().get();
        saveButton.setDisable(!hasEditor || !savable || !tab.editor().unsavedChangesProperty().get());
        saveAsNewButton.setDisable(!hasEditor || !savable || !tab.editor().canSaveAsNew());
        closeTabButton.setDisable(editorTabPane.getSelectionModel().getSelectedItem() == null);
    }

    private void updateTitle() {
        boolean anyDirty = editorTabs().stream().anyMatch(tab -> tab.editor().unsavedChangesProperty().get());
        String base = I18n.get("snippets.title");
        setTitle(anyDirty ? base + " •" : base);
    }

    private void setStatus(String message) {
        statusLabel.setText(message != null ? message : "");
    }

    private List<String> categoryNames() {
        return snippetManager.getAllCategories().stream()
            .sorted(Comparator.comparingInt(SnippetCategory::getSortOrder))
            .map(SnippetCategory::getName)
            .collect(Collectors.toList());
    }

    /** The window currently showing the workspace: its own stage, or the main window hosting its tab. */
    private Window hostWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    /**
     * The main window the workspace belongs to right now: the one hosting its tab (tabs can be
     * dragged between windows), the owner of its own window, or the one it was opened from.
     */
    private MainWindow resolveMainWindow() {
        Window window = hostWindow();
        MainWindow mainWindow = MainWindow.findByStage(window);
        if (mainWindow == null && window instanceof Stage stage) {
            mainWindow = MainWindow.findByStage(stage.getOwner());
        }
        return mainWindow != null ? mainWindow : ownerWindow;
    }

    private double loadDividerPosition() {
        GlobalSettings settings = currentSettings();
        return settings != null ? settings.getSnippetWorkspaceLibraryDividerPosition() : 0.28;
    }

    private void persistDividerPosition() {
        double position = splitPane.getItems().contains(library) && !splitPane.getDividers().isEmpty()
            ? splitPane.getDividers().getFirst().getPosition()
            : libraryDividerPosition;
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            if (app == null || app.getGlobalSettingsManager() == null) {
                return;
            }
            var manager = app.getGlobalSettingsManager();
            manager.getSettings().setSnippetWorkspaceLibraryDividerPosition(position);
            manager.scheduleSave();
        } catch (Exception e) {
            logger.debug("Could not persist the snippet workspace divider", e);
        }
    }

    private static GlobalSettings currentSettings() {
        try {
            KorTTYApplication app = KorTTYApplication.getInstance();
            return app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings()
                : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The library's view of the workspace. */
    private final class LibraryHost implements SnippetLibraryPane.Host {
        @Override
        public void previewRequested(Snippet snippet) {
            previewSnippet(snippet);
        }

        @Override
        public void openRequested(Snippet snippet) {
            openSnippet(snippet, null);
        }

        @Override
        public void newRequested() {
            openNewSnippet();
        }

        @Override
        public Window ownerWindow() {
            return hostWindow();
        }

        @Override
        public MainWindow mainWindow() {
            return resolveMainWindow();
        }

        @Override
        public boolean beforeDelete(List<Snippet> snippets) {
            List<SnippetEditorRegistry.OpenEditor> clean = new ArrayList<>();
            for (Snippet snippet : snippets) {
                Optional<SnippetEditorRegistry.OpenEditor> open = SnippetEditorRegistry.find(snippet.getId());
                if (open.isEmpty()) {
                    continue;
                }
                if (open.get().hasUnsavedChanges()) {
                    open.get().reveal();
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    DialogThemeHelper.applyTheme(alert);
                    alert.setTitle(I18n.get("snippets.title"));
                    alert.setHeaderText(null);
                    alert.setContentText(I18n.get("snippets.workspace.delete.openDirty", snippet.getName()));
                    initAlertOwner(alert);
                    alert.showAndWait();
                    return false;
                }
                clean.add(open.get());
            }
            clean.forEach(SnippetEditorRegistry.OpenEditor::closeWithoutPrompt);
            snippets.forEach(snippet -> previewTab.clearIfShowing(snippet.getId()));
            return true;
        }
    }
}
