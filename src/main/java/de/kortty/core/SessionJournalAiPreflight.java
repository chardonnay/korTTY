package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests the AI connection before a session journal relies on it: a journal whose summaries can
 * never be written is pointless, so the user is told — and asked — up front instead of finding an
 * empty journal afterwards. A successful test is remembered for a while per profile so that every
 * new tab or job run does not ping the provider again.
 */
public final class SessionJournalAiPreflight {

    /** Outcome of a test; {@code message} explains a failure in words worth showing. */
    public record Result(boolean ok, String profileName, String message) {
        public static Result success(String profileName) {
            return new Result(true, profileName, null);
        }

        public static Result failure(String profileName, String message) {
            return new Result(false, profileName, message);
        }
    }

    /** Local models may load first; give them time before calling the test failed. */
    static final Duration TIMEOUT = Duration.ofSeconds(90);
    static final Duration SUCCESS_TTL = Duration.ofMinutes(10);

    private static final Map<String, Instant> recentSuccesses = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SessionJournal-AiPreflight");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Clock clock = Clock.systemUTC();

    private SessionJournalAiPreflight() {
    }

    /**
     * True when a journal with this capture configuration would call the AI at all — only then is
     * a test worth running. With AI forbidden or summaries switched off the journal records raw
     * activity by design.
     */
    public static boolean aiWouldRun(SessionJournalConfig config, GlobalSettings settings) {
        return de.kortty.policy.PolicyManager.effective().sessionJournalAiSummariesAllowed()
            && (settings == null || settings.isSessionJournalAiSummariesEnabled())
            && (config == null || config.isAiSummariesEnabled());
    }

    /** Runs the test off the calling thread. */
    public static CompletableFuture<Result> checkAsync(SessionJournalAiSupport.AiInvoker invoker) {
        return CompletableFuture.supplyAsync(() -> check(invoker), executor);
    }

    /** Runs the test on the calling thread (bounded by {@link #TIMEOUT}); never throws. */
    public static Result check(SessionJournalAiSupport.AiInvoker invoker) {
        if (invoker == null) {
            return Result.failure(null, "No AI profile is available for the session journal.");
        }
        String profileName = profileName(invoker);
        String key = cacheKey(invoker);
        Instant last = key != null ? recentSuccesses.get(key) : null;
        if (last != null && Duration.between(last, clock.instant()).compareTo(SUCCESS_TTL) < 0) {
            return Result.success(profileName);
        }
        Future<?> future = executor.submit(() -> {
            invoker.testConnection();
            return null;
        });
        try {
            future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (key != null) {
                recentSuccesses.put(key, clock.instant());
            }
            return Result.success(profileName);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Result.failure(profileName, "The AI did not answer within " + TIMEOUT.toSeconds() + " seconds.");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Result.failure(profileName, cause.getMessage() != null ? cause.getMessage() : cause.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(profileName, "The AI connection test was interrupted.");
        }
    }

    /** Forgets remembered successes — after the user changed AI profiles, or in tests. */
    public static void forgetSuccesses() {
        recentSuccesses.clear();
    }

    static void setClock(Clock testClock) {
        clock = testClock != null ? testClock : Clock.systemUTC();
    }

    private static String profileName(SessionJournalAiSupport.AiInvoker invoker) {
        try {
            var profile = invoker.profile();
            return profile != null ? profile.getName() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String cacheKey(SessionJournalAiSupport.AiInvoker invoker) {
        try {
            var profile = invoker.profile();
            if (profile == null || profile.getId() == null) {
                return null;
            }
            return profile.getId() + "|" + profile.getApiUrl() + "|" + profile.getModel() + "|" + profile.getConnectionMode();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
