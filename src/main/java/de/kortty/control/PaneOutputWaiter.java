package de.kortty.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code pane.wait_output}: polls a {@link PaneReader} until a pattern appears or the deadline passes.
 *
 * <p>Polling, not a {@code DataListener} on the connector: there is nothing to unregister when a
 * connection dies mid-wait, no extra load on the terminal reader thread and no {@code TerminalView}
 * edit. The documented cost is that output which appears and scrolls away inside one poll window can
 * be missed in {@code visible} mode; the default {@code recent} also searches the scrollback and does
 * not have that hole.
 *
 * <p>Matching runs over a {@link DeadlineCharSequence} with the pattern capped at
 * {@link ControlApiProtocol#MAX_REGEX_CHARS} characters and the searched text at
 * {@link ControlApiProtocol#MAX_SEARCH_CHARS}, so catastrophic backtracking can neither outlive the
 * wait nor pin the timer thread.
 *
 * <p>Any thread, never the JavaFX application thread — it blocks until the deadline.
 */
public final class PaneOutputWaiter {

    private static final Logger LOG = LoggerFactory.getLogger(PaneOutputWaiter.class);

    private final ControlSurface surface;

    private final UiDispatcher ui;

    private final ScheduledExecutorService timer;

    /**
     * @param surface the window port, used once to resolve the read handle
     * @param ui the JavaFX marshaller
     * @param timer the shared {@code kortty-control-timer} executor that drives the poll
     */
    public PaneOutputWaiter(ControlSurface surface, UiDispatcher ui, ScheduledExecutorService timer) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /**
     * Waits for output.
     *
     * @param paneId the resolved pane id
     * @param regexOrNull a regular expression, or null
     * @param containsOrNull a literal substring, or null; exactly one of the two is required
     * @param mode which buffer to search
     * @param lines how many rows to read per poll
     * @param timeoutMillis the wait budget, clamped to {@link ControlApiProtocol#WAIT_HARD_CAP_MILLIS}
     * @param pollMillis the poll interval, at least {@link ControlApiProtocol#WAIT_POLL_MIN_MILLIS}
     * @throws ControlApiException {@link ControlErrorCode#INVALID_PARAMS} when neither or both
     *     patterns are given or the pattern is too long, {@link ControlErrorCode#INVALID_REGEX} with
     *     {@code data.detail}, {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#TIMEOUT} with {@code data.waited_millis} and {@code data.last_line}
     */
    public MatchResult await(String paneId, String regexOrNull, String containsOrNull, ReadMode mode,
                             int lines, long timeoutMillis, long pollMillis) throws ControlApiException {
        boolean hasRegex = regexOrNull != null && !regexOrNull.isEmpty();
        boolean hasContains = containsOrNull != null && !containsOrNull.isEmpty();
        if (hasRegex == hasContains) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Exactly one of 'regex' and 'contains' is required",
                Map.of("param", "regex", "hint", "pass either regex or contains, not both"));
        }
        Pattern pattern = hasRegex ? compile(regexOrNull) : null;
        long budget = Math.max(1L, Math.min(timeoutMillis, ControlApiProtocol.WAIT_HARD_CAP_MILLIS));
        Long clampedTo = clampReport(timeoutMillis);
        long poll = Math.max(ControlApiProtocol.WAIT_POLL_MIN_MILLIS, pollMillis);
        int rows = Math.max(1, Math.min(lines, ControlApiProtocol.MAX_READ_LINES));
        ReadMode readMode = mode == null ? ReadMode.RECENT : mode;

        PaneReader reader = UiCalls.await(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
            () -> surface.readerFor(paneId).orElse(null));
        if (reader == null) {
            throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND,
                "No pane " + paneId + " is open", Map.of("pane", paneId));
        }

        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(budget);
        AtomicReference<PaneText> lastText = new AtomicReference<>();
        CompletableFuture<MatchResult> found = new CompletableFuture<>();
        Runnable probe = () -> {
            try {
                PaneText text = read(reader, readMode, rows);
                lastText.set(text);
                MatchResult hit = search(paneId, text, pattern, containsOrNull, deadlineNanos,
                    elapsedMillis(startNanos));
                if (hit != null) {
                    found.complete(hit);
                }
            } catch (DeadlineCharSequence.DeadlineExceeded e) {
                LOG.debug("control-api pane.wait_output gave up matching on pane {} at the deadline", paneId);
            } catch (RuntimeException e) {
                found.completeExceptionally(e);
            }
        };
        ScheduledFuture<?> task = timer.scheduleWithFixedDelay(probe, 0L, poll, TimeUnit.MILLISECONDS);
        try {
            return found.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw timedOut(paneId, lastText.get(), elapsedMillis(startNanos), clampedTo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw timedOut(paneId, lastText.get(), elapsedMillis(startNanos), clampedTo);
        } catch (ExecutionException e) {
            throw new ControlApiException(ControlErrorCode.INTERNAL_ERROR,
                "The wait failed; see the korTTY log", e.getCause());
        } finally {
            task.cancel(false);
        }
    }

    /**
     * What {@code data.clamped_to} carries: the hard cap when the caller asked for more, otherwise
     * null. Factored out so the clamp is testable without actually waiting ten minutes.
     */
    static Long clampReport(long requestedMillis) {
        return requestedMillis > ControlApiProtocol.WAIT_HARD_CAP_MILLIS
            ? ControlApiProtocol.WAIT_HARD_CAP_MILLIS
            : null;
    }

    private static PaneText read(PaneReader reader, ReadMode mode, int rows) {
        PaneText text = reader.read(mode, rows);
        return text == null ? new PaneText(reader.paneId(), mode.wire(), List.of(), 0, 0, false, null, false)
            : text;
    }

    /** The hit for this poll, or null when the text does not contain the pattern yet. */
    private static MatchResult search(String paneId, PaneText text, Pattern pattern, String contains,
                                      long deadlineNanos, long waitedMillis) {
        List<String> lines = text.lines();
        String joined = join(lines);
        if (pattern == null) {
            int index = joined.indexOf(contains);
            if (index < 0) {
                return null;
            }
            int lineIndex = lineIndexOf(joined, index);
            return new MatchResult(paneId, true, contains, lineAt(lines, lineIndex), lineIndex,
                waitedMillis, text);
        }
        Matcher matcher = pattern.matcher(new DeadlineCharSequence(joined, deadlineNanos));
        if (!matcher.find()) {
            return null;
        }
        int lineIndex = lineIndexOf(joined, matcher.start());
        return new MatchResult(paneId, true, joined.substring(matcher.start(), matcher.end()),
            lineAt(lines, lineIndex), lineIndex, waitedMillis, text);
    }

    /** The searched text, capped at {@link ControlApiProtocol#MAX_SEARCH_CHARS} from the end. */
    private static String join(List<String> lines) {
        String joined = String.join("\n", lines);
        int overflow = joined.length() - ControlApiProtocol.MAX_SEARCH_CHARS;
        return overflow <= 0 ? joined : joined.substring(overflow);
    }

    private static int lineIndexOf(String joined, int offset) {
        int index = 0;
        for (int i = 0; i < offset; i++) {
            if (joined.charAt(i) == '\n') {
                index++;
            }
        }
        return index;
    }

    private static String lineAt(List<String> lines, int index) {
        return index >= 0 && index < lines.size() ? lines.get(index) : null;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static Pattern compile(String regex) throws ControlApiException {
        if (regex.length() > ControlApiProtocol.MAX_REGEX_CHARS) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "The pattern is longer than " + ControlApiProtocol.MAX_REGEX_CHARS + " characters",
                Map.of("param", "regex", "max", ControlApiProtocol.MAX_REGEX_CHARS,
                    "length", regex.length()));
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ControlApiException(ControlErrorCode.INVALID_REGEX,
                "The pattern does not compile",
                Map.of("param", "regex", "detail", String.valueOf(e.getMessage())));
        }
    }

    private static ControlApiException timedOut(String paneId, PaneText last, long waitedMillis,
                                                Long clampedTo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pane", paneId);
        data.put("waited_millis", waitedMillis);
        data.put("last_line", lastLine(last));
        if (clampedTo != null) {
            data.put("clamped_to", clampedTo);
        }
        return new ControlApiException(ControlErrorCode.TIMEOUT,
            "The pane produced no matching output within " + waitedMillis + " ms", data);
    }

    private static String lastLine(PaneText text) {
        if (text == null) {
            return null;
        }
        List<String> lines = text.lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (!lines.get(i).isBlank()) {
                return lines.get(i);
            }
        }
        return null;
    }
}
