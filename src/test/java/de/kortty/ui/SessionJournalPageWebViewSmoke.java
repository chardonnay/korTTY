package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SessionJournalService;
import de.kortty.core.SessionJournalSession;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads a real generated journal page into the SAME WebKit engine the app uses and drives its
 * context menu against a real {@code setMember} Java bridge.
 *
 * <p>This exists because a page that is fine in a desktop browser can still die in JavaFX
 * WebKit, and the page is one script block: one runtime error kills every interaction, silently.
 * The smoke pipes the page's console through {@code WebConsoleListener}, so a failure prints the
 * actual error and line instead of "the menu does not appear".</p>
 *
 * <p>Run via the {@code sessionJournalPageWebViewSmoke} Gradle task. Exit 0 = OK.</p>
 */
public final class SessionJournalPageWebViewSmoke {

    /** Same shape as the viewer's JournalBridge: public class, public methods, weakly held. */
    public static final class RecordingBridge {
        final List<String> calls = Collections.synchronizedList(new java.util.ArrayList<>());

        public boolean copyText(String text) {
            calls.add("copyText");
            return true;
        }

        public boolean copyImage(String relativePath) {
            calls.add("copyImage:" + relativePath);
            return true;
        }

        public void fontScaleChanged(int percent) {
            calls.add("fontScaleChanged:" + percent);
        }

        public void themeChanged(String theme) {
            calls.add("themeChanged:" + theme);
        }

        public void requestReplace(String term) {
            calls.add("requestReplace");
        }

        public void requestMarker(String entryId) {
            calls.add("requestMarker:" + entryId);
        }

        public void requestAnnotate(String entryId) {
            calls.add("requestAnnotate:" + entryId);
        }

        public void requestSaveImage(String relativePath) {
            calls.add("requestSaveImage:" + relativePath);
        }

        public void requestRename() {
            calls.add("requestRename");
        }

        public void applyTimeWindows(String windowsJson) {
            calls.add("applyTimeWindows");
        }

        public void liveTailStateChanged(boolean open) {
            calls.add("liveTailStateChanged:" + open);
        }

        public void liveTailHeightChanged(int heightVh) {
            calls.add("liveTailHeightChanged:" + heightVh);
        }
    }

    private SessionJournalPageWebViewSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-journal-webview-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        List<String> console = Collections.synchronizedList(new java.util.ArrayList<>());
        RecordingBridge bridge = new RecordingBridge();

        Platform.startup(() -> {
            try {
                // The page's console is the only witness when its single script block dies.
                com.sun.javafx.webkit.WebConsoleListener.setDefaultListener(
                    (view, message, lineNumber, sourceId) ->
                        console.add(message + " (line " + lineNumber + ")"));

                GlobalSettings settings = new GlobalSettings();
                settings.setLanguage("en");
                settings.setSessionJournalStoragePath(isolatedHome.resolve("journals").toString());
                LanguageManager.getInstance().initialize(settings);
                Path htmlFile = renderFixture(settings);

                WebView webView = new WebView();
                webView.setContextMenuEnabled(false);
                Stage stage = new Stage();
                stage.setScene(new Scene(webView, 900, 700));
                stage.show();

                webView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                    if (state != Worker.State.SUCCEEDED) {
                        return;
                    }
                    // Deferred exactly like the viewer: executeScript re-entrant to the
                    // load-finished dispatch crashes intermittently.
                    Platform.runLater(() -> {
                        try {
                            JSObject window = (JSObject) webView.getEngine().executeScript("window");
                            window.setMember("korttyJournal", bridge);
                            webView.getEngine().executeScript(
                                "if(window.korttyEnableReplace){window.korttyEnableReplace();}"
                                    + "if(window.korttyEnableRange){window.korttyEnableRange();}"
                                    + "if(window.korttyEnableAppActions){window.korttyEnableAppActions();}");
                            verify(webView, bridge, console);
                        } catch (Throwable t) {
                            failure.compareAndSet(null, stack(t));
                        } finally {
                            done.countDown();
                        }
                    });
                });
                webView.getEngine().load(htmlFile.toUri().toURL().toExternalForm());
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        if (!console.isEmpty()) {
            System.out.println("page console:");
            console.forEach(line -> System.out.println("  " + line));
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SMOKE FAILURE: " + fail);
            System.exit(1);
        }
        System.out.println("session journal page WebView smoke OK");
        System.exit(0);
    }

