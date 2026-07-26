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
        require(labels.contains(I18n.get("settings.translation.guide.section")),
            "guide section header missing; labels=" + labels);
        require(labels.contains(I18n.get("settings.translation.guide.generated")),
            "generated-guides label missing; labels=" + labels);

        // The controls exist and start in the right state.
        ProgressBar progress = (ProgressBar) field(dialog, "guideTranslationProgress");
        Button cancel = (Button) field(dialog, "guideTranslationCancelButton");
        @SuppressWarnings("unchecked")
        ListView<String> list = (ListView<String>) field(dialog, "guideTranslationList");
        require(progress != null && !progress.isVisible(),
            "progress bar should exist and be hidden before a run starts");
        require(cancel != null && !cancel.isVisible(),
            "cancel button should exist and be hidden before a run starts");
        require(cancel != null && cancel.getOnAction() == null,
            "cancel must only be armed once a translation is running");
        require(list != null, "translated-guides list missing");

        List<Button> allButtons = buttons(translationTab.getContent());
        Button start = allButtons.stream()
            .filter(b -> I18n.get("settings.translation.guide.generate").equals(b.getText()))
            .findFirst().orElse(null);
        require(start != null, "start button missing");
        require(start.getOnAction() != null, "start button is not wired to a handler");
        require(!start.isDisabled(), "start button should be enabled");

        // The estimate is what a user reaches for before committing to a multi-hour run, so it
        // has to be there and reachable without starting one.
        Button estimate = allButtons.stream()
            .filter(b -> I18n.get("settings.translation.guide.estimate").equals(b.getText()))
            .findFirst().orElse(null);
        require(estimate != null, "estimate button missing");
        require(estimate.getOnAction() != null, "estimate button is not wired to a handler");
        require(!estimate.isDisabled(), "estimate button should be enabled");

        // The profile picker decides which model spends the next few hours, and its first entry
        // must be the default so the section behaves as before when nothing is chosen.
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<Object> profileCombo =
            (javafx.scene.control.ComboBox<Object>) field(dialog, "guideAiProfileCombo");
        require(profileCombo != null, "AI profile combo missing");
        require(!profileCombo.getItems().isEmpty(), "AI profile combo is empty");
        require(profileCombo.getItems().getFirst() == null,
            "the first entry must be the default profile (null)");
        require(profileCombo.getValue() == null, "the default profile must be preselected");
        String rendered = profileCombo.getConverter().toString(null);
        require(I18n.get("settings.translation.guide.profileDefault").equals(rendered),
            "default entry renders as '" + rendered + "'");

        // The interface strings get the same choice as the guide. Without it that run was pinned to
        // the default text profile and refused anything but a local model, so a cloud or CLI
        // profile could not translate the UI at all.
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<Object> interfaceCombo =
            (javafx.scene.control.ComboBox<Object>) field(dialog, "interfaceAiProfileCombo");
        require(interfaceCombo != null, "interface AI profile combo missing");
        require(!interfaceCombo.getItems().isEmpty(), "interface AI profile combo is empty");
        require(interfaceCombo.getItems().getFirst() == null,
            "the first entry must be the default profile (null)");
        require(interfaceCombo.getValue() == null, "the default profile must be preselected");
        String interfaceRendered = interfaceCombo.getConverter().toString(null);
        require(I18n.get("settings.translation.profileDefault").equals(interfaceRendered),
            "default entry renders as '" + interfaceRendered + "'");
        require(labels.contains(I18n.get("settings.translation.profile")),
            "interface profile label missing; labels=" + labels);

        // It only means anything for the AI provider, so it tracks that dropdown both ways.
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<de.kortty.model.TranslationApiProvider> providerCombo =
            (javafx.scene.control.ComboBox<de.kortty.model.TranslationApiProvider>)
                field(dialog, "translationProviderCombo");
        require(providerCombo != null, "translation provider combo missing");
        providerCombo.setValue(de.kortty.model.TranslationApiProvider.GOOGLE_TRANSLATE);
        require(interfaceCombo.isDisabled(),
            "profile combo must be disabled while a translation API is selected");
        providerCombo.setValue(de.kortty.model.TranslationApiProvider.LOCAL_AI_PROFILE);
        require(!interfaceCombo.isDisabled(),
            "profile combo must be enabled once the AI provider is selected");

        System.out.println("  section, estimate, start, progress bar, cancel and list all present");
        System.out.println("  interface profile picker present and tracks the provider dropdown");

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
