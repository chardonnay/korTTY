package de.kortty.ui;

import atlantafx.base.theme.CupertinoDark;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real {@link AiManagerDialog}, headed. Types into the AI Manager's search line and checks, for "Agent",
 * "Hugging Face", "Zeitlimit" and "Skills": results exist, the Local Models page is found by its keywords before
 * it is ever opened, choosing a result selects its page and nested section tab and colours the option. Also:
 * Cmd/Ctrl+F in the search line focuses it, but not inside the Monaco web view of the skills page; the General
 * page reports changes only when a field differs. Writes PNGs of the results and of the chosen option to the
 * output directory and prints one PASS or FAIL line per check.
 *
 * <p>{@code -Dkortty.smoke.design=normal} for the Normal design, default AtlantaFX Cupertino Dark;
 * {@code -Dkortty.smoke.lang} for the language, default German (the queries are German labels and keywords).
 * Argument 0 is the output directory.</p>
 */
public final class AiManagerSearchSmoke {

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static int passed;

    private AiManagerSearchSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-ai-manager-search-smoke");
        System.setProperty("user.home", home.toString());
        String lang = System.getProperty("kortty.smoke.lang", "de");
        Locale.setDefault(Locale.forLanguageTag(lang));
        Path output = Path.of(args.length > 0 ? args[0] : "build/smoke").toAbsolutePath();
        Files.createDirectories(output);
        boolean normal = "normal".equalsIgnoreCase(System.getProperty("kortty.smoke.design", "cupertino-dark"));
        String design = normal ? "normal" : "cupertino-dark";

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            if (!normal) {
                Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
            }
            Thread worker = new Thread(() -> {
                try {
                    scenario(output, design, normal, lang);
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    done.countDown();
                }
            }, "ai-manager-search-smoke");
            worker.setDaemon(true);
            worker.start();
        });
        boolean finished = done.await(180, TimeUnit.SECONDS);
        int code;
        if (!finished) {
            System.out.println("FAIL timeout after " + passed + " passed checks");
            code = 2;
        } else if (failure.get() != null) {
            System.out.println("FAIL " + failure.get().getMessage());
            failure.get().printStackTrace(System.out);
            code = 1;
        } else {
            System.out.println("AiManagerSearchSmoke PASS: " + passed + " checks, design " + design);
            code = 0;
        }
        int exit = code;
        new Thread(() -> Runtime.getRuntime().halt(exit)).start();
    }

    private static void scenario(Path output, String design, boolean normal, String lang) throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage(lang);
        LanguageManager.getInstance().initialize(settings);

        AiManagerDialog dialog = fx(() -> {
            AiManagerDialog created = new AiManagerDialog(null);
            DialogThemeHelper.applyTheme(created);
            String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(settings, null));
            if (dynamicCss != null) {
                created.getDialogPane().getStylesheets().add(dynamicCss);
            }
            if (!normal) {
                AppDesignStyleSupport.syncApplicationBaseStylesheets(created.getDialogPane().getStylesheets(),
                    AppDesign.ATLANTAFX_CUPERTINO_DARK);
            }
            return created;
        });
        TabPane tabs = field(dialog, "tabPane", TabPane.class);
        SettingsSearchBar search = fx(dialog::aiManagerSearch);
        check(search != null, "the AI Manager has a search line over its tabs");

        Tab general = tab(tabs, "ai.manager.tab.general");
        Tab localAi = tab(tabs, "ai.local.preferences.tab");
        Tab localModels = tab(tabs, "ai.local.models.tab");
        Tab skills = tab(tabs, "settings.tab.aiSkills");
        Tab memory = tab(tabs, "settings.tab.aiMemory");
        Tab knowledge = tab(tabs, "ai.rag.tab");
        Tab profiles = tab(tabs, "ai.manager.tab.profiles");

        // Before any visit: the pre-built pages already offer their options, the lazy ones only their keywords.
        long pendingBefore = fx(() -> tabs.getTabs().stream().filter(LazyTabContent::isPending).count());
        long generalOptions = fx(() -> optionsOn(search, general));
        long localAiOptions = fx(() -> optionsOn(search, localAi));
        check(generalOptions > 0, "General offers " + generalOptions + " options before its first visit");
        check(localAiOptions > 0, "Local AI offers " + localAiOptions + " options before its first visit");
        for (Tab lazy : List.of(skills, memory, localModels, knowledge, profiles)) {
            check(!SettingsSearchBar.keywords(lazy).isBlank(), "\"" + lazy.getText() + "\" carries search keywords");
        }
        long pendingAfter = fx(() -> tabs.getTabs().stream().filter(LazyTabContent::isPending).count());
        check(pendingBefore == pendingAfter, "reading the search index attaches no page");

        Scene scene = fx(() -> {
            dialog.getDialogPane().setPrefSize(1000, 720);
            dialog.show();
            return dialog.getDialogPane().getScene();
        });
        settle(scene);

        // "Agent": options on several pages; the first result is chosen with Enter, as a user would.
        query(search, scene, "Agent");
        List<SettingsSearchMatcher.Match<SettingsSearchBar.Target>> agent = results(search);
        printHits("Agent", agent);
        check(!agent.isEmpty(), "\"Agent\" finds " + agent.size() + " results");
        shot(scene, output, "ai-manager-search-agent-" + design + ".png");
        choose(search, scene, agent.getFirst());
        check(tabs.getSelectionModel().getSelectedItem() == agent.getFirst().candidate().target().section(),
            "\"Agent\": Enter selects the page of the first result (" + agent.getFirst().candidate().context() + ")");
        verifyTarget(search, scene, agent.getFirst().candidate().target(), "Agent");

        // "Hugging Face": the Local Models page is found by its keywords while it is still not built.
        query(search, scene, "Hugging Face");
        List<SettingsSearchMatcher.Match<SettingsSearchBar.Target>> huggingFace = results(search);
        printHits("Hugging Face", huggingFace);
        SettingsSearchMatcher.Match<SettingsSearchBar.Target> modelsHit = huggingFace.stream()
            .filter(match -> match.candidate().tab() && match.candidate().target().section() == localModels)
            .findFirst().orElse(null);
        check(modelsHit != null, "\"Hugging Face\" finds the Local Models page by its keywords");
        check(fx(() -> LazyTabContent.isPending(localModels)), "the search leaves the Local Models page unbuilt");
        shot(scene, output, "ai-manager-search-huggingface-" + design + ".png");
        choose(search, scene, modelsHit);
        check(tabs.getSelectionModel().getSelectedItem() == localModels, "choosing it selects Local Models");
        check(!fx(() -> LazyTabContent.isPending(localModels)), "the chosen page is built on its selection");

        // "Zeitlimit": a limit option inside the Profiles tab, possibly under a nested section tab.
        query(search, scene, "Zeitlimit");
        List<SettingsSearchMatcher.Match<SettingsSearchBar.Target>> limits = results(search);
        printHits("Zeitlimit", limits);
        SettingsSearchMatcher.Match<SettingsSearchBar.Target> limit = limits.stream()
            .filter(match -> match.candidate().target().node() != null
                && match.candidate().target().section() == profiles)
            .findFirst().orElse(null);
        check(limit != null, "\"Zeitlimit\" finds an option on the Profiles tab");
        shot(scene, output, "ai-manager-search-zeitlimit-" + design + ".png");
        choose(search, scene, limit);
        check(tabs.getSelectionModel().getSelectedItem() == profiles, "choosing it selects the Profiles tab");
        verifyTarget(search, scene, limit.candidate().target(), "Zeitlimit");
        boolean nestedOpened = fx(() -> limit.candidate().target().path().stream()
            .filter(step -> step instanceof Tab)
            .allMatch(step -> ((Tab) step).getTabPane() != null
                && ((Tab) step).getTabPane().getSelectionModel().getSelectedItem() == step));
        check(nestedOpened, "the nested section tabs on the way to the option are selected");

        // "Skills": the page itself is the first result; its Monaco editor keeps Cmd/Ctrl+F.
        query(search, scene, "Skills");
        List<SettingsSearchMatcher.Match<SettingsSearchBar.Target>> skillHits = results(search);
        printHits("Skills", skillHits);
        check(!skillHits.isEmpty() && skillHits.getFirst().candidate().tab()
                && skillHits.getFirst().candidate().target().section() == skills,
            "\"Skills\" lists the AI Skills page first");
        choose(search, scene, skillHits.getFirst());
        Thread.sleep(800);
        check(tabs.getSelectionModel().getSelectedItem() == skills, "choosing it selects the AI Skills page");
        WebView web = fx(() -> findWebView(skills.getContent()));
        check(web != null, "the AI Skills page holds its Monaco web view");
        fx(() -> {
            // The empty temp home has no skill, so the editor is disabled; enable it as selecting a skill does.
            for (Node node = web; node != null; node = node.getParent()) {
                if (node instanceof MonacoEditorPane) {
                    node.setDisable(false);
                    break;
                }
            }
            web.requestFocus();
            return null;
        });
        settle(scene);
        check(fx(() -> SettingsSearchBar.isInWebView(scene.getFocusOwner())), "the focus is inside the web view");
        fx(() -> {
            Event.fireEvent(web, commandF());
            return null;
        });
        settle(scene);
        check(scene.getFocusOwner() != search.field() && !search.field().isFocused(),
            "Cmd/Ctrl+F inside the web view is left to Monaco");

        fx(() -> {
            tabs.requestFocus();
            Event.fireEvent(tabs, commandF());
            return null;
        });
        settle(scene);
        check(fx(() -> scene.getFocusOwner() == search.field()),
            "Cmd/Ctrl+F outside a web view moves the focus to the search line");

        // The General page: nothing changed means nothing to save on close.
        AiGeneralSettingsPane generalPane = fx(() -> field(dialog, "generalPane", AiGeneralSettingsPane.class));
        check(!generalPane.hasUnsavedChanges(), "the untouched General page has nothing to save on close");
        TextField searxng = field(generalPane, "searxngUrl", TextField.class);
        fx(() -> {
            searxng.setText("https://searx.example.invalid");
            return null;
        });
        check(generalPane.hasUnsavedChanges(), "a changed General field counts as unsaved");
        fx(() -> {
            searxng.setText("");
            return null;
        });
        check(!generalPane.hasUnsavedChanges(), "a General field changed back is not unsaved");

        fx(() -> {
            dialog.close();
            return null;
        });
        check(fx(() -> !dialog.isShowing()), "the dialog closes");
    }

    /** Chooses {@code match} the way a user does: select it in the list, then Enter in the search line. */
    private static void choose(SettingsSearchBar search, Scene scene, SettingsSearchMatcher.Match<SettingsSearchBar.Target> match)
            throws Exception {
        fx(() -> {
            search.results().getSelectionModel().select(match);
            press(search.field(), KeyCode.ENTER);
            return null;
        });
        settle(scene);
    }

    /** The option is highlighted and, inside a scroll pane, in view. */
    private static void verifyTarget(SettingsSearchBar search, Scene scene, SettingsSearchBar.Target target, String query)
            throws Exception {
        if (target.node() == null) {
            return;
        }
        boolean highlighted = fx(() -> search.highlighted().contains(target.node()));
        check(highlighted, "\"" + query + "\": the chosen option is coloured");
        boolean visible = fx(() -> {
            for (Object step : target.path()) {
                if (step instanceof ScrollPane scroll) {
                    Bounds viewport = scroll.localToScene(scroll.getLayoutBounds());
                    Bounds hit = target.node().localToScene(target.node().getBoundsInLocal());
                    return viewport.intersects(hit);
                }
            }
            return true;
        });
        check(visible, "\"" + query + "\": the chosen option is scrolled into view");
    }

    private static List<SettingsSearchMatcher.Match<SettingsSearchBar.Target>> results(SettingsSearchBar search) throws Exception {
        return fx(() -> {
            check(search.results().isVisible(), "results are listed while typing");
            return List.copyOf(search.results().getItems());
        });
    }

    private static void query(SettingsSearchBar search, Scene scene, String text) throws Exception {
        fx(() -> {
            search.field().requestFocus();
            search.field().setText(text);
            return null;
        });
        settle(scene);
    }

    private static long optionsOn(SettingsSearchBar search, Tab section) {
        return search.candidates().stream()
            .filter(candidate -> !candidate.tab() && candidate.target().section() == section).count();
    }

    private static void printHits(String query, List<SettingsSearchMatcher.Match<SettingsSearchBar.Target>> hits) {
        System.out.println("hits for \"" + query + "\": " + hits.size());
        for (SettingsSearchMatcher.Match<SettingsSearchBar.Target> hit : hits.subList(0, Math.min(8, hits.size()))) {
            System.out.println("  " + hit.candidate().label() + " — " + hit.candidate().context()
                + (hit.candidate().tab() ? " (page)" : "") + " (" + hit.score() + ")");
        }
    }

    private static void shot(Scene scene, Path output, String name) throws Exception {
        javafx.scene.image.WritableImage image = fx(() -> CodingAgentSmokeFixture.snapshot(scene, 2, Color.web("#1e1e1e")));
        CodingAgentSmokeFixture.writePng(image, output.resolve(name).toFile());
        System.out.println("snapshot " + output.resolve(name));
    }

    private static Tab tab(TabPane tabs, String key) {
        String title = I18n.get(key);
        return tabs.getTabs().stream().filter(tab -> title.equals(tab.getText())).findFirst()
            .orElseThrow(() -> new AssertionError("no tab titled " + title));
    }

    private static WebView findWebView(Node node) {
        if (node instanceof WebView web) {
            return web;
        }
        if (node instanceof ScrollPane scroll) {
            return findWebView(scroll.getContent());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                WebView found = findWebView(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Cmd+F on macOS, Ctrl+F elsewhere: the shortcut the search line listens for. */
    private static KeyEvent commandF() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "f", "f", KeyCode.F, false, !MAC, false, MAC);
    }

    private static void press(javafx.scene.Node node, KeyCode code) {
        Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    /** Lets the layout, the result list placement and the scroll position settle between steps. */
    private static void settle(Scene scene) throws Exception {
        fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return null;
        });
        Thread.sleep(200);
        fx(() -> null);
    }

    /** Runs {@code action} on the FX thread and waits for its result. */
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
        System.out.println("PASS " + message);
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no field " + name + " on " + owner.getClass().getSimpleName(), e);
        }
    }
}
