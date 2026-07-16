package de.kortty.ai.runtimeupdate;

import java.nio.file.Path;

public record LlamaRuntimeInstallation(
    LlamaRuntimePackageDescriptor descriptor,
    Path directory,
    Path executable
) {
}
