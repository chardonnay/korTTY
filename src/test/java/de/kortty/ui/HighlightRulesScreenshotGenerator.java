package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's screenshot of the highlight rule-set editor.
 *
 * <p>Builds the REAL {@link HighlightRulesDialog} headless against an isolated {@code user.home}, with a
 * demo rule set ("Web servers") and the editor's own test text, which uses documentation addresses
 * only — no real host ends up in the manual. One rule is switched off so the Check column shows a
 * state, and the regular-expression rule is selected so the details show a theme background color.
 *
 * <p>Run via the {@code generateHighlightRulesScreenshot} Gradle task. Exit 0 = OK.
 */
public final class HighlightRulesScreenshotGenerator {

    private static final double WIDTH = 980;
    private static final double HEIGHT = 700;

    private static final String OUTPUT_FILE = "app-docs/screenshots/highlighting/rules-dialog.png";

    private static final String DEMO_SET_ID = "demo-web-servers";

    private HighlightRulesScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-highlight-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        System.setProperty("TEST_MODE_KORTTY", "1");
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                GlobalSettings settings = demoSettings();
                LanguageManager.getInstance().initialize(settings);
                capture(HighlightRulesDialog.buildForCapture(settings, DEMO_SET_ID, 1, HighlightRulesDialog.DEFAULT_SAMPLE),
                    settings);
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
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + fail);
            System.exit(1);
        }
        System.exit(0);
    }

    private static GlobalSettings demoSettings() {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        List<HighlightRule> rules = new ArrayList<>();
        rules.add(rule("nginx", false, "ansi:6", null, true, false, false));
        rules.add(rule("HTTP/1\\.1 5\\d\\d", true, "ansi:15", "ansi:1", true, false, false));
        rules.add(rule("(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?", true, "ansi:5", null, false, false, true));
        HighlightRule warning = rule("WARNING", false, "ansi:11", null, true, false, false);
        warning.setEnabled(false);
        rules.add(warning);
        HighlightRule timedOut = rule("timed out", false, null, "#4A1C1C", false, false, false);
        timedOut.setScope(HighlightRule.Scope.LINE);
        rules.add(timedOut);
        rules.add(rule("deprecated", false, "ansi:3", null, false, true, false));
        settings.setHighlightRuleSets(new ArrayList<>(List.of(new HighlightRuleSet(DEMO_SET_ID, "Web servers", rules))));
        return settings;
    }

    private static HighlightRule rule(String pattern, boolean regex, String foreground, String background,
                                      boolean bold, boolean italic, boolean underline) {
        HighlightRule rule = new HighlightRule(pattern, regex);
        rule.setWholeWord(!regex);
        rule.setForeground(foreground);
        rule.setBackground(background);
        rule.setBold(bold);
        rule.setItalic(italic);
        rule.setUnderline(underline);
        return rule;
    }

    private static void capture(Dialog<ButtonType> dialog, GlobalSettings settings) throws Exception {
        // Without a running application DialogThemeHelper cannot resolve the terminal theme, so the
        // dynamic overlay that darkens lists and tables is skipped. Add it here, or the capture would
        // show light table cells on a dark pane.
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        dialog.show();

        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(WIDTH, HEIGHT);
        pane.setPrefSize(WIDTH, HEIGHT);
        pane.setMaxSize(WIDTH, HEIGHT);
        pane.applyCss();
        pane.resize(WIDTH, HEIGHT);
        pane.layout();

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(params, null);
        dialog.close();

        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File outFile = new File(OUTPUT_FILE);
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
