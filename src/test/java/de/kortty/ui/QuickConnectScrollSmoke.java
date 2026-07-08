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

        // The scroll pane now lives INSIDE the individual-connection tab, directly around the form
        // VBox (no TabPane between it and the growing content — that indirection was the clipping bug).
        ScrollPane scrollPane = findScrollPane(pane.getContent());
        if (scrollPane == null) {
            throw new IllegalStateException("No ScrollPane found inside the dialog content");
        }
        if (!(scrollPane.getContent() instanceof javafx.scene.layout.VBox)) {
            throw new IllegalStateException("ScrollPane content is not the form VBox but "
                + scrollPane.getContent().getClass().getName()
                + " — the scroll pane must wrap the form directly");
        }

        List<TitledPane> sections = new ArrayList<>();
        collectTitledPanes(scrollPane.getContent(), sections);
        if (sections.isEmpty()) {
            throw new IllegalStateException("No collapsible sections (TitledPane) found");
        }
        double screenHeight = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();

        // Fixed, compact preferred viewport height, bounded well below the screen: the window chrome
        // (header, tab strip, buttons, ~350px) plus this must always fit, and the form is taller than
        // this even collapsed, so the scroll bar engages and there is no dead space.
        double viewport = scrollPane.getPrefViewportHeight();
        if (viewport <= 0 || viewport > 620 || viewport > screenHeight * 0.55 + 1) {
            throw new IllegalStateException("Viewport height " + Math.round(viewport)
                + " is not a bounded compact value (expected <= min(620, 0.55*screen="
                + Math.round(screenHeight * 0.55) + "))");
        }

        // Lay the dialog out at its OWN preferred size — exactly what the real window does. The
        // regression this reproduces: at the real (fixed) dialog height, expanding a section must
        // grow the scroll range instead of clipping the new content.
        pane.setPrefWidth(WIDTH);
        pane.applyCss();
        double paneHeight = pane.prefHeight(WIDTH);
        pane.resize(WIDTH, paneHeight);
        pane.layout();

        Node form = scrollPane.getContent();
        double collapsedContent = form.getLayoutBounds().getHeight();
        System.out.println("collapsed: paneHeight=" + Math.round(paneHeight)
            + " content=" + Math.round(collapsedContent)
            + " viewport=" + Math.round(viewport) + " screen=" + Math.round(screenHeight));
        if (collapsedContent <= viewport) {
            throw new IllegalStateException("Collapsed content (" + Math.round(collapsedContent)
                + ") does not exceed the viewport (" + Math.round(viewport)
                + ") — the dialog could leave dead space");
        }

        // Expand all sections WITHOUT resizing the pane (the real window does not resize) — the form
        // must grow and must NOT be clipped: its laid-out height has to match its own fresh preferred
        // height. This is the exact failure the user hit (expanded sections cut off mid-row).
        for (TitledPane section : sections) {
            section.setExpanded(true);
        }
        pane.applyCss();
        pane.layout();
        double contentHeight = form.getLayoutBounds().getHeight();
        double contentPref = form.prefHeight(form.getLayoutBounds().getWidth());
        ScrollBar vbar = verticalScrollBar(scrollPane);
        boolean barVisible = vbar != null && vbar.isVisible();
        System.out.println("expanded: content=" + Math.round(contentHeight)
            + " contentPref=" + Math.round(contentPref)
            + " viewport=" + Math.round(viewport)
            + " vbarPresent=" + (vbar != null) + " vbarVisible=" + barVisible);
        if (contentHeight <= collapsedContent + 50) {
            throw new IllegalStateException("Expanding sections did not grow the scrollable content ("
                + Math.round(collapsedContent) + " -> " + Math.round(contentHeight)
                + ") — expanded content would be clipped");
        }
        if (contentHeight < contentPref - 4) {
            throw new IllegalStateException("Form is laid out at " + Math.round(contentHeight)
                + " but prefers " + Math.round(contentPref)
                + " — the expanded content is CLIPPED instead of scrollable");
        }
        if (contentHeight <= viewport) {
            throw new IllegalStateException("Expanded content (" + Math.round(contentHeight)
                + ") did not exceed the viewport (" + Math.round(viewport) + "); scroll would not engage");
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

    private static ScrollPane findScrollPane(Node node) {
        if (node instanceof ScrollPane scrollPane) {
            return scrollPane;
        }
        if (node instanceof TabPane tabPane) {
            for (Tab tab : tabPane.getTabs()) {
                ScrollPane found = tab.getContent() != null ? findScrollPane(tab.getContent()) : null;
                if (found != null) {
                    return found;
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ScrollPane found = findScrollPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
