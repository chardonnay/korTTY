package de.kortty.control;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Test double for {@link PaneReader}: one scripted {@link PaneText} per {@link ReadMode}. */
final class FakePaneReader implements PaneReader {

    private final String paneId;

    private final Map<ReadMode, List<String>> lines = new EnumMap<>(ReadMode.class);

    private final List<String> reads = new ArrayList<>();

    private boolean open = true;

    private int columns = 80;

    private int rows = 24;

    FakePaneReader(String paneId) {
        this.paneId = paneId;
    }

    void setLines(ReadMode mode, List<String> text) {
        lines.put(mode, List.copyOf(text));
    }

    void setOpen(boolean open) {
        this.open = open;
    }

    void setGeometry(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    List<String> reads() {
        return List.copyOf(reads);
    }

    @Override
    public String paneId() {
        return paneId;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public PaneText read(ReadMode mode, int maxLines) {
        reads.add(mode.wire() + ":" + maxLines);
        if (!open) {
            return new PaneText(paneId, mode.wire(), List.of(), 0, 0, false, null, false);
        }
        List<String> text = lines.getOrDefault(mode, List.of());
        List<String> kept = text.size() <= maxLines
            ? text
            : List.copyOf(text.subList(text.size() - maxLines, text.size()));
        return new PaneText(paneId, mode.wire(), kept, columns, rows, false, null, false);
    }
}
