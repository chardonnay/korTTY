package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetPlaceholderScanner;
import de.kortty.core.SnippetStructureScanner.OutlineNode;
import de.kortty.core.SnippetStructureScanner.Variable;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.IndexRange;
import javafx.scene.control.TreeItem;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Opens the real snippet editor with a demo Bash snippet, switches the structure panel on and
 * checks what the panel promises: the outline, variables and placeholders appear; the Monaco page
 * keeps its parent while the panel comes and goes (it would reload otherwise); the column ruler
 * stays aligned with the code; Enter on an outline entry or a variable selects its name in the
 * editor; the outline follows the caret; the filter narrows the lists. Writes a 2x snapshot of the
 * editor to {@code build/smoke/snippet-structure-<design>.png} (or the directory given as the first
 * argument).
 *
 * <p>{@code kortty.smoke.design}: {@code normal} for the Normal design (guide screenshots), the
 * default is AtlantaFX Cupertino Dark. {@code user.home} is a throwaway directory. Exit 0 = OK.
 */
public final class SnippetStructurePanelSmoke {

    static final String DEMO = """
        #!/bin/bash
        # Deploys the demo app to the staging hosts.
        set -euo pipefail

        readonly APP_DIR=/opt/demo
        export DEPLOY_ENV=staging
        declare -A ports=( [web]=8080 [api]=9090 )
        hosts=(app01.example.test app02.example.test)
        retries=3

        log() {
          local level="$1"; shift
          echo "$(date +%T) [$level] $*"
        }

        deploy() {
          local target="$1"
          local -a files
          files=("$APP_DIR"/*.tar.gz)
          log INFO "Deploying ${#files[@]} archives to $target (${date})"
          scp "${files[@]}" "${server_user}@${target}:/srv/demo/"
        }

        cleanup() {
          rm -rf "$APP_DIR/tmp"
        }

        for host in "${hosts[@]}"; do
          deploy "$host" || log WARN "retry later ($retries left, port ${ports[web]})"
        done
        cleanup${cursor}
        """;

    private static final List<String> failures = new ArrayList<>();

