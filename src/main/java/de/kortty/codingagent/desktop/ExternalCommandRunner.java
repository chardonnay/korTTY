package de.kortty.codingagent.desktop;

import de.kortty.platform.FlatpakSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs short-lived external commands ({@code gdbus}, {@code osascript}, {@code notify-send}) with a
 * bounded wait, never through a shell, on a named daemon single-thread executor.
 *
 * <p>The process idiom follows {@code SystemThemeDetector.run}: stderr is merged into stdout, stdin
 * is closed so a child that reads input cannot stall, {@code waitFor} is bounded and a child that
 * outlives the timeout is destroyed forcibly; the output is drained only after the child has
 * exited (the commands emit a few bytes at most, well under the pipe buffer). Any exception yields a
 * failed {@link Result} and a debug log entry — callers never see an exception.
 */
public final class ExternalCommandRunner implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ExternalCommandRunner.class);
    private static final long REAP_MILLIS = 500L;

    /**
     * Outcome of one command.
     *
     * @param exitCode the process exit code, or {@code -1} when the process could not be started,
     *     failed with an exception or timed out
     * @param output the merged stdout/stderr text (stripped), or the exception message on failure
     * @param timedOut whether the process outlived the timeout and was destroyed
     */
    public record Result(int exitCode, String output, boolean timedOut) {

        /** Canonical constructor normalising a {@code null} output to the empty string. */
        public Result {
            output = output == null ? "" : output;
        }

        /** True when the process exited with code {@code 0} within the timeout. */
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private final ExecutorService executor;
    private final long timeoutMillis;
    private final String threadName;

    /**
     * Creates a runner with its own daemon single-thread executor.
     *
     * @param threadName the executor thread name (for example {@code kortty-app-badge})
     * @param timeoutMillis the bounded wait for every command
     */
    public ExternalCommandRunner(String threadName, long timeoutMillis) {
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        this.timeoutMillis = timeoutMillis;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Submits {@code argv} to the runner's executor. The returned future is never joined by the
     * caller on the JavaFX thread; it completes with a failed {@link Result} instead of an exception,
     * also when the runner has already been closed.
     */
    public CompletableFuture<Result> run(List<String> argv) {
        List<String> command = List.copyOf(Objects.requireNonNull(argv, "argv"));
        try {
            return CompletableFuture.supplyAsync(() -> runBlocking(command, timeoutMillis), executor);
        } catch (RejectedExecutionException e) {
            logger.debug("External command '{}' rejected: runner '{}' is closed", first(command), threadName);
            return CompletableFuture.completedFuture(new Result(-1, "runner closed", false));
        }
    }

    /**
     * Runs {@code argv} on the calling thread and waits at most {@code timeoutMillis}. Never call
     * this on the JavaFX thread.
     */
    public static Result runBlocking(List<String> argv, long timeoutMillis) {
        List<String> command = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (command.isEmpty()) {
            return new Result(-1, "empty command", false);
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                // Reap the killed child so it does not linger as a zombie.
                process.waitFor(REAP_MILLIS, TimeUnit.MILLISECONDS);
                logger.debug("External command '{}' timed out after {} ms", first(command), timeoutMillis);
                return new Result(-1, "", true);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return new Result(process.exitValue(), output, false);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.debug("External command '{}' failed: {}", first(command), e.toString());
            return new Result(-1, String.valueOf(e.getMessage()), false);
        }
    }

    /**
     * Prefixes {@code argv} with {@code flatpak-spawn --host --watch-bus} when {@code env} carries a
     * {@code FLATPAK_ID}; returns an unmodifiable copy of {@code argv} otherwise. The Flatpak sandbox
     * has neither a session-bus socket nor the notification talk-name, so the commands run on the
     * host through the already granted {@code org.freedesktop.Flatpak} portal.
     */
    public static List<String> hostAware(List<String> argv, Map<String, String> env) {
        return FlatpakSupport.hostCommand(Objects.requireNonNull(argv, "argv"), null, env);
    }

    /** Stops the executor without waiting for a running command. */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String first(List<String> command) {
        return command.isEmpty() ? "" : command.get(0);
    }
}
