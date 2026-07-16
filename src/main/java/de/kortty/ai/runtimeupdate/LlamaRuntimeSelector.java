package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.update.UpdateVersion;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects only compatible, non-revoked stable packages from a verified index. */
public final class LlamaRuntimeSelector {

    private static final Pattern BUILD_NUMBER = Pattern.compile("llama-b([0-9]+)-kortty([0-9]+)");

    public Optional<LlamaRuntimePackageDescriptor> select(
        LlamaRuntimeIndex index,
        LlamaRuntimePlatform platform,
        String architecture,
        LlamaBackend requestedBackend,
        int supportedApiContractVersion,
        String currentKorttyVersion
    ) {
        if (index == null || platform == null || requestedBackend == null) {
            throw new IllegalArgumentException("Runtime selection parameters are required.");
        }
        String normalizedArchitecture = LlamaRuntimePackageDescriptor.normalizeArchitecture(architecture);
        LlamaBackend concreteBackend = requestedBackend == LlamaBackend.AUTO
            ? platform == LlamaRuntimePlatform.MACOS ? LlamaBackend.METAL : LlamaBackend.CPU
            : requestedBackend;
        UpdateVersion currentVersion = UpdateVersion.parse(currentKorttyVersion)
            .orElseThrow(() -> new IllegalArgumentException("Current korTTY version is invalid."));
        return index.packages().stream()
            .filter(descriptor -> !index.isRevoked(descriptor))
            .filter(descriptor -> descriptor.platform() == platform)
            .filter(descriptor -> descriptor.architecture().equals(normalizedArchitecture))
            .filter(descriptor -> descriptor.backend() == concreteBackend)
            .filter(descriptor -> descriptor.apiContractVersion() == supportedApiContractVersion)
            .filter(descriptor -> UpdateVersion.parse(descriptor.minimumKorttyVersion())
                .map(minimum -> currentVersion.compareTo(minimum) >= 0).orElse(false))
            .max(Comparator.comparingLong(LlamaRuntimeSelector::buildSortKey));
    }

    public boolean isNewer(
        LlamaRuntimePackageDescriptor candidate,
        LlamaRuntimePackageDescriptor installed
    ) {
        if (candidate == null || installed == null) {
            throw new IllegalArgumentException("Both runtime descriptors are required.");
        }
        return buildSortKey(candidate) > buildSortKey(installed);
    }

    private static long buildSortKey(LlamaRuntimePackageDescriptor descriptor) {
        Matcher matcher = BUILD_NUMBER.matcher(descriptor.runtimeId());
        if (!matcher.matches()) {
            return -1;
        }
        try {
            long upstream = Long.parseLong(matcher.group(1));
            long revision = Long.parseLong(matcher.group(2));
            return Math.addExact(Math.multiplyExact(upstream, 1_000_000L), revision);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return -1;
        }
    }
}
