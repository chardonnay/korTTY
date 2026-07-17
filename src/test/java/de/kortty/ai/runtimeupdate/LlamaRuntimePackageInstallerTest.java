package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaRuntimeTrustGuard;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LlamaRuntimePackageInstallerTest {

    @Test
    void installsSideBySideAndRollsBackFailedActivation() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-install");
        Map<URI, byte[]> packages = new ConcurrentHashMap<>();
        try {
            byte[] firstArchive = runtimeZip("first");
            byte[] secondArchive = runtimeZip("second");
            LlamaRuntimePackageDescriptor first = descriptor("llama-b10024-kortty1", firstArchive);
            LlamaRuntimePackageDescriptor second = descriptor("llama-b10025-kortty1", secondArchive);
            packages.put(first.downloadUri(), firstArchive);
            packages.put(second.downloadUri(), secondArchive);
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(packages.get(uri)));

            LlamaRuntimeActivationResult activated = installer.installAndActivate(
                first, () -> true, installation -> true);
            LlamaRuntimeActivationResult rolledBack = installer.installAndActivate(
                second, () -> true, installation -> false);

            assertThat(activated.status()).isEqualTo(LlamaRuntimeActivationResult.Status.ACTIVATED);
            assertThat(rolledBack.status()).isEqualTo(LlamaRuntimeActivationResult.Status.ROLLED_BACK);
            assertThat(installer.active().orElseThrow().descriptor().runtimeId())
                .isEqualTo(first.runtimeId());
            assertThat(installer.installed().stream().map(value -> value.descriptor().runtimeId()).toList())
                .containsExactly(first.runtimeId());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void leavesPackageStagedWhileGenerationIsActive() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-staged");
        try {
            byte[] archive = runtimeZip("staged");
            LlamaRuntimePackageDescriptor descriptor = descriptor("llama-b10025-kortty1", archive);
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(archive));

            LlamaRuntimeActivationResult result = installer.installAndActivate(
                descriptor, () -> false, installation -> {
                    throw new AssertionError("Health check must wait until idle.");
                });

            assertThat(result.status())
                .isEqualTo(LlamaRuntimeActivationResult.Status.STAGED_UNTIL_IDLE);
            assertThat(installer.active()).isEmpty();
            assertThat(installer.installed()).hasSize(1);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void rejectsZipSlipEntryWithoutReplacingActiveRuntime() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-zipslip");
        try {
            byte[] archive = zip(Map.of("../escaped", "bad"));
            LlamaRuntimePackageDescriptor descriptor = descriptor("llama-b10025-kortty1", archive);
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(archive));

            expectThrows(java.io.IOException.class, () -> installer.install(descriptor));

            assertThat(Files.exists(root.resolve("escaped"))).isFalse();
            assertThat(installer.active()).isEmpty();
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void refusesActivationWhenAnyInstalledPayloadFileFailsIntegrityVerification() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-integrity");
        try {
            String executable = LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
                ? "bin/llama-server.exe" : "bin/llama-server";
            byte[] archive = zip(Map.of(
                executable, "#!/bin/sh\necho verified\n",
                "lib/runtime-backend.bin", "verified native backend"));
            LlamaRuntimePackageDescriptor descriptor = descriptor("llama-b10025-kortty1", archive);
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(archive));
            LlamaRuntimeInstallation installation = installer.install(descriptor);
            Files.writeString(
                installation.directory().resolve("lib/runtime-backend.bin"),
                "tampered native backend",
                StandardCharsets.UTF_8);

            IOException error = expectThrows(IOException.class, () -> installer.installAndActivate(
                descriptor,
                () -> true,
                candidate -> {
                    throw new AssertionError("A corrupted runtime must fail before its health check.");
                }));

            assertThat(error).hasMessageThat().contains("integrity verification");
            assertThat(installer.active()).isEmpty();
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void managedLaunchVerifierRejectsUnexpectedFilesAndMissingManifest() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-launch-integrity");
        try {
            byte[] archive = runtimeZip("launch");
            LlamaRuntimePackageDescriptor descriptor = descriptor("llama-b10025-kortty1", archive);
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(archive));
            LlamaRuntimeInstallation installation = installer.install(descriptor);

            LlamaRuntimePackageIntegrity.verifyManagedExecutable(installation.executable());
            Files.writeString(installation.directory().resolve("injected-library.so"), "unexpected");
            IOException unexpected = expectThrows(IOException.class, () ->
                LlamaRuntimePackageIntegrity.verifyManagedExecutable(installation.executable()));
            assertThat(unexpected).hasMessageThat().contains("unexpected files");

            Files.delete(installation.directory().resolve("injected-library.so"));
            Files.delete(installation.directory().resolve(LlamaRuntimePackageIntegrity.MANIFEST_FILE));
            IOException missing = expectThrows(IOException.class, () ->
                LlamaRuntimePackageIntegrity.verifyManagedExecutable(installation.executable()));
            assertThat(missing).hasMessageThat().contains("manifest is missing");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void signedRevocationDeactivatesQuarantinesAndPreventsReinstallation() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-revoked");
        byte[] archive = runtimeZip("revoked");
        LlamaRuntimePackageDescriptor descriptor = descriptor("llama-b10025-kortty1", archive);
        LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
            root, uri -> new ByteArrayInputStream(archive));
        try {
            LlamaRuntimeInstallation active = installer.installAndActivate(
                descriptor, () -> true, installation -> true).installation();
            LlamaRuntimeIndex index = new LlamaRuntimeIndex(
                1, Instant.now(), List.of(descriptor), Set.of(descriptor.runtimeId()));

            java.util.Optional<LlamaRuntimeInstallation> revoked = installer.applyRevocations(index);

            assertThat(revoked).isPresent();
            assertThat(revoked.orElseThrow().descriptor().runtimeId()).isEqualTo(descriptor.runtimeId());
            assertThat(installer.active()).isEmpty();
            assertThat(installer.blockedActiveRuntimeId()).hasValue(descriptor.runtimeId());
            assertThat(LlamaRuntimeTrustGuard.isRevoked(active.executable())).isTrue();
            IOException rejected = expectThrows(IOException.class, () -> installer.install(descriptor));
            assertThat(rejected).hasMessageThat().contains("revoked");
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void rollbackNeverSelectsARevokedHealthyHistoryPackage() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-revoked-history");
        Map<URI, byte[]> packages = new ConcurrentHashMap<>();
        byte[] firstArchive = runtimeZip("first");
        byte[] secondArchive = runtimeZip("second");
        LlamaRuntimePackageDescriptor first = descriptor("llama-b10024-kortty1", firstArchive);
        LlamaRuntimePackageDescriptor second = descriptor("llama-b10025-kortty1", secondArchive);
        packages.put(first.downloadUri(), firstArchive);
        packages.put(second.downloadUri(), secondArchive);
        LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
            root, uri -> new ByteArrayInputStream(packages.get(uri)));
        try {
            LlamaRuntimeInstallation firstInstallation = installer.installAndActivate(
                first, () -> true, installation -> true).installation();
            installer.installAndActivate(second, () -> true, installation -> true);
            installer.applyRevocations(new LlamaRuntimeIndex(
                1, Instant.now(), List.of(first, second), Set.of(first.runtimeId())));
            // Defense in depth: even a stale/corrupted history entry must never override the
            // persisted signed revocation decision.
            Files.writeString(root.resolve("healthy-history-v1"),
                second.installationId() + System.lineSeparator()
                    + first.installationId() + System.lineSeparator());

            assertThat(installer.rollbackAfterFailedLaunch()).isEmpty();

            assertThat(installer.active()).isEmpty();
            assertThat(LlamaRuntimeTrustGuard.isRevoked(firstInstallation.executable())).isTrue();
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void pendingFirstLaunchSurvivesRestartAndPromotesOnlyAfterApiReadyConfirmation() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-pending-restart");
        byte[] archive = runtimeZip("pending");
        LlamaRuntimePackageDescriptor descriptor = descriptor("llama-b10025-kortty1", archive);
        try {
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(archive));
            LlamaRuntimeInstallation installation = installer.installAndActivate(
                descriptor, () -> true, candidate -> true).installation();

            LlamaRuntimePackageInstaller reopened = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(archive));
            assertThat(reopened.pendingActivation()).isPresent();
            assertThat(Files.exists(root.resolve("healthy-history-v1"))).isFalse();

            assertThat(reopened.confirmPendingFirstLaunch(installation.executable())).isPresent();

            assertThat(reopened.pendingActivation()).isEmpty();
            assertThat(reopened.active().orElseThrow().descriptor().runtimeId())
                .isEqualTo(descriptor.runtimeId());
            assertThat(Files.readString(root.resolve("healthy-history-v1")))
                .contains(descriptor.installationId());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void realFirstLaunchFailureRestoresLastHealthyNonRevokedRuntime() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-pending-rollback");
        Map<URI, byte[]> packages = new ConcurrentHashMap<>();
        byte[] firstArchive = runtimeZip("healthy");
        byte[] secondArchive = runtimeZip("pending-failure");
        LlamaRuntimePackageDescriptor first = descriptor("llama-b10024-kortty1", firstArchive);
        LlamaRuntimePackageDescriptor second = descriptor("llama-b10025-kortty1", secondArchive);
        packages.put(first.downloadUri(), firstArchive);
        packages.put(second.downloadUri(), secondArchive);
        try {
            LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
                root, uri -> new ByteArrayInputStream(packages.get(uri)));
            LlamaRuntimeInstallation firstInstallation = installer.installAndActivate(
                first, () -> true, candidate -> true).installation();
            installer.confirmPendingFirstLaunch(firstInstallation.executable());
            LlamaRuntimeInstallation failedInstallation = installer.installAndActivate(
                second, () -> true, candidate -> true).installation();

            LlamaRuntimePackageInstaller.PendingRollback rollback = installer
                .rollbackPendingFirstLaunch(failedInstallation.executable()).orElseThrow();

            assertThat(rollback.restoredInstallation().descriptor().runtimeId())
                .isEqualTo(first.runtimeId());
            assertThat(installer.active().orElseThrow().descriptor().runtimeId())
                .isEqualTo(first.runtimeId());
            assertThat(installer.pendingActivation()).isEmpty();
            assertThat(installer.installed().stream().map(value -> value.descriptor().runtimeId()).toList())
                .containsExactly(first.runtimeId());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void uninstallAllRemovesRuntimeStateButPreservesRevocationEvidence() throws Exception {
        Path root = Files.createTempDirectory("kortty-runtime-uninstall");
        byte[] archive = runtimeZip("uninstall");
        LlamaRuntimePackageDescriptor descriptor = descriptor("llama-b10025-kortty1", archive);
        LlamaRuntimePackageInstaller installer = new LlamaRuntimePackageInstaller(
            root, uri -> new ByteArrayInputStream(archive));
        try {
            installer.installAndActivate(descriptor, () -> true, installation -> true);
            installer.applyRevocations(new LlamaRuntimeIndex(
                1, Instant.now(), List.of(descriptor), Set.of(descriptor.runtimeId())));

            installer.uninstallAll();

            assertThat(installer.active()).isEmpty();
            assertThat(installer.installed()).isEmpty();
            assertThat(installer.pendingActivation()).isEmpty();
            assertThat(Files.exists(root.resolve("active-v1"))).isFalse();
            assertThat(Files.exists(root.resolve("pending-first-launch-v1"))).isFalse();
            // Fail-closed evidence survives the uninstall: the denylist and the blocked-active
            // marker keep a withdrawn package blocked even after reinstalling.
            assertThat(Files.exists(root.resolve(LlamaRuntimeTrustGuard.REVOCATION_LIST_FILE))).isTrue();
            assertThat(installer.blockedActiveRuntimeId()).hasValue(descriptor.runtimeId());
            IOException rejected = expectThrows(IOException.class, () -> installer.install(descriptor));
            assertThat(rejected).hasMessageThat().contains("revoked");
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
            "a3e5b96ac5e278c390df429df0b68efcee3ee1b5",
            1,
            "2.5.2",
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            LlamaBackend.CPU,
            archive.length,
            sha256(archive),
            URI.create("https://downloads.example.test/" + id + ".zip"),
            executable,
            false);
    }

    private static byte[] runtimeZip(String marker) throws Exception {
        String executable = LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS
            ? "bin/llama-server.exe" : "bin/llama-server";
        return zip(Map.of(executable, "#!/bin/sh\necho " + marker + "\n"));
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
