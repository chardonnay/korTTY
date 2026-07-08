package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.JvmResourceProfile;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke for the Settings &rarr; Resources tab. Builds the REAL {@link SettingsDialog},
 * selects Resources, drives the profile combo through all three profiles, and asserts that the
 * live max-heap line is populated (and free of missing-i18n-key markers) for each. Also writes a
 * snapshot to {@code build/smoke/resources-tab.png}. Exit 0 = OK.
 */
public final class ResourcesTabSmoke {

    private static final int WIDTH = 1120;
    private static final int HEIGHT = 700;

    private ResourcesTabSmoke() {
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
            System.err.println("ResourcesTabSmoke TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("ResourcesTabSmoke FAILURE: " + fail);
            System.exit(1);
        }
        System.out.println("ResourcesTabSmoke OK");
        System.exit(0);
    }

    private static void run() throws Exception {
        Path tempDir = Files.createTempDirectory("kortty-resources-smoke");

        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        SettingsDialog dialog = new SettingsDialog(
            null, null,
            new ConfigurationManager(tempDir), settings,
            new CredentialManager(tempDir), new GPGKeyManager(tempDir));
        DialogThemeHelper.applyTheme(dialog);

        DialogPane pane = dialog.getDialogPane();
        Tab resourcesTab = selectTab(dialog, I18n.get("settings.tab.resources"));

        @SuppressWarnings("unchecked")
        ComboBox<JvmResourceProfile> combo = (ComboBox<JvmResourceProfile>) field(dialog, "jvmResourceProfileCombo");

        // Drive every profile and assert the tab shows a populated, non-error max-heap line.
        for (JvmResourceProfile profile : JvmResourceProfile.values()) {
            combo.setValue(profile);
            pane.applyCss();
            pane.layout();
            List<String> labels = labelTexts(resourcesTab.getContent());
            boolean hasHeapLine = labels.stream().anyMatch(t -> t.contains("GB"));
            if (!hasHeapLine) {
                throw new IllegalStateException("No max-heap line (containing 'GB') for profile " + profile
                    + "; labels=" + labels);
            }
            String missingKey = labels.stream().filter(t -> t.startsWith("!") && t.endsWith("!")).findFirst().orElse(null);
            if (missingKey != null) {
                throw new IllegalStateException("Missing i18n key rendered for profile " + profile + ": " + missingKey);
            }
        }

        // Snapshot on the Maximum profile so the highest ceiling is visible.
        combo.setValue(JvmResourceProfile.MAXIMUM);
        selectTab(dialog, I18n.get("settings.tab.resources"));
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

        File outFile = new File("build/smoke/resources-tab.png");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", outFile);
        System.out.println("Snapshot written: " + outFile.getAbsolutePath());
    }

    private static Tab selectTab(SettingsDialog dialog, String title) throws Exception {
        TabPane tabPane = (TabPane) field(dialog, "mainTabPane");
        for (Tab tab : tabPane.getTabs()) {
            if (title.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                return tab;
            }
        }
        throw new IllegalStateException("Tab not found: " + title);
    }

    private static Object field(SettingsDialog dialog, String name) throws Exception {
        Field f = SettingsDialog.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(dialog);
    }

    private static List<String> labelTexts(Node root) {
        List<String> out = new ArrayList<>();
        collectLabels(root, out);
        return out;
    }

    private static void collectLabels(Node node, List<String> out) {
        if (node instanceof Label label && label.getText() != null && !label.getText().isBlank()) {
            out.add(label.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectLabels(child, out);
            }
        }
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
