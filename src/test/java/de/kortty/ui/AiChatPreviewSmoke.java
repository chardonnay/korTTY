package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Exercises the production preview and scroll controller on the JavaFX application thread. */
public final class AiChatPreviewSmoke {
    private static VBox messages;
    private static ScrollPane scroll;
    private static ChatAutoScrollSupport follow;
    private static Scene scene;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        try {
            fx(() -> {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                verifyMarkdown();
                messages = new VBox(12);
                scroll = new ScrollPane(messages);
                scroll.setFitToWidth(true);
                follow = new ChatAutoScrollSupport(scroll, messages);
                scene = new Scene(scroll, 700, 300);
                appendLongMessage();
            });
            drain();
            fx(() -> {
                require(atBottom(), "Initial conversation must follow to the end");
                messages.getChildren().add(ChatMarkdownView.plainText("A new user reply", 14));
                layout();
            });
            drain();
            fx(() -> {
                require(atBottom(), "User reply must be visible");
                appendLongMessage();
            });
            drain();
            fx(() -> {
                require(atBottom(), "Assistant reply must be visible after layout");
                messages.fireEvent(wheel(180));
            });
            drain();
            fx(() -> {
                require(!follow.isFollowing() && !atBottom(), "Wheel up must pause following: value=" + scroll.getVvalue() + ", content=" + messages.getHeight() + ", viewport=" + scroll.getViewportBounds().getHeight());
                appendLongMessage();
            });
            drain();
            fx(() -> {
                require(!follow.isFollowing() && !atBottom(), "New output must respect the paused reader");
                messages.fireEvent(wheel(-100000));
            });
            drain();
            fx(() -> {
                require(follow.isFollowing() && atBottom(), "Wheel down to the end must resume");
                // A delayed renderer or a width change can grow an existing message after append.
                messages.getChildren().set(0, ChatMarkdownView.markdown("More wrapped text. ".repeat(600), 14));
                layout();
            });
            drain();
            fx(() -> {
                require(atBottom(), "Delayed layout growth must keep following");
                ScrollBar bar = verticalBar();
                bar.fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                scroll.setVvalue(0.3);
                bar.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
                require(!follow.isFollowing(), "Dragging scrollbar away must pause");
                appendLongMessage();
            });
            drain();
            fx(() -> {
                require(!atBottom(), "Scrollbar reader position must survive append");
                ScrollBar bar = verticalBar();
                bar.fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                scroll.setVvalue(scroll.getVmax());
                bar.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
                require(follow.isFollowing(), "Dragging to bottom must resume");
                // Queue a follow, then scroll up before it runs: the queued work must not win.
                appendLongMessage();
                messages.fireEvent(wheel(240));
            });
            drain();
            fx(() -> require(!follow.isFollowing() && !atBottom(), "Manual scroll must cancel a queued follow"));
            fx(() -> scroll.fireEvent(key(KeyCode.END)));
            drain();
            fx(() -> {
                require(follow.isFollowing() && atBottom(), "End key must resume following");
                scroll.fireEvent(key(KeyCode.PAGE_UP));
            });
            drain();
            fx(() -> require(!follow.isFollowing() && !atBottom(), "Page Up must pause following"));
            System.out.println("aiChatPreviewSmoke OK: Markdown, wrapping, wheel, scrollbar, delayed layout, queued-scroll cancellation");
        } finally {
            Platform.exit();
        }
    }

    private static void verifyMarkdown() {
        ChatMarkdownView preview = ChatMarkdownView.markdown(
            "### A heading\n\n**Bold** and *italic* with `echo $USER` and ~~removed~~.\n\n"
                + "- First\n- Second\n\n> A quotation\n\n<script>alert('x')</script>", 14);
        require(preview.displayedText().contains("A heading") && !preview.displayedText().contains("###"), "Heading markup must disappear");
        require(preview.displayedText().contains("• First") && !preview.displayedText().contains("**Bold**"), "Lists and emphasis must render");
        require(text(preview, "A heading").getFont().getSize() > 14, "Heading must be larger");
        require(text(preview, "Bold").getFont().getStyle().toLowerCase().contains("bold"), "Strong emphasis must be bold");
        require(text(preview, "italic").getFont().getStyle().toLowerCase().contains("italic"), "Emphasis must be italic");
        require(text(preview, "removed").isStrikethrough(), "Strikethrough must render");
        require(text(preview, "echo $USER").getStyleClass().contains("ai-chat-inline-code"), "Inline code must be highlighted");
        require(preview.displayedText().contains("<script>alert('x')</script>"), "HTML must remain inert text");
        require(ChatMarkdownView.plainText("**user text**", 14).displayedText().equals("**user text**"), "User text must remain literal");
        VBox structured = new VBox();
        AiChatRenderSupport.renderInto(structured, true, "## Result\n\n| Host | State |\n| --- | --- |\n| demo | OK |\n\n```sh\necho ok\n```", 14);
        require(structured.getChildren().stream().anyMatch(javafx.scene.layout.GridPane.class::isInstance), "Tables must retain their grid");
        require(structured.getChildren().stream().anyMatch(javafx.scene.control.TextArea.class::isInstance), "Code blocks must remain source");
        ChatMarkdownView longText = ChatMarkdownView.markdown("Long readable answer. ".repeat(500), 14);
        longText.resize(250, longText.prefHeight(250));
        require(longText.prefHeight(250) > longText.prefHeight(650), "Preview must wrap at the available width");
        require(longText.prefHeight(250) > 1000, "Long answers must not be clipped to a fixed height");
        preview.resize(600, preview.prefHeight(600));
        preview.layout();
        preview.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false,
            !System.getProperty("os.name").toLowerCase().contains("mac"), false,
            System.getProperty("os.name").toLowerCase().contains("mac")));
        require(!((javafx.scene.shape.Path) preview.getChildren().getFirst()).getElements().isEmpty(),
            "Formatted text must remain selectable");
    }

    private static Text text(ChatMarkdownView view, String value) {
        return view.getChildren().stream().filter(Text.class::isInstance).map(Text.class::cast)
            .filter(t -> t.getText().equals(value)).findFirst().orElseThrow();
    }

    private static void appendLongMessage() {
        messages.getChildren().add(ChatMarkdownView.markdown("### Response\n\n" + "A readable paragraph. ".repeat(100), 14));
        layout();
    }

    private static void layout() {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static boolean atBottom() {
        return Math.abs(scroll.getVvalue() - scroll.getVmax()) < 0.0001;
    }

    private static ScrollBar verticalBar() {
        return scroll.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance).map(ScrollBar.class::cast)
            .filter(bar -> bar.getOrientation() == javafx.geometry.Orientation.VERTICAL).findFirst().orElseThrow();
    }

    private static ScrollEvent wheel(double delta) {
        return new ScrollEvent(ScrollEvent.SCROLL, 100, 100, 100, 100, false, false, false, false,
            false, false, 0, delta, 0, delta, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
            ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, 1, false, false, false, false,
            type == MouseEvent.MOUSE_PRESSED, false, false, false, false, false, null);
    }

    private static void drain() throws Exception {
        fx(AiChatPreviewSmoke::layout);
        fx(() -> { });
        fx(() -> { });
    }

    private static void fx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
