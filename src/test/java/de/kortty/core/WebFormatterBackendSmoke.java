package de.kortty.core;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end smoke for the real browser bundles and the JavaFX JavaScript bridge. */
public final class WebFormatterBackendSmoke {

    private WebFormatterBackendSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.startup(() -> {
            Thread formatterThread = new Thread(() -> {
                try {
                    verifyPrettierAndSafeArgumentTransfer();
                    verifyConfiguredLineWidth();
                    verifyHtmlEmbeddedFormatting();
                    verifySqlFormatter();
                    verifyEngineRecoversAfterFormatterFailure();
                    verifyEngineRecoversAfterTimeout();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    complete.countDown();
                }
            }, "web-formatter-smoke-worker");
            formatterThread.setDaemon(true);
            formatterThread.start();
        });

        boolean finished = complete.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            throw new IllegalStateException("Timed out waiting for bundled web formatter smoke");
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Bundled web formatter smoke failed", failure.get());
        }
        System.out.println("Bundled WebView formatter smoke passed.");
    }

    private static void verifyPrettierAndSafeArgumentTransfer() throws Exception {
        String source = "const payload=\"</script><script>window.__injected=true</script>\";";
        String formatted = CodeFormatterService.formatOrThrow(source, "javascript");
        if (formatted == null
            || !formatted.contains("window.__injected")
            || !formatted.contains("const payload =")) {
            throw new AssertionError("Prettier did not preserve source passed through JSObject.call: " + formatted);
        }
    }

    private static void verifyConfiguredLineWidth() throws Exception {
        String source = "const values = [\"alpha\", \"bravo\", \"charlie\", \"delta\", \"echo\"];";
        String formatted = CodeFormatterService.formatOrThrow(source, "typescript", 30);
        if (formatted == null || formatted.lines().count() < 3) {
            throw new AssertionError("Prettier did not apply the configured print width: " + formatted);
        }
    }

    private static void verifySqlFormatter() throws Exception {
        String formatted = CodeFormatterService.formatOrThrow(
            "select id,name from users where active=1;",
            "sql");
        if (formatted == null
            || !formatted.toUpperCase().contains("SELECT")
            || !formatted.contains("\n")) {
            throw new AssertionError("sql-formatter did not format SQL synchronously: " + formatted);
        }
    }

    private static void verifyHtmlEmbeddedFormatting() throws Exception {
        String source = "<div><script>const value={enabled:true};</script><style>.item{color:red}</style></div>";
        String formatted = CodeFormatterService.formatOrThrow(source, "html");
        if (formatted == null
            || !formatted.contains("const value = { enabled: true };")
            || !formatted.contains("color: red;")) {
            throw new AssertionError("Prettier did not format embedded HTML languages: " + formatted);
        }
    }

    private static void verifyEngineRecoversAfterFormatterFailure() throws Exception {
        try {
            CodeFormatterService.formatOrThrow("const = ;", "javascript");
            throw new AssertionError("Invalid JavaScript unexpectedly formatted successfully");
        } catch (CodeFormatterService.FormatterException expected) {
            // Every failed request drops its WebEngine; the next request must lazily rebuild it.
        }

        String recovered = CodeFormatterService.formatOrThrow("body{color:red}", "css");
        if (recovered == null || !recovered.contains("color: red")) {
            throw new AssertionError("Web formatter did not recover after resetting its engine: " + recovered);
        }
    }

    private static void verifyEngineRecoversAfterTimeout() throws Exception {
        try {
            WebFormatterBackend.format(
                "__kortty_test_never_complete__",
                null,
                "",
                null,
                1);
            throw new AssertionError("Deliberately unresolved formatter request did not time out");
        } catch (CodeFormatterService.FormatterException expected) {
            if (!expected.getMessage().contains("timed out")) {
                throw expected;
            }
        }

        String recovered = CodeFormatterService.formatOrThrow("const value={ready:true}", "typescript");
        if (recovered == null || !recovered.contains("ready: true")) {
            throw new AssertionError("Web formatter did not recover after timeout reset: " + recovered);
        }
    }
}
