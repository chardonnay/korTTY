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
                renderManager("automation-journal-manager-" + language + ".png");
                renderVirtualTerminal("automation-journal-virtual-terminal.png");
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

    /** The real journal manager (no application, so no service) fed with demo journals by reflection. */
    @SuppressWarnings("unchecked")
    private static void renderManager(String file) throws Exception {
        SessionJournalManagerDialog dialog = new SessionJournalManagerDialog(null);
        java.lang.reflect.Field field = SessionJournalManagerDialog.class.getDeclaredField("journals");
        field.setAccessible(true);
        javafx.collections.ObservableList<de.kortty.model.SessionJournalMeta> journals =
            (javafx.collections.ObservableList<de.kortty.model.SessionJournalMeta>) field.get(dialog);
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        journals.setAll(List.of(
            demo("Backup db01", null, null, null, "db01", now.minusHours(9), null, 0, false, 0),
            demo("Nightly check · web01", de.kortty.model.SessionJournalSourceKind.JOB, "job-1", "r2", "web01",
                now.minusHours(1), de.kortty.model.AutomationRunStatus.SUCCESS, 4200, false, 2),
            demo("Nightly check · web02", de.kortty.model.SessionJournalSourceKind.JOB, "job-1", "r2", "web02",
                now.minusHours(1), de.kortty.model.AutomationRunStatus.FAILED, 6100, false, 0),
            demo("Nightly check · web01", de.kortty.model.SessionJournalSourceKind.JOB, "job-1", "r1", "web01",
                now.minusDays(1), de.kortty.model.AutomationRunStatus.SUCCESS, 3900, true, 0),
            demo("Disk report · app01", de.kortty.model.SessionJournalSourceKind.SWARM, "chat-1", "r3", "app01",
                now.minusMinutes(20), de.kortty.model.AutomationRunStatus.SUCCESS, 900, false, 0)));
        java.lang.reflect.Field tableField = SessionJournalManagerDialog.class.getDeclaredField("table");
        tableField.setAccessible(true);
        javafx.scene.control.TreeTableView<?> table = (javafx.scene.control.TreeTableView<?>) tableField.get(dialog);
        expandAll(table.getRoot());
        javafx.scene.Scene scene = dialog.getDialogPane().getScene();
        dialog.getDialogPane().resize(1500, 520);
        dialog.getDialogPane().setPrefSize(1500, 520);
        dialog.getDialogPane().applyCss();
        dialog.getDialogPane().layout();
        WritableImage image = dialog.getDialogPane().snapshot(null, null);
        File out = new File("build/smoke/" + file);
        out.getParentFile().mkdirs();
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }

    private static void expandAll(javafx.scene.control.TreeItem<?> item) {
        if (item == null) {
            return;
        }
        item.setExpanded(true);
        item.getChildren().forEach(AutomationJournalPaneSmoke::expandAll);
    }

    private static de.kortty.model.SessionJournalMeta demo(String title, de.kortty.model.SessionJournalSourceKind kind,
            String sourceId, String runId, String host, java.time.OffsetDateTime start,
            de.kortty.model.AutomationRunStatus status, long tokens, boolean pinned, int duplicates) {
        de.kortty.model.SessionJournalMeta meta = new de.kortty.model.SessionJournalMeta();
        meta.setTitle(title);
        meta.setHost(host);
        meta.setUsername("root");
        meta.setConnectionName(host);
        meta.setStartedAt(start);
        meta.setEndedAt(start.plusMinutes(3));
        meta.setDirectory(java.nio.file.Path.of("/demo", title.replace(' ', '_') + (runId != null ? runId : "")));
        meta.setLogEntryCount(120);
        if (kind != null) {
            meta.setSourceKind(kind);
            meta.setSourceId(sourceId);
            meta.setSourceName(kind == de.kortty.model.SessionJournalSourceKind.SWARM ? "Disk report" : "Nightly check");
            meta.setRunId(runId);
            meta.setRunStartedAt(start);
            meta.setRunStatus(status);
            meta.setAiMode(de.kortty.model.AutomationJournalAiMode.ALWAYS);
            meta.setExpiresAt(start.plusDays(14));
            meta.setPinned(pinned);
            meta.setDuplicateRunCount(duplicates);
            meta.setAiTotalTokens(tokens);
            meta.setAiPromptTokens(tokens * 4 / 5);
            meta.setAiCompletionTokens(tokens / 5);
            meta.setAiCallCount(tokens > 0 ? 2 : 0);
            meta.setAiCost(tokens * 0.000004);
            meta.setAiCostCurrency("EUR");
            meta.setStorageBytes(180_000);
        }
        return meta;
    }

    /** A top-like full-screen frame through the headless emulator and the screenshot renderer. */
    private static void renderVirtualTerminal(String file) throws Exception {
        try (de.kortty.core.headless.HeadlessTerminal terminal = new de.kortty.core.headless.HeadlessTerminal(80, 12)) {
            terminal.feed("\u001b[2J\u001b[H\u001b[7m top - 10:42:01 up 3 days,  2 users,  load average: 0.42, 0.37, 0.31 \u001b[0m\r\n");
            terminal.feed("Tasks: \u001b[1m187\u001b[0m total,   \u001b[32m1 running\u001b[0m, 186 sleeping\r\n");
            terminal.feed("%Cpu(s): \u001b[33m 3.1 us\u001b[0m,  1.0 sy,  95.9 id\r\n\r\n");
            terminal.feed("\u001b[30;47m  PID USER      %CPU %MEM COMMAND                                         \u001b[0m\r\n");
            terminal.feed(" 1287 postgres   12.3  4.1 \u001b[36mpostgres: checkpointer\u001b[0m\r\n");
            terminal.feed("  911 root        2.0  0.8 \u001b[36mnginx: worker process\u001b[0m\r\n");
            terminal.awaitProcessed(2_000);
            byte[] png = de.kortty.core.TerminalScreenRenderer.renderPng(terminal.snapshot(), true);
            File out = new File("build/smoke/" + file);
            out.getParentFile().mkdirs();
            java.nio.file.Files.write(out.toPath(), png);
            System.out.println("Snapshot written: " + out.getAbsolutePath());
        }
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
