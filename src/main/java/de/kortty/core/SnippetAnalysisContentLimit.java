package de.kortty.core;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The one place that decides how much script text a stored Full-code analysis may hold, and how
 * large its file may grow. Every writer of a content field
 * ({@link SnippetAnalysisRecord#capContent}, {@link SnippetAnalysisRecord#compact()}) and the store
 * ({@link SnippetAnalysisStore}) asks here; nothing else knows the numbers.
 *
 * <h2>Units</h2>
 * The limit is <b>bytes of UTF-8</b> per content field (the analysed script, the text an apply
 * started from, a proposed result, the accepted editor text, a checkpoint): a 1 MB script of
 * multi-byte characters therefore counts by its real size, not by its {@code String.length()}.
 * A text of exactly the limit is stored; one byte more is dropped as a whole (never cut), leaving
 * its hash so the analysis still knows which text it was about.
 *
 * <h2>Effective limit</h2>
 * {@code effective = min(user setting, policy maximum)}, where the user setting is
 * {@link #DEFAULT_BYTES 1 MiB} when unset, {@code 0} means "do not store script text at all", and
 * every other value is clamped to {@link #MIN_BYTES 256 KiB}..{@link #MAX_BYTES 5 MiB} (the hard
 * maximum: a settings file with a larger value reads as 5 MiB). The enterprise policy value is an
 * upper bound the user cannot exceed ({@code 0} forbids storing script text); it is not clamped
 * upwards by the minimum, so an admin may set any cap, including one below {@link #MIN_BYTES}.
 *
 * <h2>Lowering the limit never deletes</h2>
 * Content that was stored earlier stays: {@code compact()} only enforces the {@link #ceiling()}
 * (the policy maximum, else {@link #MAX_BYTES}), so a user lowering the setting affects new
 * analyses only. Only the per-file budget ({@link #contentBudgetBytes()}) may still shed the
 * content of the oldest unprotected runs.
 *
 * <h2>File size</h2>
 * {@link #maxFileBytes()} is {@code max(16 MiB, 12 * MAX_BYTES)} = 60 MiB, deliberately derived
 * from the hard maximum and not from the effective limit: a file written under a higher setting
 * must still load after the user lowered it. A file legitimately holds several distinct blobs of
 * up to the limit (source, before-apply text, proposed result, accepted text, checkpoints), each
 * stored once, plus JSON escaping (about 1.05x for typical scripts, up to 6x for control
 * characters). Twelve times the maximum leaves room for the {@link #contentBudgetBytes() content
 * budget} (half of it, 30 MiB of distinct text) at an escape factor of 2; the store additionally
 * sheds more content when the serialised file would still exceed the cap.
 */
public final class SnippetAnalysisContentLimit {

    /** Default per content field: 1 MiB of UTF-8. */
    public static final long DEFAULT_BYTES = 1024L * 1024;
    /** Smallest non-zero value a user can choose. */
    public static final long MIN_BYTES = 256L * 1024;
    /** Hard maximum, whatever the settings file says (and the ceiling of {@code compact()} without a policy). */
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    /** A stored analysis file is always allowed at least this size. */
    public static final long MIN_FILE_BYTES = 16L * 1024 * 1024;
    /** The file cap is this many times the hard maximum ({@link #MAX_BYTES}). */
    public static final int FILE_BYTES_PER_LIMIT_BYTE = 12;

    /**
     * The values in force.
     *
     * @param effective the limit new content is stored with (bytes; 0 = store no script text)
     * @param ceiling   what already stored content may keep (bytes; at least {@code effective})
     * @param policyMax the enterprise policy maximum, or {@code null} when none applies
     */
    public record Limits(long effective, long ceiling, Long policyMax) {
        public Limits {
            effective = Math.max(0L, effective);
            ceiling = Math.max(effective, ceiling);
        }

        /** Whether the policy forbids storing script text altogether. */
        public boolean forbiddenByPolicy() {
            return policyMax != null && policyMax == 0L;
        }
    }

    private static final Limits DEFAULT_LIMITS = new Limits(DEFAULT_BYTES, MAX_BYTES, null);
    private static final Map<String, Long> SIZE_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int SIZE_CACHE_MIN_CHARS = 64 * 1024;

    private static volatile Supplier<Limits> source = () -> DEFAULT_LIMITS;
    private static volatile long fileBytesOverride;

    private SnippetAnalysisContentLimit() {
    }

    /** The application installs where the values come from (settings + policy); read on every use. */
    public static void install(Supplier<Limits> supplier) {
        source = supplier != null ? supplier : () -> DEFAULT_LIMITS;
    }

    /** Back to the defaults (tests). */
    public static void reset() {
        source = () -> DEFAULT_LIMITS;
        fileBytesOverride = 0L;
    }

    /** The values in force now; a failing supplier reads as the defaults. */
    public static Limits limits() {
        try {
            Limits limits = source.get();
            return limits != null ? limits : DEFAULT_LIMITS;
        } catch (RuntimeException e) {
            return DEFAULT_LIMITS;
        }
    }

    /** The limit new content is stored with, in bytes of UTF-8. */
    public static long current() {
        return limits().effective();
    }

    /** What already stored content may keep, in bytes of UTF-8. */
    public static long ceiling() {
        return limits().ceiling();
    }

    /** {@code min(user setting, policy maximum)}; see the class comment. */
    public static Limits compute(Long userBytes, Long policyMaxBytes) {
        long user = normalizeUser(userBytes);
        Long policy = policyMaxBytes != null && policyMaxBytes >= 0 ? policyMaxBytes : null;
        long effective = policy != null ? Math.min(user, policy) : user;
        long ceiling = policy != null ? Math.min(MAX_BYTES, policy) : MAX_BYTES;
        return new Limits(effective, ceiling, policy);
    }

    /** {@code null}/negative = default (1 MiB), {@code 0} = off, else clamped to {@link #MIN_BYTES}..{@link #MAX_BYTES}. */
    public static long normalizeUser(Long value) {
        if (value == null || value < 0) {
            return DEFAULT_BYTES;
        }
        if (value == 0) {
            return 0L;
        }
        return Math.max(MIN_BYTES, Math.min(MAX_BYTES, value));
    }

    /** The largest analysis file that is read or written (independent of the current setting). */
    public static long maxFileBytes() {
        long override = fileBytesOverride;
        return override > 0 ? override : Math.max(MIN_FILE_BYTES, FILE_BYTES_PER_LIMIT_BYTE * MAX_BYTES);
    }

    /** Tests only: a smaller file cap so the shedding path can be exercised with small data; 0 clears it. */
    static void overrideMaxFileBytesForTests(long bytes) {
        fileBytesOverride = bytes;
    }

    /** Distinct content one file may hold: half the file cap (the rest is JSON escaping and structure). */
    public static long contentBudgetBytes() {
        return maxFileBytes() / 2;
    }

    /** Whether {@code text} may be stored under {@code limit} (null never can; a limit of 0 stores nothing). */
    public static boolean fits(String text, long limit) {
        if (text == null || limit <= 0) {
            return false;
        }
        int chars = text.length();
        if (chars > limit) {
            return false; // at least one byte per char
        }
        if (chars * 3L <= limit) {
            return true; // at most three bytes per UTF-16 char
        }
        return utf8Length(text) <= limit;
    }

    /** The UTF-8 size of {@code text}; large texts are remembered while the string is alive. */
    public static long utf8Length(String text) {
        if (text == null) {
            return 0L;
        }
        if (text.length() < SIZE_CACHE_MIN_CHARS) {
            return computeUtf8Length(text);
        }
        Long cached = SIZE_CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        long size = computeUtf8Length(text);
        SIZE_CACHE.put(text, size);
        return size;
    }

    private static long computeUtf8Length(String text) {
        long bytes = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3; // BMP char, or a lone surrogate (encoded as '?' = 1 byte by getBytes; 3 is the safe side)
            }
        }
        return bytes;
    }

    /** For tests: the exact size {@link String#getBytes} produces. */
    static long referenceUtf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
