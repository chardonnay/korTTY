package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.settings.ModifierKeys;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * When a click on a terminal link opens it: only on a single, still Cmd+click (macOS) or Ctrl+click
 * (Windows, Linux), never with Alt, which Windows also reports for AltGr (user decision D2).
 *
 * <p>Since SithTermFX 1.2.3 the vendor asks the settings provider which clicks follow a link
 * ({@code UserSettingsProvider.isFollowLinkGesture}), and follows one only on a still, single
 * primary click. korTTY's settings provider answers with {@link #isFollowLinkGesture(ModifierKeys)}.
 * So for an OSC 8 link SithTermFX itself decides: a plain click, a double or triple click and the
 * release of a drag act as on other text (the click does nothing, the multi-clicks select a word or a
 * line, the drag selects), and a Cmd/Ctrl+click navigates the link's {@link KorttyLinkInfo}, which
 * hands it back to the pane ({@code KorttyTerminalPanel.followLink}). The pane opens it only when
 * this class saw the same click arrive on the same link as a single, still click with the open
 * gesture, so D2 holds even under a settings provider or a SithTermFX that follows other clicks,
 * and the host-mismatch question and the file handler apply as for every other link.
 *
 * <p>A link korTTY finds in plain text ({@link HitKind#AUTO}) is ordinary text to SithTermFX, so
 * this class opens it itself, as a {@code MOUSE_CLICKED} event filter on the canvas of
 * {@link KorttyTermWidget.KorttyTerminalPanel} that runs before SithTermFX's own handler.
 * {@link #decide} is that filter's pure decision table: {@link Action#OPEN} for a single, still
 * primary click with the open gesture on a plain-text link, {@link Action#PASS} for everything else,
 * which SithTermFX and the split pane handle as before (selection, focus, middle-click paste, the
 * context menu, and OSC 8 links).
 *
 * <p>An opening click is consumed and requests the keyboard focus on the canvas: consuming it also
 * skips the split pane's click handler, and the split pane follows the canvas focus to know the
 * focused pane.
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

    /** What korTTY's click filter does with a click. */
    public enum Action {
        /** Leave the click to SithTermFX and the split pane. */
        PASS,
        /** Consume the click and open the plain-text link under it. */
        OPEN
    }

    /**
     * A link under a cell.
     *
     * @param kind   what kind of link it is
     * @param target the validated web or mail target to open, or {@code null}
     * @param text   the text the link covers on screen, empty when unknown; for an OSC 8 link it can
     *               name another host than its target, see {@link TerminalLinkOpener#visibleHostMismatch}
     * @param file   the file the link opens in the Snippet Editor, or {@code null}; a link has a
     *               {@code target} or a {@code file}, or neither when it must not be opened
     */
    public record Hit(@NotNull HitKind kind, @Nullable URI target, @NotNull String text, @Nullable TerminalFileLink file) {

        /** No link under the cell. */
        public static final Hit NONE = new Hit(HitKind.NONE, null);

        public Hit {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            if (target != null && file != null) {
                throw new IllegalArgumentException("a link opens a web target or a file, not both");
            }
        }

        /** A web or mail link, or one that must not be opened. */
        public Hit(@NotNull HitKind kind, @Nullable URI target, @NotNull String text) {
            this(kind, target, text, null);
        }

        /** A link whose text is not known. */
        public Hit(@NotNull HitKind kind, @Nullable URI target) {
            this(kind, target, "");
        }

        /** Whether a click can open the link: it has a web target or a file. */
        public boolean opens() {
            return target != null || file != null;
        }
    }

    /**
     * Finds the link under a cell of a text buffer, as {@link TerminalLinkResolver} does. Called on
     * the JavaFX thread for every primary click.
     */
    @FunctionalInterface
    public interface HitResolver {
        @NotNull Hit hitAt(@NotNull TerminalTextBuffer buffer, @NotNull Point cell);
    }

    private TerminalLinkClickPolicy() {
    }

    /**
     * The open gesture: Cmd (macOS) or Ctrl (Windows, Linux) without Alt. Alt is excluded because
     * Windows reports AltGr as Ctrl+Alt. korTTY's settings provider answers SithTermFX's
     * {@code isFollowLinkGesture} with this, so the vendor's hover highlight and its link clicks
     * follow the same rule as korTTY's own links.
     */
    public static boolean isFollowLinkGesture(@NotNull ModifierKeys modifiers) {
        Objects.requireNonNull(modifiers, "modifiers");
        return isFollowLinkGesture(modifiers.isShortcutDown(), modifiers.isAltDown());
    }

    /** {@link #isFollowLinkGesture(ModifierKeys)} for the two keys it looks at. */
    public static boolean isFollowLinkGesture(boolean shortcut, boolean alt) {
        return shortcut && !alt;
    }

    /**
     * The decision table of korTTY's click filter, described on the class.
     *
     * @param primary         the primary button was clicked
     * @param popupTrigger    the click is the platform's context-menu gesture
     * @param shortcut        Cmd (macOS) or Ctrl (Windows, Linux) is down
     * @param alt             Alt (Option on macOS, also AltGr on Windows) is down
     * @param clickCount      the click count of the event
     * @param stillSincePress the mouse stayed put between the press and this click
     * @param hit             what lies under the clicked cell
     */
    public static @NotNull Action decide(boolean primary, boolean popupTrigger, boolean shortcut, boolean alt,
            int clickCount, boolean stillSincePress, @NotNull HitKind hit) {
        Objects.requireNonNull(hit, "hit");
        boolean opens = primary && !popupTrigger && hit == HitKind.AUTO && isFollowLinkGesture(shortcut, alt)
            && clickCount == 1 && stillSincePress;
        return opens ? Action.OPEN : Action.PASS;
    }

    /**
     * Adds the click filter to {@code panel}'s canvas.
     *
     * @param resolver    finds the link under the clicked cell, normally a {@link TerminalLinkResolver}
     * @param opener      opens the plain-text link of an {@link Action#OPEN} click, one that
     *                    {@link Hit#opens()}, normally through {@link TerminalLinkOpener#open} or the
     *                    pane's file handler
     * @param osc8Clicked told about every single, still primary click with the open gesture on an
     *                    OSC 8 link, with the clicked cell and its link, before SithTermFX's own click
     *                    handler runs
     */
    static void install(@NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull HitResolver resolver,
            @NotNull Consumer<Hit> opener, @NotNull BiConsumer<Point, Hit> osc8Clicked) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(opener, "opener");
        Objects.requireNonNull(osc8Clicked, "osc8Clicked");
        panel.getCanvas().addEventFilter(MouseEvent.MOUSE_CLICKED,
            event -> onClicked(panel, resolver, opener, osc8Clicked, event));
    }

    private static void onClicked(@NotNull KorttyTermWidget.KorttyTerminalPanel panel, @NotNull HitResolver resolver,
            @NotNull Consumer<Hit> opener, @NotNull BiConsumer<Point, Hit> osc8Clicked, @NotNull MouseEvent event) {
        if (event.isConsumed() || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        Point cell = panel.cellAt(event.getX(), event.getY());
        if (cell == null) {
            return;
        }
        Hit hit = resolver.hitAt(panel.getTerminalTextBuffer(), cell);
        if (hit.kind() == HitKind.OSC8) {
            // Only a single, still click is noted, so a double click or a drag release never opens
            // an OSC 8 link even if a SithTermFX upgrade navigated on one.
            if (!event.isPopupTrigger() && isFollowLinkGesture(event.isShortcutDown(), event.isAltDown())
                    && event.getClickCount() == 1 && event.isStillSincePress()) {
                osc8Clicked.accept(cell, hit);
            }
            return;
        }
        Action action = decide(true, event.isPopupTrigger(), event.isShortcutDown(), event.isAltDown(),
            event.getClickCount(), event.isStillSincePress(), hit.kind());
        if (action != Action.OPEN) {
            return;
        }
        event.consume();
        panel.getCanvas().requestFocus();
        if (hit.opens()) {
            opener.accept(hit);
        }
        panel.repaint();
    }
}
