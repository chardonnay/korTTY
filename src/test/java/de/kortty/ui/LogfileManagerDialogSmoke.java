package de.kortty.ui;

import com.google.gson.JsonParser;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.core.LogTimeRange;
import de.kortty.core.SessionJournalLogCompressor;
import de.kortty.core.TerminalLogCatalog;
import de.kortty.core.TerminalLogCodecs;
import de.kortty.core.TerminalLogExporter;
import de.kortty.core.TerminalLogExportOptions;
import de.kortty.core.TerminalLogFile;
import de.kortty.core.TerminalLogFiles;
import de.kortty.core.TerminalLogNaming;
import de.kortty.core.TerminalLogger;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalLogConfig;
import de.kortty.model.TerminalLogKnownFolder;
import atlantafx.base.theme.CupertinoDark;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Headed smoke for the Logfile Manager and the log viewer, run against a generated set of terminal
 * logs written by the production loggers and exporters.
 *
 * <p>Fixtures: a live logger, plain/XML/JSON files written by the real logger, the same three gzipped,
 * zstd and bzip2 copies, an owned archive dated 60 days ago, a foreign file, and a 50 MB plain file.
 * The dialogs run on the real FX thread; every check is collected, so one failure does not hide the
 * others. Screenshots go to {@code app-docs/screenshots/terminal}.</p>
 */
public final class LogfileManagerDialogSmoke {

    private static final int EXPECTED_FILES = 11;
    private static final int BIG_LINES = 600_000;
    private static final String BIG_NAME = "2026-01-10-10-00-00-bigbox_1.log";
    private static final String NEEDLE = "NEEDLE-END";
    private static final int JSON_LINES = 200;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern JSON_ENTRY = Pattern.compile(
        "\"timestamp\": \"(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\".*?entry (\\d{3})");
    private static final Path SCREENSHOTS = Path.of("app-docs", "screenshots", "terminal");

    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final List<Path> closedViewers = new CopyOnWriteArrayList<>();

    private Instant started;
    private Path home;
    private Path logDir;
    private GlobalSettingsManager settings;
    private ConfigurationManager configs;
    private TerminalLogger live;

    /** A call on the FX thread that answers a value. */
    private interface FxCall<T> {
        T call() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        LogfileManagerDialogSmoke smoke = new LogfileManagerDialogSmoke();
        int[] code = {0};
        Thread driver = new Thread(() -> code[0] = smoke.guarded(), "logfile-smoke-driver");
        driver.setDaemon(true);
        driver.start();
        driver.join(TimeUnit.SECONDS.toMillis(180));
        if (driver.isAlive()) {
            System.err.println("LogfileManagerDialogSmoke TIMEOUT");
            System.exit(2);
        }
        Platform.exit();
        if (code[0] == 0) {
            System.out.println("LogfileManagerDialogSmoke OK");
        } else {
            System.err.println("LogfileManagerDialogSmoke FAILED: " + smoke.failures.size() + " check(s)");
        }
        System.exit(code[0]);
    }

