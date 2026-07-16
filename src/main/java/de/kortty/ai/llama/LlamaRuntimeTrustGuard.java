package de.kortty.ai.llama;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed trust guard for managed llama.cpp executables.
 *
 * <p>The signed runtime updater writes both a package-local marker and a runtime-root denylist
 * before it deactivates a revoked package. Checking both keeps a stale model-registry entry from
 * launching that package even if cleanup or a registry rebind was interrupted.
 */
public final class LlamaRuntimeTrustGuard {

    public static final String REVOCATION_MARKER_FILE = ".kortty-runtime-revoked";
    public static final String REVOCATION_LIST_FILE = "revoked-v1";
    private static final int MAX_ANCESTORS = 16;
    private static final long MAX_REVOCATION_LIST_BYTES = 256 * 1024;

    private LlamaRuntimeTrustGuard() {
    }

    public static boolean isRevoked(Path executable) {
        if (executable == null) {
            return false;
        }
        Path current = executable.toAbsolutePath().normalize().getParent();
        for (int depth = 0; current != null && depth < MAX_ANCESTORS; depth++) {
            if (Files.isRegularFile(current.resolve(REVOCATION_MARKER_FILE), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            Path parent = current.getParent();
            if (parent != null && parent.getFileName() != null
                && "packages".equals(parent.getFileName().toString())) {
                Path runtimeRoot = parent.getParent();
                if (runtimeRoot != null && denylistContains(
                    runtimeRoot.resolve(REVOCATION_LIST_FILE), current.getFileName().toString())) {
                    return true;
                }
            }
            current = parent;
        }
        return false;
    }

    public static void requireAllowed(Path executable) {
        if (isRevoked(executable)) {
            throw new LlamaRuntimeException(
                "This llama.cpp runtime was revoked by the signed korTTY runtime index and is blocked. "
                    + "Install a supported stable runtime before using local AI again.");
        }
    }

    private static boolean denylistContains(Path denylist, String installationId) {
        try {
            if (!Files.isRegularFile(denylist, LinkOption.NOFOLLOW_LINKS)
                || Files.size(denylist) > MAX_REVOCATION_LIST_BYTES) {
                return false;
            }
            Set<String> entries;
            try (var lines = Files.lines(denylist, StandardCharsets.UTF_8)) {
                entries = lines.map(String::trim).filter(value -> !value.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            }
            return entries.contains(installationId);
        } catch (IOException | RuntimeException ignored) {
            // The package-local marker is the second, independent guard. A malformed denylist is
            // surfaced by the updater and must not make unrelated external executables unusable.
            return false;
        }
    }
}
