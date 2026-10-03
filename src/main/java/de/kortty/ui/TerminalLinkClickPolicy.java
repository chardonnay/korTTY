package de.kortty.ui;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SelectionUtil;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * When a click on a terminal link opens it: only on a single, still Cmd+click (macOS) or Ctrl+click
 * (Windows, Linux).
 *
 * <p>SithTermFX opens a link on every plain primary click over it. A double-click therefore opened it
 * twice, a drag-selection released over a link opened it, and so did a click while a program such as
 * tmux or vim had mouse reporting on. korTTY's {@link KorttyLinkInfo} does nothing when SithTermFX
 * navigates, and this class decides instead, as a {@code MOUSE_CLICKED} event filter on the canvas
 * of {@link KorttyTermWidget.KorttyTerminalPanel}. A filter runs before SithTermFX's own handler,
 * so a click this class consumes never reaches it.
 *
 * <p>{@link #decide} is the pure decision table:
 * <ul>
 *   <li>Anything but a primary click, a popup trigger, or a click on no link: {@link Action#PASS},
 *       SithTermFX and the split pane handle it as before (selection, focus, middle-click paste,
 *       the context menu).</li>
 *   <li>Shortcut down without Alt: {@link Action#OPEN} for a single click that did not move since the
 *       press, {@link Action#SWALLOW} otherwise, so a double-click or a drag release never opens a
 *       link. Alt is excluded because Windows reports AltGr as Ctrl+Alt.</li>
 *   <li>No open gesture over a link korTTY found in plain text ({@link HitKind#AUTO}): {@link Action#PASS},
 *       because SithTermFX sees ordinary text there and selects it as usual.</li>
 *   <li>No open gesture over an OSC 8 link: SithTermFX would navigate instead of selecting, so a
 *       single click is swallowed and a double or triple click selects the word or the line here,
 *       exactly as SithTermFX does on other text, but only where SithTermFX would select (not while
 *       a program receives the mouse).</li>
 * </ul>
 *
 * <p>Every consumed click requests the keyboard focus on the canvas: consuming it also skips the
 * split pane's click handler, and the split pane follows the canvas focus to know the focused pane.
 */
public final class TerminalLinkClickPolicy {

    /** What lies under the clicked cell. */
    public enum HitKind {
        /** No link. */
        NONE,
        /** An OSC 8 link a program printed around its text; SithTermFX treats the cell as a link. */
        OSC8,
        /** A link korTTY found in plain text; SithTermFX sees ordinary text there. */
        AUTO
    }

    /** What a click does. */
    public enum Action {
        /** Leave the click to SithTermFX and the split pane. */
        PASS,
        /** Open the link. */
        OPEN,
        /** Consume the click and do nothing else. */
        SWALLOW,
        /** Select the word under the click, as a double-click on other text does. */
        SELECT_WORD,
        /** Select the logical line under the click, as a triple-click on other text does. */
        SELECT_LINE
    }

    /**
     * A link under a cell.
     *
     * @param kind   what kind of link it is
     * @param target the validated target to open, or {@code null} when the link must not be opened
     */
    public record Hit(@NotNull HitKind kind, @Nullable URI target) {

        /** No link under the cell. */
        public static final Hit NONE = new Hit(HitKind.NONE, null);

        public Hit {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Finds the link under a cell of a text buffer. Called on the JavaFX thread for every primary click. */
    @FunctionalInterface
    public interface HitResolver {
        @NotNull Hit hitAt(@NotNull TerminalTextBuffer buffer, @NotNull Point cell);
    }

    private TerminalLinkClickPolicy() {
    }

    /**
     * The decision table described on the class.
     *
     * @param primary          the primary button was clicked
     * @param popupTrigger     the click is the platform's context-menu gesture
     * @param shortcut         Cmd (macOS) or Ctrl (Windows, Linux) is down
     * @param alt              Alt (Option on macOS, also AltGr on Windows) is down
     * @param clickCount       the click count of the event
     * @param stillSincePress  the mouse stayed put between the press and this click
     * @param hit              what lies under the clicked cell
     * @param localMouseAction SithTermFX handles the mouse itself instead of reporting it to the
     *                         program ({@code TerminalPanel.isLocalMouseAction})
     */
    public static @NotNull Action decide(boolean primary, boolean popupTrigger, boolean shortcut, boolean alt,
            int clickCount, boolean stillSincePress, @NotNull HitKind hit, boolean localMouseAction) {
        Objects.requireNonNull(hit, "hit");
        if (!primary || popupTrigger || hit == HitKind.NONE) {
            return Action.PASS;
        }
        if (shortcut && !alt) {
            return clickCount == 1 && stillSincePress ? Action.OPEN : Action.SWALLOW;
        }
        if (hit == HitKind.AUTO) {
            return Action.PASS;
        }
        if (clickCount == 2) {
            return localMouseAction ? Action.SELECT_WORD : Action.SWALLOW;
        }
        if (clickCount >= 3) {
            return localMouseAction ? Action.SELECT_LINE : Action.SWALLOW;
        }
        return Action.SWALLOW;
    }

    /**
     * Adds the click filter to {@code panel}'s canvas.
     *
     * @param resolver finds the link under the clicked cell
     * @param opener   opens the target of an {@link Action#OPEN} click, normally through
     *                 {@link TerminalLinkOpener#open}
     */
    static void install(@NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull HitResolver resolver,
            @NotNull Consumer<URI> opener) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(opener, "opener");
        panel.getCanvas().addEventFilter(MouseEvent.MOUSE_CLICKED, event -> onClicked(panel, resolver, opener, event));
    }

    private static void onClicked(@NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull HitResolver resolver,
            @NotNull Consumer<URI> opener, @NotNull MouseEvent event) {
        if (event.isConsumed() || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        Point cell = panel.cellAt(event.getX(), event.getY());
        if (cell == null) {
            return;
        }
        TerminalTextBuffer buffer = panel.getTerminalTextBuffer();
        Hit hit = resolver.hitAt(buffer, cell);
        Action action = decide(true, event.isPopupTrigger(), event.isShortcutDown(), event.isAltDown(),
            event.getClickCount(), event.isStillSincePress(), hit.kind(), panel.isLocalMouseAction(event));
        if (action == Action.PASS) {
            return;
        }
        event.consume();
        panel.getCanvas().requestFocus();
        switch (action) {
            case OPEN -> {
                if (hit.target() != null) {
                    opener.accept(hit.target());
                }
            }
            case SELECT_WORD -> panel.selectionProperty().set(wordSelection(buffer, cell));
            case SELECT_LINE -> panel.selectionProperty().set(lineSelection(buffer, cell));
            default -> {
                // SWALLOW: the click only takes the focus.
            }
        }
        panel.repaint();
    }

    /**
     * The OSC 8 link at {@code cell}, read under the buffer lock: {@link HitKind#OSC8} for a cell
     * that SithTermFX styles as a link, with the target of a {@link KorttyLinkInfo}. A link of any
     * other origin carries no target, so it is never opened.
     */
    static @NotNull Hit osc8HitAt(@NotNull TerminalTextBuffer buffer, @NotNull Point cell) {
        TextStyle style;
        buffer.lock();
        try {
            if (!isInside(buffer, cell)) {
                return Hit.NONE;
            }
            style = buffer.getStyleAt(cell.x, cell.y);
        } finally {
            buffer.unlock();
        }
        if (!(style instanceof HyperlinkStyle link)) {
            return Hit.NONE;
        }
        return new Hit(HitKind.OSC8, link.getLinkInfo() instanceof KorttyLinkInfo info ? info.target() : null);
    }

    /** The word around {@code cell}, delimited the way SithTermFX's own double-click delimits it. */
    static @NotNull TerminalSelection wordSelection(@NotNull TerminalTextBuffer buffer, @NotNull Point cell) {
        buffer.lock();
        try {
            TerminalSelection selection = new TerminalSelection(SelectionUtil.getPreviousSeparator(cell, buffer));
            selection.updateEnd(SelectionUtil.getNextSeparator(cell, buffer));
            return selection;
        } finally {
            buffer.unlock();
        }
    }

    /**
     * The logical line around {@code cell}: the row plus the rows it wraps from and into, as
     * SithTermFX's own triple-click selects it. Unlike SithTermFX it stops at the last screen row,
     * which has no row below it to wrap into.
     */
    static @NotNull TerminalSelection lineSelection(@NotNull TerminalTextBuffer buffer, @NotNull Point cell) {
        buffer.lock();
        try {
            int startLine = cell.y;
            while (startLine > -buffer.getHistoryLinesCount() && buffer.getLine(startLine - 1).isWrapped()) {
                startLine--;
            }
            int endLine = cell.y;
            while (endLine < buffer.getHeight() - 1 && buffer.getLine(endLine).isWrapped()) {
                endLine++;
            }
            return new TerminalSelection(new Point(0, startLine), new Point(buffer.getWidth(), endLine));
        } finally {
            buffer.unlock();
        }
    }

    private static boolean isInside(@NotNull TerminalTextBuffer buffer, @NotNull Point cell) {
        return cell.x >= 0 && cell.x < buffer.getWidth()
            && cell.y >= -buffer.getHistoryLinesCount() && cell.y < buffer.getHeight();
    }
}
