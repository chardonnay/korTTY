package de.kortty.ui;

import de.kortty.core.AiResponseSanitizer;
import de.kortty.core.AiStreamListener;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The live preview of a streamed answer in the AI chat: a transient assistant block at the end of
 * the message list that shows the answer as plain text while it arrives, with the reasoning in a
 * collapsed disclosure like the finished message has.
 *
 * <p>The preview is plain text on purpose. Markdown, code blocks and their Insert/Run buttons are
 * rendered only for the final answer, which replaces the preview when the request ends, so a
 * terminal action can never be offered for a half-received block. The preview is not part of the
 * transcript: it is not saved, searched or copied.</p>
 *
 * <p>One request at a time: {@link #begin()} returns the listener for a new request (dropping any
 * earlier preview), {@link #end()} removes the preview when the request succeeded, failed or was
 * cancelled. A service that does not stream never calls the listener, so nothing appears and the
 * chat keeps its waiting status. All methods except the returned listener run on the FX thread.</p>
 */
final class AiChatStreamingView {

    /** The preview shows at most the last this many characters of the answer and of the reasoning. */
    static final int MAX_PREVIEW_CHARS = 32_000;

    private static final String ELLIPSIS_PREFIX = "…\n";

    private final Pane container;
    private final IntSupplier fontSize;
    private final Supplier<String> roleText;
    private final Runnable onFirstSnapshot;
    private final AiStreamCoalescer.Scheduler scheduler;

    private AiStreamCoalescer active;
    private VBox block;
    private Label contentLabel;
    private Button reasoningToggle;
    private VBox reasoningBox;
    private Label reasoningLabel;
    private boolean reasoningExpanded;
    private boolean announced;
    private String lastContent;
    private String lastReasoning;

    AiChatStreamingView(Pane container, IntSupplier fontSize, Supplier<String> roleText, Runnable onFirstSnapshot) {
        this(container, fontSize, roleText, onFirstSnapshot, AiChatStreamingView::scheduleOnFx);
    }

    AiChatStreamingView(
        Pane container,
        IntSupplier fontSize,
        Supplier<String> roleText,
        Runnable onFirstSnapshot,
        AiStreamCoalescer.Scheduler scheduler) {
        this.container = Objects.requireNonNull(container, "container");
        this.fontSize = Objects.requireNonNull(fontSize, "fontSize");
        this.roleText = Objects.requireNonNull(roleText, "roleText");
        this.onFirstSnapshot = onFirstSnapshot != null ? onFirstSnapshot : () -> { };
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Starts the preview of a new request and returns the listener to put on its {@code AiRequest}. */
    AiStreamListener begin() {
        end();
        AiStreamCoalescer[] self = new AiStreamCoalescer[1];
        self[0] = new AiStreamCoalescer(new AiStreamCoalescer.Sink() {
            @Override
            public void show(String content, String reasoning) {
                if (active == self[0]) {
                    showSnapshot(content, reasoning);
                }
            }

            @Override
            public void clear() {
                if (active == self[0]) {
                    clearPreview();
                }
            }
        }, scheduler, System::currentTimeMillis, AiStreamCoalescer.DEFAULT_INTERVAL_MILLIS);
        active = self[0];
        return active;
    }

    /** Ends the current preview: later snapshots are ignored and the preview block is removed. Idempotent. */
    void end() {
        if (active != null) {
            active.close();
            active = null;
        }
        clearPreview();
        announced = false;
    }

    /** True while a request is being previewed (between {@link #begin()} and {@link #end()}). */
    boolean isActive() {
        return active != null;
    }

    /** The preview block while it is shown, else {@code null}. */
    Node node() {
        return block != null && container.getChildren().contains(block) ? block : null;
    }

    /**
     * Puts the preview back after the container was rebuilt (a font change re-renders every message
     * and clears the list), with the current font size.
     */
    void reattach() {
        if (active == null || lastContent == null) {
            return;
        }
        String content = lastContent;
        String reasoning = lastReasoning;
        removeBlock();
        showSnapshot(content, reasoning);
    }

    private void showSnapshot(String rawContent, String rawReasoning) {
        String content = previewText(AiResponseSanitizer.sanitizeForDisplay(rawContent));
        String reasoning = previewText(rawReasoning != null ? rawReasoning.strip() : "");
        if (block == null && content.isEmpty() && reasoning.isEmpty()) {
            return;
        }
        lastContent = rawContent;
        lastReasoning = rawReasoning;
        if (block == null || !container.getChildren().contains(block)) {
            buildBlock();
        }
        contentLabel.setText(content);
        contentLabel.setVisible(!content.isEmpty());
        contentLabel.setManaged(!content.isEmpty());
        boolean hasReasoning = !reasoning.isEmpty();
        reasoningLabel.setText(reasoning);
        reasoningToggle.setVisible(hasReasoning);
        reasoningToggle.setManaged(hasReasoning);
        reasoningBox.setVisible(hasReasoning && reasoningExpanded);
        reasoningBox.setManaged(hasReasoning && reasoningExpanded);
        if (!announced) {
            announced = true;
            onFirstSnapshot.run();
        }
    }

    private void clearPreview() {
        removeBlock();
        lastContent = null;
        lastReasoning = null;
    }

    private void removeBlock() {
        if (block != null) {
            container.getChildren().remove(block);
        }
        block = null;
        contentLabel = null;
        reasoningToggle = null;
        reasoningBox = null;
        reasoningLabel = null;
    }

    private void buildBlock() {
        int size = fontSize.getAsInt();
        block = new VBox(6);
        block.setFillWidth(true);
        block.getStyleClass().addAll("ai-chat-assistant", "ai-chat-streaming");

        Label role = new Label(roleText.get());
        role.getStyleClass().addAll("ai-chat-role", "ai-chat-role-assistant");
        role.setStyle("-fx-font-weight: bold;");
        role.setFont(Font.font(size));

        contentLabel = wrappingLabel(size);
        contentLabel.getStyleClass().add("ai-chat-streaming-text");

        reasoningLabel = wrappingLabel(size);
        reasoningBox = new VBox(reasoningLabel);
        reasoningBox.getStyleClass().add("ai-chat-reasoning");
        reasoningToggle = new Button(toggleText(reasoningExpanded));
        reasoningToggle.getStyleClass().add("ai-chat-reasoning-toggle");
        reasoningToggle.setFont(Font.font(Math.max(10, size - 1)));
        reasoningToggle.setFocusTraversable(false);
        reasoningToggle.setOnAction(event -> {
            reasoningExpanded = !reasoningExpanded;
            reasoningToggle.setText(toggleText(reasoningExpanded));
            reasoningBox.setVisible(reasoningExpanded);
            reasoningBox.setManaged(reasoningExpanded);
        });

        block.getChildren().addAll(role, contentLabel, reasoningToggle, reasoningBox);
        container.getChildren().add(block);
    }

    private static Label wrappingLabel(int size) {
        Label label = new Label();
        label.getStyleClass().add("ai-chat-text");
        label.setWrapText(true);
        label.setFont(Font.font(size));
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static String toggleText(boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + I18n.get("ai.result.reasoning.label");
    }

    /** Keeps the tail of a long text so the preview stays cheap to lay out 20 times a second. */
    static String previewText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= MAX_PREVIEW_CHARS) {
            return text;
        }
        int start = text.length() - MAX_PREVIEW_CHARS;
        int lineBreak = text.indexOf('\n', start);
        if (lineBreak >= 0 && lineBreak < text.length() - 1) {
            start = lineBreak + 1;
        }
        return ELLIPSIS_PREFIX + text.substring(start);
    }

    private static void scheduleOnFx(Runnable drain, long delayMillis) {
        if (delayMillis <= 0L) {
            Platform.runLater(drain);
        } else {
            DelayHolder.EXECUTOR.schedule(() -> Platform.runLater(drain), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** One daemon thread for the short drain delays of every chat tab. */
    private static final class DelayHolder {
        static final ScheduledExecutorService EXECUTOR = createExecutor();

        private static ScheduledExecutorService createExecutor() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "ai-chat-stream-delay");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }
    }
}
