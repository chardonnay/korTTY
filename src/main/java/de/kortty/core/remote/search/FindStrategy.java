package de.kortty.core.remote.search;

import de.kortty.core.remote.RemoteCommandCancellation;
import de.kortty.core.remote.RemoteCommandCancelledException;
import de.kortty.core.remote.RemoteCommandRunner;
import de.kortty.core.remote.RemoteCommandTimeoutException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs {@code find} over an exec channel (see {@link FindCommandBuilder}) and parses its
 * NUL-separated output as it arrives. At the result limit the stdout sink says stop, and the
 * runner closes the channel, which ends {@code find} on the server.
 */
public final class FindStrategy {

    /** Hard cap on the bytes read from {@code find}; far above 5 000 paths. */
    static final long OUTPUT_CAP = 256L * 1024 * 1024;
    /** One path longer than this is not a real path; it is dropped. */
    private static final int MAX_PATH_BYTES = 64 * 1024;

    private final RemoteCommandRunner runner;

    public FindStrategy(RemoteCommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * The outcome of one {@code find} run.
     *
     * @param outcome how it ended
     * @param exitCode the remote exit status, -1 when the channel was closed early
     */
    public record Run(RemoteSearchOutcome outcome, int exitCode) { }

    /**
     * Searches with {@code find}.
     *
     * @throws IOException when the exec channel fails (not on cancel or time limit, which end
     *                     the search with the hits found so far)
     */
    public Run search(RemoteSearchRequest request, Consumer<List<RemoteSearchHit>> batches,
                      RemoteCommandCancellation cancellation) throws IOException {
        HitBatcher batcher = new HitBatcher(batches);
        NulParser parser = new NulParser(request, batcher);
        RemoteCommandRunner.Request command = RemoteCommandRunner.Request.plain(request.findCommand())
            .timeout(request.timeout())
            .outputCap(OUTPUT_CAP)
            .cancellation(cancellation)
            .stdoutSink(parser::accept);
        RemoteSearchOutcome.StopReason stop;
        int exitCode = -1;
        try {
            RemoteCommandRunner.Result result = runner.run(command);
            exitCode = result.exitCode();
            if (result.stoppedBySink() || parser.limitReached()) {
                stop = RemoteSearchOutcome.StopReason.RESULT_LIMIT;
            } else if (result.outputTruncated()) {
                stop = RemoteSearchOutcome.StopReason.RESULT_LIMIT;
            } else {
                parser.finish();
                stop = parser.limitReached()
                    ? RemoteSearchOutcome.StopReason.RESULT_LIMIT
                    : RemoteSearchOutcome.StopReason.COMPLETED;
            }
        } catch (RemoteCommandCancelledException e) {
            stop = RemoteSearchOutcome.StopReason.CANCELLED;
        } catch (RemoteCommandTimeoutException e) {
            stop = RemoteSearchOutcome.StopReason.TIME_LIMIT;
        } finally {
            batcher.flush();
        }
        return new Run(new RemoteSearchOutcome(RemoteSearchOutcome.Strategy.FIND, batcher.count(), stop), exitCode);
    }

    /** Splits stdout at NUL bytes and turns each path into a hit; runs on MINA's I/O thread. */
    static final class NulParser {
        private final RemoteSearchRequest request;
        private final HitBatcher batcher;
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();
        private boolean oversized;
        private volatile boolean limitReached;

        NulParser(RemoteSearchRequest request, HitBatcher batcher) {
            this.request = request;
            this.batcher = batcher;
        }

        synchronized boolean accept(byte[] data, int offset, int length) {
            int start = offset;
            int end = offset + length;
            for (int i = offset; i < end; i++) {
                if (data[i] == 0) {
                    append(data, start, i - start);
                    record();
                    if (limitReached) {
                        return false;
                    }
                    start = i + 1;
                }
            }
            append(data, start, end - start);
            return true;
        }

        /** Output that ended without a final NUL is not a complete path; it is dropped. */
        synchronized void finish() {
            current.reset();
        }

        boolean limitReached() {
            return limitReached;
        }

        private void append(byte[] data, int offset, int length) {
            if (length <= 0 || oversized) {
                return;
            }
            if (current.size() + length > MAX_PATH_BYTES) {
                oversized = true;
                current.reset();
                return;
            }
            current.write(data, offset, length);
        }

        private void record() {
            if (oversized) {
                oversized = false;
                current.reset();
                return;
            }
            String path = current.toString(StandardCharsets.UTF_8);
            current.reset();
            if (path.isEmpty() || path.equals(request.root()) || !path.startsWith("/")) {
                return;
            }
            RemoteSearchHit hit = RemoteSearchHit.of(request.root(), path, RemoteSearchHit.Kind.UNKNOWN);
            if (!request.nameMatcher().test(hit.name())) {
                // find's -iname is a superset; the exact filter decides.
                return;
            }
            batcher.add(hit);
            if (batcher.count() >= request.maxResults()) {
                limitReached = true;
            }
        }
    }
}
