package de.kortty.ui;

import de.kortty.core.AutomationJournalCostEstimator;
import de.kortty.core.AutomationJournalPolicy;
import de.kortty.core.LanguageManager;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AutomationJournalAiMode;
import de.kortty.model.AutomationJournalConfig;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke for the "session journal per run" editor: renders the real
 * {@link AutomationJournalConfigPane} (unrestricted and under an administrator policy) and the
 * cost warning's text, and snapshots them to {@code build/smoke/automation-journal-*.png}.
 * Run via the {@code automationJournalPaneSmoke} Gradle task. Exit 0 = OK.
 */
public final class AutomationJournalPaneSmoke {

    private AutomationJournalPaneSmoke() {
    }

    public static void main(String[] args) throws Exception {
        String language = args.length > 0 ? args[0] : "de";
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                GlobalSettings settings = new GlobalSettings();
                settings.setLanguage(language);
                LanguageManager.getInstance().initialize(settings);
                render("automation-journal-pane-" + language + ".png", AutomationJournalPolicy.UNRESTRICTED);
                render("automation-journal-pane-policy-" + language + ".png",
                    new AutomationJournalPolicy(true, true, false, 30, 500, 20));
                renderWarning("automation-journal-warning-" + language + ".png");
            } catch (Exception e) {
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
        System.out.println("automationJournalPaneSmoke OK");
    }

    private static void render(String file, AutomationJournalPolicy policy) throws Exception {
        AutomationJournalConfigPane pane = new AutomationJournalConfigPane(() -> null, config -> null);
        pane.setProfiles(List.of(profile("cloud", "Cloud GPT", false), profile("local", "Local Qwen", true)));
        pane.setPolicy(policy);
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setEnabled(true);
        config.setAiProfileId("local");
        pane.load(config);
        pane.setStatsText(I18n.get("jobscheduler.dialog.sessionJournal.stats", 2, "8.2k KI-Tokens · ≈ 0,03 €", 11,
            "96.0k KI-Tokens · ≈ 0,31 €", "412.0 MB"));
        TitledPane section = new TitledPane(I18n.get("jobscheduler.dialog.sessionJournal.title"), pane);
        VBox root = new VBox(section);
        root.setPadding(new Insets(12));
        snapshot(new Scene(root, 760, 640), file);
    }

    private static void renderWarning(String file) throws Exception {
        AutomationJournalCostEstimator.Estimate estimate = new AutomationJournalCostEstimator.Estimate(
            6_400, false, 24.0, 153_600L, 4_608_000L, 0.02, 14.4, "EUR", false, 1_200_000);
        Label header = new Label(I18n.get("journal.automation.warning.header.ai"));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label body = new Label(AutomationJournalCostWarning.buildText(AutomationJournalAiMode.ALWAYS, estimate));
        body.setWrapText(true);
        body.setMaxWidth(520);
        VBox root = new VBox(12, header, body);
        root.setPadding(new Insets(16));
        snapshot(new Scene(root, 560, 520), file);
    }

    private static AiProfile profile(String id, String name, boolean local) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        if (local) {
            profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        } else {
            profile.setConnectionMode(AiConnectionMode.HTTP_API);
            profile.setApiUrl("https://api.example.com/v1/chat/completions");
            profile.setPricePerMillionPromptTokens(2.5);
            profile.setPricePerMillionCompletionTokens(10.0);
        }
        return profile;
    }

    private static void snapshot(Scene scene, String fileName) throws Exception {
        Stage stage = new Stage();
        stage.setScene(scene);
        scene.snapshot(null);
        WritableImage image = scene.snapshot(null);
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }
}
