package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.TransferBatch;
import de.kortty.core.sftp.transfer.TransferDirection;
import de.kortty.core.sftp.transfer.TransferItem;
import de.kortty.core.sftp.transfer.TransferState;
import de.kortty.ui.I18n;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What one row of the transfer list shows, and the texts of the status bar and the batch summary,
 * computed without JavaFX so they can be tested headless.
 *
 * @param name      the item's name; a file inside a folder item shows its path below the top-level
 *                  item ({@code folder/sub/file.txt})
 * @param direction "Upload" or "Download"
 * @param state     the state, ready to show
 * @param progress  0..1, or {@code -1} while the size is unknown
 * @param bytes     "1.5 MB of 3.0 MB", or only the bytes so far while the size is unknown
 * @param speed     "1.2 MB/s" while running, else empty
 * @param eta       "about 3 min" while running and known, else empty
 * @param message   why the item failed or was skipped, or {@code null}
 */
public record SftpTransferRowModel(
        String name,
        String direction,
        String state,
        double progress,
        String bytes,
        String speed,
        String eta,
        String message) {

    /** How many failed files the batch summary names before it says "and n more". */
    static final int SUMMARY_LINES = 15;

    public static SftpTransferRowModel of(TransferItem item) {
        return of(item, Locale.getDefault());
    }

    public static SftpTransferRowModel of(TransferItem item, Locale locale) {
        TransferState state = item.state();
        long done = item.bytesDone();
        long total = item.totalBytes();
        boolean running = state == TransferState.RUNNING;
        return new SftpTransferRowModel(
            displayName(item),
            direction(item.direction()),
            state(state, item.resumedFrom() > 0),
            progress(state, done, total),
            bytesText(done, total, locale),
            running ? speed(item.bytesPerSecond(), locale) : "",
            running ? eta(item.eta()) : "",
            item.message());
    }

    /** The item's name below its top-level item, with '/' between the folder names. */
    static String displayName(TransferItem item) {
        StringBuilder name = new StringBuilder(item.targetName());
        for (TransferItem parent = item.parent(); parent != null; parent = parent.parent()) {
            name.insert(0, parent.targetName() + "/");
        }
        return name.toString();
    }

    /** "1.5 MB", with a dash for an unknown size (negative). Units are binary, 1 KB = 1024 B. */
    public static String size(long bytes, Locale locale) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(locale, "%.1f %s", value, units[unit]);
    }

    /** "1.5 MB of 3.0 MB", or "1.5 MB" while the size is unknown. */
    static String bytesText(long done, long total, Locale locale) {
        if (total < 0) {
            return size(Math.max(0, done), locale);
        }
        return I18n.get("sftp.queue.bytesOf", size(Math.max(0, done), locale), size(total, locale));
    }

    /** "1.2 MB/s"; empty when nothing moves. */
    static String speed(double bytesPerSecond, Locale locale) {
        if (!(bytesPerSecond >= 1)) {
            return "";
        }
        return I18n.get("sftp.queue.speed", size((long) bytesPerSecond, locale));
    }

    /**
     * Time left, rounded to what a person reads: "less than a minute", "about 3 min",
     * "about 1 h 5 min". Empty while unknown.
     */
    static String eta(Duration eta) {
        if (eta == null || eta.isNegative()) {
            return "";
        }
        long seconds = eta.toSeconds();
        if (seconds < 60) {
            return I18n.get("sftp.queue.eta.lessThanMinute");
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return I18n.get("sftp.queue.eta.minutes", String.valueOf(minutes));
        }
        return I18n.get("sftp.queue.eta.hours", String.valueOf(minutes / 60), String.valueOf(minutes % 60));
    }

    /** 0..1, 1 when done, {@code -1} while the size is unknown. */
    static double progress(TransferState state, long done, long total) {
        if (state == TransferState.DONE) {
            return 1;
        }
        if (total < 0) {
            return state == TransferState.RUNNING || state == TransferState.EXPANDING ? -1 : 0;
        }
        if (total == 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, done / (double) total));
    }

    static String direction(TransferDirection direction) {
        return direction == TransferDirection.UPLOAD
            ? I18n.get("sftp.queue.direction.upload")
            : I18n.get("sftp.queue.direction.download");
    }

    static String state(TransferState state, boolean resumed) {
        return switch (state) {
            case QUEUED -> I18n.get("sftp.queue.state.queued");
            case EXPANDING -> I18n.get("sftp.queue.state.expanding");
            case RUNNING -> resumed ? I18n.get("sftp.queue.state.resumed") : I18n.get("sftp.queue.state.running");
            case DONE -> I18n.get("sftp.queue.state.done");
            case FAILED -> I18n.get("sftp.queue.state.failed");
            case SKIPPED -> I18n.get("sftp.queue.state.skipped");
            case CANCELLED -> I18n.get("sftp.queue.state.cancelled");
        };
    }

    // ------------------------------------------------------------------ status bar and summary

    /** Files done and in total over some batches, and the current rate. */
    public record Totals(int filesDone, int files, int active, double bytesPerSecond) {

        /** Over every item of {@code batches}; folders count by their files. */
        public static Totals of(List<TransferBatch> batches) {
            int done = 0;
            int files = 0;
            int active = 0;
            double rate = 0;
            for (TransferBatch batch : batches) {
                for (TransferItem item : batch.allItems()) {
                    if (item.state().isActive()) {
                        active++;
                    }
                    if (item.kind() != TransferItem.Kind.FILE) {
                        continue;
                    }
                    files++;
                    if (item.state() == TransferState.DONE) {
                        done++;
                    }
                    rate += item.bytesPerSecond();
                }
            }
            return new Totals(done, files, active, rate);
        }
    }

    /**
     * The status bar text while transfers run, or {@code null} when nothing is active (the bar then
     * keeps what the last batch said).
     */
    public static String status(Totals totals, Locale locale) {
        if (totals.active() == 0) {
            return null;
        }
        String speed = speed(totals.bytesPerSecond(), locale);
        if (speed.isEmpty()) {
            return I18n.get("sftp.queue.status.active", String.valueOf(totals.filesDone()),
                String.valueOf(totals.files()));
        }
        return I18n.get("sftp.queue.status.activeSpeed", String.valueOf(totals.filesDone()),
            String.valueOf(totals.files()), speed);
    }

    /**
     * What the status bar says when {@code batch} finished: "Upload complete: 3", or "2/3" when not
     * every top-level item arrived. Skipped items count as complete: they were left out on purpose.
     */
    public static String batchFinished(TransferBatch batch) {
        List<TransferItem> items = batch.items();
        int complete = 0;
        for (TransferItem item : items) {
            if (item.state() == TransferState.DONE || item.state() == TransferState.SKIPPED) {
                complete++;
            }
        }
        String count = complete == items.size() ? String.valueOf(complete) : complete + "/" + items.size();
        return batch.direction() == TransferDirection.UPLOAD
            ? I18n.get("sftp.uploadComplete", count)
            : I18n.get("sftp.downloadComplete", count);
    }

    /**
     * The text of the summary shown once a batch finished with failed files or linked folders that
     * were not followed, or {@code null} when there is nothing to report. A cancelled batch reports
     * nothing: the user stopped it.
     */
    public static String batchSummary(TransferBatch batch) {
        List<TransferItem> failed = new ArrayList<>();
        int files = 0;
        for (TransferItem item : batch.allItems()) {
            if (item.kind() == TransferItem.Kind.FILE) {
                files++;
                if (item.state() == TransferState.FAILED) {
                    failed.add(item);
                }
            } else if (item.state() == TransferState.FAILED && !item.isExpanded()) {
                // A folder that could not even be listed has no file rows of its own.
                failed.add(item);
            }
        }
        List<String> links = batch.skippedLinks();
        if (failed.isEmpty() && links.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (!failed.isEmpty()) {
            text.append(I18n.get("sftp.queue.summary.failed", String.valueOf(failed.size()),
                String.valueOf(Math.max(files, failed.size()))));
            appendLines(text, failed.stream()
                .map(item -> item.message() == null || item.message().isBlank()
                    ? displayName(item)
                    : displayName(item) + ": " + item.message())
                .toList());
            text.append('\n').append(I18n.get("sftp.queue.summary.retryHint"));
        }
        if (!links.isEmpty()) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(I18n.get("sftp.queue.summary.linkedFolders"));
            appendLines(text, links);
        }
        return text.toString();
    }

    private static void appendLines(StringBuilder text, List<String> lines) {
        int shown = Math.min(lines.size(), SUMMARY_LINES);
        for (int i = 0; i < shown; i++) {
            text.append("\n• ").append(lines.get(i));
        }
        if (lines.size() > shown) {
            text.append("\n").append(I18n.get("sftp.queue.summary.more", String.valueOf(lines.size() - shown)));
        }
    }
}
