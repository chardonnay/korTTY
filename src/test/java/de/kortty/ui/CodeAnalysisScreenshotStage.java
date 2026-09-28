package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.model.GlobalSettings;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stages a snippet editor with its Full-code-analysis side panel open inside the real main window
 * for the guide screenshot,
 * prints {@code READY x y w h} for the region to capture, and holds it until the capture-done flag
 * appears. The sibling of {@link MainWindowScreenshotStage}, which it borrows its bootstrap from.
 *
 * <p>The analysis report is a fixed demo dataset rather than a live AI run, for two reasons. A real
 * run returns different findings every time, so the published screenshot could never be reproduced
 * or updated deterministically; and a screenshot is documentation, so it should show one finding of
 * every category — the guide describes all four, and a real script rarely triggers all four at
 * once. The flow diagram uses korTTY's own deterministic local fallback, so no AI request is made
 * here at all. The analysis is seeded into the store the way a finished run leaves it, so the panel
 * shows it exactly as it appears when a snippet with a stored analysis is reopened.</p>
 *
 * <p>Runs against an isolated, empty home in English, so nothing from the developer's own
 * installation can reach a published image.</p>
 */
public final class CodeAnalysisScreenshotStage {

    private static final double X = 80;
    private static final double Y = 80;
    private static final double WIDTH = sizeProperty("kortty.mainWindowWidth", 1443);
    private static final double HEIGHT = sizeProperty("kortty.mainWindowHeight", 1500);

    /** The demo script the report describes. Never a real path from anyone's machine. */
    private static final String SCRIPT_NAME = "messages_errors_table.pl";

    private CodeAnalysisScreenshotStage() {
    }

