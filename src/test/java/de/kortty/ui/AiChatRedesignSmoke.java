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
 * Run via the {@code aiChatRedesignSmoke} Gradle task. Exit 0 = OK.
 */
public final class AiChatRedesignSmoke {

    private static final int FONT = 13;
    private static final String ASSISTANT_MD =
        "Eine pipeline läuft jedes Element unabhängig durch alle Stufen — kein Barrier dazwischen. "
            + "Ein Minimalbeispiel:\n\n"
            + "```javascript\nconst out = await pipeline(items,\n"
            + "  d => agent(d.prompt, {schema: S}),\n"
            + "  r => verify(r));\n```\n\n"
            + "Spring danach mit ↑/↓ durch die Treffer der Chat-Suche.";
    private static final String USER_MD =
        "Und wie finde ich die langsamste Stufe im ganzen Verlauf wieder?";
    private static final String ASSISTANT_MD_2 =
        "Öffne die Suche oben rechts (Cmd/Strg+F), tippe „pipeline\" und jede Fundstelle wird "
            + "hervorgehoben und ins Bild gescrollt.";

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

        messagesBox.getChildren().add(assistantBlock(ASSISTANT_MD, false));
        messagesBox.getChildren().add(userRow(true));
        messagesBox.getChildren().add(assistantBlock(ASSISTANT_MD_2, false));

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

    /** Full-width assistant turn; {@code highlight} marks it as the current search hit. */
    private static VBox assistantBlock(String markdown, boolean highlight) {
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
        return block;
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
