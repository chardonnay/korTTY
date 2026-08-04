package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SessionJournalExportFilter;
import de.kortty.core.SessionJournalExportService;
import de.kortty.core.SessionJournalMarkers;
import de.kortty.core.SessionJournalService;
import de.kortty.core.SessionJournalSession;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMarkerDefinition;
import de.kortty.model.SessionJournalMarkerRule;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's export-options and marker-management screenshots.
 *
 * <p>Builds the REAL dialogs headless against an isolated {@code user.home} and a throw-away
 * journal, then snapshots their dialog panes at 2x — no running app, no screen recording, and no
 * chance of a real host name or credential ending up in the manual.</p>
 *
 * <p>Run via the {@code generateSessionJournalScreenshots} Gradle task. Exit 0 = OK.</p>
 */
public final class SessionJournalScreenshotGenerator {

    private static final double EXPORT_WIDTH = 620;
    private static final double EXPORT_HEIGHT = 672;
    private static final double MARKER_WIDTH = 760;
    private static final double MARKER_HEIGHT = 600;

    private static final String EXPORT_FILE = "app-docs/screenshots/journal/journal-export-options.png";
    private static final String MARKER_FILE = "app-docs/screenshots/journal/journal-markers.png";

    private SessionJournalScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-journal-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        System.setProperty("TEST_MODE_KORTTY", "1");
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                GlobalSettings settings = demoSettings(isolatedHome);
                LanguageManager.getInstance().initialize(settings);
                Dialog<ButtonType> exportDialog = buildExportDialog(settings);
                // The dialog counts the matching entries on a background thread and posts the
                // result back with runLater; snapshotting now would capture "Counting…".
                javafx.animation.PauseTransition settle =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
                settle.setOnFinished(event -> {
                    try {
                        verifyLayout(exportDialog);
                        verifyEditorLayout(isolatedHome);
                        verifyEditorGeometry(isolatedHome, settings);
                        capture(exportDialog, settings, EXPORT_WIDTH, EXPORT_HEIGHT, EXPORT_FILE);
                        writeMarkerShot(settings);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, stack(t));
                    } finally {
                        done.countDown();
                    }
                });
                settle.play();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + fail);
            System.exit(1);
        }
        System.exit(0);
    }

    /** Demo markers and rules, so both dialogs show something worth looking at. */
    private static GlobalSettings demoSettings(Path home) {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        settings.setSessionJournalStoragePath(home.resolve("journals").toString());
        settings.getSessionJournalMarkers().addAll(List.of(
            new SessionJournalMarkerDefinition("software-installation", "Software installation",
                "#7c3aed", false, SessionJournalMarker.IMPORTANT),
            new SessionJournalMarkerDefinition("outage", "Outage",
                "#be123c", false, SessionJournalMarker.ERROR),
            new SessionJournalMarkerDefinition("handover", "Handover note",
                "#0e7490", false, SessionJournalMarker.INFO)));
        SessionJournalMarkerRule install =
            new SessionJournalMarkerRule("software-installation", "apt-get install", false);
        SessionJournalMarkerRule outage = new SessionJournalMarkerRule("outage", "segfault|OOM killer", true);
        settings.getSessionJournalMarkerRules().addAll(List.of(install, outage));
        settings.setSessionJournalMarkerRulesEnabled(true);
        return settings;
    }

    /** A closed demo journal so the export dialog can offer real markers and a real count. */
    private static Path demoJournal(GlobalSettings settings, SessionJournalService service) throws Exception {
        ServerConnection connection = new ServerConnection("Web01", "192.168.1.50", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-1234567890ab", settings, List.of(), false);
        session.start();
        session.appendOutputChunk("Reading package lists... Done\n");
        session.appendInputLine("apt-get install nginx");
        session.close();
        Path dir = session.getDirectory();

        // Inside the 08:00–12:00 window the dialog is pre-filled with, so the manual shows a real
        // match count rather than an empty selection.
        service.appendEntry(dir, entry("Installed nginx from the repository",
            "The package and its dependencies were installed without errors.",
            "software-installation", 1L, 2L, 9, 15));
        service.appendEntry(dir, entry("Restarted the service",
            "nginx came back up and answered on port 80.", null, 2L, 2L, 10, 40));
        service.updateDescription(dir, "Nginx rollout on web01");

        // Snapshot the definition into the journal directly: the dialog resolves markers from the
        // document, exactly as it does for a journal that was shared or exported.
        de.kortty.model.SessionJournalDocument document = service.loadDocument(dir);
        SessionJournalMarkers.snapshot(document,
            SessionJournalMarkers.byId("software-installation", settings.getSessionJournalMarkers()));
        service.saveDocument(dir, document);
        return dir;
    }

    private static SessionJournalEntry entry(String title, String text, String markerId,
                                             long fromSeq, long toSeq, int hour, int minute) {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
        entry.setTitle(title);
        entry.setText(text);
        entry.setCreatedAt(java.time.LocalDate.now().atTime(hour, minute)
            .atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime());
        entry.setLogStartSeq(fromSeq);
        entry.setLogEndSeq(toSeq);
        if (markerId != null) {
            entry.setMarkerId(markerId);
            entry.setMarker(SessionJournalMarker.IMPORTANT);
        }
        return entry;
    }

    private static Dialog<ButtonType> buildExportDialog(GlobalSettings settings) throws Exception {
        SessionJournalService service = new SessionJournalService();
        Path journalDir = demoJournal(settings, service);

        SessionJournalExportOptionsDialog.Request request =
            new SessionJournalExportOptionsDialog.Request(
                SessionJournalExportService.Format.PDF, List.of(journalDir), false, null,
                List.of(SessionJournalExportFilter.TimeWindow.ofTimes(
                    java.time.LocalTime.of(8, 0), java.time.LocalTime.of(12, 0))),
                null);
        return SessionJournalExportOptionsDialog.buildForCapture(service, request);
    }

    private static void writeMarkerShot(GlobalSettings settings) throws Exception {
        Dialog<ButtonType> dialog = SessionJournalMarkerDialog.buildForCapture(settings);
        verifyButtonBarFits(dialog, "marker dialog");
        capture(dialog, settings, MARKER_WIDTH, MARKER_HEIGHT, MARKER_FILE);
    }

    /**
     * Guards the screenshot editor's layout: the picture must follow the window instead of
     * sitting at a size computed once, and the note field must keep its five rows rather than
     * eating the space the picture should get.
     */
    private static void verifyEditorLayout(Path journalDir) throws Exception {
        BufferedImage png = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        Path file = journalDir.resolve("editor-check.png");
        javax.imageio.ImageIO.write(png, "png", file.toFile());
        javafx.scene.image.Image image =
            new javafx.scene.image.Image(file.toUri().toURL().toExternalForm());

        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.SCREENSHOT);
        entry.setScreenshotFile("editor-check.png");
        var capture = SessionJournalScreenshotEditorDialog.buildForCapture(image, entry);
        DialogPane pane = capture.dialog().getDialogPane();
        capture.dialog().show();

        double[] small = measure(pane, 700, 520);
        double[] large = measure(pane, 1300, 950);
        capture.dialog().close();

        System.out.println("layout check (screenshot editor): canvas "
            + Math.round(small[0]) + "x" + Math.round(small[1]) + " at 700x520 -> "
            + Math.round(large[0]) + "x" + Math.round(large[1]) + " at 1300x950; note height "
            + Math.round(small[2]) + " -> " + Math.round(large[2]));

        if (large[0] <= small[0] + 1 || large[1] <= small[1] + 1) {
            throw new IllegalStateException("the picture did not grow with the window");
        }
        // The aspect ratio must survive, or the marks would land in the wrong place.
        double ratio = image.getWidth() / image.getHeight();
        if (Math.abs(large[0] / large[1] - ratio) > 0.01) {
            throw new IllegalStateException("the picture was distorted: " + large[0] / large[1]);
        }
        if (Math.abs(large[2] - small[2]) > 1) {
            throw new IllegalStateException("the note field changed height with the window");
        }
    }

    /**
     * Proves the edit window really remembers its geometry: show it, move and resize it as a user
     * would, close it, and check that a freshly built one comes back the same. A unit test on the
     * clamping arithmetic alone would not catch a broken save or restore hook.
     */
    private static void verifyEditorGeometry(Path journalDir, GlobalSettings settings) throws Exception {
        // A real manager over the isolated home, so save() exercises the production path.
        de.kortty.core.GlobalSettingsManager manager =
            new de.kortty.core.GlobalSettingsManager(journalDir);
        manager.getSettings().setSessionJournalScreenshotEditorGeometry(null);
        SessionJournalScreenshotEditorDialog.setSettingsManagerForCapture(manager);
        settings = manager.getSettings();
        BufferedImage png = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        Path file = journalDir.resolve("geometry-check.png");
        javax.imageio.ImageIO.write(png, "png", file.toFile());
        javafx.scene.image.Image image =
            new javafx.scene.image.Image(file.toUri().toURL().toExternalForm());
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.SCREENSHOT);
        entry.setScreenshotFile("geometry-check.png");

        var first = SessionJournalScreenshotEditorDialog.buildForCapture(image, entry);
        first.dialog().show();
        javafx.stage.Stage stage =
            (javafx.stage.Stage) first.dialog().getDialogPane().getScene().getWindow();
        stage.setX(120);
        stage.setY(90);
        stage.setWidth(1180);
        stage.setHeight(860);
        // close() fires onHidden, which is where the tracked geometry is persisted.
        first.dialog().close();

        de.kortty.model.WindowGeometry stored =
            settings.getSessionJournalScreenshotEditorGeometry();
        System.out.println("layout check (editor geometry): stored "
            + (stored == null ? "nothing" : Math.round(stored.getWidth()) + "x"
                + Math.round(stored.getHeight()) + " at " + Math.round(stored.getX()) + ","
                + Math.round(stored.getY())));
        if (stored == null) {
            throw new IllegalStateException("closing the edit window stored no geometry");
        }
        if (Math.abs(stored.getWidth() - 1180) > 1 || Math.abs(stored.getHeight() - 860) > 1) {
            throw new IllegalStateException("the stored size is not the one the user set");
        }

        var second = SessionJournalScreenshotEditorDialog.buildForCapture(image, entry);
        second.dialog().show();
        javafx.stage.Stage reopened =
            (javafx.stage.Stage) second.dialog().getDialogPane().getScene().getWindow();
        System.out.println("layout check (editor geometry): reopened "
            + Math.round(reopened.getWidth()) + "x" + Math.round(reopened.getHeight())
            + " at " + Math.round(reopened.getX()) + "," + Math.round(reopened.getY()));
        boolean restored = Math.abs(reopened.getWidth() - 1180) <= 1
            && Math.abs(reopened.getHeight() - 860) <= 1
            && Math.abs(reopened.getX() - 120) <= 1
            && Math.abs(reopened.getY() - 90) <= 1;
        second.dialog().close();
        if (!restored) {
            throw new IllegalStateException("the edit window did not reopen where it was left");
        }
    }

    /** Resizes the pane and returns {canvas width, canvas height, note height}. */
    private static double[] measure(DialogPane pane, double width, double height) {
        pane.setPrefSize(width, height);
        pane.resize(width, height);
        pane.applyCss();
        pane.layout();
        javafx.scene.canvas.Canvas canvas = findCanvas(pane);
        javafx.scene.Node note = pane.lookup(".text-area");
        return new double[] {canvas.getWidth(), canvas.getHeight(),
            note != null ? note.getBoundsInParent().getHeight() : 0};
    }

    private static javafx.scene.canvas.Canvas findCanvas(javafx.scene.Parent parent) {
        for (javafx.scene.Node node : parent.getChildrenUnmodifiable()) {
            if (node instanceof javafx.scene.canvas.Canvas canvas) {
                return canvas;
            }
            if (node instanceof javafx.scene.Parent child) {
                javafx.scene.canvas.Canvas found = findCanvas(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The same clipping check for a dialog without scrolling content. */
    private static void verifyButtonBarFits(Dialog<ButtonType> dialog, String label) {
        dialog.show();
        DialogPane pane = dialog.getDialogPane();
        pane.applyCss();
        pane.layout();
        javafx.scene.Node bar = pane.lookup(".button-bar");
        if (bar == null) {
            throw new IllegalStateException(label + ": button bar not found");
        }
        double bottom = pane.sceneToLocal(bar.localToScene(bar.getBoundsInLocal())).getMaxY();
        System.out.println("layout check (" + label + "): button bar ends at " + Math.round(bottom)
            + "px of a " + Math.round(pane.getHeight()) + "px pane");
        if (bottom > pane.getHeight() + 1) {
            throw new IllegalStateException(label + ": button bar is clipped");
        }
        dialog.close();
    }

    /**
     * Guards the two layout faults this dialog had: the button bar being pushed out of the dialog,
     * and no scrollbar once "Add window" makes the content taller than the dialog.
     */
    private static void verifyLayout(Dialog<ButtonType> dialog) {
        dialog.show();
        DialogPane pane = dialog.getDialogPane();
        pane.applyCss();
        pane.layout();

        javafx.scene.control.ScrollPane scroll = (javafx.scene.control.ScrollPane) pane.getContent();
        javafx.scene.layout.VBox rows = findWindowRows(scroll);
        int before = rows.getChildren().size();
        // The default content must fit without scrolling; only added windows may cause a scrollbar.
        double defaultContent = scroll.getContent().getBoundsInParent().getHeight();
        double defaultViewport = scroll.getViewportBounds().getHeight();
        if (defaultContent > defaultViewport + 1) {
            throw new IllegalStateException("the default content already scrolls: "
                + defaultContent + " > " + defaultViewport);
        }
        // Three more windows than the dialog can show at once.
        for (int i = 0; i < 4; i++) {
            clickButton(scroll, I18n.get("journal.export.filter.addWindow"));
        }
        pane.applyCss();
        pane.layout();

        double contentHeight = scroll.getContent().getBoundsInParent().getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        boolean scrolls = contentHeight > viewport;
        double paneHeight = pane.getHeight();
        // The OK/Cancel bar in the pane's own coordinates; lookupButton would give the bounds
        // inside the bar, which says nothing about whether the bar itself fits.
        javafx.scene.Node buttonBar = pane.lookup(".button-bar");
        double buttonBottom = buttonBar != null
            ? pane.sceneToLocal(buttonBar.localToScene(buttonBar.getBoundsInLocal())).getMaxY()
            : Double.NaN;
        System.out.println("layout check: default content " + Math.round(defaultContent)
            + "px fits a " + Math.round(defaultViewport) + "px viewport; windows " + before + " -> " + rows.getChildren().size()
            + ", content " + Math.round(contentHeight) + "px in a " + Math.round(viewport)
            + "px viewport (scrollable=" + scrolls + "), button bar ends at "
            + Math.round(buttonBottom) + "px of a " + Math.round(paneHeight) + "px pane");
        if (Double.isNaN(buttonBottom)) {
            throw new IllegalStateException("button bar not found");
        }
        if (buttonBottom > paneHeight + 1) {
            throw new IllegalStateException("button bar is clipped: " + buttonBottom + " > " + paneHeight);
        }
        if (!scrolls) {
            throw new IllegalStateException("content did not exceed the viewport; scrolling untested");
        }
        // Back to one window for the screenshot.
        rows.getChildren().remove(before, rows.getChildren().size());
        clickButton(scroll, I18n.get("journal.export.filter.addWindow"));
        rows.getChildren().remove(before, rows.getChildren().size());
        pane.applyCss();
        pane.layout();
        dialog.close();
    }

    private static javafx.scene.layout.VBox findWindowRows(javafx.scene.Node root) {
        // The rows live in the first TitledPane's VBox; its children carry the row user data.
        for (javafx.scene.Node node : root.lookupAll(".titled-pane")) {
            javafx.scene.control.TitledPane titled = (javafx.scene.control.TitledPane) node;
            if (titled.getContent() instanceof javafx.scene.layout.VBox box
                && !box.getChildren().isEmpty()
                && box.getChildren().get(0) instanceof javafx.scene.layout.VBox rows) {
                return rows;
            }
        }
        throw new IllegalStateException("time-window rows not found");
    }

    private static void clickButton(javafx.scene.Node root, String text) {
        for (javafx.scene.Node node : root.lookupAll(".button")) {
            if (node instanceof javafx.scene.control.Button button && text.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        throw new IllegalStateException("button not found: " + text);
    }

    private static void capture(Dialog<ButtonType> dialog, GlobalSettings settings,
                                double width, double height, String outputFile) throws Exception {
        // Without a running application DialogThemeHelper cannot resolve the terminal theme, so the
        // dynamic overlay that darkens lists and tables is skipped. Add it here, or the capture
        // would show light table cells on a dark pane.
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
            ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        dialog.show();

        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(width, height);
        pane.setPrefSize(width, height);
        pane.setMaxSize(width, height);
        pane.applyCss();
        pane.resize(width, height);
        pane.layout();

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(params, null);
        dialog.close();

        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File outFile = new File(outputFile);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
        }
        ImageIO.write(buffered, "png", outFile);
        System.out.println("Generated " + outFile.getAbsolutePath()
            + " (" + buffered.getWidth() + "x" + buffered.getHeight() + ")");
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
