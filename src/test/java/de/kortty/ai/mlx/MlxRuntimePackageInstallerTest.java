package de.kortty.ai.mlx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MlxRuntimePackageInstallerTest {

    private static final String OLD_INSTALLATION_ID = "mlx-0.31.2-kortty1";
    private static final String NEW_INSTALLATION_ID = "mlx-0.31.3-kortty1";

    @Test
    void localArchiveInstallActivatesPointerSetsExecBitAndPrunesOldPackages() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-install-local-");
        Path runtimeRoot = createOldInstallation(directory);
        byte[] archive = runtimeZip();
        Path archiveFile = Files.write(directory.resolve("mlx-runtime.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            MlxRuntimeLocator.MlxRuntimeInstallation installation =
                fixture.installer.installFromLocalPackage(manager, archiveFile);

            assertThat(installation.id()).isEqualTo(NEW_INSTALLATION_ID);
            assertThat(Files.readString(runtimeRoot.resolve("active")).trim())
                .isEqualTo(NEW_INSTALLATION_ID);
            assertThat(Files.isExecutable(installation.pythonExecutable())).isTrue();
            assertThat(Files.isRegularFile(installation.launcherScript())).isTrue();
            assertThat(fixture.sanityLaunches.get()).isEqualTo(1);
            assertThat(Files.exists(runtimeRoot.resolve("packages").resolve(OLD_INSTALLATION_ID)))
                .isFalse();
            assertThat(fixture.installer.active().orElseThrow().id()).isEqualTo(NEW_INSTALLATION_ID);
        }
    }

    @Test
    void channelArchiveWithInstallationIdWrapperDirectoryInstallsIdentically() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-install-wrapped-");
        Path runtimeRoot = directory.resolve("runtime");
        // The stable channel zips the package under its installation-id directory; the single
        // wrapper level must be transparent to the layout validation.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(NEW_INSTALLATION_ID + "/python/bin/python3", "#!/bin/sh\necho fake interpreter\n");
        entries.put(NEW_INSTALLATION_ID + "/kortty_mlx_server.py", "# authenticated launcher");
        byte[] archive = zip(entries);
        Path archiveFile = Files.write(directory.resolve("mlx-runtime-wrapped.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            MlxRuntimeLocator.MlxRuntimeInstallation installation =
                fixture.installer.installFromLocalPackage(manager, archiveFile);

            assertThat(installation.id()).isEqualTo(NEW_INSTALLATION_ID);
            assertThat(Files.isRegularFile(installation.pythonExecutable())).isTrue();
            assertThat(Files.isRegularFile(installation.launcherScript())).isTrue();
            assertThat(fixture.installer.active().orElseThrow().id()).isEqualTo(NEW_INSTALLATION_ID);
        }
    }

    @Test
    void installFromIndexDownloadsVerifiesAndActivatesTheNewestEntry() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-install-index-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)), archive);
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            MlxRuntimeLocator.MlxRuntimeInstallation installation =
                fixture.installer.installFromIndex(manager);

            assertThat(installation.id()).isEqualTo(NEW_INSTALLATION_ID);
            assertThat(fixture.installer.active().orElseThrow().id()).isEqualTo(NEW_INSTALLATION_ID);
            assertThat(fixture.sanityLaunches.get()).isEqualTo(1);
        }
    }

    @Test
    void downloadedPackageWithWrongHashIsRejected() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-sha-mismatch-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        byte[] corrupted = archive.clone();
        corrupted[corrupted.length - 1] ^= 0x5a;
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)), corrupted);
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            IOException error = expectThrows(
                IOException.class, () -> fixture.installer.installFromIndex(manager));

            assertThat(error).hasMessageThat().contains("SHA-256 does not match");
            assertThat(fixture.installer.active()).isEmpty();
            assertThat(fixture.sanityLaunches.get()).isEqualTo(0);
        }
    }

    @Test
    void localArchiveNotPublishedBySignedChannelIsRejected() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-unpublished-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        Path foreignArchive = Files.write(
            directory.resolve("foreign.zip"), "unsigned bytes".getBytes(StandardCharsets.UTF_8));
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            IOException error = expectThrows(
                IOException.class,
                () -> fixture.installer.installFromLocalPackage(manager, foreignArchive));

            assertThat(error).hasMessageThat().contains("not published by the signed stable MLX channel");
            assertThat(fixture.installer.active()).isEmpty();
        }
    }

    @Test
    void revokedPackageIsRejectedForLocalAndChannelInstallation() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-revoked-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        Path archiveFile = Files.write(directory.resolve("mlx-runtime.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, true)), archive);
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            IOException local = expectThrows(
                IOException.class,
                () -> fixture.installer.installFromLocalPackage(manager, archiveFile));
            IOException channel = expectThrows(
                IOException.class, () -> fixture.installer.installFromIndex(manager));

            assertThat(local).hasMessageThat().contains("revoked");
            assertThat(channel).hasMessageThat().contains("no compatible runtime package");
            assertThat(fixture.installer.active()).isEmpty();
        }
    }

    @Test
    void zipSlipEntriesNeverEscapeTheInstallationDirectory() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-zipslip-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = zip(Map.of("../escaped", "bad"));
        Path archiveFile = Files.write(directory.resolve("evil.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            IOException error = expectThrows(
                IOException.class,
                () -> fixture.installer.installFromLocalPackage(manager, archiveFile));

            assertThat(error).hasMessageThat().contains("outside its installation directory");
            assertThat(Files.exists(runtimeRoot.resolve("packages").resolve("escaped"))).isFalse();
            assertThat(fixture.installer.active()).isEmpty();
        }
    }

    @Test
    void tamperedIndexSignatureFailsClosed() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-bad-signature-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        Path archiveFile = Files.write(directory.resolve("mlx-runtime.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        fixture.tamperIndex();
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            // A flipped base64 character can decode either to a valid-length signature that simply
            // fails verification (verify == false) or to a malformed Ed25519 point that raises a
            // SignatureException. Both are correct fail-closed outcomes, so assert only that the
            // install is refused with an IOException and no runtime is ever activated.
            expectThrows(
                IOException.class,
                () -> fixture.installer.installFromLocalPackage(manager, archiveFile));

            assertThat(fixture.installer.active()).isEmpty();
        }
    }

    @Test
    void busySidecarBlocksInstallationAndUninstall() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-busy-");
        Path runtimeRoot = createOldInstallation(directory);
        byte[] archive = runtimeZip();
        Path archiveFile = Files.write(directory.resolve("mlx-runtime.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        try (fixture; MlxRuntimeManager manager = fixture.managerWithModel(directory)) {
            try (MlxRuntimeManager.RuntimeLease ignored = manager.acquire("busy-model")) {
                expectThrows(
                    MlxRuntimePackageInstaller.MlxRuntimeBusyException.class,
                    () -> fixture.installer.installFromLocalPackage(manager, archiveFile));
                expectThrows(
                    MlxRuntimePackageInstaller.MlxRuntimeBusyException.class,
                    () -> fixture.installer.uninstall(manager));
            }
            // The previous runtime stays fully intact after a refused switch.
            assertThat(fixture.installer.active().orElseThrow().id()).isEqualTo(OLD_INSTALLATION_ID);
        }
    }

    @Test
    void uninstallStopsIdleSidecarsAndRemovesRuntimeCompletely() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-uninstall-");
        Path runtimeRoot = createOldInstallation(directory);
        byte[] archive = runtimeZip();
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        try (fixture; MlxRuntimeManager manager = fixture.managerWithModel(directory)) {
            manager.acquire("busy-model").close();

            fixture.installer.uninstall(manager);

            assertThat(fixture.installer.active()).isEmpty();
            assertThat(Files.exists(runtimeRoot.resolve("active"))).isFalse();
            assertThat(Files.exists(runtimeRoot.resolve("packages").resolve(OLD_INSTALLATION_ID)))
                .isFalse();
            assertThat(fixture.lastProcessAlive()).isFalse();
        }
    }

    @Test
    void sanityCheckFailureLeavesThePreviousRuntimeActive() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-sanity-failure-");
        Path runtimeRoot = createOldInstallation(directory);
        byte[] archive = runtimeZip();
        Path archiveFile = Files.write(directory.resolve("mlx-runtime.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)));
        fixture.failSanityCheck.set(true);
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            IOException error = expectThrows(
                IOException.class,
                () -> fixture.installer.installFromLocalPackage(manager, archiveFile));

            assertThat(error).hasMessageThat().contains("sanity");
            assertThat(fixture.installer.active().orElseThrow().id()).isEqualTo(OLD_INSTALLATION_ID);
        }
    }

    @Test
    void applyRevocationsRecordsADurableDenylistThatFailsTheActivePointerClosed() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-denylist-roundtrip-");
        Path runtimeRoot = createOldInstallation(directory);
        MlxRuntimePackageInstaller installer = bareInstaller(runtimeRoot);

        // The signed index withdraws the currently active installation id.
        installer.applyRevocations(new MlxRuntimeIndex(
            1, Instant.now(), List.of(), Set.of(OLD_INSTALLATION_ID)));

        assertThat(installer.active()).isEmpty();
        assertThat(Files.exists(runtimeRoot.resolve("active"))).isFalse();
        assertThat(installer.blockedActiveRuntimeId()).hasValue(OLD_INSTALLATION_ID);

        // The denylist and blocked-active marker are durable: a fresh installer over the same root
        // keeps failing closed, mirroring the llama.cpp denylist that survives an uninstall.
        MlxRuntimePackageInstaller reopened = bareInstaller(runtimeRoot);
        assertThat(reopened.active()).isEmpty();
        assertThat(reopened.blockedActiveRuntimeId()).hasValue(OLD_INSTALLATION_ID);
    }

    @Test
    void revokedActivePackageIsBlockedAndCanNeverBeReinstalled() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-block-reinstall-");
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        byte[] archive = runtimeZip();
        Path archiveFile = Files.write(directory.resolve("mlx-runtime.zip"), archive);
        Fixture fixture = Fixture.withSignedIndex(runtimeRoot, index(entry(archive, false)), archive);
        try (fixture; MlxRuntimeManager manager = fixture.idleManager(directory)) {
            MlxRuntimeLocator.MlxRuntimeInstallation installed =
                fixture.installer.installFromLocalPackage(manager, archiveFile);
            assertThat(installed.id()).isEqualTo(NEW_INSTALLATION_ID);

            // A later signed index revokes the active package; enforce it fail-closed.
            MlxRuntimeIndex revokedIndex = new MlxRuntimeIndex(
                1, Instant.now(), List.of(), Set.of(NEW_INSTALLATION_ID));
            fixture.installer.blockRevokedActive(manager, revokedIndex, installed);

            assertThat(fixture.installer.active()).isEmpty();
            assertThat(fixture.installer.blockedActiveRuntimeId()).hasValue(NEW_INSTALLATION_ID);

            // The withdrawn id is refused on every subsequent install path.
            IOException local = expectThrows(
                IOException.class,
                () -> fixture.installer.installFromLocalPackage(manager, archiveFile));
            IOException channel = expectThrows(
                IOException.class, () -> fixture.installer.installFromIndex(manager));
            assertThat(local).hasMessageThat().contains("revoked");
            assertThat(channel).hasMessageThat().contains("no compatible runtime package");
            assertThat(fixture.installer.active()).isEmpty();
        }
    }

    private static MlxRuntimePackageInstaller bareInstaller(Path runtimeRoot) {
        return new MlxRuntimePackageInstaller(
            runtimeRoot,
            () -> {
                throw new AssertionError("A denylist round-trip must not fetch the signed index.");
            },
            uri -> {
                throw new AssertionError("A denylist round-trip must not download packages.");
            },
            (packageDirectory, pythonExecutable) -> { },
            () -> true);
    }

    private static JsonObject entry(byte[] archive, boolean revoked) throws Exception {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("runtimeId", revoked ? "mlx-0.31.3-revoked" : "mlx-0.31.3");
        object.addProperty("installationId", NEW_INSTALLATION_ID);
        object.addProperty("platform", "macos");
        object.addProperty("architecture", "aarch64");
        object.addProperty("backend", "MLX");
        object.addProperty("minimumOsVersion", "1.0");
        object.addProperty("mlxLmVersion", "0.31.3");
        object.addProperty("pythonVersion", "3.12.6");
        object.addProperty("sourceCommit", "a3e5b96ac5e278c390df429df0b68efcee3ee1b5");
        object.addProperty("executablePath", "python/bin/python3");
        object.addProperty("launcherPath", "kortty_mlx_server.py");
        object.addProperty("sizeBytes", archive.length);
        object.addProperty("sha256", sha256(archive));
        object.addProperty("requirementsLockSha256", sha256("lock".getBytes(StandardCharsets.UTF_8)));
        object.addProperty("downloadUrl", "http://127.0.0.1/PLACEHOLDER/mlx-runtime.zip");
        if (revoked) {
            object.addProperty("revoked", true);
        }
        return object;
    }

    private static JsonObject index(JsonObject... entries) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("generatedAt", Instant.now().toString());
        JsonArray revoked = new JsonArray();
        JsonArray packages = new JsonArray();
        for (JsonObject entry : entries) {
            if (entry.has("revoked")) {
                entry.remove("revoked");
                revoked.add(entry.get("runtimeId").getAsString());
            }
            packages.add(entry);
        }
        root.add("revokedRuntimeIds", revoked);
        root.add("packages", packages);
        return root;
    }

    private static Path createOldInstallation(Path directory) throws Exception {
        Path runtimeRoot = directory.resolve("mlx").resolve("runtime");
        Path packageDirectory = Files.createDirectories(
            runtimeRoot.resolve("packages").resolve(OLD_INSTALLATION_ID));
        Path python = Files.createDirectories(packageDirectory.resolve("python").resolve("bin"))
            .resolve("python3");
        Files.writeString(python, "old interpreter");
        assertThat(python.toFile().setExecutable(true)).isTrue();
        Files.writeString(packageDirectory.resolve("kortty_mlx_server.py"), "# old launcher");
        Files.writeString(runtimeRoot.resolve("active"), OLD_INSTALLATION_ID + System.lineSeparator());
        return runtimeRoot;
    }

    private static byte[] runtimeZip() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("python/bin/python3", "#!/bin/sh\necho fake interpreter\n");
        entries.put("kortty_mlx_server.py", "# authenticated launcher");
        return zip(entries);
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

    /** Loopback release channel: signed index + signature + package, plus the installer under test. */
    private static final class Fixture implements AutoCloseable {

        final MlxRuntimePackageInstaller installer;
        final AtomicInteger sanityLaunches = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean failSanityCheck =
            new java.util.concurrent.atomic.AtomicBoolean();
        private final HttpServer server;
        private volatile byte[] indexBytes;
        private volatile byte[] signatureBytes;
        private final java.util.List<MlxRuntimeManagerTest.FakeProcess> processes =
            new java.util.concurrent.CopyOnWriteArrayList<>();

        private Fixture(Path runtimeRoot, JsonObject index, byte[] servedArchive) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            int port = server.getAddress().getPort();
            String base = "http://127.0.0.1:" + port;
            rewriteDownloadUrls(index, base + "/mlx-runtime.zip");
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            indexBytes = index.toString().getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(indexBytes);
            signatureBytes = Base64.getEncoder().encode(signer.sign());
            server.createContext("/mlx-runtime-index-v1.json", exchange -> respond(exchange, indexBytes));
            server.createContext("/mlx-runtime-index-v1.sig", exchange -> respond(exchange, signatureBytes));
            if (servedArchive != null) {
                server.createContext("/mlx-runtime.zip", exchange -> respond(exchange, servedArchive));
            }
            MlxRuntimeIndexClient indexClient = new MlxRuntimeIndexClient(
                URI.create(base + "/mlx-runtime-index-v1.json"),
                URI.create(base + "/mlx-runtime-index-v1.sig"),
                keyPair.getPublic());
            installer = new MlxRuntimePackageInstaller(
                runtimeRoot,
                indexClient::fetch,
                uri -> uri.toURL().openStream(),
                (packageDirectory, pythonExecutable) -> {
                    if (failSanityCheck.get()) {
                        throw new IOException("The MLX runtime sanity check failed: simulated");
                    }
                    sanityLaunches.incrementAndGet();
                    assertThat(Files.isExecutable(pythonExecutable)).isTrue();
                },
                () -> true);
        }

        static Fixture withSignedIndex(Path runtimeRoot, JsonObject index) throws Exception {
            return new Fixture(runtimeRoot, index, null);
        }

        static Fixture withSignedIndex(Path runtimeRoot, JsonObject index, byte[] servedArchive)
            throws Exception {
            return new Fixture(runtimeRoot, index, servedArchive);
        }

        void tamperIndex() {
            byte[] tampered = signatureBytes.clone();
            tampered[4] = (byte) (tampered[4] == 'A' ? 'B' : 'A');
            signatureBytes = tampered;
        }

        MlxRuntimeManager idleManager(Path directory) throws Exception {
            return manager(directory, false);
        }

        MlxRuntimeManager managerWithModel(Path directory) throws Exception {
            return manager(directory, true);
        }

        boolean lastProcessAlive() {
            return !processes.isEmpty() && processes.get(processes.size() - 1).isAlive();
        }

        private MlxRuntimeManager manager(Path directory, boolean withModel) throws Exception {
            MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
            if (withModel) {
                Path modelDirectory = Files.createDirectories(directory.resolve("busy-model-dir"));
                Files.writeString(modelDirectory.resolve("config.json"), "{}");
                registry.register(new MlxModel("busy-model", "Busy Model", modelDirectory));
            }
            return new MlxRuntimeManager(
                registry,
                new MlxRuntimeLocator(directory.resolve("mlx").resolve("runtime")),
                directory.resolve("run"),
                Duration.ofSeconds(5),
                (command, environment, workingDirectory, logFile) -> {
                    MlxRuntimeManagerTest.FakeProcess process = new MlxRuntimeManagerTest.FakeProcess();
                    processes.add(process);
                    return process;
                },
                (process, healthEndpoint, timeout) -> { },
                () -> 26000);
        }

        private static void rewriteDownloadUrls(JsonObject index, String downloadUrl) {
            for (var element : index.getAsJsonArray("packages")) {
                element.getAsJsonObject().addProperty("downloadUrl", downloadUrl);
            }
        }

        private static void respond(HttpExchange exchange, byte[] body) throws IOException {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