    private int guarded() {
        try {
            started = Instant.now();
            fixtures();
            CountDownLatch toolkit = new CountDownLatch(1);
            Platform.startup(() -> {
                // Closing the last dialog must not end the FX thread: the smoke opens more windows afterwards.
                Platform.setImplicitExit(false);
                // The guide screenshots must show the Normal design (AGENTS.md); the default run uses
                // the AtlantaFX dark theme, where contrast bugs in custom styles show up first.
                if (!"normal".equalsIgnoreCase(System.getProperty("kortty.smoke.design", "cupertino-dark"))) {
                    Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
                }
                toolkit.countDown();
            });
            toolkit.await(30, TimeUnit.SECONDS);
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                failures.add("uncaught on " + thread.getName() + ": " + error);
                error.printStackTrace();
            });
            runUi();
            runEngine();
            checkTempScratch();
            live.stop();
        } catch (Throwable error) {
            failures.add("driver stopped: " + error);
            error.printStackTrace();
        }
        for (String failure : failures) {
            System.err.println("FAIL: " + failure);
        }
        return failures.isEmpty() ? 0 : 1;
    }

    // ---- Fixtures ----

    private void fixtures() throws Exception {
        home = Files.createTempDirectory("kortty-logfile-smoke");
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.ENGLISH);

        Path configDir = home.resolve(".kortty");
        Files.createDirectories(configDir);
        logDir = TerminalLogger.defaultDirectory();
        Files.createDirectories(logDir);

        settings = new GlobalSettingsManager(configDir);
        GlobalSettings gs = settings.getSettings();
        gs.setLanguage("en");
        LanguageManager.getInstance().initialize(gs);
        configs = new ConfigurationManager(configDir);
        // The default folder is remembered with a 30-day retention, so the 60-day archive is past its date.
        gs.setTerminalLogKnownFolders(new ArrayList<>(List.of(
            new TerminalLogKnownFolder(logDir.toString(), 30, null, false))));

        // Retention 0 on every logger: a logger sweeps its folder at start, which would remove the old archive.
        TerminalLogConfig liveConfig = config(TerminalLogConfig.LogFormat.PLAIN_TEXT, false);
        live = new TerminalLogger(liveConfig, "smoke-live", null);
        live.start();
        live.log("live line one\n");
        Thread.sleep(300);

        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            writeLog("smoke-plain-" + slugOf(format), format, false);
            writeLog("smoke-gz-" + slugOf(format), format, true);
        }

        writeBigbox();

        LocalDate oldDay = LocalDate.now().minusDays(60);
        Path oldPlain = logDir.resolve(oldDay.format(DAY) + "-10-00-00-smoke-old_1.log");
        Files.writeString(oldPlain, "2026-01-01 00:00:00.000 an archive from the past\n", StandardCharsets.UTF_8);
        SessionJournalLogCompressor.compressGzip(oldPlain);

        Files.writeString(logDir.resolve("notes.txt"), "not a korTTY log\n", StandardCharsets.UTF_8);

        // zstd and bzip2 copies of one gzip source, written by the production exporter.
        TerminalLogFile gzSource = find(scan(), "smoke-gz-txt", TerminalLogNaming.Compression.GZIP);
        for (TerminalLogNaming.Compression target : List.of(TerminalLogNaming.Compression.ZSTD,
            TerminalLogNaming.Compression.BZIP2)) {
            Path copy = logDir.resolve(TerminalLogExporter.targetName(gzSource.name(), target, null));
            TerminalLogExporter.Result result = TerminalLogExporter.exportOne(gzSource, copy, target, false,
                null, folders(), bytes -> { }, () -> false);
            if (result.status() != TerminalLogExporter.Status.OK) {
                throw new IllegalStateException("fixture copy " + copy.getFileName() + " was " + result.status()
                    + ": " + result.error());
            }
        }
        backdateClosedFiles();
        var tagged = find(scan(), "smoke-gz-json", TerminalLogNaming.Compression.GZIP);
        de.kortty.core.TerminalLogBadges.write(tagged.path(), List.of(
            de.kortty.model.TerminalLogBadge.defaults().get(0),
            new de.kortty.model.TerminalLogBadge("demo-custom", "Release review", "search", "#4488cc",
                de.kortty.model.TerminalLogBadge.Style.SOFT)));
        System.out.println("fixtures in " + logDir + ": " + scan().size() + " korTTY files");
    }

    private static String slugOf(TerminalLogConfig.LogFormat format) {
        return switch (format) {
            case PLAIN_TEXT -> "txt";
            case XML -> "xml";
            case JSON -> "json";
        };
    }

    /**
     * A closed plain file counts as live for five minutes after its last write, so the fixtures are
     * aged past that grace period; the one live logger's file stays fresh.
     */
    private void backdateClosedFiles() throws IOException {
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofMinutes(10)));
        try (Stream<Path> entries = Files.list(logDir)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                boolean plain = name.endsWith(".log") || name.endsWith(".xml") || name.endsWith(".json");
                if (plain && !name.contains("smoke-live")) {
                    Files.setLastModifiedTime(entry, old);
                }
            }
        }
    }

    private static TerminalLogConfig config(TerminalLogConfig.LogFormat format, boolean compress) {
        TerminalLogConfig config = new TerminalLogConfig();
        config.setEnabled(true);
        config.setCompress(compress);
        config.setFormat(format);
        config.setRetentionDays(0);
        return config;
    }

    /** One logger session of {@link #JSON_LINES} lines; spaced by a millisecond so every entry has its own stamp. */
    private void writeLog(String connection, TerminalLogConfig.LogFormat format, boolean compress) throws Exception {
        TerminalLogger logger = new TerminalLogger(config(format, compress), connection, null);
        logger.start();
        for (int i = 1; i <= JSON_LINES; i++) {
            logger.log("entry " + String.format(Locale.ROOT, "%03d", i) + " " + body(i) + "\n");
            Thread.sleep(2);
        }
        logger.stop();
    }

    private static String body(int i) {
        if (i % 50 == 0) {
            return "ERROR something failed in job " + i;
        }
        if (i % 7 == 0) {
            return "fetch https://example.com/path?item=" + i;
        }
        if (i % 11 == 0) {
            return "tail /var/log/syslog segment " + i;
        }
        if (i % 13 == 0) {
            return "notify user@example.com about batch " + i;
        }
        return "routine output line " + i;
    }

    /** A 50 MB plain file in korTTY's own name shape, with the unique token only in its last ten lines. */
    private void writeBigbox() throws IOException {
        LocalDateTime base = LocalDateTime.of(2026, 1, 10, 10, 0, 0);
        Path big = logDir.resolve(BIG_NAME);
        try (BufferedWriter writer = Files.newBufferedWriter(big, StandardCharsets.UTF_8)) {
            for (int i = 1; i <= BIG_LINES; i++) {
                String stamp = STAMP.format(base.plusNanos(i * 1_000_000L));
                String line;
                if (i > BIG_LINES - 10) {
                    line = "[" + stamp + "] " + NEEDLE + " tail " + i;
                } else if (i % 500 == 0) {
                    line = "[" + stamp + "] line " + i + " ERROR something failed";
                } else {
                    line = "[" + stamp + "] line " + i + " ops=ok host=box01 user=alice path=/var/log/app.log";
                }
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    // ---- Listing helpers (off the FX thread) ----

    private List<Path> folders() throws IOException {
        return TerminalLogCatalog.folders(configs.getConnections(), settings.getSettings().getTerminalLogKnownFolders())
            .stream().map(TerminalLogCatalog.Folder::path).toList();
    }

    private List<TerminalLogFile> scan() throws IOException {
        return TerminalLogCatalog.scan(
            TerminalLogCatalog.folders(configs.getConnections(), settings.getSettings().getTerminalLogKnownFolders()),
            LocalDate.now(), Instant.now());
    }

    private static TerminalLogFile find(List<TerminalLogFile> files, String fragment,
                                        TerminalLogNaming.Compression compression) {
        return files.stream()
            .filter(file -> file.name().contains(fragment) && file.compression() == compression)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no fixture named *" + fragment + "*" + compression));
    }

    // ---- UI checks ----

    private void runUi() throws Exception {
        LogfileManagerDialog manager = onFx(() -> {
            LogfileManagerDialog dialog = new LogfileManagerDialog(settings, configs, file -> { }, path -> { });
            dialog.show();
            return dialog;
        });
        boolean loaded = waitUntil(() -> tableOf(manager).getItems().size() == EXPECTED_FILES, 20_000);
        check(loaded, "manager lists " + EXPECTED_FILES + " rows, has " + onFx(() -> tableOf(manager).getItems().size()));
        if (!loaded) {
            onFxRun(() -> manager.close());
            return;
        }
        waitUntil(() -> tableOf(manager).lookupAll(".label").stream()
            .anyMatch(node -> node instanceof Label label && I18n.get("logfile.manager.live").equals(label.getText())), 5000);
        checkManagerListing(manager);
        checkSorting(manager);
        checkFilters(manager);
        TextField badgeSearch = fieldWithPrompt(manager, I18n.get("logfile.manager.filter.text.prompt"));
        onFxRun(() -> badgeSearch.setText("release REVIEW"));
        check(waitUntil(() -> tableOf(manager).getItems().size() == 1, 5000), "custom badge names are searchable in the table");
        onFxRun(badgeSearch::clear);
        waitUntil(() -> tableOf(manager).getItems().size() == EXPECTED_FILES, 5000);
        checkManagerScreenshot(manager);
        var badgeDialog = onFx(() -> {
            var dialog = new LogfileBadgeDialog(manager.getDialogPane().getScene().getWindow(), List.of(),
                List.of(de.kortty.model.TerminalLogBadge.defaults().get(0)));
            dialog.show(); return dialog;
        });
        savePng(badgeDialog.getDialogPane(), SCREENSHOTS.resolve("logfile-badges.png"));
        onFxRun(badgeDialog::close);
        var exportDialog = onFx(() -> {
            var dialog = new LogfileExportDialog(manager.getDialogPane().getScene().getWindow(),
                List.of(tableOf(manager).getItems().get(0)), null);
            dialog.show(); return dialog;
        });
        savePng(exportDialog.getDialogPane(), SCREENSHOTS.resolve("logfile-export.png"));
        checkCompactExportDialog(exportDialog);
        LogfileExportDialog germanExport = onFx(() -> {
            LanguageManager.getInstance().setLocale(Locale.GERMAN);
            try {
                var dialog = new LogfileExportDialog(manager.getDialogPane().getScene().getWindow(),
                    List.of(tableOf(manager).getItems().get(0)), null);
                dialog.show();
                return dialog;
            } finally {
                LanguageManager.getInstance().setLocale(Locale.ENGLISH);
            }
        });
        checkCompactExportDialog(germanExport, "Exportieren...", "Abbrechen", "de");
        onFxRun(manager::close);
        checkPureHelpers();
        runViewer();
    }

    private void checkCompactExportDialog(LogfileExportDialog dialog) throws Exception {
        checkCompactExportDialog(dialog, I18n.get("logfile.manager.export"), "Cancel", "en");
    }

    private void checkCompactExportDialog(LogfileExportDialog dialog, String label, String cancelLabel,
                                         String language) throws Exception {
        ButtonType exportType = onFx(() -> dialog.getDialogPane().getButtonTypes().stream()
            .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow());
        ButtonType cancelType = onFx(() -> dialog.getDialogPane().getButtonTypes().stream()
            .filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow());
        Button export = onFx(() -> (Button) dialog.getDialogPane().lookupButton(exportType));
        Button cancel = onFx(() -> (Button) dialog.getDialogPane().lookupButton(cancelType));
        check(onFx(() -> label.equals(export.getText())),
            language + " export options have an explicitly labelled Export button");
        // The Java default remains English while the German dialog uses korTTY's selected language.
        check(onFx(() -> cancelLabel.equals(cancel.getText())),
            language + " Cancel uses the selected app language independently of the Java default");
        checkExportProtection(dialog, export);
        onFxRun(() -> {
            dialog.setWidth(787);
            dialog.setHeight(390);
        });
        check(waitUntil(() -> dialog.getDialogPane().getHeight() > 0
            && dialog.getDialogPane().getHeight() <= 390, 5000), "export dialog fits a compact window");
        check(onFx(() -> buttonFitsScene(export) && buttonFitsScene(cancel)),
            "Export and Cancel remain inside the visible compact dialog");
        savePng(dialog.getDialogPane(), Path.of("build", "smoke", "logfile-export-compact-" + language + ".png"));
        onFxRun(export::fire);
        LogfileExportDialog.ExportChoice choice = onFx(dialog::getResult);
        check(choice != null, "Export returns the options used by the subsequent save dialog");
        if (choice != null) {
            check(choice.options().archive() == TerminalLogExportOptions.Archive.ZSTD,
                "Export preserves the selected archive format");
            choice.options().close();
        }
        onFxRun(dialog::close);
    }

    private void checkExportProtection(LogfileExportDialog dialog, Button export) throws Exception {
        onFxRun(() -> {
            ComboBox<?> archive = allNodes(dialog.getDialogPane()).stream()
                .filter(node -> node instanceof ComboBox<?> combo && combo.getItems().contains(TerminalLogExportOptions.Archive.ZIP))
                .map(node -> (ComboBox<?>) node).findFirst().orElseThrow();
            List<PasswordField> passwords = allNodes(dialog.getDialogPane()).stream()
                .filter(PasswordField.class::isInstance).map(PasswordField.class::cast).toList();
            CheckBox protect = allNodes(dialog.getDialogPane()).stream()
                .filter(node -> node instanceof CheckBox box && box.disableProperty().isBound())
                .map(node -> (CheckBox) node).findFirst().orElseThrow();
            archive.getSelectionModel().select(archive.getItems().indexOf(TerminalLogExportOptions.Archive.ZIP));
            protect.setSelected(true);
            check(export.isDisabled(), "encrypted ZIP export requires a non-empty password");
            // Throwaway test password; no user credentials enter this smoke.
            passwords.get(0).setText("logfile-smoke-only");
            passwords.get(1).setText("different-smoke-password");
            check(export.isDisabled(), "encrypted ZIP export rejects mismatched passwords");
            passwords.get(1).setText("logfile-smoke-only");
            check(!export.isDisabled(), "matching ZIP passwords enable Export");
            protect.setSelected(false);
            passwords.forEach(PasswordField::clear);
            archive.getSelectionModel().select(archive.getItems().indexOf(TerminalLogExportOptions.Archive.ZSTD));
        });
    }

    private static boolean buttonFitsScene(Button button) {
        Bounds bounds = button.localToScene(button.getBoundsInLocal());
        return button.isVisible() && bounds.getMinX() >= 0 && bounds.getMinY() >= 0
            && bounds.getMaxX() <= button.getScene().getWidth()
            && bounds.getMaxY() <= button.getScene().getHeight();
    }

    private void checkManagerListing(LogfileManagerDialog manager) throws Exception {
        onFx(() -> {
            List<TerminalLogFile> rows = List.copyOf(tableOf(manager).getItems());
            check(rows.stream().noneMatch(file -> file.name().equals("notes.txt")), "notes.txt is not listed");
            boolean desc = true;
            for (int i = 1; i < rows.size(); i++) {
                if (rows.get(i - 1).startedAt().isBefore(rows.get(i).startedAt())) {
                    desc = false;
                }
            }
            check(desc, "default order is startedAt descending");

            List<TerminalLogFile> liveRows = LogfileManagerDialog.partitionLive(rows).live();
            check(liveRows.size() == 1 && liveRows.get(0).name().contains("smoke-live"),
                "exactly the live logger's file is partitioned as live (got " + liveRows.size() + ")");

            long visiblePills = tableOf(manager).lookupAll(".label").stream()
                .filter(node -> node instanceof Label label && label.isVisible()
                    && I18n.get("logfile.manager.live").equals(label.getText()))
                .count();
            check(visiblePills == 1, "one live pill is visible in the table (got " + visiblePills + ")");

            TerminalLogFile old = rows.stream().filter(file -> file.name().contains("smoke-old")).findFirst().orElse(null);
            check(old != null && old.deletableFrom() != null && old.deletableFrom().isBefore(LocalDate.now()),
                "the 60-day archive has a past deletable-from date (" + (old == null ? "missing" : old.deletableFrom()) + ")");
            check(rows.stream().anyMatch(file -> file.name().equals(BIG_NAME)), "bigbox is listed");
            return null;
        });
    }

    private void checkSorting(LogfileManagerDialog manager) throws Exception {
        String header = I18n.get("logfile.manager.column.size");
        onFxRun(() -> clickHeader(manager, header));
        List<Long> afterClick = onFx(() -> sizes(manager));
        System.out.println("  info Size header click: first row is " + (afterClick.isEmpty() ? -1 : afterClick.get(0))
            + " bytes; the table is then sorted through its API");
        onFxRun(() -> sortBySize(manager, TableColumn.SortType.ASCENDING));
        List<Long> ascending = onFx(() -> sizes(manager));
        check(ascending.size() == EXPECTED_FILES && ascending.get(0) == ascending.stream().mapToLong(Long::longValue).min().orElse(-1),
            "Size sorts ascending (head=" + (ascending.isEmpty() ? -1 : ascending.get(0)) + ")");
        onFxRun(() -> sortBySize(manager, TableColumn.SortType.DESCENDING));
        List<Long> first = onFx(() -> sizes(manager));
        check(first.size() == EXPECTED_FILES, "size sort keeps every row");
        long min = first.stream().mapToLong(Long::longValue).min().orElse(-1);
        long max = first.stream().mapToLong(Long::longValue).max().orElse(-1);
        long head = first.isEmpty() ? -1 : first.get(0);
        check(head == max, "Size sorts descending: the largest file leads (head=" + head + ", max=" + max + ")");
        check(!first.isEmpty() && first.get(first.size() - 1) == min,
            "Size sorts descending (tail=" + (first.isEmpty() ? -1 : first.get(first.size() - 1)) + ")");
        // Restore the default order for the remaining checks.
        onFxRun(() -> {
            TableView<TerminalLogFile> table = tableOf(manager);
            TableColumn<TerminalLogFile, ?> started = table.getColumns().stream().filter(column -> "logfile-started".equals(column.getId())).findFirst().orElseThrow();
            started.setSortType(TableColumn.SortType.DESCENDING);
            table.getSortOrder().setAll(List.of(started));
        });
    }

    private void checkFilters(LogfileManagerDialog manager) throws Exception {
        TextField search = fieldWithPrompt(manager, I18n.get("logfile.manager.filter.text.prompt"));
        onFxRun(() -> search.setText("bigbox"));
        waitUntil(() -> tableOf(manager).getItems().size() == 1, 5_000);
        onFx(() -> {
            List<TerminalLogFile> rows = List.copyOf(tableOf(manager).getItems());
            check(rows.size() == 1 && rows.get(0).name().equals(BIG_NAME),
                "filter text 'bigbox' leaves only bigbox (got " + rows.size() + ")");
            return null;
        });
        onFxRun(() -> search.clear());
        waitUntil(() -> tableOf(manager).getItems().size() == EXPECTED_FILES, 5_000);

        // Time window 2026-01-10 09:00 to 11:00: bigbox starts at 10:00, every other fixture is from today.
        DatePicker from = datePicker(manager, I18n.get("logfile.manager.filter.from"));
        DatePicker to = datePicker(manager, I18n.get("logfile.manager.filter.to"));
        TextField fromTime = timeField(manager, I18n.get("logfile.manager.filter.from"));
        TextField toTime = timeField(manager, I18n.get("logfile.manager.filter.to"));
        onFxRun(() -> {
            from.setValue(LocalDate.of(2026, 1, 10));
            fromTime.setText("09:00");
            to.setValue(LocalDate.of(2026, 1, 10));
            toTime.setText("11:00");
        });
        waitUntil(() -> tableOf(manager).getItems().size() == 1, 5_000);
        onFx(() -> {
            List<TerminalLogFile> rows = List.copyOf(tableOf(manager).getItems());
            check(rows.stream().anyMatch(file -> file.name().equals(BIG_NAME)),
                "the 09:00-11:00 window on 2026-01-10 includes bigbox");
            check(rows.size() == 1, "the 09:00-11:00 window leaves only bigbox (got " + rows.size() + ")");
            return null;
        });
        // A time that does not parse is ignored for filtering and its field is marked.
        onFxRun(() -> fromTime.setText("25:99"));
        waitUntil(() -> tableOf(manager).getItems().size() == 1, 5_000);
        onFx(() -> {
            check(fromTime.getStyle().contains("-fx-border-color"), "an unparseable time is marked in the field");
            List<TerminalLogFile> rows = List.copyOf(tableOf(manager).getItems());
            check(rows.size() == 1 && rows.get(0).name().equals(BIG_NAME),
                "an unparseable time is ignored and the date window still applies (got " + rows.size() + ")");
            return null;
        });
        onFxRun(() -> {
            fromTime.clear();
            toTime.clear();
            from.setValue(null);
            to.setValue(null);
        });

        ComboBox<TerminalLogConfig.LogFormat> typeCombo = formatCombo(manager);
        List<TerminalLogFile> all = scan();
        long expectedJson = all.stream().filter(file -> file.format() == TerminalLogConfig.LogFormat.JSON).count();
        onFxRun(() -> typeCombo.setValue(TerminalLogConfig.LogFormat.JSON));
        waitUntil(() -> tableOf(manager).getItems().size() == expectedJson, 5_000);
        onFx(() -> {
            List<TerminalLogFile> rows = List.copyOf(tableOf(manager).getItems());
            check(!rows.isEmpty() && rows.stream().allMatch(file -> file.format() == TerminalLogConfig.LogFormat.JSON),
                "type filter JSON leaves only JSON rows (" + rows.size() + " rows, expected " + expectedJson + ")");
            return null;
        });

        Button clear = buttonWithText(manager, I18n.get("logfile.manager.filter.clear"));
        onFxRun(clear::fire);
        waitUntil(() -> tableOf(manager).getItems().size() == EXPECTED_FILES, 5_000);
        onFx(() -> {
            check(tableOf(manager).getItems().size() == EXPECTED_FILES, "clear filters restores every row");
            check(typeCombo.getValue() == null, "clear filters resets the type filter");
            return null;
        });
    }

    private void checkManagerScreenshot(LogfileManagerDialog manager) throws Exception {
        onFxRun(() -> {
            tableOf(manager).getSelectionModel().select(0);
            Window window = manager.getDialogPane().getScene().getWindow();
            window.setWidth(1500);
            window.setHeight(640);
        });
        Thread.sleep(800);
        onFxRun(() -> {
            Button view = buttonWithText(manager, I18n.get("logfile.manager.view"));
            check(!view.isDisabled(), "view is enabled once a row is selected");
        });
        savePng(manager.getDialogPane(), SCREENSHOTS.resolve("logfile-manager.png"));
    }

    private void checkPureHelpers() throws Exception {
        LogfileManagerDialog.FilterState broken = LogfileManagerDialog.buildFilter("", null, "xx", null, "",
            null, null);
        check(broken.fromTimeInvalid() && !broken.toTimeInvalid(), "a non-blank unparseable from-time is flagged");
        LogfileManagerDialog.FilterState reversed = LogfileManagerDialog.buildFilter("", LocalDate.of(2026, 1, 11),
            "", LocalDate.of(2026, 1, 10), "", null, null);
        check(reversed.fromTimeInvalid() && reversed.toTimeInvalid() && reversed.filter().range() == null,
            "a range whose start is after its end is ignored and both time fields are flagged");
        check(LogfileExportDialog.isRangeInvalid(LocalDate.of(2026, 1, 10), "09:00", LocalDate.of(2026, 1, 10), "08:00"),
            "export range with end before start is invalid");
        check(!LogfileExportDialog.isRangeInvalid(LocalDate.of(2026, 1, 10), "09:00", LocalDate.of(2026, 1, 10), "10:00"),
            "export range 09:00-10:00 is valid");
        check(LogfileManagerDialog.compressionLabel(TerminalLogNaming.Compression.NONE) != null,
            "compression labels resolve");
    }

    private void runViewer() throws Exception {
        TerminalLogFile big = scan().stream().filter(file -> file.name().equals(BIG_NAME)).findFirst()
            .orElseThrow(() -> new IllegalStateException("bigbox missing from the scan"));
        LogfileViewerDialog viewer = onFx(() -> {
            LogfileViewerDialog dialog = new LogfileViewerDialog(big, settings, null, closedViewers::add);
            dialog.show();
            return dialog;
        });
        boolean opened = waitUntil(() -> listOf(viewer).getItems().size() == BIG_LINES, 60_000);
        check(opened, "viewer reports " + BIG_LINES + " lines (has " + onFx(() -> listOf(viewer).getItems().size()) + ")");
        if (opened) {
            checkViewerTail(viewer);
            checkViewerFind(viewer);
            checkViewerHighlights(viewer);
            onFxRun(() -> {
                Window window = viewer.getDialogPane().getScene().getWindow();
                window.setWidth(1100);
                window.setHeight(700);
            });
            Thread.sleep(800);
            savePng(viewer.getDialogPane(), SCREENSHOTS.resolve("logfile-viewer.png"));
        }
        onFxRun(viewer::close);
        check(waitUntil(() -> closedViewers.contains(big.path()), 5_000), "the viewer tells its owner it closed");
        check(Collections.frequency(closedViewers, big.path()) == 1, "the owner is told once");

        TerminalLogFile xml = find(scan(), "smoke-gz-xml", TerminalLogNaming.Compression.GZIP);
        LogfileViewerDialog xmlViewer = onFx(() -> {
            LogfileViewerDialog dialog = new LogfileViewerDialog(xml, settings, null, closedViewers::add);
            dialog.show();
            return dialog;
        });
        int xmlLines = countLines(xml.path());
        check(waitUntil(() -> listOf(xmlViewer).getItems().size() > xmlLines, 30_000), "XML opens formatted by default");
        savePng(xmlViewer.getDialogPane(), SCREENSHOTS.resolve("logfile-viewer-xml.png"));
        onFxRun(() -> checkWithText(xmlViewer, I18n.get("logfile.viewer.formatted")).fire());
        boolean xmlOpened = waitUntil(() -> listOf(xmlViewer).getItems().size() == xmlLines, 30_000);
        check(xmlOpened, "gzipped XML viewer lists its " + xmlLines + " lines");
        if (xmlOpened) {
            // Line 1 is the XML declaration, line 2 the root element, line 3 the first entry.
            waitUntil(() -> cellTextForItem(xmlViewer, 2).contains("<entry"), 10_000);
            String third = cellTextOf(xmlViewer, 2);
            check(third.contains("<entry"), "gzipped XML line 3 is an <entry> element (got '" + third + "')");
        }
        onFxRun(xmlViewer::close);
        waitUntil(() -> closedViewers.contains(xml.path()), 5_000);
        TerminalLogFile json = find(scan(), "smoke-gz-json", TerminalLogNaming.Compression.GZIP);
        var jsonViewer = onFx(() -> {
            var dialog = new LogfileViewerDialog(json, settings, null, closedViewers::add);
            dialog.show(); return dialog;
        });
        int rawJsonLines = countLines(json.path());
        check(waitUntil(() -> listOf(jsonViewer).getItems().size() > rawJsonLines, 30000), "JSON opens formatted by default");
        savePng(jsonViewer.getDialogPane(), SCREENSHOTS.resolve("logfile-viewer-json.png"));
        onFxRun(() -> checkWithText(jsonViewer, I18n.get("logfile.viewer.formatted")).fire());
        check(waitUntil(() -> listOf(jsonViewer).getItems().size() == rawJsonLines, 30000), "JSON raw view restores physical source rows");
        onFxRun(jsonViewer::close);
    }

    private void checkViewerTail(LogfileViewerDialog viewer) throws Exception {
        int count = onFx(() -> listOf(viewer).getItems().size());
        check(count == BIG_LINES, "lineCount equals the lines written (" + count + ")");
        onFxRun(() -> listOf(viewer).scrollTo(BIG_LINES - 1));
        Thread.sleep(600);
        String last = onFx(() -> cellTextForItem(viewer, BIG_LINES - 1));
        check(last.contains(NEEDLE), "last line shows " + NEEDLE + " (got '" + last + "')");
        onFxRun(() -> listOf(viewer).scrollTo(0));
        Thread.sleep(400);
    }

    private void checkViewerFind(LogfileViewerDialog viewer) throws Exception {
        Button find = buttonWithText(viewer, I18n.get("logfile.viewer.find"));
        onFxRun(find::fire);
        TextField findField = fieldWithPrompt(viewer, I18n.get("logfile.viewer.find.prompt"));
        onFxRun(() -> {
            findField.setText(NEEDLE);
            Event.fireEvent(findField, new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "\r", KeyCode.ENTER,
                false, false, false, false));
        });
        int tail = BIG_LINES - 10;
        boolean hit = waitUntil(() -> visibleHitLine(viewer, tail) >= 0, 30_000);
        int line = onFx(() -> visibleHitLine(viewer, tail));
        check(hit && line >= tail && line < BIG_LINES,
            "find Enter reveals a " + NEEDLE + " hit within the last 10 lines (line index " + line + ")");
    }

    private void checkViewerHighlights(LogfileViewerDialog viewer) throws Exception {
        String keywords = I18n.get("logfile.viewer.highlightKeywords");
        String links = I18n.get("logfile.viewer.highlightLinks");
        for (String label : List.of(keywords, links)) {
            CheckBox box = checkBoxWithText(viewer, label);
            onFxRun(() -> {
                box.fire();
                box.fire();
            });
            onFxRun(() -> {
                if (!box.isSelected()) {
                    box.fire();
                }
            });
        }
        Thread.sleep(500);
        check(true, "keyword and link highlights toggled off and on without an exception");
    }

    // ---- Engine checks (export, trim, delete) ----

    private void runEngine() throws Exception {
        List<TerminalLogFile> files = scan();
        check(files.size() == EXPECTED_FILES, "scan finds " + EXPECTED_FILES + " korTTY files (got " + files.size() + ")");
        List<Path> folders = folders();

        // gzip -> zstd for every smoke-gz file; the decoded bytes must match the source.
        Path zstDir = Files.createDirectories(home.resolve("export-zst"));
        List<TerminalLogFile> gz = files.stream().filter(file -> file.name().contains("smoke-gz-")
            && file.compression() == TerminalLogNaming.Compression.GZIP).toList();
        List<TerminalLogExporter.Result> results = TerminalLogExporter.exportAll(gz, zstDir,
            TerminalLogNaming.Compression.ZSTD, false, null, folders, progress -> { }, () -> false);
        check(results.size() == gz.size() && results.stream().allMatch(r -> r.status() == TerminalLogExporter.Status.OK),
            "gzip -> zstd exports every file with status OK");
        for (TerminalLogExporter.Result result : results) {
            byte[] source = decodedBytes(result.source());
            byte[] copy = decodedBytes(result.target());
            check(java.util.Arrays.equals(source, copy), "zstd copy of " + result.source().getFileName()
                + " decodes to the same bytes as the gzip source");
        }

        // A trimmed JSON export: the range starts at entry 75 and ends before entry 125 (exclusive).
        TerminalLogFile json = find(files, "smoke-plain-json", TerminalLogNaming.Compression.NONE);
        Matcher matcher = JSON_ENTRY.matcher("");
        java.util.Map<Integer, LocalDateTime> stamps = new java.util.HashMap<>();
        for (String text : Files.readAllLines(json.path(), StandardCharsets.UTF_8)) {
            matcher.reset(text);
            if (matcher.find()) {
                stamps.put(Integer.parseInt(matcher.group(2)), LocalDateTime.parse(matcher.group(1), STAMP));
            }
        }
        LocalDateTime from = stamps.get(75);
        LocalDateTime to = stamps.get(125);
        check(from != null && to != null, "JSON fixture entries 75 and 125 carry timestamps");
        if (from != null && to != null) {
            // The record constructor, not LogTimeRange.of: that one rounds the end up to the next whole minute,
            // and all fixture entries fall into one minute.
            LogTimeRange range = new LogTimeRange(from, to);
            Path trimmed = home.resolve("trimmed.json");
            TerminalLogExporter.Result trim = TerminalLogExporter.exportOne(json, trimmed,
                TerminalLogNaming.Compression.NONE, false, range, folders, bytes -> { }, () -> false);
            check(trim.status() == TerminalLogExporter.Status.OK, "trimmed JSON export is OK (" + trim.status() + ")");
            check(trim.entriesKept() >= 40 && trim.entriesKept() <= 60,
                "trimmed JSON keeps about 50 entries (kept " + trim.entriesKept() + ")");
            boolean parses = false;
            try {
                parses = JsonParser.parseString(Files.readString(trimmed, StandardCharsets.UTF_8)).isJsonObject();
            } catch (RuntimeException e) {
                failures.add("trimmed JSON does not parse with Gson: " + e.getMessage());
            }
            check(parses, "trimmed JSON parses with Gson as an object");
        }

        // A gzip copy under an owned name that the retention sweep would take: refused, nothing written.
        LocalDate oldDay = LocalDate.now().minusDays(60);
        Path swept = logDir.resolve(oldDay.format(DAY) + "-10-00-00-smoke-swept_1.log.gz");
        TerminalLogFile plain = find(files, "smoke-plain-txt", TerminalLogNaming.Compression.NONE);
        TerminalLogExporter.Result sweptResult = TerminalLogExporter.exportOne(plain, swept,
            TerminalLogNaming.Compression.GZIP, false, null, folders, bytes -> { }, () -> false);
        check(sweptResult.status() == TerminalLogExporter.Status.WOULD_BE_SWEPT,
            "a gzip export into the log folder under an expired name is WOULD_BE_SWEPT (" + sweptResult.status() + ")");
        check(!Files.exists(swept), "a swept export writes nothing");

        // Delete: a closed plain file goes; the live file is skipped.
        List<TerminalLogFiles.DeleteOutcome> deleted = TerminalLogFiles.delete(List.of(plain), Instant.now());
        check(deleted.size() == 1 && deleted.get(0).status() == TerminalLogFiles.DeleteStatus.DELETED
            && !Files.exists(plain.path()), "a closed plain file is deleted");
        TerminalLogFile liveFile = scan().stream().filter(TerminalLogFile::live).findFirst()
            .orElseThrow(() -> new IllegalStateException("live file not listed"));
        List<TerminalLogFiles.DeleteOutcome> skipped = TerminalLogFiles.delete(List.of(liveFile), Instant.now());
        check(skipped.size() == 1 && skipped.get(0).status() == TerminalLogFiles.DeleteStatus.SKIPPED_LIVE
            && Files.exists(liveFile.path()), "the live file is skipped by delete");
    }

    private void checkTempScratch() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        boolean clean = false;
        long end = System.currentTimeMillis() + 10_000;
        List<Path> leftovers = List.of();
        while (System.currentTimeMillis() < end) {
            try (Stream<Path> entries = Files.list(tmp)) {
                leftovers = entries
                    .filter(p -> p.getFileName().toString().startsWith("kortty-logview-"))
                    .filter(p -> modifiedSince(p, started))
                    .toList();
            }
            if (leftovers.isEmpty()) {
                clean = true;
                break;
            }
            Thread.sleep(300);
        }
        check(clean, "viewers removed their scratch folders (left: " + leftovers + ")");
    }

    private static boolean modifiedSince(Path path, Instant since) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isAfter(since.minusSeconds(2));
        } catch (IOException e) {
            return false;
        }
    }

    // ---- Driver helpers ----

    private void check(boolean condition, String message) {
        if (!condition) {
            failures.add(message);
            System.out.println("  FAIL " + message);
        } else {
            System.out.println("  ok   " + message);
        }
    }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(call.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                System.err.println("thread " + entry.getKey().getName() + " state=" + entry.getKey().getState());
                for (StackTraceElement frame : java.util.Arrays.copyOf(entry.getValue(),
                    Math.min(8, entry.getValue().length))) {
                    System.err.println("    " + frame);
                }
            }
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static void onFxRun(Runnable action) throws Exception {
        onFx(() -> {
            action.run();
            return null;
        });
    }

    private static boolean waitUntil(FxCall<Boolean> condition, long timeoutMillis) throws Exception {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(onFx(condition))) {
                return true;
            }
            Thread.sleep(100);
        }
        return Boolean.TRUE.equals(onFx(condition));
    }

    private static void savePng(Node node, Path target) throws Exception {
        Thread.sleep(300);
        WritableImage image = onFx(() -> node.snapshot(null, null));
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        Files.createDirectories(target.toAbsolutePath().getParent());
        ImageIO.write(buffered, "png", target.toFile());
        System.out.println("  wrote " + target.toAbsolutePath() + " (" + buffered.getWidth() + "x" + buffered.getHeight()
            + " px, node " + (int) image.getWidth() + "x" + (int) image.getHeight() + ")");
    }

    // ---- Node lookups ----

    @SuppressWarnings("unchecked")
    private static TableView<TerminalLogFile> tableOf(Dialog<?> dialog) {
        return (TableView<TerminalLogFile>) dialog.getDialogPane().lookup(".table-view");
    }

    /** The viewer's line list: the ListView with the most rows (the keyword-set combo has a list too). */
    @SuppressWarnings("unchecked")
    private static ListView<Integer> listOf(Dialog<?> dialog) {
        ListView<Integer> best = null;
        for (Node node : dialog.getDialogPane().lookupAll(".list-view")) {
            ListView<Integer> candidate = (ListView<Integer>) node;
            if (best == null || candidate.getItems().size() > best.getItems().size()) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException("no list view in the viewer");
        }
        return best;
    }

    private static List<Long> sizes(LogfileManagerDialog manager) {
        return tableOf(manager).getItems().stream().map(TerminalLogFile::sizeBytes).toList();
    }

    /** Sorts the table on Size through the table API, the same state a header click sets. */
    private static CheckBox checkWithText(Dialog<?> dialog, String text) {
        return (CheckBox) allNodes(dialog.getDialogPane()).stream()
            .filter(node -> node instanceof CheckBox check && text.equals(check.getText())).findFirst().orElseThrow();
    }

    private static void sortBySize(LogfileManagerDialog manager, TableColumn.SortType type) {
        TableView<TerminalLogFile> table = tableOf(manager);
        TableColumn<TerminalLogFile, ?> size = table.getColumns().stream().filter(column -> "logfile-size".equals(column.getId())).findFirst().orElseThrow();
        size.setSortType(type);
        table.getSortOrder().setAll(List.of(size));
        table.sort();
    }

    private static void clickHeader(LogfileManagerDialog manager, String headerText) {
        Node label = tableOf(manager).lookupAll(".label").stream()
            .filter(node -> node instanceof Label l && headerText.equals(l.getText()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no header labelled " + headerText));
        Bounds bounds = label.getBoundsInLocal();
        double x = bounds.getWidth() / 2;
        double y = bounds.getHeight() / 2;
        Point2D screen = label.localToScreen(x, y);
        PickResult pick = new PickResult(label, x, y);
        for (EventType<MouseEvent> type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED,
            MouseEvent.MOUSE_CLICKED)) {
            boolean pressed = type == MouseEvent.MOUSE_PRESSED;
            Event.fireEvent(label, new MouseEvent(type, x, y, screen.getX(), screen.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, pressed, false, false, false, false, false, pressed, false, pick));
        }
    }

    private static Button buttonWithText(Dialog<?> dialog, String text) {
        for (Node node : dialog.getDialogPane().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new IllegalStateException("no button '" + text + "'");
    }

    private static CheckBox checkBoxWithText(Dialog<?> dialog, String text) {
        for (Node node : dialog.getDialogPane().lookupAll(".check-box")) {
            if (node instanceof CheckBox box && text.equals(box.getText())) {
                return box;
            }
        }
        throw new IllegalStateException("no check box '" + text + "'");
    }

    private static TextField fieldWithPrompt(Dialog<?> dialog, String prompt) {
        for (Node node : dialog.getDialogPane().lookupAll(".text-field")) {
            if (node instanceof TextField field && prompt.equals(field.getPromptText())) {
                return field;
            }
        }
        throw new IllegalStateException("no text field with prompt '" + prompt + "'");
    }

    /** The control inside the filter bar's labelled group, found by the label that precedes it. */
    private static Node labelled(Dialog<?> dialog, String label, Class<?> type) {
        for (Node node : allNodes(dialog.getDialogPane())) {
            if (type.isInstance(node) && node.getParent() instanceof HBox box && !box.getChildren().isEmpty()
                && box.getChildren().get(0) instanceof Label first && label.equals(first.getText())) {
                return node;
            }
        }
        throw new IllegalStateException("no " + type.getSimpleName() + " after '" + label + "'");
    }

    private static List<Node> allNodes(Node root) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(root);
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                nodes.addAll(allNodes(child));
            }
        }
        return nodes;
    }

    private static DatePicker datePicker(Dialog<?> dialog, String label) {
        return (DatePicker) labelled(dialog, label, DatePicker.class);
    }

    private static TextField timeField(Dialog<?> dialog, String label) {
        return (TextField) labelled(dialog, label, TextField.class);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<TerminalLogConfig.LogFormat> formatCombo(Dialog<?> dialog) {
        return (ComboBox<TerminalLogConfig.LogFormat>) labelled(dialog,
            I18n.get("logfile.manager.filter.type"), ComboBox.class);
    }

    /** The row whose item is {@code item}, when the cell is on screen; empty text otherwise. */
    private static String cellTextForItem(LogfileViewerDialog viewer, int item) {
        for (Node cell : listOf(viewer).lookupAll(".list-cell")) {
            if (cell.isVisible() && cell instanceof javafx.scene.control.ListCell<?> listCell
                && Integer.valueOf(item).equals(listCell.getItem())) {
                return textOf(cell);
            }
        }
        return "";
    }

    private static String cellTextOf(LogfileViewerDialog viewer, int item) throws Exception {
        return onFx(() -> cellTextForItem(viewer, item));
    }

    /** The line of a visible row at or after {@code from} whose text holds the needle, or -1. */
    private static int visibleHitLine(LogfileViewerDialog viewer, int from) {
        int found = -1;
        for (Node cell : listOf(viewer).lookupAll(".list-cell")) {
            if (cell.isVisible() && cell instanceof javafx.scene.control.ListCell<?> listCell
                && listCell.getItem() instanceof Integer line && line >= from
                && textOf(cell).contains(NEEDLE)) {
                found = Math.max(found, line);
            }
        }
        return found;
    }

    private static String textOf(Node node) {
        StringBuilder text = new StringBuilder();
        collectText(node, text);
        return text.toString();
    }

    private static void collectText(Node node, StringBuilder into) {
        if (node instanceof Text text && text.getText() != null) {
            into.append(text.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, into);
            }
        }
    }

    private static int countLines(Path gz) throws IOException {
        return (int) new String(decodedBytes(gz), StandardCharsets.UTF_8).lines().count();
    }

    private static byte[] decodedBytes(Path file) throws IOException {
        try (InputStream in = TerminalLogCodecs.openInput(file)) {
            return in.readAllBytes();
        }
    }
}
