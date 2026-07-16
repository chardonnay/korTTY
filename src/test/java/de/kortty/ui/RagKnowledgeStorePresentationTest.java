package de.kortty.ui;

import de.kortty.rag.RagDocument;
import de.kortty.rag.RagScanPreview;
import de.kortty.rag.RagSource;
import de.kortty.rag.RagSourceStatus;
import de.kortty.rag.RagSourceType;
import de.kortty.rag.RagStatus;
import de.kortty.rag.RagStore;
import de.kortty.rag.RagSyncMode;
import de.kortty.rag.RagSyncResult;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

class RagKnowledgeStorePresentationTest {

    @Test
    void buildsPreviewRowsForNewUnchangedAndSkippedFiles() {
        RagSource source = source("source", "docs").withIndexState(new RagSyncResult(
            "source", RagSourceStatus.READY, 1, 2, 0, 1, 0, 0, Instant.EPOCH,
            Map.of("same.md", "same-hash")));
        RagDocument unchanged = document(source, "same.md", ".md", 1024, "same-hash");
        RagDocument changed = document(source, "changed.java", ".java", 2048, "new-hash");
        RagScanPreview.Problem skipped = new RagScanPreview.Problem(
            Path.of("/tmp/docs/image.png"),
            RagScanPreview.ProblemCode.UNSUPPORTED_FORMAT,
            RagScanPreview.Severity.INFO,
            "Unsupported format");

        RagKnowledgeStorePresentation.Preview preview = RagKnowledgeStorePresentation.preview(List.of(
            new RagScanPreview(source, List.of(unchanged, changed), List.of(skipped), 3072, 3)));

        assertThat(preview.acceptedFiles()).isEqualTo(2);
        assertThat(preview.unchangedFiles()).isEqualTo(1);
        assertThat(preview.filesToIndex()).isEqualTo(1);
        assertThat(preview.acceptedBytes()).isEqualTo(3072);
        assertThat(preview.formats()).containsExactly(".md", 1L, ".java", 1L).inOrder();
        assertThat(preview.skippedFiles()).isEqualTo(1);
        assertThat(preview.canConfirm()).isTrue();
        assertThat(preview.rows().stream().map(RagKnowledgeStorePresentation.PreviewRow::state).toList())
            .containsExactly(
                RagKnowledgeStorePresentation.PreviewState.UNCHANGED,
                RagKnowledgeStorePresentation.PreviewState.WILL_INDEX,
                RagKnowledgeStorePresentation.PreviewState.SKIPPED)
            .inOrder();
        assertThat(preview.rows().get(2).reason()).isEqualTo("Unsupported format");
        assertThat(preview.rows().get(2).rawSize()).isEqualTo(-1);
    }

