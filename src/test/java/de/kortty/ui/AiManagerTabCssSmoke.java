package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Border;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Regression harness for the AI Manager's selected primary tab under every app design. A looked-up
 * colour that the active user-agent stylesheet does not define (Modena's {@code -fx-focus-color}
 * under AtlantaFX) makes {@code CssStyleHelper} log a ClassCastException and drop the underline.
 * This opens the real dialog per design, forces CSS, captures the JavaFX CSS log and additionally
 * requires the selected tab to carry a real real (non-transparent) bottom border.
 */
public final class AiManagerTabCssSmoke {

    private AiManagerTabCssSmoke() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home",
            Files.createTempDirectory("kortty-ai-manager-tab-css-smoke").toString());
        Locale.setDefault(Locale.ENGLISH);

        List<String> cssWarnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String text = String.valueOf(record.getMessage());
                if (record.getThrown() != null) {
                    text += " " + record.getThrown();
                }
                if (record.getLevel().intValue() >= Level.WARNING.intValue()
                    && (text.contains("CssStyleHelper") || text.contains("ClassCastException")
                        || text.contains("while converting value") || text.contains("-fx-"))) {
                    synchronized (cssWarnings) {
                        cssWarnings.add(text);
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger("").addHandler(handler);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));
        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("AiManagerTabCssSmoke TIMEOUT");
            System.exit(2);
        }
        synchronized (cssWarnings) {
            if (failure.get() == null && !cssWarnings.isEmpty()) {
                failure.set("JavaFX CSS warnings:\n  " + String.join("\n  ", cssWarnings));
            }
        }
        if (failure.get() != null) {
            System.err.println("AiManagerTabCssSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("AiManagerTabCssSmoke OK (" + AppDesign.values().length + " designs)");
        System.exit(0);
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        AiManagerDialog dialog = null;
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);
            dialog = new AiManagerDialog(null);
            dialog.show();
            DialogPane pane = dialog.getDialogPane();
            List<String> problems = new ArrayList<>();
            for (AppDesign design : AppDesign.values()) {
                AppDesignStyleSupport.applyUserAgentStylesheet(design);
                AppDesignStyleSupport.applyToOpenWindows(design);
                pane.applyCss();
                pane.layout();
                TabPane tabs = (TabPane) pane.lookup(".ai-manager-primary-navigation");
                for (int index : new int[] {0, 1}) {
                    tabs.getSelectionModel().select(index);
                    pane.applyCss();
                    pane.layout();
                    Node header = tabs.lookupAll(".tab").stream()
                        .filter(node -> node.getStyleClass().contains("ai-manager-primary-tab")
                            && node.getPseudoClassStates().stream()
                                .anyMatch(state -> state.getPseudoClassName().equals("selected")))
                        .findFirst().orElse(null);
                    if (index == 0 && design.name().startsWith("ATLANTAFX_PRIMER")) {
                        java.nio.file.Path dir = java.nio.file.Path.of("build", "smoke");
                        Files.createDirectories(dir);
                        javafx.scene.image.WritableImage image = pane.snapshot(null, null);
                        javax.imageio.ImageIO.write(
                            javafx.embed.swing.SwingFXUtils.fromFXImage(image, null), "png",
                            dir.resolve("ai-manager-tab-css-" + design.name().toLowerCase() + ".png").toFile());
                    }
                    if (header == null) {
                        problems.add(design + ": no selected header for tab " + index);
                        continue;
                    }
                    Border border = ((Region) header).getBorder();
                    boolean coloured = border != null && !border.getStrokes().isEmpty()
                        && border.getStrokes().get(0).getBottomStroke() != null
                        && !Color.TRANSPARENT.equals(border.getStrokes().get(0).getBottomStroke());
                    if (!coloured) {
                        problems.add(design + ": selected tab has no real (non-transparent) bottom border");
                    }
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException(String.join("; ", problems));
            }
            dialog.close();
            done.countDown();
        } catch (Throwable error) {
            if (dialog != null) {
                dialog.close();
            }
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));
            failure.compareAndSet(null, writer.toString());
            done.countDown();
        }
    }
}
