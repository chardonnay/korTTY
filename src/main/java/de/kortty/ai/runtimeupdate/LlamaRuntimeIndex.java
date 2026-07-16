package de.kortty.ai.runtimeupdate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Verified contents of runtime-index-v1.json. */
public record LlamaRuntimeIndex(
    int schemaVersion,
    Instant generatedAt,
    List<LlamaRuntimePackageDescriptor> packages,
    Set<String> revokedRuntimeIds
) {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public LlamaRuntimeIndex {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported llama.cpp runtime index schema: " + schemaVersion);
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("Runtime index generation timestamp is required.");
        }
        packages = packages == null ? List.of() : List.copyOf(packages);
        revokedRuntimeIds = revokedRuntimeIds == null ? Set.of() : Set.copyOf(revokedRuntimeIds);
    }

    public boolean isRevoked(LlamaRuntimePackageDescriptor descriptor) {
        return descriptor.revoked() || revokedRuntimeIds.contains(descriptor.runtimeId())
            || revokedRuntimeIds.contains(descriptor.installationId())
            || packages.stream().anyMatch(candidate -> candidate.revoked()
                && (candidate.runtimeId().equals(descriptor.runtimeId())
                    || candidate.installationId().equals(descriptor.installationId())));
    }
}
