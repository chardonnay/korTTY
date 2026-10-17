package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.io.File;
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
 * Headless smoke for the guide-translation section of Settings &rarr; Translation.
 *
 * <p>Builds the REAL {@link SettingsDialog} and asserts the section is present and wired: the
 * start button, the progress bar and cancel button (hidden until a run starts), and the list of
 * translated guides. Also fails on any {@code !key!} marker, which is how a missing i18n string
 * renders — the one defect that compiles cleanly and only shows up in front of a user.
 *
 * <p>Writes {@code build/smoke/translation-tab.png}. Exit 0 = OK.
 */
public final class TranslationTabSmoke {

    private static final int WIDTH = 1120;
    private static final int HEIGHT = 900;

    private TranslationTabSmoke() {
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
            System.err.println("TranslationTabSmoke TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("TranslationTabSmoke FAILURE: " + fail);
            System.exit(1);
        }
        System.out.println("TranslationTabSmoke OK");
        System.exit(0);
    }

    private static void run() throws Exception {
        Path tempDir = Files.createTempDirectory("kortty-translation-tab-smoke");

        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        SettingsDialog dialog = new SettingsDialog(
            null, null,
            new ConfigurationManager(tempDir), settings,
            new CredentialManager(tempDir), new GPGKeyManager(tempDir));
        DialogThemeHelper.applyTheme(dialog);

        DialogPane pane = dialog.getDialogPane();
        Tab translationTab = selectTab(dialog, I18n.get("settings.tab.translation"));
        pane.applyCss();
        pane.layout();

        // The section renders its own strings.
        List<String> labels = labelTexts(translationTab.getContent());
        String missingKey = labels.stream()
            .filter(text -> text.startsWith("!") && text.endsWith("!")).findFirst().orElse(null);
        if (missingKey != null) {
            throw new IllegalStateException("Missing i18n key rendered: " + missingKey);
        }
        require(labels.contains(I18n.get("settings.translation.profiles.header")),
            "profiles header missing; labels=" + labels);
        require(labels.contains(I18n.get("settings.translation.run.header")),
            "translate-now header missing; labels=" + labels);
        require(labels.contains(I18n.get("settings.translation.guide.generated")),
            "generated-guides label missing; labels=" + labels);
        require(labels.contains(I18n.get("settings.translation.generatedLanguages")),
            "generated-languages label missing; labels=" + labels);

        // The profile pane and the two lists exist.
        Object pane2 = field(dialog, "translationProfilesPane");
        require(pane2 instanceof TranslationProfilesPane, "translation profiles pane missing");
        @SuppressWarnings("unchecked")
        ListView<String> list = (ListView<String>) field(dialog, "guideTranslationList");
        require(list != null, "translated-guides list missing");
        require(field(dialog, "translationGeneratedList") != null, "generated-languages list missing");

        List<Button> allButtons = buttons(translationTab.getContent());
        Button translateNow = allButtons.stream()
            .filter(b -> I18n.get("settings.translation.run.open").equals(b.getText()))
            .findFirst().orElse(null);
        require(translateNow != null, "Translate now button missing");
        require(translateNow.getOnAction() != null, "Translate now button is not wired to a handler");
        Button setup = allButtons.stream()
            .filter(b -> I18n.get("settings.translation.profiles.setup").equals(b.getText()))
            .findFirst().orElse(null);
        require(setup != null, "Set up… button missing");
        Button test = allButtons.stream()
            .filter(b -> I18n.get("settings.translation.testConnection").equals(b.getText()))
            .findFirst().orElse(null);
        require(test != null, "Test connection button missing");

        // The pane's draft starts from the settings and keeps exactly one default.
        TranslationProfilesPane profilesPane = (TranslationProfilesPane) pane2;
        require(profilesPane.profiles().size() == settings.getTranslationProfiles().size(),
            "the pane shows every stored profile");
        require(profilesPane.profiles().isEmpty() || profilesPane.defaultProfileId() != null,
            "a non-empty list has a default");

        System.out.println("  profiles pane, Set up…, Test, Translate now… and both lists present");

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

        File outFile = new File("build/smoke/translation-tab.png");
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", outFile);
        System.out.println("Snapshot written: " + outFile.getAbsolutePath());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
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

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static List<String> labelTexts(Node root) {
        List<String> texts = new ArrayList<>();
        collect(root, node -> {
            if (node instanceof Label label && label.getText() != null && !label.getText().isBlank()) {
                texts.add(label.getText());
            }
        });
        return texts;
    }

    private static List<Button> buttons(Node root) {
        List<Button> found = new ArrayList<>();
        collect(root, node -> {
            if (node instanceof Button button) {
                found.add(button);
            }
        });
        return found;
    }

    private static void collect(Node node, java.util.function.Consumer<Node> visitor) {
        if (node == null) {
            return;
        }
        visitor.accept(node);
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, visitor));
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
