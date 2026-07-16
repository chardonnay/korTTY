package de.kortty.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class RagTestSupport {
    private RagTestSupport() { }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static RagChunk chunk(String id, String source, String document, String hash, String text) {
        return new RagChunk(id, source, document, hash, 0, 0, text.length(), text, Map.of());
    }

    static RagEmbeddedChunk embedded(String id, String source, String document, float... vector) {
        return new RagEmbeddedChunk(chunk(id, source, document, "hash-" + document, id), vector);
    }

    static final class CountingEmbedding implements EmbeddingService {
        private final AtomicInteger embeddedTexts = new AtomicInteger();
        private final int dimensions;

        CountingEmbedding(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override public String modelId() { return "test-embedding"; }
        @Override public int dimensions() { return dimensions; }

        @Override
        public List<float[]> embed(List<String> texts, CancellationToken cancellation) {
            List<float[]> result = new ArrayList<>();
            for (String text : texts) {
                cancellation.throwIfCancelled();
                embeddedTexts.incrementAndGet();
                float[] vector = new float[dimensions];
                int hash = text.hashCode();
                for (int i = 0; i < dimensions; i++) {
                    vector[i] = 1 + Math.abs((hash >>> (i * 4)) & 0xf);
                }
                result.add(vector);
            }
            return result;
        }

        int count() { return embeddedTexts.get(); }
    }
}
