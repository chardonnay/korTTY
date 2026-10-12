package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.LanguageManager;
import de.kortty.core.SessionJournalService;
import de.kortty.core.SessionJournalSession;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AutomationRunStatus;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Offline generator for the manual's Session Journals manager screenshot
 * ({@code app-docs/screenshots/journal/journal-manager.png}).
 *
 * <p>Builds the REAL {@link SessionJournalManagerDialog} over a real {@link MainWindow} against an
 * isolated {@code user.home} filled with throw-away demo journals — interactive sessions with AI
 * token usage, a JobScheduler job with a successful and a failed run (which gives the "Deleted on"
 * column something to say) and a pinned AI Swarm journal — then snapshots the dialog pane at 2x.
 * No running app, no screen recording, no real host or credential.</p>
 *
 * <p>Run via the {@code generateSessionJournalManagerScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class SessionJournalManagerScreenshotGenerator {

    private static final double WIDTH = 1300;
    private static final double HEIGHT = 700;
    private static final String OUTPUT_FILE = "app-docs/screenshots/journal/journal-manager.png";

    /**
     * The real clock, not a fixed date: the "Deleted on" column and the expiry badges are judged
     * against today, so a frozen date would show every run journal as already due for deletion.
     */
    private static final OffsetDateTime NOW = OffsetDateTime.now().withSecond(0).withNano(0);

    private SessionJournalManagerScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-journal-manager-screenshot");
        // Set before the application class is touched: its static initializer already resolves the
        // configuration directory from user.home.
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                generate(done, failure);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(120, TimeUnit.SECONDS);
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

    private static void generate(CountDownLatch done, AtomicReference<String> failure) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init(); // singleton + managers; no GUI, no master-password prompt

        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        seedDemoJournals(app.getSessionJournalService(), settings);

        // The dialog takes the main window only as the opener of journal viewers; it is never shown.
        MainWindow window = new MainWindow(new Stage());
        SessionJournalManagerDialog dialog = new SessionJournalManagerDialog(window);
        DialogThemeHelper.applyTheme(dialog);
        String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
            ThemeCssSupport.resolveThemeColors(settings, null));
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(WIDTH, HEIGHT);
        pane.setPrefSize(WIDTH, HEIGHT);
        pane.setMaxSize(WIDTH, HEIGHT);
        dialog.show();
        Stage stage = (Stage) pane.getScene().getWindow();
        stage.setX(-4000);
        stage.setY(-4000); // off-screen: no visible window flash during generation

        // The manager lists the journals on show and rebuilds its tree from a filtered list; give
        // those listeners a moment, then expand the automation groups and pick the first journal
        // so the description area below the table is filled.
        PauseTransition settle = new PauseTransition(Duration.millis(1500));
        settle.setOnFinished(event -> {
            try {
                TreeTableView<SessionJournalTreeSupport.Node> table = table(dialog);
                expandAll(table.getRoot());
                pane.applyCss();
                pane.layout();
                selectFirstJournal(table);
                pane.applyCss();
                pane.layout();
                PauseTransition paint = new PauseTransition(Duration.millis(600));
                paint.setOnFinished(e -> {
                    try {
                        capture(pane);
                        dialog.close();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, stack(t));
                    } finally {
                        done.countDown();
                    }
                });
                paint.play();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });
        settle.play();
    }

    private static void capture(DialogPane pane) throws Exception {
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

    // ---------------------------------------------------------------- demo journals

    /**
     * Three interactive sessions (the classic picture), a JobScheduler job with two runs on two
     * hosts — one failed — and a pinned AI Swarm journal. All hosts are documentation addresses.
     */
    private static void seedDemoJournals(SessionJournalService service, GlobalSettings settings) throws Exception {
        AiProfile local = new AiProfile();
        local.setId("workstation-llm");
        local.setName("Workstation LLM");
        local.setConnectionMode(AiConnectionMode.HTTP_API);
        local.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");

        AiProfile hosted = new AiProfile();
        hosted.setId("hosted-assistant");
        hosted.setName("Hosted assistant");
        hosted.setConnectionMode(AiConnectionMode.HTTP_API);
        hosted.setApiUrl("https://api.example.com/v1/chat/completions");
        hosted.setPricePerMillionPromptTokens(1.0);
        hosted.setPricePerMillionCompletionTokens(4.0);
        hosted.setPriceCurrency("USD");

        Path web = journal(service, settings, "Web Server", "deploy", "web01.example.com",
            NOW.minusMinutes(27), NOW, "Web Server — Checked and reloaded nginx",
            List.of("nginx", "web01.example.com"),
            "Routine maintenance session on web01.example.com.", 8, null);
        service.addAiUsage(web, new AiTokenUsage(9_812, 2_488, 12_300), local);

        Path db = journal(service, settings, "Database", "dbadmin", "db01.example.com",
            NOW.minusHours(2).minusMinutes(20), NOW.minusHours(2).minusMinutes(8),
            "Database — Disk almost full on the data volume",
            List.of("disk-space", "postgresql"), "Freed space on the data volume before the nightly backup.", 6, null);
        service.addAiUsage(db, new AiTokenUsage(5_210, 1_640, 6_850), hosted);
        service.addAiUsage(db, new AiTokenUsage(3_980, 1_100, 5_080), hosted);

        Path build = journal(service, settings, "Build Host", "ci", "ci01.example.com",
            NOW.minusHours(4).minusMinutes(55), NOW.minusHours(4).minusMinutes(49),
            "Build Host — Ran a clean build", List.of("missing-file error"),
            "Clean build after the toolchain update.", 6, null);
        service.addAiUsage(build, new AiTokenUsage(2_410, 760, 3_170), local);

        // JobScheduler job "Nightly backup": tonight's run on two hosts succeeded, last night's
        // run on the database host failed. Run journals expire unless pinned.
        OffsetDateTime tonight = NOW.minusHours(17).withMinute(30);
        OffsetDateTime lastNight = tonight.minusDays(1);
        journal(service, settings, "Web Server", "backup", "web01.example.com",
            tonight, tonight.plusMinutes(4), "Nightly backup — web01", List.of(),
            null, 3, automation(SessionJournalSourceKind.JOB, "job-nightly-backup", "Nightly backup",
                "run-" + tonight.toEpochSecond(), tonight, AutomationRunStatus.SUCCESS, tonight.plusDays(14), false));
        journal(service, settings, "Database", "backup", "db01.example.com",
            tonight.plusMinutes(1), tonight.plusMinutes(12), "Nightly backup — db01", List.of(),
            null, 3, automation(SessionJournalSourceKind.JOB, "job-nightly-backup", "Nightly backup",
                "run-" + tonight.toEpochSecond(), tonight, AutomationRunStatus.SUCCESS, tonight.plusDays(14), false));
        Path failed = journal(service, settings, "Database", "backup", "db01.example.com",
            lastNight, lastNight.plusMinutes(2), "Nightly backup — db01", List.of("pg_dump", "exit code 1"),
            null, 4, automation(SessionJournalSourceKind.JOB, "job-nightly-backup", "Nightly backup",
                "run-" + lastNight.toEpochSecond(), lastNight, AutomationRunStatus.FAILED, lastNight.plusDays(14), false));
        service.addAiUsage(failed, new AiTokenUsage(1_930, 420, 2_350), local);

        // An AI Swarm run the user pinned: kept, so the column says so instead of a date.
        OffsetDateTime swarmStart = NOW.minusDays(2).withHour(9).withMinute(15);
        Path swarm = journal(service, settings, "Build Host", "ci", "ci01.example.com",
            swarmStart, swarmStart.plusMinutes(18), "AI Swarm — Patch review on ci01", List.of("swarm", "review"),
            "Kept as the reference run for the review swarm.", 11,
            automation(SessionJournalSourceKind.SWARM, "swarm-patch-review", "Patch review",
                "swarm-" + swarmStart.toEpochSecond(), swarmStart, AutomationRunStatus.SUCCESS, null, true));
        service.addAiUsage(swarm, new AiTokenUsage(41_200, 9_870, 51_070), hosted);
    }

    private static Consumer<SessionJournalMeta> automation(SessionJournalSourceKind kind, String sourceId,
                                                           String sourceName, String runId, OffsetDateTime runStartedAt,
                                                           AutomationRunStatus status, OffsetDateTime expiresAt,
                                                           boolean pinned) {
        return meta -> {
            meta.setSourceKind(kind);
            meta.setSourceId(sourceId);
            meta.setSourceName(sourceName);
            meta.setRunId(runId);
            meta.setRunStartedAt(runStartedAt);
            meta.setRunStatus(status);
            meta.setExpiresAt(expiresAt);
            meta.setPinned(pinned);
        };
    }

    /** One closed demo journal; the time stamps are rewritten afterwards so the list spans days. */
    private static Path journal(SessionJournalService service, GlobalSettings settings, String connectionName,
                                String user, String host, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                String title, List<String> keywords, String description, int entries,
                                Consumer<SessionJournalMeta> automation) throws Exception {
        ServerConnection connection = new ServerConnection(connectionName, host, 22, user);
        connection.getSessionJournalConfig().setEnabled(true);
        String tabId = "tab-" + Long.toHexString(startedAt.toEpochSecond()) + "0000";
        SessionJournalSession session = automation != null
            ? service.createSession(connection, tabId, settings, List.of(), false, null, automation)
            : service.createSession(connection, tabId, settings, List.of(), false);
        session.start();
        session.appendOutputChunk("Last login: " + startedAt.toLocalDate() + " from 192.0.2.10\n");
        for (int i = 0; i < entries; i++) {
            session.appendInputLine(i % 2 == 0 ? "systemctl status nginx" : "df -h /var");
            session.appendOutputChunk("ok\n");
        }
        session.close();
        Path dir = session.getDirectory();

        for (int i = 0; i < entries; i++) {
            SessionJournalEntry entry = new SessionJournalEntry();
            entry.setKind(i == 0 ? SessionJournalEntryKind.USER_NOTE : SessionJournalEntryKind.AI_SUMMARY);
            entry.setTitle(i == 0 ? "Started the session" : "Window " + i);
            entry.setText(i == 0 ? "Logged in to check the service." : "Commands ran without errors.");
            entry.setCreatedAt(startedAt.plusMinutes(i));
            entry.setLogStartSeq(i + 1L);
            entry.setLogEndSeq(i + 2L);
            service.appendEntry(dir, entry);
        }
        if (description != null) {
            service.updateDescription(dir, description);
        }

        SessionJournalDocument document = service.loadDocument(dir);
        SessionJournalMeta meta = document.getMeta();
        meta.setTitle(title);
        meta.setStartedAt(startedAt);
        meta.setEndedAt(endedAt);
        meta.setLogEntryCount(entries);
        meta.setAiKeywords(keywords.isEmpty() ? null : keywords);
        if (automation != null) {
            automation.accept(meta); // closing the session may have touched the run fields
        }
        service.saveDocument(dir, document);
        return dir;
    }

    // ---------------------------------------------------------------- dialog access

    @SuppressWarnings("unchecked")
    private static TreeTableView<SessionJournalTreeSupport.Node> table(SessionJournalManagerDialog dialog)
            throws Exception {
        Field field = SessionJournalManagerDialog.class.getDeclaredField("table");
        field.setAccessible(true);
        return (TreeTableView<SessionJournalTreeSupport.Node>) field.get(dialog);
    }

    private static void expandAll(TreeItem<SessionJournalTreeSupport.Node> item) {
        if (item == null) {
            return;
        }
        item.setExpanded(true);
        for (TreeItem<SessionJournalTreeSupport.Node> child : item.getChildren()) {
            expandAll(child);
        }
    }

    private static void selectFirstJournal(TreeTableView<SessionJournalTreeSupport.Node> table) {
        for (int row = 0; row < table.getExpandedItemCount(); row++) {
            TreeItem<SessionJournalTreeSupport.Node> item = table.getTreeItem(row);
            if (item != null && item.getValue() != null && item.getValue().meta() != null) {
                table.getSelectionModel().select(row);
                return;
            }
        }
        throw new IllegalStateException("no journal row to select — were the demo journals listed?");
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
