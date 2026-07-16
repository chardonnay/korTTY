package de.kortty.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Hash-incremental, source-transactional scan/chunk/embed/store pipeline. */
public final class RagSourceSynchronizer {
    private static final int EMBEDDING_BATCH_SIZE = 32;

    private final RagSourceScanner scanner;
    private final RagChunker chunker;
    private final EmbeddingService embeddingService;
    private final RagVectorStore store;
    private final Map<String, Object> sourceLocks = new ConcurrentHashMap<>();
    private volatile Consumer<RagStatus> statusListener = ignored -> { };

    public RagSourceSynchronizer(
        RagSourceScanner scanner,
        RagChunker chunker,
        EmbeddingService embeddingService,
        RagVectorStore store
    ) {
        this.scanner = scanner;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.store = store;
        if (embeddingService.dimensions() != store.dimensions()) {
            throw new IllegalArgumentException("Embedding/store dimension mismatch");
        }
        if (!store.embeddingModelId().isBlank()
            && !embeddingService.modelId().equals(store.embeddingModelId())) {
            throw new IllegalArgumentException("Embedding/store model mismatch");
        }
    }

    public void setStatusListener(Consumer<RagStatus> listener) {
        statusListener = listener != null ? listener : ignored -> { };
    }

    public RagSyncResult synchronize(RagSource source, CancellationToken cancellation) throws Exception {
        return synchronize(source, null, cancellation, null);
    }

    /**
     * Synchronizes exactly the files the user reviewed in {@code confirmedPreview}.
     *
     * <p>The source is scanned before any work starts and once more immediately before the new
     * vector snapshot is committed. A changed, added, removed, or newly skipped path invalidates
     * the confirmation. The extracted text from the confirmed preview is the only text passed to
     * the embedding service, so an unreviewed filesystem version can never enter the index.</p>
     */
    public RagSyncResult synchronizeConfirmed(
        RagScanPreview confirmedPreview,
        CancellationToken cancellation
    ) throws Exception {
        return synchronizeConfirmed(confirmedPreview, cancellation, () -> { });
    }

    RagSyncResult synchronizeConfirmed(
        RagScanPreview confirmedPreview,
        CancellationToken cancellation,
        ConfirmedPreviewGuard guard
    ) throws Exception {
        if (confirmedPreview == null) {
            throw new IllegalArgumentException("A confirmed scan preview is required");
        }
        return synchronize(confirmedPreview.source(), confirmedPreview, cancellation,
            guard != null ? guard : () -> { });
    }

    private RagSyncResult synchronize(
        RagSource source,
        RagScanPreview confirmedPreview,
        CancellationToken cancellation,
        ConfirmedPreviewGuard guard
    ) throws Exception {
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        Object lock = sourceLocks.computeIfAbsent(source.id(), ignored -> new Object());
        synchronized (lock) {
            try {
                return synchronizeLocked(source, confirmedPreview, token, guard);
            } catch (java.util.concurrent.CancellationException error) {
                publish(source.id(), RagSourceStatus.CANCELLED, "Synchronization cancelled", 0, 0, 0, 0);
                throw error;
            }
        }
    }

    public void remove(RagSource source, CancellationToken cancellation) throws Exception {
        Object lock = sourceLocks.computeIfAbsent(source.id(), ignored -> new Object());
        synchronized (lock) {
            store.removeSource(source.id(), cancellation != null ? cancellation : CancellationToken.NONE);
            publish(source.id(), RagSourceStatus.PENDING, "Source index removed", 0, 0, 0, 0);
        }
    }

