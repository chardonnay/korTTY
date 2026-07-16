package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeUpdateServiceTest {

    @Test
    void notifyQuarantinesARevokedActiveRuntimeWithoutDownloadingReplacement() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-notify-revoked-");
        Map<URI, byte[]> packages = new ConcurrentHashMap<>();
        AtomicInteger downloads = new AtomicInteger();
        try {
            byte[] activeArchive = runtimeZip("active");
            byte[] replacementArchive = runtimeZip("replacement");
            LlamaRuntimePackageDescriptor active = descriptor("llama-b10024-kortty1", activeArchive);
            LlamaRuntimePackageDescriptor replacement = descriptor("llama-b10025-kortty1", replacementArchive);
            packages.put(active.downloadUri(), activeArchive);
            packages.put(replacement.downloadUri(), replacementArchive);
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(root, uri -> {
                downloads.incrementAndGet();
                return new ByteArrayInputStream(packages.get(uri));
            });
            installer.installAndActivate(active, () -> true, installation -> true);
            downloads.set(0);
            LlamaRuntimeIndex index = new LlamaRuntimeIndex(
                1, Instant.now(), List.of(active, replacement), Set.of(active.runtimeId()));
            AtomicInteger blocks = new AtomicInteger();
            LlamaRuntimeUpdateService service = new LlamaRuntimeUpdateService(
                () -> index,
                new LlamaRuntimeSelector(),
                installer,
                () -> "2.5.2",
                1,
                (verifiedIndex, installation) -> {
                    blocks.incrementAndGet();
                    installer.applyRevocations(verifiedIndex);
                });

            LlamaRuntimeUpdateResult result = service.checkAndMaybeApply(
                LlamaRuntimeUpdatePolicy.NOTIFY, LlamaBackend.CPU, () -> true, installation -> true);

            assertThat(result.status()).isEqualTo(LlamaRuntimeUpdateResult.Status.REVOKED);
            assertThat(result.revokedRuntimeId()).isEqualTo(active.runtimeId());
            assertThat(result.availablePackage()).isEqualTo(replacement);
            assertThat(installer.active()).isEmpty();
            assertThat(installer.blockedActiveRuntimeId()).hasValue(active.runtimeId());
            assertThat(blocks.get()).isEqualTo(1);
            assertThat(downloads.get()).isEqualTo(0);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void automaticPolicyInstallsNonRevokedReplacementAfterBlockingActiveRuntime() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-auto-revoked-");
        Map<URI, byte[]> packages = new ConcurrentHashMap<>();
        try {
            byte[] activeArchive = runtimeZip("active");
            byte[] replacementArchive = runtimeZip("replacement");
            LlamaRuntimePackageDescriptor active = descriptor("llama-b10024-kortty1", activeArchive);
            LlamaRuntimePackageDescriptor replacement = descriptor("llama-b10025-kortty1", replacementArchive);
            packages.put(active.downloadUri(), activeArchive);
            packages.put(replacement.downloadUri(), replacementArchive);
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(packages.get(uri)));
            installer.installAndActivate(active, () -> true, installation -> true);
            LlamaRuntimeIndex index = new LlamaRuntimeIndex(
                1, Instant.now(), List.of(active, replacement), Set.of(active.runtimeId()));
            AtomicBoolean stopped = new AtomicBoolean();
            LlamaRuntimeUpdateService service = new LlamaRuntimeUpdateService(
                () -> index,
                new LlamaRuntimeSelector(),
                installer,
                () -> "2.5.2",
                1,
                (verifiedIndex, installation) -> {
                    installer.applyRevocations(verifiedIndex);
                    stopped.set(true);
                });

            LlamaRuntimeUpdateResult result = service.checkAndMaybeApply(
                LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE,
                LlamaBackend.CPU,
                stopped::get,
                installation -> true);

            assertThat(result.status()).isEqualTo(LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH);
            assertThat(result.revokedRuntimeId()).isEqualTo(active.runtimeId());
            assertThat(result.availablePackage()).isEqualTo(replacement);
            assertThat(installer.active().orElseThrow().descriptor().runtimeId())
                .isEqualTo(replacement.runtimeId());
            assertThat(installer.pendingActivation()).isPresent();
            assertThat(installer.blockedActiveRuntimeId()).isEmpty();
        } finally {
            deleteTree(root);
        }
    }

    private static LlamaRuntimePackageDescriptor descriptor(String id, byte[] archive) throws Exception {
        String tag = id.substring("llama-".length(), id.indexOf("-kortty"));
        String executable = LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
            ? "bin/llama-server.exe" : "bin/llama-server";
        return new LlamaRuntimePackageDescriptor(
            id,
            tag,
            LlamaRuntimeReleaseConfiguration.BASELINE_COMMIT,
            1,
            "2.5.2",
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            LlamaBackend.CPU,
            archive.length,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive)),
            URI.create("https://example.test/" + id + ".zip"),
            executable,
            false);
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

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((first, second) -> second.compareTo(first)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
