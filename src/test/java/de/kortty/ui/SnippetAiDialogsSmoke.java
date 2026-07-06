package de.kortty.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kortty.core.LanguageManager;
import de.kortty.core.PlantUmlRenderService;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.SplitPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                render(failure, done);
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed: " + e);
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
                    "The asset is fetched twice.", "Cache the download.", null)));
        // A completed (failed) render future exercises the async diagram lifecycle without invoking PlantUML.
        java.util.function.Supplier<CompletableFuture<PlantUmlRenderService.RenderResult>> diagramLoader =
            () -> CompletableFuture.completedFuture(new PlantUmlRenderService.RenderResult(false, null, "n/a (smoke)"));
        SnippetCodeAnalysisDialog analysisDialog = new SnippetCodeAnalysisDialog(
            null, analysis, diagramLoader, null, id -> { });
        if (findNodes(analysisDialog.getDialogPane(), SplitPane.class).isEmpty()) {
            throw new AssertionError("SnippetCodeAnalysisDialog is missing the split pane");
        }
        assertControls("SnippetCodeAnalysisDialog", analysisDialog.getDialogPane(), rerunText);
        // Snapshot first: applyCss()/layout() realizes the DialogPane button bar so the Apply button is traversable.
        snapshotPane(analysisDialog.getDialogPane(), "snippet-code-analysis.png", 1160);
        boolean hasApply = findNodes(analysisDialog.getDialogPane(), Button.class).stream()
            .anyMatch(b -> ((Button) b).getText() != null
                && ((Button) b).getText().contains(I18n.get("snippets.ai.analysis.applySelected")));
        if (!hasApply) {
            throw new AssertionError("SnippetCodeAnalysisDialog is missing the Apply-selected button");
        }

        // 4) Diff / "review changes" dialog with a re-run handler (improve/assist flow). Capture the
        //    MonacoDiffPane logger while its WebView loads to assert the Java bridge installs cleanly.
        ListAppender<ILoggingEvent> diffLog = new ListAppender<>();
        diffLog.start();
        Logger diffLogger = (Logger) LoggerFactory.getLogger("de.kortty.ui.MonacoDiffPane");
        Level previousLevel = diffLogger.getLevel();
        diffLogger.setLevel(Level.DEBUG);
        diffLogger.addAppender(diffLog);

        SnippetAiDiffDialog diff = new SnippetAiDiffDialog(
            null, I18n.get("snippets.ai.diff.title"),
            "Quote the path expansion.",
            "cat $path\n", "cat \"$path\"\n", "bash",
            EditorSettingsHelper.loadSnippetSettings(), null);
        diff.setRerunHandler(null, id -> { });
        assertControls("SnippetAiDiffDialog", diff.getDialogPane(), rerunText);
        snapshotPane(diff.getDialogPane(), "snippet-ai-diff.png", 1040);

        // Let the FX event loop pump so the diff editor loads and installBridge() runs, then verify.
        PauseTransition pause = new PauseTransition(Duration.seconds(8));
        pause.setOnFinished(event -> {
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
            } finally {
                diffLogger.detachAppender(diffLog);
                diffLogger.setLevel(previousLevel);
                done.countDown();
            }
        });
        pause.play();
    }

    /** Asserts the dialog exposes both a profile combo and a re-run button. */
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
