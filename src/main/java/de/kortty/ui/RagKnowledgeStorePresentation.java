package de.kortty.ui;

import de.kortty.rag.RagDocument;
import de.kortty.rag.RagScanPreview;
import de.kortty.rag.RagSource;
import de.kortty.rag.RagSourceStatus;
import de.kortty.rag.RagStatus;
import de.kortty.rag.RagStore;
import de.kortty.rag.RagSyncMode;
import de.kortty.rag.RagSyncResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Pure presentation and source-list transformations used by the JavaFX knowledge-store pane. */
final class RagKnowledgeStorePresentation {

    enum PreviewState {
        WILL_INDEX,
        UNCHANGED,
        SKIPPED
    }

    record PreviewRow(
        String path,
        String format,
        long rawSize,
        PreviewState state,
        String reason
    ) { }

    record Preview(
        long acceptedFiles,
        long unchangedFiles,
        long filesToIndex,
        long acceptedBytes,
        Map<String, Long> formats,
        long skippedFiles,
        List<PreviewRow> rows
    ) {
        Preview {
            formats = Collections.unmodifiableMap(new LinkedHashMap<>(formats));
            rows = List.copyOf(rows);
        }

        boolean canConfirm() {
            return acceptedFiles > 0;
        }
    }

    record Progress(
        RagSourceStatus status,
        String message,
        int percent,
        int indexedDocuments,
        int indexedChunks,
        int problemCount
    ) { }

    record Completion(
        boolean detailed,
        int indexedDocuments,
        int unchangedDocuments,
        int removedDocuments,
        int skippedDocuments
    ) { }

    @FunctionalInterface
    interface SourceVectorRemover {
        CompletableFuture<Void> remove(RagStore store, List<RagSource> sources);
    }

    private RagKnowledgeStorePresentation() {
    }

    static Preview preview(List<RagScanPreview> previews) {
        List<RagScanPreview> safePreviews = previews == null ? List.of() : List.copyOf(previews);
        List<PreviewRow> rows = new ArrayList<>();
        Map<String, Long> formats = new LinkedHashMap<>();
        long acceptedFiles = 0;
        long unchangedFiles = 0;
        long acceptedBytes = 0;
        long skippedFiles = 0;

        for (RagScanPreview preview : safePreviews) {
            acceptedBytes += preview.acceptedBytes();
            for (RagDocument document : preview.documents()) {
                acceptedFiles++;
                formats.merge(document.format(), 1L, Long::sum);
                boolean unchanged = document.sha256().equals(
                    preview.source().documentHashes().get(document.relativePath()));
                if (unchanged) {
                    unchangedFiles++;
                }
                rows.add(new PreviewRow(
                    document.absolutePath().toString(),
                    document.format(),
                    document.rawSize(),
                    unchanged ? PreviewState.UNCHANGED : PreviewState.WILL_INDEX,
                    ""));
            }
            for (RagScanPreview.Problem problem : preview.problems()) {
                skippedFiles++;
                rows.add(new PreviewRow(
                    problem.path().toString(),
                    "",
                    -1,
                    PreviewState.SKIPPED,
                    problem.message()));
            }
        }
        return new Preview(
            acceptedFiles,
            unchangedFiles,
            acceptedFiles - unchangedFiles,
            acceptedBytes,
            formats,
            skippedFiles,
            rows);
    }

    static Progress progress(RagStatus status) {
        Objects.requireNonNull(status, "status");
        return new Progress(
            status.status(),
            status.message(),
            (int) Math.round(status.progress() * 100),
            status.indexedDocuments(),
            status.indexedChunks(),
            status.problemCount());
    }

    static Completion completion(List<RagSyncResult> results) {
        if (results == null) {
            return new Completion(false, 0, 0, 0, 0);
        }
        return new Completion(
            true,
            results.stream().mapToInt(RagSyncResult::embeddedDocuments).sum(),
            results.stream().mapToInt(RagSyncResult::reusedDocuments).sum(),
            results.stream().mapToInt(RagSyncResult::removedDocuments).sum(),
            results.stream().mapToInt(RagSyncResult::problems).sum());
    }

    static String failureMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null && !message.isBlank() ? message : current.getClass().getSimpleName();
    }

    static List<RagSource> withSyncMode(
        List<RagSource> sources,
        String sourceId,
        RagSyncMode mode
    ) {
        Objects.requireNonNull(mode, "mode");
        return replaceSource(sources, sourceId, source -> source.withSyncMode(mode));
    }

    static List<RagSource> replaceSource(
        List<RagSource> sources,
        String sourceId,
        java.util.function.UnaryOperator<RagSource> replacement
    ) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(replacement, "replacement");
        return (sources == null ? List.<RagSource>of() : sources).stream()
            .map(source -> source.id().equals(sourceId) ? replacement.apply(source) : source)
            .toList();
    }

    static List<RagSource> removeSources(List<RagSource> sources, Set<String> sourceIds) {
        Set<String> ids = sourceIds == null ? Set.of() : Set.copyOf(sourceIds);
        return (sources == null ? List.<RagSource>of() : sources).stream()
            .filter(source -> !ids.contains(source.id()))
            .toList();
    }

    static CompletableFuture<Void> removeStoreVectors(
        RagStore store,
        List<RagSource> sources,
        SourceVectorRemover remover,
        Runnable afterRemoval
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(remover, "remover");
        Objects.requireNonNull(afterRemoval, "afterRemoval");
        List<RagSource> snapshot = sources == null ? List.of() : List.copyOf(sources);
        return Objects.requireNonNull(remover.remove(store, snapshot), "removal future")
            .thenRun(afterRemoval);
    }
}
