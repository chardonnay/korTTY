package de.kortty.ui;

import atlantafx.base.theme.CupertinoDark;
import de.kortty.core.LanguageManager;
import de.kortty.core.SessionJournalHtmlRenderer;
import de.kortty.core.SessionJournalService;
import de.kortty.core.SessionJournalSession;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SavedAgentStep;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed check of a journal page with a KI-Chat linked to an AGENT card. Loads the rendered page in a
 * WebView with the app's bridge, clicks "Open in KI-Chat" (the bridge must get the entry id, and the
 * card must not open its log panel), opens the transcript, runs a journal search for text that only sits
 * inside a closed step list (the step must open), and writes screenshots of the page in its light and
 * dark themes.
 *
 * <p>Properties: {@code kortty.smoke.design} ({@code normal} for the Normal design, the default is
 * Cupertino Dark). Output: {@code build/smoke/journal-chat-<design>-<theme>.png}, or the directory given
 * as the first argument.</p>
 */
public final class SessionJournalChatPageSmoke {

    /** Same shape as the viewer's JournalBridge: a public class with public methods, called from the page. */
    public static final class RecordingBridge {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        public void requestOpenChat(String entryId) {
            calls.add("requestOpenChat:" + entryId);
        }

        public void themeChanged(String theme) {
            calls.add("themeChanged:" + theme);
        }

        public void liveTailStateChanged(boolean open) {
            calls.add("liveTailStateChanged:" + open);
        }

        public void liveTailHeightChanged(int heightVh) {
            calls.add("liveTailHeightChanged:" + heightVh);
        }

        public void fontScaleChanged(int percent) {
            calls.add("fontScaleChanged:" + percent);
        }
    }

