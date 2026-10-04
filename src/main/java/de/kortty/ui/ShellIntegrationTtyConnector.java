package de.kortty.ui;

import com.sithtermfx.core.Questioner;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.emulator.EmulationType;
import com.sithtermfx.core.util.TermSize;
import de.kortty.shellintegration.OscEventSplitter;
import de.kortty.shellintegration.ShellIntegrationEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The outermost connector of a pane: takes the {@link de.kortty.shellintegration.OwnedOsc}
 * sequences out of the output with an {@link OscEventSplitter} and delivers them as
 * {@link ShellIntegrationEvent}s at the exact point of the stream where they stood.
 *
 * <p>{@link #read} returns text only up to the next sequence it took out. Its event is delivered at
 * the start of the following {@code read}, on the emulator thread: SithTermFX asks for more only
 * once it has interpreted every char it was given (its {@code TtyBasedArrayDataStream} refills when
 * empty, and no escape sequence reads ahead), so the event handler sees the cursor and buffer
 * exactly as they stood where the sequence was. {@code ShellIntegrationEmulatorOrderTest} pins
 * that. A sequence without an event ends the read as well, so SithTermFX writes the text on both
 * sides in separate pieces, as it does when it reads the sequence itself.
 *
 * <p>{@code read} returns {@code 0} or less only when the delegate reports end of stream; a chunk
 * that held nothing but events blocks for more output, because SithTermFX takes any non-positive
 * count for the end of the session.
 *
 * <p>The wrapper registers nothing anywhere: a pane re-decorated after a Mosh recovery gets a new
 * one while the old emulator may still read the old one, and each keeps its own state. The event
 * consumer is resolved per pane by whoever creates it.
 *
 * <p>Only emulations that SithTermFX runs on {@code SithEmulator} read OSC the way the splitter
 * expects ({@link #appliesTo}); every other emulation must not get this wrapper.
 */
public final class ShellIntegrationTtyConnector implements TtyConnector {

    private static final Logger logger = LoggerFactory.getLogger(ShellIntegrationTtyConnector.class);

    private final TtyConnector delegate;
    private final Consumer<ShellIntegrationEvent> events;
    private final OscEventSplitter splitter = new OscEventSplitter();
    private final Splits splits = new Splits();

    /** Whether text is waiting to be returned; read by {@link #ready} from any thread. */
    private volatile boolean textPending;

    public ShellIntegrationTtyConnector(TtyConnector delegate, Consumer<ShellIntegrationEvent> events) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Whether SithTermFX interprets {@code emulation} with {@code SithEmulator}: XTERM, the VT100
     * to VT520 family, SCO ANSI, Sun CDE and CTERM. Wyse, TeleVideo, HP, TN3270, TN5250 and
     * PETSCII have emulators of their own, for which the C1 char {@code U+009D} or an ESC sequence
     * may mean something else entirely, so their output must not be split.
     */
    public static boolean appliesTo(@Nullable EmulationType emulation) {
        if (emulation == null) {
            return false;
        }
        return switch (emulation) {
            case XTERM, VT100, VT102, VT220, VT320, VT420, VT520, SCOANSI, SUN_CDE, CTERM -> true;
            default -> false;
        };
    }

    TtyConnector delegate() {
        return delegate;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        while (true) {
            deliverEventsUpTo(splits.textStart);
            int available = splits.textBeforeNextEvent();
            if (available > 0) {
                int count = Math.min(length, available);
                System.arraycopy(splits.text, splits.textStart, buf, offset, count);
                splits.textStart += count;
                textPending = splits.textStart < splits.textEnd;
                return count;
            }
            // Every char returned and every event delivered: read the next chunk.
            splits.clear();
            textPending = false;
            char[] input = splits.input(length);
            int count = delegate.read(input, 0, length);
            if (count <= 0) {
                return count;
            }
            splitter.feed(input, 0, count, splits);
            textPending = splits.textEnd > 0;
        }
    }

    private void deliverEventsUpTo(int position) {
        PendingEvent pending;
        while ((pending = splits.events.peekFirst()) != null && pending.position() <= position) {
            splits.events.removeFirst();
            ShellIntegrationEvent event = pending.event();
            if (event == null) {
                continue;
            }
            try {
                events.accept(event);
            } catch (RuntimeException e) {
                // A failing consumer must never stop the pane's emulator thread.
                logger.warn("A shell integration event handler failed: {}", e.getClass().getName());
                logger.debug("Shell integration event handler failure", e);
            }
        }
    }

    /**
     * Text waiting to be returned, not events: an event alone gives SithTermFX nothing to read, and
     * answering {@code false} lets it run its before-blocking hook (type-ahead) before {@link #read}
     * delivers the event and waits for output.
     */
    @Override
    public boolean ready() throws IOException {
        return textPending || delegate.ready();
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        delegate.write(bytes);
    }

    @Override
    public void write(String string) throws IOException {
        delegate.write(string);
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void resize(@NotNull TermSize termSize) {
        delegate.resize(termSize);
    }

    @Override
    public int waitFor() throws InterruptedException {
        return delegate.waitFor();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @SuppressWarnings({"removal", "DeprecatedIsStillUsed"})
    @Override
    public boolean init(Questioner questioner) {
        return delegate.init(questioner);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void resize(@NotNull java.awt.Dimension termWinSize) {
        delegate.resize(termWinSize);
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Override
    public void resize(java.awt.Dimension termWinSize, java.awt.Dimension pixelSize) {
        delegate.resize(termWinSize, pixelSize);
    }

    /** A sequence taken out before the text at {@code position}; {@code event} null if it announced nothing. */
    private record PendingEvent(int position, @Nullable ShellIntegrationEvent event) {
    }

    /** The splitter's output for the current chunk; touched only by the reading thread. */
    private static final class Splits implements OscEventSplitter.Sink {
        private char[] input = new char[1024];
        private char[] text = new char[1024];
        private int textStart;
        private int textEnd;
        /** Sequences taken out, in stream order; each sits before the text at its position. */
        private final ArrayDeque<PendingEvent> events = new ArrayDeque<>();

        char[] input(int length) {
            if (input.length < length) {
                input = new char[length];
            }
            return input;
        }

        int textBeforeNextEvent() {
            PendingEvent next = events.peekFirst();
            return (next != null ? next.position() : textEnd) - textStart;
        }

        void clear() {
            textStart = 0;
            textEnd = 0;
            events.clear();
        }

        @Override
        public void text(char[] chars, int offset, int length) {
            if (textEnd + length > text.length) {
                text = Arrays.copyOf(text, Math.max(text.length * 2, textEnd + length));
            }
            System.arraycopy(chars, offset, text, textEnd, length);
            textEnd += length;
        }

        @Override
        public void sequence(@Nullable ShellIntegrationEvent event) {
            events.addLast(new PendingEvent(textEnd, event));
        }
    }
}
