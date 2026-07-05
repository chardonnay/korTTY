package de.kortty.plugin.terminaleffects.pack;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Paces terminal output with a typewriter rhythm: a short delay per character, longer pauses
 * after punctuation and a distinct carriage-return pause on line breaks.
 */
final class PackTypewriterTtyConnector implements TerminalEffectConnectorWrapper {

    private static final int READ_BUFFER_SIZE = 2048;
    private static final long CHARACTER_DELAY_MILLIS = 28L;
    private static final long PUNCTUATION_DELAY_MILLIS = 120L;
    private static final long LINE_BREAK_DELAY_MILLIS = 300L;

    private final TtyConnector delegate;
    private final DoubleSupplier animationSpeedSupplier;
    private final PackOutputPacer pacer = new PackOutputPacer();
    private String pendingText;

    PackTypewriterTtyConnector(TtyConnector delegate, DoubleSupplier animationSpeedSupplier) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.animationSpeedSupplier = Objects.requireNonNull(animationSpeedSupplier, "animationSpeedSupplier");
    }

    @Override
    public TtyConnector delegate() {
        return delegate;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        int copied = drainPendingText(buf, offset, length);
        if (copied > 0) {
            return copied;
        }

        PackOutputPacer.OutputSegment queued = pacer.poll();
        if (queued != null) {
            return copySegment(queued, buf, offset, length);
        }

        char[] source = new char[Math.min(READ_BUFFER_SIZE, Math.max(1, length))];
        int count = delegate.read(source, 0, source.length);
        if (count <= 0) {
            return count;
        }
        pacer.enqueue(new String(source, 0, count));
        PackOutputPacer.OutputSegment segment = pacer.poll();
        if (segment == null) {
            return 0;
        }
        return copySegment(segment, buf, offset, length);
    }

    private int copySegment(
            PackOutputPacer.OutputSegment segment,
            char[] buf,
            int offset,
            int length) throws IOException {
        sleepBeforeOutput(segment.delayMode());
        int copied = Math.min(length, segment.text().length());
        segment.text().getChars(0, copied, buf, offset);
        if (copied < segment.text().length()) {
            pendingText = segment.text().substring(copied);
        }
        return copied;
    }

    private int drainPendingText(char[] buf, int offset, int length) {
        if (pendingText == null || pendingText.isEmpty()) {
            return 0;
        }
        int copied = Math.min(length, pendingText.length());
        pendingText.getChars(0, copied, buf, offset);
        pendingText = copied < pendingText.length() ? pendingText.substring(copied) : null;
        return copied;
    }

    private void sleepBeforeOutput(PackOutputPacer.DelayMode delayMode) throws IOException {
        long delayMillis = delayMillis(delayMode, animationSpeedSupplier.getAsDouble());
        if (delayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while pacing terminal output", e);
        }
    }

    static long delayMillis(PackOutputPacer.DelayMode delayMode, double animationSpeed) {
        long baseDelayMillis = switch (delayMode) {
            case NONE -> 0L;
            case CHARACTER -> CHARACTER_DELAY_MILLIS;
            case PUNCTUATION -> PUNCTUATION_DELAY_MILLIS;
            case LINE_BREAK -> LINE_BREAK_DELAY_MILLIS;
        };
        if (baseDelayMillis <= 0L) {
            return 0L;
        }
        double normalizedSpeed = TerminalEffectAnimationSpeed.normalize(animationSpeed);
        return Math.max(1L, Math.round(baseDelayMillis / normalizedSpeed));
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
    public boolean ready() throws IOException {
        return pendingText != null && !pendingText.isEmpty()
                || pacer.hasPending()
                || delegate.ready();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
