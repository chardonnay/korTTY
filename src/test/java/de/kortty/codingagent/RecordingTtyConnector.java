package de.kortty.codingagent;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

/** Test double: records every byte written, can report disconnected and can fail the next write. */
public final class RecordingTtyConnector implements TtyConnector {

    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private boolean connected = true;
    private String failNextWriteMessage;
    private int writeCount;

    @Override
    public int read(char[] buf, int offset, int length) {
        return -1;
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (failNextWriteMessage != null) {
            String message = failNextWriteMessage;
            failNextWriteMessage = null;
            throw new IOException(message);
        }
        writeCount++;
        written.writeBytes(bytes);
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void resize(@NotNull TermSize termSize) {
    }

    @Override
    public int waitFor() {
        return 0;
    }

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public String getName() {
        return "recording";
    }

    @Override
    public void close() {
        connected = false;
    }

    /** Every byte written so far, in order. */
    public byte[] written() {
        return written.toByteArray();
    }

    /** {@link #written()} decoded as UTF-8. */
    public String writtenText() {
        return written.toString(StandardCharsets.UTF_8);
    }

    /** Number of successful write calls. */
    public int writeCount() {
        return writeCount;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /** The next write throws an IOException with {@code message}. */
    public void failNextWrite(String message) {
        this.failNextWriteMessage = message;
    }

    /** Forgets the recorded bytes. */
    public void clear() {
        written.reset();
        writeCount = 0;
    }
}
