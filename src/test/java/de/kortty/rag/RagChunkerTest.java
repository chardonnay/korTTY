package de.kortty.rag;

import org.testng.annotations.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class RagChunkerTest {
    @Test
    void appliesEightHundredTokenWindowWithOneHundredTwentyTokenOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            if (i > 0) text.append(' ');
            text.append("token").append(alpha(i));
        }
        RagDocument document = document("notes.txt", ".txt", text.toString());
        List<RagChunk> chunks = new RagChunker().chunk(document);

        assertThat(chunks).hasSize(2);
        assertThat(RagContextBuilder.estimateTokens(chunks.get(0).text())).isEqualTo(800);
        assertThat(RagContextBuilder.estimateTokens(chunks.get(1).text())).isEqualTo(320);
        assertThat(chunks.get(0).text()).contains("token" + alpha(680));
        assertThat(chunks.get(1).text()).startsWith("token" + alpha(680));
    }

    @Test
    void producesStableIdsAndPdfPageCitations() {
        RagDocument document = document("guide.pdf", ".pdf", "first page\fsecond page");
        RagChunker chunker = new RagChunker();
        List<RagChunk> first = chunker.chunk(document);
        List<RagChunk> second = chunker.chunk(document);

        assertThat(first.stream().map(RagChunk::id).toList())
            .containsExactlyElementsIn(second.stream().map(RagChunk::id).toList()).inOrder();
        assertThat(first).hasSize(2);
        assertThat(first.get(0).citation()).isEqualTo("guide.pdf#page=1");
        assertThat(first.get(1).citation()).isEqualTo("guide.pdf#page=2");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsOverlapEqualToWindow() {
        new RagChunker(10, 10);
    }

    private static RagDocument document(String path, String format, String text) {
        return new RagDocument("source", Path.of(path), path, format, text.length(),
            Instant.EPOCH, "abc", text);
    }

    private static String alpha(int value) {
        StringBuilder result = new StringBuilder();
        int current = value;
        do {
            result.append((char) ('a' + current % 26));
            current = current / 26;
        } while (current > 0);
        return result.reverse().toString();
    }
}