    /** A closed journal with a marked entry and a screenshot, like the page-dump fixture. */
    private static Path renderFixture(GlobalSettings settings) throws Exception {
        SessionJournalService service = new SessionJournalService();
        ServerConnection connection = new ServerConnection("Web01", "192.168.1.50", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(
            connection, "tab-1234567890ab", settings, List.of(), false);
        session.start();
        session.appendOutputChunk("Active: active (running)\n");
        session.appendInputLine("systemctl status nginx");
        java.awt.image.BufferedImage png =
            new java.awt.image.BufferedImage(320, 160, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(png, "png", bytes);
        session.attachScreenshot(bytes.toByteArray(), "status");
        session.close();
        Path dir = session.getDirectory();

        SessionJournalEntry summary = new SessionJournalEntry();
        summary.setKind(SessionJournalEntryKind.AI_SUMMARY);
        summary.setTitle("Checked nginx");
        summary.setText("The service is running.");
        summary.setMarker(de.kortty.model.SessionJournalMarker.IMPORTANT);
        summary.setLogStartSeq(1L);
        summary.setLogEndSeq(2L);
        service.appendEntry(dir, summary);

        return new de.kortty.core.SessionJournalHtmlRenderer(service).renderToFile(dir);
    }

    private static void verify(WebView webView, RecordingBridge bridge, List<String> console) {
        // Where did the page's single script block get to? Each window function marks a stage.
        String probes = (String) webView.getEngine().executeScript(
            "(function(){return [typeof window.korttyEnableReplace,"
                + "typeof window.korttyEnableAppActions,"
                + "typeof window.korttyStartRange].join(',');})()");
        System.out.println("script stages (replace,appActions,range): " + probes);
        if (probes.contains("undefined")) {
            throw new IllegalStateException(
                "the page script died before finishing — see the console lines above");
        }

        // The production path: JavaFX hands us window coordinates, the forwarder finds the
        // element underneath and dispatches into the DOM. A synthetic dispatch alone would not
        // catch a broken elementFromPoint mapping.
        String centre = (String) webView.getEngine().executeScript(
            "(function(){var el=document.querySelector('img.thumb');el.scrollIntoView();"
                + "var r=el.getBoundingClientRect();"
                + "return Math.round(r.left+r.width/2)+','+Math.round(r.top+r.height/2);})()");
        String[] xy = centre.split(",");
        SessionJournalViewerPane.forwardContextMenu(
            webView.getEngine(), Double.parseDouble(xy[0]), Double.parseDouble(xy[1]));
        String forwarded = (String) webView.getEngine().executeScript(
            "(function(){var menu=document.getElementById('ctxMenu');"
                + "if(!menu.classList.contains('open')){return 'closed';}"
                + "var ids=[];menu.querySelectorAll('button.available')"
                + ".forEach(function(b){ids.push(b.id);});return 'open:'+ids.join(',');})()");
        System.out.println("menu via JavaFX forwarding at " + centre + ": " + forwarded);
        if (!forwarded.contains("ctxAnnotate") || !forwarded.contains("ctxSaveImage")) {
            throw new IllegalStateException("the forwarded right-click did not open the image menu");
        }
        webView.getEngine().executeScript("document.getElementById('ctxMenu').classList.remove('open');");

        String menuOnImage = openMenuOn(webView, "img.thumb");
        System.out.println("menu on screenshot: " + menuOnImage);
        if (!menuOnImage.startsWith("open:")) {
            throw new IllegalStateException("right-click on the screenshot opened no menu");
        }
        for (String required : List.of("ctxAnnotate", "ctxSaveImage", "ctxScreenshot")) {
            if (!menuOnImage.contains(required)) {
                throw new IllegalStateException(required + " missing from the screenshot menu");
            }
        }

        // The two actions must reach Java through the real marshalling layer.
        webView.getEngine().executeScript("document.getElementById('ctxAnnotate').click();");
        openMenuOn(webView, "img.thumb");
        webView.getEngine().executeScript("document.getElementById('ctxSaveImage').click();");
        System.out.println("bridge calls: " + bridge.calls);
        if (bridge.calls.stream().noneMatch(call -> call.startsWith("requestAnnotate:"))
            || bridge.calls.stream().noneMatch(call -> call.startsWith("requestSaveImage:"))) {
            throw new IllegalStateException("the menu actions did not reach the Java bridge");
        }

        String menuOnTitle = openMenuOn(webView, ".head-main h1");
        System.out.println("menu on title: " + menuOnTitle);
        if (!menuOnTitle.contains("ctxRename")) {
            throw new IllegalStateException("ctxRename missing from the title menu");
        }
        webView.getEngine().executeScript("document.getElementById('ctxRename').click();");
        webView.getEngine().executeScript(
            "var t=document.querySelector('.head-main h1');"
                + "t.dispatchEvent(new MouseEvent('dblclick',{bubbles:true}));");
        if (bridge.calls.stream().filter(call -> call.equals("requestRename")).count() < 2) {
            throw new IllegalStateException("rename did not reach the bridge from both paths");
        }

        // Live tail: append streams into the open tail, duplicate seqs are ignored.
        String tail = (String) webView.getEngine().executeScript(
            "(function(){"
                + "korttyOpenLiveTail();"
                + "korttyAppendLog([{s:999901,t:'12:00:00',k:'o',x:'live line one'},"
                + "{s:999902,t:'12:00:01',k:'i',x:'live input'}]);"
                + "korttyAppendLog([{s:999902,t:'12:00:01',k:'i',x:'live input'},"
                + "{s:999903,t:'12:00:02',k:'n',x:'live note'}]);"
                + "var text=document.getElementById('logBody').textContent;"
                + "var panelOpen=document.getElementById('logPanel').classList.contains('open');"
                + "var inputs=(text.match(/live input/g)||[]).length;"
                + "return 'open='+panelOpen+',one='+(text.indexOf('live line one')>=0)"
                + "+',note='+(text.indexOf('live note')>=0)+',inputs='+inputs;})()");
        System.out.println("live tail: " + tail);
        if (!tail.equals("open=true,one=true,note=true,inputs=1")) {
            throw new IllegalStateException("live tail append/dedup misbehaved: " + tail);
        }
        webView.getEngine().executeScript("korttyCloseLiveTail();");
        String closed = (String) webView.getEngine().executeScript(
            "document.getElementById('logPanel').classList.contains('open') ? 'open' : 'closed'");
        if (!"closed".equals(closed)) {
            throw new IllegalStateException("korttyCloseLiveTail left the panel open");
        }
        // Open/close must have reached the bridge so the host toggle can stay in sync.
        if (!bridge.calls.contains("liveTailStateChanged:true")
            || !bridge.calls.contains("liveTailStateChanged:false")) {
            throw new IllegalStateException("live tail state changes did not reach the bridge: "
                + bridge.calls);
        }
        // Height hook: set from Java, read back from the CSS variable; the resize grip exists.
        String height = (String) webView.getEngine().executeScript(
            "(function(){korttySetLiveTailHeight(33);"
                + "var v=document.documentElement.style.getPropertyValue('--kortty-tail-h');"
                + "var grip=document.getElementById('logResize');"
                + "return v+','+(grip?'grip':'no-grip');})()");
        if (!"33vh,grip".equals(height)) {
            throw new IllegalStateException("live tail height hook misbehaved: " + height);
        }
    }

    /** Fires a contextmenu event on the selector and reports the menu state. */
    private static String openMenuOn(WebView webView, String selector) {
        return (String) webView.getEngine().executeScript(
            "(function(){var el=document.querySelector('" + selector + "');"
                + "if(!el){return 'no-element';}"
                + "el.dispatchEvent(new MouseEvent('contextmenu',"
                + "{bubbles:true,cancelable:true,clientX:60,clientY:60}));"
                + "var menu=document.getElementById('ctxMenu');"
                + "if(!menu.classList.contains('open')){return 'closed';}"
                + "var ids=[];menu.querySelectorAll('button.available')"
                + ".forEach(function(b){ids.push(b.id);});"
                + "return 'open:'+ids.join(',');})()");
    }

    private static String stack(Throwable t) {
        java.io.StringWriter writer = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
