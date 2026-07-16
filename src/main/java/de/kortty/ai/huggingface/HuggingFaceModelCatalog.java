package de.kortty.ai.huggingface;

import de.kortty.ai.catalog.AiCatalogBootstrap;
import de.kortty.ai.catalog.AiCatalogService;
import de.kortty.ai.catalog.AiModelPromptCatalog;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Built-in bootstrap catalog. A separately signed catalog can supersede it later. */
public final class HuggingFaceModelCatalog {

    public enum Role {
        TEXT,
        CODING,
        EMBEDDING
    }

    public record Recommendation(
        String id,
        String modelId,
        String revision,
        String quantization,
        Set<Role> roles,
        long minimumSystemMemoryBytes,
        int preference
    ) {
        public Recommendation {
            roles = Set.copyOf(roles);
        }

        public Recommendation(
            String id,
            String modelId,
            String quantization,
            Set<Role> roles,
            long minimumSystemMemoryBytes,
            int preference
        ) {
            this(id, modelId, null, quantization, roles, minimumSystemMemoryBytes, preference);
        }

        public Optional<String> fixedRevision() {
            return Optional.ofNullable(revision);
        }
    }

    private HuggingFaceModelCatalog() {
    }

    public static List<Recommendation> bootstrapCatalog() {
        return AiCatalogBootstrap.catalog().recommendations().stream()
            .map(HuggingFaceModelCatalog::fromCatalog)
            .toList();
    }

    /** Returns one preferred recommendation for each role supported by the detected RAM tier. */
    public static List<Recommendation> defaultsForMemory(long systemMemoryBytes) {
        List<Recommendation> available = AiCatalogService.getDefault().catalog().recommendations().stream()
            .map(HuggingFaceModelCatalog::fromCatalog)
            .toList();
        Map<String, Recommendation> selected = new LinkedHashMap<>();
        for (Role role : Role.values()) {
            available.stream()
                .filter(value -> value.roles().contains(role))
                .filter(value -> value.minimumSystemMemoryBytes() <= Math.max(0, systemMemoryBytes))
                .max(Comparator.comparingInt(Recommendation::preference)
                    .thenComparingLong(Recommendation::minimumSystemMemoryBytes))
                .ifPresent(value -> selected.putIfAbsent(value.id(), value));
        }
        return List.copyOf(selected.values());
    }

    private static Recommendation fromCatalog(AiModelPromptCatalog.Recommendation value) {
        EnumSet<Role> roles = EnumSet.noneOf(Role.class);
        value.roles().forEach(role -> roles.add(Role.valueOf(role.name())));
        return new Recommendation(
            value.id(),
            value.modelId(),
            value.revision(),
            value.quantization(),
            roles,
            value.minimumSystemMemoryBytes(),
            value.preference());
    }
}
