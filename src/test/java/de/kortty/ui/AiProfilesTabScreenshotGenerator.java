package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiPromptPreset;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiVisionMode;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's AI Manager &rarr; Profiles screenshot.
 *
 * <p>Builds the REAL {@link AiManagerDialog} (null owner, isolated {@code user.home}), fills the
 * profile list with one neutral demo profile, selects the Profiles tab and snapshots the dialog
 * pane at 2x to {@code app-docs/screenshots/ai/ai-profiles.png}.</p>
 *
 * <p>Unlike the Settings dialog's AI tab, this capture is meant to show the per-profile editor
 * itself — connection, model, reasoning, image input and the internet mode — so the editor's
 * scroll pane is scrolled to the top and the pane is sized tall enough for those rows to be
 * visible without scrolling.</p>
 *
 * <p>Run via the {@code generateAiProfilesTabScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class AiProfilesTabScreenshotGenerator {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 900;
    private static final String OUTPUT_FILE = "app-docs/screenshots/ai/ai-profiles.png";

    private AiProfilesTabScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-ai-profiles-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                writeScreenshot();
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

    private static void writeScreenshot() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        AiManagerDialog dialog = new AiManagerDialog(null);
        DialogThemeHelper.applyTheme(dialog);
        // Without a running application, DialogThemeHelper cannot resolve the terminal theme, so the
        // dynamic overlay (which darkens lists) is skipped. Add it from the default settings here so
        // the capture matches what a user sees.
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
            ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        // Show first: the scene stylesheets that darken lists live on the stage, so an unshown pane
        // would snapshot with default (light) list cells.
        dialog.show();

        selectProfilesTab(dialog);
        fillDemoProfile(dialog);

        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(WIDTH, HEIGHT);
        pane.setPrefSize(WIDTH, HEIGHT);
        pane.setMaxSize(WIDTH, HEIGHT);
        pane.applyCss();
        pane.resize(WIDTH, HEIGHT);
        pane.layout();
        // The editor opens wherever the last layout pass left it; the manual wants the top of the
        // profile form, where the connection and model rows are.
        scrollEditorToTop(pane);
        pane.applyCss();
        pane.layout();

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2)); // match the Retina crispness of the other shots
        WritableImage image = pane.snapshot(params, null);

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

    /**
     * One neutral demo profile. Nothing here is a real endpoint or key: the manual must not show
     * anyone's configuration, and a screenshot is the easiest place to leak one.
     */
    @SuppressWarnings("unchecked")
    private static void fillDemoProfile(AiManagerDialog dialog) throws Exception {
        AiProfile demo = new AiProfile();
        demo.setId("demo-profile");
        demo.setName("Workstation LLM");
        demo.setConnectionMode(AiConnectionMode.HTTP_API);
        demo.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        demo.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        demo.setModel("qwen2.5-vl-7b-instruct");
        demo.setReasoningEffort(AiReasoningEffort.DISABLED);
        demo.setVisionSupport(AiVisionMode.AUTO);
        demo.setPromptPreset(AiPromptPreset.AUTO);

        // The list view is constructed over this very list, so filling it is all it takes —
        // copying it into getItems() would hand setAll() its own source and leave it empty.
        ObservableList<AiProfile> profiles = (ObservableList<AiProfile>) rawField(dialog, "profiles");
        profiles.setAll(List.of(demo));
        // Fills the "Default profile" combo the same way the dialog's own reload does.
        invoke(dialog, "refreshDefaultProfileSelection", demo.getId());
        ListView<AiProfile> listView = (ListView<AiProfile>) rawField(dialog, "profileListView");
        // Selecting drives the dialog's own listener, which loads the profile into the editor form.
        listView.getSelectionModel().select(demo);
    }

    private static void selectProfilesTab(AiManagerDialog dialog) {
        DialogPane pane = dialog.getDialogPane();
        TabPane tabPane = (TabPane) pane.lookup(".ai-manager-primary-navigation");
        if (tabPane == null) {
            throw new IllegalStateException("AI Manager primary navigation not found");
        }
        String title = I18n.get("ai.manager.tab.profiles");
        for (Tab tab : tabPane.getTabs()) {
            if (title.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        throw new IllegalStateException("Profiles tab not found in AiManagerDialog");
    }

    /** The profile editor is the only scroll pane on this tab. */
    private static void scrollEditorToTop(DialogPane pane) {
        for (Node node : pane.lookupAll(".scroll-pane")) {
            if (node instanceof ScrollPane scrollPane) {
                scrollPane.setVvalue(0);
            }
        }
    }

    private static Object rawField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String methodName, String argument) throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        method.invoke(target, argument);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