    private static double sizeProperty(String key, double fallback) {
        try {
            String value = System.getProperty(key);
            return value != null && !value.isBlank() ? Double.parseDouble(value.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void main(String[] args) throws Exception {
        Path doneFlag = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : null;
        String homeOverride = System.getProperty("kortty.screenshotHome");
        Path isolatedHome = homeOverride != null && !homeOverride.isBlank()
            ? Files.createDirectories(Path.of(homeOverride))
            : Files.createTempDirectory("kortty-analysis-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                show(doneFlag, done);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (failure.get() != null) {
            System.err.println("CODE ANALYSIS SCREENSHOT FAILURE: " + failure.get());
            System.exit(1);
        }
        if (!finished) {
            System.err.println("CODE ANALYSIS SCREENSHOT TIMEOUT");
            System.exit(2);
        }
        System.exit(0);
    }

    private static void show(Path doneFlag, CountDownLatch done) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init(); // singleton + managers; no GUI, no master-password prompt

        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        // The guide's shot shows the hardening panel open, because that is where the reader is told
        // the options live; a collapsed strip would document nothing.
        settings.setCodeAnalysisHardeningExpanded(true);
        settings.setCodeAnalysisDiagramAutoGenerate(true);
        LanguageManager.getInstance().initialize(settings);

        Stage stage = new Stage();
        MainWindow window = new MainWindow(stage);
        window.show();
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);
        stage.setX(X);
        stage.setY(Y);
        stage.setAlwaysOnTop(true);
        stage.toFront();

        settings.setSnippetAnalysisPanelWidth(760.0);
        SnippetEditDialog editor = buildEditor(app, window);
        window.hostMultiInstanceToolTab(editor);
        editor.analysisController().showPanel();

        // Both panes are WebViews that render asynchronously; announcing before they paint would
        // capture an empty report next to a spinner.
        PauseTransition settle = new PauseTransition(Duration.millis(4500));
        settle.setOnFinished(e -> {
            expandDependencies(editor);
            announce(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        });
        settle.play();

        Timeline poll = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if (doneFlag != null && Files.exists(doneFlag)) {
                stage.hide();
                done.countDown();
            }
        }));
        poll.setCycleCount(180);
        poll.setOnFinished(e -> {
            stage.hide();
            done.countDown();
        });
        poll.play();
    }

    /**
     * One finding per category, so every section icon the guide describes is actually visible, plus
     * a dependency with a reduce/replace suggestion.
     */
    private static SnippetEditDialog buildEditor(KorTTYApplication app, MainWindow window) {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "The script reads a system log file (defaulting to /var/log/messages), parses each line to "
                + "extract host, process, and message, classifies the message severity (CRITICAL, ERROR, "
                + "WARNING), normalizes the message content by replacing hex values, IP addresses, and "
                + "numbers with placeholders, aggregates identical messages by a signature, and finally "
                + "prints a table of findings sorted by occurrence count, severity, and process name.",
            List.of(new SnippetAiResponseSupport.ScriptDependency(
                "D1", "logrotate", "program",
                "The script assumes rotated log files sit in the same directory.",
                "Read the log path from configuration instead of assuming logrotate's layout.")),
            List.of(
                new SnippetAiResponseSupport.ScriptImprovement(
                    "SEC-1", "security", "medium",
                    "Ensure proper handling of Unicode log files",
                    "Reading log files as raw bytes corrupts multibyte sequences and breaks regex matching.",
                    "Insert `use open qw(:std :encoding(UTF-8));` after the strict/warnings pragmas.", 3),
                new SnippetAiResponseSupport.ScriptImprovement(
                    "OPT-1", "optimization", "low",
                    "Eliminate unused variable $host",
                    "`parse_syslog_line` returns a host value the script never uses.",
                    "Ignore the host: `my (undef, $process, $message) = parse_syslog_line($line);`.", 24),
                new SnippetAiResponseSupport.ScriptImprovement(
                    "DES-1", "design", "medium",
                    "Replace manual open/close error handling with autodie",
                    "Manual open/close checks are easy to forget; autodie fails on any filehandle error.",
                    "Add `use autodie;` and drop the explicit `or die` clauses.", 16)));

        // Enough control flow that korTTY's deterministic fallback draws a real flowchart: a
        // three-node diagram would be zoomed to fill the pane and document nothing.
        String script = String.join("\n",
            "#!/usr/bin/perl",
            "use strict;",
            "use warnings;",
            "my $path = shift // '/var/log/messages';",
            "open(my $fh, '<', $path) or die \"cannot read $path\";",
            "while (my $line = <$fh>) {",
            "    my ($host, $process, $message) = parse_syslog_line($line);",
            "    if ($message =~ /CRITICAL|ERROR/) {",
            "        record_finding($process, $message);",
            "    } else {",
            "        skip_line($line);",
            "    }",
            "}",
            "close($fh);",
            "print_findings_table();",
            "");
        de.kortty.model.Snippet snippet = new de.kortty.model.Snippet(SCRIPT_NAME, script, "perl");
        app.getSnippetManager().addSnippet(snippet);
        de.kortty.core.SnippetAnalysisRecord record = de.kortty.core.SnippetAnalysisRecord.fromAnalysis(
            "demo-analysis", snippet.getId(), analysis,
            de.kortty.core.SnippetAnalysisRecord.Source.of(script, "perl", "en", "en", SCRIPT_NAME),
            new de.kortty.core.SnippetAnalysisRecord.Provenance(null, "Default profile", null,
                List.of("perl", "shell"), List.of("Perl (Perl 5)", "Bourne-Shell (sh, POSIX)"), "", null),
            de.kortty.core.SnippetAnalysisRecord.Purpose.ANALYSIS, null, System.currentTimeMillis())
            .withDiagram(new de.kortty.core.SnippetAnalysisRecord.AnalysisDiagram("logical-structure",
                SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(script, "perl"), List.of(), "",
                true, SnippetDiagramSupport.contentHash(script), null, System.currentTimeMillis()));
        de.kortty.core.SnippetAnalysisStore.shared().addAnalysis(snippet.getId(), record);
        // With an assist the panel shows its AI controls instead of the view-only notice; no request
        // is made, because the report and diagram are already stored.
        return new SnippetEditDialog(snippet, List.of(), SnippetAiAssistFactory.create(() -> window));
    }

    /**
     * Opens the dependencies disclosure for the capture.
     *
     * <p>It renders as a collapsed {@code <details>}, which is right in the product — the reader
     * opens it when they want it — but in a still image the section reads as an empty heading, as
     * though the feature were broken. The screenshot shows it open for the same reason the
     * hardening panel is shown open: a picture of a collapsed strip documents nothing.</p>
     */
    private static void expandDependencies(SnippetEditDialog editor) {
        try {
            java.lang.reflect.Field field =
                SnippetAnalysisPanel.class.getDeclaredField("findingsView");
            field.setAccessible(true);
            javafx.scene.web.WebView view =
                (javafx.scene.web.WebView) field.get(editor.analysisController().analysisPanel());
            view.getEngine().executeScript(
                "document.querySelectorAll('details.dep-group').forEach(function (d) { d.open = true; });");
        } catch (Exception e) {
            System.err.println("could not expand the dependencies section: " + e);
        }
    }

    private static void announce(double x, double y, double width, double height) {
        System.out.printf(Locale.ROOT, "READY %.0f %.0f %.0f %.0f%n", x, y, width, height);
        System.out.flush();
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
