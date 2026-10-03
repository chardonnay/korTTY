package de.kortty.ui;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.TerminalLine;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import com.sithtermfx.core.util.CharUtils;
import de.kortty.core.TerminalLinkDetector;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.core.TerminalLinkDetector.Match;
import de.kortty.ui.TerminalLinkClickPolicy.Hit;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Finds the link under one cell of a terminal text buffer: an OSC 8 link a program printed around
 * its text, or a link korTTY finds in plain text.
 *
 * <p>Plain text is searched on demand, only when a click or the mouse moving onto another cell
 * ({@link TerminalLinkHoverController}) asks for one cell, never while output arrives. SithTermFX's
 * own mechanism for plain-text links, a {@code HyperlinkFilter}, runs on every write and keeps stale
 * or cut-short targets when a link arrives in pieces, so korTTY registers none (see
 * {@code NoHyperlinkFilterGuardTest}). Searching on demand costs nothing while a program prints, and
 * it always sees the text as it is on screen now.
 *
 * <p>The search covers the logical line around the cell: the rows it wraps from and into, at most
 * {@value #MAX_ROWS_AROUND} rows on each side and {@value #MAX_WINDOW_CHARS} characters in all (a
 * single row is always read whole). A token that runs on past those limits is not reported, rather
 * than reported cut short, so a click never opens half a link; see
 * {@link TerminalLinkDetector#find(CharSequence, Set, boolean, boolean)}. That keeps a hover over a
 * minified JSON document that wraps over thousands of rows as cheap as one over a short line.
 *
 * <p>Toolkit-free. It reads the buffer under {@link TerminalTextBuffer#lock()}, because the emulator
 * thread writes to it while the JavaFX thread asks.
 */
public final class TerminalLinkResolver implements TerminalLinkClickPolicy.HitResolver {

    /** Wrapped rows read at most on each side of the cell's row. */
    public static final int MAX_ROWS_AROUND = 16;

    /** Characters read at most around the cell, unless a single row is wider. */
    public static final int MAX_WINDOW_CHARS = 4096;

    /** What plain-text link detection looks for: web and mail links, and e-mail addresses (as {@code mailto}). */
    public static final Set<Kind> WEB_LINK_KINDS = Collections.unmodifiableSet(EnumSet.of(Kind.URL, Kind.EMAIL));

    /**
     * A link under a cell.
     *
     * @param kind   {@link HitKind#OSC8} or {@link HitKind#AUTO}, never {@link HitKind#NONE}
     * @param target what a Cmd/Ctrl+click opens: a target that passed {@link TerminalLinkOpener#allowedBrowseUri},
     *               or {@code null} when the link must not be opened (a refused target, an OSC 8 link of
     *               another origin, a kind korTTY does not open in a browser)
     * @param text   the text the link covers on screen, without {@link CharUtils#DWC} cells
     * @param start  the first cell of the link ({@code y} is the buffer line, negative in the history)
     * @param end    the last cell of the link, inclusive
     */
    public record Link(@NotNull HitKind kind, @Nullable URI target, @NotNull String text, @NotNull Point start,
            @NotNull Point end) {

        public Link {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (kind == HitKind.NONE) {
                throw new IllegalArgumentException("a link is OSC8 or AUTO");
            }
        }

        /** The link as the click policy sees it. */
        public @NotNull Hit hit() {
            return new Hit(kind, target, text);
        }
    }

    private final Supplier<Set<Kind>> plainTextKinds;

    /**
     * @param plainTextKinds the kinds to find in plain text, asked on every call so a settings change
     *     applies at once; an empty set turns plain-text detection off while OSC 8 links still resolve
     */
    public TerminalLinkResolver(@NotNull Supplier<Set<Kind>> plainTextKinds) {
        this.plainTextKinds = Objects.requireNonNull(plainTextKinds, "plainTextKinds");
    }

    @Override
    public @NotNull Hit hitAt(@NotNull TerminalTextBuffer buffer, @NotNull Point cell) {
        Link link = linkAt(buffer, cell, plainTextKinds.get());
        return link != null ? link.hit() : Hit.NONE;
    }

    /**
     * The link under {@code cell}. An OSC 8 link wins over anything found in its text. Otherwise the
     * plain text of the logical line around the cell is searched for {@code kinds}: a {@link Kind#URL}
     * opens as it is and an {@link Kind#EMAIL} as a {@code mailto} link, if {@link TerminalLinkOpener}
     * allows it; other kinds are reported without a target.
     *
     * @return the link, or {@code null} for a cell outside the buffer or on no link
     */
    public static @Nullable Link linkAt(@NotNull TerminalTextBuffer buffer, @NotNull Point cell,
            @Nullable Set<Kind> kinds) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(cell, "cell");
        buffer.lock();
        try {
            if (!isInside(buffer, cell)) {
                return null;
            }
            TextStyle style = buffer.getStyleAt(cell.x, cell.y);
            if (style instanceof HyperlinkStyle hyperlink) {
                return osc8Link(buffer, cell, hyperlink.getLinkInfo());
            }
            if (kinds == null || kinds.isEmpty()) {
                return null;
            }
            return plainTextLink(buffer, cell, kinds);
        } finally {
            buffer.unlock();
        }
    }

    /**
     * The rows read around {@code cell}: the logical line it belongs to, cut to
     * {@value #MAX_ROWS_AROUND} rows on each side and {@value #MAX_WINDOW_CHARS} characters. Rows the
     * end does not need go to the start. Call it under the buffer lock.
     */
    static @NotNull Window window(@NotNull TerminalTextBuffer buffer, @NotNull Point cell) {
        int width = buffer.getWidth();
        int firstLine = -buffer.getHistoryLinesCount();
        int lastLine = buffer.getHeight() - 1;
        int maxRows = Math.max(1, MAX_WINDOW_CHARS / Math.max(1, width));
        int start = extendUp(buffer, cell.y, Math.min(MAX_ROWS_AROUND, (maxRows - 1) / 2), firstLine);
        int end = cell.y;
        int rowsAfter = Math.min(MAX_ROWS_AROUND, maxRows - 1 - (cell.y - start));
        while (end - cell.y < rowsAfter && end < lastLine && buffer.getLine(end).isWrapped()) {
            end++;
        }
        start = extendUp(buffer, cell.y, Math.min(MAX_ROWS_AROUND, maxRows - 1 - (end - cell.y)), firstLine);
        boolean continuesBefore = start > firstLine && buffer.getLine(start - 1).isWrapped();
        boolean continuesAfter = end < lastLine && buffer.getLine(end).isWrapped();
        return new Window(start, end, width, continuesBefore, continuesAfter);
    }

    /** The first of at most {@code rows} rows above {@code row} that wrap, row by row, into it. */
    private static int extendUp(@NotNull TerminalTextBuffer buffer, int row, int rows, int firstLine) {
        int start = row;
        while (row - start < rows && start > firstLine && buffer.getLine(start - 1).isWrapped()) {
            start--;
        }
        return start;
    }

    private static @Nullable Link plainTextLink(@NotNull TerminalTextBuffer buffer, @NotNull Point cell,
            @NotNull Set<Kind> kinds) {
        Window window = window(buffer, cell);
        int offset = window.offsetOf(cell);
        for (Match match : TerminalLinkDetector.find(window.text(buffer), kinds, window.continuesBefore(),
                window.continuesAfter())) {
            if (match.start() > offset) {
                break;
            }
            if (offset < match.end()) {
                return new Link(HitKind.AUTO, target(match), match.text(), window.cellAt(match.start()),
                    window.cellAt(match.end() - 1));
            }
        }
        return null;
    }

    private static @Nullable URI target(@NotNull Match match) {
        return switch (match.kind()) {
            case URL -> TerminalLinkOpener.allowedBrowseUri(match.text()).orElse(null);
            case EMAIL -> TerminalLinkOpener.allowedBrowseUri("mailto:" + match.text()).orElse(null);
            default -> null;
        };
    }

    /**
     * The OSC 8 link under {@code cell}: the cells around it in the window that carry the same
     * {@link LinkInfo}, which is one per OSC 8 sequence. Only a {@link KorttyLinkInfo} has a target.
     */
    private static @NotNull Link osc8Link(@NotNull TerminalTextBuffer buffer, @NotNull Point cell,
            @Nullable LinkInfo info) {
        Window window = window(buffer, cell);
        int width = window.width();
        int rows = window.rows();
        boolean[] linked = new boolean[rows * width];
        for (int row = 0; row < rows; row++) {
            int x = 0;
            for (TerminalLine.TextEntry entry : buffer.getLine(window.start() + row).getEntries()) {
                int length = entry.getLength();
                if (entry.getStyle() instanceof HyperlinkStyle style && style.getLinkInfo() == info) {
                    for (int i = x; i < Math.min(x + length, width); i++) {
                        linked[row * width + i] = true;
                    }
                }
                x += length;
                if (x >= width) {
                    break;
                }
            }
        }
        int offset = window.offsetOf(cell);
        int first = offset;
        while (first > 0 && linked[first - 1]) {
            first--;
        }
        int last = offset;
        while (last + 1 < linked.length && linked[last + 1]) {
            last++;
        }
        String cells = window.text(buffer);
        StringBuilder text = new StringBuilder(last - first + 1);
        for (int i = first; i <= last; i++) {
            char c = cells.charAt(i);
            if (c != CharUtils.DWC && c != CharUtils.NUL_CHAR) {
                text.append(c);
            }
        }
        URI target = info instanceof KorttyLinkInfo korttyInfo ? korttyInfo.target() : null;
        return new Link(HitKind.OSC8, target, text.toString(), window.cellAt(first), window.cellAt(last));
    }

    private static boolean isInside(@NotNull TerminalTextBuffer buffer, @NotNull Point cell) {
        return cell.x >= 0 && cell.x < buffer.getWidth()
            && cell.y >= -buffer.getHistoryLinesCount() && cell.y < buffer.getHeight();
    }

    /**
     * Rows {@code start..end} of the buffer, read as one string of {@code width} cells per row.
     *
     * @param continuesBefore the logical line goes on above {@code start}
     * @param continuesAfter  the logical line goes on below {@code end}
     */
    record Window(int start, int end, int width, boolean continuesBefore, boolean continuesAfter) {

        int rows() {
            return end - start + 1;
        }

        int offsetOf(@NotNull Point cell) {
            return (cell.y - start) * width + cell.x;
        }

        @NotNull Point cellAt(int offset) {
            return new Point(offset % width, start + offset / width);
        }

        /**
         * The cells of the window, exactly {@code width} per row: what each row holds, cut at the
         * width, then padded with NUL (an empty cell), the way SithTermFX joins wrapped rows for its
         * own link filters. Call it under the buffer lock.
         */
        @NotNull String text(@NotNull TerminalTextBuffer buffer) {
            StringBuilder text = new StringBuilder(rows() * width);
            for (int line = start; line <= end; line++) {
                int rowStart = text.length();
                for (TerminalLine.TextEntry entry : buffer.getLine(line).getEntries()) {
                    int room = width - (text.length() - rowStart);
                    if (room <= 0) {
                        break;
                    }
                    CharSequence chars = entry.getText();
                    text.append(chars, 0, Math.min(chars.length(), room));
                }
                while (text.length() - rowStart < width) {
                    text.append(CharUtils.NUL_CHAR);
                }
            }
            return text.toString();
        }
    }
}
