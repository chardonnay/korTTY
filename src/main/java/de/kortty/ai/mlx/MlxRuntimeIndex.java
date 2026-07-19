package de.kortty.ai.mlx;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Verified contents of mlx-runtime-index-v1.json. */
public record MlxRuntimeIndex(
    int schemaVersion,
    Instant generatedAt,
    List<MlxRuntimePackageDescriptor> packages,
    Set<String> revokedRuntimeIds
) {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public MlxRuntimeIndex {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported MLX runtime index schema: " + schemaVersion);
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("MLX runtime index generation timestamp is required.");
        }
        packages = packages == null ? List.of() : List.copyOf(packages);
        revokedRuntimeIds = revokedRuntimeIds == null ? Set.of() : Set.copyOf(revokedRuntimeIds);
    }

    public boolean isRevoked(MlxRuntimePackageDescriptor descriptor) {
        return revokedRuntimeIds.contains(descriptor.runtimeId())
            || revokedRuntimeIds.contains(descriptor.installationId());
    }
}
