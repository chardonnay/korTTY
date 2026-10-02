package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAnalysisRecord;
import de.kortty.core.SnippetAnalysisStore;
import de.kortty.core.SnippetFolderLayout;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import de.kortty.core.SnippetProjectAiSupport;
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
 * Stages the guide screenshots of the folder ("project") analysis, prints {@code READY x y w h}
 * for the region to capture, and holds it until the capture-done flag appears. Two scenes, chosen
 * with {@code -Pkortty.screenshotScene}:
 *
 * <ul>
 *   <li>{@code project} (default) — the Snippet Manager with a demo folder selected in the library
 *       tree and its project-analysis tab open: findings tagged with their file, the options left,
 *       the flow diagram right and a modularization proposal below.</li>
 *   <li>{@code preview} — the multi-file review window of a modularization result: one diff tab
 *       per file and the directory tree.</li>
 * </ul>
 *
 * <p>Everything shown is a throwaway demo dataset in an isolated, empty home: invented script
 * names, no hosts, keys or passwords. The analysis is seeded into the store the way a finished run
 * leaves it; no AI profile exists, so the diagram is korTTY's deterministic local fallback and no
 * AI request is made. The sibling of {@link SnippetManagerScreenshotStage}.</p>
 */
public final class SnippetProjectAnalysisScreenshotStage {

    private static final double X = 60;
    private static final double Y = 60;

    private SnippetProjectAnalysisScreenshotStage() {
    }

    public static void main(String[] args) throws Exception {
        Path doneFlag = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : null;
        String scene = System.getProperty("kortty.screenshotScene", "project");
        Path isolatedHome = Files.createTempDirectory("kortty-project-analysis-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                if ("preview".equals(scene)) {
                    showPreview(doneFlag, done);
                } else {
                    showProject(doneFlag, done);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });
        boolean finished = done.await(150, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (failure.get() != null) {
            System.err.println("PROJECT ANALYSIS SCREENSHOT FAILURE: " + failure.get());
            System.exit(1);
        }
        if (!finished) {
            System.err.println("PROJECT ANALYSIS SCREENSHOT TIMEOUT");
            System.exit(2);
        }
        System.exit(0);
    }

