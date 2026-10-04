package de.kortty.core.remote.search;

import de.kortty.core.remote.RemoteCommandCancellation;
import de.kortty.core.remote.RemoteCommandException;
import de.kortty.core.remote.RemoteCommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Recursive remote search (D17): {@code find} over an exec channel when the server offers one and
 * has {@code find}, otherwise a breadth-first SFTP walk. Both stream hits in batches of 100 to the
 * caller's consumer (on the calling thread or an SSH I/O thread, never the FX thread), stop at the
 * result, depth and time limits, never follow symlinks and stop promptly on cancel.
 *
 * <p>The search is read-only and stays on the server, so it is not gated by the file-transfer
 * policy (D6).
 */
public final class RemoteSearchService {

    private static final Logger logger = LoggerFactory.getLogger(RemoteSearchService.class);
    static final String PROBE_COMMAND = "command -v find >/dev/null 2>&1";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    private final RemoteCommandRunner runner;
    private final RemoteTreeReader reader;

    /**
     * @param runner exec channels of the session, or {@code null} when the session has none
     * @param reader SFTP access for the walk
     */
    public RemoteSearchService(RemoteCommandRunner runner, RemoteTreeReader reader) {
        this.runner = runner;
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * Searches; see the class comment.
     *
     * @throws IOException when neither strategy can read the search folder
     */
    public RemoteSearchOutcome search(RemoteSearchRequest request, Consumer<List<RemoteSearchHit>> batches,
                                      RemoteCommandCancellation cancellation) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(batches, "batches");
        Objects.requireNonNull(cancellation, "cancellation");
        if (runner != null && findAvailable(cancellation)) {
            int[] delivered = {0};
            Consumer<List<RemoteSearchHit>> counting = batch -> {
                delivered[0] += batch.size();
                batches.accept(batch);
            };
            try {
                FindStrategy.Run run = new FindStrategy(runner).search(request, counting, cancellation);
                RemoteSearchOutcome outcome = run.outcome();
                boolean failedEmpty = outcome.results() == 0
                    && outcome.stop() == RemoteSearchOutcome.StopReason.COMPLETED
                    && run.exitCode() != 0;
                if (!failedEmpty) {
                    return outcome;
                }
                // find failed without a single hit (an unknown option on a minimal find, or an
                // unreadable root): the walk gives the real answer or the real error.
                logger.info("Remote search: find exited with {} and no results; walking over SFTP", run.exitCode());
            } catch (IOException e) {
                if (delivered[0] > 0) {
                    throw e;
                }
                logger.info("Remote search: find could not run ({}); walking over SFTP",
                    e instanceof RemoteCommandException ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        if (cancellation.isCancelled()) {
            return new RemoteSearchOutcome(RemoteSearchOutcome.Strategy.SFTP_WALK, 0,
                RemoteSearchOutcome.StopReason.CANCELLED);
        }
        return new SftpWalkStrategy(reader).search(request, batches, cancellation);
    }

    /** Whether an exec channel opens and the server has {@code find}. */
    private boolean findAvailable(RemoteCommandCancellation cancellation) {
        try {
            RemoteCommandRunner.Result probe = runner.run(RemoteCommandRunner.Request.plain(PROBE_COMMAND)
                .timeout(PROBE_TIMEOUT).outputCap(4096).cancellation(cancellation));
            return probe.isSuccess();
        } catch (IOException | RuntimeException e) {
            logger.info("Remote search: no exec channel for find ({}); walking over SFTP",
                e.getClass().getSimpleName());
            return false;
        }
    }
}
