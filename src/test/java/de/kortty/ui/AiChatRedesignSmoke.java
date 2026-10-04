package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.ChatColorProfile;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless smoke harness for the redesigned AI chat: for every built-in color profile it builds a
 * representative conversation with the real production pieces — {@link ThemeCssSupport#getChatStylesheetUrl}
 * for the palette, the {@code ai-chat-*} style classes, and {@link AiChatRenderSupport#renderInto} for the
 * message content — then snapshots it to {@code build/smoke/ai-chat-<profile>.png}. The stage is never shown.
 * It also builds the Insert and Run buttons of a code block ({@link AiCodeBlockTerminalButtons}) against stub
 * policies and checks which buttons render and which are greyed out with which reason, writing
 * {@code build/smoke/ai-chat-code-block-buttons.png}.
 * Finally it streams a stubbed answer through {@link AiChatStreamingView} and checks that the live preview is plain
 * text without Insert/Run buttons, that the final answer replaces it exactly once and that a late drain cannot bring
 * the preview back ({@code build/smoke/ai-chat-streaming.png}).
 * Run via the {@code aiChatRedesignSmoke} Gradle task. Exit 0 = OK.
 */
public final class AiChatRedesignSmoke {

    private static final int FONT = 13;
    private static final String ASSISTANT_MD =
        "### Pipeline mit Vorschau\n\nEine **pipeline** läuft jedes Element *unabhängig* durch alle Stufen — kein Barrier dazwischen. "
            + "Ein Minimalbeispiel:\n\n"
            + "```javascript\nconst out = await pipeline(items,\n"
            + "  d => agent(d.prompt, {schema: S}),\n"
            + "  r => verify(r));\n```\n\n"
            + "Spring danach mit ↑/↓ durch die Treffer der Chat-Suche.";
    private static final String USER_MD =
        "Und wie finde ich die langsamste Stufe im ganzen Verlauf wieder?";
    private static final String ASSISTANT_MD_2 =
        "### Die nächste Stufe\n\nÖffne die **Suche** oben rechts (Cmd/Strg+F), tippe `pipeline` und jede Fundstelle wird "
            + "hervorgehoben und ins Bild gescrollt.\n\n- **Prüfen:** Ergebnis lesen\n- *Fortsetzen:* nächste Stufe starten";
    private static final String REASONING_MD =
        "Der Nutzer fragt nach der Pipeline-Semantik. Ich erkläre zuerst das Fehlen des Barriers "
            + "und zeige dann ein knappes Codebeispiel, das genau eine Stufe pro Element durchläuft.";

    private AiChatRedesignSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                for (ChatColorProfile profile : ChatColorProfileSupport.all()) {
                    renderProfile(profile);
                }
                checkCodeBlockTerminalButtons();
                checkStreamingPreview();
            } catch (Exception e) {
                failure.compareAndSet(null, "Setup failed: " + e);
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("aiChatRedesignSmoke OK");
    }

    private static void renderProfile(ChatColorProfile profile) throws Exception {
        // Explicit profiles ignore the app; the theme-following profile falls back to the dark palette
        // when no app/theme is available (which is the case in this headless harness).
        ThemeCssSupport.ChatPalette palette = ChatColorProfileSupport.resolvePalette(profile, null);

        VBox messagesBox = new VBox(16);
        messagesBox.getStyleClass().add("ai-chat-messages");
        messagesBox.setFillWidth(true);
        messagesBox.setPadding(new Insets(14, 16, 14, 16));

        messagesBox.getChildren().add(assistantBlock(ASSISTANT_MD, false, REASONING_MD));
        messagesBox.getChildren().add(userRow(true));
        VBox researched = assistantBlock(ASSISTANT_MD_2, false, null);
        appendWebActivityDisclosure(researched);
        messagesBox.getChildren().add(researched);

        ScrollPane scroll = new ScrollPane(messagesBox);
        scroll.getStyleClass().add("ai-chat-scroll");
        scroll.setFitToWidth(true);

        HBox search = searchBar();

        VBox root = new VBox(search, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Scene scene = new Scene(root, 900, 620);
        scene.setFill(Color.web(palette.background()));
        String stylesheet = ThemeCssSupport.getChatStylesheetUrl(palette);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        Stage stage = new Stage();
        stage.setScene(scene);

        snapshot(scene, "ai-chat-" + profile.id() + ".png");
    }

    /**
     * The Insert and Run buttons of a code block: which render for which block and setting, and which are greyed
     * out with which tooltip under an allowing, a {@code READ_ONLY} and a no-AI-chat policy stub. No terminal is
     * open, so an allowed action is greyed out for having no target; the policy reason comes first.
     */
    private static void checkCodeBlockTerminalButtons() throws Exception {
        AiCodeBlockTerminalAction.Policy allow =
            new AiCodeBlockTerminalAction.Policy(true, de.kortty.policy.AgentExecutionMode.ALLOW);
        AiCodeBlockTerminalAction.Policy readOnly =
            new AiCodeBlockTerminalAction.Policy(true, de.kortty.policy.AgentExecutionMode.READ_ONLY);
        AiCodeBlockTerminalAction.Policy noChat =
            new AiCodeBlockTerminalAction.Policy(false, de.kortty.policy.AgentExecutionMode.ALLOW);
        String noTarget = I18n.get("ai.result.terminal.verdict.noTarget", "");
        String denied = I18n.get("ai.result.terminal.verdict.policyDenied", "");

        AiCodeBlockTerminalButtons allowed = buttons("bash", "uptime\n",
            de.kortty.model.AiChatTerminalActions.INSERT_AND_RUN, allow);
        require(allowed != null && allowed.insertButton() != null && allowed.runButton() != null,
            "a shell block renders Insert and Run");
        require(allowed.insertButton().isDisabled() && tooltip(allowed, true).equals(noTarget),
            "without an open pane Insert is greyed out with the no-target reason");
        require(allowed.runButton().isDisabled() && tooltip(allowed, false).equals(noTarget),
            "without an open pane Run is greyed out with the no-target reason");

        AiCodeBlockTerminalButtons underReadOnly = buttons("bash", "uptime",
            de.kortty.model.AiChatTerminalActions.INSERT_AND_RUN, readOnly);
        require(underReadOnly != null && underReadOnly.runButton() != null && underReadOnly.runButton().isDisabled(),
            "READ_ONLY greys Run out up front");
        require(tooltip(underReadOnly, false).equals(denied), "READ_ONLY names the policy on Run");
        require(tooltip(underReadOnly, true).equals(noTarget), "READ_ONLY leaves Insert to the pane checks");

        AiCodeBlockTerminalButtons withoutChat = buttons("bash", "uptime",
            de.kortty.model.AiChatTerminalActions.INSERT_AND_RUN, noChat);
        require(withoutChat != null && withoutChat.insertButton().isDisabled() && withoutChat.runButton().isDisabled()
            && tooltip(withoutChat, true).equals(denied) && tooltip(withoutChat, false).equals(denied),
            "without AI chat both buttons are greyed out with the policy reason");

        AiCodeBlockTerminalButtons python = buttons("python", "print(1)",
            de.kortty.model.AiChatTerminalActions.INSERT_AND_RUN, allow);
        require(python != null && python.insertButton() != null && python.runButton() == null,
            "a python block offers Insert only");
        AiCodeBlockTerminalButtons insertOnly = buttons("bash", "uptime",
            de.kortty.model.AiChatTerminalActions.INSERT_ONLY, allow);
        require(insertOnly != null && insertOnly.runButton() == null, "the Insert-only setting hides Run");
        require(buttons("bash", "uptime", de.kortty.model.AiChatTerminalActions.OFF, allow) == null,
            "the Off setting renders no terminal button");
        AiCodeBlockTerminalButtons hostile = buttons("bash", "echo \u001b[2J",
            de.kortty.model.AiChatTerminalActions.INSERT_AND_RUN, allow);
        require(hostile != null && hostile.runButton() == null, "a block with an escape sequence offers no Run");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label language = new Label("bash");
        language.getStyleClass().add("ai-chat-code-lang");
        HBox header = new HBox(8, language, spacer);
        header.getChildren().addAll(underReadOnly.nodes());
        javafx.scene.control.Button copy = new javafx.scene.control.Button("⧉");
        copy.getStyleClass().add("ai-chat-icon-button");
        header.getChildren().add(copy);
        VBox codeBox = new VBox(6, header, new Label("uptime"));
        codeBox.getStyleClass().add("ai-chat-code");
        codeBox.setPadding(new Insets(10));
        ChatColorProfile profile = ChatColorProfileSupport.all().get(0);
        ThemeCssSupport.ChatPalette palette = ChatColorProfileSupport.resolvePalette(profile, null);
        Scene scene = new Scene(new VBox(codeBox), 520, 110);
        scene.setFill(Color.web(palette.background()));
        String stylesheet = ThemeCssSupport.getChatStylesheetUrl(palette);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        new Stage().setScene(scene);
        snapshot(scene, "ai-chat-code-block-buttons.png");
    }

    private static final String STREAM_FINAL = "Check the load:\n\n```bash\nuptime\n```\n\nThen **compare** it.";

    /**
     * A stubbed streaming service sends growing snapshots (answer with a code fence, plus reasoning) through the real
     * {@link AiChatStreamingView} and {@link AiStreamCoalescer}; drains are queued by a manual scheduler and run here
     * on the FX thread. The preview must be plain text with no code block and no Insert/Run button; ending the request
     * removes it, the final answer is rendered once, and a drain still queued after the end changes nothing.
     */
    private static void checkStreamingPreview() throws Exception {
        VBox messagesBox = new VBox(16);
        messagesBox.getStyleClass().add("ai-chat-messages");
        messagesBox.setFillWidth(true);
        messagesBox.setPadding(new Insets(14, 16, 14, 16));
        java.util.List<Runnable> drains = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger firstSnapshots = new java.util.concurrent.atomic.AtomicInteger();
        AiChatStreamingView view = new AiChatStreamingView(
            messagesBox, () -> FONT, () -> "KI · Stub", firstSnapshots::incrementAndGet, (drain, delay) -> drains.add(drain));

        de.kortty.core.AiService streaming = new de.kortty.core.AiService() {
            @Override
            public de.kortty.core.AiExecutionResult execute(de.kortty.core.AiRequest request) {
                de.kortty.core.AiStreamListener listener = request.streamListener();
                listener.onProgress("", "Thinking about");
                listener.onProgress("Ignore this first try", "Thinking about");
                listener.onRestart();
                StringBuilder answer = new StringBuilder();
                for (char c : STREAM_FINAL.substring(0, STREAM_FINAL.indexOf("```", 20)).toCharArray()) {
                    answer.append(c);
                    listener.onProgress(answer.toString(), "Thinking about the load");
                }
                return null;
            }

            @Override
            public boolean testConnection() {
                return true;
            }
        };
        de.kortty.core.AiRequest request = new de.kortty.core.AiRequest(
            de.kortty.core.AiAction.ASK, "", "stub", "en", "load?").withStreamListener(view.begin());
        Thread worker = new Thread(() -> {
            try {
                streaming.execute(request);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "smoke-stream");
        worker.start();
        worker.join(10_000);
        requireStream(!worker.isAlive(), "the stubbed stream finished");
        requireStream(drains.size() == 1, "one drain is queued for the whole burst of snapshots");
        new java.util.ArrayList<>(drains).forEach(Runnable::run);
        drains.clear();

        javafx.scene.Node preview = view.node();
        requireStream(preview != null && messagesBox.getChildren().size() == 1, "the preview block is shown");
        requireStream(firstSnapshots.get() == 1, "the chat is told once that the answer started arriving");
        String shown = ((Label) preview.lookup(".ai-chat-streaming-text")).getText();
        requireStream(shown.contains("```bash") && shown.contains("uptime") && !shown.contains("first try"),
            "the preview shows the latest snapshot as raw text, the restarted attempt is gone");
        requireStream(preview.lookupAll(".ai-chat-code-area").isEmpty(), "the preview renders no code block");
        String insert = I18n.get("ai.result.terminal.insert");
        String run = I18n.get("ai.result.terminal.run");
        boolean actionButton = preview.lookupAll(".button").stream()
            .map(node -> ((javafx.scene.control.Button) node).getText())
            .anyMatch(text -> text != null && (text.contains(insert) || text.contains(run)));
        requireStream(!actionButton, "no Insert or Run button while the answer streams");

        ScrollPane streamScroll = new ScrollPane(messagesBox);
        streamScroll.getStyleClass().add("ai-chat-scroll");
        streamScroll.setFitToWidth(true);
        Scene scene = new Scene(streamScroll, 700, 260);
        ChatColorProfile profile = ChatColorProfileSupport.all().get(0);
        ThemeCssSupport.ChatPalette palette = ChatColorProfileSupport.resolvePalette(profile, null);
        scene.setFill(Color.web(palette.background()));
        String stylesheet = ThemeCssSupport.getChatStylesheetUrl(palette);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        new Stage().setScene(scene);
        snapshot(scene, "ai-chat-streaming.png");

        // A snapshot that arrives just before the task ends still queues a drain.
        request.streamListener().onProgress(STREAM_FINAL, "Thinking about the load");
        request.streamListener().onComplete();
        // Task succeeded: the tab ends the preview and renders the final answer the normal way.
        view.end();
        VBox finalBlock = assistantBlock(STREAM_FINAL, false, null);
        AiCodeBlockTerminalButtons finalButtons = buttons("bash", "uptime\n",
            de.kortty.model.AiChatTerminalActions.INSERT_AND_RUN,
            new AiCodeBlockTerminalAction.Policy(true, de.kortty.policy.AgentExecutionMode.ALLOW));
        finalBlock.getChildren().add(new HBox(8, finalButtons.nodes().toArray(new javafx.scene.Node[0])));
        messagesBox.getChildren().add(finalBlock);
        new java.util.ArrayList<>(drains).forEach(Runnable::run);

        requireStream(view.node() == null && messagesBox.lookupAll(".ai-chat-streaming").isEmpty(),
            "ending the request removes the preview and a late drain does not bring it back");
        requireStream(messagesBox.lookupAll(".ai-chat-assistant").size() == 1, "the final answer is rendered once");
        requireStream(!finalBlock.lookupAll(".ai-chat-code-area").isEmpty() && finalButtons.insertButton() != null
            && finalButtons.runButton() != null, "the final answer carries the code block with Insert and Run");
    }

    private static void requireStream(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("Streaming preview: " + what);
        }
        System.out.println("ok: " + what);
    }

    private static AiCodeBlockTerminalButtons buttons(String language, String code,
            de.kortty.model.AiChatTerminalActions setting, AiCodeBlockTerminalAction.Policy policy) {
        return AiCodeBlockTerminalButtons.create(language, code, new AiCodeBlockTerminalButtons.Host() {
            @Override
            public de.kortty.model.AiChatTerminalActions setting() {
                return setting;
            }

            @Override
            public AiCodeBlockTerminalAction.Policy policy() {
                return policy;
            }

            @Override
            public TerminalPaneRef target() {
                return null;
            }

            @Override
            public AiCodeBlockTerminalAction.PaneState probe(TerminalPaneRef target) {
                throw new IllegalStateException("no pane to probe");
            }

            @Override
            public void insert(TerminalPaneRef target, String text,
                               java.util.function.Consumer<de.kortty.paste.PasteGuard.Outcome> outcome) {
                throw new IllegalStateException("nothing may be inserted without a target");
            }

            @Override
            public boolean confirmRun(TerminalPaneRef target, String line, AiCodeBlockTerminalAction.Decision decision) {
                throw new IllegalStateException("nothing may be confirmed without a target");
            }

            @Override
            public boolean sendLine(TerminalPaneRef target, String line) {
                throw new IllegalStateException("nothing may be sent without a target");
            }

            @Override
            public void status(String message) {
            }
        });
    }

    private static String tooltip(AiCodeBlockTerminalButtons buttons, boolean insert) {
        String text = insert ? buttons.insertTooltipText() : buttons.runTooltipText();
        return text != null ? text : "";
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("Code block buttons: " + what);
        }
        System.out.println("ok: " + what);
    }

    /** Full-width assistant turn; {@code highlight} marks it as the current search hit. */
    private static VBox assistantBlock(String markdown, boolean highlight, String reasoning) {
        VBox block = new VBox(6);
        block.setFillWidth(true);
        block.getStyleClass().add("ai-chat-assistant");
        if (highlight) {
            block.getStyleClass().add("ai-chat-hit-current");
        }
        Label role = new Label("KI · Claude");
        role.getStyleClass().addAll("ai-chat-role", "ai-chat-role-assistant");
        role.setStyle("-fx-font-weight: bold;");
        block.getChildren().add(role);
        AiChatRenderSupport.renderInto(block, true, markdown, FONT);
        if (reasoning != null && !reasoning.isBlank()) {
            appendReasoningDisclosure(block, reasoning);
        }
        return block;
    }

    /**
     * Mirrors {@code AiResultTab.appendReasoningDisclosure}: a toggle bar plus a collapsible body, both
     * carrying the production {@code ai-chat-reasoning*} style classes so every profile snapshot exercises
     * the separated-reasoning theming. Rendered expanded here so the muted body colour is visible.
     */
    private static void appendReasoningDisclosure(VBox target, String reasoning) {
        Label body = new Label(reasoning.trim());
        body.getStyleClass().add("ai-chat-text");
        body.setWrapText(true);
        VBox bodyBox = new VBox(body);
        bodyBox.getStyleClass().add("ai-chat-reasoning");
        javafx.scene.control.Button toggle = new javafx.scene.control.Button("▾ Gedankengang");
        toggle.getStyleClass().add("ai-chat-reasoning-toggle");
        toggle.setFocusTraversable(false);
        target.getChildren().addAll(toggle, bodyBox);
    }

    /**
     * Mirrors {@code AiResultTab.appendWebActivityDisclosure} with the real summary/detail text from
     * {@link AiWebActivitySupport}, placed between role label and answer and rendered expanded.
     */
    private static void appendWebActivityDisclosure(VBox block) {
        java.util.List<de.kortty.model.SavedAiWebToolCall> calls = AiWebActivitySupport.toSaved(java.util.List.of(
            new de.kortty.core.AiWebToolCall(de.kortty.core.AiWebToolCall.Kind.SEARCH, "web_search",
                "jenkins pipeline retry", true, null,
                java.util.List.of(
                    new de.kortty.core.AiWebToolCall.Source("Pipeline Syntax – Jenkins", "https://www.jenkins.io/doc/book/pipeline/syntax/"),
                    new de.kortty.core.AiWebToolCall.Source("retry step", "https://www.jenkins.io/doc/pipeline/steps/workflow-basic-steps/")),
                0, false),
            new de.kortty.core.AiWebToolCall(de.kortty.core.AiWebToolCall.Kind.EXTRACT, "web_extract",
                "https://www.jenkins.io/doc/book/pipeline/syntax/", true, null,
                java.util.List.of(new de.kortty.core.AiWebToolCall.Source("", "https://www.jenkins.io/doc/book/pipeline/syntax/")),
                12000, true)));
        Label body = new Label(AiWebActivitySupport.detail(calls));
        body.getStyleClass().add("ai-chat-text");
        body.setWrapText(true);
        VBox bodyBox = new VBox(body);
        bodyBox.getStyleClass().addAll("ai-chat-reasoning", "ai-chat-web-activity");
        javafx.scene.control.Button toggle = new javafx.scene.control.Button("▾ " + AiWebActivitySupport.summary(calls));
        toggle.getStyleClass().addAll("ai-chat-reasoning-toggle", "ai-chat-web-activity-toggle");
        toggle.setFocusTraversable(false);
        block.getChildren().addAll(1, java.util.List.of(toggle, bodyBox));
    }

    /** Right-indented user bubble; {@code highlight} outlines just the bubble as the current hit. */
    private static HBox userRow(boolean highlight) {
        VBox bubble = new VBox(4);
        bubble.setFillWidth(true);
        bubble.getStyleClass().add("ai-chat-user-bubble");
        if (highlight) {
            bubble.getStyleClass().add("ai-chat-hit-current");
        }
        bubble.setMaxWidth(620);
        Label role = new Label("Du");
        role.getStyleClass().add("ai-chat-role");
        role.setStyle("-fx-font-weight: bold;");
        role.setMaxWidth(Double.MAX_VALUE);
        role.setAlignment(Pos.CENTER_RIGHT);
        bubble.getChildren().add(role);
        AiChatRenderSupport.renderInto(bubble, false, USER_MD, FONT);

        HBox row = new HBox(bubble);
        row.getStyleClass().add("ai-chat-user-row");
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private static HBox searchBar() {
        TextField field = new TextField("pipeline");
        field.getStyleClass().add("ai-chat-search-field");
        HBox.setHgrow(field, Priority.ALWAYS);
        Label count = new Label("1/2");
        count.getStyleClass().add("ai-chat-search-count");
        HBox bar = new HBox(8, new Label("Suche"), field, count);
        bar.getStyleClass().add("ai-chat-search-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private static void snapshot(Scene scene, String fileName) throws Exception {
        scene.snapshot(null);
        WritableImage image = scene.snapshot(null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File out = new File("build/smoke/" + fileName);
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }
}
