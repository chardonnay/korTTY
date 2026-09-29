package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.ApplyRun;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the snippet library shows about the stored analyses of one snippet, without their content:
 * small enough to keep for hundreds of snippets, computed on the store's IO thread
 * ({@link SnippetAnalysisStore#overviews}) or from a cached history.
 *
 * <p>Only the newest record counts for the status, like everywhere else; a pending review counts
 * for any record, because "apply later" must stay findable after a re-run. How many records a new
 * analysis would trim ({@link #trimmableAt(int)}) matches {@link SnippetAnalysisHistory#trimmableAt}.</p>
 *
 * @param recordCount       stored records
 * @param unprotectedCount  records retention may trim (not pinned, no running/pending/resumable run)
 * @param pendingReview     some record has a run waiting for review
 * @param sourceSha256      hash of the content the newest record analysed ({@code ""} = unknown)
 * @param analyzedAt        when the newest record was analysed
 * @param findingIds        finding ids of the newest record
 * @param acceptedRuns      the accepted runs of the newest record
 */
public record SnippetAnalysisOverview(
    String snippetId,
    int recordCount,
    int unprotectedCount,
    boolean pendingReview,
    String sourceSha256,
    long analyzedAt,
    Set<String> findingIds,
    List<AcceptedRun> acceptedRuns) {

    /** An accepted run, reduced to what decides "applied": the ids and whether it was saved. */
    public record AcceptedRun(String acceptedContentSha256, boolean savedToSnippet, Set<String> appliedFindingIds) {
        public AcceptedRun {
            acceptedContentSha256 = acceptedContentSha256 != null ? acceptedContentSha256 : "";
            appliedFindingIds = appliedFindingIds == null ? Set.of() : Set.copyOf(appliedFindingIds);
        }
    }

    /** The library's single status symbol for a snippet (the stale marker is shown in addition). */
    public enum Kind {
        /** No stored analysis. */
        NONE,
        /** A result waits for review ("apply later"). */
        REVIEW_PENDING,
        /** Findings of the newest analysis are not applied to the saved snippet. */
        OPEN_FINDINGS,
        /** Every finding of the newest analysis reached the saved snippet. */
        APPLIED,
        /** The newest analysis found nothing to fix. */
        CLEAN
    }

    /**
     * The status against the saved snippet: {@code openFindings} counts findings of the newest
     * analysis that no saved accepted run covers; {@code stale} means the snippet changed since that
     * analysis other than by accepting one of its results.
     */
    public record Status(Kind kind, int openFindings, boolean stale, long analyzedAt) {
        public static final Status NONE = new Status(Kind.NONE, 0, false, 0L);

        public boolean hasAnalysis() {
            return kind != Kind.NONE;
        }
    }

    /** The library's analysis filter ("inbox" for later work). */
    public enum Filter {
        ALL, OPEN_FINDINGS, STALE, REVIEW_PENDING;

        public boolean matches(Status status) {
            Status value = status != null ? status : Status.NONE;
            return switch (this) {
                case ALL -> true;
                case OPEN_FINDINGS -> value.openFindings() > 0;
                case STALE -> value.stale();
                case REVIEW_PENDING -> value.kind() == Kind.REVIEW_PENDING;
            };
        }
    }

    public SnippetAnalysisOverview {
        snippetId = snippetId != null ? snippetId : "";
        recordCount = Math.max(0, recordCount);
        unprotectedCount = Math.max(0, Math.min(unprotectedCount, recordCount));
        sourceSha256 = sourceSha256 != null ? sourceSha256 : "";
        findingIds = findingIds == null ? Set.of() : Set.copyOf(findingIds);
        acceptedRuns = acceptedRuns == null ? List.of() : List.copyOf(acceptedRuns);
    }

    /** The overview of {@code history}; {@code null} for {@code null}. */
    public static SnippetAnalysisOverview of(SnippetAnalysisHistory history) {
        if (history == null) {
            return null;
        }
        SnippetAnalysisRecord current = history.current();
        int unprotected = 0;
        boolean pending = false;
        for (SnippetAnalysisRecord record : history.records()) {
            if (!record.isProtectedFromRetention()) {
                unprotected++;
            }
            pending |= record.hasPendingReview();
        }
        List<AcceptedRun> accepted = new ArrayList<>();
        if (current != null) {
            for (ApplyRun run : current.applyRuns()) {
                if (run.isAccepted()) {
                    accepted.add(new AcceptedRun(run.acceptedContentSha256(), run.savedToSnippetAt() > 0,
                        new HashSet<>(run.appliedFindingIds())));
                }
            }
        }
        return new SnippetAnalysisOverview(history.snippetId(), history.records().size(), unprotected, pending,
            current != null ? current.source().sha256() : "", current != null ? current.analyzedAt() : 0L,
            current != null ? current.allFindingIds() : Set.of(), accepted);
    }

    public boolean isEmpty() {
        return recordCount == 0;
    }

    /** How many records a new analysis would trim at {@code limit} (see {@link SnippetAnalysisHistory#trimmableAt}). */
    public int trimmableAt(int limit) {
        int max = Math.max(1, limit);
        return Math.max(0, Math.min(unprotectedCount, recordCount + 1 - max));
    }

    /**
     * The status against the saved snippet whose content hashes to {@code savedContentSha256}
     * ({@code null} = unknown: only runs stamped as saved count).
     */
    public Status statusFor(String savedContentSha256) {
        if (isEmpty()) {
            return Status.NONE;
        }
        String saved = savedContentSha256 != null && !savedContentSha256.isBlank() ? savedContentSha256 : null;
        Set<String> applied = new HashSet<>();
        boolean anySaved = false;
        boolean acceptedIsSaved = false;
        for (AcceptedRun run : acceptedRuns) {
            boolean matches = saved != null && saved.equals(run.acceptedContentSha256());
            acceptedIsSaved |= matches;
            if (run.savedToSnippet() || matches) {
                anySaved = true;
                applied.addAll(run.appliedFindingIds());
            }
        }
        int open = 0;
        for (String id : findingIds) {
            if (!applied.contains(id)) {
                open++;
            }
        }
        boolean stale = saved != null && !sourceSha256.isBlank() && !sourceSha256.equals(saved) && !acceptedIsSaved;
        Kind kind;
        if (pendingReview) {
            kind = Kind.REVIEW_PENDING;
        } else if (open > 0) {
            kind = Kind.OPEN_FINDINGS;
        } else if (anySaved) {
            kind = Kind.APPLIED;
        } else {
            kind = Kind.CLEAN;
        }
        return new Status(kind, open, stale, analyzedAt);
    }
}
