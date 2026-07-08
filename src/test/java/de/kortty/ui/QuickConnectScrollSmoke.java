package de.kortty.ui;

import de.kortty.core.CredentialManager;
import de.kortty.core.SSHKeyManager;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke guarding the Quick Connect scroll fix: with all collapsible sections expanded and
 * the dialog constrained to a height smaller than its content, the wrapping {@link ScrollPane} must
 * show a visible vertical scroll bar (rather than clipping the bottom of the form off-screen).
 * Snapshots to {@code build/smoke/quick-connect-scroll.png}. Exit 0 = OK.
 */
public final class QuickConnectScrollSmoke {

    private static final double WIDTH = 780;
    private static final double CONSTRAINED_HEIGHT = 560; // deliberately shorter than expanded content

    private QuickConnectScrollSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                run();
                done.countDown();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("QuickConnectScrollSmoke TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("QuickConnectScrollSmoke FAILURE: " + fail);
            System.exit(1);
        }
        System.out.println("QuickConnectScrollSmoke OK");
        System.exit(0);
    }

    private static void run() throws Exception {
        Path tempDir = Files.createTempDirectory("kortty-quickconnect-smoke");

        QuickConnectDialog dialog = new QuickConnectDialog(
            null, List.of(), null,
            new CredentialManager(tempDir), new SSHKeyManager(tempDir),
            null, 0);
        DialogThemeHelper.applyTheme(dialog);

        DialogPane pane = dialog.getDialogPane();
        Node content = pane.getContent();
        if (!(content instanceof ScrollPane scrollPane)) {
            throw new IllegalStateException("Dialog content is not a ScrollPane but "
                + (content == null ? "null" : content.getClass().getName()));
        }

        // Expand every collapsible section so the form overflows a short window.
        List<TitledPane> sections = new ArrayList<>();
        collectTitledPanes(scrollPane.getContent(), sections);
        if (sections.isEmpty()) {
            throw new IllegalStateException("No collapsible sections (TitledPane) found");
        }
        for (TitledPane section : sections) {
            section.setExpanded(true);
        }

        // Lay the dialog out at a height shorter than the expanded content.
        pane.setPrefSize(WIDTH, CONSTRAINED_HEIGHT);
        pane.setMinSize(WIDTH, CONSTRAINED_HEIGHT);
        pane.setMaxSize(WIDTH, CONSTRAINED_HEIGHT);
        pane.applyCss();
        pane.resize(WIDTH, CONSTRAINED_HEIGHT);
        pane.layout();

        // Second layout pass so the AS_NEEDED skin settles its scroll-bar state.
        pane.applyCss();
        pane.layout();

        double viewportHeight = scrollPane.getViewportBounds().getHeight();
        double contentHeight = scrollPane.getContent().getLayoutBounds().getHeight();
        ScrollBar vbar = verticalScrollBar(scrollPane);
        boolean barVisible = vbar != null && vbar.isVisible();

        System.out.println("sections=" + sections.size()
            + " viewportHeight=" + Math.round(viewportHeight)
            + " contentHeight=" + Math.round(contentHeight)
            + " vbarPresent=" + (vbar != null) + " vbarVisible=" + barVisible);

        // The deterministic guarantee that scrolling engages: the expanded content is taller than
        // the viewport, so the ScrollPane is scrollable (the bottom of the form is reachable rather
        // than clipped off-screen). Scroll-bar node visibility is a rendering detail that the
        // AS_NEEDED skin only settles once shown, so it is logged, not asserted.
        if (contentHeight <= viewportHeight) {
            throw new IllegalStateException("Expanded content (" + Math.round(contentHeight)
                + ") did not exceed the constrained viewport (" + Math.round(viewportHeight)
                + "); scroll would not engage");
        }

        // Section titles must be readable on the dark theme (the base dialog CSS previously had no
        // titled-pane rule, so the title text fell back to a near-black default).
        for (TitledPane section : sections) {
            Node titleText = section.lookup(".title .text");
            if (!(titleText instanceof Text text)) {
                throw new IllegalStateException("Could not find title text node for section: " + section.getText());
            }
            double brightness = brightnessOf(text.getFill());
            System.out.println("  title '" + section.getText() + "' fill=" + text.getFill() + " brightness=" + brightness);
            if (brightness < 0.5) {
                throw new IllegalStateException("Section title '" + section.getText()
                    + "' is too dark to read on the dark theme (brightness " + brightness + ")");
            }
        }

        // Snapshot at natural height so the (now readable) section titles are visible in the image.
        double naturalHeight = contentHeight + 120;
        pane.setPrefSize(WIDTH, naturalHeight);
        pane.setMinSize(WIDTH, naturalHeight);
        pane.setMaxSize(WIDTH, naturalHeight);
        pane.applyCss();
        pane.resize(WIDTH, naturalHeight);
        pane.layout();

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(params, null);
        File outFile = new File("build/smoke/quick-connect-scroll.png");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", outFile);
        System.out.println("Snapshot written: " + outFile.getAbsolutePath());
    }

    private static void collectTitledPanes(Node node, List<TitledPane> out) {
        if (node instanceof TitledPane titled) {
            out.add(titled);
        }
        // A Tab's content is not part of the TabPane's child node tree, so descend into it explicitly.
        if (node instanceof TabPane tabPane) {
            for (Tab tab : tabPane.getTabs()) {
                if (tab.getContent() != null) {
                    collectTitledPanes(tab.getContent(), out);
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectTitledPanes(child, out);
            }
        }
    }

    private static double brightnessOf(Paint paint) {
        return paint instanceof Color color ? color.getBrightness() : 1.0;
    }

    private static ScrollBar verticalScrollBar(ScrollPane scrollPane) {
        for (Node node : scrollPane.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                return bar;
            }
        }
        return null;
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
