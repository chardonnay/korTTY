package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.llama.LlamaRuntimeManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Promotes a runtime only after a real model-backed API start and restores the previous verified
 * package when that first start fails.
 */
public final class LlamaRuntimeFirstLaunchRecovery implements LlamaRuntimeManager.StartupListener {

    private static final String FAILED_BINDING = ".kortty-first-launch-failed";

    private final LlamaRuntimePackageInstaller installer;
    private final LlamaModelRegistry registry;
    private final Runnable stopRuntimeManager;
    private final Consumer<Runnable> asynchronousExecutor;

    public static LlamaRuntimeFirstLaunchRecovery createDefault(Path llamaDirectory) {
        Objects.requireNonNull(llamaDirectory, "llamaDirectory");
        return new LlamaRuntimeFirstLaunchRecovery(
            new LlamaRuntimePackageInstaller(llamaDirectory.resolve("runtime")),
            LlamaModelRegistry.inDirectory(llamaDirectory),
            LlamaRuntimeManager::shutdownDefault,
            task -> CompletableFuture.runAsync(task));
    }

    LlamaRuntimeFirstLaunchRecovery(
        LlamaRuntimePackageInstaller installer,
        LlamaModelRegistry registry,
        Runnable stopRuntimeManager,
        Consumer<Runnable> asynchronousExecutor
    ) {
        this.installer = Objects.requireNonNull(installer, "installer");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.stopRuntimeManager = Objects.requireNonNull(stopRuntimeManager, "stopRuntimeManager");
        this.asynchronousExecutor = Objects.requireNonNull(asynchronousExecutor, "asynchronousExecutor");
    }

    @Override
    public void onReady(Path executable) throws IOException {
        installer.confirmPendingFirstLaunch(executable);
    }

    @Override
    public void onStartFailure(Path executable, Throwable failure) throws IOException {
        Optional<LlamaRuntimePackageInstaller.PendingRollback> rollback =
            installer.rollbackPendingFirstLaunch(executable);
        if (rollback.isEmpty()) {
            return;
        }
        LlamaRuntimePackageInstaller.PendingRollback result = rollback.get();
        Path replacement = result.restoredInstallation() != null
            ? result.restoredInstallation().executable()
            : result.failedInstallation().directory().resolve(FAILED_BINDING);
        IOException recoveryFailure = null;
        try {
            registry.replaceServerExecutable(result.failedInstallation().executable(), replacement);
        } catch (RuntimeException e) {
            recoveryFailure = new IOException(
                "The previous llama.cpp runtime pointer was restored, but model bindings could not be rebound.", e);
        } finally {
            // This callback runs inside the failing RuntimeSlot monitor. Stop the default manager
            // asynchronously so close() cannot wait on the same monitor before failStart returns.
            asynchronousExecutor.accept(stopRuntimeManager);
        }
        if (recoveryFailure != null) {
            throw recoveryFailure;
        }
    }
}
