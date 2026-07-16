package de.kortty.ai.runtimeupdate;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeIndexFreshnessGuardTest {

    @Test
    void persistsHighWaterMarkAndRejectsOlderSignedPayload() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-index-freshness-");
        try {
            LlamaRuntimeIndexFreshnessGuard first = new LlamaRuntimeIndexFreshnessGuard(root);
            first.accept(index("2026-07-15T12:00:00Z"), "new".getBytes(StandardCharsets.UTF_8));

            IOException replay = org.testng.Assert.expectThrows(IOException.class, () ->
                new LlamaRuntimeIndexFreshnessGuard(root).accept(
                    index("2026-07-14T12:00:00Z"), "old".getBytes(StandardCharsets.UTF_8)));

            assertThat(replay).hasMessageThat().contains("replay rejected");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void sameTimestampRequiresIdenticalVerifiedBytes() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-index-collision-");
        try {
            LlamaRuntimeIndexFreshnessGuard guard = new LlamaRuntimeIndexFreshnessGuard(root);
            LlamaRuntimeIndex index = index("2026-07-15T12:00:00Z");
            guard.accept(index, "first".getBytes(StandardCharsets.UTF_8));
            guard.accept(index, "first".getBytes(StandardCharsets.UTF_8));

            IOException collision = org.testng.Assert.expectThrows(IOException.class, () ->
                guard.accept(index, "second".getBytes(StandardCharsets.UTF_8)));
            assertThat(collision).hasMessageThat().contains("collision");
        } finally {
            deleteTree(root);
        }
    }

    private static LlamaRuntimeIndex index(String generatedAt) {
        return new LlamaRuntimeIndex(1, Instant.parse(generatedAt), List.of(), Set.of());
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
