package de.kortty.jobscheduler;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerRsyncSupportTest {

    @Test
    void createsAskpassAndKnownHostsFilesForPasswordAuthAndCleansThemUp() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        JobSchedulerRsyncSupport support = new JobSchedulerRsyncSupport(null, new FixedToolLocator(), executor);
        ScheduledJob job = new ScheduledJob();
        job.setHostKeyVerificationDisabled(false);
        JobAction action = job.getAction();
        action.setType(JobActionType.RSYNC_SYNC);
        action.setRsyncDirection(RsyncDirection.UPLOAD);
        action.setRsyncSourcePaths(List.of("/tmp/source"));
        action.setRsyncTargetRoot("/srv/target");
        ServerConnection connection = connection();
        PinnedHostKey hostKey = new PinnedHostKey();
        hostKey.setConnectionId(connection.getId());
        hostKey.setHost(connection.getHost());
        hostKey.setPort(connection.getPort());
        hostKey.setPublicKeyLine("ssh-ed25519 AAAATEST");
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();

        JobExecutionOutcome outcome = support.run(
            job,
            connection,
            hostKey,
            new JobSchedulerRemoteSession.ExternalSshAuthMaterial(
                AuthMethod.PASSWORD,
                Optional.of("ssh-secret"),
                Optional.empty(),
                Optional.empty()),
            1,
            redactor);

        assertThat(outcome.status()).isEqualTo(JobRunStatus.SUCCESS);
        assertThat(executor.command).isNotEmpty();
        assertThat(executor.askpassFile).isNotNull();
        assertThat(executor.secretFile).isNotNull();
        assertThat(executor.knownHostsFile).isNotNull();
        assertThat(Files.exists(executor.askpassFile)).isFalse();
        assertThat(Files.exists(executor.secretFile)).isFalse();
        assertThat(Files.exists(executor.knownHostsFile)).isFalse();
        assertThat(redactor.redact(outcome.detail())).doesNotContain(executor.knownHostsFile.toString());
    }

    @Test
    void blocksPinnedHostKeysWithoutOpenSshPublicKeyMaterial() throws Exception {
        JobSchedulerRsyncSupport support = new JobSchedulerRsyncSupport(null, new FixedToolLocator(), new CapturingExecutor());
        ScheduledJob job = new ScheduledJob();
        job.getAction().setType(JobActionType.RSYNC_SYNC);
        job.getAction().setRsyncDirection(RsyncDirection.UPLOAD);
        job.getAction().setRsyncSourcePaths(List.of("/tmp/source"));
        job.getAction().setRsyncTargetRoot("/srv/target");

        try {
            support.run(
                job,
                connection(),
                new PinnedHostKey(),
                new JobSchedulerRemoteSession.ExternalSshAuthMaterial(
                    AuthMethod.PUBLIC_KEY,
                    Optional.empty(),
                    Optional.of(Path.of("/tmp/key")),
                    Optional.empty()),
                1,
                new JobSchedulerSecretRedactor());
            throw new AssertionError("Expected Rsync to block old host-key pins.");
        } catch (JobBlockedException expected) {
            assertThat(expected.getMessage()).contains("Confirm the host key again");
        }
    }

    private ServerConnection connection() {
        ServerConnection connection = new ServerConnection();
        connection.setId("12345678-1234");
        connection.setName("Fedora44");
        connection.setHost("example.test");
        connection.setPort(2222);
        connection.setUsername("daniel");
        connection.setAuthMethod(AuthMethod.PASSWORD);
        return connection;
    }

    private static final class FixedToolLocator extends RsyncToolLocator {
        @Override
        String resolveRsync(String configuredPath) {
            return "/usr/bin/rsync";
        }

        @Override
        String resolveSsh() {
            return "/usr/bin/ssh";
        }
    }

    private static final class CapturingExecutor implements RsyncProcessExecutor {
        private static final Pattern KNOWN_HOSTS_PATTERN = Pattern.compile("UserKnownHostsFile=([^']+)");

        private List<String> command;
        private Path askpassFile;
        private Path secretFile;
        private Path knownHostsFile;

        @Override
        public RsyncProcessResult execute(List<String> command, Map<String, String> environment) throws Exception {
            this.command = command;
            askpassFile = Path.of(environment.get("SSH_ASKPASS"));
            secretFile = Path.of(environment.get("KORTTY_RSYNC_ASKPASS_SECRET_FILE"));
            assertThat(Files.readString(secretFile)).contains("ssh-secret");
            assertThat(Files.exists(askpassFile)).isTrue();
            Matcher matcher = KNOWN_HOSTS_PATTERN.matcher(command.get(command.indexOf("-e") + 1));
            assertThat(matcher.find()).isTrue();
            knownHostsFile = Path.of(matcher.group(1));
            assertThat(Files.readString(knownHostsFile)).contains("ssh-ed25519 AAAATEST");
            return new RsyncProcessResult(0, "synced", "");
        }
    }
}
