package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AiChatTerminalActions;
import de.kortty.model.ChatColorProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.policy.AgentExecutionMode;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Offline generator for the manual's screenshot of an AI chat answer whose code block offers Insert and Run.
 *
 * <p>Renders a demo question and answer with the chat's production stylesheet (the default, theme-following
 * chat colour profile, which falls back to its dark palette without an app) and builds the code block's header
 * like {@code AiResultTab.createPlainCodeBlock}: the language, the real {@link AiCodeBlockTerminalButtons} bound
 * to a demo pane {@code demo-web-01} that is connected and at a prompt, Save as snippet and copy. The code itself
 * is shown in the plain text area the chat renderer uses, because the Monaco editor of the real tab is a WebView
 * that a snapshot cannot capture synchronously. No terminal, connection or AI service is involved.</p>
 *
 * <p>Writes {@code app-docs/screenshots/ai/chat-code-block-actions.png} at 2x. Run via the
 * {@code generateAiChatCodeBlockScreenshot} Gradle task. Exit 0 = OK.</p>
 */
public final class AiChatCodeBlockScreenshotGenerator {

    private static final String OUTPUT_FILE = "app-docs/screenshots/ai/chat-code-block-actions.png";
    private static final int FONT = 13;
    private static final double WIDTH = 760;
    private static final String DEMO_PANE = "demo-web-01";
    private static final String QUESTION = "The disk on demo-web-01 is almost full. How do I see what takes the space?";
    private static final String ANSWER_INTRO =
        "Start with the largest directories below `/var`, the usual place for growing logs and caches:";
    private static final String CODE = "sudo du -xh /var --max-depth=2 | sort -rh | head -n 15\n";
    private static final String ANSWER_OUTRO =
        "**Insert** puts the command at the prompt so you can change the path first; **Run** asks before it runs it.";

