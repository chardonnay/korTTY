package de.kortty.ui;

import com.sithtermfx.core.model.TerminalModelListener;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.split.TerminalSplitPane;
import com.sithtermfx.ui.split.TerminalSplitPane.PaneOverlayLayer;
import de.kortty.core.KorttyClipboard;
import de.kortty.core.QuickSelectLabels;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.ui.QuickSelectScreen.Hit;
import de.kortty.ui.QuickSelectSession.Outcome;
import de.kortty.ui.QuickSelectSession.Target;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.Font;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Quick select for the panes of one terminal tab: on request it marks every URL, path, e-mail
 * address, UUID, IP address, git hash and number of four or more digits the focused pane shows
 * ({@link QuickSelectScreen}) with a box and a label of one or two letters ({@link QuickSelectLabels}).
 * Typing a label copies that text to the clipboard through {@link KorttyClipboard}, so the
 * enterprise policy's internal clipboard mode applies; Shift with the label opens a web or e-mail
 * address, or a file path in a pane that opens files, the way a Cmd/Ctrl+click does
 * ({@link KorttyTermWidget#openLink}), and copies everything else. The keys are decided by
 * {@link QuickSelectSession}.
 *
 * <p>Its key filters sit on the split pane, added before the terminal view's own, so they run before
 * every pane, broadcast mode's mirror and the agent input lock: while quick select runs, every key
 * press, typed character and input-method event is consumed, and {@link QuickSelectInputGuard}
 * swallows what the last key leaves behind, so nothing reaches any shell. The chord that starts it
 * is routed by the main window's scene shortcut router, which swallows that chord's own residue.
 *
 * <p>The boxes and labels go into the pane's {@code LINKS} overlay layer
 * ({@link TerminalSplitPane#paneOverlay}), which is mouse-transparent, placed through scene
 * coordinates like the hover underline. Quick select ends on a key that picks or cancels, a mouse
 * press in the pane, the pane losing the focus (switching tabs or windows included), the pane moving
 * or changing size, scrolling, a font size change, and the pane leaving the scene. When the program
 * prints over a marked text, that text stops being offered, and when nothing is left quick select
 * ends; the rest keep their labels.
 *
 * <p>Runs on the JavaFX thread; the text buffer listener only schedules work there.
 */
final class TerminalQuickSelectController {

    /** How long the "Copied" or "No matches" note stays. */
    static final Duration FEEDBACK_DURATION = Duration.seconds(1);

    /** Opacity of the box fill and outline drawn over a match, in the pane's text color. */
    private static final double MATCH_FILL_OPACITY = 0.18;
    private static final double MATCH_STROKE_OPACITY = 0.7;

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final TerminalSplitPane splitPane;
    private final @Nullable KeyCombination trigger;
    private final QuickSelectInputGuard input = new QuickSelectInputGuard();
    private Consumer<String> copier = KorttyClipboard::setText;
    private @Nullable Shown shown;

    private TerminalQuickSelectController(@NotNull TerminalSplitPane splitPane, @Nullable KeyCombination trigger) {
        this.splitPane = Objects.requireNonNull(splitPane, "splitPane");
        this.trigger = trigger;
    }

    /**
     * Adds quick select's key filters to {@code splitPane}. Call it before any other key filter is
     * added to the split pane, so quick select sees every key first.
     *
     * @param trigger the chord that starts quick select; ignored while it runs, because holding it
     *                down repeats it
     */
    static @NotNull TerminalQuickSelectController install(@NotNull TerminalSplitPane splitPane,
            @Nullable KeyCombination trigger) {
        TerminalQuickSelectController controller = new TerminalQuickSelectController(splitPane, trigger);
        splitPane.addEventFilter(KeyEvent.KEY_PRESSED, controller::onKeyPressed);
        splitPane.addEventFilter(KeyEvent.KEY_TYPED, controller::onKeyTyped);
        splitPane.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, controller::onInputMethod);
        return controller;
    }

    /**
     * Starts quick select in {@code widget}, which gets the keyboard focus. Does nothing while quick
     * select already runs there, and ends it in another pane first.
     *
     * @return whether quick select runs now; false when the pane shows nothing to pick, which the
     *         pane says for a moment
     */
    boolean start(@NotNull KorttyTermWidget widget) {
        Objects.requireNonNull(widget, "widget");
        if (shown != null) {
            if (shown.widget == widget) {
                return true;
            }
            cancel();
        }
        if (!(widget.getTerminalPanel() instanceof KorttyTermWidget.KorttyTerminalPanel panel)) {
            return false;
        }
        splitPane.focusWidget(widget);
        TerminalCellGeometry geometry = panel.cellGeometry();
        Pane layer = splitPane.paneOverlay(widget, PaneOverlayLayer.LINKS);
        if (geometry == null || layer == null) {
            return false;
        }
        List<Hit> hits = QuickSelectScreen.capture(panel.getTerminalTextBuffer(), geometry.scrollOrigin(), geometry.rows());
        QuickSelectSession session = QuickSelectSession.of(hits, QuickSelectLabels.DEFAULT_ALPHABET, trigger);
        if (session.targets().isEmpty()) {
            showFeedback(panel, layer, null, I18n.get("terminal.quickSelect.noMatches"));
            return false;
        }
        Shown started = new Shown(widget, panel, layer, session, geometry);
        shown = started;
        started.draw();
        started.watch();
        return true;
    }

    /** Ends quick select without doing anything; nothing happens while it does not run. */
    void cancel() {
        Shown ending = shown;
        shown = null;
        if (ending != null) {
            ending.dispose();
        }
    }

    boolean isActive() {
        return shown != null;
    }

    /** The labels shown now, in label order; for {@code terminalLinksSmoke}. */
    @NotNull List<String> shownLabels() {
        Shown current = shown;
        if (current == null) {
            return List.of();
        }
        return current.session.targets().stream().filter(current.session::shows).map(Target::label).toList();
    }

    /** The text each shown label picks, in label order; for {@code terminalLinksSmoke}. */
    @NotNull List<String> shownTexts() {
        Shown current = shown;
        if (current == null) {
            return List.of();
        }
        return current.session.targets().stream().filter(current.session::shows).map(Target::text).toList();
    }

    /** Replaces the clipboard as the target of copies, for {@code terminalLinksSmoke}. */
    void setCopier(@NotNull Consumer<String> copier) {
        this.copier = Objects.requireNonNull(copier, "copier");
    }

    /**
     * What Shift with a label opens in the browser or mail program: a web address as it is and an
     * e-mail address as a {@code mailto} link, if {@link TerminalLinkOpener} allows it; {@code null}
     * for every other kind and for an address it refuses. Those are copied instead, except a path
     * the pane opens ({@link #openFile}).
     */
    static @Nullable URI openTarget(@NotNull Kind kind, @NotNull String text) {
        return switch (kind) {
            case URL -> TerminalLinkOpener.allowedBrowseUri(text).orElse(null);
            case EMAIL -> TerminalLinkOpener.allowedBrowseUri("mailto:" + text).orElse(null);
            default -> null;
        };
    }

    /**
     * The file Shift with a label opens in the Snippet Editor: a {@link Kind#PATH}, if the pane opens
     * files ({@link KorttyTermWidget#opens}); {@code null} for every other kind.
     */
    static @Nullable TerminalFileLink openFile(@NotNull Kind kind, @NotNull String text) {
        return kind == Kind.PATH ? TerminalFileLink.printed(text) : null;
    }

    private void onKeyPressed(@NotNull KeyEvent event) {
        input.keyPressed();
        Shown current = shown;
        if (current == null) {
            return;
        }
        Outcome outcome = current.session.onKey(KeyPress.of(event, MAC));
        event.consume();
        input.consumed(event.getCode());
        switch (outcome.action()) {
            case IGNORE -> {
            }
            case CONTINUE -> current.showPrefix();
            case CANCEL -> cancel();
            case COPY -> {
                cancel();
                copy(current, Objects.requireNonNull(outcome.target()));
            }
            case OPEN -> {
                cancel();
                open(current, Objects.requireNonNull(outcome.target()));
            }
        }
    }

    private void onKeyTyped(@NotNull KeyEvent event) {
        if (input.swallowTyped(shown != null, event.getCharacter())) {
            event.consume();
        }
    }

    private void onInputMethod(@NotNull InputMethodEvent event) {
        if (input.swallowInputMethod(shown != null, !event.getComposed().isEmpty())) {
            event.consume();
        }
    }

    private void copy(@NotNull Shown from, @NotNull Target target) {
        copier.accept(target.text());
        showFeedback(from.panel, from.layer, target.hits().get(0), I18n.get("terminal.quickSelect.copied"));
    }

    private void open(@NotNull Shown from, @NotNull Target target) {
        Hit hit = target.hits().get(0);
        TerminalLinkResolver.Link link = new TerminalLinkResolver.Link(HitKind.AUTO,
            openTarget(target.kind(), target.text()), target.text(), hit.start(), hit.end(),
            openFile(target.kind(), target.text()));
        if (!from.widget.opens(link)) {
            copy(from, target);
            return;
        }
        from.widget.openLink(link);
    }

    /**
     * Shows {@code text} for {@link #FEEDBACK_DURATION} over the first cell of {@code at}, or over the
     * pane's top left corner.
     */
    private static void showFeedback(@NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull Pane layer,
            @Nullable Hit at, @NotNull String text) {
        TerminalCellGeometry geometry = panel.cellGeometry();
        if (geometry == null) {
            return;
        }
        double x = geometry.insetX();
        double y = 0;
        if (at != null) {
            List<TerminalCellGeometry.Box> boxes = geometry.boxes(at.start(), at.end());
            if (!boxes.isEmpty()) {
                x = boxes.get(0).x();
                y = boxes.get(0).y();
            }
        }
        Point2D origin = toLayer(layer, panel.getCanvas(), x, y);
        if (origin == null) {
            return;
        }
        Label note = chip(panel, text, "terminal-link-feedback", true);
        note.setAccessibleText(text);
        note.relocate(origin.getX(), origin.getY());
        layer.getChildren().add(note);
        note.applyCss();
        note.autosize();
        note.layout();
        PauseTransition delay = new PauseTransition(FEEDBACK_DURATION);
        delay.setOnFinished(event -> layer.getChildren().remove(note));
        delay.play();
    }

    /**
     * A label in the pane's colors inverted, in its font: text color on the background color.
     *
     * @param framed with some padding and a one-pixel outline in the background color, for a note;
     *               without, for a label that fills whole cells
     */
    private static @NotNull Label chip(@NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull String text,
            @NotNull String styleClass, boolean framed) {
        Color foreground = panel.getForeground();
        Color background = panel.getBackground();
        Font font = panel.terminalFont();
        Label chip = new Label(text);
        chip.getStyleClass().add(styleClass);
        chip.setMouseTransparent(true);
        chip.setFocusTraversable(false);
        // Inline, so no application style sheet can make the label unreadable on the terminal.
        chip.setStyle("-fx-background-color: " + css(foreground) + ";"
            + " -fx-text-fill: " + css(background) + ";"
            + " -fx-border-color: " + css(background) + ";"
            + (framed ? " -fx-border-width: 1; -fx-padding: 0 2 0 2;" : " -fx-border-width: 0; -fx-padding: 0;")
            + " -fx-background-radius: 2; -fx-border-radius: 2;"
            + " -fx-font-family: \"" + font.getFamily().replace("\"", "") + "\";"
            + String.format(Locale.ROOT, " -fx-font-size: %.1fpx;", font.getSize())
            + " -fx-font-weight: bold;");
        return chip;
    }

    /** The color without its opacity, so a see-through terminal background never fades a label. */
    private static @NotNull String css(@NotNull Color color) {
        return String.format(Locale.ROOT, "rgb(%d,%d,%d)", Math.round(color.getRed() * 255),
            Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    /** A point of the canvas in the layer's coordinates; the layer's origin is the pane wrapper's. */
    private static @Nullable Point2D toLayer(@NotNull Pane layer, @NotNull Canvas canvas, double x, double y) {
        Point2D scene = canvas.localToScene(x, y);
        return scene != null ? layer.sceneToLocal(scene) : null;
    }

    /** One running quick select: what it shows in which pane, and the listeners that end it. */
    private final class Shown {

        final KorttyTermWidget widget;
        final KorttyTermWidget.KorttyTerminalPanel panel;
        final Pane layer;
        final QuickSelectSession session;
        final TerminalCellGeometry geometry;
        final Canvas canvas;
        final TerminalTextBuffer buffer;
        final Group group = new Group();
        final List<Node> boxes = new ArrayList<>();
        final List<Label> chips = new ArrayList<>();
        final List<Target> chipTargets = new ArrayList<>();
        final List<Target> boxTargets = new ArrayList<>();
        final AtomicBoolean recheckPending = new AtomicBoolean();
        final Bounds startBounds;
        final TerminalModelListener textChanged = this::onTextChanged;
        final EventHandler<MouseEvent> pressed = event -> endIfCurrent();
        final ChangeListener<Boolean> focusChanged = (observable, was, focused) -> {
            if (!focused) {
                endIfCurrent();
            }
        };
        final ChangeListener<Scene> sceneChanged = (observable, was, scene) -> {
            if (scene == null) {
                endIfCurrent();
            }
        };
        final InvalidationListener placementChanged = observable -> checkPlacement();
        final InvalidationListener scrolled = observable -> endIfCurrent();

        Shown(@NotNull KorttyTermWidget widget, @NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull Pane layer,
              @NotNull QuickSelectSession session, @NotNull TerminalCellGeometry geometry) {
            this.widget = widget;
            this.panel = panel;
            this.layer = layer;
            this.session = session;
            this.geometry = geometry;
            this.canvas = panel.getCanvas();
            this.buffer = panel.getTerminalTextBuffer();
            this.startBounds = canvas.localToScene(canvas.getLayoutBounds());
        }

        /** Draws a box around every place of every target and its label over the first cell. */
        void draw() {
            group.getChildren().clear();
            boxes.clear();
            chips.clear();
            chipTargets.clear();
            boxTargets.clear();
            Color foreground = panel.getForeground();
            Color fill = foreground.deriveColor(0, 1, 1, MATCH_FILL_OPACITY);
            Color stroke = foreground.deriveColor(0, 1, 1, MATCH_STROKE_OPACITY);
            for (Target target : session.targets()) {
                for (Hit hit : target.hits()) {
                    List<TerminalCellGeometry.Box> cells = geometry.boxes(hit.start(), hit.end());
                    for (TerminalCellGeometry.Box cell : cells) {
                        Point2D from = toLayer(layer, canvas, cell.x(), cell.y());
                        Point2D to = toLayer(layer, canvas, cell.x() + cell.width(), cell.y() + cell.height());
                        if (from == null || to == null) {
                            continue;
                        }
                        Rectangle box = new Rectangle(from.getX(), from.getY(), to.getX() - from.getX(),
                            to.getY() - from.getY());
                        box.setFill(fill);
                        box.setStroke(stroke);
                        box.setStrokeWidth(1);
                        box.setStrokeType(StrokeType.INSIDE);
                        box.setMouseTransparent(true);
                        box.getStyleClass().add("quick-select-match");
                        boxes.add(box);
                        boxTargets.add(target);
                    }
                    if (cells.isEmpty()) {
                        continue;
                    }
                    // A badge of whole cells from the match's first: one cell per letter and one more,
                    // so it reads as a label and not as part of the text, and leaves no half character.
                    TerminalCellGeometry.Box first = cells.get(0);
                    Point2D corner = toLayer(layer, canvas, first.x(), first.y());
                    Point2D far = toLayer(layer, canvas,
                        first.x() + (target.label().length() + 1) * geometry.cellWidth(), first.y() + first.height());
                    if (corner == null || far == null) {
                        continue;
                    }
                    Label chip = chip(panel, target.label(), "quick-select-label", false);
                    chip.setAlignment(Pos.CENTER);
                    double width = far.getX() - corner.getX();
                    double height = far.getY() - corner.getY();
                    // At least those cells, wider only if the bold letters need it; never cut short.
                    chip.setMinSize(width, height);
                    chip.setPrefHeight(height);
                    chip.setMaxHeight(height);
                    chip.setTextOverrun(OverrunStyle.CLIP);
                    chip.relocate(corner.getX(), corner.getY());
                    chips.add(chip);
                    chipTargets.add(target);
                }
            }
            // The boxes first, so no box covers another match's label.
            group.getChildren().addAll(boxes);
            group.getChildren().addAll(chips);
            group.setMouseTransparent(true);
            group.setAccessibleText(I18n.get("terminal.quickSelect.accessible", session.targets().size()));
            if (group.getParent() == null) {
                layer.getChildren().add(group);
            }
            group.applyCss();
            group.layout();
            showPrefix();
        }

        /** Hides the boxes and labels of targets whose label no longer fits the letters typed. */
        void showPrefix() {
            for (int i = 0; i < chips.size(); i++) {
                chips.get(i).setVisible(session.shows(chipTargets.get(i)));
            }
            for (int i = 0; i < boxes.size(); i++) {
                boxes.get(i).setVisible(session.shows(boxTargets.get(i)));
            }
        }

        void watch() {
            canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
            canvas.focusedProperty().addListener(focusChanged);
            canvas.sceneProperty().addListener(sceneChanged);
            canvas.localToSceneTransformProperty().addListener(placementChanged);
            canvas.widthProperty().addListener(placementChanged);
            canvas.heightProperty().addListener(placementChanged);
            panel.getScrollBar().valueProperty().addListener(scrolled);
            buffer.addModelListener(textChanged);
            // An invalidation listener hears only a change of a valid value: validate it once.
            canvas.getLocalToSceneTransform();
        }

        void dispose() {
            canvas.removeEventFilter(MouseEvent.MOUSE_PRESSED, pressed);
            canvas.focusedProperty().removeListener(focusChanged);
            canvas.sceneProperty().removeListener(sceneChanged);
            canvas.localToSceneTransformProperty().removeListener(placementChanged);
            canvas.widthProperty().removeListener(placementChanged);
            canvas.heightProperty().removeListener(placementChanged);
            panel.getScrollBar().valueProperty().removeListener(scrolled);
            buffer.removeModelListener(textChanged);
            layer.getChildren().remove(group);
        }

        private void endIfCurrent() {
            if (shown == this) {
                cancel();
            }
        }

        /** Ends quick select when the pane moved or changed size, so the marks would sit beside their text. */
        private void checkPlacement() {
            if (shown != this) {
                return;
            }
            // Reading the transform validates it again, so the next change is reported too.
            canvas.getLocalToSceneTransform();
            if (!startBounds.equals(canvas.localToScene(canvas.getLayoutBounds()))) {
                cancel();
            }
        }

        /** On the emulator thread: schedule one recheck on the JavaFX thread, however much text arrives. */
        private void onTextChanged() {
            if (recheckPending.compareAndSet(false, true)) {
                Platform.runLater(this::recheck);
            }
        }

        /** Drops the places the program printed over; ends quick select when nothing is left. */
        private void recheck() {
            recheckPending.set(false);
            if (shown != this) {
                return;
            }
            int before = places();
            if (!session.retainHits(hit -> QuickSelectScreen.stillShows(buffer, hit))) {
                cancel();
                return;
            }
            if (places() != before) {
                draw();
            }
        }

        private int places() {
            return session.targets().stream().mapToInt(target -> target.hits().size()).sum();
        }
    }
}
