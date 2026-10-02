package de.kortty.jobscheduler;

import de.kortty.core.TemporaryKeyTestFixtures;
import de.kortty.core.TemporarySshKeyMaterial;
import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.server.SshServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/**
 * The scheduler still writes a {@code TEMPORARY:} key to a file, because Rsync jobs hand it to an
 * external {@code ssh}. Pins that the file is owner-only from the start and is gone once the
 * session is closed or its connect failed.
 */
class JobSchedulerTemporaryKeyFileTest {

    private SshServer server;

    @AfterMethod(alwaysRun = true)
    void stopServer() throws IOException {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void theKeyFileIsOwnerOnlyAndDeletedOnClose() throws Exception {
        KeyPair key = TemporaryKeyTestFixtures.ed25519KeyPair();
        startServer(key);
        String keyText = TemporaryKeyTestFixtures.openSshPrivateKey(key);

        Path keyFile;
        try (JobSchedulerRemoteSession session = new JobSchedulerRemoteSession(
            null, temporaryKeyConnection(keyText), null, null, true)) {
            session.connect();
            assertThat(session.isConnected()).isTrue();

            keyFile = session.externalSshAuthMaterial().privateKeyPath().orElseThrow();
            assertThat(keyFile.getFileName().toString()).startsWith("kortty_scheduler_key_");
            assertThat(Files.readString(keyFile)).isEqualTo(keyText);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertThat(Files.getPosixFilePermissions(keyFile))
                    .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        }

        assertThat(Files.exists(keyFile)).isFalse();
    }

    @Test
    void aFailedConnectLeavesNoKeyFileBehind() throws Exception {
        startServer(TemporaryKeyTestFixtures.ed25519KeyPair());
        String rejectedKey = TemporaryKeyTestFixtures.openSshPrivateKey(TemporaryKeyTestFixtures.ed25519KeyPair());
        Set<String> before = TemporaryKeyTestFixtures.tempFolderEntries("kortty_scheduler_key_");

        JobSchedulerRemoteSession session = new JobSchedulerRemoteSession(
            null, temporaryKeyConnection(rejectedKey), null, null, true);
        try {
            session.connect();
            throw new AssertionError("A key the server does not accept must fail the connect");
        } catch (Exception expected) {
            // Deliberately no close(): JobSchedulerDialog does not close a session whose connect failed.
            assertThat(session.externalSshAuthMaterial().privateKeyPath()).isEmpty();
            assertThat(TemporaryKeyTestFixtures.tempFolderEntries("kortty_scheduler_key_")).isEqualTo(before);
        }
    }

    @Test
    void theOwnerOnlyFileHasTheNameTheStartupSweepLooksFor() throws Exception {
        Path file = JobSchedulerRemoteSession.createOwnerOnlyTempFile();
        try {
            assertThat(file.getFileName().toString()).matches("kortty_scheduler_key_\\d{1,20}\\.key");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void startServer(KeyPair accepted) throws Exception {
        Path tmp = Files.createTempDirectory("kortty-scheduler-temp-key-");
        server = TemporaryKeyTestFixtures.publicKeyOnlyServer(tmp.resolve("host.ser"), accepted.getPublic());
        server.start();
    }

    private ServerConnection temporaryKeyConnection(String keyText) {
        ServerConnection connection = new ServerConnection("scheduler-temp-key", "127.0.0.1", server.getPort(), "tester");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath(TemporarySshKeyMaterial.PREFIX + keyText);
        return connection;
    }
}
