package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import de.kortty.ui.TerminalLinkHoverController.LinkFinder;
import de.kortty.ui.TerminalLinkResolver.Link;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * <b>Open Link</b> and <b>Copy Link Address</b> at the top of the terminal's right-click menu, when
 * it is opened on a link. They reach a link without holding Cmd or Ctrl, so a link can be opened
 * with one hand on the mouse or a pointing device without modifier keys, and they also work while a
 * program such as tmux or vim uses the mouse.
 *
 * <p>The split pane builds the menu on the right-button {@code MOUSE_CLICKED} and knows only the
 * pane, not where the button went down. So a {@code MOUSE_PRESSED} filter on the canvas remembers the
 * link under every right-button press, looked up there and then with the same bounded search as a
 * click ({@link TerminalLinkResolver}), and {@link KorttyTermWidget#contextMenuLink()} hands it to the
 * menu. Output that arrives between the press and the menu cannot change which link the menu acts
 * on. Any other press, or a right-button press beside the text, forgets it.
 *
 * <p>{@link #entries} is the toolkit-free part: no entries for no link or for a link korTTY does not
 * open (one without a target, see {@link Link#target()}); otherwise <b>Open Link</b>, which opens the
 * link exactly as a Cmd/Ctrl+click does, the host-mismatch question for an OSC 8 link included, and
 * <b>Copy Link Address</b>, which copies {@link #address}.
 */
public final class TerminalLinkContextMenu {

    /** The label of the entry that opens the link. */
    public static final String OPEN_LINK_KEY = "terminal.contextMenu.openLink";

    /** The label of the entry that copies the link's address. */
    public static final String COPY_LINK_KEY = "terminal.contextMenu.copyLink";

    /**
     * One entry of the menu.
     *
     * @param i18nKey the key of its label
     * @param action  what choosing it does
     */
    public record Entry(@NotNull String i18nKey, @NotNull Runnable action) {

        public Entry {
            Objects.requireNonNull(i18nKey, "i18nKey");
            Objects.requireNonNull(action, "action");
        }
    }

    private final LinkFinder finder;
    private @Nullable Link link;

    TerminalLinkContextMenu(@NotNull LinkFinder finder) {
        this.finder = Objects.requireNonNull(finder, "finder");
    }

    /**
     * Adds the press filter to {@code panel}'s canvas.
     *
     * @param finder finds the link under a cell, {@link TerminalLinkResolver#linkAt} for the pane's
     *               link kinds
     */
    static @NotNull TerminalLinkContextMenu install(@NotNull KorttyTermWidget.KorttyTerminalPanel panel,
            @NotNull LinkFinder finder) {
        TerminalLinkContextMenu menu = new TerminalLinkContextMenu(finder);
        panel.getCanvas().addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            boolean secondary = event.getButton() == MouseButton.SECONDARY;
            menu.pressed(secondary, panel.getTerminalTextBuffer(),
                secondary ? panel.cellAt(event.getX(), event.getY()) : null);
        });
        return menu;
    }

    /**
     * Remembers the link under a press: under {@code cell} for a right-button press, none for any
     * other press or a press on no cell.
     */
    void pressed(boolean secondary, @NotNull TerminalTextBuffer buffer, @Nullable Point cell) {
        link = secondary && cell != null ? finder.linkAt(buffer, cell) : null;
    }

    /** The link under the last right-button press, or {@code null}. */
    @Nullable Link link() {
        return link;
    }

    /**
     * The entries for a menu opened on {@code link}, in menu order.
     *
     * @param link  the link the menu was opened on, or {@code null}
     * @param open  opens a link as a Cmd/Ctrl+click on it does
     * @param copy  puts text on the clipboard, normally {@code KorttyClipboard.setText}
     * @return <b>Open Link</b> and <b>Copy Link Address</b> for a link korTTY opens, none otherwise
     */
    public static @NotNull List<Entry> entries(@Nullable Link link, @NotNull Consumer<Link> open,
            @NotNull Consumer<String> copy) {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(copy, "copy");
        if (link == null || link.target() == null) {
            return List.of();
        }
        String address = address(link);
        return List.of(new Entry(OPEN_LINK_KEY, () -> open.accept(link)),
            new Entry(COPY_LINK_KEY, () -> copy.accept(address)));
    }

    /**
     * What <b>Copy Link Address</b> copies. For an OSC 8 link, the target it really opens, in the form
     * the browser receives ({@link URI#toASCIIString()}: an international host in punycode, as the
     * tooltip shows it), never the text the program printed for it. For an address printed as plain
     * text, the address as printed, so an e-mail address comes without {@code mailto:}.
     *
     * @throws IllegalArgumentException for a link without a target
     */
    public static @NotNull String address(@NotNull Link link) {
        Objects.requireNonNull(link, "link");
        URI target = link.target();
        if (target == null) {
            throw new IllegalArgumentException("a link without a target has no address");
        }
        if (link.kind() == HitKind.AUTO && !link.text().isEmpty()) {
            return link.text();
        }
        return target.toASCIIString();
    }
}