    private SessionJournalChatPageSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "build/smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path isolatedHome = Files.createTempDirectory("kortty-journal-chat-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);
        String design = System.getProperty("kortty.smoke.design", "cupertino-dark");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<String> console = Collections.synchronizedList(new ArrayList<>());
        RecordingBridge bridge = new RecordingBridge();

        Thread runner = new Thread(() -> {
            try {
                run(isolatedHome, output, design, bridge, console);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                int code = failure.get() == null ? 0 : 1;
                if (failure.get() != null) {
                    failure.get().printStackTrace();
                }
                System.out.println(code == 0 ? "journal chat page smoke OK" : "journal chat page smoke FAILED");
                Runtime.getRuntime().halt(code);
            }
        }, "journal-chat-smoke");

        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            if (!"normal".equalsIgnoreCase(design)) {
                Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());
            }
            runner.start();
        });
        runner.join(TimeUnit.SECONDS.toMillis(180));
        if (runner.isAlive()) {
            System.err.println("journal chat page smoke TIMEOUT");
            Runtime.getRuntime().halt(2);
        }
    }

    private static void run(Path home, Path output, String design, RecordingBridge bridge, List<String> console)
            throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        settings.setSessionJournalStoragePath(home.resolve("journals").toString());
        fx(() -> {
            LanguageManager.getInstance().initialize(settings);
            return null;
        });
        String[] ids = new String[2];
        Path htmlFile = renderFixture(settings, ids);
        String chatEntryId = ids[0];

        CountDownLatch loaded = new CountDownLatch(1);
        WebView[] holder = new WebView[1];
        fx(() -> {
            WebView webView = new WebView();
            webView.setContextMenuEnabled(false);
            webView.setPrefSize(900, 760);
            holder[0] = webView;
            Stage stage = new Stage();
            stage.setScene(new Scene(webView, 900, 760));
            stage.setTitle("journal chat page smoke");
            webView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    loaded.countDown();
                }
            });
            stage.show();
            webView.getEngine().getLoadWorker().exceptionProperty().addListener((obs, old, error) -> {
                if (error != null) {
                    console.add("load failed: " + error);
                }
            });
            webView.getEngine().load(htmlFile.toUri().toURL().toExternalForm());
            return null;
        });
        if (!loaded.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("the journal page did not load");
        }
        WebView webView = holder[0];
        // The page's console is the witness when its one script block dies.
        com.sun.javafx.webkit.WebConsoleListener.setDefaultListener(
            (view, message, lineNumber, sourceId) -> console.add(message + " (line " + lineNumber + ")"));

        fx(() -> {
            JSObject window = (JSObject) webView.getEngine().executeScript("window");
            window.setMember("korttyJournal", bridge);
            webView.getEngine().executeScript("if(window.korttyEnableAppActions){window.korttyEnableAppActions();}");
            return null;
        });
        Thread.sleep(300);

        // 1. The button is visible in the app and the transcript exists.
        Object buttons = js(webView, "document.querySelectorAll('.chat-open-btn').length");
        check(num(buttons) == 1, "one open button expected, got " + buttons);
        check(!bool(js(webView, "document.querySelector('.chat-open-btn').hidden")), "the button stays hidden in the app");
        check(num(js(webView, "document.querySelectorAll('.chat-tag').length")) == 1, "one KI-Chat tag expected");

        // 2. The button hands the entry id to the bridge and leaves the card's log panel alone.
        js(webView, "document.querySelector('.chat-open-btn').click()");
        Thread.sleep(200);
        check(bridge.calls.contains("requestOpenChat:" + chatEntryId),
            "requestOpenChat not called with the entry id: " + bridge.calls);
        check(!bool(js(webView, "document.getElementById('logPanel').classList.contains('open')")),
            "the button must not open the log panel");

        // 3. A search for words inside a closed step list opens the list around the hit.
        js(webView, "document.querySelector('.chat-steps').open = false");
        js(webView, "document.querySelector('.chat-transcript').open = false");
        js(webView, "document.getElementById('searchToggle').click()");
        // The term sits only in a step's output, inside the closed transcript and the closed step list.
        js(webView, "var s=document.getElementById('journalSearch'); s.value='12:51:58'; s.dispatchEvent(new Event('input'))");
        Thread.sleep(500);
        check(bool(js(webView, "document.querySelector('.chat-transcript').open")),
            "the search must open the transcript around its hit");
        check(bool(js(webView, "document.querySelector('.chat-steps').open")),
            "the search must open the step list around its hit");
        check(num(js(webView, "document.querySelectorAll('mark.gs').length")) > 0, "the search must mark a hit");
        js(webView, "var s=document.getElementById('journalSearch'); s.value=''; s.dispatchEvent(new Event('input'))");
        Thread.sleep(400);
        check(!bool(js(webView, "document.querySelector('.chat-transcript').open")),
            "clearing the search closes the transcript it opened");
        check(!bool(js(webView, "document.querySelector('.chat-steps').open")),
            "clearing the search closes the step list it opened");
        js(webView, "document.getElementById('searchToggle').click()");

        // 4. Screenshots: the transcript open, in light and in dark.
        js(webView, "document.querySelector('.chat-transcript').open = true");
        js(webView, "document.querySelector('.chat-steps').open = true");
        for (String theme : List.of("light", "dark")) {
            js(webView, "document.documentElement.setAttribute('data-theme','" + theme + "')");
            Thread.sleep(400);
            // Scroll the chat card into view so the screenshot shows the transcript.
            js(webView, "document.querySelector('.chat-transcript').scrollIntoView({block:'start'})");
            Thread.sleep(300);
            BufferedImage shot = fx(() -> SwingFXUtils.fromFXImage(snapshot(webView), null));
            Path file = output.resolve("journal-chat-" + design + "-" + theme + ".png");
            ImageIO.write(shot, "png", file.toFile());
            System.out.println("wrote " + file);
        }

        // 5. The disclosure shows one triangle: the browser's own marker is off on the nested summary too.
        check("none".equals(js(webView, "getComputedStyle(document.querySelector('.chat-steps > summary')).listStyleType")),
            "the nested steps summary must not draw the native marker");

        // 6. A live reload keeps the transcript and its step list open: the viewer captures the open chats
        // before the reload and re-opens them after it, in the same order the viewer runs its scripts.
        Object keys = js(webView, SessionJournalViewerPane.OPEN_CHAT_KEYS_SCRIPT);
        String captured = String.valueOf(keys);
        check(captured.contains("entry-" + chatEntryId + "|chat"), "the open transcript must be captured: " + captured);
        check(captured.contains("entry-" + chatEntryId + "|steps|0"), "the open step list must be captured: " + captured);
        CountDownLatch reloaded = new CountDownLatch(1);
        fx(() -> {
            webView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    reloaded.countDown();
                }
            });
            webView.getEngine().reload();
            return null;
        });
        check(reloaded.await(60, TimeUnit.SECONDS), "the reload did not finish");
        Thread.sleep(300);
        check(!bool(js(webView, "document.querySelector('.chat-transcript').open")),
            "a reloaded page starts with the transcript closed");
        js(webView, SessionJournalViewerPane.restoreOpenChatsScript(List.of(captured.split("\n"))));
        check(bool(js(webView, "document.querySelector('.chat-transcript').open")),
            "the reload must re-open the transcript the user had open");
        check(bool(js(webView, "document.querySelector('.chat-steps').open")),
            "the reload must re-open the step list the user had open");

        check(console.stream().noneMatch(line -> line.contains("Could not resolve")),
            "page console reports unresolved styles: " + console);
        check(console.stream().noneMatch(line -> line.toLowerCase(Locale.ROOT).contains("uncaught")),
            "page script failed: " + console);
        System.out.println("page console: " + console);
    }

    /** The journal: a plain AGENT card and a chat-linked AGENT card with a frozen transcript. */
    private static Path renderFixture(GlobalSettings settings, String[] ids) throws Exception {
        SessionJournalService service = new SessionJournalService();
        ServerConnection connection = new ServerConnection("Web01", "192.168.1.50", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-chatsmoke0001", settings, List.of(), false);
        session.start();
        session.appendOutputChunk("nginx.service - A high performance web server\n");
        session.appendInputLine("systemctl status nginx");
        session.appendOutputChunk("Active: active (running)\n");
        session.close();
        Path dir = session.getDirectory();

        SessionJournalEntry plain = new SessionJournalEntry();
        plain.setKind(SessionJournalEntryKind.AGENT);
        plain.setTitle("Check the status page");
        plain.setText("The status page answers with HTTP 200.");
        plain.setAgentModel("Claude (claude-sonnet-5)");
        plain.setAgentDurationMillis(42_000L);
        plain.setLogStartSeq(1L);
        plain.setLogEndSeq(2L);
        service.appendEntry(dir, plain);

        SessionJournalEntry chatRun = new SessionJournalEntry();
        chatRun.setKind(SessionJournalEntryKind.AGENT);
        chatRun.setTitle("Restart nginx after the config change");
        chatRun.setText("nginx restarted and answers on port 443.");
        chatRun.setAgentModel("Claude (claude-sonnet-5)");
        chatRun.setAgentDurationMillis(383_000L);
        chatRun.setAgentTokens(12_040L);
        chatRun.setLogStartSeq(3L);
        chatRun.setLogEndSeq(4L);
        service.appendEntry(dir, chatRun);
        ids[0] = chatRun.getId();

        SessionJournalChatSnapshot snapshot = new SessionJournalChatSnapshot();
        snapshot.setTitle("Restart nginx after the config change");
        snapshot.setCapturedAt(OffsetDateTime.of(2026, 8, 3, 14, 52, 0, 0, ZoneOffset.ofHours(2)));
        SessionJournalChatMessage question = new SessionJournalChatMessage();
        question.setRole("USER");
        question.setContent("Restart **nginx** and check that port 443 answers.");
        question.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 14, 50, 0, 0, ZoneOffset.ofHours(2)));
        SessionJournalChatMessage answer = new SessionJournalChatMessage();
        answer.setRole("ASSISTANT");
        answer.setAiProfileName("Claude");
        answer.setContent("Restarted; port 443 answers:\n\n- HTTP/2 200\n- certificate valid for 61 days\n\n"
            + "```text\nserver: nginx\n```");
        answer.setCreatedAt(OffsetDateTime.of(2026, 8, 3, 14, 51, 0, 0, ZoneOffset.ofHours(2)));
        SavedAgentStep restart = new SavedAgentStep();
        restart.setType("ACTION");
        restart.setStatus("DONE");
        restart.setTitle("Restart nginx");
        restart.setCommand("systemctl restart nginx");
        restart.setExitCode(0);
        restart.setElapsedSeconds(2);
        SavedAgentStep probe = new SavedAgentStep();
        probe.setType("ACTION");
        probe.setStatus("DONE");
        probe.setTitle("Check port 443");
        probe.setCommand("curl -sI https://localhost");
        probe.setExitCode(0);
        probe.setOutput("HTTP/2 200\nserver: nginx\ndate: Mon, 03 Aug 2026 12:51:58 GMT");
        answer.setSteps(List.of(restart, probe));
        snapshot.setMessages(List.of(question, answer));
        snapshot.setFrozen(true);
        service.linkChat(dir, chatRun.getId(), "chat-smoke-1", snapshot);

        return new SessionJournalHtmlRenderer(service).renderToFile(dir);
    }

    private static WritableImage snapshot(WebView webView) {
        return webView.snapshot(null, null);
    }

    private static Object js(WebView webView, String script) throws Exception {
        return fx(() -> webView.getEngine().executeScript(script));
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static int num(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Runs on the FX thread and returns the result to the caller's thread. */
    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(60, TimeUnit.SECONDS);
    }
}
