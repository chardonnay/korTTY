package de.kortty.core.remote.search;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * What a recursive remote search looks for, with its limits (D17: depth 10, 5 000 results, 60 s,
 * one file system by default).
 *
 * @param root the absolute folder to search in
 * @param text the search-field text
 * @param glob whether {@code text} is a glob that parses; otherwise a substring
 * @param nameMatcher the exact name filter, the same one the folder listing applies
 * @param maxDepth how many folder levels below the root are searched
 * @param maxResults the result limit; the search stops there
 * @param timeout the time limit
 * @param maxEntries how many entries the SFTP walk reads at most
 * @param sameFilesystem whether {@code find} stays on the root's file system ({@code -xdev})
 */
public record RemoteSearchRequest(String root, String text, boolean glob, Predicate<String> nameMatcher,
                                  int maxDepth, int maxResults, Duration timeout, long maxEntries,
                                  boolean sameFilesystem) {

    public static final int DEFAULT_MAX_DEPTH = 10;
    public static final int DEFAULT_MAX_RESULTS = 5_000;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final long DEFAULT_MAX_ENTRIES = 200_000;

    public RemoteSearchRequest {
        root = FindCommandBuilder.normalizeRoot(root);
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("The search text cannot be empty.");
        }
        Objects.requireNonNull(nameMatcher, "nameMatcher");
        Objects.requireNonNull(timeout, "timeout");
        if (maxDepth < 1 || maxResults < 1 || maxEntries < 1) {
            throw new IllegalArgumentException("Search limits must be positive.");
        }
    }

    /** A request with the default limits, staying on one file system. */
    public static RemoteSearchRequest of(String root, String text, boolean glob, Predicate<String> nameMatcher) {
        return new RemoteSearchRequest(root, text, glob, nameMatcher, DEFAULT_MAX_DEPTH, DEFAULT_MAX_RESULTS,
            DEFAULT_TIMEOUT, DEFAULT_MAX_ENTRIES, true);
    }

    public RemoteSearchRequest withSameFilesystem(boolean value) {
        return new RemoteSearchRequest(root, text, glob, nameMatcher, maxDepth, maxResults, timeout, maxEntries, value);
    }

    public RemoteSearchRequest withLimits(int depth, int results, Duration time, long entries) {
        return new RemoteSearchRequest(root, text, glob, nameMatcher, depth, results, time, entries, sameFilesystem);
    }

    /** The {@code find} command for this request. */
    String findCommand() {
        return FindCommandBuilder.build(root, FindCommandBuilder.namePattern(text, glob), maxDepth, sameFilesystem);
    }
}
