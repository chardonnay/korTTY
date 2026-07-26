package de.kortty.ui;

import de.kortty.core.GuideLocationResolver;
import de.kortty.core.GuideTranslationGenerator;
import de.kortty.core.TranslationService;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that a locally generated guide language actually renders.
 *
 * <p>This is the one property the whole viewer integration rests on and the one that cannot be
 * checked structurally: a generated page is loaded from the config directory over {@code file:},
 * while the English guide it was derived from lives in a {@code jar:}. Relative asset links can
 * only work if the theme's stylesheets and images were staged next to the page — and whether
 * WebKit agrees is a question about WebKit, not about the file tree.
 *
 * <p>Fails loudly if the stylesheets are missing, empty, or blocked, which is what an unstaged
 * or cross-origin asset tree would look like: readable text with no styling at all.
 */
public final class GeneratedGuideRenderSmoke {

    private static final String LANG = "xx";
    private static final long TIMEOUT_SECONDS = 90;

    private GeneratedGuideRenderSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path configDir = Path.of("build", "smoke", "generated-guide").toAbsolutePath();
        Files.createDirectories(configDir);

        System.out.println("generating a '" + LANG + "' guide tree into " + configDir);
        GuideTranslationGenerator generator =
            new GuideTranslationGenerator(new IdentityTranslationService(), configDir);
        GuideTranslationGenerator.Result result = generator.generate(
            LANG, List.of("index.html", "features/connections.html"), null, null);
        System.out.printf("  %d page(s) written, %d skipped%n",
            result.pagesWritten(), result.pagesSkipped());

        if (!GuideLocationResolver.isGenerated(LANG, configDir)) {
            fail("the generated tree was not recognised as complete (missing pages or assets)");
        }
        String url = GuideLocationResolver.pageUrl(LANG, "index.html", configDir);
        if (url == null || !url.startsWith("file:")) {
            fail("expected a file: URL for the generated page, got " + url);
        }
        System.out.println("loading " + url);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + error));

        Platform.startup(() -> {
            try {
                WebView webView = new WebView();
                WebEngine engine = webView.getEngine();
                Stage stage = new Stage();
                stage.setScene(new Scene(webView, 1100, 800));
                stage.show();
                engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                    if (state == Worker.State.FAILED) {
                        failure.compareAndSet(null, "the page failed to load");
                        done.countDown();
                    } else if (state == Worker.State.SUCCEEDED) {
                        try {
                            check(engine, failure);
                        } catch (RuntimeException e) {
                            failure.compareAndSet(null, "inspection failed: " + e);
                        } finally {
                            done.countDown();
                        }
                    }
                });
                engine.load(url);
            } catch (Throwable error) {
                failure.compareAndSet(null, "setup failed: " + error);
                done.countDown();
            }
        });

        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            fail("timed out after " + TIMEOUT_SECONDS + "s");
        }
        Platform.exit();
        if (failure.get() != null) {
            fail(failure.get());
        }
        System.out.println("\nOK — the generated guide renders with its own styling.");
        System.exit(0);
    }

    private static void check(WebEngine engine, AtomicReference<String> failure) {
        int linkTags = number(engine, "document.querySelectorAll('link[rel=stylesheet]').length");
        int sheets = number(engine, "document.styleSheets.length");
        // A stylesheet that 404s still appears in document.styleSheets, but with no rules —
        // counting rules is what actually distinguishes "loaded" from "referenced".
        int rules = number(engine,
            "Array.from(document.styleSheets).reduce(function(n, s) {"
                + " try { return n + (s.cssRules ? s.cssRules.length : 0); } catch (e) { return n; } }, 0)");
        int images = number(engine, "document.querySelectorAll('img').length");
        int loadedImages = number(engine,
            "Array.from(document.querySelectorAll('img')).filter(function(i) {"
                + " return i.naturalWidth > 0; }).length");
        String background = string(engine,
            "getComputedStyle(document.body).backgroundColor");
        String title = string(engine, "document.title");

        System.out.printf("""
              title            %s
              <link> tags      %d
              styleSheets      %d
              CSS rules loaded %d
              images           %d loaded of %d
              body background  %s
            %n""", title, linkTags, sheets, rules, loadedImages, images, background);

        if (linkTags == 0) {
            failure.compareAndSet(null, "the page references no stylesheets at all");
        } else if (rules < 100) {
            failure.compareAndSet(null, "only " + rules + " CSS rules loaded — the theme "
                + "stylesheets did not resolve next to the generated page");
        } else if (images > 0 && loadedImages == 0) {
            failure.compareAndSet(null, "no image loaded — staged assets are not reachable");
        }
    }

    private static int number(WebEngine engine, String script) {
        Object value = engine.executeScript(script);
        return value instanceof Number n ? n.intValue() : -1;
    }

    private static String string(WebEngine engine, String script) {
        Object value = engine.executeScript(script);
        return value != null ? value.toString() : "(null)";
    }

    private static void fail(String message) {
        System.err.println("FAILED: " + message);
        System.exit(1);
    }

    /** Keeps the page in English; this smoke is about rendering, not translation quality. */
    private static final class IdentityTranslationService implements TranslationService {
        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return text;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
            return List.copyOf(texts);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
