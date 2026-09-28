package de.kortty.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiWorkflowSupport;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the unified snippet-editor AI dialogs (code review, description,
 * alternatives, diff). It builds each real dialog owner-less/app-less with the transient profile picker
 * and re-run enabled, asserts the shared toolbar controls are present, and snapshots each pane to
 * {@code build/smoke/snippet-ai-*.png}. It also proves the {@link MonacoDiffPane} WebView Java bridge
 * installs cleanly (public {@code netscape.javascript.JSObject}) by capturing the pane's logger while the
 * diff editor loads and asserting no "Could not install Monaco diff Java bridge" error is logged. Run via
 * the {@code snippetAiDialogsSmoke} Gradle task. Exit 0 = OK.
 */
public final class SnippetAiDialogsSmoke {

    private SnippetAiDialogsSmoke() {
    }

    public static void main(String[] args) throws Exception {
        // The integrated analysis writes through to the shared store; give this run its own
        // throwaway directory (every id is persistable there), so the persistence leg reads real files.
        if (System.getProperty(de.kortty.core.SnippetAnalysisStore.DIRECTORY_PROPERTY) == null) {
            System.setProperty(de.kortty.core.SnippetAnalysisStore.DIRECTORY_PROPERTY,
                java.nio.file.Files.createTempDirectory("kortty-smoke-analyses").toString());
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                // The integrated leg closes and reopens its editor; with implicit exit, closing the
                // only open window would shut the toolkit down in the middle of the run.
                Platform.setImplicitExit(false);
                LanguageManager.getInstance().initialize(new GlobalSettings());
                render(failure, done);
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed: " + e);
                done.countDown();
            }
        });

        boolean finished = done.await(180, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("snippetAiDialogsSmoke OK");
    }

    private static void render(AtomicReference<String> failure, CountDownLatch done) throws Exception {
        String rerunText = I18n.get("snippets.ai.rerun");

        // 1) Code-review findings dialog (themed HTML report).
        List<SnippetAiResponseSupport.CodeReviewFinding> findings = List.of(
            new SnippetAiResponseSupport.CodeReviewFinding(
                "R1", "high", "Unquoted variable expansion",
                "The variable $path is used unquoted, so spaces split it into multiple words.",
                "Wrap the expansion in double quotes: \"$path\".", 12),
            new SnippetAiResponseSupport.CodeReviewFinding(
                "R2", "low", "Prefer printf over echo",
                "echo handling of backslashes is shell-dependent.",
                "Use printf '%s\\n' for portable output.", null));
        SnippetAiReviewDialog review = new SnippetAiReviewDialog(
            null, I18n.get("snippets.ai.review.title"), findings, null, id -> { });
        assertControls("SnippetAiReviewDialog", review.getDialogPane(), rerunText);
        snapshotPane(review.getDialogPane(), "snippet-ai-review.png", 820);

        // 2) Technical-description dialog.
        SnippetDescriptionDialog describe = new SnippetDescriptionDialog(
            null,
            "Reads the config file, validates each entry and returns the parsed settings.",
            "bash", "", text -> { }, null, id -> { });
        assertControls("SnippetDescriptionDialog", describe.getDialogPane(), rerunText);
        snapshotPane(describe.getDialogPane(), "snippet-ai-describe.png", 760);

        // 3) Alternative-solutions dialog (profile combo drives the reload).
        AlternativeSnippetSolutionsDialog alternatives = new AlternativeSnippetSolutionsDialog(
            null, "bash", (instructions, profileId) -> List.of(), true, null);
        if (findNodes(alternatives.getDialogPane(), ComboBox.class).isEmpty()) {
            throw new AssertionError("AlternativeSnippetSolutionsDialog is missing the profile picker");
        }
        snapshotPane(alternatives.getDialogPane(), "snippet-ai-alternatives.png", 920);

        // 3b) Code-analysis dialog (split pane: selectable categorized improvements + deps left, diagram right).
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "Downloads a release asset with curl and installs it, logging progress.",
            List.of(new SnippetAiResponseSupport.ScriptDependency(
                "D1", "curl", "program", "download the release asset", "use wget, or a language built-in HTTP client")),
            List.of(
                new SnippetAiResponseSupport.ScriptImprovement(
                    "SEC-1", "security", "high", "Unquoted path expansion",
                    "$path is used unquoted.", "Quote it: \"$path\".", 12),
                new SnippetAiResponseSupport.ScriptImprovement(
                    "OPT-1", "optimization", "low", "Avoid re-downloading",
                    "The asset is fetched twice.", "Cache the download.", null),
                new SnippetAiResponseSupport.ScriptImprovement(
                    "DES-1", "design", "medium", "Separate download and install",
                    "One function handles both concerns.", "Split it into two functions.", 18)));
        // The diagram viewer only renders when the dialog is shown (setOnShown), which the smoke never does,
        // so no Mermaid render is invoked; the supplier is just a placeholder source.
        java.util.function.Supplier<CompletableFuture<SnippetDiagramView.DiagramSource>> diagramLoader =
            () -> CompletableFuture.completedFuture(new SnippetDiagramView.DiagramSource(
                de.kortty.core.SnippetDiagramSupport.buildFallbackLogicalStructureMermaid("print 'x';\n", "perl"),
                "print 'x';\n", java.util.List.of()));
        verifyPendingDiagramSourcesAreCancelled();
        verifyDiagramZoomSliderAndButtonsStayInStep();
        List<de.kortty.model.AiSkill> analysisSkills = List.of(
            smokeSkill("skill-bash", "Bash hardening", "Adds strict mode, traps and safe expansions"),
            smokeSkill("skill-posix", "POSIX portability", "Prefers POSIX-compliant constructs"));
        SnippetAnalysisPanel.SkillContext skillContext = new SnippetAnalysisPanel.SkillContext(
            analysisSkills,
            new java.util.LinkedHashSet<>(List.of("skill-bash")),
            true,
            ids -> { });
        SnippetAnalysisPanel analysisPanel = new SnippetAnalysisPanel(
            "server_monitor_stats.pl", "perl", analysis, diagramLoader, null, id -> { }, null, skillContext,
            de.kortty.core.ScriptLanguageMixSupport.detect("perl", "#!/usr/bin/env perl\nprint 1;\n"), "de");
        DialogPane analysisPane = hostPane(analysisPanel);
        realize(analysisPane);
        AtomicBoolean analysisSelectionVerified = new AtomicBoolean();
        CheckBox selectAllImprovements = selectAllImprovementsCheckBox(analysisPane);
        Label profileUsing = nodeById(analysisPane, "snippet-analysis-profile-using", Label.class);
        verifySelectAllImprovementPlacement(selectAllImprovements, profileUsing);
        WebEngine analysisEngine = findingsWebView(analysisPanel).getEngine();
        // The page reports user changes back through a per-panel bridge; select-all is one of them.
        java.util.concurrent.atomic.AtomicInteger selectionEvents = new java.util.concurrent.atomic.AtomicInteger();
        analysisPanel.addSelectionListener(selectionEvents::incrementAndGet);
        onLoadSuccess(analysisEngine, () -> {
            try {
                verifyImprovementBulkSelection(selectAllImprovements, analysisEngine);
                // A restored selection ticks exactly the named findings and reads back unchanged.
                analysisPanel.setSelectedFindings(List.of("imp:OPT-1", "dep:D1"));
                List<String> restored = analysisPanel.selectedFindingTokens();
                if (!restored.equals(List.of("imp:OPT-1", "dep:D1"))) {
                    throw new AssertionError("Restored finding selection read back as " + restored);
                }
                analysisPanel.setSelectedFindings(List.of());
                analysisSelectionVerified.set(true);
            } catch (Throwable e) {
                failure.compareAndSet(null, "SnippetAnalysisPanel selection check failed: " + e);
            }
        });
        if (findNodes(analysisPane, SplitPane.class).isEmpty()) {
            throw new AssertionError("SnippetAnalysisPanel is missing the report/diagram split");
        }
        assertControls("SnippetAnalysisPanel", analysisPane, rerunText);
        javafx.scene.control.MenuButton exportButton = findNodes(analysisPane, javafx.scene.control.MenuButton.class)
            .stream()
            .map(javafx.scene.control.MenuButton.class::cast)
            .filter(node -> I18n.get("snippets.ai.analysis.export").equals(node.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("SnippetAnalysisPanel is missing the Export button"));
        verifyExportMenu(exportButton);
        snapshotPane(analysisPane, "snippet-code-analysis.png", 560);
        HardeningOptionsSelector hardeningSelector = field(
            analysisPanel, "hardeningSelector", HardeningOptionsSelector.class);
        if (!hardeningSelector.selectedOptions().equals(
                de.kortty.core.WorkflowScriptSupport.HardeningOption.defaults())) {
            throw new AssertionError("Hardening selector did not expose the all-on default option set");
        }
        Button clearHardening = findNodes(hardeningSelector, Button.class).stream()
            .map(Button.class::cast)
            .filter(button -> I18n.get("ai.workflow.options.clear").equals(button.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Hardening selector is missing Clear"));
        Button allHardening = findNodes(hardeningSelector, Button.class).stream()
            .map(Button.class::cast)
            .filter(button -> I18n.get("ai.workflow.options.all").equals(button.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Hardening selector is missing All"));
        clearHardening.fire();
        if (!hardeningSelector.selectedOptions().isEmpty() || hardeningSelector.selectedCount() != 0) {
            throw new AssertionError("Hardening Clear did not remove the effective option set");
        }
        allHardening.fire();
        if (!hardeningSelector.selectedOptions().equals(
                de.kortty.core.WorkflowScriptSupport.HardeningOption.defaults())) {
            throw new AssertionError("Hardening All did not restore every effective option");
        }

        // Dependencies alone must not enable the JavaFX bulk selector: it controls improvements only.
        SnippetAiResponseSupport.ScriptAnalysis dependenciesOnly = new SnippetAiResponseSupport.ScriptAnalysis(
            "Calls curl.", analysis.dependencies(), List.of());
        SnippetAnalysisPanel dependenciesOnlyPanel = new SnippetAnalysisPanel(
            "dependency_only.yml", "yaml", dependenciesOnly, diagramLoader, null, null, null, null,
            de.kortty.core.ScriptLanguageMixSupport.detect("yaml", "key: value\n"), "de");
        DialogPane dependenciesOnlyPane = hostPane(dependenciesOnlyPanel);
        realize(dependenciesOnlyPane);
        CheckBox dependenciesOnlyBulkCheck = selectAllImprovementsCheckBox(dependenciesOnlyPane);
        if (!dependenciesOnlyBulkCheck.isDisable()) {
            throw new AssertionError("Select-all-improvements must be disabled when only dependencies exist");
        }

        InputHardeningSelector declarativeSelector = field(
            dependenciesOnlyPanel, "inputHardeningSelector", InputHardeningSelector.class);
        if (declarativeSelector.isSupported() || !declarativeSelector.isDisable()
                || declarativeSelector.currentConfig().isEnabled()) {
            throw new AssertionError("YAML analysis must disable input hardening instead of silently ignoring it");
        }

        // 3c) The AI text-language selector is independent from the snippet's code language. Drive the
        // real editor controls and defer checking the asynchronous provider calls until the final pause.
        Runnable verifyAiTextLanguage = exerciseSnippetEditorAiTextLanguageSelection();
        Runnable verifyAnalysisLanguages = exerciseSnippetAnalysisLanguageRouting();

        // 4) Diff / "review changes" dialog with a re-run handler (improve/assist flow). Capture the
        //    MonacoDiffPane logger while its WebView loads to assert the Java bridge installs cleanly.
        ListAppender<ILoggingEvent> diffLog = new ListAppender<>();
        diffLog.start();
        Logger diffLogger = (Logger) LoggerFactory.getLogger("de.kortty.ui.MonacoDiffPane");
        Level previousLevel = diffLogger.getLevel();
        diffLogger.setLevel(Level.DEBUG);
        diffLogger.addAppender(diffLog);

        // A staged Full-code-analysis apply concatenates one paragraph per stage, so the summary is the
        // long case the review window has to survive: it scrolls inside its own pane and the split
        // divider below it keeps the diff readable.
        SnippetAiDiffDialog diff = new SnippetAiDiffDialog(
            null, I18n.get("snippets.ai.diff.title"),
            "Quote the path expansion.\n\nApplied SEC-1 by replacing the shell-interpolated invocation "
                + "with a list-form pipe and adding input validation.\n\nApplied mandatory hardening: "
                + "configuration block for literals, distinct non-zero exit codes per failure class, "
                + "and an error trap with cleanup logic.\n\nAdded the --help usage message and argument "
                + "parsing to satisfy HARDENING-05.",
            "cat $path\n", "cat \"$path\"\n", "bash",
            EditorSettingsHelper.loadSnippetSettings(), null);
        diff.setRerunHandler(null, id -> { });
        // Exercise the "Why these parts changed" cards: category icons per finding-id prefix plus the
        // reasons JSON handed to the diff host (idx/finding/anchor/reason per change).
        diff.setChangeExplanations(List.of(
            new SnippetAiResponseSupport.SecurityChange(
                "SEC-1", "cat \"$path\"", "Quoted the path expansion to prevent word splitting."),
            new SnippetAiResponseSupport.SecurityChange(
                "D1", "cat \"$path\"", "Replaced the external helper with a shell built-in.")));
        // The toolbar now sits inside the summary/review split, so its skin has to exist before the
        // walker can reach the profile picker and the re-run button.
        realize(diff.getDialogPane());
        assertControls("SnippetAiDiffDialog", diff.getDialogPane(), rerunText);
        assertFindingFilter(diff.getDialogPane());
        snapshotPane(diff.getDialogPane(), "snippet-ai-diff.png", 1040);

        // Let the FX event loop pump so the diff editor loads and installBridge() runs, then verify.
        PauseTransition pause = new PauseTransition(Duration.seconds(8));
        pause.setOnFinished(event -> finishSmoke(failure, done, diffLog, diffLogger, previousLevel,
            analysisSelectionVerified, selectionEvents, verifyAiTextLanguage, verifyAnalysisLanguages));
        pause.play();
    }

    private static void finishSmoke(
            AtomicReference<String> failure, CountDownLatch done, ListAppender<ILoggingEvent> diffLog,
            Logger diffLogger, Level previousLevel, AtomicBoolean analysisSelectionVerified,
            java.util.concurrent.atomic.AtomicInteger selectionEvents, Runnable verifyAiTextLanguage,
            Runnable verifyAnalysisLanguages) {
        boolean runFlow = false;
        try {
            boolean bridgeError = diffLog.list.stream().anyMatch(e ->
                e.getLevel() == Level.ERROR && String.valueOf(e.getMessage()).contains("Monaco diff Java bridge"));
            boolean bridgeInstalled = diffLog.list.stream().anyMatch(e ->
                String.valueOf(e.getMessage()).contains("Installed Monaco diff Java bridge"));
            if (bridgeError) {
                failure.compareAndSet(null, "MonacoDiffPane Java bridge failed to install (regression)");
            } else if (bridgeInstalled) {
                System.out.println("MonacoDiffPane bridge installed cleanly (public JSObject).");
            } else {
                System.out.println("MonacoDiffPane bridge did not report within the wait "
                    + "(WebView likely did not finish loading headless); no error was logged.");
            }
            if (!analysisSelectionVerified.get()) {
                failure.compareAndSet(null,
                    "SnippetAnalysisPanel selection page did not finish loading within the wait");
            }
            if (analysisSelectionVerified.get() && selectionEvents.get() == 0) {
                failure.compareAndSet(null,
                    "The analysis page never reported a selection change through its bridge");
            }
            try {
                verifyAiTextLanguage.run();
                verifyAnalysisLanguages.run();
            } catch (Throwable e) {
                failure.compareAndSet(null, "SnippetEditDialog AI flow check failed: " + e);
            }
            runFlow = true;
        } finally {
            diffLogger.detachAppender(diffLog);
            diffLogger.setLevel(previousLevel);
            if (runFlow) {
                // The integrated analysis leg runs on its own once the other editors are gone: it
                // opens, closes and reopens editors, and WebKit copes badly with many pages booting
                // at once in one headless harness.
                PauseTransition settle = new PauseTransition(Duration.seconds(2));
                settle.setOnFinished(event -> runIntegratedAnalysisLeg(failure, done));
                settle.play();
                return;
            }
            // Closing the editors disposes their analysis and diff WebViews. Give macOS WebKit one
            // pulse to release its native scenes before the harness calls Platform.exit();
            // immediate shutdown can crash in objc_msgSend.
            PauseTransition cleanupPause = new PauseTransition(Duration.seconds(1));
            cleanupPause.setOnFinished(cleanup -> done.countDown());
            cleanupPause.play();
        }
    }

    /** Runs the integrated Full-code-analysis leg, then verifies it and ends the harness. */
    private static void runIntegratedAnalysisLeg(AtomicReference<String> failure, CountDownLatch done) {
        AtomicBoolean flowDone = new AtomicBoolean();
        Runnable verify;
        try {
            verify = exerciseFullAnalysisApplyPreview(failure, flowDone);
        } catch (Throwable e) {
            failure.compareAndSet(null, "Integrated Full-code-analysis leg could not start: " + e);
            done.countDown();
            return;
        }
        long started = System.nanoTime();
        Timeline wait = new Timeline();
        wait.getKeyFrames().add(new KeyFrame(Duration.millis(250), tick -> {
            if (!flowDone.get() && System.nanoTime() - started < 60_000_000_000L) {
                return;
            }
            wait.stop();
            try {
                verify.run();
            } catch (Throwable e) {
                failure.compareAndSet(null, "Integrated Full-code-analysis check failed: " + e);
            }
            // Closing the editors disposes their analysis and diff WebViews. Give macOS WebKit a
            // moment to release its native scenes before the next editor boots its own.
            PauseTransition cleanupPause = new PauseTransition(Duration.seconds(2));
            cleanupPause.setOnFinished(cleanup -> runInEditorChangeReviewLeg(failure, done));
            cleanupPause.play();
        }));
        wait.setCycleCount(Animation.INDEFINITE);
        wait.play();
    }

    /**
     * The ad-hoc AI flows (improve, migrate, assistant, security fix, format) review their change in
     * the editor area, not in a window that blocks in a nested event loop. Driven through language
     * migration: an edit made while the AI works blocks Accept and offers a re-run on the current
     * content, no second AI action starts while the review is open, Accept applies, and Reject of the
     * line-width format preview leaves the content alone. Ends the harness.
     */
    private static void runInEditorChangeReviewLeg(AtomicReference<String> failure, CountDownLatch done) {
        String original = "#!/bin/bash\necho \"$1\"\n";
        String migrated = "#!/usr/bin/env python3\nimport sys\nprint(sys.argv[1])\n";
        String edit = "# edited while the AI works\n";
        CountDownLatch firstCallGate = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger migrationCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<SnippetAiDiffPane> firstReview = new AtomicReference<>();
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicReference<Timeline> poller = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong started = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null, null, null, null, null, null, null, null, null,
            request -> {
                if (migrationCalls.incrementAndGet() == 1) {
                    firstCallGate.await(30, TimeUnit.SECONDS);
                }
                return new SnippetAiResponseSupport.LanguageMigration(migrated, "Ported to Python.", List.of());
            },
            null, null, null, null, null, null, null,
            false,
            null);
        SnippetEditDialog editorDialog;
        try {
            editorDialog = new SnippetEditDialog(new Snippet("in-editor-review-smoke.sh", original, "bash"),
                List.of(), assist);
            editorDialog.show();
        } catch (Throwable e) {
            failure.compareAndSet(null, "In-editor AI change leg could not start: " + e);
            done.countDown();
            return;
        }
        MonacoEditorPane editor = field(editorDialog, "contentArea", MonacoEditorPane.class);
        MenuItem migrateItem = field(editorDialog, "migrateLanguageItem", MenuItem.class);
        SnippetAiWorkflowSupport.MigrationPlan plan = new SnippetAiWorkflowSupport.MigrationPlan(null, null, null);
        Class<?>[] migrationSignature = {SnippetAiWorkflowSupport.MigrationPlan.class, String.class};
        long baselineWindows = showingStages();

        Runnable finish = () -> {
            stop(poller);
            editorDialog.closeWithoutPrompt();
            PauseTransition cleanupPause = new PauseTransition(Duration.seconds(2));
            cleanupPause.setOnFinished(cleanup -> runResumeAfterRestartLeg(failure, done));
            cleanupPause.play();
        };
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            try {
                if (System.nanoTime() - started.get() > 60_000_000_000L) {
                    throw new AssertionError("timed out");
                }
                switch (phase.get()) {
                    case 0 -> {
                        if (!editor.isReady()) {
                            return;
                        }
                        phase.set(1);
                        invoke(editorDialog, "runLanguageMigration", migrationSignature, plan, null);
                        // The user keeps typing while the AI works; then the AI answers.
                        editor.replaceText(edit + original);
                        firstCallGate.countDown();
                    }
                    case 1 -> {
                        SnippetAiDiffPane review = editorDialog.aiChangeReviewPane();
                        if (review == null || review.getScene() == null || !review.isDiffReady()) {
                            return;
                        }
                        if (showingStages() != baselineWindows) {
                            throw new AssertionError("The migration preview opened a window instead of using the editor");
                        }
                        if (requireInEditor(editorDialog, "#snippet-ai-diff-later").isVisible()) {
                            throw new AssertionError("An ad-hoc change offers Review later, which it cannot keep");
                        }
                        if (!review.isAcceptBlocked()
                                || !requireInEditor(editorDialog, "#snippet-ai-diff-accept").isDisabled()) {
                            throw new AssertionError("Accept stayed enabled although the snippet changed meanwhile");
                        }
                        if (!migrateItem.isDisable()) {
                            throw new AssertionError("AI actions stayed enabled while a change waits for review");
                        }
                        invoke(editorDialog, "runLanguageMigration", migrationSignature, plan, null);
                        if (migrationCalls.get() != 1 || editorDialog.aiChangeReviewPane() != review) {
                            throw new AssertionError("A second AI action started on top of the open review");
                        }
                        Node rerun = requireInEditor(editorDialog, "#snippet-ai-diff-blocking-action");
                        if (!rerun.isVisible()) {
                            throw new AssertionError("The blocked review offers no re-run on the current content");
                        }
                        firstReview.set(review);
                        phase.set(2);
                        ((Button) rerun).fire();
                    }
                    case 2 -> {
                        SnippetAiDiffPane review = editorDialog.aiChangeReviewPane();
                        if (review == null || review == firstReview.get() || review.getScene() == null
                                || !review.isDiffReady()) {
                            return;
                        }
                        if (migrationCalls.get() != 2) {
                            throw new AssertionError("The re-run did not ask the AI again: " + migrationCalls.get());
                        }
                        if (!(edit + original).equals(review.originalText())) {
                            throw new AssertionError("The re-run did not work on the current content");
                        }
                        if (review.isAcceptBlocked()) {
                            throw new AssertionError("Accept is blocked although the content is unchanged");
                        }
                        phase.set(3);
                        ((Button) requireInEditor(editorDialog, "#snippet-ai-diff-accept")).fire();
                    }
                    case 3 -> {
                        if (!migrated.equals(editor.getText())) {
                            throw new AssertionError("Accept did not put the migration into the editor");
                        }
                        if (editorDialog.aiChangeReviewPane() != null
                                || editorDialog.getDialogPane().lookup("#snippet-ai-diff-pane") != null) {
                            throw new AssertionError("Accept did not give the editor area back");
                        }
                        if (migrateItem.isDisable()) {
                            throw new AssertionError("AI actions stayed disabled after the review was decided");
                        }
                        accepted.set(true);
                        phase.set(4);
                        invoke(editorDialog, "showLineWidthFormatPreview",
                            new Class<?>[] {String.class, String.class, String.class, String.class, int.class,
                                boolean.class, int.class, int.class},
                            migrated, migrated, migrated + "# wrapped\n", "python", 40, false, 0, 0);
                    }
                    case 4 -> {
                        SnippetAiDiffPane review = editorDialog.aiChangeReviewPane();
                        if (review == null || review.getScene() == null) {
                            return;
                        }
                        phase.set(5);
                        ((Button) requireInEditor(editorDialog, "#snippet-ai-diff-reject")).fire();
                    }
                    case 5 -> {
                        if (!migrated.equals(editor.getText())) {
                            throw new AssertionError("Reject changed the editor content");
                        }
                        if (editorDialog.aiChangeReviewPane() != null) {
                            throw new AssertionError("Reject did not close the review");
                        }
                        rejected.set(true);
                        phase.set(6);
                        finish.run();
                    }
                    default -> stop(poller);
                }
            } catch (Throwable e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : e;
                failure.compareAndSet(null, "In-editor AI change review failed in phase " + phase.get() + ": " + cause);
                firstCallGate.countDown();
                phase.set(99);
                finish.run();
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        poller.set(timeline);
        timeline.play();
    }

    /**
     * An apply that stopped between two stages in an earlier session (the app quit mid-run) is
     * offered "Resume" in the panel of a freshly opened editor, and resuming sends the provider the
     * stored request byte for byte: the checkpoint, the stored instructions and the migration rebuilt
     * from the stored target. Ends the harness.
     */
    private static void runResumeAfterRestartLeg(AtomicReference<String> failure, CountDownLatch done) {
        String original = "#!/bin/bash\necho $1\n";
        AtomicReference<SnippetEditDialog.ImprovementApplyRequest> seen = new AtomicReference<>();
        AtomicReference<Timeline> poller = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
        long started = System.nanoTime();
        SnippetEditDialog editorDialog;
        try {
            Snippet snippet = new Snippet("resume-after-restart-smoke.sh", original, "bash");
            SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
                "Echoes an argument.", List.of(),
                List.of(new SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "high",
                    "Quote $1", "", "", 2)));
            de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot request =
                new de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot("bash", "en", List.of("SEC-1"),
                    List.of(), "stored extra instructions", List.of(), "", List.of(), "", "PYTHON", null, null,
                    null, null, "", null, de.kortty.core.SnippetDiagramSupport.contentHash(original), original);
            int stages = SnippetAiWorkflowSupport.planSnippetImprovements(analysis.improvements(), List.of(), "",
                "", SnippetAnalysisController.migrationFromSnapshot(request, original), original).size();
            de.kortty.core.SnippetAnalysisRecord.StoredCheckpoint checkpoint =
                new de.kortty.core.SnippetAnalysisRecord.StoredCheckpoint(1, stages,
                    "#!/usr/bin/env python3\nimport sys\nprint(sys.argv[1])\n", List.of("Ported to Python."),
                    List.of(), List.of(), null);
            de.kortty.core.SnippetAnalysisRecord.ApplyRun interrupted = new de.kortty.core.SnippetAnalysisRecord.ApplyRun(
                "resume-run", 10L, 20L, 0L, de.kortty.core.SnippetAnalysisRecord.RunOutcome.INTERRUPTED, false,
                request, List.of(), checkpoint, null, null, null, null, null, null, null, null, null, null, null, 0L);
            de.kortty.core.SnippetAnalysisRecord record = de.kortty.core.SnippetAnalysisRecord.fromAnalysis(
                "resume-record", snippet.getId(), analysis,
                de.kortty.core.SnippetAnalysisRecord.Source.of(original, "bash", "en", "en", snippet.getName()),
                de.kortty.core.SnippetAnalysisRecord.Provenance.EMPTY,
                de.kortty.core.SnippetAnalysisRecord.Purpose.ANALYSIS, null, System.currentTimeMillis())
                .withRun(interrupted);
            de.kortty.core.SnippetAnalysisStore.shared().addAnalysis(snippet.getId(), record);
            SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                analyzed -> analysis,
                applyRequest -> {
                    seen.set(applyRequest);
                    return new SnippetAiResponseSupport.SnippetSecurityFix(
                        "#!/usr/bin/env python3\nimport sys\nprint(\"%s\" % sys.argv[1])\n",
                        "Quoted.", List.of());
                },
                false,
                null);
            editorDialog = new SnippetEditDialog(snippet, List.of(), assist);
            editorDialog.show();
            editorDialog.analysisController().showPanel();
        } catch (Throwable e) {
            failure.compareAndSet(null, "Resume-after-restart leg could not start: " + e);
            done.countDown();
            return;
        }
        SnippetEditDialog shown = editorDialog;
        Runnable finish = () -> {
            stop(poller);
            shown.closeWithoutPrompt();
            PauseTransition cleanupPause = new PauseTransition(Duration.seconds(2));
            cleanupPause.setOnFinished(cleanup -> done.countDown());
            cleanupPause.play();
        };
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            try {
                if (System.nanoTime() - started > 60_000_000_000L) {
                    throw new AssertionError("timed out in phase " + phase.get());
                }
                switch (phase.get()) {
                    case 0 -> {
                        Node progress = shown.getDialogPane().lookup("#snippet-analysis-progress-pane");
                        if (progress == null) {
                            return;
                        }
                        String resumeText = I18n.get("snippets.ai.analysis.fix.recovery.resume");
                        List<Node> nodes = new ArrayList<>();
                        collect(progress, nodes);
                        Button resume = nodes.stream()
                            .filter(node -> node instanceof Button button && resumeText.equals(button.getText()))
                            .map(Button.class::cast).findFirst().orElse(null);
                        if (resume == null) {
                            return;
                        }
                        phase.set(1);
                        resume.fire();
                    }
                    case 1 -> {
                        SnippetEditDialog.ImprovementApplyRequest request = seen.get();
                        if (request == null) {
                            return;
                        }
                        if (request.resumeFrom() == null || request.resumeFrom().completedStages() != 1) {
                            throw new AssertionError("Resume did not continue from the stored checkpoint: "
                                + request.resumeFrom());
                        }
                        if (!"stored extra instructions".equals(request.additionalInstructions())) {
                            throw new AssertionError("Resume did not reuse the stored instructions: "
                                + request.additionalInstructions());
                        }
                        if (request.migration() == null
                                || request.migration().targetLanguage() != de.kortty.core.WorkflowScriptSupport.ScriptLanguage.PYTHON) {
                            throw new AssertionError("Resume did not rebuild the stored migration: " + request.migration());
                        }
                        if (!original.equals(request.fullContent())) {
                            throw new AssertionError("Resume did not start from the stored base content");
                        }
                        phase.set(2);
                        finish.run();
                    }
                    default -> stop(poller);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, "Resume after restart failed: " + e);
                phase.set(99);
                finish.run();
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        poller.set(timeline);
        timeline.play();
    }

    /**
     * The zoom slider and the −/+/Fit buttons drive one value, so whichever the user reaches for,
     * the other follows. The track is powers of two with the fitted size in the middle.
     */
    private static void verifyDiagramZoomSliderAndButtonsStayInStep() {
        SnippetDiagramView view = new SnippetDiagramView(CompletableFuture::new, false);
        javafx.scene.control.Slider slider = view.zoomSlider();
        Button zoomIn = findButtonByText(view, "+");
        Button zoomOut = findButtonByText(view, "−");
        Button zoomFit = findButtonByText(view, I18n.get("snippets.ai.diagram.zoom.fit"));

        requireZoom("the fitted size does not start in the middle of the track", view, slider, 1.0, 0.0);

        zoomIn.fire();
        requireZoom("the + button did not move the slider", view, slider, 1.15,
            Math.log(1.15) / Math.log(2.0));
        zoomOut.fire();
        requireZoom("the − button did not move the slider back", view, slider, 1.0, 0.0);

        // One step right doubles, one step left halves.
        slider.setValue(1.0);
        requireZoom("a step right on the slider did not double the zoom", view, slider, 2.0, 1.0);
        slider.setValue(-1.0);
        requireZoom("a step left on the slider did not halve the zoom", view, slider, 0.5, -1.0);

        zoomFit.fire();
        requireZoom("Fit did not return the slider to the middle", view, slider, 1.0, 0.0);

        // Past the ends the factor is pinned and the knob sits on the end it is pinned to.
        for (int press = 0; press < 40; press++) {
            zoomIn.fire();
        }
        requireZoom("the slider ran past the maximum zoom", view, slider, 4.0, slider.getMax());
        for (int press = 0; press < 60; press++) {
            zoomOut.fire();
        }
        requireZoom("the slider ran past the minimum zoom", view, slider, 0.25, slider.getMin());
        view.dispose();
    }

    private static void requireZoom(
        String message, SnippetDiagramView view, javafx.scene.control.Slider slider,
        double expectedFactor, double expectedSliderValue) {

        if (Math.abs(view.zoomFactor() - expectedFactor) > 1e-6
                || Math.abs(slider.getValue() - expectedSliderValue) > 1e-6) {
            throw new AssertionError(message + " (factor " + view.zoomFactor() + " expected " + expectedFactor
                + ", slider " + slider.getValue() + " expected " + expectedSliderValue + ")");
        }
    }

    private static Button findButtonByText(javafx.scene.Parent parent, String text) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Button button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof javafx.scene.Parent nested) {
                Button found = findButtonByText(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Test-double futures prove Regenerate and close cancel superseded diagram-source work. */
    private static void verifyPendingDiagramSourcesAreCancelled() {
        List<CompletableFuture<SnippetDiagramView.DiagramSource>> requests = new ArrayList<>();
        SnippetDiagramView view = new SnippetDiagramView(() -> {
            CompletableFuture<SnippetDiagramView.DiagramSource> request = new CompletableFuture<>();
            requests.add(request);
            return request;
        }, true);

        view.reload();
        CompletableFuture<SnippetDiagramView.DiagramSource> first = requests.get(0);
        view.reload();
        CompletableFuture<SnippetDiagramView.DiagramSource> second = requests.get(1);
        if (!first.isCancelled()) {
            throw new AssertionError("Regenerate did not cancel the superseded diagram source");
        }

        view.dispose();
        if (!second.isCancelled()) {
            throw new AssertionError("Closing the diagram view did not cancel its pending source");
        }
    }

    /**
     * Exercises the temporary AI text-language setting through the real selector and action controls.
     * The persistent XML round-trip is covered by {@code GlobalSettingsManagerTest}; this owner-less
     * JavaFX harness intentionally keeps "remember" off so it never touches the user's settings file.
     */
    private static Runnable exerciseSnippetEditorAiTextLanguageSelection() throws Exception {
        AtomicReference<ProviderLanguage> metadataLanguage = new AtomicReference<>();
        AtomicReference<ProviderLanguage> descriptionLanguage = new AtomicReference<>();
        AtomicReference<SnippetEditDialog.SelectionTextTransformRequest> selectionRequest =
            new AtomicReference<>();

        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            (content, language, responseLanguageCode) -> {
                metadataLanguage.set(new ProviderLanguage(language, responseLanguageCode));
                return new SnippetEditDialog.SuggestedSnippetMetadata(
                    "language-smoke.sh", "Prüft die temporäre Textsprache.", language, null);
            },
            (content, language, description, responseLanguageCode) -> {
                descriptionLanguage.set(new ProviderLanguage(language, responseLanguageCode));
                return description;
            },
            request -> {
                selectionRequest.set(request);
                return request.selectedText();
            },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null);

        String content = "# Deutscher Kommentar\nprintf '%s\\n' \"$HOME\"\n";
        Snippet snippet = new Snippet("language-smoke.sh", content, "bash");
        snippet.setDescription("Beschreibung für den Sprachtest.");
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, List.of(), assist);

        @SuppressWarnings("unchecked")
        ComboBox<AiLanguageSupport.LanguageOption> textLanguageCombo =
            (ComboBox<AiLanguageSupport.LanguageOption>) nodeById(
                dialog.getDialogPane(), "snippet-ai-text-language", ComboBox.class);
        CheckBox rememberLanguage = nodeById(
            dialog.getDialogPane(), "snippet-ai-text-language-remember", CheckBox.class);
        if (!textLanguageCombo.isVisible() || !textLanguageCombo.isManaged()
                || textLanguageCombo.getParent() == null
                || !textLanguageCombo.getParent().isVisible()
                || !textLanguageCombo.getParent().isManaged()) {
            throw new AssertionError("SnippetEditDialog AI text-language selector is not visible with AiAssist");
        }
        if (rememberLanguage.isSelected()) {
            throw new AssertionError("Remember-AI-text-language must be off by default");
        }

        @SuppressWarnings("unchecked")
        ComboBox<String> codeLanguageCombo = (ComboBox<String>) field(
            dialog, "languageCombo", ComboBox.class);
        if (!"bash".equals(codeLanguageCombo.getValue())) {
            throw new AssertionError("Expected the snippet code language to start as bash, was "
                + codeLanguageCombo.getValue());
        }

        AiLanguageSupport.LanguageOption english = textLanguageCombo.getItems().stream()
            .filter(option -> option != null && "en".equals(option.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AI text-language selector is missing English"));
        textLanguageCombo.getSelectionModel().select(english);
        if (textLanguageCombo.getOnAction() == null) {
            throw new AssertionError("AI text-language selector has no action handler");
        }
        textLanguageCombo.getOnAction().handle(new ActionEvent(textLanguageCombo, textLanguageCombo));
        if (rememberLanguage.isSelected()) {
            throw new AssertionError("Temporary language selection unexpectedly enabled persistence");
        }
        if (!"bash".equals(codeLanguageCombo.getValue())) {
            throw new AssertionError("Changing AI text language changed the snippet code language");
        }

        MonacoEditorPane editor = field(dialog, "contentArea", MonacoEditorPane.class);
        String selectedComment = "Deutscher Kommentar";
        int selectedCommentStart = content.indexOf(selectedComment);
        editor.selectRange(selectedCommentStart, selectedCommentStart + selectedComment.length());
        field(dialog, "generateMetadataButton", Button.class).fire();
        field(dialog, "correctDescriptionButton", Button.class).fire();
        field(dialog, "correctSelectionTextItem", MenuItem.class).fire();
        snapshotPane(dialog.getDialogPane(), "snippet-ai-language-selector.png", 900);

        return () -> {
            try {
                assertProviderLanguage("metadata", metadataLanguage.get(), "bash", "en");
                assertProviderLanguage("description", descriptionLanguage.get(), "bash", "en");
                SnippetEditDialog.SelectionTextTransformRequest request = selectionRequest.get();
                if (request == null) {
                    throw new AssertionError("Selection correction provider was not invoked");
                }
                assertProviderLanguage(
                    "selection",
                    new ProviderLanguage(request.snippetLanguage(), request.fallbackLanguageCode()),
                    "bash",
                    "en");
                if (!selectedComment.equals(request.selectedText())
                        || request.selectionStart() != selectedCommentStart
                        || request.selectionEnd() != selectedCommentStart + selectedComment.length()) {
                    throw new AssertionError("Selection correction did not preserve the partial comment range");
                }
                if (!"bash".equals(codeLanguageCombo.getValue())) {
                    throw new AssertionError("AI provider completion changed the snippet code language");
                }
                if (rememberLanguage.isSelected()) {
                    throw new AssertionError("Temporary language selection was persisted unexpectedly");
                }
            } finally {
                editor.dispose();
            }
        };
    }

    private static void assertProviderLanguage(
        String provider,
        ProviderLanguage actual,
        String expectedSnippetLanguage,
        String expectedTextLanguage) {

        if (actual == null) {
            throw new AssertionError(provider + " provider was not invoked");
        }
        if (!expectedSnippetLanguage.equals(actual.snippetLanguage())) {
            throw new AssertionError(provider + " provider received snippet language "
                + actual.snippetLanguage() + " instead of " + expectedSnippetLanguage);
        }
        if (!expectedTextLanguage.equals(actual.textLanguage())) {
            throw new AssertionError(provider + " provider received AI text language "
                + actual.textLanguage() + " instead of " + expectedTextLanguage);
        }
    }

    private record ProviderLanguage(String snippetLanguage, String textLanguage) {
    }

    /**
     * Drives the integrated Full-code analysis of a real editor end to end, and then its persistence:
     * <ol>
     *   <li>Full code analysis shows its result in the editor's side panel (no extra window), the
     *       dedicated diagram request runs, a pending auto-completion is discarded;</li>
     *   <li>Apply selected shows the progress in the panel and the review in the editor area; the
     *       editor text stays unchanged; "Review later" keeps a PENDING_REVIEW run;</li>
     *   <li>the editor is closed and the snippet reopened: the stored analysis, its diagram and the
     *       ticked findings come back without any provider call, the stored file carries the pending
     *       run, and "Review changes" reopens it — Accept replaces the text and stores ACCEPTED;</li>
     *   <li>Verify analyses the accepted content again: the new VERIFY record carries the comparison
     *       (resolved / still open / new), the panel shows its banner, chips and "Resolved" list, and
     *       the after-apply report finds it;</li>
     *   <li>history actions: pinning is stored, viewing an older entry never changes the current
     *       one, Discard and Delete all ask inline (Cancel keeps everything) and then remove.</li>
     * </ol>
     */
    private static Runnable exerciseFullAnalysisApplyPreview(
            AtomicReference<String> failure, AtomicBoolean flowDone) throws Exception {
        // Real comments, because the apply flow now keeps whatever language the script is written
        // in and asks the user when it cannot tell. A bare two-line fixture is exactly the
        // "cannot tell" case and would stop this harness on a question nobody can answer.
        String prose = "# Prints the value that was passed to the script and checks that the\n"
            + "# variable is quoted, otherwise the shell splits it into several arguments.\n";
        String original = "#!/usr/bin/env bash\n" + prose + "printf '%s\\n' $value\n";
        String replacement = "#!/usr/bin/env bash\n" + prose + "printf '%s\\n' \"$value\"\n";
        String longImprovementTitle = "Quote the untrusted variable expansion before invoking the command "
            + "so spaces, wildcard characters and user-controlled shell fragments remain one literal argument "
            + "without changing the command's existing output or error handling";
        SnippetAiResponseSupport.ScriptImprovement improvement =
            new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high", longImprovementTitle, "Expansion is unquoted.",
                "Quote the expansion.", 2);
        SnippetAiResponseSupport.ScriptImprovement optimization =
            new SnippetAiResponseSupport.ScriptImprovement(
                "OPT-1", "optimization", "medium", "Avoid repeated parsing", "The value is parsed twice.",
                "Parse the value once and reuse it.", 2);
        SnippetAiResponseSupport.ScriptAnalysis fullAnalysis =
            new SnippetAiResponseSupport.ScriptAnalysis(
                "Prints one value.", List.of(), List.of(improvement, optimization));
        // What Verify reports for the applied script: SEC-1 is gone, the parsing finding persists
        // under a new id, and one design finding is new.
        SnippetAiResponseSupport.ScriptAnalysis verifyAnalysis =
            new SnippetAiResponseSupport.ScriptAnalysis(
                "Prints one value, quoted.", List.of(), List.of(
                    new SnippetAiResponseSupport.ScriptImprovement(
                        "OPT-2", "optimization", "medium", "Avoid repeated parsing", "The value is parsed twice.",
                        "Parse the value once and reuse it.", 3),
                    new SnippetAiResponseSupport.ScriptImprovement(
                        "DES-9", "design", "low", "Add a usage message", "Callers get no help.",
                        "Print a usage line when no value is given.", 1)));
        java.util.concurrent.atomic.AtomicInteger verifyCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger callsAtAccept = new java.util.concurrent.atomic.AtomicInteger(-1);
        AtomicBoolean verified = new AtomicBoolean();
        AtomicBoolean historyActionsDone = new AtomicBoolean();
        AtomicReference<String> analysedRecordId = new AtomicReference<>();
        AtomicBoolean diagramProviderCalled = new AtomicBoolean();
        AtomicBoolean applyProviderCalled = new AtomicBoolean();
        AtomicBoolean applyClicked = new AtomicBoolean();
        AtomicBoolean previewShown = new AtomicBoolean();
        AtomicBoolean reopenedFromStore = new AtomicBoolean();
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicBoolean autoCompletionProviderCalled = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger reopenedProviderCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Timeline> poller = new AtomicReference<>();
        AtomicReference<SnippetEditDialog> reopened = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong closedAt = new java.util.concurrent.atomic.AtomicLong();

        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null,
            null,
            null,
            null,
            null,
            null,
            request -> {
                // Test double: any invocation proves the pending completion escaped into the analysis flow.
                autoCompletionProviderCalled.set(true);
                return List.of(new SnippetAiResponseSupport.CompletionSuggestion(
                    "#!/usr/bin/perl\nuse autodie qw(open close);", "Unexpected during analysis"));
            },
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            request -> {
                diagramProviderCalled.set(true);
                return new SnippetAiResponseSupport.MermaidDiagram("Flow", "");
            },
            request -> fullAnalysis,
            request -> {
                applyProviderCalled.set(true);
                List<SnippetAiWorkflowSupport.ImprovementApplyProgress> plan =
                    SnippetAiWorkflowSupport.planSnippetImprovements(
                        request.improvements(),
                        request.dependencies(),
                        request.classicHardeningInstructions(),
                        request.inputHardeningInstructions());
                if (request.progressListener() != null && !plan.isEmpty()) {
                    SnippetAiWorkflowSupport.ImprovementApplyProgress pending = plan.get(0);
                    request.progressListener().onProgress(new SnippetAiWorkflowSupport.ImprovementApplyProgress(
                        pending.phase(), pending.stage(), pending.totalStages(),
                        pending.firstRequirement(), pending.lastRequirement(), pending.phaseRequirementCount(),
                        pending.detail(), pending.workItems(),
                        SnippetAiWorkflowSupport.ImprovementApplyProgressState.RUNNING, null));
                    request.progressListener().onProgress(new SnippetAiWorkflowSupport.ImprovementApplyProgress(
                        pending.phase(), pending.stage(), pending.totalStages(),
                        pending.firstRequirement(), pending.lastRequirement(), pending.phaseRequirementCount(),
                        pending.detail(), pending.workItems(),
                        SnippetAiWorkflowSupport.ImprovementApplyProgressState.COMPLETED,
                        new de.kortty.core.AiTokenUsage(80, 40, 120)));
                }
                return new SnippetAiResponseSupport.SnippetSecurityFix(
                    replacement,
                    "Quotes the variable expansion.",
                    List.of(new SnippetAiResponseSupport.SecurityChange(
                        "SEC-1", "printf '%s\\n' \"$value\"", "Prevents word splitting.")));
            },
            false,
            null);
        // The reopened editor must never ask a provider: everything it shows comes from the store.
        SnippetEditDialog.AiAssist countingAssist = new SnippetEditDialog.AiAssist(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            request -> {
                reopenedProviderCalls.incrementAndGet();
                return new SnippetAiResponseSupport.MermaidDiagram("Flow", "");
            },
            request -> {
                // Only Verify may analyse again; it gets the follow-up result.
                verifyCalls.incrementAndGet();
                return verifyAnalysis;
            },
            request -> {
                reopenedProviderCalls.incrementAndGet();
                return new SnippetAiResponseSupport.SnippetSecurityFix("", "", List.of());
            },
            false,
            null);
        Snippet snippet = new Snippet("analysis-preview-smoke.sh", original, "bash");
        String snippetId = snippet.getId();
        de.kortty.core.SnippetAnalysisStore store = de.kortty.core.SnippetAnalysisStore.shared();
        SnippetEditDialog editorDialog = new SnippetEditDialog(snippet, List.of(), assist);
        MonacoEditorPane editor = field(editorDialog, "contentArea", MonacoEditorPane.class);
        editorDialog.show();
        long baselineWindows = showingStages();

        // Reproduce the reported race: an edit has queued auto-completion, then Full code analysis starts
        // before the 900 ms debounce expires. The analysis must own the AI flow and discard that queue.
        field(editorDialog, "autoCompleteItem", CheckMenuItem.class).setSelected(true);
        // A static field, the notice is shown once per application run; Field.set ignores the instance.
        setField(editorDialog, "autoCompletionWarningAccepted", true);
        invoke(editorDialog, "scheduleAutoCompletion", new Class<?>[0]);
        PauseTransition ghostTimer = field(editorDialog, "autoCompletionDelay", PauseTransition.class);
        if (ghostTimer.getStatus() != Animation.Status.RUNNING) {
            throw new AssertionError("scheduleAutoCompletion did not arm the ghost-text timer");
        }

        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            try {
                switch (phase.get()) {
                    case 0 -> {
                        // The result lands in the editor's own side panel.
                        SnippetAnalysisController controller = editorDialog.analysisController();
                        SnippetAnalysisPanel panel = controller.analysisPanel();
                        if (panel == null || !panel.isPageReady()) {
                            return;
                        }
                        requireInEditor(editorDialog, "#" + SnippetAnalysisController.SIDE_PANEL_ID);
                        if (showingStages() != baselineWindows) {
                            throw new AssertionError("Full code analysis opened a window of its own");
                        }
                        WebEngine findings = findingsWebView(panel).getEngine();
                        setChecked(findings, "imp", "SEC-1", true);
                        setChecked(findings, "imp", "OPT-1", true);
                        Button apply = (Button) requireInEditor(editorDialog,
                            "#" + SnippetAnalysisController.APPLY_BUTTON_ID);
                        applyClicked.set(true);
                        phase.set(1);
                        apply.fire();
                    }
                    case 1 -> {
                        SnippetAiDiffPane review = editorDialog.analysisController().reviewPane();
                        if (review == null || review.getScene() == null || !review.isDiffReady()) {
                            return;
                        }
                        if (showingStages() != baselineWindows) {
                            throw new AssertionError("The apply opened an extra window instead of using the editor");
                        }
                        if (!original.equals(editor.getText())) {
                            throw new AssertionError("Editor content changed before the review was decided");
                        }
                        verifyProgressPane((javafx.scene.Parent) requireInEditor(editorDialog,
                            "#snippet-analysis-progress-pane"), longImprovementTitle);
                        previewShown.set(true);
                        phase.set(2);
                        ((Button) requireInEditor(editorDialog, "#snippet-ai-diff-later")).fire();
                    }
                    case 2 -> {
                        de.kortty.core.SnippetAnalysisRecord record = store.cached(snippetId).current();
                        if (record == null || record.diagram() == null) {
                            return; // wait for the diagram job to store its result
                        }
                        if (!editor.isReady()) {
                            return; // let the editor finish booting before it is closed
                        }
                        if (editorDialog.analysisController().isReviewShowing()) {
                            throw new AssertionError("Review later did not give the editor area back");
                        }
                        if (!original.equals(editor.getText())) {
                            throw new AssertionError("Review later changed the editor content");
                        }
                        de.kortty.core.SnippetAnalysisRecord.ApplyRun run = lastRun(record);
                        if (run.outcome() != de.kortty.core.SnippetAnalysisRecord.RunOutcome.PENDING_REVIEW) {
                            throw new AssertionError("Review later stored " + run.outcome() + " instead of PENDING_REVIEW");
                        }
                        if (record.selection() == null
                                || !record.selection().improvementIds().containsAll(List.of("SEC-1", "OPT-1"))) {
                            throw new AssertionError("Apply did not store the ticked findings: " + record.selection());
                        }
                        // Close the editor; the analysis lives on in the store. WebKit gets a moment
                        // to release the closed editor's pages before the next editor boots its own.
                        editorDialog.close();
                        closedAt.set(System.nanoTime());
                        phase.set(20);
                    }
                    case 20 -> {
                        if (System.nanoTime() - closedAt.get() < 1_500_000_000L) {
                            return;
                        }
                        store.flush(java.time.Duration.ofSeconds(5));
                        SnippetEditDialog again = new SnippetEditDialog(snippet, List.of(), countingAssist);
                        reopened.set(again);
                        again.show();
                        again.analysisController().showPanel();
                        phase.set(3);
                    }
                    case 3 -> {
                        SnippetEditDialog again = reopened.get();
                        SnippetAnalysisPanel panel = again.analysisController().analysisPanel();
                        if (panel == null || !panel.isPageReady()) {
                            return;
                        }
                        List<String> restored = panel.selectedFindingTokens();
                        if (!restored.containsAll(List.of("imp:SEC-1", "imp:OPT-1"))) {
                            throw new AssertionError("The reopened analysis did not restore the selection: " + restored);
                        }
                        if (panel.diagramView().currentNotice() == null
                                && panel.diagramView().currentRenderRequest(false) == null) {
                            return; // cached diagram still rendering
                        }
                        // A second store instance reads what is on disk, exactly as after a restart.
                        de.kortty.core.SnippetAnalysisStore fresh = new de.kortty.core.SnippetAnalysisStore(
                            store.directory(), id -> true, () -> 5);
                        de.kortty.core.SnippetAnalysisHistory onDisk = fresh.load(snippetId).get(10, TimeUnit.SECONDS);
                        fresh.close();
                        if (onDisk.current() == null || lastRun(onDisk.current()).outcome()
                                != de.kortty.core.SnippetAnalysisRecord.RunOutcome.PENDING_REVIEW) {
                            throw new AssertionError("The analysis file does not carry the pending review");
                        }
                        Button reviewOpen = (Button) requireInEditor(again, "#snippet-analysis-review-open");
                        reopenedFromStore.set(true);
                        phase.set(4);
                        reviewOpen.fire();
                    }
                    case 4 -> {
                        SnippetEditDialog again = reopened.get();
                        SnippetAiDiffPane review = again.analysisController().reviewPane();
                        if (review == null || review.getScene() == null || !review.isDiffReady()) {
                            return;
                        }
                        MonacoEditorPane againEditor = field(again, "contentArea", MonacoEditorPane.class);
                        if (!original.equals(againEditor.getText())) {
                            throw new AssertionError("The reopened editor changed before Accept");
                        }
                        phase.set(5);
                        ((Button) requireInEditor(again, "#snippet-ai-diff-accept")).fire();
                    }
                    case 5 -> {
                        SnippetEditDialog again = reopened.get();
                        MonacoEditorPane againEditor = field(again, "contentArea", MonacoEditorPane.class);
                        if (!replacement.equals(againEditor.getText())) {
                            throw new AssertionError("Accept did not put the reviewed result into the editor");
                        }
                        de.kortty.core.SnippetAnalysisRecord.ApplyRun run =
                            lastRun(store.cached(snippetId).current());
                        if (run.outcome() != de.kortty.core.SnippetAnalysisRecord.RunOutcome.ACCEPTED
                                || !run.appliedFindingIds().containsAll(List.of("SEC-1", "OPT-1"))) {
                            throw new AssertionError("Accept stored " + run.outcome() + " / " + run.appliedFindingIds());
                        }
                        accepted.set(true);
                        callsAtAccept.set(reopenedProviderCalls.get() + verifyCalls.get());
                        analysedRecordId.set(store.cached(snippetId).current().id());
                        phase.set(6);
                    }
                    case 6 -> {
                        // Verify is offered once a run was accepted, and analyses the applied content.
                        SnippetEditDialog again = reopened.get();
                        Button verify = (Button) requireInEditor(again, "#" + SnippetAnalysisController.VERIFY_BUTTON_ID);
                        if (!verify.isVisible() || verify.isDisabled()) {
                            return; // the accept is still being recorded
                        }
                        phase.set(7);
                        click(verify);
                    }
                    case 7 -> {
                        SnippetEditDialog again = reopened.get();
                        de.kortty.core.SnippetAnalysisHistory stored = store.cached(snippetId);
                        de.kortty.core.SnippetAnalysisRecord current = stored.current();
                        if (current.purpose() != de.kortty.core.SnippetAnalysisRecord.Purpose.VERIFY) {
                            return;
                        }
                        SnippetAnalysisPanel panel = again.analysisController().analysisPanel();
                        if (panel == null || !panel.isPageReady() || panel.verification() == null) {
                            return;
                        }
                        verifyVerification(again, panel, stored, current, analysedRecordId.get(), replacement);
                        snapshotNode(again.analysisController().sidePanel(), "snippet-analysis-verify.png");
                        verified.set(true);
                        phase.set(8);
                    }
                    case 8 -> {
                        exerciseHistoryActions(reopened.get(), store, snippetId, analysedRecordId.get());
                        historyActionsDone.set(true);
                        phase.set(9);
                        stop(poller);
                        // The editor is closed with the others at the end of the run.
                        flowDone.set(true);
                    }
                    default -> stop(poller);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, "Integrated Full-code-analysis flow failed in phase "
                    + phase.get() + ": " + e);
                stop(poller);
                editorDialog.closeWithoutPrompt();
                if (reopened.get() != null) {
                    reopened.get().closeWithoutPrompt();
                }
                flowDone.set(true);
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        poller.set(timeline);
        timeline.play();
        invoke(editorDialog, "runCodeReview", new Class<?>[0]);
        // The analysis takes the AI flow over synchronously (beginSnippetAiAction): the timer armed above
        // must already be gone, whatever the caret position, so no ghost request can slip in behind it.
        if (ghostTimer.getStatus() != Animation.Status.STOPPED) {
            throw new AssertionError("Full-code-analysis left the queued auto-completion timer running");
        }

        return () -> {
            timeline.stop();
            try {
                if (!applyClicked.get()) {
                    throw new AssertionError("Full-code-analysis selection was not applied from the side panel");
                }
                if (!applyProviderCalled.get()) {
                    throw new AssertionError("Full-code-analysis apply provider was not invoked");
                }
                if (!diagramProviderCalled.get()) {
                    throw new AssertionError("Full-code-analysis did not run the dedicated diagram request on open");
                }
                if (!previewShown.get()) {
                    throw new AssertionError("Full-code-analysis did not show the review in the editor area");
                }
                if (autoCompletionProviderCalled.get()) {
                    throw new AssertionError(
                        "Full-code-analysis allowed a pending auto-completion popup to run");
                }
                if (!reopenedFromStore.get()) {
                    throw new AssertionError("The reopened editor did not show the stored analysis (phase "
                        + phase.get() + ")");
                }
                if (callsAtAccept.get() != 0) {
                    throw new AssertionError("Reopening a stored analysis called an AI provider "
                        + callsAtAccept.get() + " time(s)");
                }
                if (!accepted.get()) {
                    throw new AssertionError("The reopened review was not accepted into the editor");
                }
                if (verifyCalls.get() != 1 || !verified.get()) {
                    Node verifyButton = reopened.get() != null
                        ? reopened.get().getDialogPane().lookup("#" + SnippetAnalysisController.VERIFY_BUTTON_ID) : null;
                    throw new AssertionError("Verify did not run exactly one follow-up analysis (calls "
                        + verifyCalls.get() + ", verified " + verified.get() + ", phase " + phase.get()
                        + ", button " + (verifyButton == null ? "missing"
                            : "visible=" + verifyButton.isVisible() + " disabled=" + verifyButton.isDisabled())
                        + ", status " + (reopened.get() != null ? field(reopened.get(), "statusLabel", Label.class).getText() : "")
                        + ")");
                }
                if (!historyActionsDone.get()) {
                    throw new AssertionError("The history actions (pin, discard, delete all) were not exercised");
                }
            } finally {
                Platform.runLater(() -> {
                    editorDialog.closeWithoutPrompt();
                    if (reopened.get() != null) {
                        reopened.get().closeWithoutPrompt();
                    }
                });
            }
        };
    }

    /** The stored comparison, the panel's banner, chips and "Resolved" list, and the report's lookup. */
    private static void verifyVerification(SnippetEditDialog editor, SnippetAnalysisPanel panel,
                                           de.kortty.core.SnippetAnalysisHistory stored,
                                           de.kortty.core.SnippetAnalysisRecord verify, String analysedId,
                                           String appliedContent) {
        de.kortty.core.SnippetAnalysisRecord.Verification verification = verify.verification();
        if (verification == null || !analysedId.equals(verify.previousRecordId())
                || !analysedId.equals(verification.previousRecordId())) {
            throw new AssertionError("The Verify record does not reference the analysed record: " + verification);
        }
        if (!verification.resolvedPreviousIds().equals(List.of("SEC-1"))
                || !"OPT-1".equals(verification.persistingCurrentToPrevious().get("OPT-2"))
                || !verification.newIds().equals(List.of("DES-9"))) {
            throw new AssertionError("Unexpected verification " + verification);
        }
        if (!verify.source().sha256().equals(de.kortty.core.SnippetDiagramSupport.contentHash(appliedContent))) {
            throw new AssertionError("Verify must analyse the accepted content");
        }
        Node banner = requireInEditor(editor, "#" + SnippetAnalysisController.VERIFY_BANNER_ID);
        String bannerText = ((Label) ((HBox) banner).getChildren().getFirst()).getText();
        String template = I18n.get("snippets.ai.analysis.verify.banner", "\u0000", 1, 1, 1);
        String expectedSuffix = template.substring(template.indexOf('\u0000') + 1);
        if (!bannerText.endsWith(expectedSuffix)) {
            throw new AssertionError("Unexpected verify banner: " + bannerText);
        }
        WebEngine engine = findingsWebView(panel).getEngine();
        Object persisting = engine.executeScript("document.querySelectorAll('.verify-chip.v-persist').length");
        Object introduced = engine.executeScript("document.querySelectorAll('.verify-chip.v-new').length");
        Object resolved = engine.executeScript("document.querySelectorAll('#verify-resolved .resolved-card').length");
        if (((Number) persisting).intValue() != 1 || ((Number) introduced).intValue() != 1
                || ((Number) resolved).intValue() != 1) {
            throw new AssertionError("Verification chips: persisting " + persisting + ", new " + introduced
                + ", resolved " + resolved);
        }
        de.kortty.core.SnippetAnalysisRecord analysed = stored.find(analysedId);
        de.kortty.core.SnippetAnalysisRecord.ApplyRun run = lastRun(analysed);
        de.kortty.core.SnippetAnalysisReport report = de.kortty.core.SnippetAnalysisReports.postApply(
            analysed, run.id(), stored.records(),
            new de.kortty.core.SnippetAnalysisReports.ReportContext("smoke", "bash", appliedContent));
        if (report.verification() == null || report.verification().resolved().size() != 1) {
            throw new AssertionError("The after-apply report did not find the verification");
        }
    }

    /**
     * Pin, view an older entry, Discard (Cancel first) and Delete all, through the panel's history
     * menu and its inline confirmation strip.
     */
    private static void exerciseHistoryActions(SnippetEditDialog editor, de.kortty.core.SnippetAnalysisStore store,
                                               String snippetId, String analysedId) {
        SnippetAnalysisController controller = editor.analysisController();
        String currentId = store.cached(snippetId).current().id();
        javafx.scene.control.MenuButton actions = (javafx.scene.control.MenuButton) requireInEditor(editor,
            "#" + SnippetAnalysisController.HISTORY_ACTIONS_ID);
        historyItem(actions, SnippetAnalysisController.HISTORY_PIN_ID).fire();
        if (!store.cached(snippetId).current().pinned()) {
            throw new AssertionError("Pin was not stored");
        }
        // Looking at the older entry is UI state only.
        controller.selectRecord(analysedId);
        if (!store.cached(snippetId).current().id().equals(currentId)) {
            throw new AssertionError("Viewing an older entry changed the current record");
        }
        historyItem(actions, SnippetAnalysisController.HISTORY_DISCARD_ID).fire();
        requireInEditor(editor, "#" + SnippetAnalysisController.CONFIRM_STRIP_ID);
        click((Button) requireInEditor(editor, "#" + SnippetAnalysisController.CONFIRM_NO_ID));
        if (editor.getDialogPane().lookup("#" + SnippetAnalysisController.CONFIRM_STRIP_ID) != null
                || store.cached(snippetId).find(analysedId) == null) {
            throw new AssertionError("Cancel must keep the analysis and close the confirmation strip");
        }
        historyItem(actions, SnippetAnalysisController.HISTORY_DISCARD_ID).fire();
        click((Button) requireInEditor(editor, "#" + SnippetAnalysisController.CONFIRM_YES_ID));
        de.kortty.core.SnippetAnalysisHistory after = store.cached(snippetId);
        if (after.find(analysedId) != null || after.records().size() != 1
                || !after.current().id().equals(currentId) || !after.current().pinned()) {
            throw new AssertionError("Discard must remove exactly the viewed analysis, got " + after.records());
        }
        historyItem(actions, SnippetAnalysisController.HISTORY_DELETE_ALL_ID).fire();
        click((Button) requireInEditor(editor, "#" + SnippetAnalysisController.CONFIRM_YES_ID));
        if (!store.cached(snippetId).isEmpty()) {
            throw new AssertionError("Delete all must remove every analysis of the snippet");
        }
        requireInEditor(editor, "#" + SnippetAnalysisController.EMPTY_STATE_ID);
    }

    /**
     * Like a mouse click: the button takes the focus first. The editor swallows button actions
     * while its code area has the focus (its Enter guard), so a bare fire() after an Accept would
     * be ignored.
     */
    private static void click(Button button) {
        button.requestFocus();
        button.fire();
    }

    private static MenuItem historyItem(javafx.scene.control.MenuButton actions, String id) {
        return actions.getItems().stream()
            .filter(item -> id.equals(item.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("History menu has no " + id));
    }

    /** The progress checklist of the apply run, now inside the editor's analysis panel. */
    private static void verifyProgressPane(javafx.scene.Parent progress, String longImprovementTitle) {
        ProgressBar improvementsProgress = nodeById(
            progress, "snippet-analysis-progress-improvements", ProgressBar.class);
        ProgressBar hardeningProgress = nodeById(
            progress, "snippet-analysis-progress-hardening", ProgressBar.class);
        if (!improvementsProgress.isVisible() || !hardeningProgress.isVisible()
                || improvementsProgress.getProgress() != 1.0
                || hardeningProgress.getProgress() != 1.0) {
            throw new AssertionError("AI-processing pane did not keep separate completed progress bars");
        }
        boolean completedCheckVisible = findNodes(progress, Label.class).stream()
            .map(Label.class::cast)
            .anyMatch(label -> "✓".equals(label.getText()));
        if (!completedCheckVisible) {
            throw new AssertionError("AI-processing pane did not mark the completed step");
        }
        boolean reportedTokensVisible = findNodes(progress, Label.class).stream()
            .map(Label.class::cast)
            .map(Label::getText)
            .anyMatch(text -> text != null && text.contains("120"));
        if (!reportedTokensVisible) {
            throw new AssertionError("AI-processing pane did not show provider-reported token usage");
        }
        HBox improvementRow = findNodes(progress, HBox.class).stream()
            .map(HBox.class::cast)
            .filter(row -> "SEC-1".equals(row.getUserData()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AI-processing pane did not expose the SEC-1 work row"));
        List<String> progressTexts = findNodes(improvementRow, Label.class).stream()
            .map(Label.class::cast)
            .map(Label::getText)
            .filter(java.util.Objects::nonNull)
            .toList();
        String improvementClassification =
            I18n.get("snippets.ai.analysis.section.security") + " · high";
        if (progressTexts.contains(improvementClassification)) {
            throw new AssertionError("AI-processing pane still showed category and severity as right-side text");
        }
        SVGPath categoryIcon = findNodes(improvementRow, SVGPath.class).stream()
            .map(SVGPath.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("AI-processing improvement row did not show its category icon"));
        if (!SnippetAiDialogSupport.sectionIconPath("security").equals(categoryIcon.getContent())
                || !Color.web(SnippetAiDialogSupport.sectionColor("security")).equals(categoryIcon.getFill())
                || !(categoryIcon.getParent() instanceof HBox identifierLine)
                || identifierLine.getChildren().indexOf(categoryIcon) != 1
                || !(identifierLine.getChildren().getFirst() instanceof Label identifier)
                || !"SEC-1".equals(identifier.getText())) {
            throw new AssertionError("AI-processing category icon was not coloured and placed beside its identifier");
        }
        Label clampedDescription = findNodes(improvementRow, Label.class).stream()
            .map(Label.class::cast)
            .filter(label -> label.getStyleClass().contains("snippet-analysis-progress-description"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "AI-processing improvement row did not expose its bounded description"));
        if (!longImprovementTitle.equals(clampedDescription.getText())
                || !clampedDescription.isWrapText()
                || clampedDescription.getTextOverrun() != OverrunStyle.ELLIPSIS) {
            throw new AssertionError("AI-processing description was not set up as a clamped, wrapping label");
        }
        SVGPath optimizationIcon = findNodes(progress, HBox.class).stream()
            .map(HBox.class::cast)
            .filter(row -> "OPT-1".equals(row.getUserData()))
            .flatMap(row -> findNodes(row, SVGPath.class).stream())
            .map(SVGPath.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("AI-processing optimization row did not show its lightning icon"));
        if (!SnippetAiDialogSupport.sectionIconPath("optimization").equals(optimizationIcon.getContent())
                || !Color.web(SnippetAiDialogSupport.sectionColor("optimization")).equals(optimizationIcon.getFill())) {
            throw new AssertionError("AI-processing optimization row did not use the coloured lightning icon");
        }
        boolean hardeningRowHasClassification = findNodes(progress, HBox.class).stream()
            .map(HBox.class::cast)
            .filter(row -> row.getUserData() instanceof String id && id.startsWith("HARDENING-"))
            .findFirst()
            .map(row -> findNodes(row, Label.class).size() > 3 || !findNodes(row, SVGPath.class).isEmpty())
            .orElseThrow(() -> new AssertionError("AI-processing pane did not expose a hardening work row"));
        if (hardeningRowHasClassification) {
            throw new AssertionError("AI-processing hardening row still showed a redundant right-side classification");
        }
    }

    private static de.kortty.core.SnippetAnalysisRecord.ApplyRun lastRun(de.kortty.core.SnippetAnalysisRecord record) {
        if (record == null || record.applyRuns().isEmpty()) {
            throw new AssertionError("The stored analysis has no apply run");
        }
        return record.applyRuns().get(record.applyRuns().size() - 1);
    }

    private static Node requireInEditor(SnippetEditDialog editor, String selector) {
        Node node = editor.getDialogPane().lookup(selector);
        if (node == null) {
            throw new AssertionError("The editor scene has no " + selector);
        }
        return node;
    }

    private static long showingStages() {
        return Window.getWindows().stream().filter(Window::isShowing).filter(Stage.class::isInstance).count();
    }

    private static void stop(AtomicReference<Timeline> poller) {
        Timeline active = poller.get();
        if (active != null) {
            active.stop();
        }
    }

    /** Verifies the editor forwards GUI/report language and code language through the two analysis actions. */
    private static Runnable exerciseSnippetAnalysisLanguageRouting() throws Exception {
        String guiLanguage = LanguageManager.getInstance().getCurrentLanguageCode();
        AtomicReference<SnippetEditDialog.CodeAnalysisRequest> analysisRequest = new AtomicReference<>();
        AtomicReference<SnippetEditDialog.ImprovementApplyRequest> applyRequest = new AtomicReference<>();
        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            request -> new SnippetAiResponseSupport.MermaidDiagram("", ""),
            request -> {
                analysisRequest.set(request);
                return null;
            },
            request -> {
                applyRequest.set(request);
                return new SnippetAiResponseSupport.SnippetSecurityFix("", "", List.of());
            },
            false,
            null);
        Snippet snippet = new Snippet(
            "analysis-language-smoke.sh",
            "#!/usr/bin/env bash\nprintf '%s\\n' \"$value\"\n",
            "bash");
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, List.of(), assist);

        @SuppressWarnings("unchecked")
        ComboBox<AiLanguageSupport.LanguageOption> textLanguageCombo =
            (ComboBox<AiLanguageSupport.LanguageOption>) nodeById(
                dialog.getDialogPane(), "snippet-ai-text-language", ComboBox.class);
        AiLanguageSupport.LanguageOption english = textLanguageCombo.getItems().stream()
            .filter(option -> option != null && "en".equals(option.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("AI text-language selector is missing English"));
        textLanguageCombo.getSelectionModel().select(english);
        textLanguageCombo.getOnAction().handle(new ActionEvent(textLanguageCombo, textLanguageCombo));

        invoke(dialog, "runCodeReview", new Class<?>[0]);
        SnippetAiResponseSupport.ScriptImprovement improvement =
            new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high", "Quote variable", "Expansion is unquoted.",
                "Quote the expansion.", 2);
        SnippetAnalysisPanel.ApplySelection selection = new SnippetAnalysisPanel.ApplySelection(
            List.of(improvement),
            List.of(),
            EnumSet.of(de.kortty.core.WorkflowScriptSupport.HardeningOption.SAFE_MODE),
            de.kortty.core.WorkflowScriptSupport.InputHardeningConfig.disabled(),
            null,
            null,
            null);
        // runCodeReview completes on a background Task. Start the apply probe on a later pulse so the
        // editor's single-AI-action guard does not correctly reject it as a concurrent second action.
        PauseTransition applyDelay = new PauseTransition(Duration.millis(250));
        applyDelay.setOnFinished(event -> {
            try {
                invoke(
                    dialog,
                    "runImprovementFixes",
                    new Class<?>[] {SnippetAnalysisPanel.ApplySelection.class},
                    selection);
            } catch (Exception e) {
                throw new IllegalStateException("Could not start the apply-language smoke probe", e);
            }
        });
        applyDelay.play();

        MonacoEditorPane editor = field(dialog, "contentArea", MonacoEditorPane.class);
        return () -> {
            try {
                SnippetEditDialog.CodeAnalysisRequest analysis = analysisRequest.get();
                if (analysis == null) {
                    throw new AssertionError("Full-code-analysis provider was not invoked");
                }
                assertProviderLanguage(
                    "full-code-analysis",
                    new ProviderLanguage(analysis.snippetLanguage(), analysis.fallbackLanguageCode()),
                    "bash",
                    guiLanguage);
                SnippetEditDialog.ImprovementApplyRequest apply = applyRequest.get();
                if (apply == null) {
                    throw new AssertionError("Apply-selected provider was not invoked");
                }
                assertProviderLanguage(
                    "apply-selected",
                    new ProviderLanguage(apply.snippetLanguage(), apply.fallbackLanguageCode()),
                    "bash",
                    "en");
                if (apply.improvements().size() != 1 || !"SEC-1".equals(apply.improvements().getFirst().id())) {
                    throw new AssertionError("Apply-selected did not forward the selected improvement");
                }
                if (apply.mandatoryHardeningInstructions() == null
                        || !apply.mandatoryHardeningInstructions().contains("--dry-run")) {
                    throw new AssertionError("Apply-selected did not forward hardening as a mandatory contract");
                }
            } finally {
                editor.dispose();
            }
        };
    }

    /** Asserts the dialog exposes both a profile combo and a re-run button. */
    /**
     * The review preview offers a per-finding focus picker once at least two findings carry a reason:
     * it must list "all changes" plus every finding id, and selecting one must reach the diff host.
     */
    private static void assertFindingFilter(DialogPane pane) {
        String allLabel = I18n.get("snippets.ai.diff.focus.all");
        ComboBox<?> focusCombo = findNodes(pane, ComboBox.class).stream()
            .map(node -> (ComboBox<?>) node)
            .filter(combo -> combo.getItems().contains(allLabel))
            .findFirst()
            .orElseThrow(() -> new AssertionError("SnippetAiDiffDialog is missing the finding focus picker"));
        if (!focusCombo.getItems().containsAll(List.of(allLabel, "SEC-1", "D1"))) {
            throw new AssertionError("Finding focus picker is missing an annotated finding: "
                + focusCombo.getItems());
        }
        if (!allLabel.equals(focusCombo.getValue())) {
            throw new AssertionError("Finding focus picker must start on every change, was "
                + focusCombo.getValue());
        }
        Button previous = findNodes(pane, Button.class).stream().map(node -> (Button) node)
            .filter(button -> "◀".equals(button.getText())).findFirst()
            .orElseThrow(() -> new AssertionError("SnippetAiDiffDialog is missing the previous-finding button"));
        Button next = findNodes(pane, Button.class).stream().map(node -> (Button) node)
            .filter(button -> "▶".equals(button.getText())).findFirst()
            .orElseThrow(() -> new AssertionError("SnippetAiDiffDialog is missing the next-finding button"));

        // Stepping forward from "all changes" must enter the list rather than do nothing, and each
        // step must re-filter the diff — the pane queues that call while Monaco is still booting.
        next.fire();
        if (!"SEC-1".equals(focusCombo.getValue())) {
            throw new AssertionError("Next finding should select SEC-1, was " + focusCombo.getValue());
        }
        next.fire();
        if (!"D1".equals(focusCombo.getValue())) {
            throw new AssertionError("Next finding should select D1, was " + focusCombo.getValue());
        }
        // Two findings in this fixture, so forward wraps back to the first, never onto "all changes".
        next.fire();
        if (!"SEC-1".equals(focusCombo.getValue())) {
            throw new AssertionError("Next finding should wrap to SEC-1, was " + focusCombo.getValue());
        }
        previous.fire();
        if (!"D1".equals(focusCombo.getValue())) {
            throw new AssertionError("Previous finding should wrap to D1, was " + focusCombo.getValue());
        }
        ((ComboBox<Object>) focusCombo).setValue(allLabel);
    }

    private static void assertControls(String name, DialogPane pane, String rerunText) {
        if (findNodes(pane, ComboBox.class).isEmpty()) {
            throw new AssertionError(name + " is missing the AI-profile picker");
        }
        boolean hasRerun = findNodes(pane, Button.class).stream()
            .anyMatch(button -> rerunText.equals(((Button) button).getText()));
        if (!hasRerun) {
            throw new AssertionError(name + " is missing the re-run button ('" + rerunText + "')");
        }
    }

    private static de.kortty.model.AiSkill smokeSkill(String id, String name, String description) {
        de.kortty.model.AiSkill skill = new de.kortty.model.AiSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setDescription(description);
        skill.setContent("Example skill content.");
        skill.setEnabled(true);
        return skill;
    }

    /**
     * Drives the real JavaFX bulk checkbox and checks the WebView selection state. Improvements from all
     * three display categories must follow it, while a dependency keeps its independently chosen state.
     */
    private static void verifyImprovementBulkSelection(CheckBox bulkCheck, WebEngine engine) {
        assertChecked(engine, "imp", "SEC-1", false);
        assertChecked(engine, "imp", "OPT-1", false);
        assertChecked(engine, "imp", "DES-1", false);

        setChecked(engine, "dep", "D1", true);
        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", true);
        assertChecked(engine, "imp", "OPT-1", true);
        assertChecked(engine, "imp", "DES-1", true);
        assertChecked(engine, "dep", "D1", true);

        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", false);
        assertChecked(engine, "imp", "OPT-1", false);
        assertChecked(engine, "imp", "DES-1", false);
        assertChecked(engine, "dep", "D1", true);

        setChecked(engine, "dep", "D1", false);
        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", true);
        assertChecked(engine, "imp", "OPT-1", true);
        assertChecked(engine, "imp", "DES-1", true);
        assertChecked(engine, "dep", "D1", false);

        bulkCheck.fire();
        assertChecked(engine, "imp", "SEC-1", false);
        assertChecked(engine, "imp", "OPT-1", false);
        assertChecked(engine, "imp", "DES-1", false);
        assertChecked(engine, "dep", "D1", false);
    }

    private static CheckBox selectAllImprovementsCheckBox(DialogPane pane) {
        String expectedText = I18n.get("snippets.ai.analysis.selectAllImprovements");
        return findNodes(pane, CheckBox.class).stream()
            .map(CheckBox.class::cast)
            .filter(checkBox -> expectedText.equals(checkBox.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "SnippetAnalysisPanel is missing the select-all-improvements checkbox ('"
                    + expectedText + "')"));
    }

    /** Keeps the bulk selector at the far-left edge of the Full-code-analysis toolbar. */
    private static void verifySelectAllImprovementPlacement(CheckBox bulkCheck, Label profileUsing) {
        if (!(bulkCheck.getParent() instanceof HBox toolbar)
                || toolbar.getChildren().isEmpty()
                || toolbar.getChildren().getFirst() != bulkCheck) {
            throw new AssertionError("Select-all-improvements must be the leftmost toolbar control");
        }
        if (profileUsing.getParent() != toolbar
                || toolbar.getChildren().indexOf(profileUsing) != 1
                || HBox.getMargin(profileUsing) == null
                || HBox.getMargin(profileUsing).getLeft() < 16) {
            throw new AssertionError(
                "The used-profile label must be visibly separated from select-all-improvements");
        }
    }

    private static <T extends Node> T nodeById(Node root, String id, Class<T> type) {
        return findNodes(root, type).stream()
            .map(type::cast)
            .filter(node -> id.equals(node.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + type.getSimpleName() + " with id " + id));
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(target.getClass().getSimpleName() + " is missing field " + name, e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(target.getClass().getSimpleName() + " is missing field " + name, e);
        }
    }

    private static void invoke(
        Object target, String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {

        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }

    private static WebView findingsWebView(SnippetAnalysisPanel panel) {
        try {
            Field field = SnippetAnalysisPanel.class.getDeclaredField("findingsView");
            field.setAccessible(true);
            return (WebView) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SnippetAnalysisPanel is missing its findings WebView", e);
        }
    }

    /** A bare DialogPane around a node, so the pane-based helpers can walk and snapshot it. */
    /**
     * The Export menu: "Before applying" and "After applying" submenus with every format; without a
     * stored apply run "After applying" is disabled and the button's tooltip says why; the
     * "Include the full script" toggle is a CheckMenuItem.
     */
    private static void verifyExportMenu(javafx.scene.control.MenuButton exportButton) {
        List<javafx.scene.control.Menu> submenus = exportButton.getItems().stream()
            .filter(javafx.scene.control.Menu.class::isInstance)
            .map(javafx.scene.control.Menu.class::cast)
            .toList();
        if (submenus.size() != 2) {
            throw new AssertionError("Export menu must offer two submenus (before/after), found " + submenus.size());
        }
        javafx.scene.control.Menu before = submenus.stream()
            .filter(menu -> I18n.get("snippets.ai.analysis.export.before").equals(menu.getText()))
            .findFirst().orElseThrow(() -> new AssertionError("Export menu is missing 'Before applying'"));
        javafx.scene.control.Menu after = submenus.stream()
            .filter(menu -> I18n.get("snippets.ai.analysis.export.after").equals(menu.getText()))
            .findFirst().orElseThrow(() -> new AssertionError("Export menu is missing 'After applying'"));
        List<String> formats = before.getItems().stream().map(javafx.scene.control.MenuItem::getText).toList();
        for (de.kortty.core.SnippetAnalysisExportService.Format format
                : de.kortty.core.SnippetAnalysisExportService.Format.values()) {
            if (!formats.contains(I18n.get(format.getMenuKey()))) {
                throw new AssertionError("'Before applying' is missing " + format + ": " + formats);
            }
        }
        if (before.isDisable()) {
            throw new AssertionError("'Before applying' must be enabled for a shown analysis");
        }
        if (!after.isDisable()) {
            throw new AssertionError("'After applying' must be disabled while no apply run exists");
        }
        String tooltip = exportButton.getTooltip() != null ? exportButton.getTooltip().getText() : "";
        if (!tooltip.contains(I18n.get("snippets.ai.analysis.export.after.unavailable"))) {
            throw new AssertionError("The Export tooltip does not explain why 'After applying' is disabled: " + tooltip);
        }
        boolean includeCode = exportButton.getItems().stream()
            .anyMatch(item -> item instanceof javafx.scene.control.CheckMenuItem
                && I18n.get("snippets.ai.analysis.export.includeCode").equals(item.getText()));
        if (!includeCode) {
            throw new AssertionError("Export menu is missing the 'Include the full script' CheckMenuItem");
        }
    }

    private static DialogPane hostPane(Node content) {
        DialogPane pane = new DialogPane();
        pane.setContent(content);
        return pane;
    }

    private static void setChecked(WebEngine engine, String kind, String id, boolean checked) {
        Object result = engine.executeScript("(function(){var b=document.querySelector(\"input.analysis-check[data-kind='"
            + kind + "'][data-id='" + id + "']\");if(!b)return false;b.checked=" + checked
            + ";return true;})()");
        if (!Boolean.TRUE.equals(result)) {
            throw new AssertionError("Missing analysis checkbox " + kind + ":" + id);
        }
    }

    private static void assertChecked(WebEngine engine, String kind, String id, boolean expected) {
        Object result = engine.executeScript("(function(){var b=document.querySelector(\"input.analysis-check[data-kind='"
            + kind + "'][data-id='" + id + "']\");return b?b.checked:null;})()");
        if (!(result instanceof Boolean actual) || actual != expected) {
            throw new AssertionError("Expected " + kind + ":" + id + " checked=" + expected + ", was " + result);
        }
    }

    private static void onLoadSuccess(WebEngine engine, Runnable action) {
        if (engine.getLoadWorker().getState() == javafx.concurrent.Worker.State.SUCCEEDED) {
            action.run();
            return;
        }
        AtomicReference<javafx.beans.value.ChangeListener<javafx.concurrent.Worker.State>> holder =
            new AtomicReference<>();
        holder.set((obs, was, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                engine.getLoadWorker().stateProperty().removeListener(holder.get());
                action.run();
            }
        });
        engine.getLoadWorker().stateProperty().addListener(holder.get());
    }

    private static List<Node> findNodes(Node root, Class<? extends Node> type) {
        List<Node> all = new ArrayList<>();
        collect(root, all);
        List<Node> matches = new ArrayList<>();
        for (Node node : all) {
            if (type.isInstance(node)) {
                matches.add(node);
            }
        }
        return matches;
    }

    private static void collect(Node root, List<Node> out) {
        if (root == null) {
            return;
        }
        out.add(root);
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    /** Builds the pane's skins so nodes behind a skin (e.g. a ScrollPane's content) become walkable. */
    private static void realize(DialogPane pane) {
        pane.applyCss();
        pane.layout();
    }

    /** A snapshot of a node as it is laid out in its scene (best effort: a failure only logs). */
    private static void snapshotNode(Node node, String fileName) {
        try {
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.web("#1e1e1e"));
            WritableImage image = node.snapshot(params, null);
            File out = new File("build/smoke/" + fileName);
            out.getParentFile().mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
            System.out.println("Snapshot written: " + out.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("Snapshot " + fileName + " failed: " + e);
        }
    }

    private static void snapshotPane(DialogPane pane, String fileName, double minWidth) throws Exception {
        pane.applyCss();
        pane.layout();
        double width = Math.max(pane.prefWidth(-1), minWidth);
        double height = Math.max(pane.prefHeight(width), 300);
        pane.resize(width, height);
        pane.applyCss();
        pane.layout();
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        WritableImage image = pane.snapshot(params, null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }

}
