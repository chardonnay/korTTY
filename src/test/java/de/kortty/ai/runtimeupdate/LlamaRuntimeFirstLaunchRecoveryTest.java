package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeFirstLaunchRecoveryTest {

    @Test
    void failedRealModelStartRollsPointerAndRegistryBackThenStopsManager() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-first-launch-recovery-");
        Path llamaDirectory = root.resolve("llm");
        Map<URI, byte[]> packages = new ConcurrentHashMap<>();
        byte[] firstArchive = runtimeZip("healthy");
        byte[] secondArchive = runtimeZip("fails-real-start");
        LlamaRuntimePackageDescriptor first = descriptor("llama-b10024-kortty1", firstArchive);
        LlamaRuntimePackageDescriptor second = descriptor("llama-b10025-kortty1", secondArchive);
        packages.put(first.downloadUri(), firstArchive);
        packages.put(second.downloadUri(), secondArchive);
        LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
            llamaDirectory.resolve("runtime"), uri -> new ByteArrayInputStream(packages.get(uri)));
        LlamaRuntimeInstallation previous = installer.installAndActivate(
            first, () -> true, candidate -> true).installation();
        installer.confirmPendingFirstLaunch(previous.executable());
        LlamaRuntimeInstallation pending = installer.installAndActivate(
            second, () -> true, candidate -> true).installation();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(llamaDirectory);
        registry.register(new LlamaModel(
            "text", "Text", root.resolve("text.gguf"), pending.executable()));
        AtomicInteger stops = new AtomicInteger();
        LlamaRuntimeFirstLaunchRecovery recovery = new LlamaRuntimeFirstLaunchRecovery(
            installer,
            registry,
            stops::incrementAndGet,
            Runnable::run);

        recovery.onStartFailure(pending.executable().toRealPath(), new java.io.IOException("API failed"));

        assertThat(installer.active().orElseThrow().descriptor().runtimeId()).isEqualTo(first.runtimeId());
        assertThat(installer.pendingActivation()).isEmpty();
        assertThat(LlamaModelRegistry.inDirectory(llamaDirectory)
            .find("text").orElseThrow().getServerExecutable())
            .isEqualTo(previous.executable().toAbsolutePath().normalize());
        assertThat(stops.get()).isEqualTo(1);
    }

    private static LlamaRuntimePackageDescriptor descriptor(String id, byte[] archive) throws Exception {
        String tag = id.substring("llama-".length(), id.indexOf("-kortty"));
        String executable = LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
            ? "bin/llama-server.exe" : "bin/llama-server";
        return new LlamaRuntimePackageDescriptor(
            id, tag, LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT, 1, "2.5.2",
            LlamaRuntimePlatform.current(), LlamaRuntimePackageDescriptor.currentArchitecture(),
            LlamaBackend.CPU, archive.length,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)),
            URI.create("https://example.test/" + id + ".zip"), executable, false);
    }

    private static byte[] runtimeZip(String marker) throws Exception {
        String executable = LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
            ? "bin/llama-server.exe" : "bin/llama-server";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(executable));
            output.write(("#!/bin/sh\necho " + marker + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
