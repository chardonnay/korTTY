package de.kortty.ui;

import de.kortty.core.AsciiArtPictureRenderer;
import de.kortty.core.LanguageManager;
import de.kortty.model.AsciiArtCommentStyle;
import de.kortty.model.AsciiArtPictureSize;
import de.kortty.model.AsciiArtPrintStyle;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the ASCII Art dialog. It builds the real dialog owner-less (so the AI
 * tab's controls are disabled, exactly as without a profile), fills the banner preview, puts a
 * locally rendered picture into the AI preview, exercises the copy-format row (mutual exclusion of
 * comment and print styles, the gap, the tooltip preview) and snapshots the pane to
 * {@code build/smoke/ascii-art-*.png}. Run via the {@code asciiArtDialogSmoke} Gradle task. Exit 0 = OK.
 */
public final class AsciiArtDialogSmoke {

    private static final String HOUSE_SVG = "<svg viewBox=\"0 0 100 100\">"
        + "<circle cx=\"84\" cy=\"16\" r=\"8\" fill=\"#aaa\"/>"
        + "<rect x=\"0\" y=\"80\" width=\"100\" height=\"20\" fill=\"#aaa\"/>"
        + "<polygon points=\"4,80 15,42 26,80\" fill=\"#555\"/>"
        + "<rect x=\"13\" y=\"80\" width=\"4\" height=\"8\" fill=\"black\"/>"
        + "<polygon points=\"70,80 82,36 94,80\" fill=\"#555\"/>"
        + "<rect x=\"80\" y=\"80\" width=\"4\" height=\"8\" fill=\"black\"/>"
        + "<rect x=\"34\" y=\"58\" width=\"32\" height=\"24\" fill=\"#555\"/>"
        + "<polygon points=\"30,58 50,38 70,58\" fill=\"black\"/>"
        + "<rect x=\"58\" y=\"42\" width=\"5\" height=\"10\" fill=\"black\"/>"
        + "<rect x=\"46\" y=\"68\" width=\"8\" height=\"14\" fill=\"black\"/>"
        + "<rect x=\"38\" y=\"63\" width=\"7\" height=\"7\" fill=\"white\"/>"
        + "</svg>";

    private AsciiArtDialogSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                render();
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed: " + e);
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("asciiArtDialogSmoke OK");
    }

    private static void render() throws Exception {
        AsciiArtBannerDialog dialog = new AsciiArtBannerDialog(null);
        DialogPane pane = dialog.getDialogPane();

        // 1) Banner tab with a rendered FIGlet banner.
        TextField inputField = field(dialog, "inputField");
        inputField.setText("korTTY");
        realize(pane);
        TextArea outputArea = field(dialog, "outputArea");
        if (outputArea.getText() == null || outputArea.getText().isBlank()) {
            throw new AssertionError("banner preview stayed empty");
        }
        snapshotPane(pane, "ascii-art-dialog.png", 680);

        // 2) AI tab: disabled controls without an owner, a locally rendered picture in the preview.
        TabPane tabPane = field(dialog, "tabPane");
        tabPane.getSelectionModel().select(1);
        Button cancelButton = field(dialog, "cancelButton");
        if (cancelButton.isVisible() || cancelButton.isManaged()) {
            throw new AssertionError("Cancel must be hidden while nothing runs");
        }
        ComboBox<AsciiArtPictureSize> sizeCombo = field(dialog, "sizeCombo");
        if (sizeCombo.getItems().size() != AsciiArtPictureSize.values().length
            || sizeCombo.getValue() != AsciiArtPictureSize.DEFAULT) {
            throw new AssertionError("size selector is not populated with the default selected");
        }
        AsciiArtPictureRenderer.RenderResult rendered = AsciiArtPictureRenderer.render(HOUSE_SVG, 60, 30, null);
        if (!rendered.isAccepted() || rendered.text() == null) {
            throw new AssertionError("the smoke house did not render: " + rendered.rejection());
        }
        TextArea aiOutputArea = field(dialog, "aiOutputArea");
        aiOutputArea.setText(rendered.text());
        // The AI snapshot doubles as the guide's screenshot: show the tab as it looks after a
        // successful run (subject filled in, controls enabled, status line empty) rather than the
        // owner-less "no profile" state the harness is actually in.
        TextField subjectField = field(dialog, "subjectField");
        subjectField.setText("Haus im Wald");
        Label aiStatusLabel = field(dialog, "aiStatusLabel");
        aiStatusLabel.setText("");
        for (String name : List.of("subjectField", "sizeCombo", "aiProfileCombo", "generateButton", "retryButton")) {
            Node control = field(dialog, name);
            control.setDisable(false);
        }
        realize(pane);
        snapshotPane(pane, "ascii-art-dialog-ai.png", 680);

        // 3) Image tab: a synthetic "photo" (a shaded ball on a light floor) converted locally,
        //    invert flips the tones, reset returns the sliders to neutral.
        tabPane.getSelectionModel().select(2);
        dialog.showImage(shadedBall(320, 240), "ball.png");
        TextArea imageOutputArea = field(dialog, "imageOutputArea");
        String plain = imageOutputArea.getText();
        if (plain == null || plain.isBlank()) {
            throw new AssertionError("image preview stayed empty");
        }
        javafx.scene.control.CheckBox invertCheck = field(dialog, "invertCheck");
        invertCheck.setSelected(true);
        if (plain.equals(imageOutputArea.getText())) {
            throw new AssertionError("invert did not change the picture");
        }
        javafx.scene.control.Slider contrastSlider = field(dialog, "contrastSlider");
        contrastSlider.setValue(60);
        Button imageResetButton = field(dialog, "imageResetButton");
        imageResetButton.fire();
        if (invertCheck.isSelected() || contrastSlider.getValue() != 0 || !plain.equals(imageOutputArea.getText())) {
            throw new AssertionError("reset did not restore the neutral picture");
        }
        Label imageStatusLabel = field(dialog, "imageStatusLabel");
        if (!imageStatusLabel.getText().contains("320")) {
            throw new AssertionError("image status does not name the source size: " + imageStatusLabel.getText());
        }
        realize(pane);
        snapshotPane(pane, "ascii-art-dialog-image.png", 680);
        tabPane.getSelectionModel().select(1);

        // 4) Copy row: choosing a print style clears the comment style and vice versa, the tooltip
        //    shows the first line as it will be copied.
        ComboBox<AsciiArtCommentStyle> commentCombo = field(dialog, "commentCombo");
        ComboBox<AsciiArtPrintStyle> printCombo = field(dialog, "printCombo");
        Spinner<Integer> gapSpinner = field(dialog, "gapSpinner");
        Tooltip copyTooltip = field(dialog, "copyTooltip");

        commentCombo.setValue(AsciiArtCommentStyle.HASH);
        if (printCombo.getValue() != AsciiArtPrintStyle.NONE) {
            throw new AssertionError("a comment style must reset the print style");
        }
        if (!copyTooltip.getText().contains("#")) {
            throw new AssertionError("tooltip does not preview the comment marker: " + copyTooltip.getText());
        }
        printCombo.setValue(AsciiArtPrintStyle.PYTHON);
        if (commentCombo.getValue() != AsciiArtCommentStyle.NONE) {
            throw new AssertionError("a print style must reset the comment style");
        }
        gapSpinner.getValueFactory().setValue(3);
        if (!copyTooltip.getText().contains("print(\"   ")) {
            throw new AssertionError("tooltip does not preview the print statement with the gap: " + copyTooltip.getText());
        }
        realize(pane);
        snapshotPane(pane, "ascii-art-copy-row.png", 680);
        System.out.println("Copy tooltip: " + copyTooltip.getText());

        // 5) Closing writes the window geometry together with the UI font scale stamp that
        //    validates the stored size on the next open. The harness never shows the dialog, so the
        //    geometry the shown dialog would have tracked is seeded the way the tracker parks it.
        WindowGeometry moved = new WindowGeometry(120, 80, 1410, 1120);
        pane.getProperties().put(DialogGeometrySupport.TRACKED_KEY, moved);
        GlobalSettings settings = new GlobalSettings();
        settings.setUiFontScalePercentAtGeometrySave(UiFontScaleSupport.effectivePercent() + 25);
        dialog.writeState(settings);
        WindowGeometry stored = settings.getAsciiArtDialogGeometry();
        if (stored == null || stored.getWidth() != 1410 || stored.getHeight() != 1120
            || stored.getX() != 120 || stored.getY() != 80) {
            throw new AssertionError("window geometry was not stored: " + stored);
        }
        if (settings.getUiFontScalePercentAtGeometrySave() == null
            || settings.getUiFontScalePercentAtGeometrySave() != UiFontScaleSupport.effectivePercent()) {
            throw new AssertionError("the UI font scale stamp was not refreshed: "
                + settings.getUiFontScalePercentAtGeometrySave());
        }
        if (settings.getAsciiArtCopyPrintStyle() != AsciiArtPrintStyle.PYTHON || settings.getAsciiArtCopyGap() != 3) {
            throw new AssertionError("copy settings were not stored");
        }
        System.out.println("Stored geometry: " + stored.getWidth() + " x " + stored.getHeight()
            + " at " + stored.getX() + "," + stored.getY() + ", scale stamp "
            + settings.getUiFontScalePercentAtGeometrySave());
    }

    /** A grey ball lit from the top left on a light floor: mid tones, a highlight and a shadow. */
    private static java.awt.image.BufferedImage shadedBall(int width, int height) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        double cx = width * 0.45;
        double cy = height * 0.45;
        double radius = Math.min(width, height) * 0.36;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = (x - cx) / radius;
                double dy = (y - cy) / radius;
                double d2 = dx * dx + dy * dy;
                double tone;
                if (d2 <= 1.0) {
                    double nz = Math.sqrt(1.0 - d2);
                    double light = Math.max(0.0, (-dx * 0.5 - dy * 0.5 + nz * 0.7) / 1.0);
                    tone = 0.25 + 0.7 * light;
                } else {
                    tone = y > cy + radius * 0.9 ? 0.78 : 0.92;
                }
                int level = (int) Math.round(Math.max(0.0, Math.min(1.0, tone)) * 255.0);
                image.setRGB(x, y, (level << 16) | (level << 8) | level);
            }
        }
        return image;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    private static void realize(DialogPane pane) {
        pane.applyCss();
        pane.layout();
    }

    private static void snapshotPane(DialogPane pane, String fileName, double minWidth) throws Exception {
        pane.applyCss();
        pane.layout();
        double width = Math.max(pane.prefWidth(-1), minWidth);
        double height = Math.max(pane.prefHeight(width), 300);
        pane.resize(width, height);
        pane.applyCss();
        pane.layout();
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        WritableImage image = pane.snapshot(params, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }
}
