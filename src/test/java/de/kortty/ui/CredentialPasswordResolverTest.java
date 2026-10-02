package de.kortty.ui;

import de.kortty.core.CredentialManager;
import de.kortty.model.StoredCredential;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

class CredentialPasswordResolverTest {

    private static final char[] MASTER = "master-password".toCharArray();

    /** Collects UI-thread callbacks so the test decides when (and in which order) they run. */
    private final BlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();

    @Test(timeOut = 60_000)
    void staleResultOfAnEarlierSelectionIsDropped() throws Exception {
        CredentialManager manager = newManager();
        StoredCredential first = externalCommandCredential(manager, "first", "echo first-secret");
        StoredCredential second = externalCommandCredential(manager, "second", "echo second-secret");
        CredentialPasswordResolver resolver = new CredentialPasswordResolver(uiQueue::add);
        RecordingListener listener = new RecordingListener();

        resolver.resolve(manager, first, MASTER, listener);
        resolver.resolve(manager, second, MASTER, listener);
        assertThat(resolver.isPending()).isTrue();
        runUiCallbacks(2);

        assertThat(listener.events).containsExactly("started first", "started second", "resolved second second-secret")
            .inOrder();
        assertThat(resolver.isPending()).isFalse();
    }

    @Test(timeOut = 60_000)
    void cancelDropsTheResultInFlight() throws Exception {
        CredentialManager manager = newManager();
        StoredCredential credential = externalCommandCredential(manager, "vault", "echo secret");
        CredentialPasswordResolver resolver = new CredentialPasswordResolver(uiQueue::add);
        RecordingListener listener = new RecordingListener();

        resolver.resolve(manager, credential, MASTER, listener);
        resolver.cancel();
        assertThat(resolver.isPending()).isFalse();
        runUiCallbacks(1);

        assertThat(listener.events).containsExactly("started vault");
    }

    @Test(timeOut = 60_000)
    void commandFailureIsDeliveredUnwrapped() throws Exception {
        CredentialManager manager = newManager();
        StoredCredential credential = externalCommandCredential(manager, "broken", "exit 3");
        CredentialPasswordResolver resolver = new CredentialPasswordResolver(uiQueue::add);
        RecordingListener listener = new RecordingListener();

        resolver.resolve(manager, credential, MASTER, listener);
        runUiCallbacks(1);

        assertThat(listener.events).containsExactly("started broken", "failed broken IOException").inOrder();
    }

    @Test
    void storedPasswordResolvesWithoutABackgroundTask() throws Exception {
        CredentialManager manager = newManager();
        StoredCredential credential = new StoredCredential();
        credential.setName("stored");
        credential.setUsername("deploy");
        credential.setPasswordType(StoredCredential.PasswordType.STORED);
        manager.setPassword(credential, "s3cret", MASTER);
        CredentialPasswordResolver resolver = new CredentialPasswordResolver(uiQueue::add);
        RecordingListener listener = new RecordingListener();

        resolver.resolve(manager, credential, MASTER, listener);

        assertThat(listener.events).containsExactly("resolved stored s3cret");
        assertThat(resolver.isPending()).isFalse();
        assertThat(uiQueue).isEmpty();
    }

    private void runUiCallbacks(int expected) throws InterruptedException {
        for (int i = 0; i < expected; i++) {
            Runnable callback = uiQueue.poll(30, TimeUnit.SECONDS);
            assertThat(callback).isNotNull();
            callback.run();
        }
    }

    private static CredentialManager newManager() throws IOException {
        return new CredentialManager(Files.createTempDirectory("kortty-credential-resolver-"));
    }

    /** {@code echo} and {@code exit} behave the same in sh and PowerShell, so these run everywhere. */
    private static StoredCredential externalCommandCredential(CredentialManager manager, String name, String command)
        throws Exception {
        StoredCredential credential = new StoredCredential();
        credential.setName(name);
        credential.setUsername("deploy");
        credential.setPasswordType(StoredCredential.PasswordType.EXTERNAL_COMMAND);
        manager.setExternalCommand(credential, command, MASTER);
        return credential;
    }

    private static final class RecordingListener implements CredentialPasswordResolver.Listener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void started(StoredCredential credential) {
            events.add("started " + credential.getName());
        }

        @Override
        public void resolved(StoredCredential credential, String password) {
            events.add("resolved " + credential.getName() + " " + password);
        }

        @Override
        public void failed(StoredCredential credential, Exception error) {
            events.add("failed " + credential.getName() + " " + error.getClass().getSimpleName());
        }
    }
}
