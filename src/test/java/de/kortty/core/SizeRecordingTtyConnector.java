package de.kortty.core;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.PixelSize;
import com.sithtermfx.core.util.TermSize;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Test double: records every resize as {@code "COLSxROWS"} or {@code "COLSxROWS@WIDTHxHEIGHTpx"}, so
 * a wrapper that drops the pixel size (and falls back to {@code resize(TermSize)}) shows up.
 */
public final class SizeRecordingTtyConnector implements TtyConnector {

    private final List<String> resizes = new ArrayList<>();

    public List<String> resizes() {
        return resizes;
    }

    @Override
    public void resize(@NotNull TermSize termSize) {
        resizes.add(termSize.getColumns() + "x" + termSize.getRows());
    }

    @Override
    public void resize(@NotNull TermSize termSize, @NotNull PixelSize pixelSize) {
        resizes.add(termSize.getColumns() + "x" + termSize.getRows()
            + "@" + pixelSize.getWidth() + "x" + pixelSize.getHeight() + "px");
    }

    @Override
    public int read(char[] buf, int offset, int length) {
        return -1;
    }

    @Override
    public void write(byte[] bytes) {
    }

    @Override
    public void write(String string) {
    }

    @Override
    public boolean isConnected() {
        return true;
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
        return "size-recording";
    }

    @Override
    public void close() {
    }
}