    private SnippetStructurePanelSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-structure-smoke");
        System.setProperty("user.home", home.toString());
        String design = System.getProperty("kortty.smoke.design", "cupertino-dark");
        Path out = args.length > 0 ? Path.of(args[0]) : Path.of("build/smoke");
        Files.createDirectories(out);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            failures.add("uncaught on " + thread.getName() + ": " + error);
            error.printStackTrace();
        });
        int code;
        try {
            code = run(design, out);
        } catch (Throwable error) {
            error.printStackTrace();
            code = 1;
        }
        int exit = code;
        new Thread(() -> Runtime.getRuntime().halt(exit)).start();
    }

    private static int run(String design, Path out) throws Exception {
        CountDownLatch toolkit = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            LanguageManager.getInstance().initialize(new GlobalSettings());
            AppDesignStyleSupport.initializeGlobalStyling("normal".equalsIgnoreCase(design)
                ? AppDesign.NORMAL : AppDesign.ATLANTAFX_CUPERTINO_DARK);
            toolkit.countDown();
        });
        if (!toolkit.await(30, TimeUnit.SECONDS)) {
            System.err.println("FAIL: toolkit did not start");
            return 2;
        }
        SnippetEditDialog editor = onFx(() -> {
            Snippet snippet = new Snippet("deploy-demo", DEMO, "bash");
            SnippetEditDialog dialog = new SnippetEditDialog(snippet, List.of());
            dialog.show();
            dialog.getDialogPane().getScene().getWindow().setWidth(1280);
            dialog.getDialogPane().getScene().getWindow().setHeight(1000);
            return dialog;
        });
        MonacoEditorPane monaco = onFx(() -> find(editor.getDialogPane(), MonacoEditorPane.class));
        waitFor("Monaco ready", () -> onFxQuiet(() -> monaco.readyProperty().get()), 60);
        Parent monacoParent = onFx(monaco::getParent);
        SnippetStructurePanel panel = onFx(editor::structurePanel);

        check(!onFx(panel::isVisible), "the panel starts hidden (toggle off by default)");
        onFxRun(() -> editor.structureToggleButton().fire());
        waitFor("structure scan", () -> onFxQuiet(() -> panel.isVisible() && !panel.analysis().outline().isEmpty()), 15);

        List<String> outline = onFx(() -> names(panel.analysis().outline()));
        check(outline.equals(List.of("log", "deploy", "cleanup")), "outline is log, deploy, cleanup: " + outline);
        List<Variable> variables = onFx(() -> panel.analysis().variables());
        check(has(variables, "target", "deploy"), "target is a local of deploy");
        check(has(variables, "APP_DIR", ""), "APP_DIR is global");
        check(has(variables, "ports", ""), "ports is global");
        List<String> placeholders = onFx(() -> panel.placeholders().stream()
            .map(p -> p.name() + "=" + p.status()).toList());
        // ${target} is a shell variable here, but korTTY would refuse it in a scheduled run all the same.
        check(placeholders.equals(List.of("date=BUILT_IN", "server_user=UNDECLARED", "target=UNDECLARED",
            "cursor=BUILT_IN")), "placeholders: " + placeholders);
        check(onFx(monaco::getParent) == monacoParent, "Monaco keeps its parent when the panel shows");

        double[] xs = onFx(() -> {
            SnippetColumnRuler ruler = find(editor.getDialogPane(), SnippetColumnRuler.class);
            return new double[] {
                ruler.localToScene(ruler.getBoundsInLocal()).getMinX(),
                monaco.localToScene(monaco.getBoundsInLocal()).getMinX(),
                panel.localToScene(panel.getBoundsInLocal()).getMaxX()};
        });
        check(Math.abs(xs[0] - xs[1]) < 1.5, "ruler and editor start at the same x: " + xs[0] + " / " + xs[1]);
        check(xs[1] > xs[2], "the code starts right of the panel: " + xs[1] + " > " + xs[2]);

        // Enter on the "cleanup" outline entry selects its name in the editor.
        OutlineNode cleanup = onFx(() -> panel.analysis().outline().get(2));
        onFxRun(() -> {
            TreeItem<OutlineNode> item = panel.outlineTree().getRoot().getChildren().get(2);
            panel.outlineTree().getSelectionModel().select(item);
            panel.outlineTree().fireEvent(enter());
        });
        waitFor("jump to cleanup", () -> onFxQuiet(() -> sameRange(monaco.getSelection(), cleanup.nameStart(), cleanup.nameEnd())), 5);

        // Enter on the variable "target" selects it.
        Variable target = variables.stream().filter(v -> v.name().equals("target")).findFirst().orElseThrow();
        onFxRun(() -> {
            panel.variableTable().getSelectionModel().select(target);
            panel.variableTable().fireEvent(enter());
        });
        waitFor("jump to target", () -> onFxQuiet(() -> sameRange(monaco.getSelection(), target.nameStart(), target.nameEnd())), 5);
        // The caret is now inside deploy: the outline follows it.
        waitFor("outline follows the caret", () -> onFxQuiet(() -> {
            TreeItem<OutlineNode> selected = panel.outlineTree().getSelectionModel().getSelectedItem();
            return selected != null && selected.getValue().name().equals("deploy");
        }), 5);

        // The filter narrows all three lists.
        onFxRun(() -> panel.filterField().setText("tar"));
        int filteredRows = onFx(() -> panel.variableTable().getItems().size());
        check(filteredRows == 1, "filter 'tar' leaves one variable: " + filteredRows);
        onFxRun(() -> panel.filterField().clear());

        // Typing changes the outline after the debounce.
        onFxRun(() -> monaco.replaceText(DEMO + "\nrollback() {\n  :\n}\n"));
        waitFor("rescan after an edit", () -> onFxQuiet(() -> names(panel.analysis().outline()).contains("rollback")), 5);
        onFxRun(() -> monaco.replaceText(DEMO));
        waitFor("rescan back", () -> onFxQuiet(() -> !names(panel.analysis().outline()).contains("rollback")), 5);

        // A screenshot with a declared variable in two states, as a user with a configured Variable Manager sees it.
        onFxRun(() -> {
            Map<String, String> declared = Map.of("server_user", "deploy");
            panel.show(panel.analysis(), SnippetPlaceholderScanner.scan(DEMO, declared::containsKey, declared::get));
            TreeItem<OutlineNode> deploy = panel.outlineTree().getRoot().getChildren().get(1);
            panel.outlineTree().getSelectionModel().select(deploy);
            panel.variableTable().getSelectionModel().select(target);
        });
        Thread.sleep(600);
        Path png = out.resolve("snippet-structure-" + design + ".png");
        onFxRun(() -> {
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(Transform.scale(2, 2));
            WritableImage image = editor.getDialogPane().snapshot(parameters, null);
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", png.toFile());
            } catch (java.io.IOException e) {
                failures.add("snapshot: " + e);
            }
        });

        // Off and on again: Monaco stays where it is.
        onFxRun(() -> editor.structureToggleButton().fire());
        check(!onFx(panel::isVisible), "the toggle hides the panel");
        check(!onFx(panel::isActive), "a hidden panel stops scanning");
        check(onFx(monaco::getParent) == monacoParent, "Monaco keeps its parent when the panel hides");
        onFxRun(() -> editor.structureToggleButton().fire());
        check(onFx(panel::isVisible), "the toggle shows it again");
        onFxRun(editor::close);
        luaEditor(out, design);

        for (String failure : failures) {
            System.err.println("FAIL: " + failure);
        }
        System.out.println((failures.isEmpty() ? "OK" : "FAILED") + " — snapshot " + png.toAbsolutePath());
        return failures.isEmpty() ? 0 : 1;
    }

    static final String LUA_DEMO = """
        #!/usr/bin/env lua
        -- Keeps account balances.
        local Account = {}
        Account.__index = Account

        function Account.new(owner, balance)
          local self = setmetatable({}, Account)
          self.owner = owner
          self.balance = balance or 0
          return self
        end

        function Account:deposit(amount)
          self.balance = self.balance + amount
        end

        local function report(accounts)
          for _, account in ipairs(accounts) do
            print(account.owner .. ": " .. account.balance)
          end
        end

        report({ Account.new("demo", 10) })
        """;

    /** A Lua snippet: highlighted by Monaco, outlined by the structure panel. */
    private static void luaEditor(Path out, String design) throws Exception {
        SnippetEditDialog editor = onFx(() -> {
            SnippetEditDialog dialog = new SnippetEditDialog(new Snippet("accounts", LUA_DEMO, "lua"), List.of());
            dialog.show();
            dialog.getDialogPane().getScene().getWindow().setWidth(1280);
            dialog.getDialogPane().getScene().getWindow().setHeight(1000);
            return dialog;
        });
        MonacoEditorPane monaco = onFx(() -> find(editor.getDialogPane(), MonacoEditorPane.class));
        waitFor("Lua editor ready", () -> onFxQuiet(() -> monaco.readyProperty().get()), 60);
        SnippetStructurePanel panel = onFx(editor::structurePanel);
        // Without an application nothing is stored, so this editor starts with the panel off as well.
        if (!onFx(panel::isVisible)) {
            onFxRun(() -> editor.structureToggleButton().fire());
        }
        waitFor("Lua structure scan", () -> onFxQuiet(() -> !panel.analysis().outline().isEmpty()), 15);
        List<String> outline = onFx(() -> names(panel.analysis().outline()));
        check(outline.equals(List.of("Account", "report")), "Lua outline is Account, report: " + outline);
        List<String> methods = onFx(() -> names(panel.analysis().outline().get(0).children()));
        check(methods.equals(List.of("new", "deposit")), "Account holds new and deposit: " + methods);
        check(onFx(() -> has(panel.analysis().variables(), "balance", "Account")), "balance is a field of Account");
        if (de.kortty.core.CodeFormatterService.getFormatterInfo("lua").available()) {
            String tooltip = onFx(() -> button(editor, I18n.get("editor.format")).getTooltip().getText());
            check(tooltip.contains("stylua"), "Format Code names StyLua: " + tooltip);
        }
        if (de.kortty.core.SnippetLinter.isSupported("lua")) {
            check(!onFx(() -> button(editor, I18n.get("editor.lint")).isDisabled()), "Check Syntax is enabled for Lua");
        }
        // Monaco tokenizes Lua: more than one token class in the rendered lines (plain text has one).
        javafx.scene.web.WebView webView = onFx(() -> find(monaco, javafx.scene.web.WebView.class));
        waitFor("Lua syntax highlighting", () -> onFxQuiet(() -> {
            Object classes = webView.getEngine().executeScript(
                "new Set(Array.from(document.querySelectorAll('.view-line span span')).map(s => s.className)).size");
            return classes instanceof Number number && number.intValue() > 3;
        }), 10);
        Thread.sleep(400);
        Path png = out.resolve("snippet-structure-lua-" + design + ".png");
        onFxRun(() -> {
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(Transform.scale(2, 2));
            WritableImage image = editor.getDialogPane().snapshot(parameters, null);
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", png.toFile());
            } catch (java.io.IOException e) {
                failures.add("Lua snapshot: " + e);
            }
        });
        onFxRun(editor::close);
    }

    // ---------------------------------------------------------------- helpers

    /** The toolbar button whose text ends with {@code label} (the labels carry a leading symbol). */
    private static javafx.scene.control.Button button(SnippetEditDialog editor, String label) {
        List<javafx.scene.control.Button> found = new ArrayList<>();
        collect(editor.getDialogPane(), javafx.scene.control.Button.class, found);
        return found.stream().filter(b -> b.getText() != null && b.getText().endsWith(label)).findFirst()
            .orElseThrow(() -> new AssertionError("no button " + label));
    }

    private static <T extends Node> void collect(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, out);
            }
        }
    }

    private static KeyEvent enter() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
    }

    private static boolean sameRange(IndexRange range, int start, int end) {
        return range != null && range.getStart() == start && range.getEnd() == end;
    }

    private static boolean has(List<Variable> variables, String name, String scope) {
        return variables.stream().anyMatch(v -> v.name().equals(name) && v.scope().equals(scope));
    }

    private static List<String> names(List<OutlineNode> nodes) {
        return nodes.stream().map(OutlineNode::name).toList();
    }

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "  ok   " : "  FAIL ") + message);
        if (!condition) {
            failures.add(message);
        }
    }

    private static <T extends Node> T find(Node root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void waitFor(String what, BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                System.out.println("  ok   " + what);
                return;
            }
            Thread.sleep(50);
        }
        failures.add("timed out waiting for " + what);
        System.out.println("  FAIL timed out waiting for " + what);
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX call timed out");
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private static boolean onFxQuiet(Callable<Boolean> work) {
        try {
            return Boolean.TRUE.equals(onFx(work));
        } catch (Exception e) {
            return false;
        }
    }

    private static void onFxRun(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }
}
