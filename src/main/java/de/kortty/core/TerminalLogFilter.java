package de.kortty.core;

import de.kortty.model.TerminalLogConfig;

import java.util.Locale;
import java.util.Objects;

/**
 * The criteria the Logfile Manager's list is narrowed by. Every criterion that is set must hold; an
 * unset one (null, or blank text) accepts everything.
 *
 * <p>The time range is matched by overlap, not by start: a file that began before the range and
 * was still being written inside it is one the user is looking for, and so is one that ran into
 * it. Dropping it because it started earlier would hide the very entries that prompted the search.</p>
 *
 * @param text        matched case-insensitively against the file name and its folder
 * @param range       the time window; {@code null} or open means no time criterion
 * @param format      only this format; {@code null} for any
 * @param compression only this compression; {@code null} for any
 */
public record TerminalLogFilter(String text, LogTimeRange range, TerminalLogConfig.LogFormat format,
                                TerminalLogNaming.Compression compression) {

    /** A filter that accepts every file. */
    public static TerminalLogFilter none() {
        return new TerminalLogFilter(null, null, null, null);
    }

    /** Whether no criterion is set, so the filter accepts every file and can be left out of the UI. */
    public boolean isEmpty() {
        return needle() == null
            && (range == null || range.isOpen())
            && format == null
            && compression == null;
    }

    /** Whether {@code file} satisfies every criterion that is set. */
    public boolean test(TerminalLogFile file) {
        if (file == null) {
            return false;
        }
        // A contradictory window cannot hold anything; saying so keeps the list from showing files
        // that merely overlap the broken bounds.
        if (range != null && range.isInvalid()) {
            return false;
        }
        String needle = needle();
        if (needle != null && !matchesText(file, needle)) {
            return false;
        }
        if (range != null && !range.overlaps(file.startedAt(), file.endedAt())) {
            return false;
        }
        if (format != null && format != file.format()) {
            return false;
        }
        return compression == null || compression == file.compression();
    }

    /** The trimmed, lower-cased text, or {@code null} when there is nothing to match. */
    private String needle() {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesText(TerminalLogFile file, String needle) {
        String name = Objects.toString(file.name(), "").toLowerCase(Locale.ROOT);
        if (name.contains(needle)) {
            return true;
        }
        String folder = Objects.toString(file.directory(), "").toLowerCase(Locale.ROOT);
        return folder.contains(needle);
    }
}
