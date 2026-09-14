package de.kortty.codingagent;

/**
 * "m:ss" / "h:mm:ss" formatting for time-in-state labels. Values below one hour are cached so a
 * 1-second tick can compare String identity and skip unchanged labels.
 */
public final class DurationText {

    private static final int CACHE_SIZE = 3600;
    private static final String[] CACHE = new String[CACHE_SIZE];

    private DurationText() {
    }

    /** 0 -&gt; "0:00", 61 -&gt; "1:01", 3600 -&gt; "1:00:00"; negative values render as "0:00". */
    public static String mmss(long seconds) {
        long total = Math.max(0L, seconds);
        if (total < CACHE_SIZE) {
            int index = (int) total;
            String cached = CACHE[index];
            if (cached == null) {
                cached = format(total);
                CACHE[index] = cached;
            }
            return cached;
        }
        return format(total);
    }

    private static String format(long total) {
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long secs = total % 60L;
        if (hours > 0) {
            return hours + ":" + twoDigits(minutes) + ":" + twoDigits(secs);
        }
        return minutes + ":" + twoDigits(secs);
    }

    private static String twoDigits(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }
}
