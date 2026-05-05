package de.kortty.plugin.terminaleffects.mother;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;
import java.util.function.DoubleSupplier;

final class MotherPacedTtyConnector implements TerminalEffectConnectorWrapper {

    private static final int READ_BUFFER_SIZE = 2048;
    private static final long CHARACTER_DELAY_MILLIS = 14L;
    private static final long WORD_GAP_DELAY_MILLIS = 6L;
    private static final long LINE_START_DELAY_MILLIS = 55L;
    private static final long LINE_BREAK_DELAY_MILLIS = 70L;
    private static final long PUNCTUATION_DELAY_MILLIS = 34L;

    private final TtyConnector delegate;
    private final DoubleSupplier animationSpeedSupplier;
    private final Runnable visibleOutputListener;
    private final TerminalOutputPacer pacer = new TerminalOutputPacer();
    private String pendingText;

    MotherPacedTtyConnector(TtyConnector delegate, DoubleSupplier animationSpeedSupplier) {
        this(delegate, animationSpeedSupplier, () -> {
        });
    }

    MotherPacedTtyConnector(
            TtyConnector delegate,
            DoubleSupplier animationSpeedSupplier,
            Runnable visibleOutputListener) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.animationSpeedSupplier = Objects.requireNonNull(animationSpeedSupplier, "animationSpeedSupplier");
        this.visibleOutputListener = Objects.requireNonNull(visibleOutputListener, "visibleOutputListener");
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

        TerminalOutputPacer.OutputSegment queued = pacer.poll();
        if (queued != null) {
            return copySegment(queued, buf, offset, length);
        }

        char[] source = new char[Math.min(READ_BUFFER_SIZE, Math.max(1, length))];
        int count = delegate.read(source, 0, source.length);
        if (count <= 0) {
            return count;
        }
        pacer.enqueue(new String(source, 0, count));
        TerminalOutputPacer.OutputSegment segment = pacer.poll();
        if (segment == null) {
            return 0;
        }
        return copySegment(segment, buf, offset, length);
    }

    private int copySegment(
            TerminalOutputPacer.OutputSegment segment,
            char[] buf,
            int offset,
            int length) throws IOException {
        sleepBeforeOutput(segment.delayMode());
        return copyText(segment.text(), buf, offset, length);
    }

    private int drainPendingText(char[] buf, int offset, int length) {
        if (pendingText == null || pendingText.isEmpty()) {
            return 0;
        }
        int copied = Math.min(length, pendingText.length());
        pendingText.getChars(0, copied, buf, offset);
        notifyVisibleOutput(pendingText, copied);
        pendingText = copied < pendingText.length() ? pendingText.substring(copied) : null;
        return copied;
    }

    private int copyText(String text, char[] buf, int offset, int length) {
        int copied = Math.min(length, text.length());
        text.getChars(0, copied, buf, offset);
        notifyVisibleOutput(text, copied);
        if (copied < text.length()) {
            pendingText = text.substring(copied);
        }
        return copied;
    }

    private void notifyVisibleOutput(String text, int length) {
        if (containsVisibleOutput(text, length)) {
            visibleOutputListener.run();
        }
    }

    static boolean containsVisibleOutput(String text, int length) {
        if (text == null || length <= 0) {
            return false;
        }
        int end = Math.min(length, text.length());
        int i = 0;
        while (i < end) {
            if (text.charAt(i) == '\u001B') {
                int controlEnd = TerminalOutputPacer.terminalControlSequenceEnd(text, i);
                if (controlEnd > i) {
                    i = Math.min(controlEnd, end);
                    continue;
                }
                i++;
                continue;
            }
            if (!Character.isISOControl(text.charAt(i))) {
                return true;
            }
            i++;
        }
        return false;
    }

    private void sleepBeforeOutput(TerminalOutputPacer.DelayMode delayMode) throws IOException {
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

    static long delayMillis(TerminalOutputPacer.DelayMode delayMode, double animationSpeed) {
        long baseDelayMillis = switch (delayMode) {
            case NONE -> 0L;
            case CHARACTER -> CHARACTER_DELAY_MILLIS;
            case WORD_GAP -> WORD_GAP_DELAY_MILLIS;
            case LINE_START -> LINE_START_DELAY_MILLIS;
            case LINE_BREAK -> LINE_BREAK_DELAY_MILLIS;
            case PUNCTUATION -> PUNCTUATION_DELAY_MILLIS;
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
