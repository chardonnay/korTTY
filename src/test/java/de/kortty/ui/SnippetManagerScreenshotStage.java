package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.SnippetManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;
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
 * Stages the Snippet Manager workspace for the guide screenshot — the library with a small demo
 * snippet collection on the left, one snippet open in an editor tab and its Full-code-analysis panel
 * open beside the code — prints {@code READY x y w h} for the region to capture, and holds it until
 * the capture-done flag appears. The sibling of {@link CodeAnalysisScreenshotStage}.
 *
 * <p>Everything shown is a throwaway demo dataset in an isolated, empty home: invented script names,
 * no hosts, keys or passwords. The analyses are seeded into the store the way finished runs leave
 * them, so the library's Analysis column shows its states (open findings, a clean analysis, a stale
 * one) and the panel shows a stored result; the flow diagram is korTTY's deterministic local
 * fallback, so no AI request is made at all.</p>
 */
public final class SnippetManagerScreenshotStage {

    private static final double X = 60;
    private static final double Y = 60;
    private static final double WIDTH = sizeProperty("kortty.workspaceWidth", 2100);
    private static final double HEIGHT = sizeProperty("kortty.workspaceHeight", 1060);

    private SnippetManagerScreenshotStage() {
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
            : Files.createTempDirectory("kortty-workspace-screenshot");
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

        boolean finished = done.await(150, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (failure.get() != null) {
            System.err.println("SNIPPET MANAGER SCREENSHOT FAILURE: " + failure.get());
            System.exit(1);
        }
        if (!finished) {
            System.err.println("SNIPPET MANAGER SCREENSHOT TIMEOUT");
            System.exit(2);
        }
        System.exit(0);
    }

    private static void show(Path doneFlag, CountDownLatch done) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init(); // singleton + managers; no GUI, no master-password prompt

        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        settings.setOpenToolWindowsAsTabs(false);
        settings.setSnippetWorkspaceLibraryDividerPosition(0.27);
        settings.setSnippetAnalysisPanelWidth(720.0);
        settings.setSnippetManagerGeometry(new de.kortty.model.WindowGeometry(X, Y, WIDTH, HEIGHT));
        settings.setCodeAnalysisHardeningExpanded(false);
        settings.setCodeAnalysisDiagramAutoGenerate(true);
        LanguageManager.getInstance().initialize(settings);

        String openId = seedLibrary(app.getSnippetManager());

        Stage stage = new Stage();
        MainWindow window = new MainWindow(stage);
        window.show();
        stage.setX(X);
        stage.setY(Y);

        window.showSnippetWorkspace(openId);
        SnippetWorkspaceDialog workspace = workspaceOf(window);
        Window workspaceWindow = workspace.getDialogPane().getScene().getWindow();
        workspaceWindow.setX(X);
        workspaceWindow.setY(Y);
        workspaceWindow.setWidth(WIDTH);
        workspaceWindow.setHeight(HEIGHT);
        if (workspaceWindow instanceof Stage workspaceStage) {
            workspaceStage.setAlwaysOnTop(true);
            workspaceStage.toFront();
        }

        // Let the editor tab lay out at the final width before the panel opens, so the library is not
        // folded away for room it would actually have.
        PauseTransition openPanel = new PauseTransition(Duration.millis(2500));
        openPanel.setOnFinished(e -> {
            try {
                java.lang.reflect.Field split = SnippetWorkspaceDialog.class.getDeclaredField("splitPane");
                split.setAccessible(true);
                ((javafx.scene.control.SplitPane) split.get(workspace)).setDividerPositions(0.27);
            } catch (ReflectiveOperationException ex) {
                System.err.println("could not set the library width: " + ex);
            }
            List<SnippetEditorTab> tabs = workspace.editorTabs();
            if (!tabs.isEmpty()) {
                // The panel opens by itself for a stored analysis while the window is still being
                // laid out; reopen it at the demo width now that the window has its final size.
                SnippetAnalysisController controller = tabs.getFirst().editor().analysisController();
                controller.hidePanel();
                settings.setSnippetAnalysisPanelWidth(740.0);
                controller.showPanel();
            }
        });
        openPanel.play();

        // Monaco and both analysis WebViews render asynchronously.
        PauseTransition settle = new PauseTransition(Duration.millis(9000));
        settle.setOnFinished(e -> announce(workspaceWindow.getX(), workspaceWindow.getY(),
            workspaceWindow.getWidth(), workspaceWindow.getHeight()));
        settle.play();

        Timeline poll = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if (doneFlag != null && Files.exists(doneFlag)) {
                workspaceWindow.hide();
                stage.hide();
                done.countDown();
            }
        }));
        poll.setCycleCount(260);
        poll.setOnFinished(e -> {
            workspaceWindow.hide();
            stage.hide();
            done.countDown();
        });
        poll.play();
    }

    private static SnippetWorkspaceDialog workspaceOf(MainWindow window) throws Exception {
        java.lang.reflect.Field field = MainWindow.class.getDeclaredField("snippetWorkspace");
        field.setAccessible(true);
        return (SnippetWorkspaceDialog) field.get(window);
    }

    /**
     * A small invented library; returns the id of the snippet that is opened in an editor tab. Three
     * snippets carry a stored analysis so the Analysis column shows open findings, a clean result
     * and a stale one.
     */
    private static String seedLibrary(SnippetManager manager) {
        String deploy = String.join("\n",
            "#!/usr/bin/env bash",
            "# Rolls a release archive out to the local staging directory.",
            "set -u",
            "",
            "release_dir=\"${1:-./releases}\"",
            "target_dir=\"${2:-/tmp/demo-staging}\"",
            "",
            "latest=$(ls -t \"$release_dir\"/*.tar.gz | head -n 1)",
            "if [ -z \"$latest\" ]; then",
            "    echo \"No release archive found in $release_dir\"",
            "    exit 1",
            "fi",
            "",
            "mkdir -p \"$target_dir\"",
            "tar -xzf $latest -C \"$target_dir\"",
            "",
            "for file in \"$target_dir\"/*.conf; do",
            "    if grep -q \"TODO\" \"$file\"; then",
            "        echo \"Unfinished config: $file\"",
            "    else",
            "        cp \"$file\" \"$target_dir/active/\"",
            "    fi",
            "done",
            "",
            "echo \"Deployed $(basename \"$latest\") to $target_dir\"",
            "");
        Snippet deploySnippet = snippet(manager, "deploy_release.sh", deploy, "bash", "Deployment",
            List.of("deploy", "release"), "linux");
        seedAnalysis(deploySnippet, deploy, "bash", List.of(
            new SnippetAiResponseSupport.ScriptImprovement(
                "SEC-1", "security", "high",
                "Quote the archive path passed to tar",
                "`$latest` is expanded unquoted, so a file name with spaces or glob characters breaks the extraction.",
                "Write `tar -xzf \"$latest\" -C \"$target_dir\"`.", 15),
            new SnippetAiResponseSupport.ScriptImprovement(
                "OPT-1", "optimization", "low",
                "Avoid parsing ls output",
                "`ls -t | head` misreads unusual file names and runs two extra processes.",
                "Pick the newest archive with a glob loop and `-nt` comparisons.", 8),
            new SnippetAiResponseSupport.ScriptImprovement(
                "DES-1", "design", "medium",
                "Stop on errors",
                "Only `set -u` is active, so a failed `mkdir` or `tar` does not stop the rollout.",
                "Use `set -euo pipefail` and a trap that reports the failing line.", 3)),
            true);

        String backup = String.join("\n",
            "#!/usr/bin/env python3",
            "\"\"\"Keeps the newest N files of a demo backup folder.\"\"\"",
            "import sys",
            "from pathlib import Path",
            "",
            "folder = Path(sys.argv[1] if len(sys.argv) > 1 else './backups')",
            "keep = int(sys.argv[2]) if len(sys.argv) > 2 else 7",
            "files = sorted(folder.glob('*.bak'), key=lambda p: p.stat().st_mtime, reverse=True)",
            "for old in files[keep:]:",
            "    old.unlink()",
            "    print(f'removed {old.name}')",
            "");
        Snippet backupSnippet = snippet(manager, "rotate_backups.py", backup, "python", "Maintenance",
            List.of("backup", "cleanup"), "any");
        seedAnalysis(backupSnippet, backup, "python", List.of(), true);

        String report = String.join("\n",
            "#!/usr/bin/perl",
            "use strict;",
            "use warnings;",
            "my %count;",
            "while (my $line = <STDIN>) {",
            "    $count{$1}++ if $line =~ /\\b(ERROR|WARN)\\b/;",
            "}",
            "printf \"%-6s %d\\n\", $_, $count{$_} for sort keys %count;",
            "");
        Snippet reportSnippet = snippet(manager, "log_summary.pl", report, "perl", "Monitoring",
            List.of("logs", "report"), "any");
        seedAnalysis(reportSnippet, report.replace("WARN", "WARNING"), "perl", List.of(
            new SnippetAiResponseSupport.ScriptImprovement(
                "OPT-1", "optimization", "low", "Precompile the pattern",
                "The pattern is rebuilt for every line.", "Hoist it into a `qr//` constant.", 6),
            new SnippetAiResponseSupport.ScriptImprovement(
                "DES-1", "design", "low", "Accept a file argument",
                "The script only reads standard input.", "Fall back to `<>` so files can be passed.", 5)),
            false);

        snippet(manager, "disk_usage_top10.sh", "#!/bin/sh\ndu -sh ./* 2>/dev/null | sort -rh | head -n 10\n",
            "bash", "Monitoring", List.of("disk"), "linux");
        snippet(manager, "service_status.ps1", "Get-Service | Where-Object Status -eq 'Running' | Sort-Object Name\n",
            "powershell", "Monitoring", List.of("services"), "windows");
        snippet(manager, "users_by_group.sql",
            "SELECT g.name, COUNT(*) AS members\nFROM demo_users u JOIN demo_groups g ON g.id = u.group_id\nGROUP BY g.name\nORDER BY members DESC;\n",
            "sql", "Database", List.of("report"), "any");
        snippet(manager, "nginx_site.conf",
            "server {\n    listen 8080;\n    server_name demo.local;\n    root /srv/demo;\n}\n",
            "nginx", "Configuration", List.of("web"), "linux");
        return deploySnippet.getId();
    }

    private static Snippet snippet(SnippetManager manager, String name, String content, String language,
                                   String category, List<String> tags, String os) {
        Snippet snippet = new Snippet(name, content, language);
        snippet.setCategory(category);
        snippet.setTags(new java.util.ArrayList<>(tags));
        snippet.setOperatingSystem(os);
        manager.addSnippet(snippet);
        return snippet;
    }

    private static void seedAnalysis(Snippet snippet, String analysedContent, String language,
                                     List<SnippetAiResponseSupport.ScriptImprovement> improvements,
                                     boolean withDiagram) {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            summaryFor(snippet.getName()), List.of(), improvements);
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis(
            "demo-" + snippet.getName(), snippet.getId(), analysis,
            SnippetAnalysisRecord.Source.of(analysedContent, language, "en", "en", snippet.getName()),
            new SnippetAnalysisRecord.Provenance(null, "Demo profile", null, List.of(), List.of(), "", null),
            SnippetAnalysisRecord.Purpose.ANALYSIS, null, System.currentTimeMillis());
        if (withDiagram) {
            record = record.withDiagram(new SnippetAnalysisRecord.AnalysisDiagram("logical-structure",
                SnippetDiagramSupport.buildFallbackLogicalStructureMermaid(analysedContent, language), List.of(), "",
                true, SnippetDiagramSupport.contentHash(analysedContent), null, System.currentTimeMillis()));
        }
        SnippetAnalysisStore.shared().addAnalysis(snippet.getId(), record);
    }

    private static String summaryFor(String name) {
        return switch (name) {
            case "deploy_release.sh" -> "The script picks the newest release archive from a folder, unpacks it into a "
                + "staging directory, copies every finished configuration file into the active folder and reports "
                + "unfinished ones that still contain a TODO marker.";
            case "rotate_backups.py" -> "The script keeps the newest backup files of a folder and deletes the rest.";
            default -> "The script counts ERROR and WARN lines read from standard input and prints a summary table.";
        };
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
