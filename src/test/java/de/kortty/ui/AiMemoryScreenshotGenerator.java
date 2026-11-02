package de.kortty.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.KorTTYApplication;
import de.kortty.core.AiSentContext;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryBlock.Origin;
import de.kortty.memory.MemoryBlock.SentPiece;
import de.kortty.memory.MemoryFormat;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleSource;
import de.kortty.model.AiMemoryLearningMode;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import de.kortty.policy.UserMemoryMode;
import de.kortty.stats.SkillSize;
import de.kortty.stats.SkillStatsReport;
import de.kortty.stats.SkillStatsStore;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's korTTY memory and skill statistics screenshots:
 * <ul>
 *   <li>{@code ai/ai-memory-tab.png}: the REAL {@link AiManagerDialog} on its Memory tab, global scope, entries view;</li>
 *   <li>{@code ai/ai-triage-card.png}: the REAL {@link SolutionChoicePane} with a demo triage proposal;</li>
 *   <li>{@code ai/ai-rule-proposal.png}: the REAL {@link RuleProposalCard} with a demo rule;</li>
 *   <li>{@code ai/ai-sent-context.png}: the REAL {@link SentContextView}, expanded, with demo memory parts;</li>
 *   <li>{@code ai/ai-skill-statistics.png}: the REAL {@link AiSkillStatisticsDialog} over a demo statistics file.</li>
 * </ul>
 *
 * <p>Everything runs in an isolated {@code user.home} with a throwaway settings directory, a demo MEMORY.md, a demo
 * skill-usage file and demo texts. No network call is made. The Normal design (AGENTS.md house style) is used; each
 * capture is a 2x {@link Node#snapshot}.</p>
 *
 * <p>Run via the {@code generateAiMemoryScreenshots} Gradle task, or the compiled class on the test runtime classpath.
 * Exit 0 = OK.</p>
 */
public final class AiMemoryScreenshotGenerator {

    private static final String MEMORY_TAB_FILE = "app-docs/screenshots/ai/ai-memory-tab.png";
    private static final String TRIAGE_FILE = "app-docs/screenshots/ai/ai-triage-card.png";
    private static final String RULE_FILE = "app-docs/screenshots/ai/ai-rule-proposal.png";
    private static final String SENT_FILE = "app-docs/screenshots/ai/ai-sent-context.png";
    private static final String STATS_FILE = "app-docs/screenshots/ai/ai-skill-statistics.png";

    /** The size the AI Manager opens at (AiManagerDialog sets 980 x 640). */
    private static final double MEMORY_WIDTH = 980;
    private static final double MEMORY_HEIGHT = 640;
    /** The width of the cards as the agent panel and the chat window show them. */
    private static final double CARD_WIDTH = 620;
    /** The size the statistics dialog opens at (AiSkillStatisticsDialog sets 1060 x 800). */
    private static final double STATS_WIDTH = 1060;
    private static final double STATS_HEIGHT = 800;
    /** The demo date: the statistics and the memory dates are counted back from it. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final int STATS_RANGE_DAYS = 90;

    private static final String PERL_ID = "c41a";
    private static final String SSH_ID = "7e02";
    /*
     * The user paragraphs stay short: the Entries list does not wrap a long row, so a longer paragraph is cut off at the
     * right edge of the list. A "not sent" row needs more text than the 500-token budget floor allows without that cut.
     */
    private static final String USER_PARAGRAPH_ONE = "I maintain the demo fleet: web-demo-01 and web-demo-02 serve the shop behind one proxy.";
    private static final String USER_PARAGRAPH_TWO = "Answer with the command first and name the host in front of it.";
    private static final String PERL_ENTRY = "- Perl: declare static variables with state, never via closures.";
    private static final String SSH_ENTRY = "- Use ed25519 keys for new SSH logins.";

    private static final String POSTGRES_SKILL = """
        # PostgreSQL operations

        Apply these rules to every PostgreSQL host of the demo fleet. The primary is db-demo-01, the streaming replica is
        db-demo-02, and the nightly logical dump runs from the backup host.

        ## Connections and roles

        - Connect with a personal role and never as the superuser in scripts. Use psql with ON_ERROR_STOP=1 so that a
          failed statement stops the script.
        - Give every application its own role. Grant CONNECT on the database, USAGE on the schema and only the table
          rights it needs.
        - Do not raise max_connections to solve pool exhaustion. Find the idle-in-transaction sessions first, then size
          the pooler.

        Idle sessions that hold a transaction open:

        ```sql
        SELECT pid, usename, client_addr, now() - xact_start AS open_for, left(query, 80)
        FROM pg_stat_activity
        WHERE state LIKE 'idle in transaction%'
        ORDER BY xact_start;
        ```

        ## Upgrades

        - A major upgrade runs in a maintenance window and starts with a fresh base backup from pgBackRest. Name the
          backup in the change note before the window opens.
        - Run pg_upgrade --check first. Do not start the new cluster until the check is clean.
        - Extensions must exist for the target version. List them with pg_available_extensions before the window.
        - After the upgrade run ANALYZE on every database, because the planner statistics are not carried over.

        ## Vacuum and bloat

        - Leave autovacuum on. Tune it per table with storage parameters instead of switching it off for a busy table.
        - Long-running transactions block the cleanup of dead rows. Check pg_stat_activity before a manual VACUUM.
        - Run VACUUM (VERBOSE, ANALYZE) on a table only when the numbers in pg_stat_user_tables show a real problem.
          Do not run VACUUM FULL on a live table without a window and a backup.

        ## Backups and restores

        - Every host runs a nightly pgBackRest full backup and a differential one every six hours. Check the last
          successful run with pgbackrest info before any change.
        - A restore is tested on a scratch host first. Write the restore time into the runbook, not into a comment.
        - Never delete a backup set by hand. Let the retention settings of pgBackRest remove old sets.

        ## Replication

        - The replica must stay within 60 seconds of the primary. Check pg_stat_replication on the primary and the
          replay lag on the replica before a restart of either one.
        - A switchover is announced in the channel, takes the replica out of the pooler first and writes the new
          primary into the inventory before the application reads it.
        - Replication slots that are not consumed fill the disk. Drop a slot only after the consumer is confirmed gone.

        ## Changes and migrations

        - Schema changes ship as numbered migration files in the repository. Do not run ad-hoc DDL on the primary.
        - Large data changes run in batches of a few thousand rows, each batch in its own transaction, with a pause
          between the batches so that the replica can keep up.
        - Indexes on big tables are created with CREATE INDEX CONCURRENTLY and checked for INVALID status afterwards.

        ## Logging and monitoring

        - Log statements slower than one second with log_min_duration_statement and keep pg_stat_statements enabled.
        - The dashboards read the statistics views; a host that does not show there is treated as down until proven up.
        - Log lines never carry passwords, tokens or connection strings with embedded credentials. Mask them before a paste into a ticket.

        ## Failover

        - A failover of the primary is a planned event. Freeze schema changes, confirm the replica lag is under five seconds and write the start time into the change note.
        - Promote the replica with pg_ctl promote on db-demo-02 only after the old primary is fenced. Two primaries accepting writes cannot be merged later.
        - After the switch, point the application at the new primary through the pooler, then run the read checks from the monitoring section before writes are enabled again.
        - Rebuild the old primary as a replica with pg_basebackup or a pgBackRest restore. Never start it as a primary again.

        ## Maintenance windows

        - Announce the window in the team channel at least two days before it opens and repeat the announcement on the day.
        - Every step of the window has an owner, a planned start time and a rollback command written next to it.
        - A step that runs longer than twice its planned time stops the window, and the team decides in the channel whether to roll back.
        """;
    private static final String DEPLOY_SKILL = """
        # Deployments with rsync

        Releases are built on the build host and copied to /srv/releases/<tag> on each web host. The live version is the
        symlink /srv/app/current, and a rollback moves that symlink back.

        - Copy with rsync -a --delete-after into the new release folder, never into the live folder.
        - Dry-run first with rsync -avn and read the list of deletions before the real run.
        - Switch the symlink only after the health check of the new release answers on the local port.
        - Keep the previous three release folders. Remove older ones by hand only after the switch has been stable for a day.
        """;
    private static final String SYSADMIN_SKILL = """
        # Operations conventions

        Hosts are named by role and number: web-demo-01, db-demo-01, build-demo-01. Services run as systemd units with
        a unit file in the repository. Use systemctl status and journalctl -u for the first look at a failing service.

        - Read the logs before a restart. A restart that hides the first error costs more than a few minutes of reading.
        - Changes to /etc go through the repository. Keep a dated copy of the old file next to the new one.
        - Cron jobs log to the journal, not to a file in /tmp. Each job names its owner in the unit description.
        - Disk full: check df -h and then du -xh --max-depth=2 on the mount point. Do not delete a log that is still open.
        """;
    private static final String PERL_SKILL = """
        # Perl style guide

        The maintenance scripts are small Perl programs with strict and warnings enabled.

        - Declare variables with my, and use state for a value that must survive between calls.
        - Prefer named subroutines to closures that capture a counter. A named sub is easier to test and to read.
        - Die with a message that names the file and the step, and log the same message to the journal.
        """;
    private static final String FTP_SKILL = """
        # Legacy FTP upload host

        The old upload host is retired. Keep these notes only for the migration of the last customer folders.

        - Uploads now go through the SFTP gateway. Do not restart the FTP daemon for a customer request.
        """;

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    /** One capture: it builds, shows and snapshots its window, then calls {@code next} once it is done. */
    @FunctionalInterface
    private interface Step {
        void run(Runnable next) throws Exception;
    }

    private static CountDownLatch done;
    private static final AtomicReference<String> failure = new AtomicReference<>();
    private static Path home;
    private static KorTTYApplication app;

    private AiMemoryScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        home = Files.createTempDirectory("kortty-ai-memory-screenshot");
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.ENGLISH);

        done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                generate();
            } catch (Throwable t) {
                fail(t);
            }
        });

        boolean finished = done.await(180, TimeUnit.SECONDS);
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

    private static void generate() throws Exception {
        app = demoApplication();
        AppDesignStyleSupport.initializeGlobalStyling(AppDesign.NORMAL);
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        installMemory(settings);
        writeStatistics(settings.getAiSkills());
        runSteps(List.of(
            AiMemoryScreenshotGenerator::memoryTab,
            AiMemoryScreenshotGenerator::triageCard,
            AiMemoryScreenshotGenerator::ruleCard,
            AiMemoryScreenshotGenerator::sentContext,
            AiMemoryScreenshotGenerator::statistics), 0);
    }

    private static void runSteps(List<Step> steps, int index) {
        if (index >= steps.size()) {
            done.countDown();
            return;
        }
        try {
            steps.get(index).run(() -> runSteps(steps, index + 1));
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ----- the five captures ----------------------------------------------------------------------

    /** The AI Manager on its Memory tab, global scope, entries view, at the size the dialog opens at. */
    private static void memoryTab(Runnable next) throws Exception {
        AiManagerDialog dialog = new AiManagerDialog(null);
        prepare(dialog);
        dialog.show();
        Tab tab = selectTab(dialog.getDialogPane(), I18n.get("settings.tab.aiMemory"));
        after(1.8, () -> {
            // The tab is taller than the dialog. The view starts at the scope row, so the budget and the entries show.
            Node scope = field(field(dialog, "aiMemoryPane"), "scopeCombo");
            scrollTo((ScrollPane) tab.getContent(), scope, 8);
            snapshot(dialog.getDialogPane(), MEMORY_WIDTH, MEMORY_HEIGHT, MEMORY_TAB_FILE);
            dialog.close();
            next.run();
        });
    }

    /** The solution choice of a triage: three options, the recommended one first and expanded, and a rule with a place. */
    private static void triageCard(Runnable next) throws Exception {
        SolutionChoicePane pane = new SolutionChoicePane(triageProposal(), choice -> { });
        TextArea ruleArea = field(pane, "ruleArea");
        ruleArea.setText("Always take a pgBackRest base backup before a major PostgreSQL upgrade.");
        VBox card = wrap(pane, CARD_WIDTH);
        Stage stage = showOffscreen(card);
        after(0.8, () -> {
            writePng(card, TRIAGE_FILE);
            stage.close();
            next.run();
        });
    }

    /** The card that offers to keep a rule from the chat, with its place. */
    private static void ruleCard(Runnable next) throws Exception {
        RuleProposal proposal = new RuleProposal("Always use rsync -a --delete-after for deployments.", RuleSource.CHAT,
            "web-demo-01", "web-demo-01", "web-demo-01.example.test", null);
        RuleProposalCard card = new RuleProposalCard(proposal, null, finished -> { });
        VBox box = wrap(card, CARD_WIDTH);
        Stage stage = showOffscreen(box);
        after(0.8, () -> {
            writePng(box, RULE_FILE);
            stage.close();
            next.run();
        });
    }

    /** The "Mitgesendet" disclosure, expanded: memory parts, one external file, two skills and the tokens. */
    private static void sentContext(Runnable next) throws Exception {
        VBox view = SentContextView.create(sentContext(), SentContextView.Style.CHAT, 0, true);
        VBox box = wrap(view, CARD_WIDTH);
        Stage stage = showOffscreen(box);
        after(0.8, () -> {
            writePng(box, SENT_FILE);
            stage.close();
            next.run();
        });
    }

    /** The skill statistics over the demo file, with the pinned uses shown and the 90-day range selected. */
    private static void statistics(Runnable next) throws Exception {
        AiSkillStatisticsDialog dialog = new AiSkillStatisticsDialog(null, app, skill -> null, skill -> { });
        prepare(dialog);
        CheckBox includePinned = field(dialog, "includePinnedCheck");
        includePinned.setSelected(true);
        ComboBox<SkillStatsReport.Range> rangeBox = field(dialog, "rangeBox");
        dialog.show();
        for (SkillStatsReport.Range range : rangeBox.getItems()) {
            if (range.periods() == STATS_RANGE_DAYS) {
                rangeBox.getSelectionModel().select(range);
            }
        }
        after(1.8, () -> {
            // The dialog is taller than its window. The view starts at the skills table, so the table, the size history
            // and the hints with their shortening button show.
            Node table = field(dialog, "table");
            scrollTo((ScrollPane) dialog.getDialogPane().getContent(), table, 34);
            snapshot(dialog.getDialogPane(), STATS_WIDTH, STATS_HEIGHT, STATS_FILE);
            dialog.close();
            next.run();
        });
    }

    // ----- demo data ------------------------------------------------------------------------------

    /** An application object that carries the settings only, as the other AI-skill generators do. */
    private static KorTTYApplication demoApplication() throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(home);
        GlobalSettings settings = manager.getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);
        settings.setAiMemoryEnabled(true);
        settings.setAiMemoryLearningMode(AiMemoryLearningMode.PROPOSE);
        settings.setAiSkillStatisticsEnabled(true);
        settings.setAiSkills(demoSkills());

        KorTTYApplication application = new KorTTYApplication();
        setField(application, "globalSettingsManager", manager);
        Field instance = KorTTYApplication.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, application);
        return application;
    }

    private static List<AiSkill> demoSkills() {
        List<AiSkill> skills = new ArrayList<>();
        skills.add(demoSkill("postgres-ops", "PostgreSQL upgrades, vacuum, backups and replication checks.",
            "postgres, database", POSTGRES_SKILL));
        skills.add(demoSkill("deploy-rsync", "Release folders, rsync flags and rollback for the demo hosts.",
            "deploy, linux", DEPLOY_SKILL));
        skills.add(demoSkill("linux-sysadmin", "Operations conventions for the demo fleet.", "linux, ops", SYSADMIN_SKILL));
        skills.add(demoSkill("perl-quality", "Perl style guide for the maintenance scripts.", "perl", PERL_SKILL));
        skills.add(demoSkill("legacy-ftp-notes", "Notes for the retired FTP upload host.", "ftp, legacy", FTP_SKILL));
        return skills;
    }

    private static AiSkill demoSkill(String name, String description, String tags, String content) {
        AiSkill skill = new AiSkill();
        skill.ensureId();
        skill.setName(name);
        skill.setDescription(description);
        skill.setTagsFromString(tags);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setEnabled(true);
        skill.setContent(content);
        return skill;
    }

    /**
     * The memory services read their gates from the policy at startup; the generator sets the same gates to the
     * unrestricted values, writes the demo MEMORY.md, and sets the budget at which exactly one korTTY entry is not sent.
     */
    private static void installMemory(GlobalSettings settings) throws IOException {
        MemoryService memory = MemoryService.get();
        memory.setEnabledSupplier(settings::isAiMemoryEnabled);
        memory.setAiMemoryAllowedSupplier(() -> true);
        memory.setUserMemoryModeSupplier(() -> UserMemoryMode.EDITABLE);
        memory.setExternalFilesAllowedSupplier(() -> false);

        String text = USER_PARAGRAPH_ONE + "\n\n" + USER_PARAGRAPH_TWO + "\n\n"
            + MemoryFormat.newEntry(PERL_ID, PERL_ENTRY, LocalDate.of(2026, 10, 14), "chat") + "\n"
            + MemoryFormat.newEntry(SSH_ID, SSH_ENTRY, LocalDate.of(2026, 10, 21), "agent");
        Path file = memory.store().fileOf(MemoryScope.global());
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static TerminalAgentModels.TriageProposal triageProposal() {
        List<TerminalAgentModels.PlanOption> options = List.of(
            new TerminalAgentModels.PlanOption("pg-upgrade-link", "In-place upgrade with pg_upgrade --link",
                "Keeps the data files and takes minutes instead of hours. The old cluster cannot be started again once the new one has accepted writes.",
                "Feasible in a maintenance window of about 30 minutes.",
                List.of("No rollback to the old cluster after the first write on the new one.",
                    "Every extension must exist for version 16 before the window."),
                List.of("A fresh base backup from pgBackRest, named in the change note.",
                    "pg_available_extensions checked on db-demo-01."),
                List.of("Stop the application workers.", "Run pg_upgrade --check and read the result.",
                    "Run pg_upgrade --link.", "Start the cluster and run ANALYZE on every database."),
                List.of("Logical dump and restore, see the second option.")),
            new TerminalAgentModels.PlanOption("dump-restore", "Logical dump and restore into a new cluster",
                "The slowest option, but the old cluster stays untouched until the application switches over.",
                "Feasible; the restore takes several hours for the 180 GB database.",
                List.of("Writes are stopped for the length of the dump and restore."),
                List.of("Enough free disk on the new host for the dump and the new cluster."),
                List.of("Dump with pg_dump --format=directory and --jobs.", "Restore into the new cluster.",
                    "Switch the connection string in the pooler."),
                List.of("In-place upgrade, the first option.")),
            new TerminalAgentModels.PlanOption("logical-replication", "Logical replication to a new 16 standby",
                "The shortest downtime, but it needs a replica identity on every table and two days of preparation.",
                "Feasible with the preparation done before the window.",
                List.of("Tables without a primary key need REPLICA IDENTITY FULL.", "Sequences are not replicated."),
                List.of("A publication on db-demo-01 and a subscription on the new host."),
                List.of("Set up the subscription and wait for the initial sync.", "Check the lag in pg_stat_subscription.",
                    "Switch the writers over in one step."),
                List.of("pg_upgrade --link, the first option.")));
        return new TerminalAgentModels.TriageProposal(true,
            List.of("The database is the production primary, so a failed upgrade must not lose a write.",
                "Two application workers keep long transactions open at night."),
            0, options, "db-demo-01", "db-demo-01", "db-demo-01.example.test");
    }

    /** The memory parts of one answer: two global and two connection parts, one external file and two skills. */
    private static AiSentContext sentContext() {
        List<SentPiece> pieces = List.of(
            new SentPiece(Origin.GLOBAL, 0, null, null,
                "I maintain the demo fleet. web-demo-01 and web-demo-02 serve the shop frontend behind one reverse proxy.", 24, false),
            new SentPiece(Origin.GLOBAL, 2, PERL_ID, null, PERL_ENTRY, 19, false),
            new SentPiece(Origin.CONNECTION, 0, null, null,
                "db-demo-01 is the PostgreSQL 15 primary; the streaming replica is db-demo-02.", 20, false),
            new SentPiece(Origin.CONNECTION, 2, "9b5d", null,
                "- Keep pg_stat_statements enabled; the dashboards read it.", 17, false),
            new SentPiece(Origin.EXTERNAL, 0, null, "CLAUDE.md (demo-laptop)",
                "# Working notes\n- Run ./gradlew test before a commit.\n- Ask before changing the proxy.", 58, false));
        MemoryBlock memory = new MemoryBlock(
            String.join("\n", pieces.stream().map(SentPiece::text).toList()), 138, List.of(PERL_ID, "9b5d"), List.of(),
            0, pieces);
        return new AiSentContext(memory, List.of(
            new AiSentContext.Skill("postgres-ops", "postgres-ops", true, 612, 1,
                "- Check replication lag before a restart."),
            new AiSentContext.Skill("linux-sysadmin", "linux-sysadmin", false, 186, 1, "")));
    }

    /**
     * The skill-usage file of the demo: about three months of daily uses per skill, and size snapshots. The file is read
     * by the store the statistics dialog uses, so the numbers on screen are the ones the store reports.
     */
    private static void writeStatistics(List<AiSkill> skills) throws IOException {
        JsonObject records = new JsonObject();
        LocalDate first = TODAY.minusDays(STATS_RANGE_DAYS - 16);
        LocalDate june = LocalDate.of(2026, 6, 1);
        addSkill(records, skills, "postgres-ops", 0.7, 3, 1, 11, first, TODAY,
            "2026-08-19", 0.62, "2026-09-16", 0.78, "2026-10-09", 0.9, "2026-10-27", 1.0);
        addSkill(records, skills, "deploy-rsync", 0.3, 2, 1, 23, first, TODAY,
            "2026-08-22", 0.87, "2026-10-19", 1.0);
        addSkill(records, skills, "linux-sysadmin", 0.5, 2, 1, 37, first, TODAY,
            "2026-08-25", 0.97, "2026-10-02", 1.0);
        addSkill(records, skills, "perl-quality", 0.2, 1, 0, 41, first, TODAY,
            "2026-09-01", 0.9);
        addSkill(records, skills, "legacy-ftp-notes", 0.3, 1, 0, 53, june, june.plusDays(27),
            "2026-06-02", 1.0);

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("tokenizer", AiTokenizerType.ESTIMATE.name());
        root.add("skills", records);

        Path file = home.resolve(".kortty").resolve("stats").resolve(SkillStatsStore.FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        // The clock fixes "today" of the report; the store reads the file once, when it is created.
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        SkillStatsStore.replaceInstanceForTesting(new SkillStatsStore(file, clock));
    }

    /**
     * One skill's usage and size history, keyed by the skill's id in the statistics file. The size snapshots are shares of
     * the skill's live size, so the last snapshot is the size the statistics dialog shows as current.
     */
    private static void addSkill(JsonObject records, List<AiSkill> skills, String name, double activity, int automaticMax,
                                 int pinnedMax, long seed, LocalDate from, LocalDate to, Object... dateShares) {
        AiSkill skill = skills.stream()
            .filter(candidate -> name.equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No demo skill " + name));
        int current = SkillSize.of(skill, AiTokenizerType.ESTIMATE).tokens();
        records.add(skill.getId(), skillRecord(name, activity, automaticMax, pinnedMax, seed, from, to,
            sizes(current, dateShares)));
    }

    /** The size snapshots of one skill: date and share of the current size, in date order. */
    private static TreeMap<String, Integer> sizes(int current, Object... dateShares) {
        TreeMap<String, Integer> sizes = new TreeMap<>();
        for (int index = 0; index < dateShares.length; index += 2) {
            sizes.put((String) dateShares[index], (int) Math.round(current * (Double) dateShares[index + 1]));
        }
        return sizes;
    }

    /** One skill's record: a use on about {@code activity} of the days, automatic and pinned uses, sized at the snapshot. */
    private static JsonObject skillRecord(String name, double activity, int automaticMax, int pinnedMax, long seed,
                                          LocalDate from, LocalDate to, TreeMap<String, Integer> sizes) {
        Random random = new Random(seed);
        JsonObject daily = new JsonObject();
        String lastUsed = null;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (random.nextDouble() >= activity * (weekend ? 0.35 : 1.0)) {
                continue;
            }
            int automatic = 1 + random.nextInt(automaticMax);
            int pinned = pinnedMax > 0 && random.nextInt(4) == 0 ? 1 + random.nextInt(pinnedMax) : 0;
            int uses = automatic + pinned;
            JsonObject bucket = new JsonObject();
            bucket.addProperty("automatic", automatic);
            bucket.addProperty("pinned", pinned);
            bucket.addProperty("tokens", (long) uses * sizeOn(sizes, day));
            JsonObject surfaces = new JsonObject();
            surfaces.addProperty("CHAT", uses);
            bucket.add("surfaces", surfaces);
            daily.add(day.toString(), bucket);
            lastUsed = day.toString();
        }
        JsonArray snapshots = new JsonArray();
        for (var entry : sizes.entrySet()) {
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("date", entry.getKey());
            snapshot.addProperty("tokens", entry.getValue());
            snapshot.addProperty("tokenizer", AiTokenizerType.ESTIMATE.name());
            snapshot.addProperty("fingerprint", "demo-" + entry.getKey());
            snapshots.add(snapshot);
        }
        JsonObject record = new JsonObject();
        record.addProperty("name", name);
        record.addProperty("lastUsed", lastUsed);
        record.addProperty("lastFingerprint", "demo");
        record.add("daily", daily);
        record.add("monthly", new JsonObject());
        record.add("yearly", new JsonObject());
        record.add("snapshots", snapshots);
        return record;
    }

    /** The size a skill had on {@code day}: its latest snapshot on or before that day, or the first one. */
    private static int sizeOn(TreeMap<String, Integer> sizes, LocalDate day) {
        var entry = sizes.floorEntry(day.toString());
        return entry != null ? entry.getValue() : sizes.firstEntry().getValue();
    }

    // ----- windows, snapshots and reflection --------------------------------------------------------

    private static void prepare(Dialog<?> dialog) {
        DialogThemeHelper.applyTheme(dialog);
        String dynamicCss = dynamicStylesheet();
        if (dynamicCss != null) {
            dialog.getDialogPane().getStylesheets().add(dynamicCss);
        }
    }

    private static String dynamicStylesheet() {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(settings, null));
    }

    /** A card in a scene of its own, off screen, with the design's stylesheets, so that its CSS applies. */
    private static Stage showOffscreen(Region root) {
        Scene scene = new Scene(root);
        AppDesignStyleSupport.registerApplicationBaseStyles(scene);
        String dynamicCss = dynamicStylesheet();
        if (dynamicCss != null) {
            scene.getStylesheets().add(dynamicCss);
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.setX(-4000);
        stage.setY(-4000);
        stage.show();
        return stage;
    }

    private static VBox wrap(Region content, double width) {
        VBox box = new VBox(content);
        box.setPadding(new Insets(14));
        box.setPrefWidth(width);
        return box;
    }

    private static Tab selectTab(DialogPane pane, String title) {
        TabPane tabPane = (TabPane) pane.lookup(".ai-manager-primary-navigation");
        if (tabPane == null) {
            throw new IllegalStateException("AI Manager primary navigation not found");
        }
        Tab tab = tabPane.getTabs().stream()
            .filter(candidate -> title.equals(candidate.getText()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Tab " + title + " not found in AiManagerDialog"));
        tabPane.getSelectionModel().select(tab);
        return tab;
    }

    /** Scrolls the view so that {@code node} sits {@code offset} pixels below the top of the scrolled content. */
    private static void scrollTo(ScrollPane scroller, Node node, double offset) {
        Node content = scroller.getContent();
        double target = node.localToScene(0, 0).getY() - content.localToScene(0, 0).getY() - offset;
        double range = content.getBoundsInLocal().getHeight() - scroller.getViewportBounds().getHeight();
        scroller.setVvalue(range > 0 ? Math.max(0, Math.min(1, target / range)) : 0);
    }

    /** Sets the region to the given size, lays it out and snapshots it at 2x. */
    private static void snapshot(Region region, double width, double height, String output) throws IOException {
        region.setMinSize(width, height);
        region.setPrefSize(width, height);
        region.setMaxSize(width, height);
        region.applyCss();
        region.resize(width, height);
        region.layout();
        writePng(region, output);
    }

    private static void writePng(Node node, String output) throws IOException {
        node.applyCss();
        if (node instanceof Region region) {
            region.layout();
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = node.snapshot(params, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File file = new File(output);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        ImageIO.write(buffered, "png", file);
        System.out.println("Generated " + file.getAbsolutePath() + " (" + buffered.getWidth() + "x" + buffered.getHeight() + ")");
    }

    /** Runs {@code work} after {@code seconds} on the FX thread; a failure ends the run with the stack trace. */
    private static void after(double seconds, Work work) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(event -> {
            try {
                work.run();
            } catch (Throwable t) {
                fail(t);
            }
        });
        pause.play();
    }

    private static void fail(Throwable t) {
        failure.compareAndSet(null, stack(t));
        done.countDown();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
