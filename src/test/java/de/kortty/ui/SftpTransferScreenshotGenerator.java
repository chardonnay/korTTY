package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.sftp.SftpChannelSource;
import de.kortty.core.sftp.transfer.ConflictInfo;
import de.kortty.core.sftp.transfer.ConflictResolver;
import de.kortty.core.sftp.transfer.SftpTransferQueue;
import de.kortty.core.sftp.transfer.TransferDirection;
import de.kortty.core.sftp.transfer.TransferItem;
import de.kortty.core.sftp.transfer.TransferScreenshotDemo;
import de.kortty.core.sftp.transfer.TransferSettings;
import de.kortty.core.sftp.transfer.TransferState;
import de.kortty.model.GlobalSettings;
import de.kortty.ui.sftp.SftpConflictViewModel;
import de.kortty.ui.sftp.SftpScreenshotAccess;
import de.kortty.ui.sftp.SftpTransferQueuePane;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javax.imageio.ImageIO;
import org.apache.sshd.sftp.client.SftpClient;

/**
 * Offline generator for the manual's SFTP transfer screenshots, in the Normal design with demo
 * data only (no server, no real hosts):
 *
 * <ul>
 *   <li>{@code app-docs/screenshots/sftp/sftp-transfer-list.png}: the real
 *       {@link SftpTransferQueuePane} fed with {@link TransferScreenshotDemo}'s rows (a folder upload
 *       with a failed file inside, a large download in progress, finished and waiting rows);</li>
 *   <li>{@code app-docs/screenshots/sftp/sftp-conflict-dialog.png}: the real "File already exists"
 *       dialog from {@code SftpConflictDialog.build} for an upload onto an older file.</li>
 * </ul>
 *
 * <p>Both are snapshotted at 2x. Run via the {@code generateSftpTransferScreenshots} Gradle task,
 * then {@code ./scripts/optimize-png.sh}. Exit 0 = OK.</p>
 */
public final class SftpTransferScreenshotGenerator {

    private static final String LIST_FILE = "app-docs/screenshots/sftp/sftp-transfer-list.png";
    private static final String DIALOG_FILE = "app-docs/screenshots/sftp/sftp-conflict-dialog.png";
    private static final double LIST_WIDTH = 1240;
    private static final double LIST_HEIGHT = 172;

    private SftpTransferScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH); // JavaFX's own button labels follow the JVM locale
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                GlobalSettings settings = new GlobalSettings();
                settings.setLanguage("en");
                LanguageManager.getInstance().initialize(settings);
                writeTransferList(settings);
                writeConflictDialog(settings);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + failure.get());
            System.exit(1);
        }
        System.exit(0);
    }

    private static void writeTransferList(GlobalSettings settings) throws Exception {
        SftpTransferQueuePane pane = new SftpTransferQueuePane();
        SftpTransferQueue queue = new SftpTransferQueue(new NoServer(), TransferSettings.defaults(),
            ConflictResolver.always(de.kortty.core.sftp.transfer.ConflictAction.SKIP));
        pane.setQueue(queue);
        List<TransferItem> items = TransferScreenshotDemo.items();
        pane.listener().itemsAdded(items);
        SftpScreenshotAccess.flush(pane);
        pane.dispose();
        queue.close();

        @SuppressWarnings("unchecked")
        TableView<TransferItem> table = (TableView<TransferItem>) pane.lookup(".sftp-transfer-table");
        table.setPrefHeight(LIST_HEIGHT);
        TransferItem failed = items.stream().filter(item -> item.state() == TransferState.FAILED)
            .findFirst().orElseThrow();
        table.getSelectionModel().select(failed);

        VBox root = new VBox(pane);
        root.setPadding(new Insets(10));
        root.getStyleClass().add("file-browser-panel");
        var fileBrowserCss = SftpTransferQueuePane.class.getResource("/styles/filebrowser.css");
        if (fileBrowserCss != null) {
            root.getStylesheets().add(fileBrowserCss.toExternalForm());
        }
        capturePanel(root, settings, LIST_WIDTH, LIST_HEIGHT + 64, LIST_FILE);
    }

    private static void writeConflictDialog(GlobalSettings settings) throws Exception {
        long source = LocalDateTime.of(2026, 10, 4, 9, 41).toInstant(ZoneOffset.UTC).toEpochMilli();
        long target = LocalDateTime.of(2026, 9, 28, 17, 5).toInstant(ZoneOffset.UTC).toEpochMilli();
        ConflictInfo info = ConflictInfo.files(TransferDirection.UPLOAD, "demo/projects/website/index.html",
            "/var/www/website", "index.html", 49_152, 46_310, source, target);
        SftpConflictViewModel model = SftpConflictViewModel.of(info, ZoneOffset.UTC, Locale.ENGLISH);
        var dialog = SftpScreenshotAccess.conflictDialog(model);
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        dialog.show();
        DialogPane pane = dialog.getDialogPane();
        pane.applyCss();
        double width = Math.ceil(pane.prefWidth(-1));
        double height = Math.ceil(pane.prefHeight(width));
        pane.resize(width, height);
        pane.layout();
        write(snapshot(pane), DIALOG_FILE);
        dialog.setResult(ConflictResolver.Resolution.CANCEL_ALL);
        dialog.close();
    }

    /** Themes a plain panel like the SFTP tab and snapshots it on an off-screen stage. */
    private static void capturePanel(Parent panel, GlobalSettings settings, double width, double height,
            String outputFile) throws Exception {
        var terminalCss = SftpTransferScreenshotGenerator.class.getResource("/styles/terminal.css");
        if (terminalCss != null) {
            panel.getStylesheets().add(0, terminalCss.toExternalForm());
        }
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            panel.getStylesheets().add(dynamicCss);
        }
        AppDesignStyleSupport.applyToParent(panel);

        Stage stage = new Stage(StageStyle.UNDECORATED);
        stage.setScene(new Scene(panel, width, height));
        stage.setX(-4000);
        stage.setY(-4000);
        stage.show();
        panel.applyCss();
        panel.layout();
        WritableImage image = snapshot(panel);
        stage.close();
        write(image, outputFile);
    }

    private static WritableImage snapshot(Node node) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        return node.snapshot(params, null);
    }

    private static void write(WritableImage image, String outputFile) throws IOException {
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

    /** A source the queue never uses: the demo rows are fed straight into the pane. */
    private static final class NoServer implements SftpChannelSource {
        @Override
        public SftpClient primaryClient() {
            throw new IllegalStateException("no server in the screenshot generator");
        }

        @Override
        public SftpClient openChannel() throws IOException {
            throw new IOException("no server in the screenshot generator");
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public boolean ownsSession() {
            return true;
        }

        @Override
        public String describe() {
            return "demo";
        }
    }
}