    private AiChatCodeBlockScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                writeScreenshot();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + failure.get());
            System.exit(1);
        }
        System.exit(0);
    }

    private static void writeScreenshot() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);

        ChatColorProfile profile = ChatColorProfileSupport.byId(null);
        ThemeCssSupport.ChatPalette palette = ChatColorProfileSupport.resolvePalette(profile, null);

        VBox messages = new VBox(16);
        messages.getStyleClass().add("ai-chat-messages");
        messages.setFillWidth(true);
        messages.setPadding(new Insets(14, 16, 14, 16));
        messages.getChildren().addAll(userRow(), assistantBlock());

        Scene scene = new Scene(messages, WIDTH, 400);
        scene.setFill(Color.web(palette.background()));
        String stylesheet = ThemeCssSupport.getChatStylesheetUrl(palette);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        new Stage().setScene(scene);

        messages.applyCss();
        messages.resize(WIDTH, 1000);
        messages.layout();
        double height = Math.ceil(messages.prefHeight(WIDTH));
        messages.resize(WIDTH, height);
        messages.layout();

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web(palette.background()));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = messages.snapshot(params, null);

        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File outFile = new File(OUTPUT_FILE);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
        }
        ImageIO.write(buffered, "png", outFile);
        System.out.println("Generated " + outFile.getAbsolutePath()
            + " (" + buffered.getWidth() + "x" + buffered.getHeight() + ")");
    }

    private static HBox userRow() {
        VBox bubble = new VBox(4);
        bubble.setFillWidth(true);
        bubble.getStyleClass().add("ai-chat-user-bubble");
        bubble.setMaxWidth(520);
        Label role = new Label(I18n.get("ai.result.user"));
        role.getStyleClass().add("ai-chat-role");
        role.setStyle("-fx-font-weight: bold;");
        role.setMaxWidth(Double.MAX_VALUE);
        role.setAlignment(Pos.CENTER_RIGHT);
        bubble.getChildren().add(role);
        AiChatRenderSupport.renderInto(bubble, false, QUESTION, FONT);
        HBox row = new HBox(bubble);
        row.getStyleClass().add("ai-chat-user-row");
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private static VBox assistantBlock() throws Exception {
        VBox block = new VBox(6);
        block.setFillWidth(true);
        block.getStyleClass().add("ai-chat-assistant");
        Label role = new Label(I18n.get("ai.result.assistantWithProfile", "Demo profile"));
        role.getStyleClass().addAll("ai-chat-role", "ai-chat-role-assistant");
        role.setStyle("-fx-font-weight: bold;");
        block.getChildren().add(role);
        AiChatRenderSupport.renderInto(block, true, ANSWER_INTRO, FONT);
        block.getChildren().add(codeBlock());
        AiChatRenderSupport.renderInto(block, true, ANSWER_OUTRO, FONT);
        return block;
    }

    /** The header of {@code AiResultTab.createPlainCodeBlock} over the chat renderer's code area. */
    private static VBox codeBlock() throws Exception {
        Label language = new Label("bash");
        language.getStyleClass().add("ai-chat-code-lang");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, language, spacer);

        AiCodeBlockTerminalButtons buttons = AiCodeBlockTerminalButtons.create("bash", CODE, demoHost());
        if (buttons == null || buttons.insertButton() == null || buttons.runButton() == null
                || buttons.insertButton().isDisabled() || buttons.runButton().isDisabled()) {
            throw new IllegalStateException("the demo block must offer enabled Insert and Run buttons");
        }
        header.getChildren().addAll(buttons.nodes());

        Button save = new Button(I18n.get("ai.result.saveSnippet"));
        save.getStyleClass().add("ai-chat-icon-button");
        save.setStyle("-fx-padding: 3 10 3 10;");
        Button copy = new Button("⧉");
        copy.getStyleClass().add("ai-chat-icon-button");
        copy.setStyle("-fx-padding: 3 8 3 8;");
        header.getChildren().addAll(save, copy);
        header.setAlignment(Pos.CENTER_LEFT);

        TextArea area = new TextArea(CODE.stripTrailing());
        area.getStyleClass().add("ai-chat-code-area");
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle(String.format("-fx-font-family: 'monospace'; -fx-font-size: %dpx;", FONT));
        area.setPrefRowCount(2);

        VBox codeBox = new VBox(6, header, area);
        codeBox.getStyleClass().add("ai-chat-code");
        return codeBox;
    }

    /** A connected demo pane at a shell prompt, with every action allowed; nothing is ever sent. */
    private static AiCodeBlockTerminalButtons.Host demoHost() throws Exception {
        TerminalPaneRef demoPane = demoPane();
        AiCodeBlockTerminalAction.PaneState ready = new AiCodeBlockTerminalAction.PaneState(true, false, false, null,
            false, false, ShellIntegrationController.PromptState.AT_PROMPT);
        return new AiCodeBlockTerminalButtons.Host() {
            @Override
            public AiChatTerminalActions setting() {
                return AiChatTerminalActions.INSERT_AND_RUN;
            }

            @Override
            public AiCodeBlockTerminalAction.Policy policy() {
                return new AiCodeBlockTerminalAction.Policy(true, AgentExecutionMode.ALLOW);
            }

            @Override
            public TerminalPaneRef target() {
                return demoPane;
            }

            @Override
            public AiCodeBlockTerminalAction.PaneState probe(TerminalPaneRef target) {
                return ready;
            }

            @Override
            public void insert(TerminalPaneRef target, String text,
                               java.util.function.Consumer<de.kortty.paste.PasteGuard.Outcome> outcome) {
                throw new IllegalStateException("the screenshot never inserts");
            }

            @Override
            public boolean confirmRun(TerminalPaneRef target, String line, AiCodeBlockTerminalAction.Decision decision) {
                return false;
            }

            @Override
            public boolean sendLine(TerminalPaneRef target, String line) {
                throw new IllegalStateException("the screenshot never sends");
            }

            @Override
            public void status(String message) {
            }
        };
    }

    /** A pane reference that only carries its name: no tab or terminal exists behind it. */
    private static TerminalPaneRef demoPane() throws Exception {
        Constructor<TerminalPaneRef> constructor = TerminalPaneRef.class.getDeclaredConstructor(
            TerminalTab.class, KorttyTermWidget.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, DEMO_PANE);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