    private static KorTTYApplication bootstrap() throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init(); // singleton + managers; no GUI, no master-password prompt
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage("en");
        settings.setOpenToolWindowsAsTabs(false);
        settings.setCodeAnalysisHardeningExpanded(false);
        settings.setCodeAnalysisDiagramAutoGenerate(true);
        settings.setCodeAnalysisDiagramOptionsExpanded(false);
        settings.setCodeAnalysisProjectDiagramVisible(true);
        settings.setCodeAnalysisProposeModularization(true);
        LanguageManager.getInstance().initialize(settings);
        return app;
    }

    // ---- Scene: the project-analysis tab ----

    private static void showProject(Path doneFlag, CountDownLatch done) throws Exception {
        double width = sizeProperty("kortty.workspaceWidth", 2100);
        double height = sizeProperty("kortty.workspaceHeight", 1160);
        KorTTYApplication app = bootstrap();
        app.getGlobalSettingsManager().getSettings().setSnippetWorkspaceLibraryDividerPosition(0.22);
        app.getGlobalSettingsManager().getSettings().setSnippetManagerGeometry(
            new de.kortty.model.WindowGeometry(X, Y, width, height));
        SnippetManager manager = app.getSnippetManager();
        Demo demo = seedFolder(manager);
        seedProjectAnalysis(manager, demo.folderId());

        Stage stage = new Stage();
        MainWindow window = new MainWindow(stage);
        window.show();
        window.showSnippetWorkspace(null);
        SnippetWorkspaceDialog workspace = workspaceOf(window);
        Window workspaceWindow = workspace.getDialogPane().getScene().getWindow();
        workspaceWindow.setX(X);
        workspaceWindow.setY(Y);
        workspaceWindow.setWidth(width);
        workspaceWindow.setHeight(height);
        if (workspaceWindow instanceof Stage workspaceStage) {
            workspaceStage.setAlwaysOnTop(true);
            workspaceStage.toFront();
        }
        PauseTransition open = new PauseTransition(Duration.millis(2000));
        open.setOnFinished(e -> {
            try {
                java.lang.reflect.Field split = SnippetWorkspaceDialog.class.getDeclaredField("splitPane");
                split.setAccessible(true);
                ((javafx.scene.control.SplitPane) split.get(workspace)).setDividerPositions(0.22);
                java.lang.reflect.Field library = SnippetWorkspaceDialog.class.getDeclaredField("library");
                library.setAccessible(true);
                ((SnippetLibraryPane) library.get(workspace)).folderTree().selectFolder(demo.folderId());
            } catch (ReflectiveOperationException ex) {
                System.err.println("could not arrange the library: " + ex);
            }
            workspace.openFolderAnalysis(demo.folderId());
        });
        open.play();
        announceAndHold(workspaceWindow, doneFlag, done, () -> {
            workspaceWindow.hide();
            stage.hide();
        }, 9000);
    }

    // ---- Scene: the multi-file review window ----

    private static void showPreview(Path doneFlag, CountDownLatch done) throws Exception {
        double width = sizeProperty("kortty.previewWidth", 1500);
        double height = sizeProperty("kortty.previewHeight", 900);
        bootstrap();
        String original = String.join("\n",
            "#!/usr/bin/env python3",
            "\"\"\"Prints load average and the busiest processes of the demo host.\"\"\"",
            "import os",
            "import sys",
            "",
            "def read_load():",
            "    with open('/proc/loadavg') as handle:",
            "        return [float(value) for value in handle.read().split()[:3]]",
            "",
            "def top_processes(limit):",
            "    rows = os.popen('ps -eo pid,comm,%cpu --sort=-%cpu').read().splitlines()[1:]",
            "    return rows[:limit]",
            "",
            "def main():",
            "    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 5",
            "    print('load:', ' '.join(f'{v:.2f}' for v in read_load()))",
            "    for row in top_processes(limit):",
            "        print(row)",
            "",
            "if __name__ == '__main__':",
            "    main()",
            "");
        String main = String.join("\n",
            "#!/usr/bin/env python3",
            "\"\"\"Prints load average and the busiest processes of the demo host.\"\"\"",
            "import sys",
            "",
            "from lib.load import read_load",
            "from lib.processes import top_processes",
            "",
            "def main():",
            "    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 5",
            "    print('load:', ' '.join(f'{v:.2f}' for v in read_load()))",
            "    for row in top_processes(limit):",
            "        print(row)",
            "",
            "if __name__ == '__main__':",
            "    main()",
            "");
        String load = String.join("\n",
            "\"\"\"Reads the load average.\"\"\"",
            "",
            "def read_load():",
            "    with open('/proc/loadavg') as handle:",
            "        return [float(value) for value in handle.read().split()[:3]]",
            "");
        String processes = String.join("\n",
            "\"\"\"Lists the busiest processes.\"\"\"",
            "import subprocess",
            "",
            "def top_processes(limit):",
            "    output = subprocess.run(['ps', '-eo', 'pid,comm,%cpu', '--sort=-%cpu'],",
            "                            capture_output=True, text=True, check=True).stdout",
            "    return output.splitlines()[1:limit + 1]",
            "");
        List<SnippetMultiFilePreview.FileChange> files = List.of(
            new SnippetMultiFilePreview.FileChange("host_report.py", original, main, true, "demo-1"),
            new SnippetMultiFilePreview.FileChange("lib/__init__.py", null, "", false, null),
            new SnippetMultiFilePreview.FileChange("lib/load.py", null, load, false, null),
            new SnippetMultiFilePreview.FileChange("lib/processes.py", null, processes, false, null));
        var dialog = SnippetMultiFilePreview.show(null, I18n.get("snippets.modularize.previewTitle"),
            "Splits the report into a small entry point and two modules: reading the load average and "
                + "listing the busiest processes. The process list now runs ps without a shell.",
            files, EditorSettingsHelper.loadSnippetSettings(), accepted -> { });
        Window previewWindow = dialog.getDialogPane().getScene().getWindow();
        previewWindow.setX(X);
        previewWindow.setY(Y);
        previewWindow.setWidth(width);
        previewWindow.setHeight(height);
        if (previewWindow instanceof Stage previewStage) {
            previewStage.setAlwaysOnTop(true);
            previewStage.toFront();
        }
        announceAndHold(previewWindow, doneFlag, done, previewWindow::hide, 6000);
    }

    // ---- Demo data ----

    private record Demo(String folderId) {
    }

    private static Demo seedFolder(SnippetManager manager) {
        String folder = manager.ensureFolderPath("server_tools", null);
        String lib = manager.ensureFolderPath("server_tools/lib", null);
        add(manager, "server_report.sh", String.join("\n",
            "#!/usr/bin/env bash",
            "# Prints a short health report of the demo host.",
            "set -euo pipefail",
            "source \"$(dirname \"$0\")/lib/format.sh\"",
            "",
            "section \"Load\"",
            "cat /proc/loadavg",
            "section \"Disk\"",
            "df -h / | tail -n 1",
            "section \"Memory\"",
            "free -h | awk 'NR==2'",
            ""), "bash", folder);
        add(manager, "rotate_logs.sh", String.join("\n",
            "#!/usr/bin/env bash",
            "# Compresses demo logs older than seven days.",
            "source lib/format.sh",
            "log_dir=${1:-/tmp/demo-logs}",
            "for file in $(ls $log_dir/*.log); do",
            "    if [ $(find $file -mtime +7) ]; then",
            "        gzip $file && section \"rotated $file\"",
            "    fi",
            "done",
            ""), "bash", folder);
        add(manager, "format.sh", String.join("\n",
            "# Shared output helpers for the server tools.",
            "section() {",
            "    printf '\\n== %s ==\\n' \"$1\"",
            "}",
            ""), "bash", lib).setExecutable(Boolean.FALSE);
        return new Demo(folder);
    }

    private static Snippet add(SnippetManager manager, String name, String content, String language, String folderId) {
        Snippet snippet = new Snippet(name, content, language);
        snippet.setCategory("Maintenance");
        manager.addSnippet(snippet);
        snippet.setFolderId(folderId);
        return snippet;
    }

    private static void seedProjectAnalysis(SnippetManager manager, String folderId) {
        SnippetProjectAiSupport.ProjectContext context = SnippetProjectAiSupport.contextOf(
            SnippetFolderLayout.ofFolder(manager, folderId, false), manager.folderPath(folderId));
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "The folder holds two maintenance scripts and one shared helper. server_report.sh prints load, disk "
                + "and memory sections; rotate_logs.sh compresses log files older than seven days. Both print "
                + "their headings through the section function in lib/format.sh.",
            List.of(),
            List.of(
                new SnippetAiResponseSupport.ScriptImprovement("SEC-1", "security", "high",
                    "[rotate_logs.sh] Quote the log directory and file names",
                    "`$log_dir` and `$file` are expanded unquoted, so a name with spaces or glob characters "
                        + "compresses the wrong files.",
                    "Iterate with `for file in \"$log_dir\"/*.log` and quote every expansion.", 5),
                new SnippetAiResponseSupport.ScriptImprovement("DES-1", "design", "medium",
                    "[rotate_logs.sh] Source the helper relative to the script",
                    "`source lib/format.sh` only works when the script is started from its own folder; "
                        + "server_report.sh already resolves the path from its location.",
                    "Use `source \"$(dirname \"$0\")/lib/format.sh\"` like server_report.sh.", 3),
                new SnippetAiResponseSupport.ScriptImprovement("DES-2", "design", "low",
                    "Use the same strict mode in every script",
                    "Only server_report.sh enables `set -euo pipefail`; rotate_logs.sh keeps going after errors.",
                    "Add `set -euo pipefail` to rotate_logs.sh.", 1),
                new SnippetAiResponseSupport.ScriptImprovement("OPT-1", "optimization", "low",
                    "[rotate_logs.sh] Let find select the old files",
                    "One `find` call per file is slow for large directories.",
                    "Use `find \"$log_dir\" -name '*.log' -mtime +7 -exec gzip {} +`.", 6)));
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis("demo-project",
            SnippetProjectAiSupport.folderKey(folderId), analysis,
            SnippetAnalysisRecord.Source.of(context.render(), "project", "en", "en", manager.folderPath(folderId)),
            new SnippetAnalysisRecord.Provenance(null, "Demo profile", null, List.of(), List.of(), "", null),
            SnippetAnalysisRecord.Purpose.ANALYSIS, null, System.currentTimeMillis())
            .withModularization(new ModularizationPlan(true,
                "rotate_logs.sh mixes finding and compressing old files with the reporting; a small module "
                    + "makes the rotation reusable from other scripts.",
                List.of(
                    new ModuleFile("rotate_logs.sh", "Entry point: parses the arguments", List.of("main"), true, true),
                    new ModuleFile("lib/rotation.sh", "Finds and compresses old log files",
                        List.of("rotate_old_logs"), false, false),
                    new ModuleFile("lib/format.sh", "Shared output helpers", List.of("section"), false, false))));
        String mermaid = String.join("\n",
            "flowchart TD",
            "    A([server_report.sh]) --> B[Load lib/format.sh]",
            "    B --> C[Print load section]",
            "    C --> D[Print disk section]",
            "    D --> E[Print memory section]",
            "    E --> Z([Done])",
            "    F([rotate_logs.sh]) --> G[Load lib/format.sh]",
            "    G --> H{More log files?}",
            "    H -->|yes| I{Older than 7 days?}",
            "    I -->|yes| J[Compress the file]",
            "    J --> K[Print rotated section]",
            "    K --> H",
            "    I -->|no| H",
            "    H -->|no| Z");
        record = record.withDiagram(new SnippetAnalysisRecord.AnalysisDiagram("logical-structure", mermaid, List.of(),
            "", false, de.kortty.core.SnippetDiagramSupport.contentHash(context.render()), null,
            System.currentTimeMillis()));
        SnippetAnalysisStore.shared().addAnalysis(SnippetProjectAiSupport.folderKey(folderId), record);
    }

    // ---- Shared ----

    private static void announceAndHold(Window window, Path doneFlag, CountDownLatch done, Runnable close,
                                        long settleMillis) {
        PauseTransition settle = new PauseTransition(Duration.millis(settleMillis));
        settle.setOnFinished(e -> System.out.printf(Locale.ROOT, "READY %.0f %.0f %.0f %.0f%n",
            window.getX(), window.getY(), window.getWidth(), window.getHeight()));
        settle.play();
        Timeline poll = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            if (doneFlag != null && Files.exists(doneFlag)) {
                close.run();
                done.countDown();
            }
        }));
        poll.setCycleCount(260);
        poll.setOnFinished(e -> {
            close.run();
            done.countDown();
        });
        poll.play();
    }

    private static SnippetWorkspaceDialog workspaceOf(MainWindow window) throws Exception {
        java.lang.reflect.Field field = MainWindow.class.getDeclaredField("snippetWorkspace");
        field.setAccessible(true);
        return (SnippetWorkspaceDialog) field.get(window);
    }

    private static double sizeProperty(String key, double fallback) {
        try {
            String value = System.getProperty(key);
            return value != null && !value.isBlank() ? Double.parseDouble(value.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
