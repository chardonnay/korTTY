package de.kortty.ai.catalog;

import de.kortty.model.AiPromptPreset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, signed catalog payload shared by local-model recommendations and prompt detection. */
public record AiModelPromptCatalog(
    int schemaVersion,
    long sequence,
    String catalogVersion,
    List<Recommendation> recommendations,
    List<PromptFamily> promptFamilies
) {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_RECOMMENDATIONS = 256;
    private static final int MAX_PROMPT_FAMILIES = 64;
    private static final long MAX_MEMORY_BYTES = 1L << 50;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");
    private static final Pattern REVISION = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern QUANTIZATION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public AiModelPromptCatalog {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported AI catalog schema version: " + schemaVersion);
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("AI catalog sequence must be positive.");
        }
        catalogVersion = requireText(catalogVersion, "catalogVersion", 128);
        recommendations = List.copyOf(Objects.requireNonNull(recommendations, "recommendations"));
        promptFamilies = List.copyOf(Objects.requireNonNull(promptFamilies, "promptFamilies"));
        if (recommendations.isEmpty() || recommendations.size() > MAX_RECOMMENDATIONS) {
            throw new IllegalArgumentException("AI catalog must contain 1-" + MAX_RECOMMENDATIONS + " recommendations.");
        }
        if (promptFamilies.isEmpty() || promptFamilies.size() > MAX_PROMPT_FAMILIES) {
            throw new IllegalArgumentException("AI catalog must contain 1-" + MAX_PROMPT_FAMILIES + " prompt families.");
        }
        requireUniqueIds(recommendations.stream().map(Recommendation::id).toList(), "recommendation");
        requireUniqueIds(promptFamilies.stream().map(PromptFamily::id).toList(), "prompt-family");
        EnumSet<Role> coveredRoles = EnumSet.noneOf(Role.class);
        recommendations.forEach(value -> coveredRoles.addAll(value.roles()));
        if (!coveredRoles.containsAll(EnumSet.allOf(Role.class))) {
            throw new IllegalArgumentException("AI catalog must provide TEXT, CODING, and EMBEDDING recommendations.");
        }
        for (Role role : Role.values()) {
            boolean hasBaseline = recommendations.stream()
                .anyMatch(value -> value.minimumSystemMemoryBytes() == 0 && value.roles().contains(role));
            if (!hasBaseline) {
                throw new IllegalArgumentException("AI catalog must provide a zero-memory baseline for role " + role + ".");
            }
        }
        List<PromptFamily> ordered = new ArrayList<>(promptFamilies);
        ordered.sort(Comparator.comparingInt(PromptFamily::priority).reversed().thenComparing(PromptFamily::id));
        promptFamilies = List.copyOf(ordered);
    }

    /** Returns the first signed family match after deterministic priority ordering. */
    public Optional<AiPromptPreset> promptPresetFor(String modelName) {
        String normalized = modelName != null ? modelName.toLowerCase(Locale.ROOT) : "";
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return promptFamilies.stream()
            .filter(family -> family.modelNameContains().stream().anyMatch(normalized::contains))
            .map(PromptFamily::preset)
            .findFirst();
    }

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
            id = requireIdentifier(id, "recommendation id");
            modelId = requirePattern(modelId, MODEL_ID, "modelId");
            if (revision != null && !revision.isBlank()) {
                revision = requirePattern(revision.trim(), REVISION, "revision").toLowerCase(Locale.ROOT);
            } else {
                revision = null;
            }
            quantization = requirePattern(quantization, QUANTIZATION, "quantization");
            roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
            if (roles.isEmpty()) {
                throw new IllegalArgumentException("A recommendation must have at least one role.");
            }
            if (minimumSystemMemoryBytes < 0 || minimumSystemMemoryBytes > MAX_MEMORY_BYTES) {
                throw new IllegalArgumentException("minimumSystemMemoryBytes is outside the supported range.");
            }
            if (preference < -10_000 || preference > 10_000) {
                throw new IllegalArgumentException("preference is outside the supported range.");
            }
        }

        public Optional<String> fixedRevision() {
            return Optional.ofNullable(revision);
        }
    }

    public record PromptFamily(
        String id,
        AiPromptPreset preset,
        List<String> modelNameContains,
        int priority
    ) {
        public PromptFamily {
            id = requireIdentifier(id, "prompt-family id");
            preset = Objects.requireNonNull(preset, "preset");
            if (preset == AiPromptPreset.AUTO || preset == AiPromptPreset.GENERIC) {
                throw new IllegalArgumentException("Prompt-family mappings require a concrete preset.");
            }
            modelNameContains = List.copyOf(Objects.requireNonNull(modelNameContains, "modelNameContains").stream()
                .map(value -> requireText(value, "modelNameContains", 64).toLowerCase(Locale.ROOT))
                .distinct()
                .toList());
            if (modelNameContains.isEmpty() || modelNameContains.size() > 16) {
                throw new IllegalArgumentException("A prompt family must contain 1-16 model-name tokens.");
            }
            if (priority < -10_000 || priority > 10_000) {
                throw new IllegalArgumentException("prompt-family priority is outside the supported range.");
            }
        }
    }

    private static String requireIdentifier(String value, String name) {
        return requirePattern(value, IDENTIFIER, name);
    }

    private static String requirePattern(String value, Pattern pattern, String name) {
        String normalized = requireText(value, name, 256);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format.");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        String normalized = value != null ? value.trim() : "";
        if (normalized.isEmpty() || normalized.length() > maximumLength || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is missing or invalid.");
        }
        return normalized;
    }

    private static void requireUniqueIds(List<String> ids, String type) {
        Set<String> unique = new HashSet<>();
        for (String id : ids) {
            if (!unique.add(id)) {
                throw new IllegalArgumentException("Duplicate " + type + " id: " + id);
            }
        }
    }
}
