package de.kortty.ui;

import de.kortty.memory.MemoryArchive;
import de.kortty.memory.MemoryScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The archived entries of one memory file, as the archive view lists them: newest first, with the date, the source and the
 * text. Pure logic, so the filter and the order are testable without a window.
 */
final class AiMemoryArchiveRows {

    /**
     * One line of the archive view.
     *
     * @param key       the archive key of the entry, see {@link MemoryArchive.Archived#key()}
     * @param created   the date the entry was written
     * @param source    where the entry came from, such as {@code chat}
     * @param firstLine the first line of the text, shown in the list
     * @param text      the whole text, shown as the tooltip
     */
    record Row(String key, String created, String source, String firstLine, String text) {
    }

    private AiMemoryArchiveRows() {
    }

    /** The entries of {@code archiveText} that belong to {@code scope}, newest first. */
    static List<Row> forScope(String archiveText, MemoryScope scope) {
        List<Row> rows = new ArrayList<>();
        for (MemoryArchive.Archived archived : MemoryArchive.entries(archiveText)) {
            if (scope.equals(archived.scope())) {
                rows.add(row(archived));
            }
        }
        // The file lists the blocks in the order they were archived: the later ones are the newer ones. Reversed first, the
        // stable sort by date keeps that order among entries of the same day.
        Collections.reverse(rows);
        rows.sort(Comparator.comparing(Row::created, Comparator.reverseOrder()));
        return List.copyOf(rows);
    }

    /** The first line of a text, without its line break. */
    static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).strip();
    }

    private static Row row(MemoryArchive.Archived archived) {
        String body = archived.entry().body();
        return new Row(archived.key(), archived.entry().created(), archived.entry().source(), firstLine(body), body);
    }
}
