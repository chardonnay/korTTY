package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SessionJournalExportService;
import de.kortty.core.SessionJournalHtmlRenderer;
import de.kortty.core.SessionJournalLogEntry;
import de.kortty.core.SessionJournalService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalReplacement;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * The journal page view extracted from {@link SessionJournalViewerDialog}: the generated
 * journal.html in a WebView (loaded from a file: URL so relative screenshots work) with the
 * JS bridge, context-menu forwarding, marker/annotate/replace/export actions, and — in
 * {@link Mode#FULL} — the toolbar and edit mode. {@link Mode#COMPACT} builds neither toolbar
 * nor edit table and is meant for the docked live panel, whose host supplies the buttons.
 *
 * <p>Reusable as a plain {@code BorderPane}: the dialog wraps it, the docked panel embeds it.
 * Child dialogs resolve their owner from the current scene, falling back to the main window's
 * stage while the pane is not attached.</p>
 */
public class SessionJournalViewerPane extends BorderPane {

    public enum Mode { FULL, COMPACT }

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalViewerPane.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    private final MainWindow mainWindow;
    private final KorTTYApplication app;
    private final Mode mode;
    private final Consumer<String> onTitleChanged;
    /** Volatile: the docked panel rebinds the pane to another journal via {@code showJournal}. */
    private volatile Path journalDir;
    private final WebView webView = new WebView();
    private final BorderPane centerPane = new BorderPane();
    private final ObservableList<SessionJournalEntry> entries = FXCollections.observableArrayList();
    /** Lazily built in {@link #buildEditSplit()}; COMPACT mode never constructs it. */
    private TableView<SessionJournalEntry> entryTable;
    private final ComboBox<de.kortty.model.SessionJournalMarkerDefinition> markerCombo = new ComboBox<>();

    /** The document behind {@link #entries}; markers resolve against its snapshot. */
    private SessionJournalDocument loadedDocument = new SessionJournalDocument();
    private final TextField titleField = new TextField();
    private final TextArea summaryArea = new TextArea();
    private final SessionJournalNoteEditor noteEditor = new SessionJournalNoteEditor();
    private final Label editStatus = new Label();
    private final ToggleButton editToggle = new ToggleButton(I18n.get("journal.viewer.edit"));
    private final ToggleButton askToggle = new ToggleButton(I18n.get("journal.ask.toggle"));
    private SessionJournalAskPanel askPanel;
    private final Consumer<Path> changeListener;
    /**
     * JavaFX binds objects handed to {@code JSObject.setMember} through WEAK references, so the
     * bridge must stay strongly reachable for the life of the WebView — otherwise the next
     * JS→Java call crashes natively (see MonacoEditorPane).
     */
    private final JournalBridge journalBridge = new JournalBridge(this);
    private javafx.animation.PauseTransition fontScaleSaveDelay;
    private javafx.animation.PauseTransition appearanceSaveDelay;
    private javafx.animation.PauseTransition themeSaveDelay;
    private SplitPane editSplit;
    private volatile boolean disposed;

    // ==== live state ====
    /**
     * Debounced document reload: sits above the renderer's own 1000 ms debounce so the fresh
     * render this pane triggers never races a stale journal.html on disk.
     */
    private final javafx.animation.PauseTransition documentReloadDelay =
        new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
    /** True once the bridge is installed in the current page; executeScript is safe then. */
    private boolean pageReady;
    /** Timeline scroll offset to restore after the next load (0 = leave at the top/anchor). */
    private double pendingScrollY;
    /** Capture-log seq to jump to once the page is ready (queued by {@link #jumpToLogSeq}). */
    private Long pendingJumpSeq;
    /** Live batches that arrived while the page was loading; bounded, oldest dropped. */
    private final java.util.ArrayDeque<SessionJournalLogEntry> pendingLive = new java.util.ArrayDeque<>();
    private static final int PENDING_LIVE_CAP = SessionJournalLiveFeed.DEFAULT_MAX_ENTRIES;
    private SessionJournalLiveFeed feed;
    private boolean liveFeedActive;
    /** Whether the live tail should be (re)opened after loads — the user's last choice. */
    private boolean liveTailWanted;
    /** Notified (FX thread) when the page's live tail opens or closes; the host syncs its toggle. */
    private Consumer<Boolean> onLiveTailStateChanged;
    private javafx.animation.PauseTransition tailHeightSaveDelay;

    public SessionJournalViewerPane(MainWindow mainWindow, Path journalDir, Mode mode,
                                    Consumer<String> onTitleChanged) {
        this.mainWindow = mainWindow;
        this.app = KorTTYApplication.getInstance();
        this.journalDir = journalDir;
        this.mode = mode;
        this.onTitleChanged = onTitleChanged;

        // The page brings its own right-click menu (copy screenshot/summary/entry/log).
        webView.setContextMenuEnabled(false);
        // Disabling the native menu does NOT hand the right-click to the page: JavaFX WebView
        // never delivers a DOM contextmenu event for the real gesture. The page's menu only ever
        // worked in external browsers — inside the app the event has to be forwarded by hand.
        webView.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (!disposed) {
                forwardContextMenu(webView.getEngine(), event.getX(), event.getY());
            }
            event.consume();
        });
        installExternalLinkHandler();
        installJavaBridge();
        centerPane.setCenter(webView);

        if (mode == Mode.FULL) {
            setTop(buildToolbar());
        }
        setCenter(centerPane);

        // Live view: reload whenever this journal changes — the summarizer appended an entry,
        // an edit saved, the session closed. Debounced above the renderer's own debounce, and
        // the reload renders fresh HTML itself, so it never loads a stale journal.html.
        documentReloadDelay.setOnFinished(event -> reloadPreservingScroll());
        changeListener = changedDir -> {
            Path dir = this.journalDir;
            if (!disposed && dir != null
                && changedDir.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize())) {
                Platform.runLater(documentReloadDelay::playFromStart);
            }
        };
        if (service() != null) {
            service().addChangeListener(changeListener);
        }

        // The docked panel constructs the pane unbound (dir == null) and binds via showJournal.
        if (journalDir != null) {
            renderAndLoad(null);
        }
    }

    // ==== live binding (docked panel) ====

    /**
     * Rebinds the pane to another journal: one WebView per window, reused across sessions.
     * Must be called on the FX thread.
     */
    public void showJournal(Path dir) {
        hideAskPanel(); // the conversation belongs to the previous journal
        detachLiveSession();
        documentReloadDelay.stop();
        pendingLive.clear();
        pendingScrollY = 0;
        pageReady = false;
        liveTailWanted = false; // a new journal starts with the tail hidden (the default)
        if (onLiveTailStateChanged != null) {
            onLiveTailStateChanged.accept(false);
        }
        this.journalDir = dir;
        renderAndLoad(null);
    }

    /**
     * Streams the session's capture log into the loaded page. The feed keeps running across page
     * reloads: a fresh page's embedded {@code LOG} already holds every persisted line and the
     * page deduplicates by seq value, so batches queued here while a page loads are safe to flush
     * afterwards — the page discards what the render already caught. Lines older than the page's
     * 8 MB embed cap are never re-delivered, so the missing seqs there are harmless.
     */
    public void attachLiveSession(de.kortty.core.SessionJournalSession session) {
        detachLiveSession();
        liveFeedActive = true;
        feed = new SessionJournalLiveFeed(
            session,
            SessionJournalLiveFeed.DEFAULT_MAX_ENTRIES,
            SessionJournalLiveFeed.DEFAULT_COALESCE_MILLIS,
            Platform::runLater,
            backfill -> { /* the rendered page's embedded LOG already holds all persisted lines */ },
            this::onLiveBatch);
        feed.start();
        // The tail stays hidden by default; the user opens it on demand (host toggle). Lines
        // keep accumulating in the page's LOG either way, so opening later shows everything.
        if (pageReady && liveTailWanted) {
            applyLiveTailHeight();
            runScript("if(window.korttyOpenLiveTail){window.korttyOpenLiveTail();}");
        }
    }

    public void setOnLiveTailStateChanged(Consumer<Boolean> listener) {
        this.onLiveTailStateChanged = listener;
    }

    /** Opens the page's live-log tail (host toggle / after the user closed it). */
    public void openLiveTail() {
        liveTailWanted = true;
        applyLiveTailHeight();
        runScript("if(window.korttyOpenLiveTail){window.korttyOpenLiveTail();}");
    }

    /** Closes the page's live-log tail without stopping the feed (lines keep accumulating). */
    public void closeLiveTail() {
        liveTailWanted = false;
        runScript("if(window.korttyCloseLiveTail){window.korttyCloseLiveTail();}");
    }

    /**
     * Toggles the page between light and dark, resolving "auto" against the current appearance
     * first. Sets the attribute directly rather than clicking the page's ◐ button, and persists
     * through the same setting the button's bridge call uses.
     */
    public void cyclePageTheme() {
        runScript("(function(){var root=document.documentElement;"
            + "var cur=root.getAttribute('data-theme')||'auto';"
            + "var dark=cur==='dark'||(cur==='auto'&&window.matchMedia"
            + "&&window.matchMedia('(prefers-color-scheme: dark)').matches);"
            + "var next=dark?'light':'dark';"
            + "root.setAttribute('data-theme',next);"
            + "try{localStorage.setItem('kortty-journal-theme',next);}catch(e){}"
            + "})();");
        // Persist directly: the page script above cannot know whether the bridge is installed.
        GlobalSettings settings = settings();
        if (settings != null) {
            try {
                Object theme = webView.getEngine()
                    .executeScript("document.documentElement.getAttribute('data-theme')");
                if (theme instanceof String value) {
                    persistPageTheme(value);
                }
            } catch (Exception e) {
                logger.debug("Could not read the journal page theme: {}", e.getMessage());
            }
        }
    }

    /** Applies the persisted tail height to the page (no-op when unset). */
    private void applyLiveTailHeight() {
        GlobalSettings settings = settings();
        Integer heightVh = settings != null ? settings.getSessionJournalLiveTailHeightVh() : null;
        if (heightVh != null) {
            runScript("if(window.korttySetLiveTailHeight){window.korttySetLiveTailHeight(" + heightVh + ");}");
        }
    }

    /** The page reported an open/close of the tail (✕ button, card click, host toggle). */
    void liveTailStateChanged(boolean open) {
        liveTailWanted = open;
        if (onLiveTailStateChanged != null) {
            onLiveTailStateChanged.accept(open);
        }
    }

    /** The page reported a drag-resize of the tail; persist debounced like the font scale. */
    void persistLiveTailHeight(int heightVh) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setSessionJournalLiveTailHeightVh(heightVh);
        if (tailHeightSaveDelay == null) {
            tailHeightSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            tailHeightSaveDelay.setOnFinished(event -> {
                try {
                    if (app != null && app.getGlobalSettingsManager() != null) {
                        app.getGlobalSettingsManager().save();
                    }
                } catch (Exception e) {
                    logger.warn("Could not save the live tail height: {}", e.getMessage());
                }
            });
        }
        tailHeightSaveDelay.playFromStart();
    }

    /** Stops feeding; the tail stays visible (frozen) so a stopped journal keeps its context. */
    public void detachLiveSession() {
        liveFeedActive = false;
        if (feed != null) {
            feed.stop();
            feed = null;
        }
        pendingLive.clear();
    }

    private void onLiveBatch(List<SessionJournalLogEntry> batch) {
        if (disposed) {
            return;
        }
        if (!pageReady) {
            for (SessionJournalLogEntry entry : batch) {
                if (pendingLive.size() >= PENDING_LIVE_CAP) {
                    pendingLive.pollFirst();
                }
                pendingLive.addLast(entry);
            }
            return;
        }
        pushLiveEntries(batch);
    }

    private void pushLiveEntries(List<SessionJournalLogEntry> batch) {
        String script = SessionJournalLiveScript.appendLogCall(
            batch, ZoneId.systemDefault(),
            I18n.get("journal.html.hiddenInput"),
            I18n.get("journal.html.screenshot"));
        if (!script.isEmpty()) {
            runScript(script);
        }
    }

    private void flushPendingLiveEntries() {
        if (pendingLive.isEmpty()) {
            return;
        }
        List<SessionJournalLogEntry> batch = new java.util.ArrayList<>(pendingLive);
        pendingLive.clear();
        pushLiveEntries(batch);
    }

    /** FX-thread executeScript with the disposed/pageReady guards; failures are debug-logged. */
    private void runScript(String script) {
        if (disposed || !pageReady || script == null || script.isEmpty()) {
            return;
        }
        try {
            webView.getEngine().executeScript(script);
        } catch (Exception e) {
            logger.debug("Journal page script failed: {}", e.getMessage());
        }
    }

    /** Captures the timeline scroll offset, then reloads with a fresh render. */
    private void reloadPreservingScroll() {
        if (disposed) {
            return;
        }
        try {
            Object y = webView.getEngine().executeScript("window.scrollY");
            pendingScrollY = y instanceof Number number ? number.doubleValue() : 0;
        } catch (Exception ignored) {
            pendingScrollY = 0;
        }
        renderAndLoad(null);
    }

    public Path getJournalDir() {
        return journalDir;
    }

    /** Re-renders and reloads the page; the docked panel's refresh action. */
    public void refresh() {
        renderAndLoad(null);
    }

    /** Scrolls the timeline to the entry card in the loaded page; anchored reload while loading. */
    public void jumpToEntry(String entryId) {
        if (disposed || entryId == null || entryId.isBlank()) {
            return;
        }
        if (!pageReady) {
            renderAndLoad(entryId);
            return;
        }
        runScript("(function(){var el=document.getElementById("
            + de.kortty.core.AiChatRenderPageSupport.toJsStringLiteral("entry-" + entryId)
            + ");if(el){el.scrollIntoView({block:\"center\"});}})();");
    }

    /**
     * Opens the log panel scrolled to the capture-log entry with the given seq. Oversized
     * journals embed only entry-referenced log ranges into the page, so when the seq is not
     * embedded this falls back to the tightest timeline entry covering it. Queued while the
     * page is still loading and applied once it is ready.
     */
    public void jumpToLogSeq(long seq) {
        if (disposed) {
            return;
        }
        if (!pageReady) {
            pendingJumpSeq = seq;
            return;
        }
        Object jumped = null;
        try {
            jumped = webView.getEngine().executeScript(
                "window.korttyJumpToSeq ? window.korttyJumpToSeq(" + seq + ") : false");
        } catch (Exception e) {
            logger.debug("Journal seq jump script failed: {}", e.getMessage());
        }
        if (Boolean.TRUE.equals(jumped)) {
            return;
        }
        jumpToCoveringEntry(seq);
    }

    /** Fallback for {@link #jumpToLogSeq(long)}: the tightest entry whose log range covers the seq. */
    private void jumpToCoveringEntry(long seq) {
        SessionJournalService service = service();
        Path dir = journalDir;
        if (service == null || dir == null) {
            return;
        }
        Thread lookup = new Thread(() -> {
            try {
                SessionJournalDocument document = service.loadDocument(dir);
                SessionJournalEntry best = null;
                long bestSpan = Long.MAX_VALUE;
                for (SessionJournalEntry entry : document.getEntries()) {
                    if (entry.getLogStartSeq() == null || entry.getLogEndSeq() == null
                        || seq < entry.getLogStartSeq() || seq > entry.getLogEndSeq()) {
                        continue;
                    }
                    long span = entry.getLogEndSeq() - entry.getLogStartSeq();
                    if (span < bestSpan) {
                        bestSpan = span;
                        best = entry;
                    }
                }
                String entryId = best != null ? best.getId() : null;
                if (entryId != null) {
                    Platform.runLater(() -> jumpToEntry(entryId));
                }
            } catch (Exception e) {
                logger.debug("Journal seq fallback lookup failed: {}", e.getMessage());
            }
        }, "SessionJournal-SeqJumpLookup");
        lookup.setDaemon(true);
        lookup.start();
    }

    /** Opens the appearance popover anchored at a host-supplied control. */
    public void showAppearance(Node anchor) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        SessionJournalAppearancePopover.show(anchor, settings,
            this::previewAppearance, this::commitAppearance);
    }

    /** Themed inline when docked; when hosted in the dialog its stylesheets win anyway. */
    public void applyTheme(String bgColor, String fgColor) {
        String bg = bgColor != null && !bgColor.isEmpty() ? bgColor : "#1e1e1e";
        setStyle("-fx-background-color: " + bg + ";");
    }

    /** Owner for child dialogs: the current scene's window, or the main stage while detached. */
    private Window ownerWindow() {
        return getScene() != null && getScene().getWindow() != null
            ? getScene().getWindow()
            : mainWindow.getStage();
    }

    private HBox buildToolbar() {
        Button openBrowserButton = new Button(I18n.get("journal.viewer.openBrowser"));
        openBrowserButton.setOnAction(event -> openInBrowser());
        MenuButton exportButton = new MenuButton(I18n.get("journal.manager.export"));
        for (SessionJournalExportService.Format format : SessionJournalExportService.Format.values()) {
            MenuItem item = new MenuItem(switch (format) {
                case PDF -> I18n.get("journal.export.pdf");
                case MARKDOWN -> I18n.get("journal.export.markdown");
                case HTML_BUNDLE -> I18n.get("journal.export.htmlBundle");
            });
            item.setOnAction(event -> exportJournal(format));
            exportButton.getItems().add(item);
        }
        editToggle.setOnAction(event -> toggleEditMode());
        Button appearanceButton = new Button(I18n.get("journal.viewer.appearance"));
        appearanceButton.setOnAction(event -> showAppearance(appearanceButton));
        Button refreshButton = new Button(I18n.get("journal.viewer.refresh"));
        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);
        refreshButton.setOnAction(event -> renderAndLoad(null));

        HBox toolbar = new HBox(8, openBrowserButton, exportButton, editToggle,
            appearanceButton, refreshButton);
        if (de.kortty.policy.PolicyManager.effective().sessionJournalAiAskAllowed()) {
            askToggle.setOnAction(event -> toggleAskPanel());
            toolbar.getChildren().add(askToggle);
        }
        toolbar.setPadding(new Insets(6));
        return toolbar;
    }

    // ==== AI Q&A panel ====

    private void toggleAskPanel() {
        if (askPanel != null) {
            hideAskPanel();
            return;
        }
        Path dir = journalDir;
        SessionJournalService service = service();
        if (dir == null || service == null) {
            askToggle.setSelected(false);
            return;
        }
        // The meta (host, user, journal id) lives in journal.xml — load it off the FX thread.
        Thread loader = new Thread(() -> {
            try {
                de.kortty.model.SessionJournalMeta meta = service.loadDocument(dir).getMeta();
                Platform.runLater(() -> {
                    if (!disposed && dir.equals(journalDir) && askPanel == null
                        && askToggle.isSelected()) {
                        installAskPanel(meta);
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not open the journal Q&A panel: {}", e.getMessage());
                Platform.runLater(() -> askToggle.setSelected(false));
            }
        }, "SessionJournal-AskMeta");
        loader.setDaemon(true);
        loader.start();
    }

    private void installAskPanel(de.kortty.model.SessionJournalMeta meta) {
        askPanel = new SessionJournalAskPanel(meta,
            de.kortty.core.SessionJournalAskService.application(service()),
            this::jumpToEntry, this::jumpToLogSeq, this::saveAskAnswerAsEntry);
        SplitPane split = new SplitPane(centerPane, askPanel);
        split.setDividerPositions(0.68);
        setCenter(split);
        askPanel.focusQuestionField();
    }

    private void hideAskPanel() {
        if (askPanel == null) {
            return;
        }
        askPanel.dispose();
        askPanel = null;
        setCenter(centerPane);
        askToggle.setSelected(false);
    }

    /** Persists a Q&A answer as an AGENT entry; the change listener re-renders the timeline. */
    private void saveAskAnswerAsEntry(String question, String answerMarkdown) {
        Path dir = journalDir;
        SessionJournalService service = service();
        if (dir == null || service == null) {
            return;
        }
        Thread saver = new Thread(() -> {
            try {
                SessionJournalEntry entry = new SessionJournalEntry();
                entry.setKind(de.kortty.model.SessionJournalEntryKind.AGENT);
                entry.setTitle(de.kortty.core.SessionJournalAiSupport.normalizeTitle(
                    question, I18n.get("journal.ask.title"), 80));
                entry.setText(answerMarkdown);
                service.appendEntry(dir, entry);
            } catch (Exception e) {
                logger.warn("Could not save the Q&A answer as a journal entry: {}", e.getMessage());
            }
        }, "SessionJournal-AskNote");
        saver.setDaemon(true);
        saver.start();
    }

    // ==== page loading ====

    private SessionJournalService service() {
        return app != null ? app.getSessionJournalService() : null;
    }

    private SessionJournalHtmlRenderer renderer() {
        return app != null ? app.getSessionJournalHtmlRenderer() : null;
    }

    /** Regenerates journal.html and loads it; a non-null anchor keeps the scroll position. */
    void renderAndLoad(String anchorEntryId) {
        // A direct (often anchored) load supersedes any pending debounced reload.
        documentReloadDelay.stop();
        if (anchorEntryId != null) {
            pendingScrollY = 0; // the anchor is the better position
        }
        Path dir = journalDir;
        if (dir == null) {
            return;
        }
        Thread rendererThread = new Thread(() -> {
            try {
                Path htmlFile = renderer() != null
                    ? renderer().renderToFile(dir)
                    : dir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
                if (!Files.isRegularFile(htmlFile)) {
                    return;
                }
                String url = htmlFile.toUri().toURL().toExternalForm();
                String target = anchorEntryId != null ? url + "#entry-" + anchorEntryId : url;
                Platform.runLater(() -> {
                    if (!disposed && dir.equals(journalDir)) {
                        webView.getEngine().load(target);
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not render session journal page: {}", e.getMessage());
            }
        }, "SessionJournal-ViewerRender");
        rendererThread.setDaemon(true);
        rendererThread.start();
        if (editToggle.isSelected()) {
            loadEntries();
        }
    }

    /**
     * Exposes {@code window.korttyJournal} to the page so it can copy screenshots and text through
     * the system clipboard and persist its font size in the korTTY settings.
     */
    private void installJavaBridge() {
        // A JavaScript error in the page is otherwise completely silent, and the page is one
        // block: a single throw can take out the whole context menu.
        webView.getEngine().setOnError(event ->
            logger.warn("Session journal page error: {}", event.getMessage()));
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (disposed) {
                return;
            }
            if (newState == javafx.concurrent.Worker.State.SCHEDULED
                || newState == javafx.concurrent.Worker.State.RUNNING) {
                pageReady = false;
                return;
            }
            if (newState != javafx.concurrent.Worker.State.SUCCEEDED) {
                return;
            }
            // Defer off the load-worker callback: WebKit is still inside its native load-finished
            // dispatch here, and calling executeScript re-entrantly crashes intermittently.
            Platform.runLater(() -> {
                if (disposed) {
                    return;
                }
                try {
                    netscape.javascript.JSObject window =
                        (netscape.javascript.JSObject) webView.getEngine().executeScript("window");
                    window.setMember("korttyJournal", journalBridge);
                    // Only now can the page offer Replace — it needs the bridge to reach the app.
                    webView.getEngine().executeScript(
                        "if(window.korttyEnableReplace){window.korttyEnableReplace();}"
                            + "if(window.korttyEnableRange){window.korttyEnableRange();}"
                            + "if(window.korttyEnableAppActions){window.korttyEnableAppActions();}");
                    if (isAiScreenshotAnalysisAvailable()) {
                        webView.getEngine().executeScript(
                            "if(window.korttyEnableAiAnalysis){window.korttyEnableAiAnalysis();}");
                    }
                    pageReady = true;
                    // Re-establish the live state the load reset: the tail (unless the user
                    // closed it), its height, the timeline scroll offset, and any batches that
                    // arrived while loading (the page's seq set discards what the fresh render
                    // already contains).
                    applyLiveTailHeight();
                    if (liveFeedActive && liveTailWanted) {
                        runScript("if(window.korttyOpenLiveTail){window.korttyOpenLiveTail();}");
                    }
                    if (pendingScrollY > 0) {
                        runScript("window.scrollTo(0," + pendingScrollY + ");");
                        pendingScrollY = 0;
                    }
                    flushPendingLiveEntries();
                    if (pendingJumpSeq != null) {
                        long seq = pendingJumpSeq;
                        pendingJumpSeq = null;
                        jumpToLogSeq(seq);
                    }
                } catch (Exception e) {
                    logger.warn("Could not install the session journal page bridge: {}", e.getMessage());
                }
            });
        });
    }

    /** Puts plain text on the system clipboard; called from the page's copy actions. */
    boolean copyTextToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        return javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Prompts for a new journal title and saves it. Uses the same wording as the manager's rename
     * and surfaces the same refusals: an organization policy may forbid renaming.
     */
    private void renameJournalInteractive() {
        if (disposed) {
            return;
        }
        Path dir = journalDir;
        Thread loader = new Thread(() -> {
            try {
                String currentTitle = service().loadDocument(dir).getMeta().getTitle();
                Platform.runLater(() -> {
                    if (dir.equals(journalDir)) {
                        promptForRename(currentTitle);
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not load the journal title: {}", e.getMessage());
            }
        }, "SessionJournal-RenameLoad");
        loader.setDaemon(true);
        loader.start();
    }

    private void promptForRename(String currentTitle) {
        javafx.scene.control.TextInputDialog prompt =
            new javafx.scene.control.TextInputDialog(currentTitle != null ? currentTitle : "");
        DialogThemeHelper.applyTheme(prompt);
        prompt.initOwner(ownerWindow());
        prompt.setTitle(I18n.get("journal.manager.rename.title"));
        prompt.setHeaderText(I18n.get("journal.manager.rename.header"));
        String entered = prompt.showAndWait().orElse(null);
        if (entered == null || entered.isBlank() || entered.strip().equals(currentTitle)) {
            return;
        }
        String newTitle = entered.strip();
        Path dir = journalDir;
        Thread saver = new Thread(() -> {
            try {
                service().renameJournal(dir, newTitle);
                Platform.runLater(() -> {
                    if (onTitleChanged != null) {
                        onTitleChanged.accept(newTitle);
                    }
                    renderAndLoad(null);
                });
            } catch (Exception e) {
                // Most commonly the enterprise policy; its message says exactly that.
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-Rename");
        saver.setDaemon(true);
        saver.start();
    }

    /**
     * Dispatches a DOM contextmenu event at the given WebView-local coordinates, which map 1:1 to
     * CSS viewport pixels at the default zoom. Package-visible so the WebView smoke can drive the
     * exact production path instead of a synthetic stand-in.
     */
    static void forwardContextMenu(javafx.scene.web.WebEngine engine, double x, double y) {
        try {
            engine.executeScript(
                "(function(x,y){var el=document.elementFromPoint(x,y)||document.body;"
                    + "el.dispatchEvent(new MouseEvent('contextmenu',"
                    + "{bubbles:true,cancelable:true,clientX:x,clientY:y}));})("
                    + x + "," + y + ")");
        } catch (Exception e) {
            logger.debug("Could not forward the context menu into the journal page: {}", e.getMessage());
        }
    }

    /** Strips the cache-busting token the page appends to an edited screenshot's URL. */
    private static String stripCacheToken(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        int query = relativePath.indexOf('?');
        return query >= 0 ? relativePath.substring(0, query) : relativePath;
    }

    /** Puts a journal screenshot on the system clipboard as an image. */
    boolean copyImageToClipboard(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            Path base = journalDir.toAbsolutePath().normalize();
            Path image = base.resolve(stripCacheToken(relativePath)).normalize();
            // The path comes from the page, so never let it escape the journal directory.
            if (!image.startsWith(base) || !Files.isRegularFile(image)) {
                return false;
            }
            javafx.scene.image.Image loaded =
                new javafx.scene.image.Image(image.toUri().toURL().toExternalForm());
            if (loaded.isError()) {
                return false;
            }
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putImage(loaded);
            return javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        } catch (Exception e) {
            logger.warn("Could not copy the journal screenshot: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Applies a look to the loaded page without re-rendering it. The inline style on {@code <html>}
     * outranks every stylesheet rule, so setting the custom properties there is both instant and
     * complete; the file on disk catches up when the debounced commit re-renders it.
     */
    private void previewAppearance(de.kortty.core.SessionJournalPageAppearance appearance) {
        if (disposed) {
            return;
        }
        try {
            webView.getEngine().executeScript(appearance.previewScript(
                SessionJournalPageSchemes.resolve(appearance.schemeId(), app)));
        } catch (Exception e) {
            logger.debug("Could not preview the journal appearance: {}", e.getMessage());
        }
    }

    /** Writes the look into the settings and re-renders, debounced so dragging stays smooth. */
    private void commitAppearance(de.kortty.core.SessionJournalPageAppearance appearance) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setSessionJournalPageSchemeId(appearance.schemeId());
        settings.setSessionJournalPageUiFont(appearance.uiFont());
        settings.setSessionJournalPageMonoFont(appearance.monoFont());
        settings.setSessionJournalFontScalePercent(appearance.fontScalePercent());
        if (appearanceSaveDelay == null) {
            appearanceSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
            appearanceSaveDelay.setOnFinished(event -> {
                try {
                    if (app != null && app.getGlobalSettingsManager() != null) {
                        app.getGlobalSettingsManager().save();
                    }
                    // Re-render so "Open in browser" and every export show the same look.
                    if (renderer() != null) {
                        renderer().requestRender(journalDir);
                    }
                } catch (Exception e) {
                    logger.warn("Could not save the journal appearance: {}", e.getMessage());
                }
            });
        }
        appearanceSaveDelay.playFromStart();
    }

    /**
     * Persists the light/dark state the page's ◐ button produced. Without this the choice would
     * live only in the page's localStorage and be lost whenever the journal regenerates — which
     * now happens on every marker edit, annotation and appearance change.
     */
    void persistPageTheme(String theme) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setSessionJournalPageTheme(
            de.kortty.core.SessionJournalPageAppearance.normalizeTheme(theme));
        if (themeSaveDelay == null) {
            themeSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            themeSaveDelay.setOnFinished(event -> {
                try {
                    if (app != null && app.getGlobalSettingsManager() != null) {
                        app.getGlobalSettingsManager().save();
                    }
                } catch (Exception e) {
                    logger.warn("Could not save the journal page theme: {}", e.getMessage());
                }
            });
        }
        themeSaveDelay.playFromStart();
    }

    /** Persists the page font size chosen with the A-/A+ buttons (debounced). */
    void persistFontScale(int percent) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        settings.setSessionJournalFontScalePercent(percent);
        if (fontScaleSaveDelay == null) {
            fontScaleSaveDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            fontScaleSaveDelay.setOnFinished(event -> {
                try {
                    if (app != null && app.getGlobalSettingsManager() != null) {
                        app.getGlobalSettingsManager().save();
                    }
                } catch (Exception e) {
                    logger.warn("Could not save the journal font size: {}", e.getMessage());
                }
            });
        }
        fontScaleSaveDelay.playFromStart();
    }

    /** JS→Java entry points; must be a public class with public methods for WebView marshalling. */
    public static final class JournalBridge {
        private final java.lang.ref.WeakReference<SessionJournalViewerPane> paneRef;

        JournalBridge(SessionJournalViewerPane pane) {
            this.paneRef = new java.lang.ref.WeakReference<>(pane);
        }

        public boolean copyText(String text) {
            SessionJournalViewerPane pane = paneRef.get();
            return pane != null && pane.copyTextToClipboard(text);
        }

        public boolean copyImage(String relativePath) {
            SessionJournalViewerPane pane = paneRef.get();
            return pane != null && pane.copyImageToClipboard(relativePath);
        }

        public void fontScaleChanged(int percent) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                pane.persistFontScale(percent);
            }
        }

        /** Opens the marker picker for the entry the user right-clicked in the timeline. */
        public void requestMarker(String entryId) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.chooseMarkerForEntry(entryId));
            }
        }

        /** Opens the rename prompt for this journal (title double-click or context menu). */
        public void requestRename() {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(pane::renameJournalInteractive);
            }
        }

        /** Persists the page's light/dark choice so a regenerated page keeps it. */
        public void themeChanged(String theme) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                pane.persistPageTheme(theme);
            }
        }

        /** Opens the screenshot editor for the picture the user right-clicked in the timeline. */
        public void requestAnnotate(String entryId) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.editScreenshotById(entryId));
            }
        }

        /** Opens a link the user typed into a note in the system browser. */
        public void openExternalLink(String url) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.openExternalLink(url));
            }
        }

        /** Runs the AI screenshot analysis for the picture the user right-clicked. */
        public void requestAiAnalysis(String entryId) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.analyzeScreenshotById(entryId));
            }
        }

        /** Saves the picture the user right-clicked to a file of their choosing. */
        public void requestSaveImage(String relativePath) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.exportScreenshot(relativePath));
            }
        }

        /**
         * Hands the time windows the user marked in the timeline to the export dialog.
         * {@code windowsJson} is {@code [{"from":"<iso>","to":"<iso>"},…]}.
         */
        public void applyTimeWindows(String windowsJson) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.openExportWithWindows(windowsJson));
            }
        }

        /**
         * Opens the search-and-replace dialog with the page's current search term. The page does
         * not rewrite anything itself: the journal files are the source of truth and the page is
         * regenerated from them, so the round trip through the dialog is also what keeps the
         * confirmation, the dry run and the log-rewrite choice in one place.
         */
        public void requestReplace(String searchTerm) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.replaceInJournal(searchTerm));
            }
        }

        /** The page's live tail opened or closed (✕, card click, programmatic open). */
        public void liveTailStateChanged(boolean open) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.liveTailStateChanged(open));
            }
        }

        /** The user drag-resized the live tail; height arrives in vh. */
        public void liveTailHeightChanged(int heightVh) {
            SessionJournalViewerPane pane = paneRef.get();
            if (pane != null) {
                Platform.runLater(() -> pane.persistLiveTailHeight(heightVh));
            }
        }
    }

    /**
     * Opens a link from the page in the system browser. The scheme is re-checked here rather than
     * trusted from the page: the renderer only ever writes http(s) hrefs, but this method is
     * reachable from JavaScript, and handing an arbitrary string to the desktop's URL opener is
     * exactly the kind of thing that must not depend on one layer getting it right.
     */
    private void openExternalLink(String url) {
        if (url == null) {
            return;
        }
        String trimmed = url.trim();
        String scheme = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            logger.warn("Refusing to open a journal link that is not http(s)");
            return;
        }
        try {
            app.getHostServices().showDocument(trimmed);
        } catch (Exception e) {
            logger.warn("Could not open external link: {}", e.getMessage());
        }
    }

    /** http(s) links inside the journal open in the system browser, never in the WebView. */
    private void installExternalLinkHandler() {
        webView.getEngine().locationProperty().addListener((obs, oldLocation, newLocation) -> {
            if (newLocation != null && (newLocation.startsWith("http://") || newLocation.startsWith("https://"))) {
                Platform.runLater(() -> {
                    try {
                        app.getHostServices().showDocument(newLocation);
                    } catch (Exception e) {
                        logger.warn("Could not open external link: {}", e.getMessage());
                    }
                    if (oldLocation != null) {
                        webView.getEngine().load(oldLocation);
                    }
                });
            }
        });
    }

    private void openInBrowser() {
        try {
            Path htmlFile = journalDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
            if (!Files.isRegularFile(htmlFile) && renderer() != null) {
                htmlFile = renderer().renderToFile(journalDir);
            }
            app.getHostServices().showDocument(htmlFile.toUri().toURL().toExternalForm());
        } catch (Exception e) {
            showError(I18n.get("journal.export.error", e.getMessage()));
        }
    }

    // ==== edit mode ====

    private TableView<SessionJournalEntry> buildEntryTable() {
        TableView<SessionJournalEntry> view = new TableView<>(entries);
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // MULTIPLE so a range can also be picked in the table; the form and Save/Delete keep
        // working on the last selected row only.
        view.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        javafx.scene.control.MenuItem useAsWindow =
            new javafx.scene.control.MenuItem(I18n.get("journal.viewer.useSelectionAsWindow"));
        useAsWindow.setOnAction(event -> exportSelectionAsWindow(view));
        javafx.scene.control.MenuItem editImage =
            new javafx.scene.control.MenuItem(I18n.get("journal.screenshot.editor.open"));
        editImage.setOnAction(event -> editScreenshot(view.getSelectionModel().getSelectedItem()));
        javafx.scene.control.MenuItem exportImage =
            new javafx.scene.control.MenuItem(I18n.get("journal.screenshot.export.open"));
        exportImage.setOnAction(event -> {
            SessionJournalEntry selected = view.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getKind() == de.kortty.model.SessionJournalEntryKind.SCREENSHOT) {
                exportScreenshot(selected.getScreenshotFile());
            }
        });
        view.setContextMenu(new javafx.scene.control.ContextMenu(editImage, exportImage, useAsWindow));
        // Double-clicking a screenshot row is the obvious way in, so offer it too.
        view.setRowFactory(table -> {
            javafx.scene.control.TableRow<SessionJournalEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()
                    && row.getItem().getKind() == de.kortty.model.SessionJournalEntryKind.SCREENSHOT) {
                    editScreenshot(row.getItem());
                }
            });
            return row;
        });

        TableColumn<SessionJournalEntry, String> timeColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.time"));
        timeColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().getCreatedAt() != null
                ? cell.getValue().getCreatedAt().atZoneSameInstant(ZoneId.systemDefault()).format(TIME_FORMAT)
                : ""));
        timeColumn.setMinWidth(80);

        TableColumn<SessionJournalEntry, String> kindColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.kind"));
        kindColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getKind().name()));
        kindColumn.setMinWidth(100);

        TableColumn<SessionJournalEntry, String> markerColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.marker"));
        markerColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            de.kortty.core.SessionJournalMarkers.displayName(resolveMarker(cell.getValue()))));
        // A swatch makes the column readable at a glance now that markers carry user colours.
        markerColumn.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                de.kortty.model.SessionJournalMarkerDefinition definition =
                    resolveMarker(getTableView().getItems().get(getIndex()));
                setGraphic(definition.isNone() ? null : SessionJournalMarkerDialog.swatch(definition));
            }
        });
        markerColumn.setMinWidth(110);

        TableColumn<SessionJournalEntry, String> textColumn =
            new TableColumn<>(I18n.get("journal.viewer.column.text"));
        textColumn.setCellValueFactory(cell -> {
            String text = cell.getValue().getTitle() != null && !cell.getValue().getTitle().isBlank()
                ? cell.getValue().getTitle()
                : cell.getValue().getText();
            if (text == null) {
                text = "";
            }
            return new SimpleStringProperty(text.length() > 80 ? text.substring(0, 80) + "..." : text);
        });
        textColumn.setMinWidth(220);

        view.getColumns().addAll(List.of(timeColumn, kindColumn, markerColumn, textColumn));
        view.getSelectionModel().selectedItemProperty().addListener((obs, old, entry) -> {
            showEntryInForm(entry);
            editStatus.setText("");
        });
        return view;
    }

    /**
     * Copies one screenshot out of the journal to wherever the user wants it. Exports the picture
     * as shown — with its marks — since that is the one the page displays.
     */
    private void exportScreenshot(String relativePath) {
        if (disposed || relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path base = journalDir.toAbsolutePath().normalize();
        Path source = base.resolve(stripCacheToken(relativePath)).normalize();
        // The path arrives from the page, so it must never escape the journal directory.
        if (!source.startsWith(base) || !Files.isRegularFile(source)) {
            showError(I18n.get("journal.screenshot.missing"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("journal.screenshot.export.title"));
        chooser.setInitialFileName(source.getFileName().toString());
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get("journal.screenshot.export.filter"), "*.png"));
        File target = chooser.showSaveDialog(ownerWindow());
        if (target == null) {
            return;
        }
        try {
            Files.copy(source, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            showInfo(I18n.get("journal.screenshot.export.done", target.getAbsolutePath()));
        } catch (Exception e) {
            logger.warn("Could not export the journal screenshot: {}", e.getMessage());
            showError(I18n.get("journal.export.error", e.getMessage()));
        }
    }

    /** Loads the entry behind an id off-thread and opens the screenshot editor for it. */
    /** Availability of the manual AI screenshot analysis; decides the page's context-menu entry. */
    private static boolean isAiScreenshotAnalysisAvailable() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            de.kortty.core.SessionJournalScreenshotAnalyzer analyzer =
                app != null ? app.getSessionJournalScreenshotAnalyzer() : null;
            return analyzer != null && analyzer.isManuallyAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Manual AI analysis of one screenshot entry, triggered from the page's context menu. The
     * result appears through the normal change-listener reload; only failures need UI here.
     */
    private void analyzeScreenshotById(String entryId) {
        if (disposed || entryId == null || entryId.isBlank()) {
            return;
        }
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        de.kortty.core.SessionJournalScreenshotAnalyzer analyzer =
            app != null ? app.getSessionJournalScreenshotAnalyzer() : null;
        Path dir = journalDir;
        if (analyzer == null || dir == null) {
            return;
        }
        runScript("if(window.korttyToast){window.korttyToast("
            + jsQuote(I18n.get("journal.ai.screenshot.started")) + ");}");
        analyzer.analyzeManually(dir, entryId).whenComplete((ignored, failure) -> {
            if (failure == null) {
                return;
            }
            Throwable cause = failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null ? failure.getCause() : failure;
            Platform.runLater(() -> {
                if (disposed || !dir.equals(journalDir)) {
                    return;
                }
                if (cause instanceof de.kortty.core.SessionJournalScreenshotAnalyzer.VisionUnavailableException) {
                    showError(I18n.get("journal.ai.screenshot.unavailable"));
                } else {
                    showError(I18n.get("journal.ai.screenshot.failed",
                        cause.getMessage() != null ? cause.getMessage() : cause.toString()));
                }
            });
        });
    }

    private static String jsQuote(String value) {
        String safe = value != null ? value : "";
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ") + "\"";
    }

    private void editScreenshotById(String entryId) {
        if (disposed || entryId == null || entryId.isBlank()) {
            return;
        }
        Path dir = journalDir;
        Thread loader = new Thread(() -> {
            try {
                SessionJournalDocument document = service().loadDocument(dir);
                SessionJournalEntry target = document.getEntries().stream()
                    .filter(entry -> entryId.equals(entry.getId()))
                    .findFirst()
                    .orElse(null);
                if (target != null) {
                    Platform.runLater(() -> {
                        if (dir.equals(journalDir)) {
                            editScreenshot(target);
                        }
                    });
                }
            } catch (Exception e) {
                logger.warn("Could not load the journal entry {}: {}", entryId, e.getMessage());
            }
        }, "SessionJournal-ScreenshotLoad");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Opens the screenshot editor for an entry and writes back both the marks and the note.
     * The editor always starts from the untouched capture, so repeated edits replace the previous
     * marks instead of stacking a second layer on top of them.
     */
    private void editScreenshot(SessionJournalEntry entry) {
        if (entry == null || entry.getKind() != de.kortty.model.SessionJournalEntryKind.SCREENSHOT
            || entry.getScreenshotFile() == null) {
            return;
        }
        Path dir = journalDir;
        Path source = de.kortty.core.SessionJournalScreenshotAnnotator.sourceImage(dir, entry);
        if (source == null || !Files.isRegularFile(source)) {
            showError(I18n.get("journal.screenshot.missing"));
            return;
        }
        SessionJournalScreenshotEditorDialog.Result result = SessionJournalScreenshotEditorDialog
            .open(ownerWindow(), source, entry)
            .orElse(null);
        if (result == null) {
            return;
        }
        SessionJournalEntry updated = new SessionJournalEntry(entry);
        updated.setAnnotations(result.annotations());
        updated.setUserNote(result.note());
        Thread saver = new Thread(() -> {
            try {
                // Burn first: updateEntry notifies listeners, and the page must already show the
                // new image when it reloads.
                de.kortty.core.SessionJournalScreenshotAnnotator.apply(dir, updated);
                service().updateEntry(dir, updated);
                Platform.runLater(() -> {
                    if (!dir.equals(journalDir)) {
                        return;
                    }
                    loadEntries();
                    renderAndLoad(updated.getId());
                });
            } catch (Exception e) {
                logger.warn("Could not save the screenshot annotations: {}", e.getMessage());
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-ScreenshotEdit");
        saver.setDaemon(true);
        saver.start();
    }

    /** Turns the selected rows into one time window and opens the export dialog with it. */
    private void exportSelectionAsWindow(TableView<SessionJournalEntry> view) {
        List<SessionJournalEntry> selected = view.getSelectionModel().getSelectedItems().stream()
            .filter(entry -> entry != null && entry.getCreatedAt() != null)
            .sorted(java.util.Comparator.comparing(SessionJournalEntry::getCreatedAt))
            .toList();
        if (selected.isEmpty()) {
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime from = selected.get(0).getCreatedAt().atZoneSameInstant(zone);
        java.time.ZonedDateTime to = selected.get(selected.size() - 1).getCreatedAt()
            .atZoneSameInstant(zone);
        exportJournal(SessionJournalExportService.Format.PDF,
            List.of(new de.kortty.core.SessionJournalExportFilter.TimeWindow(
                from.toLocalDate(), from.toLocalTime().withSecond(0).withNano(0),
                to.toLocalDate(), to.toLocalTime().withSecond(0).withNano(0))));
    }

    /** The picker plus the affordance that leads to the marker and rule management. */
    private HBox markerRow() {
        Button manage = new Button(I18n.get("journal.viewer.marker.manage"));
        manage.setOnAction(event -> showMarkerDialog());
        HBox row = new HBox(8, markerCombo, manage);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private void refreshMarkerRegistry() {
        de.kortty.model.SessionJournalMarkerDefinition selected = markerCombo.getValue();
        markerCombo.getItems().setAll(markerRegistry());
        markerCombo.setValue(selected != null
            ? de.kortty.core.SessionJournalMarkers.byId(selected.getId(), markerCombo.getItems())
            : de.kortty.core.SessionJournalMarkers.byId("none", markerCombo.getItems()));
    }

    private void showMarkerDialog() {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        boolean changed = SessionJournalMarkerDialog.show(
            ownerWindow(), settings, this::applyMarkerRules);
        if (!changed) {
            return;
        }
        try {
            if (app != null && app.getGlobalSettingsManager() != null) {
                app.getGlobalSettingsManager().save();
            }
        } catch (Exception e) {
            logger.warn("Could not save the journal markers: {}", e.getMessage());
        }
        refreshMarkerRegistry();
        loadEntries();
        renderAndLoad(null);
    }

    /**
     * The shortcut from the timeline's context menu: pick a marker for one entry. Writes through
     * the same path as the edit form, so a marker set here is a USER marker too and no rule or AI
     * pass will overwrite it.
     */
    private void chooseMarkerForEntry(String entryId) {
        if (disposed || entryId == null || entryId.isBlank()) {
            return;
        }
        Path dir = journalDir;
        Thread loader = new Thread(() -> {
            try {
                SessionJournalDocument document = service().loadDocument(dir);
                SessionJournalEntry target = document.getEntries().stream()
                    .filter(entry -> entryId.equals(entry.getId()))
                    .findFirst()
                    .orElse(null);
                if (target == null) {
                    return;
                }
                Platform.runLater(() -> {
                    if (dir.equals(journalDir)) {
                        promptForMarker(document, target);
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not load the journal entry {}: {}", entryId, e.getMessage());
            }
        }, "SessionJournal-MarkerPick");
        loader.setDaemon(true);
        loader.start();
    }

    private void promptForMarker(SessionJournalDocument document, SessionJournalEntry target) {
        java.util.List<de.kortty.model.SessionJournalMarkerDefinition> registry = markerRegistry();
        de.kortty.model.SessionJournalMarkerDefinition current = de.kortty.core.SessionJournalMarkers
            .byId(de.kortty.core.SessionJournalMarkers.resolve(target, document).getId(), registry);

        ChoiceDialog<de.kortty.model.SessionJournalMarkerDefinition> dialog =
            new ChoiceDialog<>(current != null ? current : registry.get(0), registry);
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(ownerWindow());
        dialog.setTitle(I18n.get("journal.viewer.marker"));
        dialog.setHeaderText(target.getTitle() != null && !target.getTitle().isBlank()
            ? target.getTitle() : I18n.get("journal.viewer.marker"));
        dialog.setContentText(I18n.get("journal.viewer.marker"));

        de.kortty.model.SessionJournalMarkerDefinition chosen = dialog.showAndWait().orElse(null);
        if (chosen == null) {
            return;
        }
        SessionJournalEntry updated = new SessionJournalEntry(target);
        de.kortty.core.SessionJournalMarkers.apply(updated, chosen);
        updated.setMarkerSource(SessionJournalEntry.MarkerSource.USER);
        Path dir = journalDir;
        Thread saver = new Thread(() -> {
            try {
                service().updateEntry(dir, updated);
                Platform.runLater(() -> {
                    if (!dir.equals(journalDir)) {
                        return;
                    }
                    loadEntries();
                    renderAndLoad(updated.getId());
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-MarkerSave");
        saver.setDaemon(true);
        saver.start();
    }

    /** Runs the auto-marker rules over this journal off the FX thread. */
    private void applyMarkerRules(SessionJournalMarkerDialog.ApplyRequest request) {
        Path dir = journalDir;
        Thread worker = new Thread(() -> {
            try {
                int changed = service().applyMarkerRules(dir, request.overwriteManual());
                Platform.runLater(() -> {
                    request.onChanged().accept(changed);
                    if (changed > 0 && dir.equals(journalDir)) {
                        loadEntries();
                        renderAndLoad(null);
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not apply the journal marker rules: {}", e.getMessage());
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-MarkerRules");
        worker.setDaemon(true);
        worker.start();
    }

    /** The definition an entry shows, resolved from the journal's own snapshot. */
    private de.kortty.model.SessionJournalMarkerDefinition resolveMarker(SessionJournalEntry entry) {
        return de.kortty.core.SessionJournalMarkers.resolve(entry, loadedDocument);
    }

    /** Built-ins plus the user's own markers, for the picker. */
    private java.util.List<de.kortty.model.SessionJournalMarkerDefinition> markerRegistry() {
        return de.kortty.core.SessionJournalMarkers.registry(settings());
    }

    private void showEntryInForm(SessionJournalEntry entry) {
        de.kortty.model.SessionJournalMarkerDefinition current = entry != null
            ? de.kortty.core.SessionJournalMarkers.byId(resolveMarker(entry).getId(), markerCombo.getItems())
            : null;
        markerCombo.setValue(current != null
            ? current
            : de.kortty.core.SessionJournalMarkers.byId("none", markerCombo.getItems()));
        titleField.setText(entry != null && entry.getTitle() != null ? entry.getTitle() : "");
        summaryArea.setText(entry != null && entry.getText() != null ? entry.getText() : "");
        noteEditor.setText(entry != null && entry.getUserNote() != null ? entry.getUserNote() : "");
    }

    private void toggleEditMode() {
        if (mode != Mode.FULL) {
            return;
        }
        if (editToggle.isSelected()) {
            if (editSplit == null) {
                editSplit = buildEditSplit();
            }
            centerPane.setCenter(editSplit);
            loadEntries();
        } else {
            if (editSplit != null) {
                editSplit.getItems().remove(webView);
            }
            centerPane.setCenter(webView);
        }
    }

    private SplitPane buildEditSplit() {
        if (entryTable == null) {
            entryTable = buildEntryTable();
        }
        markerCombo.setCellFactory(view -> new SessionJournalMarkerDialog.DefinitionListCell());
        markerCombo.setButtonCell(new SessionJournalMarkerDialog.DefinitionListCell());
        refreshMarkerRegistry();
        summaryArea.setPrefRowCount(4);
        summaryArea.setWrapText(true);

        Button saveButton = new Button(I18n.get("journal.viewer.save"));
        saveButton.disableProperty().bind(entryTable.getSelectionModel().selectedItemProperty().isNull());
        saveButton.setOnAction(event -> saveSelectedEntry());
        Button revertButton = new Button(I18n.get("journal.viewer.revert"));
        revertButton.disableProperty().bind(entryTable.getSelectionModel().selectedItemProperty().isNull());
        revertButton.setOnAction(event ->
            showEntryInForm(entryTable.getSelectionModel().getSelectedItem()));
        Button deleteButton = new Button(I18n.get("journal.viewer.deleteEntry"));
        deleteButton.disableProperty().bind(entryTable.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.setOnAction(event -> deleteSelectedEntry());
        Button replaceButton = new Button(I18n.get("journal.viewer.replace"));
        replaceButton.setOnAction(event -> replaceInJournal(null));
        editStatus.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");

        HBox buttons = new HBox(8, saveButton, revertButton, deleteButton, replaceButton, editStatus);
        VBox form = new VBox(6,
            new Label(I18n.get("journal.viewer.entryTitle")), titleField,
            new Label(I18n.get("journal.viewer.summary")), summaryArea,
            new Label(I18n.get("journal.viewer.marker")), markerRow(),
            new Label(I18n.get("journal.viewer.note")), noteEditor,
            buttons);
        form.setPadding(new Insets(6));

        VBox editorPane = new VBox(6, new Label(I18n.get("journal.viewer.entries")), entryTable, form);
        editorPane.setPadding(new Insets(6));
        VBox.setVgrow(entryTable, Priority.ALWAYS);

        SplitPane split = new SplitPane(webView, editorPane);
        Double savedDivider = settings() != null
            ? settings().getSessionJournalViewerEditDividerPosition() : null;
        split.setDividerPositions(savedDivider != null ? savedDivider : 0.62);
        split.getDividers().get(0).positionProperty().addListener((obs, old, position) -> {
            if (settings() != null) {
                settings().setSessionJournalViewerEditDividerPosition(position.doubleValue());
            }
        });
        return split;
    }

    private void loadEntries() {
        Path dir = journalDir;
        Thread loader = new Thread(() -> {
            try {
                SessionJournalDocument document = service().loadDocument(dir);
                List<SessionJournalEntry> loaded = document.getEntries();
                Platform.runLater(() -> {
                    if (!dir.equals(journalDir)) {
                        return;
                    }
                    // Kept so markers resolve from the journal's own snapshot, not the settings.
                    loadedDocument = document;
                    entries.setAll(loaded);
                    if (entryTable != null) {
                        entryTable.refresh();
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not load session journal entries: {}", e.getMessage());
            }
        }, "SessionJournal-ViewerEntries");
        loader.setDaemon(true);
        loader.start();
    }

    private void saveSelectedEntry() {
        SessionJournalEntry entry = entryTable.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        SessionJournalEntry updated = new SessionJournalEntry(entry);
        de.kortty.core.SessionJournalMarkers.apply(updated, markerCombo.getValue());
        // A user's marker choice must never be overwritten by AI regeneration.
        updated.setMarkerSource(SessionJournalEntry.MarkerSource.USER);
        String title = titleField.getText();
        updated.setTitle(title != null && !title.isBlank() ? title.strip() : null);
        String summary = summaryArea.getText();
        updated.setText(summary != null && !summary.isBlank() ? summary.strip() : null);
        String note = noteEditor.getText();
        updated.setUserNote(note != null && !note.isBlank() ? note.strip() : null);
        String anchorId = updated.getId();
        Path dir = journalDir;
        Thread saver = new Thread(() -> {
            try {
                service().updateEntry(dir, updated);
                Platform.runLater(() -> {
                    editStatus.setText(I18n.get("journal.viewer.saved"));
                    loadEntries();
                    renderAndLoad(anchorId);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-ViewerSave");
        saver.setDaemon(true);
        saver.start();
    }

    private void deleteSelectedEntry() {
        SessionJournalEntry entry = entryTable.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(ownerWindow());
        confirm.setTitle(I18n.get("journal.viewer.deleteEntry.title"));
        confirm.setHeaderText(I18n.get("journal.viewer.deleteEntry.header"));
        confirm.setContentText(I18n.get("journal.viewer.deleteEntry.content"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        String entryId = entry.getId();
        Path dir = journalDir;
        Thread worker = new Thread(() -> {
            try {
                service().deleteEntry(dir, entryId);
                Platform.runLater(() -> {
                    editStatus.setText(I18n.get("journal.viewer.deleteEntry.done"));
                    showEntryInForm(null);
                    loadEntries();
                    renderAndLoad(null);
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            }
        }, "SessionJournal-ViewerDeleteEntry");
        worker.setDaemon(true);
        worker.start();
    }

    /** What the search-and-replace dialog collected. */
    private record ReplaceRequest(SessionJournalReplacement rule, boolean includeLog) {
    }

    /**
     * Search and replace across the whole journal — how a password that slipped past the
     * capture-time protection gets erased, and how any other text is corrected after the fact.
     * The search text only ever lives in the dialog and the service call; it is never logged
     * or kept in a field, because for a redaction it IS the secret.
     */
    void replaceInJournal(String initialSearch) {
        Dialog<ReplaceRequest> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.initOwner(ownerWindow());
        dialog.setTitle(I18n.get("journal.viewer.replace.title"));
        dialog.setHeaderText(I18n.get("journal.viewer.replace.header"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK))
            .setText(I18n.get("journal.viewer.replace.apply"));

        TextField searchField = new TextField(initialSearch != null ? initialSearch : "");
        searchField.setPromptText(I18n.get("journal.viewer.replace.search.prompt"));
        TextField replacementField = new TextField(SessionJournalReplacement.DEFAULT_REPLACEMENT);
        CheckBox regexCheck = new CheckBox(I18n.get("journal.viewer.replace.regex"));
        CheckBox ignoreCaseCheck = new CheckBox(I18n.get("journal.viewer.replace.ignoreCase"));
        CheckBox includeLogCheck = new CheckBox(I18n.get("journal.viewer.replace.includeLog"));
        includeLogCheck.setSelected(true);

        Label countLabel = new Label();
        countLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        Button countButton = new Button(I18n.get("journal.viewer.replace.count"));
        countButton.disableProperty().bind(searchField.textProperty().isEmpty());

        Label warning = new Label(I18n.get("journal.viewer.replace.warning"));
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: #d29922; -fx-font-size: 0.8462em;");

        java.util.function.Supplier<ReplaceRequest> collect = () -> new ReplaceRequest(
            new SessionJournalReplacement(searchField.getText(), replacementField.getText(),
                regexCheck.isSelected(), ignoreCaseCheck.isSelected(), null),
            includeLogCheck.isSelected());
        // A dry run over the real journal: regex rules are easy to get wrong, and the
        // rewrite itself cannot be undone.
        countButton.setOnAction(event -> {
            ReplaceRequest request = collect.get();
            countLabel.setText(I18n.get("journal.viewer.replace.counting"));
            Path dir = journalDir;
            Thread counter = new Thread(() -> {
                try {
                    SessionJournalService.RedactionResult preview =
                        service().replace(dir, request.rule(), request.includeLog(), true);
                    Platform.runLater(() -> countLabel.setText(I18n.get(
                        "journal.viewer.replace.count.result",
                        preview.entryHits(), preview.logHits())));
                } catch (Exception e) {
                    Platform.runLater(() -> countLabel.setText(e.getMessage()));
                }
            }, "SessionJournal-ReplacePreview");
            counter.setDaemon(true);
            counter.start();
        });

        VBox content = new VBox(6,
            new Label(I18n.get("journal.viewer.replace.search")), searchField,
            new Label(I18n.get("journal.viewer.replace.replacement")), replacementField,
            regexCheck, ignoreCaseCheck, includeLogCheck,
            new HBox(8, countButton, countLabel),
            warning);
        appendPolicyReplacementHint(content);
        content.setPadding(new Insets(6));
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
            .bind(searchField.textProperty().isEmpty());
        Platform.runLater(searchField::requestFocus);
        dialog.setResultConverter(button -> button == ButtonType.OK ? collect.get() : null);

        ReplaceRequest request = dialog.showAndWait().orElse(null);
        if (request == null || request.rule().isEmpty()) {
            return;
        }
        applyReplacement(request.rule(), request.includeLog());
    }

    /** Names the organisation's automatic rules so a user is not surprised by them. */
    private void appendPolicyReplacementHint(VBox content) {
        List<SessionJournalReplacement> mandated = SessionJournalService.policyReplacements();
        if (mandated.isEmpty()) {
            return;
        }
        Label hint = new Label(I18n.get("journal.viewer.replace.policyHint", mandated.size()));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
        content.getChildren().add(hint);
    }

    /** Runs the rewrite off the FX thread, then reloads entries and regenerates the page. */
    private void applyReplacement(SessionJournalReplacement rule, boolean includeLog) {
        editStatus.setText(I18n.get("journal.viewer.replace.running"));
        Path dir = journalDir;
        Thread worker = new Thread(() -> {
            try {
                SessionJournalService.RedactionResult result =
                    service().replace(dir, rule, includeLog, false);
                Platform.runLater(() -> {
                    editStatus.setText("");
                    loadEntries();
                    showEntryInForm(null);
                    renderAndLoad(null);
                    showInfo(I18n.get("journal.viewer.replace.done",
                        result.entryHits(), result.logHits()));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    editStatus.setText("");
                    showError(I18n.get("journal.viewer.replace.error", e.getMessage()));
                });
            }
        }, "SessionJournal-ViewerReplace");
        worker.setDaemon(true);
        worker.start();
    }

    // ==== export ====

    private void exportJournal(SessionJournalExportService.Format format) {
        exportJournal(format, List.of());
    }

    /**
     * Asks for the export options before the file chooser, so a cancelled save still zeroes the
     * password. {@code presetWindows} pre-fills the time windows, e.g. from a range the user
     * marked in the timeline.
     */
    private void exportJournal(SessionJournalExportService.Format format,
                               List<de.kortty.core.SessionJournalExportFilter.TimeWindow> presetWindows) {
        Path dir = journalDir;
        boolean archive = format == SessionJournalExportService.Format.HTML_BUNDLE;
        SessionJournalExportOptionsDialog.ExportChoice choice = SessionJournalExportOptionsDialog.ask(
            service(),
            new SessionJournalExportOptionsDialog.Request(format, List.of(dir), archive,
                ownerWindow(), presetWindows, this::startRangeSelection))
            .orElse(null);
        if (choice == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("journal.export.title"));
        chooser.setInitialFileName("session-journal" + format.getExtension());
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(I18n.get(format.getFilterKey()), "*" + format.getExtension()));
        File target = chooser.showSaveDialog(ownerWindow());
        if (target == null) {
            zero(choice.password());
            return;
        }
        Thread exporter = new Thread(() -> {
            try {
                SessionJournalExportService.ExportResult result =
                    new SessionJournalExportService(service(), renderer()).export(format, dir,
                        target.toPath(),
                        new SessionJournalExportService.Options(choice.includeScreenshots(), choice.filter()),
                        choice.password());
                Platform.runLater(() -> {
                    if (result.aiSelectionWarning() != null && !result.aiSelectionWarning().isBlank()) {
                        showInfo(result.aiSelectionWarning());
                    }
                    showInfo(I18n.get("journal.export.done", target.getAbsolutePath()));
                });
            } catch (Exception e) {
                logger.error("Session journal export failed: {}", e.getMessage(), e);
                Platform.runLater(() -> showError(I18n.get("journal.export.error", e.getMessage())));
            } finally {
                zero(choice.password());
            }
        }, "SessionJournal-ViewerExport");
        exporter.setDaemon(true);
        exporter.start();
    }

    private static void zero(char[] password) {
        if (password != null) {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * Reopens the export dialog with the marked ranges pre-filled. The format is chosen again
     * because a range says nothing about it; PDF is the common case and stays the default.
     */
    private void openExportWithWindows(String windowsJson) {
        List<de.kortty.core.SessionJournalExportFilter.TimeWindow> windows = parseWindows(windowsJson);
        if (windows.isEmpty()) {
            return;
        }
        exportJournal(SessionJournalExportService.Format.PDF, windows);
    }

    /** Parses the bridge payload; anything unparsable is skipped rather than failing the dialog. */
    private static List<de.kortty.core.SessionJournalExportFilter.TimeWindow> parseWindows(String json) {
        List<de.kortty.core.SessionJournalExportFilter.TimeWindow> windows = new java.util.ArrayList<>();
        if (json == null || json.isBlank()) {
            return windows;
        }
        try {
            com.google.gson.JsonArray array =
                com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement element : array) {
                com.google.gson.JsonObject object = element.getAsJsonObject();
                java.time.OffsetDateTime from = parseInstant(object, "from");
                java.time.OffsetDateTime to = parseInstant(object, "to");
                if (from == null || to == null) {
                    continue;
                }
                ZoneId zone = ZoneId.systemDefault();
                java.time.ZonedDateTime start = from.atZoneSameInstant(zone);
                java.time.ZonedDateTime end = to.atZoneSameInstant(zone);
                windows.add(new de.kortty.core.SessionJournalExportFilter.TimeWindow(
                    start.toLocalDate(), start.toLocalTime().withSecond(0).withNano(0),
                    end.toLocalDate(), end.toLocalTime().withSecond(0).withNano(0)));
            }
        } catch (RuntimeException e) {
            logger.warn("Could not read the marked journal range: {}", e.getMessage());
        }
        return windows;
    }

    private static java.time.OffsetDateTime parseInstant(com.google.gson.JsonObject object, String key) {
        try {
            return object.has(key)
                ? java.time.OffsetDateTime.parse(object.get(key).getAsString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Switches the page into range-selection mode; the selection comes back through the bridge. */
    private void startRangeSelection() {
        if (disposed) {
            return;
        }
        // The page only offers the mode when the bridge answered, so guard the call as well.
        Platform.runLater(() -> {
            try {
                webView.getEngine().executeScript(
                    "if(window.korttyStartRange){window.korttyStartRange();}");
            } catch (Exception e) {
                logger.debug("Could not start the journal range selection: {}", e.getMessage());
            }
        });
    }

    // ==== lifecycle ====

    /**
     * Detaches from the service and unloads the page. WebKit engines leak native memory unless the
     * page is explicitly unloaded; idempotent because DIALOG_HIDDEN fires twice when the dialog
     * shell is hosted in a tab.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (askPanel != null) {
            askPanel.dispose();
            askPanel = null;
        }
        detachLiveSession();
        documentReloadDelay.stop();
        if (service() != null) {
            service().removeChangeListener(changeListener);
        }
        webView.getEngine().loadContent("");
        try {
            var settingsManager = app != null ? app.getGlobalSettingsManager() : null;
            if (settingsManager != null) {
                settingsManager.save();
            }
        } catch (Exception ignored) {
        }
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("journal.manager.title"));
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("journal.manager.title"));
        alert.setContentText(message);
        alert.showAndWait();
    }
}
