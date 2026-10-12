package de.kortty.ui;

import com.sithtermfx.core.emulator.ColorPalette;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.KorttyClipboard;
import de.kortty.core.LogfileLineStyler;
import de.kortty.core.TerminalLogFile;
import de.kortty.core.TerminalLogLineIndex;
import de.kortty.core.TerminalLogNaming;
import de.kortty.core.TerminalLogReader;
import de.kortty.core.TerminalLogScratch;
import de.kortty.core.TerminalLogSearch;
import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.HighlightMatcher;
import de.kortty.core.highlight.TerminalHighlightService;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRuleSet;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableListBase;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Read-only viewer for one terminal log file, opened from the Logfile Manager.
 *
 * <p>The file is never loaded whole: the line index is built once, and the rows fetch their text
 * a page at a time, so a multi-gigabyte log opens at once and scrolls without holding it in memory.
 * Rows are coloured with the same link and highlight rules the terminal uses, so what the viewer
 * calls a link is what the terminal calls one.</p>
 *
 * <p>Everything the rows read is touched on the FX thread only. File I/O runs on two single-thread
 * executors: one for page loads and copies, one for searches. Keeping them apart means a search
 * over a large file never holds up the rows the user is looking at.</p>
 */
public final class LogfileViewerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(LogfileViewerDialog.class);

    /** Lines per page: the unit the reader is asked for and the cache keeps. */
    static final int PAGE_LINES = 256;
    /** Pages kept in memory; a screen of rows plus scrolling back and forth fits comfortably. */
    static final int MAX_PAGES = 16;
    /** Most lines a selection copy takes; more would put tens of megabytes on the clipboard. */
    static final int COPY_LIMIT = 2000;
    /** Rows whose link and keyword spans are kept; old rows are recomputed when scrolled back to. */
    private static final int SPAN_CACHE_LINES = 2048;

    private static final String MEASURE_TEXT = "MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM";
    /** Row height as a multiple of the font size; leaves room for the underline and the ascent. */
    private static final double CELL_HEIGHT_FACTOR = 1.45;
    private static final double CELL_PADDING = 6;
    private static final double GUTTER_PADDING = 8;
    private static final long STATUS_SECONDS = 4;
    private static final long FIND_DEBOUNCE_MILLIS = 300;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    // No theme variables here: the Normal design (Modena) knows no AtlantaFX "-color-*" tokens and
    // AtlantaFX knows no Modena "-fx-*" colour tokens, so text takes the cell's own text fill (which
    // every theme sets, and which follows selection) and the few accents are literal colours that
    // read on light and dark backgrounds — the same ones the toolbar glyphs and journal badges use.
    private static final Color LINK_FILL = Color.web("#368af5");
    private static final Color FIND_FILL = Color.web("#f59e0b", 0.35);
    private static final Color FIND_CURRENT_FILL = Color.web("#f59e0b", 0.75);
    private static final double MUTED_OPACITY = 0.6;
    private static final String STATUS_STYLE = "-fx-text-fill: gray;";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final TerminalLogFile file;
    private final GlobalSettingsManager settings;
    private final TerminalHighlightService highlights;
    private final Consumer<Path> onClosed;
    private final ExecutorService pageExecutor = newWorker("LogfileViewer-pages-");
    private final ExecutorService searchExecutor = newWorker("LogfileViewer-search-");
    private final AtomicBoolean disposed = new AtomicBoolean();
    /** Bumped by every search or count; a result that finds the token moved on is dropped. */
    private final AtomicLong searchToken = new AtomicLong();

    /** Indexed by {@code (bold ? 1 : 0) + (italic ? 2 : 0)}. */
    private final Font[] fonts = new Font[4];
    private final double charWidth;
    private final double cellHeight;
    private final ColorPalette palette;
    private final DoubleProperty xOffset = new SimpleDoubleProperty();
    private final PageCache pages = new PageCache();
    /** Link and keyword spans per line, valid while their style epoch is current. */
    private final Map<Integer, SpanEntry> spanCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, SpanEntry> eldest) {
            return size() > SPAN_CACHE_LINES;
        }
    };

    private final ListView<Integer> listView;
    private final ScrollBar horizontalBar = new ScrollBar();
    private final Label infoLabel = new Label();
    private final Label snapshotLabel = new Label();
    private final Button refreshButton = new Button();
    private final CheckBox linksBox = new CheckBox();
    private final CheckBox keywordsBox = new CheckBox();
    private final Label keywordSetLabel = new Label();
    private final ComboBox<String> keywordSet = new ComboBox<>();
    private final Button findButton = new Button();
    private final TextField goToField = new TextField();
    private final HBox findBar = new HBox();
    private final TextField findField = new TextField();
    private final Button previousButton = new Button();
    private final Button nextButton = new Button();
    private final Label hitsLabel = new Label();
    private final Button closeFindButton = new Button();
    private final PauseTransition findDebounce = new PauseTransition(Duration.millis(FIND_DEBOUNCE_MILLIS));
    private final StackPane contentStack = new StackPane();
    private final VBox openingPane = new VBox();
    private final ProgressIndicator openingIndicator = new ProgressIndicator();
    private final Label openingLabel = new Label();
    private final VBox rotatedPane = new VBox();
    private final Label statusLabel = new Label();
    private final PauseTransition statusTimer = new PauseTransition(Duration.seconds(STATUS_SECONDS));

    private final List<String> keywordSetIds;
    private boolean linksOn;
    private boolean keywordsOn;
    private String keywordSetId;
    private CompiledHighlightSet activeSet;
    private int styleEpoch;
    /** The find text the rows highlight; empty when the find bar is closed. */
    private String findNeedle = "";
    private int currentHitLine = -1;
    private int currentHitColumn = -1;
    private double gutterWidth;

    private Opened opened;
    private Task<Opened> openTask;
    private LocalTime openedAt;
    /** The first visible line to bring back into view once a refresh has indexed the file again. */
    private int restoreLine = -1;

    /**
     * The state of one successful open: the readable copy (the original, or its unpacked scratch
     * copy), its line index, the reader over it, and the scratch folder to delete on close.
     */
    private record Opened(Path readable, TerminalLogLineIndex index, TerminalLogReader reader,
                          Path scratchDir, long snapshotLength) {
    }

    private record SpanEntry(int epoch, List<LogfileLineStyler.Span> spans) {
    }

    /**
     * @param file the log file to show, as the Logfile Manager lists it
     * @param settings the settings the viewer reads its highlight choices from and saves them to;
     *                 may be {@code null} in a smoke, which then uses the built-in defaults
     * @param highlights the highlight rule sets, including the user's; may be {@code null}, in which
     *                   case only the built-in sets are offered
     * @param onClosed receives the file path once when the viewer goes away, so the owner forgets it
     */
    public LogfileViewerDialog(TerminalLogFile file, GlobalSettingsManager settings,
                               TerminalHighlightService highlights, Consumer<Path> onClosed) {
        super();
        this.file = Objects.requireNonNull(file, "file");
        this.settings = settings;
        this.highlights = highlights;
        this.onClosed = onClosed != null ? onClosed : path -> { };

        ConnectionSettings defaults = terminalDefaults(settings);
        String family = monospaceFamily(defaults.getFontFamily());
        double size = defaults.getFontSize() > 0 ? defaults.getFontSize() : Font.getDefault().getSize();
        fonts[0] = Font.font(family, size);
        fonts[1] = Font.font(family, FontWeight.BOLD, FontPosture.REGULAR, size);
        fonts[2] = Font.font(family, FontWeight.NORMAL, FontPosture.ITALIC, size);
        fonts[3] = Font.font(family, FontWeight.BOLD, FontPosture.ITALIC, size);
        Text probe = new Text(MEASURE_TEXT);
        probe.setFont(fonts[0]);
        this.charWidth = probe.getLayoutBounds().getWidth() / MEASURE_TEXT.length();
        this.cellHeight = Math.ceil(fonts[0].getSize() * CELL_HEIGHT_FACTOR);
        this.palette = LogfileColors.palette(defaults);

        keywordSetIds = keywordSetIds();
        readHighlightSettings();

        setTitle(I18n.get("logfile.viewer.title", file.name()));
        initModality(Modality.NONE);
        setResizable(true);

        buildHeader();
        buildFindBar();
        listView = buildList();
        clampHorizontal();
        buildOverlays();
        buildStatus();

        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(4, 12, 6, 12));
        VBox centre = new VBox(contentStack, horizontalBar);
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        BorderPane root = new BorderPane();
        root.setTop(new VBox(headerRow(), findBar));
        root.setCenter(centre);
        root.setBottom(statusRow);

        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(1100, 700);
        getDialogPane().setMinSize(700, 440);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::onDialogKey);
        // DIALOG_HIDDEN fires twice when the viewer is hosted in a tab, so the teardown is idempotent.
        setOnHidden(event -> dispose());

        startOpening();
    }

    // ----------------------------------------------------------------------------------------------
    // Construction helpers
    // ----------------------------------------------------------------------------------------------

    private static ExecutorService newWorker(String prefix) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, prefix + SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    private static ConnectionSettings terminalDefaults(GlobalSettingsManager settings) {
        GlobalSettings current = settings != null ? settings.getSettings() : null;
        ConnectionSettings defaults = current != null ? current.getDefaultTerminalSettings() : null;
        return defaults != null ? defaults : new ConnectionSettings();
    }

    /** The terminal's font family when the system has it, else the logical monospace family. */
    private static String monospaceFamily(String configured) {
        if (configured == null || configured.isBlank() || !Font.getFamilies().contains(configured)) {
            return "Monospaced";
        }
        return configured;
    }

    private void readHighlightSettings() {
        GlobalSettings current = settings != null ? settings.getSettings() : null;
        linksOn = current == null || current.isLogfileViewerHighlightLinks();
        keywordsOn = current == null || current.isLogfileViewerHighlightKeywords();
        String saved = current != null ? current.getLogfileViewerKeywordSetId() : null;
        keywordSetId = saved != null && keywordSetIds.contains(saved) ? saved : HighlightBuiltinSets.ERRORS;
        activeSet = compiledSet(keywordSetId);
    }

    private List<String> keywordSetIds() {
        if (highlights != null) {
            return highlights.setIds();
        }
        return HighlightBuiltinSets.all().stream().map(HighlightRuleSet::getId).toList();
    }

    private CompiledHighlightSet compiledSet(String id) {
        if (id == null) {
            return null;
        }
        if (highlights != null) {
            return highlights.compiledSet(id);
        }
        HighlightRuleSet builtin = HighlightBuiltinSets.byId(id);
        return builtin != null ? CompiledHighlightSet.compile(builtin) : null;
    }

    private String setName(String id) {
        if (HighlightBuiltinSets.isBuiltin(id)) {
            return I18n.get(HighlightBuiltinSets.nameKey(id));
        }
        String name = highlights != null ? highlights.userSetName(id) : null;
        return name != null && !name.isBlank() ? name : id;
    }

    private void buildHeader() {
        boolean live = file.live();
        infoLabel.setText("");
        snapshotLabel.setVisible(live);
        snapshotLabel.setManaged(live);

        ButtonIcons.apply(refreshButton, ButtonIcons.REFRESH);
        refreshButton.setText(I18n.get("logfile.viewer.refresh"));
        refreshButton.setVisible(live);
        refreshButton.setManaged(live);
        refreshButton.setOnAction(event -> {
            restoreLine = topLine();
            startOpening();
        });

        linksBox.setText(I18n.get("logfile.viewer.highlightLinks"));
        linksBox.setSelected(linksOn);
        linksBox.setOnAction(event -> {
            linksOn = linksBox.isSelected();
            persist(s -> s.setLogfileViewerHighlightLinks(linksOn));
            styleChanged();
        });

        keywordsBox.setText(I18n.get("logfile.viewer.highlightKeywords"));
        keywordsBox.setSelected(keywordsOn);
        keywordsBox.setOnAction(event -> {
            keywordsOn = keywordsBox.isSelected();
            keywordSet.setDisable(!keywordsOn);
            persist(s -> s.setLogfileViewerHighlightKeywords(keywordsOn));
            styleChanged();
        });

        keywordSetLabel.setText(I18n.get("logfile.viewer.keywordSet"));
        keywordSet.setItems(FXCollections.observableArrayList(keywordSetIds));
        keywordSet.setCellFactory(list -> new SetNameCell());
        keywordSet.setButtonCell(new SetNameCell());
        keywordSet.setValue(keywordSetId);
        keywordSet.setDisable(!keywordsOn);
        keywordSet.setOnAction(event -> {
            String chosen = keywordSet.getValue();
            if (chosen == null || chosen.equals(keywordSetId)) {
                return;
            }
            keywordSetId = chosen;
            activeSet = compiledSet(chosen);
            persist(s -> s.setLogfileViewerKeywordSetId(chosen));
            styleChanged();
        });

        ButtonIcons.apply(findButton, ButtonIcons.SEARCH);
        findButton.setText(I18n.get("logfile.viewer.find"));
        findButton.setOnAction(event -> toggleFindBar());

        goToField.setPromptText(I18n.get("logfile.viewer.goToLine.prompt"));
        goToField.setPrefWidth(80);
        goToField.setOnAction(event -> goToLineFromField());
    }

    private FlowPane headerRow() {
        Button goToButton = new Button(I18n.get("logfile.viewer.goToLine"));
        goToButton.setOnAction(event -> goToLineFromField());
        FlowPane header = new FlowPane(8, 6,
            infoLabel, snapshotLabel, refreshButton, linksBox, keywordsBox, keywordSetLabel, keywordSet,
            findButton, goToField, goToButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 4, 12));
        return header;
    }

    private void buildFindBar() {
        findField.setPromptText(I18n.get("logfile.viewer.find.prompt"));
        findField.setTooltip(new Tooltip(I18n.get("logfile.viewer.findHint")));
        HBox.setHgrow(findField, Priority.ALWAYS);
        findField.textProperty().addListener((obs, before, after) -> findDebounce.playFromStart());
        findField.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case ENTER -> {
                    findStep(!event.isShiftDown());
                    event.consume();
                }
                case ESCAPE -> {
                    hideFindBar();
                    event.consume();
                }
                default -> {
                }
            }
        });
        findDebounce.setOnFinished(event -> runCount());

        ButtonIcons.apply(previousButton, ButtonIcons.CHEVRON_LEFT);
        previousButton.setTooltip(new Tooltip(I18n.get("logfile.viewer.previous")));
        previousButton.setOnAction(event -> findStep(false));
        ButtonIcons.apply(nextButton, ButtonIcons.CHEVRON_RIGHT);
        nextButton.setTooltip(new Tooltip(I18n.get("logfile.viewer.next")));
        nextButton.setOnAction(event -> findStep(true));

        ButtonIcons.apply(closeFindButton, ButtonIcons.CANCEL);
        closeFindButton.setTooltip(new Tooltip(I18n.get("logfile.viewer.close")));
        closeFindButton.setOnAction(event -> hideFindBar());

        findBar.getChildren().setAll(findField, previousButton, nextButton, hitsLabel, closeFindButton);
        findBar.setSpacing(8);
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.setPadding(new Insets(0, 12, 8, 12));
        findBar.setVisible(false);
        findBar.setManaged(false);
    }

    private ListView<Integer> buildList() {
        ListView<Integer> list = new ListView<>(new LineList(0));
        list.setFixedCellSize(cellHeight);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(view -> new LogLineCell());
        // Select-all would select every line of a multi-gigabyte file; copying a selection is bounded.
        list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.A) {
                event.consume();
            }
        });
        list.addEventFilter(ScrollEvent.SCROLL, this::onListScroll);
        // A cell paints only the columns its width shows, so a resize has to repaint the rows.
        list.widthProperty().addListener((obs, before, after) -> {
            clampHorizontal();
            list.refresh();
        });
        xOffset.addListener((obs, before, after) -> list.refresh());
        horizontalBar.setOrientation(Orientation.HORIZONTAL);
        horizontalBar.setMaxWidth(Double.MAX_VALUE);
        horizontalBar.valueProperty().bindBidirectional(xOffset);
        return list;
    }

    private void buildOverlays() {
        openingLabel.setText(I18n.get("logfile.viewer.placeholder"));
        openingPane.getChildren().setAll(openingIndicator, openingLabel);
        openingPane.setSpacing(12);
        openingPane.setAlignment(Pos.CENTER);
        // The overlays cover the list with the dialog's own background, whatever the theme paints it.
        openingPane.backgroundProperty().bind(getDialogPane().backgroundProperty());

        Label rotatedLabel = new Label(I18n.get("logfile.viewer.rotated"));
        Button reload = new Button(I18n.get("logfile.viewer.reload"));
        ButtonIcons.apply(reload, ButtonIcons.REFRESH);
        reload.setOnAction(event -> {
            restoreLine = topLine();
            startOpening();
        });
        rotatedPane.getChildren().setAll(rotatedLabel, reload);
        rotatedPane.setSpacing(12);
        rotatedPane.setAlignment(Pos.CENTER);
        rotatedPane.backgroundProperty().bind(getDialogPane().backgroundProperty());
        rotatedPane.setVisible(false);

        contentStack.getChildren().setAll(listView, openingPane, rotatedPane);
    }

    private void buildStatus() {
        statusLabel.setStyle(STATUS_STYLE);
        statusTimer.setOnFinished(event -> statusLabel.setText(""));
    }

    private void onDialogKey(KeyEvent event) {
        if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
            showFindBar();
            event.consume();
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Opening, refreshing and closing
    // ----------------------------------------------------------------------------------------------

    /**
     * Indexes the file on a daemon thread. A live file is indexed up to the length it has now, so a
     * refresh shows a snapshot that is consistent with the line numbers already on screen.
     */
    private void startOpening() {
        if (disposed.get()) {
            return;
        }
        rotatedPane.setVisible(false);
        showOpening(true);
        openingLabel.setText(I18n.get("logfile.viewer.placeholder"));
        Task<Opened> task = new Task<>() {
            @Override
            protected Opened call() throws Exception {
                return openLog(this::updateMessage, this::isCancelled);
            }
        };
        openTask = task;
        task.messageProperty().addListener((obs, before, message) -> {
            if (message != null && !message.isBlank() && task == openTask) {
                openingLabel.setText(message);
            }
        });
        task.setOnSucceeded(event -> onOpened(task, task.getValue()));
        task.setOnFailed(event -> onOpenFailed(task, task.getException()));
        Thread thread = new Thread(task, "LogfileViewerOpen-" + SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The blocking part of opening, off the FX thread. A compressed file is unpacked into a private
     * scratch folder first; a plain live file is read in place, with no channel held open, so the
     * logger can still compress it when it rotates.
     */
    private Opened openLog(Consumer<String> progress, BooleanSupplier cancelled) throws IOException {
        Path source = resolveSource();
        TerminalLogNaming.Compression compression =
            TerminalLogNaming.Compression.ofName(source.getFileName().toString());
        Path scratch = null;
        TerminalLogReader reader = null;
        boolean complete = false;
        try {
            Path readable = source;
            if (compression != TerminalLogNaming.Compression.NONE) {
                scratch = TerminalLogScratch.create();
                progress.accept(I18n.get("logfile.viewer.decompressing", file.name()));
                readable = TerminalLogScratch.decompress(source, scratch, null, cancelled);
            }
            long length = Files.size(readable);
            TerminalLogLineIndex index = TerminalLogLineIndex.build(readable, length,
                scanned -> progress.accept(I18n.get("logfile.viewer.indexing", percent(scanned, length))),
                cancelled);
            if (cancelled.getAsBoolean()) {
                // A cancel that lands after the index is complete must still release everything
                // through the finally block, not hand back a state nobody will close.
                throw new TerminalLogScratch.CancelledException("Opening " + file.name() + " was cancelled");
            }
            reader = TerminalLogReader.open(readable, index, scratch != null);
            if (scratch != null) {
                TerminalLogScratch.register(scratch, reader);
            }
            complete = true;
            return new Opened(readable, index, reader, scratch, length);
        } finally {
            if (!complete) {
                closeQuietly(reader);
                if (scratch != null) {
                    TerminalLogScratch.delete(scratch);
                }
            }
        }
    }

    /**
     * The file to read. The Logfile Manager lists the original, but the logger may have rotated it
     * into an archive since, so a missing original falls back to the archive with the same name.
     */
    private Path resolveSource() {
        Path plain = file.path();
        if (Files.exists(plain)) {
            return plain;
        }
        for (TerminalLogNaming.Compression compression : TerminalLogNaming.Compression.values()) {
            if (compression == TerminalLogNaming.Compression.NONE) {
                continue;
            }
            Path archive = plain.resolveSibling(plain.getFileName() + compression.suffix);
            if (Files.isRegularFile(archive)) {
                return archive;
            }
        }
        return plain;
    }

    private void onOpened(Task<Opened> task, Opened next) {
        if (task != openTask || disposed.get()) {
            releaseAsync(next);
            return;
        }
        Opened previous = opened;
        opened = next;
        searchToken.incrementAndGet();
        int count = next.index().lineCount();
        listView.setItems(new LineList(count));
        pages.reset(next.reader());
        spanCache.clear();
        currentHitLine = -1;
        currentHitColumn = -1;
        gutterWidth = digits(count) * charWidth + GUTTER_PADDING;
        openedAt = LocalTime.now().withNano(0);
        updateInfo(count);
        snapshotLabel.setText(file.live() ? I18n.get("logfile.viewer.snapshot", CLOCK.format(openedAt)) : "");
        showOpening(false);
        if (restoreLine >= 0 && count > 0) {
            listView.scrollTo(Math.min(restoreLine, count - 1));
        }
        restoreLine = -1;
        listView.refresh();
        if (findBar.isVisible() && !findField.getText().isEmpty()) {
            runCount();
        }
        releaseAsync(previous);
    }

    private void onOpenFailed(Task<Opened> task, Throwable error) {
        if (task != openTask || disposed.get()) {
            return;
        }
        showOpening(false);
        String message = error != null && error.getMessage() != null ? error.getMessage() : String.valueOf(error);
        logger.warn("Could not open the log file {}: {}", file.name(), message);
        showErrorAlert(I18n.get("logfile.viewer.error", message));
        closeDialogOrHostTab();
    }

    private void showErrorAlert(String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        DialogThemeHelper.applyTheme(alert);
        Window owner = ownerWindow();
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(I18n.get("error.title"));
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private Window ownerWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    /**
     * Teardown, run once however often the dialog reports itself hidden: cancels the work in
     * flight, stops both executors, releases the reader and deletes the scratch copy, then tells
     * the owner. The file deletion happens off the FX thread.
     */
    private void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        Task<Opened> task = openTask;
        if (task != null) {
            task.cancel();
        }
        searchToken.incrementAndGet();
        pageExecutor.shutdownNow();
        searchExecutor.shutdownNow();
        Opened current = opened;
        opened = null;
        releaseAsync(current);
        try {
            onClosed.accept(file.path());
        } catch (RuntimeException e) {
            logger.warn("The log viewer owner could not be told it closed: {}", e.getMessage());
        }
    }

    private static void releaseAsync(Opened held) {
        if (held == null) {
            return;
        }
        Thread thread = new Thread(() -> release(held), "LogfileViewerRelease-" + SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
    }

    private static void release(Opened held) {
        if (held.scratchDir() != null) {
            TerminalLogScratch.unregister(held.scratchDir(), held.reader());
        }
        closeQuietly(held.reader());
        if (held.scratchDir() != null) {
            TerminalLogScratch.delete(held.scratchDir());
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            logger.debug("Closing the log reader failed: {}", e.getMessage());
        }
    }

    private void showOpening(boolean opening) {
        openingPane.setVisible(opening);
        refreshButton.setDisable(opening);
    }

    private void showRotated() {
        pages.halt();
        showOpening(false);
        rotatedPane.setVisible(true);
    }

    private void updateInfo(int count) {
        NumberFormat megabytes = NumberFormat.getNumberInstance();
        megabytes.setMinimumFractionDigits(1);
        megabytes.setMaximumFractionDigits(1);
        infoLabel.setText(I18n.get("logfile.viewer.info",
            NumberFormat.getIntegerInstance().format(count), megabytes.format(file.sizeMb())));
    }

    static int percent(long done, long total) {
        return total <= 0 ? 100 : (int) Math.min(100L, done * 100L / total);
    }

    private static int digits(int count) {
        return String.valueOf(Math.max(1, count)).length() + 1;
    }

    // ----------------------------------------------------------------------------------------------
    // Highlight choices
    // ----------------------------------------------------------------------------------------------

    private void persist(Consumer<GlobalSettings> change) {
        if (settings == null) {
            return;
        }
        GlobalSettings current = settings.getSettings();
        if (current == null) {
            return;
        }
        change.accept(current);
        settings.saveAsync();
    }

    /** A change of colours or sets: nothing is re-indexed, the cached spans are simply stale. */
    private void styleChanged() {
        styleEpoch++;
        listView.refresh();
    }

    /** The name of a set in the picker; built-ins are translated, user sets carry their own name. */
    private final class SetNameCell extends ListCell<String> {
        @Override
        protected void updateItem(String id, boolean empty) {
            super.updateItem(id, empty);
            setText(empty || id == null ? null : setName(id));
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Horizontal scrolling
    // ----------------------------------------------------------------------------------------------

    /** The width the line text really gets: the list minus the gutter and the cell padding. */
    private double clipWidth() {
        return Math.max(0, listView.getWidth() - gutterWidth - 2 * CELL_PADDING);
    }

    private double maxOffset() {
        return Math.max(0, TerminalLogReader.DISPLAY_CHARS * charWidth - clipWidth());
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(maxOffset(), value));
    }

    private void clampHorizontal() {
        double max = maxOffset();
        horizontalBar.setMin(0);
        horizontalBar.setVisibleAmount(clipWidth());
        horizontalBar.setMax(max);
        if (xOffset.get() > max) {
            xOffset.set(max);
        }
    }

    /** Scrolls the columns so that {@code column} is on screen, a third of the way in. */
    private void revealColumn(int column) {
        double x = column * charWidth;
        double width = clipWidth();
        if (width <= 0) {
            return;
        }
        if (x < xOffset.get() || x >= xOffset.get() + width) {
            xOffset.set(clamp(x - width / 3));
        }
    }

    /** Shift-wheel, or a mostly horizontal trackpad gesture, moves the columns; the rest scrolls rows. */
    private void onListScroll(ScrollEvent event) {
        boolean horizontal = event.isShiftDown() || Math.abs(event.getDeltaX()) > Math.abs(event.getDeltaY());
        if (!horizontal) {
            return;
        }
        double delta = event.getDeltaX() != 0 ? event.getDeltaX() : event.getDeltaY();
        if (delta == 0) {
            return;
        }
        xOffset.set(clamp(xOffset.get() - delta));
        event.consume();
    }

    // ----------------------------------------------------------------------------------------------
    // Lines, spans and rendering
    // ----------------------------------------------------------------------------------------------

    /** The visible rows' index range, or {@code null} when no row is showing. FX thread only. */
    private int[] visibleRange() {
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (Node node : listView.lookupAll(".list-cell")) {
            if (node instanceof IndexedCell<?> cell && cell.isVisible() && !cell.isEmpty() && cell.getIndex() >= 0) {
                first = Math.min(first, cell.getIndex());
                last = Math.max(last, cell.getIndex());
            }
        }
        return last < 0 ? null : new int[] {first, last};
    }

    private int topLine() {
        int[] range = visibleRange();
        return range == null ? 0 : range[0];
    }

    private List<LogfileLineStyler.Span> styledSpans(int lineNo, String text) {
        List<LogfileLineStyler.Span> base = baseSpans(lineNo, text);
        if (findNeedle.isEmpty()) {
            return base;
        }
        int current = lineNo == currentHitLine ? currentHitColumn : -1;
        List<LogfileLineStyler.Span> find = LogfileLineStyler.findSpans(text, findNeedle, current);
        return find.isEmpty() ? base : LogfileLineStyler.merge(base, find);
    }

    /** Links and keyword spans of one row, cached per style epoch: the expensive part of a repaint. */
    private List<LogfileLineStyler.Span> baseSpans(int lineNo, String text) {
        SpanEntry cached = spanCache.get(lineNo);
        if (cached != null && cached.epoch() == styleEpoch) {
            return cached.spans();
        }
        List<LogfileLineStyler.Span> links = linksOn ? LogfileLineStyler.linkSpans(text) : List.of();
        List<LogfileLineStyler.Span> keywords = List.of();
        if (keywordsOn && activeSet != null) {
            long budget = Math.max(1, activeSet.size()) * HighlightMatcher.RULE_BUDGET_NANOS;
            keywords = LogfileLineStyler.keywordSpans(text, activeSet, System.nanoTime() + budget);
        }
        List<LogfileLineStyler.Span> spans = links.isEmpty() && keywords.isEmpty()
            ? List.of() : LogfileLineStyler.merge(links, keywords);
        spanCache.put(lineNo, new SpanEntry(styleEpoch, spans));
        return spans;
    }

    /** The rows: row {@code i} is line {@code i}, so the list never boxes the whole file. */
    private static final class LineList extends ObservableListBase<Integer> {
        private final int count;

        LineList(int count) {
            this.count = count;
        }

        @Override
        public Integer get(int index) {
            return index;
        }

        @Override
        public int size() {
            return count;
        }
    }

    /**
     * Pages of lines, cached least-recently-used. The cache lives on the FX thread; the page reads
     * run on the page executor and come back through {@link Platform#runLater}. A request for a page
     * that scrolled out of view while it waited is dropped, so a fast scroll does not queue reads
     * for rows nobody will see.
     */
    private final class PageCache {
        private final LinkedHashMap<Integer, TerminalLogReader.Line[]> cache =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, TerminalLogReader.Line[]> eldest) {
                    return size() > MAX_PAGES;
                }
            };
        private final Set<Integer> loading = new HashSet<>();
        private TerminalLogReader reader;
        private int generation;
        private boolean halted;
        private boolean refreshQueued;
        private volatile Set<Integer> wanted = Set.of();

        void reset(TerminalLogReader next) {
            generation++;
            reader = next;
            cache.clear();
            loading.clear();
            halted = false;
            wanted = Set.of();
        }

        /** Stops asking for pages after the file changed under the reader. */
        void halt() {
            halted = true;
        }

        /** The line, or {@code null} while its page is loading (the request is already sent). */
        TerminalLogReader.Line lineAt(int lineNo) {
            if (reader == null) {
                return null;
            }
            int page = lineNo / PAGE_LINES;
            TerminalLogReader.Line[] lines = cache.get(page);
            if (lines == null) {
                if (!halted) {
                    request(page);
                }
                return null;
            }
            int at = lineNo - page * PAGE_LINES;
            return at < lines.length ? lines[at] : new TerminalLogReader.Line(lineNo, "", false);
        }

        private void request(int page) {
            if (!loading.add(page)) {
                return;
            }
            Set<Integer> keep = new HashSet<>();
            keep.add(page);
            int[] range = visibleRange();
            if (range != null) {
                for (int p = Math.max(0, range[0] / PAGE_LINES - 1); p <= range[1] / PAGE_LINES + 1; p++) {
                    keep.add(p);
                }
            }
            wanted = Set.copyOf(keep);
            int gen = generation;
            TerminalLogReader source = reader;
            submit(pageExecutor, () -> load(gen, source, page));
        }

        private void load(int gen, TerminalLogReader source, int page) {
            if (!wanted.contains(page)) {
                Platform.runLater(() -> {
                    if (gen == generation) {
                        loading.remove(page);
                    }
                });
                return;
            }
            try {
                TerminalLogReader.Line[] lines =
                    source.readPage(page * PAGE_LINES, PAGE_LINES).toArray(new TerminalLogReader.Line[0]);
                Platform.runLater(() -> loaded(gen, page, lines));
            } catch (TerminalLogReader.RotatedException e) {
                Platform.runLater(() -> failed(gen, page, e));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> failed(gen, page, e));
            }
        }

        private void loaded(int gen, int page, TerminalLogReader.Line[] lines) {
            if (gen != generation || disposed.get()) {
                return;
            }
            loading.remove(page);
            cache.put(page, lines);
            scheduleRefresh();
        }

        private void failed(int gen, int page, Exception error) {
            if (gen != generation || disposed.get()) {
                return;
            }
            loading.remove(page);
            if (error instanceof TerminalLogReader.RotatedException) {
                showRotated();
                return;
            }
            // Halt rather than retry: every visible row would otherwise re-request the same failing page.
            halted = true;
            logger.warn("Reading a page of {} failed: {}", file.name(), error.getMessage());
            showStatus(I18n.get("logfile.viewer.error", String.valueOf(error.getMessage())));
        }

        /** One repaint for however many pages arrived in the same pulse. */
        private void scheduleRefresh() {
            if (refreshQueued) {
                return;
            }
            refreshQueued = true;
            Platform.runLater(() -> {
                refreshQueued = false;
                listView.refresh();
            });
        }
    }

    /** One row: a right-aligned line number, then the clipped window of the line's text. */
    private final class LogLineCell extends ListCell<Integer> {
        private final Label gutter = new Label();
        private final Pane clip = new Pane();
        private final HBox row = new HBox(gutter, clip);
        private final List<Node> backgrounds = new ArrayList<>();
        private final List<Node> runs = new ArrayList<>();
        /** The cell's text colour, faded: gutter numbers and placeholders in any theme, selected or not. */
        private final ObjectBinding<Paint> mutedFill = Bindings.createObjectBinding(
            () -> getTextFill() instanceof Color color ? color.deriveColor(0, 1, 1, MUTED_OPACITY) : getTextFill(),
            textFillProperty());
        private int line = -1;
        private LogfileLineStyler.Span pressedSpan;

        LogLineCell() {
            setPadding(new Insets(0, CELL_PADDING, 0, CELL_PADDING));
            setMinHeight(cellHeight);
            setPrefHeight(cellHeight);
            setMaxHeight(cellHeight);

            gutter.setFont(fonts[0]);
            gutter.textFillProperty().bind(mutedFill);
            gutter.setAlignment(Pos.CENTER_RIGHT);
            gutter.setPadding(new Insets(0, GUTTER_PADDING, 0, 0));
            gutter.setMinHeight(cellHeight);
            gutter.setPrefHeight(cellHeight);

            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(clip, Priority.ALWAYS);
            clip.setMinSize(0, cellHeight);
            clip.setPrefHeight(cellHeight);
            clip.setMaxHeight(cellHeight);
            Rectangle window = new Rectangle();
            window.widthProperty().bind(clip.widthProperty());
            window.heightProperty().bind(clip.heightProperty());
            clip.setClip(window);

            setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.SECONDARY) {
                    Node hit = event.getPickResult() != null ? event.getPickResult().getIntersectedNode() : null;
                    pressedSpan = hit != null && hit.getUserData() instanceof LogfileLineStyler.Span span ? span : null;
                }
            });
            setOnContextMenuRequested(event -> {
                if (line >= 0) {
                    showContextMenu(line, pressedSpan, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            clip.getChildren().clear();
            if (empty || item == null) {
                line = -1;
                setGraphic(null);
                setText(null);
                return;
            }
            line = item;
            gutter.setText(Integer.toString(line + 1));
            gutter.setMinWidth(gutterWidth);
            gutter.setPrefWidth(gutterWidth);
            double width = Math.max(0, listView.getWidth() - gutterWidth - 2 * CELL_PADDING);
            clip.setPrefWidth(width);
            clip.setMaxWidth(width);
            setText(null);
            setGraphic(row);

            TerminalLogReader.Line source = pages.lineAt(line);
            if (source == null) {
                clip.getChildren().setAll(plainText(I18n.get("logfile.viewer.placeholder"), 0, true));
            } else {
                renderLine(source);
            }
        }

        /** Paints only the columns the window shows: the line is cut at the scroll position. */
        private void renderLine(TerminalLogReader.Line source) {
            String text = source.text();
            double offset = xOffset.get();
            int start = (int) Math.floor(offset / charWidth);
            double shift = offset - start * charWidth;
            int columns = (int) Math.ceil(clip.getPrefWidth() / charWidth) + 2;
            int end = Math.min(text.length(), start + columns);

            backgrounds.clear();
            runs.clear();
            int at = start;
            for (LogfileLineStyler.Span span : styledSpans(line, text)) {
                if (span.end() <= start) {
                    continue;
                }
                if (span.start() >= end) {
                    break;
                }
                int from = Math.max(span.start(), start);
                int to = Math.min(span.end(), end);
                if (from > at) {
                    addRun(at, from, null, text, start, shift);
                }
                addRun(from, to, span, text, start, shift);
                at = to;
            }
            if (at < end) {
                addRun(at, end, null, text, start, shift);
            }
            if (source.truncated()) {
                int tail = text.length() - start;
                if (tail >= 0 && tail <= columns) {
                    runs.add(plainText(" " + I18n.get("logfile.viewer.truncated"),
                        tail * charWidth - shift, true));
                }
            }
            List<Node> all = new ArrayList<>(backgrounds.size() + runs.size());
            all.addAll(backgrounds);
            all.addAll(runs);
            clip.getChildren().setAll(all);
        }

        private void addRun(int from, int to, LogfileLineStyler.Span span, String text, int start, double shift) {
            double x = (from - start) * charWidth - shift;
            double width = (to - from) * charWidth;
            LogfileLineStyler.Kind kind = span == null ? null : span.kind();
            Color fill = null;
            Color background = null;
            boolean bold = false;
            boolean italic = false;
            boolean underline = false;
            if (kind != null) {
                switch (kind) {
                    case LINK, PATH -> {
                        fill = LINK_FILL;
                        underline = true;
                    }
                    case KEYWORD -> {
                        CompiledHighlightSet.Rule rule = ruleOf(span);
                        if (rule != null) {
                            fill = LogfileColors.toFx(rule.foreground(), palette);
                            background = LogfileColors.toFx(rule.background(), palette);
                            bold = rule.bold();
                            italic = rule.italic();
                            underline = rule.underline();
                        }
                    }
                    case FIND -> background = FIND_FILL;
                    case FIND_CURRENT -> {
                        background = FIND_CURRENT_FILL;
                        underline = true;
                    }
                }
            }
            if (background != null) {
                Rectangle rectangle = new Rectangle(x, 0, width, cellHeight);
                rectangle.setFill(background);
                backgrounds.add(rectangle);
            }
            Text run = new Text(text.substring(from, to));
            run.setFont(fonts[(bold ? 1 : 0) + (italic ? 2 : 0)]);
            run.setTextOrigin(VPos.CENTER);
            run.setX(x);
            run.setY(cellHeight / 2);
            if (fill != null) {
                run.setFill(fill);
            } else {
                // The cell's text fill is what the theme (and a selected row) paints text in.
                run.fillProperty().bind(textFillProperty());
            }
            run.setUnderline(underline);
            if (span != null) {
                run.setUserData(span);
            }
            if (kind == LogfileLineStyler.Kind.LINK || kind == LogfileLineStyler.Kind.PATH) {
                String copy = span.copyText();
                if (copy != null) {
                    run.setCursor(Cursor.HAND);
                    Tooltip.install(run, new Tooltip(I18n.get("logfile.viewer.clickToCopy")));
                    run.setOnMouseClicked(event -> {
                        if (event.getButton() == MouseButton.PRIMARY) {
                            copyToClipboard(copy);
                            event.consume();
                        }
                    });
                }
            }
            runs.add(run);
        }

        private CompiledHighlightSet.Rule ruleOf(LogfileLineStyler.Span span) {
            CompiledHighlightSet set = activeSet;
            if (set == null || span.ruleIndex() < 0 || span.ruleIndex() >= set.size()) {
                return null;
            }
            return set.rule(span.ruleIndex());
        }

        private Text plainText(String content, double x, boolean muted) {
            Text text = new Text(content);
            text.setFont(fonts[0]);
            text.setTextOrigin(VPos.CENTER);
            text.setX(x);
            text.setY(cellHeight / 2);
            text.fillProperty().bind(muted ? mutedFill : textFillProperty());
            return text;
        }

        private void showContextMenu(int lineNo, LogfileLineStyler.Span span, double screenX, double screenY) {
            if (!listView.getSelectionModel().isSelected(lineNo)) {
                listView.getSelectionModel().clearAndSelect(lineNo);
            }
            ContextMenu menu = new ContextMenu();
            if (span != null && span.copyText() != null
                    && (span.kind() == LogfileLineStyler.Kind.LINK || span.kind() == LogfileLineStyler.Kind.PATH)) {
                String key = span.kind() == LogfileLineStyler.Kind.LINK
                    ? "terminal.contextMenu.copyLink" : "terminal.contextMenu.copyPath";
                MenuItem copyLink = new MenuItem(I18n.get(key));
                copyLink.setOnAction(event -> copyToClipboard(span.copyText()));
                menu.getItems().add(copyLink);
            }
            MenuItem copyOne = new MenuItem(I18n.get("logfile.viewer.copyLine"));
            copyOne.setOnAction(event -> copyLine(lineNo));
            MenuItem copyMany = new MenuItem(I18n.get("logfile.viewer.copySelection"));
            copyMany.setDisable(listView.getSelectionModel().getSelectedIndices().isEmpty());
            copyMany.setOnAction(event -> copySelection());
            menu.getItems().addAll(copyOne, copyMany);
            menu.show(this, screenX, screenY);
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Copying
    // ----------------------------------------------------------------------------------------------

    private void copyToClipboard(String text) {
        KorttyClipboard.setText(text);
        showStatus(I18n.get("logfile.viewer.copied"));
    }

    /**
     * One line, read in full: up to {@link TerminalLogReader#MAX_LINE_BYTES} rather than the display
     * text, so what the viewer cut off can still be copied.
     */
    private void copyLine(int lineNo) {
        Opened current = opened;
        if (current == null) {
            return;
        }
        submit(pageExecutor, () -> {
            try {
                String text = readFullLine(current.readable(), current.index(), lineNo);
                Platform.runLater(() -> copyToClipboard(text));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> showStatus(I18n.get("logfile.viewer.error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private void copySelection() {
        Opened current = opened;
        if (current == null) {
            return;
        }
        List<Integer> lines = new ArrayList<>(listView.getSelectionModel().getSelectedIndices());
        if (lines.isEmpty()) {
            return;
        }
        if (lines.size() > COPY_LIMIT) {
            showStatus(I18n.get("logfile.viewer.copyTooLarge", lines.size(), COPY_LIMIT));
            return;
        }
        Collections.sort(lines);
        List<int[]> runs = contiguousRuns(lines);
        TerminalLogReader reader = current.reader();
        submit(pageExecutor, () -> {
            try {
                List<String> texts = new ArrayList<>(lines.size());
                for (int[] run : runs) {
                    for (TerminalLogReader.Line source : reader.readPage(run[0], run[1])) {
                        texts.add(source.text());
                    }
                }
                String joined = String.join("\n", texts);
                Platform.runLater(() -> copyToClipboard(joined));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> showStatus(I18n.get("logfile.viewer.error", String.valueOf(e.getMessage()))));
            }
        });
    }

    /**
     * The full text of one line, read from the file at its indexed offset and cut at
     * {@link TerminalLogReader#MAX_LINE_BYTES}. The reader's own page text is cut at the display
     * length, which is shorter, so single-line copies go straight to the file.
     */
    static String readFullLine(Path readable, TerminalLogLineIndex index, int lineNo) throws IOException {
        long from = index.offsetOf(lineNo);
        long indexed = index.indexedBytes();
        int wanted = (int) Math.min(TerminalLogReader.MAX_LINE_BYTES + 1L, Math.max(0L, indexed - from));
        byte[] bytes = new byte[wanted];
        int filled = 0;
        try (FileChannel channel = FileChannel.open(readable, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long position = from;
            while (buffer.hasRemaining()) {
                int got = channel.read(buffer, position);
                if (got < 0) {
                    break;
                }
                position += got;
            }
            filled = buffer.position();
        }
        int end = 0;
        while (end < filled && bytes[end] != '\n') {
            end++;
        }
        int content = Math.min(end, TerminalLogReader.MAX_LINE_BYTES);
        if (end <= TerminalLogReader.MAX_LINE_BYTES && content > 0 && bytes[content - 1] == '\r') {
            content--;
        }
        return new String(bytes, 0, content, StandardCharsets.UTF_8);
    }

    /**
     * A sorted selection as runs of consecutive lines, each as {@code {first, count}}, so a copy
     * reads a run in one page call rather than a line at a time.
     */
    static List<int[]> contiguousRuns(List<Integer> sortedLines) {
        List<int[]> runs = new ArrayList<>();
        int i = 0;
        while (i < sortedLines.size()) {
            int first = sortedLines.get(i);
            int last = first;
            int j = i + 1;
            while (j < sortedLines.size() && sortedLines.get(j) == last + 1) {
                last++;
                j++;
            }
            runs.add(new int[] {first, last - first + 1});
            i = j;
        }
        return runs;
    }

    private static void submit(ExecutorService executor, Runnable work) {
        try {
            executor.execute(work);
        } catch (RejectedExecutionException e) {
            logger.debug("The log viewer is closing; dropped a queued task");
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Find, go to line and status
    // ----------------------------------------------------------------------------------------------

    private void toggleFindBar() {
        if (findBar.isVisible()) {
            hideFindBar();
        } else {
            showFindBar();
        }
    }

    private void showFindBar() {
        boolean wasVisible = findBar.isVisible();
        findBar.setVisible(true);
        findBar.setManaged(true);
        findField.requestFocus();
        findField.selectAll();
        if (!wasVisible && !findField.getText().isEmpty()) {
            runCount();
        }
    }

    /** Closing the bar also drops its highlights and cancels any search still running. */
    private void hideFindBar() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        findDebounce.stop();
        findNeedle = "";
        currentHitLine = -1;
        currentHitColumn = -1;
        hitsLabel.setText("");
        searchToken.incrementAndGet();
        listView.refresh();
    }

    /** Counts the matches of the find text for the hit label, and highlights the rows. */
    private void runCount() {
        findDebounce.stop();
        String needle = findField.getText();
        findNeedle = needle;
        currentHitLine = -1;
        currentHitColumn = -1;
        long token = searchToken.incrementAndGet();
        hitsLabel.setText("");
        listView.refresh();
        Opened current = opened;
        if (needle.isEmpty() || current == null) {
            return;
        }
        TerminalLogReader reader = current.reader();
        BooleanSupplier cancelled = () -> stale(token);
        submit(searchExecutor, () -> {
            try {
                int count = TerminalLogSearch.countMatches(
                    reader, needle, TerminalLogSearch.DEFAULT_CAP, cancelled, null);
                Platform.runLater(() -> showCount(token, count));
            } catch (IOException | RuntimeException e) {
                logger.debug("Counting matches stopped: {}", e.getMessage());
            }
        });
    }

    private boolean stale(long token) {
        return disposed.get() || token != searchToken.get();
    }

    private void showCount(long token, int count) {
        if (stale(token)) {
            return;
        }
        if (count <= 0) {
            hitsLabel.setText(I18n.get("logfile.viewer.noMatch"));
        } else if (count >= TerminalLogSearch.DEFAULT_CAP) {
            hitsLabel.setText(I18n.get("logfile.viewer.hitsCapped", TerminalLogSearch.DEFAULT_CAP));
        } else {
            hitsLabel.setText(I18n.get("logfile.viewer.hits", count));
        }
    }

    /**
     * Moves to the next or previous match. Forward starts after the current hit, backward before it,
     * and either wraps once around the file when nothing lies that way.
     */
    private void findStep(boolean forward) {
        Opened current = opened;
        String needle = findField.getText();
        if (current == null || needle.isEmpty()) {
            return;
        }
        if (!needle.equals(findNeedle)) {
            findNeedle = needle;
            listView.refresh();
        }
        int count = current.index().lineCount();
        if (count <= 0) {
            hitsLabel.setText(I18n.get("logfile.viewer.noMatch"));
            return;
        }
        boolean hasHit = currentHitLine >= 0;
        int anchor = hasHit ? currentHitLine : topLine();
        TerminalLogReader reader = current.reader();
        long token = searchToken.incrementAndGet();
        BooleanSupplier cancelled = () -> stale(token);
        submit(searchExecutor, () -> {
            try {
                OptionalInt found;
                if (forward) {
                    found = TerminalLogSearch.findForward(reader, needle, hasHit ? anchor + 1 : anchor, cancelled);
                    if (found.isEmpty() && !cancelled.getAsBoolean()) {
                        found = TerminalLogSearch.findForward(reader, needle, 0, cancelled);
                    }
                } else {
                    boolean atTop = hasHit && anchor == 0;
                    found = atTop ? OptionalInt.empty()
                        : TerminalLogSearch.findBackward(reader, needle, hasHit ? anchor - 1 : anchor, cancelled);
                    if (found.isEmpty() && !cancelled.getAsBoolean()) {
                        found = TerminalLogSearch.findBackward(reader, needle, count - 1, cancelled);
                    }
                }
                if (cancelled.getAsBoolean()) {
                    return;
                }
                if (found.isEmpty()) {
                    Platform.runLater(() -> showNoMatch(token));
                    return;
                }
                int line = found.getAsInt();
                String text = firstLineText(reader, line);
                int column = TerminalLogSearch.columnOf(text, needle);
                Platform.runLater(() -> showHit(token, line, column));
            } catch (TerminalLogReader.RotatedException e) {
                Platform.runLater(this::showRotated);
            } catch (IOException | RuntimeException e) {
                logger.debug("Find stopped: {}", e.getMessage());
            }
        });
    }

    private static String firstLineText(TerminalLogReader reader, int line) throws IOException {
        List<TerminalLogReader.Line> page = reader.readPage(line, 1);
        return page.isEmpty() ? "" : page.get(0).text();
    }

    private void showNoMatch(long token) {
        if (!stale(token)) {
            hitsLabel.setText(I18n.get("logfile.viewer.noMatch"));
        }
    }

    private void showHit(long token, int line, int column) {
        if (stale(token)) {
            return;
        }
        currentHitLine = line;
        currentHitColumn = column;
        revealLine(line);
        if (column >= 0 && column < TerminalLogReader.DISPLAY_CHARS) {
            revealColumn(column);
        } else {
            showStatus(I18n.get("logfile.viewer.hitBeyondView"));
        }
        listView.refresh();
    }

    private void goToLineFromField() {
        Opened current = opened;
        if (current == null) {
            return;
        }
        int count = current.index().lineCount();
        int number;
        try {
            number = Integer.parseInt(goToField.getText().trim());
        } catch (NumberFormatException e) {
            number = -1;
        }
        if (number < 1 || number > count) {
            showStatus(I18n.get("logfile.viewer.lineRange", count));
            return;
        }
        revealLine(number - 1);
    }

    private void revealLine(int lineNo) {
        listView.scrollTo(lineNo);
        listView.getSelectionModel().clearAndSelect(lineNo);
        listView.getFocusModel().focus(lineNo);
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
        statusTimer.playFromStart();
    }
}
