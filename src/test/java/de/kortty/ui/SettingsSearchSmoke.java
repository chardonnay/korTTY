package de.kortty.ui;

import atlantafx.base.theme.CupertinoDark;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real SettingsDialog: types into the search line, checks the results, chooses one on a page that
 * was never opened and checks that the page is selected, the option scrolled into view, coloured and
 * focused; Enter and Esc in the search line must not close the dialog. Writes
 * {@code settings-search-results.png} and {@code settings-search-hit.png}.
 *
 * <p>{@code -Dkortty.smoke.design=normal} for the Normal design (guide screenshots), default
 * AtlantaFX Cupertino Dark; {@code -Dkortty.smoke.lang=de} for German; {@code -Dkortty.smoke.query}
 * for the query (default "scroll").</p>
 */
public final class SettingsSearchSmoke {
    private SettingsSearchSmoke() { }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-settings-search-smoke");
        System.setProperty("user.home", home.toString());
        String lang = System.getProperty("kortty.smoke.lang", "en");
        Locale.setDefault(Locale.forLanguageTag(lang));
        Path output = Path.of(args.length > 0 ? args[0] : "build/smoke").toAbsolutePath();
        Files.createDirectories(output);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            if (!"normal".equalsIgnoreCase(System.getProperty("kortty.smoke.design", "cupertino-dark"))) {
                Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
            }
            try {
                run(home, output, lang, done, failure);
            } catch (Throwable problem) {
                failure.set(problem);
                done.countDown();
            }
        });
        boolean finished = done.await(90, TimeUnit.SECONDS);
        int code = 0;
        if (!finished) {
            System.err.println("SettingsSearchSmoke TIMEOUT");
            code = 2;
        } else if (failure.get() != null) {
            failure.get().printStackTrace();
            code = 1;
        } else {
            System.out.println("SettingsSearchSmoke OK");
        }
        int exit = code;
        new Thread(() -> Runtime.getRuntime().halt(exit)).start();
    }

    private static void run(Path home, Path output, String lang, CountDownLatch done,
                            AtomicReference<Throwable> failure) throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(home.resolve(".kortty"));
        GlobalSettings settings = manager.getSettings();
        settings.setLanguage(lang);
        LanguageManager.getInstance().initialize(settings);
        SettingsDialog dialog = new SettingsDialog(null, null, new ConfigurationManager(home), settings,
            new CredentialManager(home), new GPGKeyManager(home));
        DialogThemeHelper.applyTheme(dialog);
        // Without a running application the helper falls back to the Normal design's terminal.css;
        // give the dark run the AtlantaFX components sheet korTTY loads for that design.
        if (!"normal".equalsIgnoreCase(System.getProperty("kortty.smoke.design", "cupertino-dark"))) {
            AppDesignStyleSupport.syncApplicationBaseStylesheets(dialog.getDialogPane().getStylesheets(),
                de.kortty.model.AppDesign.ATLANTAFX_CUPERTINO_DARK);
        }
        TabPane tabs = field(dialog, "mainTabPane", TabPane.class);
        SettingsSearchBar search = dialog.settingsSearch();
        require(search != null, "the dialog has a search line");

        long pendingBefore = tabs.getTabs().stream().filter(LazyTabContent::isPending).count();
        // Every page but the keyboard one (built on first selection) offers options before it is opened.
        List<String> empty = new ArrayList<>();
        for (Tab tab : tabs.getTabs()) {
            long options = search.candidates().stream()
                .filter(candidate -> !candidate.tab() && candidate.target().section() == tab).count();
            if (options == 0 && tab.getUserData() != SettingsSection.KEYBOARD) {
                empty.add(tab.getText());
            }
        }
        require(empty.isEmpty(), "pages without searchable options: " + empty);
        long pendingAfter = tabs.getTabs().stream().filter(LazyTabContent::isPending).count();
        System.out.println("candidates=" + search.candidates().size() + " pendingTabs=" + pendingAfter);
        require(pendingAfter == pendingBefore, "searching attaches no page");

        dialog.getDialogPane().setPrefSize(1120, 1000);
        dialog.show();
        Scene scene = dialog.getDialogPane().getScene();

        String query = System.getProperty("kortty.smoke.query", "scroll");
        search.field().requestFocus();
        search.field().setText(query);
        require(search.results().isVisible(), "results show while typing");
        require(!search.results().getItems().isEmpty(), "\"" + query + "\" finds something");
        search.results().getItems().forEach(match -> System.out.println("  hit: " + match.candidate().label()
            + " — " + match.candidate().context() + " (" + match.score() + ")"));
        CodingAgentSmokeFixture.writePng(CodingAgentSmokeFixture.snapshot(scene, 2, Color.web("#1e1e1e")),
            output.resolve("settings-search-results.png").toFile());

        // Enter picks the selected result — not the dialog's Save button.
        SettingsSearchMatcher.Match<SettingsSearchBar.Target> chosen = search.results().getItems().stream()
            .filter(match -> match.candidate().target().node() != null
                && LazyTabContent.isPending(match.candidate().target().section()))
            .findFirst().orElse(search.results().getItems().getFirst());
        search.results().getSelectionModel().select(chosen);
        press(search.field(), KeyCode.ENTER);
        require(dialog.isShowing(), "Enter in the search line keeps the dialog open");
        require(!search.results().isVisible(), "choosing hides the results");
        SettingsSearchBar.Target target = chosen.candidate().target();
        require(tabs.getSelectionModel().getSelectedItem() == target.section(), "the option's page is selected");

        Platform.runLater(() -> Platform.runLater(() -> {
            try {
                Node node = target.node();
                if (node != null) {
                    require(search.highlighted().contains(node), "the option is coloured");
                    require(node.getStyleClass().contains(SettingsSearchBar.HIT_STYLE_CLASS)
                        || node.getStyleClass().contains(SettingsSearchBar.HIT_CONTROL_STYLE_CLASS), "hit style class set");
                    for (Object step : target.path()) {
                        if (step instanceof ScrollPane scroll) {
                            Bounds viewport = scroll.localToScene(scroll.getLayoutBounds());
                            Bounds hit = node.localToScene(node.getBoundsInLocal());
                            require(viewport.intersects(hit), "the option is scrolled into view");
                        }
                    }
                    Node focus = scene.getFocusOwner();
                    System.out.println("chosen=" + chosen.candidate().label() + " on " + target.section().getText()
                        + " highlighted=" + search.highlighted().size() + " focus=" + (focus != null ? focus.getClass().getSimpleName() : null));
                }
                CodingAgentSmokeFixture.writePng(CodingAgentSmokeFixture.snapshot(scene, 2, Color.web("#1e1e1e")),
                    output.resolve("settings-search-hit.png").toFile());

                // Esc clears a search first instead of closing the dialog.
                search.field().requestFocus();
                search.field().setText("zzzz-nothing");
                require(search.results().isVisible() && search.results().getItems().isEmpty(), "no match shows the empty list");
                press(search.field(), KeyCode.ESCAPE);
                require(dialog.isShowing(), "Esc in a filled search line keeps the dialog open");
                require(search.field().getText().isEmpty(), "Esc clears the search");

                // The ✕ button: hidden on an empty line, shown with text, one click empties the search.
                require(!search.clearButton().isVisible(), "no clear button on an empty search line");
                search.field().setText("backup");
                require(search.clearButton().isVisible(), "the clear button shows while there is text");
                CodingAgentSmokeFixture.writePng(CodingAgentSmokeFixture.snapshot(scene, 2, Color.web("#1e1e1e")),
                    output.resolve("settings-search-clear.png").toFile());
                search.clearButton().fire();
                require(search.field().getText().isEmpty(), "the clear button empties the search");
                require(!search.results().isVisible(), "clearing hides the results");
                require(!search.clearButton().isVisible(), "the clear button hides again");

                Hyperlink guide = (Hyperlink) scene.lookup("#settings-guide-link");
                require(guide != null && guide.isVisible(), "the manual link is shown");
                require(guide.getTooltip().getText().contains(target.section().getText()), "the link names the page");
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                done.countDown();
            }
        }));
    }

    private static void press(Node node, KeyCode code) {
        Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }
}
