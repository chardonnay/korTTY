package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
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
 * Offline generator for the manual's AI Manager &rarr; AI Skills screenshot.
 *
 * <p>Builds the REAL {@link AiManagerDialog} headless (null owner, isolated {@code user.home}),
 * fills the skill library with a small demo set, selects the AI Skills tab and snapshots the
 * dialog pane at 2x to {@code app-docs/screenshots/settings/ai-skills.png}. The Markdown editor
 * is a WebView and stays empty in an offline snapshot, exactly as in the previous capture.</p>
 *
 * <p>Run via the {@code generateAiSkillsTabScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class AiSkillsTabScreenshotGenerator {

    private static final double WIDTH = 1180;
    private static final double HEIGHT = 820;
    private static final String OUTPUT_FILE = "app-docs/screenshots/settings/ai-skills.png";

    private AiSkillsTabScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-ai-skills-screenshot");
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
        // Show the dialog first: the scene stylesheets that darken lists and tables are attached
        // to the stage, so an unshown pane would snapshot with default (light) list cells.
        dialog.show();

        AiSkillsPane skillsPane = field(dialog, "aiSkillsPane");
        fillDemoSkills(skillsPane);
        selectAiSkillsTab(dialog);

        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(WIDTH, HEIGHT);
        pane.setPrefSize(WIDTH, HEIGHT);
        pane.setMaxSize(WIDTH, HEIGHT);
        pane.applyCss();
        pane.resize(WIDTH, HEIGHT);
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

    /** The same demo library the previous capture used, so the manual stays comparable. */
    private static void fillDemoSkills(AiSkillsPane pane) throws Exception {
        List<AiSkill> demo = List.of(
            demoSkill("bash-shell-quality", "Shell review rules", "linux, bash"),
            demoSkill("linux-sysadmin", "Operations conventions", "linux, ops"),
            demoSkill("perl-quality", "Perl style guide", "perl"));

        @SuppressWarnings("unchecked")
        List<AiSkill> skills = (List<AiSkill>) rawField(pane, "aiSkills");
        skills.clear();
        skills.addAll(demo);

        @SuppressWarnings("unchecked")
        ListView<AiSkill> listView = (ListView<AiSkill>) rawField(pane, "aiSkillListView");
        listView.getItems().setAll(skills);
        listView.getSelectionModel().selectFirst();
    }

    private static AiSkill demoSkill(String name, String description, String tags) {
        AiSkill skill = new AiSkill();
        skill.ensureId();
        skill.setName(name);
        skill.setDescription(description);
        skill.setTagsFromString(tags);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setEnabled(true);
        skill.setContent("");
        return skill;
    }

    private static void selectAiSkillsTab(AiManagerDialog dialog) {
        DialogPane pane = dialog.getDialogPane();
        TabPane tabPane = (TabPane) pane.lookup(".ai-manager-primary-navigation");
        if (tabPane == null) {
            throw new IllegalStateException("AI Manager primary navigation not found");
        }
        String title = I18n.get("settings.tab.aiSkills");
        for (Tab tab : tabPane.getTabs()) {
            if (title.equals(tab.getText())) {
                tabPane.getSelectionModel().select(tab);
                return;
            }
        }
        throw new IllegalStateException("AI Skills tab not found in AiManagerDialog");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        return (T) rawField(target, name);
    }

    private static Object rawField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