    private RagSyncResult synchronizeLocked(
        RagSource source,
        RagScanPreview confirmedPreview,
        CancellationToken token,
        ConfirmedPreviewGuard guard
    ) throws Exception {
        if (!source.enabled()) {
            publish(source.id(), RagSourceStatus.DISABLED, "Source is disabled", 0, 0, 0, 0);
            return new RagSyncResult(source.id(), RagSourceStatus.DISABLED, 0, 0, 0, 0, 0, 0, Instant.now());
        }
        RagScanPreview preview;
        if (confirmedPreview == null) {
            publish(source.id(), RagSourceStatus.SCANNING, "Scanning source", 0, 0, 0, 0);
            preview = scanner.preview(source, token);
        } else {
            publish(source.id(), RagSourceStatus.SCANNING, "Verifying confirmed file preview", 0, 0, 0, 0);
            guard.verify();
            assertConfirmedPreviewCurrent(source, confirmedPreview, token);
            // Only the immutable, user-reviewed documents are ever chunked or embedded.
            preview = confirmedPreview;
        }
        token.throwIfCancelled();
        boolean fatal = preview.problems().stream().anyMatch(problem ->
            problem.severity() == RagScanPreview.Severity.ERROR);
        if (fatal) {
            boolean sourceMissing = preview.problems().stream().anyMatch(problem ->
                problem.code() == RagScanPreview.ProblemCode.SOURCE_MISSING);
            RagSourceStatus status = sourceMissing ? RagSourceStatus.MISSING : RagSourceStatus.ERROR;
            if (sourceMissing) {
                // A deleted source is an incremental change, not an extraction failure. Commit an
                // empty replacement so stale content cannot be retrieved. The vector store still
                // provides the normal transactional guarantee: cancellation or an I/O error keeps
                // the previously active snapshot untouched.
                int removedDocuments = (int) store.chunksForSource(source.id()).stream()
                    .map(value -> value.chunk().documentPath())
                    .distinct()
                    .count();
                store.replaceSource(source.id(), List.of(), token);
                publish(source.id(), status, firstError(preview), 1, 0, 0, preview.problems().size());
                return new RagSyncResult(source.id(), status, 0, 0, 0, 0,
                    removedDocuments, preview.problems().size(), Instant.now(), Map.of());
            }
            publish(source.id(), status, firstError(preview), 0, 0, 0, preview.problems().size());
            throw new RagSynchronizationException(status, firstError(preview), preview);
        }

        List<RagEmbeddedChunk> existing = store.chunksForSource(source.id());
        Map<String, List<RagEmbeddedChunk>> oldByDocument = groupByDocument(existing);
        Set<String> currentPaths = new HashSet<>();
        List<RagEmbeddedChunk> candidate = new ArrayList<>();
        List<RagChunk> chunksToEmbed = new ArrayList<>();
        int reusedDocuments = 0;
        int embeddedDocuments = 0;

        int processed = 0;
        for (RagDocument document : preview.documents()) {
            token.throwIfCancelled();
            currentPaths.add(document.relativePath());
            List<RagEmbeddedChunk> old = oldByDocument.get(document.relativePath());
            if (old != null && !old.isEmpty()
                && old.stream().allMatch(chunk -> chunk.chunk().documentHash().equals(document.sha256()))) {
                candidate.addAll(old);
                reusedDocuments++;
            } else {
                List<RagChunk> chunks = chunker.chunk(document);
                chunksToEmbed.addAll(chunks);
                embeddedDocuments++;
            }
            processed++;
            publish(source.id(), RagSourceStatus.INDEXING, "Preparing documents",
                preview.documents().isEmpty() ? 0 : processed * 0.3 / preview.documents().size(),
                processed, candidate.size(), preview.problems().size());
        }

        for (int offset = 0; offset < chunksToEmbed.size(); offset += EMBEDDING_BATCH_SIZE) {
            token.throwIfCancelled();
            int end = Math.min(chunksToEmbed.size(), offset + EMBEDDING_BATCH_SIZE);
            List<RagChunk> batch = chunksToEmbed.subList(offset, end);
            List<float[]> vectors = embeddingService.embed(batch.stream().map(RagChunk::text).toList(), token);
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("Embedding service returned a mismatched batch size");
            }
            for (int i = 0; i < batch.size(); i++) {
                if (vectors.get(i).length != store.dimensions()) {
                    throw new IllegalStateException("Embedding service returned a mismatched vector dimension");
                }
                candidate.add(new RagEmbeddedChunk(batch.get(i), vectors.get(i)));
            }
            double embeddingProgress = chunksToEmbed.isEmpty() ? 0.9 : 0.3 + 0.6 * end / chunksToEmbed.size();
            publish(source.id(), RagSourceStatus.INDEXING, "Embedding changed documents",
                embeddingProgress, preview.documents().size(), candidate.size(), preview.problems().size());
        }

        int removedDocuments = (int) oldByDocument.keySet().stream()
            .filter(path -> !currentPaths.contains(path)).count();
        token.throwIfCancelled();
        if (confirmedPreview != null) {
            publish(source.id(), RagSourceStatus.SCANNING,
                "Checking that confirmed files did not change", 0.92,
                preview.documents().size(), candidate.size(), preview.problems().size());
            assertConfirmedPreviewCurrent(source, confirmedPreview, token);
            guard.verify();
        }
        publish(source.id(), RagSourceStatus.INDEXING, "Committing index", 0.95,
            preview.documents().size(), candidate.size(), preview.problems().size());
        store.replaceSource(source.id(), candidate, token);

