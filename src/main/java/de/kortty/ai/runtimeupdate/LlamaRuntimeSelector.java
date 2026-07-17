package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.update.UpdateVersion;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
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
        Predicate<LlamaRuntimePackageDescriptor> compatible = descriptor -> isCompatible(
            index, descriptor, platform, architecture, supportedApiContractVersion, currentKorttyVersion);

        if (requestedBackend != LlamaBackend.AUTO) {
            return selectBackend(index, compatible, requestedBackend);
        }

        // Metal is the native default on macOS. Vulkan remains an explicit opt-in on Windows and
        // Linux because merely having a Vulkan-linked package does not prove that a usable driver
        // or GPU is present. AUTO therefore chooses the portable CPU package there, with the other
        // package only as a last-resort channel fallback.
        LlamaBackend preferredBackend = platform == LlamaRuntimePlatform.MACOS
            ? LlamaBackend.METAL
            : LlamaBackend.CPU;
        LlamaBackend fallbackBackend = platform == LlamaRuntimePlatform.MACOS
            ? LlamaBackend.CPU
            : LlamaBackend.VULKAN;
        Optional<LlamaRuntimePackageDescriptor> preferred = selectBackend(
            index, compatible, preferredBackend);
        return preferred.isPresent()
            ? preferred
            : selectBackend(index, compatible, fallbackBackend);
    }

    /** Shared compatibility gate for automatic selection and explicit local-archive installation. */
    public boolean isCompatible(
        LlamaRuntimeIndex index,
        LlamaRuntimePackageDescriptor descriptor,
        LlamaRuntimePlatform platform,
        String architecture,
        int supportedApiContractVersion,
        String currentKorttyVersion
    ) {
        if (index == null || descriptor == null || platform == null) {
            throw new IllegalArgumentException("Runtime compatibility parameters are required.");
        }
        String normalizedArchitecture = LlamaRuntimePackageDescriptor.normalizeArchitecture(architecture);
        UpdateVersion currentVersion = UpdateVersion.parse(currentKorttyVersion)
            .orElseThrow(() -> new IllegalArgumentException("Current korTTY version is invalid."));
        return !index.isRevoked(descriptor)
            && descriptor.platform() == platform
            && descriptor.architecture().equals(normalizedArchitecture)
            && descriptor.apiContractVersion() == supportedApiContractVersion
            && UpdateVersion.parse(descriptor.minimumKorttyVersion())
                .map(minimum -> currentVersion.compareTo(minimum) >= 0)
                .orElse(false);
    }

    private static Optional<LlamaRuntimePackageDescriptor> selectBackend(
        LlamaRuntimeIndex index,
        Predicate<LlamaRuntimePackageDescriptor> compatible,
        LlamaBackend backend
    ) {
        return index.packages().stream()
            .filter(compatible)
            .filter(descriptor -> descriptor.backend() == backend)
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