    @Test
    void refusesPreviewConfirmationWhenEveryFileWasSkipped() {
        RagSource source = source("source", "docs");
        RagScanPreview.Problem skipped = new RagScanPreview.Problem(
            Path.of("/tmp/docs/archive.zip"),
            RagScanPreview.ProblemCode.UNSUPPORTED_FORMAT,
            RagScanPreview.Severity.INFO,
            "Unsupported format");

        RagKnowledgeStorePresentation.Preview preview = RagKnowledgeStorePresentation.preview(List.of(
            new RagScanPreview(source, List.of(), List.of(skipped), 0, 1)));

        assertThat(preview.canConfirm()).isFalse();
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().getFirst().state())
            .isEqualTo(RagKnowledgeStorePresentation.PreviewState.SKIPPED);
    }

    @Test
    void presentsProgressCompletionAndNestedFailureDetails() {
        RagStatus status = new RagStatus(
            "source", RagSourceStatus.INDEXING, "Embedding", 0.426,
            4, 19, 2, Instant.EPOCH);
        RagSyncResult first = new RagSyncResult(
            "first", RagSourceStatus.READY, 7, 20, 2, 5, 1, 1, Instant.EPOCH);
        RagSyncResult second = new RagSyncResult(
            "second", RagSourceStatus.WARNING, 4, 12, 1, 3, 2, 2, Instant.EPOCH);

        RagKnowledgeStorePresentation.Progress progress = RagKnowledgeStorePresentation.progress(status);
        RagKnowledgeStorePresentation.Completion completion =
            RagKnowledgeStorePresentation.completion(List.of(first, second));

        assertThat(progress.status()).isEqualTo(RagSourceStatus.INDEXING);
        assertThat(progress.message()).isEqualTo("Embedding");
        assertThat(progress.percent()).isEqualTo(43);
        assertThat(progress.indexedDocuments()).isEqualTo(4);
        assertThat(progress.indexedChunks()).isEqualTo(19);
        assertThat(progress.problemCount()).isEqualTo(2);
        assertThat(completion.detailed()).isTrue();
        assertThat(completion.indexedDocuments()).isEqualTo(8);
        assertThat(completion.unchangedDocuments()).isEqualTo(3);
        assertThat(completion.removedDocuments()).isEqualTo(3);
        assertThat(completion.skippedDocuments()).isEqualTo(3);
        assertThat(RagKnowledgeStorePresentation.failureMessage(
            new CompletionException(new IllegalStateException("Embedding model stopped"))))
            .isEqualTo("Embedding model stopped");
    }

    @Test
    void changesOnlyTheSelectedSyncModeAndRemovesOnlySelectedSources() {
        RagSource first = source("first", "docs");
        RagSource second = source("second", "code");

        List<RagSource> changed = RagKnowledgeStorePresentation.withSyncMode(
            List.of(first, second), first.id(), RagSyncMode.MANUAL);
        List<RagSource> remaining = RagKnowledgeStorePresentation.removeSources(
            changed, Set.of(first.id()));

        assertThat(first.syncMode()).isEqualTo(RagSyncMode.AUTOMATIC);
        assertThat(changed.getFirst().syncMode()).isEqualTo(RagSyncMode.MANUAL);
        assertThat(changed.get(1)).isSameInstanceAs(second);
        assertThat(remaining).containsExactly(second);
    }

    @Test
    void storeDeletionHandsEverySourceToTheVectorRemoverBeforeContinuing() {
        RagStore qdrant = RagStore.qdrant(
            java.net.URI.create("https://qdrant.example"), "knowledge", "secret");
        List<RagSource> sources = List.of(source("first", "docs"), source("second", "code"));
        AtomicReference<RagStore> capturedStore = new AtomicReference<>();
        AtomicReference<List<RagSource>> capturedSources = new AtomicReference<>();
        AtomicBoolean cleanupStarted = new AtomicBoolean();
        CompletableFuture<Void> removal = new CompletableFuture<>();

        CompletableFuture<Void> returned = RagKnowledgeStorePresentation.removeStoreVectors(
            qdrant, sources, (store, selected) -> {
                capturedStore.set(store);
                capturedSources.set(selected);
                return removal;
            }, () -> cleanupStarted.set(true));

        assertThat(capturedStore.get()).isSameInstanceAs(qdrant);
        assertThat(capturedSources.get()).containsExactlyElementsIn(sources).inOrder();
        assertThat(cleanupStarted.get()).isFalse();
        assertThat(returned.isDone()).isFalse();
        removal.complete(null);
        assertThat(returned.isDone()).isTrue();
        assertThat(cleanupStarted.get()).isTrue();
    }

    @Test
    void storeDeletionDoesNotRunCleanupWhenVectorRemovalFails() {
        RagStore store = RagStore.local(Path.of("/tmp/index"));
        AtomicBoolean cleanupStarted = new AtomicBoolean();
        CompletableFuture<Void> removal = new CompletableFuture<>();

        CompletableFuture<Void> returned = RagKnowledgeStorePresentation.removeStoreVectors(
            store,
            List.of(source("source", "docs")),
            (ignoredStore, ignoredSources) -> removal,
            () -> cleanupStarted.set(true));
        removal.completeExceptionally(new IllegalStateException("Qdrant unavailable"));

        assertThat(returned.isCompletedExceptionally()).isTrue();
        assertThat(cleanupStarted.get()).isFalse();
    }

    private static RagSource source(String id, String name) {
        return new RagSource(
            id,
            name,
            Path.of("/tmp", name),
            RagSourceType.DIRECTORY,
            RagSyncMode.AUTOMATIC,
            true,
            List.of(),
            List.of());
    }

    private static RagDocument document(
        RagSource source,
        String relativePath,
        String format,
        long rawSize,
        String hash
    ) {
        return new RagDocument(
            source.id(),
            source.path().resolve(relativePath),
            relativePath,
            format,
            rawSize,
            Instant.EPOCH,
            hash,
            "content");
    }
}