        RagSourceStatus finalStatus = preview.problems().stream().anyMatch(problem ->
            problem.severity() == RagScanPreview.Severity.WARNING)
            ? RagSourceStatus.WARNING : RagSourceStatus.READY;
        publish(source.id(), finalStatus, finalStatus == RagSourceStatus.READY
            ? "Source is ready" : "Source indexed with skipped files", 1,
            preview.documents().size(), candidate.size(), preview.problems().size());
        return new RagSyncResult(source.id(), finalStatus, preview.documents().size(), candidate.size(),
            reusedDocuments, embeddedDocuments, removedDocuments, preview.problems().size(), Instant.now(),
            preview.documents().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                RagDocument::relativePath, RagDocument::sha256)));
    }

    private void assertConfirmedPreviewCurrent(
        RagSource source,
        RagScanPreview confirmed,
        CancellationToken token
    ) throws ConfirmedPreviewStaleException {
        if (!sameScanConfiguration(source, confirmed.source())) {
            throw stale(source.id(), "Source settings changed after the preview");
        }
        RagScanPreview current = scanner.preview(source, token);
        token.throwIfCancelled();
        if (!sameSnapshot(confirmed, current)) {
            throw stale(source.id(), "Files changed after the preview; review and confirm them again");
        }
    }

    /** Scan-affecting configuration only; persisted result counters may legitimately change. */
    static boolean sameScanConfiguration(RagSource left, RagSource right) {
        return left != null && right != null
            && left.id().equals(right.id())
            && left.enabled() == right.enabled()
            && sameContentScope(left, right);
    }

    /** Returns whether two configurations approve the same set of possible file contents. */
    static boolean sameContentScope(RagSource left, RagSource right) {
        return left != null && right != null
            && left.id().equals(right.id())
            && left.path().equals(right.path())
            && left.type() == right.type()
            && left.recursive() == right.recursive()
            && left.respectGitIgnore() == right.respectGitIgnore()
            && left.maxFileBytes() == right.maxFileBytes()
            && left.includePatterns().equals(right.includePatterns())
            && left.excludePatterns().equals(right.excludePatterns());
    }

    private static boolean sameSnapshot(RagScanPreview confirmed, RagScanPreview current) {
        if (confirmed.acceptedBytes() != current.acceptedBytes()
            || confirmed.visitedFiles() != current.visitedFiles()
            || confirmed.documents().size() != current.documents().size()
            || confirmed.problems().size() != current.problems().size()) {
            return false;
        }
        Map<String, DocumentFingerprint> confirmedDocuments = documentFingerprints(confirmed.documents());
        Map<String, DocumentFingerprint> currentDocuments = documentFingerprints(current.documents());
        if (!confirmedDocuments.equals(currentDocuments)) {
            return false;
        }
        return problemFingerprints(confirmed.problems()).equals(problemFingerprints(current.problems()));
    }

    private static Map<String, DocumentFingerprint> documentFingerprints(List<RagDocument> documents) {
        Map<String, DocumentFingerprint> result = new HashMap<>();
        for (RagDocument document : documents) {
            DocumentFingerprint previous = result.put(document.relativePath(), new DocumentFingerprint(
                document.absolutePath(), document.format(), document.rawSize(), document.sha256(), document.text()));
            if (previous != null) {
                // A duplicate relative path is not a stable, confirmable snapshot.
                return Map.of();
            }
        }
        return Map.copyOf(result);
    }

    private static Map<ProblemFingerprint, Integer> problemFingerprints(
        List<RagScanPreview.Problem> problems
    ) {
        Map<ProblemFingerprint, Integer> result = new HashMap<>();
        for (RagScanPreview.Problem problem : problems) {
            result.merge(new ProblemFingerprint(
                problem.path().toAbsolutePath().normalize(), problem.code(), problem.severity()), 1, Integer::sum);
        }
        return Map.copyOf(result);
    }

    private ConfirmedPreviewStaleException stale(String sourceId, String message) {
        publish(sourceId, RagSourceStatus.ERROR, message, 0, 0, 0, 1);
        return new ConfirmedPreviewStaleException(message);
    }

    private static Map<String, List<RagEmbeddedChunk>> groupByDocument(Collection<RagEmbeddedChunk> chunks) {
        Map<String, List<RagEmbeddedChunk>> result = new LinkedHashMap<>();
        for (RagEmbeddedChunk chunk : chunks) {
            result.computeIfAbsent(chunk.chunk().documentPath(), ignored -> new ArrayList<>()).add(chunk);
        }
        return result;
    }

    private static String firstError(RagScanPreview preview) {
        return preview.problems().stream()
            .filter(problem -> problem.severity() == RagScanPreview.Severity.ERROR)
            .map(RagScanPreview.Problem::message)
            .findFirst().orElse("Source scan failed");
    }

    private void publish(
        String sourceId,
        RagSourceStatus status,
        String message,
        double progress,
        int documents,
        int chunks,
        int problems
    ) {
        try {
            statusListener.accept(new RagStatus(sourceId, status, message, progress,
                documents, chunks, problems, Instant.now()));
        } catch (RuntimeException ignored) {
            // Presentation callbacks must never corrupt or abort a transactional sync.
        }
    }

    public static final class RagSynchronizationException extends Exception {
        private final RagSourceStatus status;
        private final RagScanPreview preview;

        public RagSynchronizationException(RagSourceStatus status, String message, RagScanPreview preview) {
            super(message);
            this.status = status;
            this.preview = preview;
        }

        public RagSourceStatus status() { return status; }
        public RagScanPreview preview() { return preview; }
    }

    /** Signals that a user-approved preview no longer describes the filesystem. */
    public static final class ConfirmedPreviewStaleException extends Exception {
        public ConfirmedPreviewStaleException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface ConfirmedPreviewGuard {
        void verify() throws Exception;
    }

    private record DocumentFingerprint(
        java.nio.file.Path absolutePath,
        String format,
        long rawSize,
        String sha256,
        String extractedText
    ) { }

    private record ProblemFingerprint(
        java.nio.file.Path path,
        RagScanPreview.ProblemCode code,
        RagScanPreview.Severity severity
    ) { }
}
