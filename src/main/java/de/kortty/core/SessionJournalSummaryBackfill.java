package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Opt-in catch-up summarization: finds closed journals that were never summarized (recorded
 * while AI summaries were off or no model was reachable) and runs the regular
 * {@link SessionJournalSummarizer} close pass over them, one journal at a time. Sequential on
 * purpose — the summarizer's single AI executor is the bottleneck anyway, and one journal at a
 * time keeps the progress display honest and cancellation prompt. Failures are collected per
 * journal instead of aborting the run.
 */
public final class SessionJournalSummaryBackfill {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalSummaryBackfill.class);

    /** {@code done} counts finished journals (successful or not) out of {@code total}. */
    public record Progress(int done, int total, String currentTitle) {
    }

    /** {@code failedTitles} lists journals whose pass produced no summaries. */
    public record Outcome(int processed, List<String> failedTitles, boolean cancelled) {
    }

    private final SessionJournalService service;
    private final SessionJournalSummarizer summarizer;

    public SessionJournalSummaryBackfill(SessionJournalService service,
                                         SessionJournalSummarizer summarizer) {
        this.service = service;
        this.summarizer = summarizer;
    }

    /** Closed journals with captured output but no summarization progress at all. */
    public List<SessionJournalMeta> findCandidates(GlobalSettings settings) {
        try {
            List<SessionJournalMeta> candidates = new ArrayList<>();
            for (SessionJournalMeta meta : service.listJournals(settings)) {
                if (!meta.isLive() && meta.getLastSummarizedSeq() == 0 && meta.getLogEntryCount() > 0) {
                    candidates.add(meta);
                }
            }
            return candidates;
        } catch (Exception e) {
            logger.warn("Could not list journals for summary backfill: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Runs the backfill over {@code candidates}, reporting progress before each journal. Call
     * from a background thread — each close pass blocks until its AI calls finish. Never throws.
     */
    public Outcome run(List<SessionJournalMeta> candidates, Consumer<Progress> onProgress,
                       BooleanSupplier cancelled) {
        List<String> failedTitles = new ArrayList<>();
        int done = 0;
        for (SessionJournalMeta meta : candidates) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return new Outcome(done, List.copyOf(failedTitles), true);
            }
            if (onProgress != null) {
                onProgress.accept(new Progress(done, candidates.size(), titleOf(meta)));
            }
            try {
                summarizer.backfillClosedJournal(meta.getDirectory()).get();
                // The close pass logs and swallows its own errors; the persisted progress marker
                // is the reliable signal for whether anything was actually summarized.
                boolean advanced = service.loadDocument(meta.getDirectory())
                    .getMeta().getLastSummarizedSeq() > 0;
                if (!advanced) {
                    failedTitles.add(titleOf(meta));
                }
            } catch (Exception e) {
                logger.warn("Summary backfill failed for {}: {}",
                    meta.getDirectory() != null ? meta.getDirectory().getFileName() : "?",
                    e.getMessage());
                failedTitles.add(titleOf(meta));
            }
            done++;
        }
        if (onProgress != null) {
            onProgress.accept(new Progress(done, candidates.size(), null));
        }
        return new Outcome(done, List.copyOf(failedTitles), false);
    }

    private static String titleOf(SessionJournalMeta meta) {
        if (meta.getTitle() != null && !meta.getTitle().isBlank()) {
            return meta.getTitle();
        }
        return meta.getConnectionName() != null ? meta.getConnectionName() : "?";
    }
}
