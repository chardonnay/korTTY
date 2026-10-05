package de.kortty.isolation;

import com.sithtermfx.core.TtyConnector;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;

/**
 * The strict terminal mode around a session: what the session sends passes through a
 * {@link StrictEscapeFilter} before anything else in korTTY reads it. Sits directly on the base
 * connector, so the shell integration, terminal effects and the emulator all see the filtered stream.
 * Input to the session is not touched.
 */
public final class StrictEscapeFilterTtyConnector implements TtyConnector {

    private final TtyConnector delegate;
    private final StrictEscapeFilter filter = new StrictEscapeFilter();
    private final StringBuilder pendingOutput = new StringBuilder();
    private boolean ended;

    public StrictEscapeFilterTtyConnector(TtyConnector delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The connector this one filters. */
    public TtyConnector delegate() {
        return delegate;
    }

    /** How many sequences were dropped so far. */
    public long droppedCount() {
        return filter.droppedCount();
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        // A read never returns 0 while the session goes on: a chunk that was all dropped reads on.
        while (pendingOutput.length() == 0) {
            if (ended) {
                return -1;
            }
            char[] source = new char[Math.max(length, 1024)];
            int count = delegate.read(source, 0, source.length);
            if (count < 0) {
                ended = true;
                filter.flush(pendingOutput);
                if (pendingOutput.length() == 0) {
                    return count;
                }
                break;
            }
            if (count == 0) {
                return 0;
            }
            filter.filter(source, 0, count, pendingOutput);
        }
        int count = Math.min(length, pendingOutput.length());
        pendingOutput.getChars(0, count, buf, offset);
        pendingOutput.delete(0, count);
        return count;
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
    public void resize(@NotNull com.sithtermfx.core.util.TermSize termSize) {
        delegate.resize(termSize);
    }

    @Override
    public int waitFor() throws InterruptedException {
        return delegate.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        return pendingOutput.length() > 0 || delegate.ready();
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
    public boolean init(com.sithtermfx.core.Questioner questioner) {
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
}
