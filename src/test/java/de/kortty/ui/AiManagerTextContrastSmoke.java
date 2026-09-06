package de.kortty.ui;

import atlantafx.base.theme.PrimerDark;
import de.kortty.core.LanguageManager;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.GlobalSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed regression harness for the AI Manager's text contrast on dark chrome. Every AtlantaFX app
 * design swaps the user-agent stylesheet, so any inline style naming a Modena-only looked-up colour
 * (such as {@code -fx-text-inner-color}) is dropped and the label falls back to black — unreadable
 * on the dark dialog. This renders the dialog under Primer Dark, fails on any label or profile-list
 * cell that does not clear {@value #MIN_CONTRAST}:1 against the dialog background, and writes
 * {@code build/smoke/ai-manager-text-contrast.png} for visual inspection.
 */
public final class AiManagerTextContrastSmoke {

    /** WCAG AA for large/secondary text; body-sized hints clear far more than this. */
    private static final double MIN_CONTRAST = 3.0;

    private AiManagerTextContrastSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-ai-manager-contrast-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(45, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("AiManagerTextContrastSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("AiManagerTextContrastSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("AiManagerTextContrastSmoke OK");
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        AiManagerDialog dialog = null;
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

            dialog = new AiManagerDialog(null);
            dialog.show();
            DialogPane dialogPane = dialog.getDialogPane();
            Stage stage = (Stage) dialogPane.getScene().getWindow();
            stage.setWidth(1180);
            stage.setHeight(820);

            ObservableList<AiProfile> profiles =
                (ObservableList<AiProfile>) field(dialog, "profiles");
            profiles.setAll(
                profile("MiniMAX", 11_000_000L, 0L),
                profile("AI Profile", 2_800_000L, 20_000L),
                profile("Research budget", 8_200_000L, 10_000L),
                profile("Nightly agent", 9_600_000L, 10_000L));
            ListView<AiProfile> profileList = (ListView<AiProfile>) field(dialog, "profileListView");
            profileList.getSelectionModel().selectFirst();
            profileList.refresh();

            dialogPane.applyCss();
            dialogPane.layout();

            Color background = backgroundOf(dialogPane);
            List<String> offenders = new ArrayList<>();
            collectOffenders(dialogPane, background, offenders);
            snapshot(dialogPane, "ai-manager-text-contrast.png");

            if (!offenders.isEmpty()) {
                throw new IllegalStateException("unreadable text on the dark AI Manager: "
                    + String.join("; ", offenders));
            }
            dialog.close();
            done.countDown();
        } catch (Throwable error) {
            if (dialog != null) {
                dialog.close();
            }
            failure.compareAndSet(null, stack(error));
            done.countDown();
        }
    }

    private static void collectOffenders(Node root, Color background, List<String> offenders) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && hasText(label.getText()) && label.isVisible()) {
                check(label.getText(), label.getTextFill(), background, offenders);
            }
        }
        for (Node node : root.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && hasText(cell.getText()) && cell.isVisible()) {
                check(cell.getText(), cell.getTextFill(), background, offenders);
            }
        }
    }

    private static void check(String text, Paint fill, Color background, List<String> offenders) {
        if (!(fill instanceof Color color)) {
            return;
        }
        double contrast = contrastRatio(color, background);
        if (contrast < MIN_CONTRAST) {
            offenders.add(String.format(Locale.ROOT, "'%s' %s on %s at %.2f:1",
                firstLine(text), toHex(color), toHex(background), contrast));
        }
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    private static Color backgroundOf(Region region) {
        if (region.getBackground() != null) {
            for (BackgroundFill fill : region.getBackground().getFills()) {
                if (fill.getFill() instanceof Color color && color.getOpacity() > 0.9) {
                    return color;
                }
            }
        }
        throw new IllegalStateException("dialog pane has no opaque background to measure against");
    }

    private static double contrastRatio(Color foreground, Color background) {
        double lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
        double darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(Color color) {
        return 0.2126 * channel(color.getRed())
            + 0.7152 * channel(color.getGreen())
            + 0.0722 * channel(color.getBlue());
    }

    private static double channel(double value) {
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static String toHex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
            Math.round(color.getRed() * 255),
            Math.round(color.getGreen() * 255),
            Math.round(color.getBlue() * 255));
    }

    private static AiProfile profile(String name, long usedTokens, long limitThousands) {
        AiProfile profile = new AiProfile();
        profile.setId(name.toLowerCase(Locale.ROOT).replace(' ', '-'));
        profile.setName(name);
        profile.setUsedTotalTokens(usedTokens);
        if (limitThousands > 0) {
            profile.setTokenLimitAmount(limitThousands);
            profile.setTokenLimitUnit(AiTokenLimitUnit.THOUSANDS);
        }
        return profile;
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void snapshot(Node node, String fileName) throws Exception {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(2, 2));
        WritableImage image = node.snapshot(parameters, null);
        File target = new File("build/smoke/" + fileName);
        target.getParentFile().mkdirs();
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", target);
    }

    private static String stack(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
